package com.dabhiram.expensetracker.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dabhiram.expensetracker.data.model.Category
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.MerchantRule
import com.dabhiram.expensetracker.data.model.SourceApp
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.llm.ApiKeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t', '\r')

class SettingsViewModel(
    private val repository: TransactionRepository
) : ViewModel() {

    val merchantRules: StateFlow<List<MerchantRule>> = repository.merchantRules
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val categoriesWithBudgets: StateFlow<List<Category>> = repository.allCategoriesFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _openAiKey = MutableStateFlow("")
    val openAiKey: StateFlow<String> = _openAiKey.asStateFlow()

    private val _groqKey = MutableStateFlow("")
    val groqKey: StateFlow<String> = _groqKey.asStateFlow()

    private val _geminiKey = MutableStateFlow("")
    val geminiKey: StateFlow<String> = _geminiKey.asStateFlow()

    private val _llmEnabled = MutableStateFlow(false)
    val llmEnabled: StateFlow<Boolean> = _llmEnabled.asStateFlow()

    private val _defaultSplitPeople = MutableStateFlow(2)
    val defaultSplitPeople: StateFlow<Int> = _defaultSplitPeople.asStateFlow()

    fun loadApiKeyState(context: Context) {
        _openAiKey.value = ApiKeyManager.getOpenAiKey(context)
        _groqKey.value = ApiKeyManager.getGroqKey(context)
        _geminiKey.value = ApiKeyManager.getGeminiKey(context)
        _llmEnabled.value = ApiKeyManager.isEnabled(context)
        _defaultSplitPeople.value = ApiKeyManager.getDefaultSplitPeople(context)
    }

    fun saveDefaultSplitPeople(context: Context, count: Int) {
        ApiKeyManager.setDefaultSplitPeople(context, count)
        _defaultSplitPeople.value = count.coerceAtLeast(2)
    }

    fun saveOpenAiKey(context: Context, key: String) {
        ApiKeyManager.setOpenAiKey(context, key)
        _openAiKey.value = key.trim()
        _llmEnabled.value = ApiKeyManager.isEnabled(context)
    }

    fun saveGroqKey(context: Context, key: String) {
        ApiKeyManager.setGroqKey(context, key)
        _groqKey.value = key.trim()
        _llmEnabled.value = ApiKeyManager.isEnabled(context)
    }

    fun saveGeminiKey(context: Context, key: String) {
        ApiKeyManager.setGeminiKey(context, key)
        _geminiKey.value = key.trim()
        _llmEnabled.value = ApiKeyManager.isEnabled(context)
    }

    fun setLlmEnabled(context: Context, enabled: Boolean) {
        ApiKeyManager.setEnabled(context, enabled)
        _llmEnabled.value = enabled && ApiKeyManager.hasAnyKey(context)
    }

    fun addMerchantRule(pattern: String, category: String) {
        if (pattern.isBlank()) return
        viewModelScope.launch {
            repository.upsertMerchantRule(MerchantRule(pattern.trim().lowercase(), category, true))
        }
    }

    fun deleteMerchantRule(rule: MerchantRule) {
        viewModelScope.launch {
            repository.deleteMerchantRule(rule)
        }
    }

    fun saveCategoryBudget(name: String, budget: String?) {
        viewModelScope.launch {
            val trimmed = budget?.trim()?.takeIf { it.isNotBlank() }
            repository.updateCategoryBudget(name, trimmed)
        }
    }

    fun seedTestTransactions() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val hour = 3_600_000L
            val testRows = listOf(
                Transaction("SEED001", "450.00",  "Swiggy",               "swiggy@icici",         SourceApp.GPAY,    now - 1*hour,  "Food",              CategorizedBy.AUTO_MERCHANT, "seed"),
                Transaction("SEED002", "1200.00", "Zomato",               "zomato@icici",         SourceApp.PHONEPE, now - 3*hour,  "Food",              CategorizedBy.AUTO_MERCHANT, "seed"),
                Transaction("SEED003", "85.00",   "Rapido",               "rapido@ybl",           SourceApp.GPAY,    now - 5*hour,  "Travel",            CategorizedBy.AUTO_MERCHANT, "seed"),
                Transaction("SEED004", "299.00",  "Netflix",              "netflix@icici",        SourceApp.GPAY,    now - 8*hour,  "Entertainment",     CategorizedBy.AUTO_MERCHANT, "seed"),
                Transaction("SEED005", "3500.00", "Rahul Sharma",         "9876543210@oksbi",     SourceApp.PHONEPE, now - 12*hour, "Uncategorized",     CategorizedBy.UNRESOLVED,    "seed"),
                Transaction("SEED006", "650.00",  "Blinkit",              "blinkit@icici",        SourceApp.GPAY,    now - 24*hour, "Groceries",         CategorizedBy.AUTO_MERCHANT, "seed"),
                Transaction("SEED007", "180.00",  "Unknown Merchant",     "qrcode1234@paytm",     SourceApp.PHONEPE, now - 26*hour, "Uncategorized",     CategorizedBy.UNRESOLVED,    "seed"),
                Transaction("SEED008", "12000.00","BESCOM",               "bescom@sbi",           SourceApp.GPAY,    now - 48*hour, "Utilities",         CategorizedBy.AUTO_MERCHANT, "seed"),
                Transaction("SEED009", "800.00",  "Priya Lunch",          "9988776655@oksbi",     SourceApp.GPAY,    now - 2*hour,  "Lent",              CategorizedBy.USER,          "seed")
            )
            testRows.forEach { repository.insert(it) }
        }
    }

    fun exportToCsv(context: Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val transactions = repository.exportAllTransactions()
                if (transactions.isEmpty()) {
                    onResult(false, "No transactions to export")
                    return@launch
                }

                val csv = buildString {
                    appendLine("Date,Time,Amount,Recipient,VPA,Category,Source,Ref,CategorizedBy")
                    val sdf = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss", Locale.US)
                    transactions.forEach { t ->
                        val dt = sdf.format(Date(t.timestamp))
                        val row = listOf(
                            dt,
                            t.amount,
                            t.recipientName.csvEscape(),
                            (t.recipientVpa ?: "").csvEscape(),
                            t.category.csvEscape(),
                            t.sourceApp.name,
                            t.id.csvEscape(),
                            t.categorizedBy.name
                        ).joinToString(",")
                        appendLine(row)
                    }
                }

                val fileName = "expenses_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(csv.toByteArray())
                        }
                        onResult(true, "Saved to Downloads/$fileName")
                    } ?: onResult(false, "Failed to create file")
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir
                    dir.mkdirs()
                    val file = java.io.File(dir, fileName)
                    file.writeText(csv)
                    onResult(true, "Saved to ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Export failed", e)
                onResult(false, "Export failed: ${e.message}")
            }
        }
    }

    fun importFromCsv(context: Context, uri: android.net.Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readLines()
                    ?: run { onResult(false, "Could not read file"); return@launch }

                if (lines.isEmpty()) { onResult(false, "File is empty"); return@launch }

                // Validate header: Date,Time,Amount,Recipient,VPA,Category,Source,Ref,CategorizedBy
                val header = lines.first().trim()
                if (!header.startsWith("Date,Time,Amount")) {
                    onResult(false, "Unrecognized CSV format — export from this app first")
                    return@launch
                }

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                var imported = 0
                var skipped = 0

                // CSV columns: 0=Date, 1=Time, 2=Amount, 3=Recipient, 4=VPA,
                //              5=Category, 6=Source, 7=Ref, 8=CategorizedBy
                for (line in lines.drop(1)) {
                    if (line.isBlank()) continue
                    val cols = parseCsvLine(line)
                    if (cols.size < 8) { skipped++; continue }
                    runCatching {
                        val timestamp = sdf.parse("${cols[0]} ${cols[1]}")?.time
                            ?: run { skipped++; return@runCatching }
                        val amount = cols[2].toBigDecimalOrNull()
                            ?: run { skipped++; return@runCatching }
                        val txn = Transaction(
                            id = cols[7].ifBlank { "IMPORT_${timestamp}_${System.nanoTime()}" },
                            amount = amount.toPlainString(),
                            recipientName = cols[3],
                            recipientVpa = cols[4].ifBlank { null },
                            sourceApp = runCatching { SourceApp.valueOf(cols[6]) }.getOrDefault(SourceApp.GPAY),
                            timestamp = timestamp,
                            category = cols[5].ifBlank { "Other" },
                            categorizedBy = runCatching { CategorizedBy.valueOf(cols.getOrElse(8) { "USER" }) }.getOrDefault(CategorizedBy.USER),
                            rawDump = "Imported from CSV"
                        )
                        repository.insert(txn)
                        imported++
                    }
                }
                onResult(true, "Imported $imported transactions${if (skipped > 0) " ($skipped skipped)" else ""}")
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Import failed", e)
                onResult(false, "Import failed: ${e.message}")
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i += 2
                }
                line[i] == '"' -> { inQuotes = !inQuotes; i++ }
                line[i] == ',' && !inQuotes -> { result.add(current.toString()); current.clear(); i++ }
                else -> { current.append(line[i]); i++ }
            }
        }
        result.add(current.toString())
        return result
    }

    private fun String.csvEscape(): String {
        // Neutralize formula-trigger leading characters (CSV/Excel/Sheets formula
        // injection): a recipient name or VPA is attacker-influenced — any UPI
        // counterparty can set their own display name to a formula payload.
        val safe = if (isNotEmpty() && first() in FORMULA_TRIGGER_CHARS) "'$this" else this
        return if (safe.contains(',') || safe.contains('"') || safe.contains('\n')) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }

    class Factory(private val repository: TransactionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}
