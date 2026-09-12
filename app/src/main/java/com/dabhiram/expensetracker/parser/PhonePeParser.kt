package com.dabhiram.expensetracker.parser

import android.util.Log
import com.dabhiram.expensetracker.data.model.SourceApp
import java.math.BigDecimal

object PhonePeParser {

    private val TAG = "PhonePeParser"

    private val AMOUNT_PATTERN = Regex("""[₹₹]\s*([\d,]+(?:\.\d{1,2})?)""")
    private val VPA_PATTERN = Regex("""[\w.\-]+@[\w.\-]+""")
    private val REF_PATTERN = Regex("""(?:UPI Ref|Ref No|Transaction ID|UTR)[.:\s]*([A-Z0-9]{10,20})""", RegexOption.IGNORE_CASE)
    private val DIGITS_REF = Regex("""\b(\d{12})\b""")
    // Must have at least one of these to be a real payment success screen
    private val SUCCESS_PHRASES = setOf(
        "payment successful", "transfer successful", "paid successfully",
        "money sent", "sent successfully", "payment done", "transfer done"
    )

    fun parse(textNodes: List<String>, timestamp: Long): ParsedTransaction? {
        val combined = textNodes.joinToString(" ").lowercase()
        val hasSuccess = SUCCESS_PHRASES.any { combined.contains(it) }
        val allText = textNodes.joinToString(" ")
        val amountMatch = AMOUNT_PATTERN.find(allText)
        if (!hasSuccess || amountMatch == null) return null

        val amountStr = amountMatch.groupValues[1].replace(",", "")
        val amount = runCatching { BigDecimal(amountStr) }.getOrNull() ?: return null

        val vpa = textNodes.firstNotNullOfOrNull { node ->
            VPA_PATTERN.find(node)?.value?.lowercase()?.takeIf { it.contains('@') && it.length > 4 }
        }

        val refMatch = REF_PATTERN.find(allText)
            ?: DIGITS_REF.find(allText)
        val ref = refMatch?.groupValues?.getOrNull(1)
            ?: generateFallbackRef(timestamp, amountStr)

        val recipientName = extractRecipientName(textNodes, vpa)

        Log.d(TAG, "Parsed PhonePe: ₹$amount to $recipientName ($vpa), ref=$ref")

        return ParsedTransaction(
            transactionRef = ref,
            amount = amount,
            recipientName = recipientName,
            recipientVpa = vpa,
            sourceApp = SourceApp.PHONEPE,
            timestamp = timestamp,
            rawDump = textNodes.joinToString("\n")
        )
    }

    private fun extractRecipientName(nodes: List<String>, vpa: String?): String {
        val skipWords = setOf(
            "payment", "successful", "paid", "to", "sent", "phonepe",
            "rupees", "amount", "upi", "ref", "transaction", "via", "transfer",
            "you", "done", "share", "complete", "processing", "enter", "close"
        )
        for (i in nodes.indices) {
            val raw = nodes[i].trim()
            if (raw.length < 2 || raw.length > 50) continue
            // Check each word — "Transfer done" has "done" which is a skip word
            if (raw.lowercase().split(Regex("\\s+")).any { it in skipWords }) continue
            if (AMOUNT_PATTERN.containsMatchIn(raw)) continue
            if (VPA_PATTERN.containsMatchIn(raw)) continue
            if (raw.lowercase().contains("successful")) continue
            if (raw.matches(Regex("\\d+"))) continue
            if (vpa != null && raw.equals(vpa, ignoreCase = true)) continue
            if (raw.any { it.isLetter() } && raw[0].isUpperCase()) return raw
        }
        return vpa?.substringBefore('@')?.replace('.', ' ')?.capitalizeWords() ?: "Unknown"
    }

    private fun generateFallbackRef(timestamp: Long, amount: String): String {
        return "PHONEPE_${timestamp}_$amount"
    }

    private fun String.capitalizeWords(): String =
        split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
