package com.dabhiram.expensetracker.service

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.dabhiram.expensetracker.MainActivity
import com.dabhiram.expensetracker.R
import com.dabhiram.expensetracker.categorizer.Categorizer
import com.dabhiram.expensetracker.categorizer.CategorizationResult
import com.dabhiram.expensetracker.categorizer.DuplicateDetector
import com.dabhiram.expensetracker.categorizer.knownMappings
import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.llm.ApiKeyManager
import com.dabhiram.expensetracker.llm.LlmCategorizer
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.CATEGORY_UNCATEGORIZED
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.notification.CHANNEL_SERVICE
import com.dabhiram.expensetracker.notification.NOTIFICATION_ID_SERVICE
import com.dabhiram.expensetracker.notification.TransactionNotificationManager
import com.dabhiram.expensetracker.parser.GPayParser
import com.dabhiram.expensetracker.parser.ParsedTransaction
import com.dabhiram.expensetracker.parser.PhonePeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class UpiAccessibilityService : AccessibilityService() {

    private val TAG = "UpiAccessibilityService"
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val recentRefs = ConcurrentHashMap<String, Long>()
    private val DEDUP_WINDOW_MS = 30_000L

    // GPay's list often settles into its final state (e.g. the new "Paid" row)
    // without dispatching another accessibility event, so we re-scan the node
    // tree a bit after the last event instead of relying solely on events.
    private val rescanHandler = Handler(Looper.getMainLooper())
    private val pendingRescans = ConcurrentHashMap<String, Runnable>()
    private val RESCAN_DELAY_MS = 1_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "UPI Accessibility Service connected")
        startForegroundWithNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != GPAY_PACKAGE && pkg != PHONEPE_PACKAGE) return

        val source = event.source ?: return
        val textNodes = mutableListOf<String>()
        collectTextNodes(source, textNodes)
        source.recycle()

        scheduleRescan(pkg)

        if (textNodes.isEmpty()) return

        val timestamp = System.currentTimeMillis()
        writeDumpLog(pkg, textNodes, timestamp)
        tryParseAndProcess(pkg, textNodes, timestamp)
    }

    // Debounced re-read of the current node tree, fired a moment after the
    // last event for this package. Catches screen states (like GPay's history
    // row updating after payment) that never trigger their own event.
    private fun scheduleRescan(pkg: String) {
        pendingRescans.remove(pkg)?.let { rescanHandler.removeCallbacks(it) }
        val runnable = Runnable { rescanNow(pkg) }
        pendingRescans[pkg] = runnable
        rescanHandler.postDelayed(runnable, RESCAN_DELAY_MS)
    }

    private fun rescanNow(pkg: String) {
        pendingRescans.remove(pkg)
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) {
            root.recycle()
            return
        }
        val textNodes = mutableListOf<String>()
        collectTextNodes(root, textNodes)
        root.recycle()
        if (textNodes.isEmpty()) return

        val timestamp = System.currentTimeMillis()
        writeDumpLog(pkg, textNodes, timestamp)
        tryParseAndProcess(pkg, textNodes, timestamp)
    }

    private fun tryParseAndProcess(pkg: String, textNodes: List<String>, timestamp: Long) {
        val parsed = when (pkg) {
            GPAY_PACKAGE -> GPayParser.parse(textNodes, timestamp)
            PHONEPE_PACKAGE -> PhonePeParser.parse(textNodes, timestamp)
            else -> null
        } ?: return

        if (isDuplicate(parsed.transactionRef)) return
        markSeen(parsed.transactionRef)

        scope.launch { processTransaction(parsed) }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (!text.isNullOrBlank()) result.add(normalizeWhitespace(text.trim()))
        if (!desc.isNullOrBlank() && desc != text) result.add(normalizeWhitespace(desc.trim()))

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTextNodes(child, result)
            child?.recycle()
        }
    }

    // Android's time formatter inserts U+202F (narrow no-break space) and
    // sometimes U+00A0 (no-break space) around "am"/"pm" — neither matches
    // regex `\s` or SimpleDateFormat's literal space, so parsers silently
    // fail to match otherwise-correct text. Normalize to a plain space.
    private fun normalizeWhitespace(text: String): String =
        text.replace(' ', ' ').replace(' ', ' ')

    private suspend fun processTransaction(parsed: ParsedTransaction) {
        val db = AppDatabase.getInstance(this)

        if (db.transactionDao().exists(parsed.transactionRef)) {
            Log.d(TAG, "Duplicate transaction skipped: ${parsed.transactionRef}")
            return
        }

        val existingDuplicate = DuplicateDetector.findRecent(
            db = db,
            amount = parsed.amount,
            recipientVpa = parsed.recipientVpa,
            recipientName = parsed.recipientName,
            timestamp = parsed.timestamp
        )
        if (existingDuplicate != null) {
            Log.d(TAG, "Skipping — already logged via ${existingDuplicate.sourceApp}: ${existingDuplicate.id}")
            return
        }

        val vpaCacheList = db.vpaCategoryDao().getAll()
        val merchantRules = db.merchantRuleDao().getAll()
        val categories = db.categoryDao().getAll().map { it.name }
        val since = System.currentTimeMillis() - 3 * 24L * 60 * 60 * 1000
        val recentTransactions = db.transactionDao().getRecentCategorized(since, 30)

        val ruleResult = Categorizer.categorize(
            vpa = parsed.recipientVpa,
            recipientName = parsed.recipientName,
            vpaCacheEntries = vpaCacheList,
            merchantRules = merchantRules,
            availableCategories = categories
        )

        // Determine final category and whether/how to ask the user.
        // VPA cache hits (user-confirmed) are trusted directly — no AI needed.
        // Everything else (merchant-rule matches or unknowns) always goes through AI
        // so that recurring patterns (same canteen every morning) get recognized.
        val category: String
        val categorizedBy: CategorizedBy
        var llmSuggestionForNotif: String? = null
        var needsUserInput = false
        var suggestedCategories: List<String> = emptyList()

        val vpaConfirmed = ruleResult is CategorizationResult.Categorized &&
            ruleResult.by == CategorizedBy.AUTO_VPA

        if (vpaConfirmed) {
            // User explicitly confirmed this VPA mapping — trust it, skip AI
            val confirmed = ruleResult as CategorizationResult.Categorized
            category = confirmed.category
            categorizedBy = confirmed.by
        } else if (ApiKeyManager.isEnabled(this)) {
            // Merchant-rule match or unknown — always run AI with recent history
            val ruleCategory = (ruleResult as? CategorizationResult.Categorized)?.category
            suggestedCategories = (ruleResult as? CategorizationResult.NeedsUserInput)?.suggestedCategories ?: emptyList()

            val llmResult = LlmCategorizer.categorize(
                vpa = parsed.recipientVpa,
                recipientName = parsed.recipientName,
                amount = parsed.amount,
                categories = categories,
                keys = ApiKeyManager.providerKeys(this),
                knownMappings = knownMappings(vpaCacheList),
                recentTransactions = recentTransactions
            )
            when {
                llmResult == null -> {
                    // AI failed — use rule result if any, otherwise ask user
                    if (ruleCategory != null) {
                        category = ruleCategory
                        categorizedBy = (ruleResult as CategorizationResult.Categorized).by
                    } else {
                        category = CATEGORY_UNCATEGORIZED
                        categorizedBy = CategorizedBy.UNRESOLVED
                        needsUserInput = true
                    }
                }
                llmResult.confidence >= 85 -> {
                    Log.i(TAG, "LLM auto-categorized (${llmResult.confidence}%): ${llmResult.category}")
                    category = llmResult.category
                    categorizedBy = CategorizedBy.AUTO_LLM
                }
                else -> {
                    Log.i(TAG, "LLM suggests ${llmResult.category} (${llmResult.confidence}%) — asking user")
                    category = CATEGORY_UNCATEGORIZED
                    categorizedBy = CategorizedBy.UNRESOLVED
                    needsUserInput = true
                    llmSuggestionForNotif = llmResult.category
                }
            }
        } else {
            // AI disabled — use rule result if any, otherwise ask user
            if (ruleResult is CategorizationResult.Categorized) {
                category = ruleResult.category
                categorizedBy = ruleResult.by
            } else {
                val needsInput = ruleResult as CategorizationResult.NeedsUserInput
                suggestedCategories = needsInput.suggestedCategories
                category = CATEGORY_UNCATEGORIZED
                categorizedBy = CategorizedBy.UNRESOLVED
                needsUserInput = true
            }
        }

        db.transactionDao().insert(
            Transaction(
                id = parsed.transactionRef,
                amount = parsed.amount.toPlainString(),
                recipientName = parsed.recipientName,
                recipientVpa = parsed.recipientVpa,
                sourceApp = parsed.sourceApp,
                timestamp = parsed.timestamp,
                category = category,
                categorizedBy = categorizedBy,
                rawDump = parsed.rawDump
            )
        )

        Log.i(TAG, "Saved: ₹${parsed.amount} to ${parsed.recipientName} → $category")
        com.dabhiram.expensetracker.data.TransactionEventBus.notifyChanged()

        if (needsUserInput) {
            TransactionNotificationManager.postCategorizationNotification(
                context = this,
                transactionId = parsed.transactionRef,
                amount = parsed.amount,
                recipientName = parsed.recipientName,
                vpa = parsed.recipientVpa,
                suggestedCategories = suggestedCategories,
                llmSuggestion = llmSuggestionForNotif
            )
        }
    }

    private fun isDuplicate(ref: String): Boolean {
        purgeOldRefs(System.currentTimeMillis())
        return recentRefs.containsKey(ref)
    }

    private fun markSeen(ref: String) {
        recentRefs[ref] = System.currentTimeMillis()
    }

    private fun purgeOldRefs(now: Long) {
        recentRefs.entries.removeIf { it.value < now - DEDUP_WINDOW_MS }
    }

    private fun writeDumpLog(pkg: String, textNodes: List<String>, timestamp: Long) {
        try {
            val logDir = File(getExternalFilesDir(null), "logs")
            logDir.mkdirs()
            val appName = if (pkg == GPAY_PACKAGE) "gpay" else "phonepe"
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            val logFile = File(logDir, "dump_${appName}_$dateStr.txt")
            logFile.writeText(buildString {
                appendLine("Package: $pkg")
                appendLine("Timestamp: $timestamp")
                appendLine("Date: ${Date(timestamp)}")
                appendLine("---")
                textNodes.forEachIndexed { i, text -> appendLine("[$i] $text") }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write dump log", e)
        }
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("UPI Expense Tracker")
            .setContentText("Monitoring Google Pay and PhonePe")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        pendingRescans.values.forEach { rescanHandler.removeCallbacks(it) }
        pendingRescans.clear()
    }

    companion object {
        const val GPAY_PACKAGE = "com.google.android.apps.nbu.paisa.user"
        const val PHONEPE_PACKAGE = "com.phonepe.app"
    }
}
