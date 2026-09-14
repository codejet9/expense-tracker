package com.dabhiram.expensetracker.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dabhiram.expensetracker.BuildConfig
import com.dabhiram.expensetracker.data.model.MerchantRule
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.ui.viewmodel.SettingsViewModel

private const val MERCHANT_RULES_PAGE_SIZE = 10
private const val BUDGET_PAGE_SIZE = 5

@Composable
fun SettingsScreen(
    repository: TransactionRepository,
    onViewLogs: () -> Unit
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(repository))
    val rules by vm.merchantRules.collectAsState()
    val categories by vm.categories.collectAsState()
    val categoriesWithBudgets by vm.categoriesWithBudgets.collectAsState()
    val openAiKey by vm.openAiKey.collectAsState()
    val groqKey by vm.groqKey.collectAsState()
    val geminiKey by vm.geminiKey.collectAsState()
    val llmEnabled by vm.llmEnabled.collectAsState()
    val defaultSplitPeople by vm.defaultSplitPeople.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadApiKeyState(context) }

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var budgetPage by remember { mutableStateOf(0) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFromCsv(context, it) { _, msg -> exportMessage = msg } }
    }
    var rulesPage by remember { mutableStateOf(0) }
    LaunchedEffect(rules.size) {
        val pageCount = ((rules.size + MERCHANT_RULES_PAGE_SIZE - 1) / MERCHANT_RULES_PAGE_SIZE).coerceAtLeast(1)
        if (rulesPage >= pageCount) rulesPage = pageCount - 1
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ServiceStatusCard(context = context)
            }

            item {
                BatteryOptimizationCard(context = context)
            }

            item {
                CollapsibleSettingsCard(title = "How Transactions Get Captured") {
                    HowCaptureWorksContent()
                }
            }

            item {
                CollapsibleSettingsCard(
                    title = "AI Categorization & API Keys",
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                ) {
                    AiProvidersContent(
                        openAiKey = openAiKey,
                        groqKey = groqKey,
                        geminiKey = geminiKey,
                        enabled = llmEnabled,
                        onSaveOpenAiKey = { vm.saveOpenAiKey(context, it) },
                        onSaveGroqKey = { vm.saveGroqKey(context, it) },
                        onSaveGeminiKey = { vm.saveGeminiKey(context, it) },
                        onToggle = { vm.setLlmEnabled(context, it) }
                    )
                }
            }

            item {
                SplitDefaultsCard(
                    defaultPeople = defaultSplitPeople,
                    onDecrement = { if (defaultSplitPeople > 2) vm.saveDefaultSplitPeople(context, defaultSplitPeople - 1) },
                    onIncrement = { vm.saveDefaultSplitPeople(context, defaultSplitPeople + 1) }
                )
            }

            item {
                ActionCard(
                    title = "Export Transactions",
                    subtitle = "Save all transactions to a CSV file in Downloads",
                    icon = Icons.Default.FileDownload,
                    buttonLabel = "Export CSV"
                ) {
                    vm.exportToCsv(context) { _, msg ->
                        exportMessage = msg
                    }
                }
            }

            item {
                ActionCard(
                    title = "Import Transactions",
                    subtitle = "Restore transactions from a previously exported CSV",
                    icon = Icons.Default.FileUpload,
                    buttonLabel = "Import CSV"
                ) {
                    importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*"))
                }
            }

            item {
                ActionCard(
                    title = "Accessibility Logs",
                    subtitle = "View raw view tree dumps from GPay / PhonePe",
                    icon = Icons.AutoMirrored.Filled.TextSnippet,
                    buttonLabel = "View Logs",
                    onClick = onViewLogs
                )
            }

            if (BuildConfig.DEBUG) {
                item {
                    DebugSeedCard { vm.seedTestTransactions() }
                }
            }

            item {
                val budgetPageCount = ((categoriesWithBudgets.size + BUDGET_PAGE_SIZE - 1) / BUDGET_PAGE_SIZE).coerceAtLeast(1)
                LaunchedEffect(categoriesWithBudgets.size) {
                    if (budgetPage >= budgetPageCount) budgetPage = (budgetPageCount - 1).coerceAtLeast(0)
                }
                CollapsibleSettingsCard(
                    title = "Category Budgets",
                    subtitle = "Monthly limits"
                ) {
                    val pagedCats = categoriesWithBudgets
                        .drop(budgetPage * BUDGET_PAGE_SIZE)
                        .take(BUDGET_PAGE_SIZE)

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        pagedCats.forEach { cat ->
                            BudgetFieldRow(
                                categoryName = cat.name,
                                currentBudget = cat.budget,
                                onSave = { vm.saveCategoryBudget(cat.name, it) }
                            )
                        }

                        if (budgetPageCount > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { budgetPage-- },
                                    enabled = budgetPage > 0
                                ) { Text("← Prev") }
                                Text(
                                    "Page ${budgetPage + 1} of $budgetPageCount",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(
                                    onClick = { budgetPage++ },
                                    enabled = budgetPage < budgetPageCount - 1
                                ) { Text("Next →") }
                            }
                        }

                        Text(
                            "Leave blank = no limit. Changes save automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                CollapsibleSettingsCard(
                    title = "Merchant Rules",
                    trailingAction = {
                        IconButton(onClick = { showAddRuleDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add rule")
                        }
                    }
                ) {
                    val pageCount = ((rules.size + MERCHANT_RULES_PAGE_SIZE - 1) / MERCHANT_RULES_PAGE_SIZE).coerceAtLeast(1)
                    val pagedRules = rules.drop(rulesPage * MERCHANT_RULES_PAGE_SIZE).take(MERCHANT_RULES_PAGE_SIZE)

                    Column {
                        Text(
                            "Matched against VPA or recipient name to auto-categorize payments.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        pagedRules.forEach { rule ->
                            MerchantRuleItem(rule = rule, onDelete = { vm.deleteMerchantRule(rule) })
                        }
                        if (pageCount > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { rulesPage-- }, enabled = rulesPage > 0) { Text("← Prev") }
                                Text("Page ${rulesPage + 1} of $pageCount", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { rulesPage++ }, enabled = rulesPage < pageCount - 1) { Text("Next →") }
                            }
                        }
                    }
                }
            }
        }

        exportMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                exportMessage = null
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = { exportMessage = null }) { Text("OK") } }
            ) {
                Text(msg)
            }
        }
    }

    if (showAddRuleDialog) {
        AddMerchantRuleDialog(
            categories = categories,
            onDismiss = { showAddRuleDialog = false },
            onAdd = { pattern, category ->
                vm.addMerchantRule(pattern, category)
                showAddRuleDialog = false
            }
        )
    }
}

@Composable
private fun AiProvidersContent(
    openAiKey: String,
    groqKey: String,
    geminiKey: String,
    enabled: Boolean,
    onSaveOpenAiKey: (String) -> Unit,
    onSaveGroqKey: (String) -> Unit,
    onSaveGeminiKey: (String) -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val hasAnyKey = openAiKey.isNotBlank() || groqKey.isNotBlank() || geminiKey.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Enable AI categorization",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = hasAnyKey
            )
        }

        Text(
            "Tried in order — OpenAI, then Groq, then Gemini — whichever keys are configured, falling back to the next if one fails. Only the VPA and recipient name (or OCR text) are sent, never amounts or other personal data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!hasAnyKey) {
            Text(
                "Add at least one API key below to turn this on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider()
        ApiKeyField(
            label = "OpenAI API Key",
            placeholder = "sk-...",
            apiKey = openAiKey,
            onSaveKey = onSaveOpenAiKey,
            helpText = "platform.openai.com/api-keys — paid, cheapest models (e.g. gpt-5-nano) cost fractions of a cent per call"
        )
        HorizontalDivider()
        ApiKeyField(
            label = "Groq API Key",
            placeholder = "gsk_...",
            apiKey = groqKey,
            onSaveKey = onSaveGroqKey,
            helpText = "console.groq.com/keys — free tier, generous daily limit"
        )
        HorizontalDivider()
        ApiKeyField(
            label = "Gemini API Key",
            placeholder = "AIzaSy...",
            apiKey = geminiKey,
            onSaveKey = onSaveGeminiKey,
            helpText = "aistudio.google.com/app/apikey — free tier"
        )
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    placeholder: String,
    apiKey: String,
    onSaveKey: (String) -> Unit,
    helpText: String
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(apiKey) { mutableStateOf(apiKey) }
    var showKey by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = if (editing) draft else apiKey,
            onValueChange = { draft = it },
            readOnly = !editing,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(
            helpText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (editing) {
                TextButton(onClick = { editing = false; draft = apiKey }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSaveKey(draft); editing = false }) { Text("Save") }
            } else {
                OutlinedButton(onClick = { editing = true }) {
                    Text(if (apiKey.isBlank()) "Add Key" else "Change Key")
                }
            }
        }
    }
}

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    return enabled.contains("com.dabhiram.expensetracker/com.dabhiram.expensetracker.service.UpiAccessibilityService")
}

@Composable
private fun ServiceStatusCard(context: android.content.Context) {
    var isEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isEnabled) "Service Active" else "Service Disabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (isEnabled) "Monitoring Google Pay and PhonePe"
                    else "Tap Enable to start tracking UPI payments",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!isEnabled) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                ) {
                    Text("Enable")
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(context: android.content.Context) {
    fun isExempt(): Boolean {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var isExempt by remember { mutableStateOf(isExempt()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = isExempt()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isExempt) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Battery optimization is on",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "The OS can silently kill capture in the background. Exempt this app to keep it running reliably.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            ) {
                Text("Exempt")
            }
        }
    }
}

@Composable
private fun HowCaptureWorksContent() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "• Auto-capture: after paying in GPay/PhonePe, return to the recipient's chat/history screen before leaving the app — that's the only screen this can read. The PIN-entry, processing, and success screens are locked down by the payment app itself.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "• Screenshot import: share a payment screenshot (e.g. from the success screen) to this app via the share sheet — Gemini reads the amount and recipient directly from the image.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DebugSeedCard(onSeed: () -> Unit) {
    var seeded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Debug: Seed Test Data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (seeded) "8 test transactions inserted" else "Insert sample transactions to test the UI",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onSeed(); seeded = true },
                enabled = !seeded
            ) {
                Text("Seed")
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClick) {
                Text(buttonLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MerchantRuleItem(rule: MerchantRule, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(rule.pattern) },
        supportingContent = {
            Row {
                CategoryChip(rule.category)
                if (rule.isUserAdded) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            "Custom",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        trailingContent = {
            if (rule.isUserAdded) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete rule", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMerchantRuleDialog(
    categories: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Other") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Merchant Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Pattern (e.g. swiggy, zomato)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    selectedCategory = cat
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(pattern, selectedCategory) },
                enabled = pattern.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CollapsibleSettingsCard(
    title: String,
    subtitle: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                leadingIcon?.invoke()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailingAction?.invoke()
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun BudgetFieldRow(
    categoryName: String,
    currentBudget: String?,
    onSave: (String?) -> Unit
) {
    var draft by remember(currentBudget) { mutableStateOf(currentBudget ?: "") }
    var wasFocused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            categoryName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.filter { c -> c.isDigit() || c == '.' } },
            prefix = { if (draft.isNotBlank()) Text("₹") },
            placeholder = { Text("No limit", style = MaterialTheme.typography.bodySmall) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSave(draft.takeIf { it.isNotBlank() }) }
            ),
            modifier = Modifier
                .width(140.dp)
                .onFocusChanged { focusState ->
                    if (wasFocused && !focusState.isFocused) {
                        onSave(draft.takeIf { it.isNotBlank() })
                    }
                    wasFocused = focusState.isFocused
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SplitDefaultsCard(
    defaultPeople: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Split Defaults", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Default number of people when splitting an expense",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default split people", style = MaterialTheme.typography.bodyMedium)
                    Text("Including you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDecrement, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Fewer people")
                }
                Text(
                    "$defaultPeople",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = onIncrement, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "More people")
                }
            }
        }
    }
}
