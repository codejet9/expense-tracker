package com.dabhiram.expensetracker.parser

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.math.BigDecimal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads amount/recipient/VPA/reference straight off a payment screenshot
 * using on-device OCR (ML Kit) instead of sending the image to a remote
 * vision model — no network round-trip, no timeout risk.
 */
object ScreenshotTextParser {

    // ₹ correctly preserved by OCR (rare on real screenshots but does happen)
    private val AMOUNT_PATTERN = Regex("""₹\s*([\d,]+(?:\.\d{1,2})?)""")
    // ₹ OCR'd as 'R' or 'Rs'/'Rs.' (common ML Kit corruption of the Rupee glyph)
    private val R_AMOUNT_PATTERN = Regex("""(?<![A-Za-z])R(?:s\.?)?\s*(\d[\d,]*(?:\.\d{1,2})?)""")
    // ₹ OCR'd as 'INR' (also seen on some receipt layouts)
    private val INR_AMOUNT_PATTERN = Regex("""INR\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    // ML Kit's Latin text recognizer silently drops the ₹ glyph on real
    // screenshots (confirmed via on-device OCR dump — every "₹1" line came
    // back as just "1"), so amounts have to be found from surrounding phrase
    // context instead of a currency symbol that OCR never actually reports.
    private val CONTEXTUAL_AMOUNT_PATTERN = Regex(
        """([\d,]+(?:\.\d{1,2})?)\s*(?:was debited|was credited|sent to|is debited|is credited|paid|deducted)""",
        RegexOption.IGNORE_CASE
    )
    // Keyword-prefixed amounts ("Total: 9,000", "Amount: 9,000", "Paid: 9,000")
    private val KEYWORD_AMOUNT_PATTERN = Regex(
        """(?:total|amount|paid|pay|charge)[:\s]+(\d[\d,]*(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )
    // Last resort: the amount as a bare number on its own OCR line, with no symbol or
    // phrase context surviving at all. Capped at 12 chars (covers up to ₹9,99,99,999)
    // so it never matches a 12+ digit reference/transaction ID.
    private val BARE_NUMBER_LINE = Regex("""^[\d,]{1,12}(?:\.\d{1,2})?$""")
    private val VPA_PATTERN = Regex("""[\w.\-]+@[\w.\-]+""")
    private val REF_PATTERN = Regex(
        """(?:UPI Ref|Ref No|Transaction ID|UTR)[.:\s]*([A-Z0-9]{10,20})""",
        RegexOption.IGNORE_CASE
    )
    private val TO_PREFIX_PATTERN = Regex("""^to:?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SKIP_WORDS = setOf(
        "payment", "successful", "paid", "to", "sent", "google", "pay", "phonepe",
        "rupees", "amount", "upi", "ref", "transaction", "via", "you", "bank",
        "done", "share", "complete", "processing", "enter", "close", "screenshot"
    )

    data class Result(
        val amount: BigDecimal,
        val recipientName: String,
        val recipientVpa: String?,
        val referenceNumber: String?,
        // True when the amount couldn't be cross-checked against a second
        // occurrence, or occurrences disagreed without a clear majority —
        // OCR can't be made 100% reliable against a stylized currency glyph,
        // so surface low confidence instead of silently trusting a guess.
        val amountUncertain: Boolean
    )

    suspend fun recognizeLines(bitmap: Bitmap): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = visionText.textBlocks.flatMap { block -> block.lines.map { it.text } }
                    cont.resume(lines)
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    fun parse(lines: List<String>): Result? {
        // The same amount is usually rendered several times on a GPay/PhonePe
        // receipt (big header, "X was debited", "X sent to Y"). OCR garbles
        // the ₹ glyph inconsistently across occurrences — sometimes dropped,
        // sometimes fused into the digits as a stray leading "7" — so taking
        // the *first* match can lock onto a corrupted one. Instead, collect
        // every occurrence and go with whichever value shows up most often.
        val candidates = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            AMOUNT_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { candidates.add(it.replace(",", "")) }
            R_AMOUNT_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { candidates.add(it.replace(",", "")) }
            INR_AMOUNT_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { candidates.add(it.replace(",", "")) }
            CONTEXTUAL_AMOUNT_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { candidates.add(it.replace(",", "")) }
            KEYWORD_AMOUNT_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { candidates.add(it.replace(",", "")) }
            if (BARE_NUMBER_LINE.matches(trimmed)) candidates.add(trimmed.replace(",", ""))
        }
        val counts = candidates.groupingBy { it }.eachCount()
        val amountStr = counts.entries
            .maxWithOrNull(compareBy({ it.value }, { -it.key.length }))
            ?.key ?: return null
        val amount = runCatching { BigDecimal(amountStr) }.getOrNull() ?: return null
        val topCount = counts[amountStr] ?: 0
        val amountUncertain = candidates.size <= 1 || counts.values.count { it == topCount } > 1

        val vpa = lines.firstNotNullOfOrNull { line ->
            VPA_PATTERN.find(line)?.value?.lowercase()?.takeIf { it.contains('@') && it.length > 4 }
        }
        val ref = REF_PATTERN.find(lines.joinToString(" "))?.groupValues?.getOrNull(1)
        val recipientName = extractRecipientName(lines, vpa)

        return Result(amount, recipientName, vpa, ref, amountUncertain)
    }

    private fun extractRecipientName(lines: List<String>, vpa: String?): String {
        // Prefer the top-of-screen "To <Name>" header — GPay's contact name,
        // matching the convention the accessibility path already uses for
        // the same contact. A detail screen also shows a "To: <Name>" line
        // further down paired with the VPA, but that's the bank-registered
        // account name, which can differ from the contact name (e.g. a
        // shared/family account) — using it instead fragments the same
        // real-world contact into two different recipientName values across
        // capture methods.
        lines.firstNotNullOfOrNull { line ->
            TO_PREFIX_PATTERN.find(line.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }?.let { return it }

        // Fallback: name on the line immediately before the matched VPA —
        // a far more reliable signal than scanning for a name-shaped line
        // (the generic scan below can land on an unrelated capitalized line
        // like "State Bank of India 5535").
        if (vpa != null) {
            val vpaIndex = lines.indexOfFirst { VPA_PATTERN.find(it)?.value?.lowercase() == vpa }
            if (vpaIndex > 0) {
                val precedingLine = lines[vpaIndex - 1].trim()
                if (precedingLine.length in 2..50 &&
                    precedingLine.any { it.isLetter() } &&
                    precedingLine[0].isUpperCase()
                ) {
                    return precedingLine
                }
            }
        }

        for (raw in lines) {
            val n = raw.trim()
            if (n.length < 2 || n.length > 50) continue
            if (n.lowercase().split(Regex("\\s+")).any { it in SKIP_WORDS }) continue
            if (AMOUNT_PATTERN.containsMatchIn(n)) continue
            if (VPA_PATTERN.containsMatchIn(n)) continue
            if (n.matches(Regex("\\d+"))) continue
            if (vpa != null && n.equals(vpa, ignoreCase = true)) continue
            if (n.any { it.isLetter() } && n[0].isUpperCase()) return n
        }
        return vpa?.substringBefore('@')?.replace('.', ' ')?.capitalizeWords() ?: "Unknown"
    }

    private fun String.capitalizeWords(): String =
        split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
