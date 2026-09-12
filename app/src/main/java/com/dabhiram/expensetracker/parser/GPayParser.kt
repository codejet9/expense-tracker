package com.dabhiram.expensetracker.parser

import android.util.Log
import com.dabhiram.expensetracker.data.model.SourceApp
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object GPayParser {

    private val TAG = "GPayParser"

    private val PHONE_PATTERN = Regex("""\+91(\d{10})""")

    // Matches a single history node like:
    //   "Payment to Venkateshwar\n₹1\nPaid • 9:09 pm"
    // The time-only format (no date) means it happened today.
    private val HISTORY_NODE_PATTERN = Regex(
        """Payment to ([^\n]+)\n₹([\d,]+(?:\.\d{1,2})?)\nPaid\s*[•·\-]?\s*(\d{1,2}:\d{2}\s*(?:am|pm))""",
        RegexOption.IGNORE_CASE
    )

    // GPay's PIN-entry, processing, and success screens are FLAG_SECURE — no
    // accessibility events fire at all while they're shown (confirmed on
    // device: zero events, not just missing text). Detection only works once
    // GPay navigates back to the contact's transaction history screen.
    fun parse(textNodes: List<String>, timestamp: Long): ParsedTransaction? =
        parseHistoryScreen(textNodes, timestamp)

    // ── History screen ──────────────────────────────────────────────────────
    // Nodes like:
    //   "Payment to Venkateshwar\n₹1\nPaid • 9:09 pm"
    // show "time only" (no date) when the payment happened today.

    private fun parseHistoryScreen(textNodes: List<String>, timestamp: Long): ParsedTransaction? {
        for (node in textNodes) {
            val match = HISTORY_NODE_PATTERN.find(node) ?: continue
            val nameFromNode = match.groupValues[1].trim()
            if (nameFromNode.equals("you", ignoreCase = true)) continue // skip received
            val amountStr = match.groupValues[2].replace(",", "")
            val amount = runCatching { BigDecimal(amountStr) }.getOrNull() ?: continue
            val timeStr = match.groupValues[3].trim()
            if (!isRecentTime(timeStr, timestamp)) continue

            // Contact header node: "Full Name\n+91XXXXXXXXXX"
            val phone = textNodes.firstNotNullOfOrNull { n ->
                PHONE_PATTERN.find(n)?.groupValues?.getOrNull(1)
            }
            val contactName = textNodes.firstNotNullOfOrNull { n ->
                if (PHONE_PATTERN.containsMatchIn(n))
                    n.split("\n").firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length < 60 }
                else null
            } ?: nameFromNode

            val vpa = phone?.let { "$it@gpay" }
            val ref = historyRef(phone ?: nameFromNode, amountStr, timeStr)

            Log.d(TAG, "History screen: ₹$amount to $contactName ref=$ref")
            return ParsedTransaction(
                transactionRef = ref,
                amount = amount,
                recipientName = contactName,
                recipientVpa = vpa,
                sourceApp = SourceApp.GPAY,
                timestamp = timestamp,
                rawDump = textNodes.joinToString("\n")
            )
        }
        return null
    }

    // "9:09 pm" → check if within 5 minutes of now (uses full AM/PM so 9am ≠ 9pm)
    private fun isRecentTime(timeStr: String, nowMs: Long): Boolean {
        return try {
            val sdf = SimpleDateFormat("h:mm a", Locale.US)
            val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val parsed = sdf.parse(timeStr.trim()) ?: return false
            val parsedCal = Calendar.getInstance().apply {
                time = parsed
                set(Calendar.YEAR, nowCal.get(Calendar.YEAR))
                set(Calendar.DAY_OF_YEAR, nowCal.get(Calendar.DAY_OF_YEAR))
            }
            val diffMs = Math.abs(nowMs - parsedCal.timeInMillis)
            diffMs <= 5 * 60 * 1000L
        } catch (e: Exception) { false }
    }

    // Stable ref: same (identifier + amount + time) always produces the same string
    // so multiple events for the same history screen are deduped.
    private fun historyRef(identifier: String, amount: String, timeStr: String): String {
        val t = timeStr.replace(":", "").replace(" ", "").lowercase()
        return "GPAY_H_${identifier}_${amount}_$t"
    }
}
