package com.dabhiram.expensetracker.share

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.dabhiram.expensetracker.categorizer.CategorizationResult
import com.dabhiram.expensetracker.categorizer.Categorizer
import com.dabhiram.expensetracker.categorizer.DuplicateDetector
import com.dabhiram.expensetracker.categorizer.knownMappings
import com.dabhiram.expensetracker.data.TransactionEventBus
import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.SourceApp
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.llm.ApiKeyManager
import com.dabhiram.expensetracker.llm.LlmCategorizer
import com.dabhiram.expensetracker.llm.LlmScreenshotTextAnalyzer
import com.dabhiram.expensetracker.parser.ScreenshotTextParser
import com.dabhiram.expensetracker.ui.theme.ExpenseTrackerTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal

private sealed class AnalyzeState {
    object Loading : AnalyzeState()
    object NoImage : AnalyzeState()
    data class Failed(val message: String) : AnalyzeState()
    data class Ready(
        val amount: BigDecimal,
        val recipientName: String,
        val recipientVpa: String?,
        val referenceNumber: String?,
        val category: String,
        val categorizedBy: CategorizedBy,
        val confidence: Int?,
        val duplicateOf: Transaction?,
        val amountUncertain: Boolean
    ) : AnalyzeState()
}

class ScreenshotShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        setContent {
            ExpenseTrackerTheme {
                ScreenshotReviewScreen(
                    imageUri = imageUri,
                    onDone = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenshotReviewScreen(imageUri: Uri?, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var state by remember { mutableStateOf<AnalyzeState>(if (imageUri == null) AnalyzeState.NoImage else AnalyzeState.Loading) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    val categories by AppDatabase.getInstance(context).categoryDao().getAllFlow()
        .map { list -> list.map { it.name } }
        .collectAsState(initial = emptyList())

    val thumbnail = remember(imageUri) {
        imageUri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        }
    }

    LaunchedEffect(imageUri, thumbnail) {
        if (imageUri == null) return@LaunchedEffect

        if (thumbnail == null) {
            state = AnalyzeState.Failed("Couldn't read that image file.")
            return@LaunchedEffect
        }

        val lines = runCatching { ScreenshotTextParser.recognizeLines(thumbnail) }.getOrNull()
        writeOcrDumpLog(context, lines)
        val ocrResult = lines?.let { ScreenshotTextParser.parse(it) }

        if (ocrResult == null) {
            state = AnalyzeState.Failed("Couldn't find a payment amount in this screenshot — is it a payment confirmation?")
            return@LaunchedEffect
        }

        val db = AppDatabase.getInstance(context)
        val vpaCache = db.vpaCategoryDao().getAll()
        val merchantRules = db.merchantRuleDao().getAll()
        val categories = db.categoryDao().getAll().map { it.name }
        val since = System.currentTimeMillis() - 3 * 24L * 60 * 60 * 1000
        val recentTransactions = db.transactionDao().getRecentCategorized(since, 30)

        // OCR is the source of truth (VPA in particular is far more reliable
        // from regex than free-form AI reading), but amount/recipient/category
        // go through the AI provider chain first when available — it can
        // reconcile OCR's currency-symbol corruption using language
        // understanding, something no amount of hand-rolled regex heuristics
        // fully solves. Falls back to the pure-OCR heuristic result whenever
        // every provider is disabled, fails, or times out.
        val aiExtraction = if (lines != null && ApiKeyManager.isEnabled(context)) {
            LlmScreenshotTextAnalyzer.extract(
                ocrLines = lines,
                categories = categories,
                keys = ApiKeyManager.providerKeys(context),
                knownMappings = knownMappings(vpaCache),
                recentTransactions = recentTransactions
            )
        } else null

        val amount = aiExtraction?.amount ?: ocrResult.amount
        val recipientName = aiExtraction?.recipientName ?: ocrResult.recipientName
        val amountUncertain = aiExtraction == null && ocrResult.amountUncertain

        val ruleResult = Categorizer.categorize(
            vpa = ocrResult.recipientVpa,
            recipientName = recipientName,
            vpaCacheEntries = vpaCache,
            merchantRules = merchantRules,
            availableCategories = categories
        )

        val duplicate = DuplicateDetector.findRecent(
            db = db,
            amount = amount,
            recipientVpa = ocrResult.recipientVpa,
            recipientName = recipientName,
            timestamp = System.currentTimeMillis()
        )

        state = if (ruleResult is CategorizationResult.Categorized) {
            AnalyzeState.Ready(
                amount = amount,
                recipientName = recipientName,
                recipientVpa = ocrResult.recipientVpa,
                referenceNumber = ocrResult.referenceNumber,
                category = ruleResult.category,
                categorizedBy = ruleResult.by,
                confidence = null,
                duplicateOf = duplicate,
                amountUncertain = amountUncertain
            )
        } else {
            val needsInput = ruleResult as CategorizationResult.NeedsUserInput

            // Prefer the category the combined AI extraction already produced;
            // otherwise fall back to the narrow text-only categorizer (the
            // same one the accessibility path uses).
            val category: String
            val categorizedBy: CategorizedBy
            val confidence: Int?
            if (aiExtraction != null) {
                category = aiExtraction.category
                categorizedBy = CategorizedBy.AUTO_LLM
                confidence = aiExtraction.confidence
            } else {
                val llmResult = if (ApiKeyManager.isEnabled(context)) {
                    LlmCategorizer.categorize(
                        vpa = ocrResult.recipientVpa,
                        recipientName = recipientName,
                        amount = amount,
                        categories = categories,
                        keys = ApiKeyManager.providerKeys(context),
                        knownMappings = knownMappings(vpaCache),
                        recentTransactions = recentTransactions
                    )
                } else null

                if (llmResult != null) {
                    category = llmResult.category
                    categorizedBy = CategorizedBy.AUTO_LLM
                    confidence = llmResult.confidence
                } else {
                    category = needsInput.suggestedCategories.firstOrNull() ?: "Other"
                    categorizedBy = CategorizedBy.USER
                    confidence = null
                }
            }

            AnalyzeState.Ready(
                amount = amount,
                recipientName = recipientName,
                recipientVpa = ocrResult.recipientVpa,
                referenceNumber = ocrResult.referenceNumber,
                category = category,
                categorizedBy = categorizedBy,
                confidence = confidence,
                duplicateOf = duplicate,
                amountUncertain = amountUncertain
            )
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Import from screenshot") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Screenshot preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            }

            when (val s = state) {
                is AnalyzeState.NoImage -> {
                    Text("No image was shared.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
                is AnalyzeState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Analyzing screenshot…")
                    }
                }
                is AnalyzeState.Failed -> {
                    Text(s.message)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
                is AnalyzeState.Ready -> {
                    var amountText by remember { mutableStateOf(s.amount.toPlainString()) }
                    var recipientText by remember { mutableStateOf(s.recipientName) }
                    var category by remember { mutableStateOf(s.category) }

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (s.amountUncertain) {
                        Text(
                            "Couldn't fully verify this amount from the screenshot — double-check it before saving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = recipientText,
                        onValueChange = { recipientText = it },
                        label = { Text("Recipient") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(
                        expanded = showCategoryMenu,
                        onExpandedChange = { showCategoryMenu = it }
                    ) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryMenu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        DropdownMenu(
                            expanded = showCategoryMenu,
                            onDismissRequest = { showCategoryMenu = false }
                        ) {
                            categories.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        category = option
                                        showCategoryMenu = false
                                    }
                                )
                            }
                        }
                    }

                    if (s.confidence != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "AI suggestion confidence: ${s.confidence}%",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (s.duplicateOf != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Possible duplicate — already logged ₹${s.duplicateOf.amount} to ${s.duplicateOf.recipientName} (${s.duplicateOf.sourceApp.name.lowercase()})",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val parsedAmount = amountText.toBigDecimalOrNull()
                            if (parsedAmount == null) return@Button
                            scope.launch {
                                saveTransaction(
                                    context = context,
                                    amount = parsedAmount,
                                    recipientName = recipientText,
                                    recipientVpa = s.recipientVpa,
                                    referenceNumber = s.referenceNumber,
                                    category = category,
                                    categorizedBy = if (category == s.category) s.categorizedBy else CategorizedBy.USER
                                )
                                onDone()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (s.duplicateOf != null) "Save anyway" else "Save transaction")
                    }
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.duplicateOf != null) "Skip (already logged)" else "Cancel")
                    }
                }
            }
        }
    }
}

private fun writeOcrDumpLog(context: android.content.Context, lines: List<String>?) {
    try {
        val logDir = java.io.File(context.getExternalFilesDir(null), "logs")
        logDir.mkdirs()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val logFile = java.io.File(logDir, "ocr_$dateStr.txt")
        logFile.writeText(
            if (lines == null) "OCR FAILED (recognizeLines threw)"
            else lines.mapIndexed { i, l -> "[$i] $l" }.joinToString("\n")
        )
    } catch (e: Exception) {
        android.util.Log.e("ScreenshotShareActivity", "Failed to write OCR dump log", e)
    }
}

private suspend fun saveTransaction(
    context: android.content.Context,
    amount: BigDecimal,
    recipientName: String,
    recipientVpa: String?,
    referenceNumber: String?,
    category: String,
    categorizedBy: CategorizedBy
) {
    val db = AppDatabase.getInstance(context)
    val timestamp = System.currentTimeMillis()
    val ref = referenceNumber ?: "SCREENSHOT_${timestamp}_${amount.toPlainString()}"

    if (db.transactionDao().exists(ref)) return

    db.transactionDao().insert(
        Transaction(
            id = ref,
            amount = amount.toPlainString(),
            recipientName = recipientName,
            recipientVpa = recipientVpa,
            sourceApp = SourceApp.SCREENSHOT,
            timestamp = timestamp,
            category = category,
            categorizedBy = categorizedBy,
            rawDump = "Imported from shared screenshot"
        )
    )
    TransactionEventBus.notifyChanged()
}
