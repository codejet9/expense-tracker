package com.dabhiram.expensetracker.llm

import android.util.Log
import com.dabhiram.expensetracker.data.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the OCR text already extracted from a payment screenshot and asks
 * an LLM to make sense of it — a fast, text-only call (not the image
 * itself), tried across providers (OpenAI → Groq → Gemini, whichever keys
 * are configured) via [MultiProviderLlmClient]. OCR garbles the ₹ symbol
 * inconsistently (dropped, or fused into the digits as a stray leading
 * character) and the amount is usually repeated 2-3 times on a receipt — an
 * LLM's language understanding can reconcile that far better than
 * hand-rolled regex heuristics. [ScreenshotTextParser]'s pure-OCR result
 * remains the fallback whenever every provider is disabled, fails, or
 * times out.
 */
object LlmScreenshotTextAnalyzer {

    private const val TAG = "LlmScreenshotTextAnalyzer"

    // A response below this confidence keeps trying the next configured
    // provider instead of settling — a fast "Other, 30%" from the first
    // provider isn't actually better than what a second one might say.
    private const val CONFIDENCE_RETRY_THRESHOLD = 70

    data class Result(
        val amount: BigDecimal,
        val recipientName: String,
        val category: String,
        val confidence: Int
    )

    /**
     * @param categories the user's current, live category list (editable in
     * Settings) — never a hardcoded set, so an added/deleted category takes
     * effect on the very next call.
     * @param knownMappings past recipient→category choices the user has
     * already confirmed, given as context so the model can categorize by
     * analogy (see [LlmCategorizer] for why this matters).
     */
    suspend fun extract(
        ocrLines: List<String>,
        categories: List<String>,
        keys: MultiProviderLlmClient.Keys,
        knownMappings: List<Pair<String, String>> = emptyList(),
        recentTransactions: List<Transaction> = emptyList()
    ): Result? = withContext(Dispatchers.IO) {
        val system = buildSystemPrompt(categories)
        val user = buildUserPrompt(ocrLines, knownMappings, recentTransactions)

        var best: Result? = null
        for ((providerName, apiKey) in MultiProviderLlmClient.configuredProviders(keys)) {
            Log.d(TAG, "Trying $providerName")
            val parsed = try {
                MultiProviderLlmClient.callProvider(providerName, apiKey, system, user)?.let { parseResult(it, categories) }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction via $providerName failed", e)
                null
            } ?: continue
            Log.d(TAG, "$providerName answered: ₹${parsed.amount} to ${parsed.recipientName}, ${parsed.category} @ ${parsed.confidence}%")

            if (best == null || parsed.confidence > best!!.confidence) best = parsed
            if (parsed.confidence >= CONFIDENCE_RETRY_THRESHOLD) return@withContext parsed
        }
        best
    }

    private fun buildSystemPrompt(categories: List<String>): String =
        """You are analyzing noisy OCR text extracted from an Indian UPI payment screenshot (GPay/PhonePe). Return ONLY a JSON object — no markdown, no explanation.

Categories: ${categories.joinToString(", ")}

OCR handling:
1. Amount: the ₹ symbol is often dropped entirely, or misread and fused into the amount as a stray leading digit/letter (e.g. "₹101" may appear as "101" or "7101"). The true amount is usually repeated 2-3 times across the receipt (a large header, a "was debited" line, a "sent to" line) — use the value that appears consistently, ignoring one-off corrupted variants. If no reliable amount can be determined, set amount to null.
2. Recipient: prefer a top-of-screen contact name (a line like "To <Name>") over a lower "To: <Name>" detail-section name if they differ — the detail section may show a different bank-registered account name for a shared/family account.
3. Category — important principles:
   - The recipient is NOT necessarily the merchant or service — a Rapido/Ola ride is often paid directly to the driver's personal account, a food-delivery order may go to a payment processor rather than a name containing "Swiggy"/"Zomato". Do not invent a specific merchant or brand that isn't supported by the evidence.
   - An unknown recipient does NOT automatically mean "Other" — infer the most likely category from whatever evidence is available. Use "Other" only when there's genuinely insufficient evidence.
   - Phone-number or individual-looking accounts are NOT automatically "Personal Transfers".
   - Swiggy/Zomato are NOT inherently Food — they also cover grocery/quick-commerce orders. Pick Food vs Groceries based on whatever evidence distinguishes them.
   - Amount and time may be used only as weak secondary signals, never overriding stronger evidence.
   - The user's previously confirmed categorizations (if given) are strong evidence — a new payment with a different recipient can still be the same kind of expense.
4. If this doesn't look like a payment confirmation, or no amount is legible: amount = null, confidence should be low.

Confidence scale:
- 90-100: very strong evidence, or matches a confirmed historical mapping
- 75-89: strong inference
- 50-74: plausible but uncertain
- 0-49: very weak evidence

JSON format (one line, no markdown):
{"amount": <number or null>, "recipientName": "<name or null>", "category": "<name from list>", "confidence": <0-100>}"""

    private fun buildUserPrompt(
        ocrLines: List<String>,
        knownMappings: List<Pair<String, String>>,
        recentTransactions: List<Transaction>
    ): String {
        val numbered = ocrLines.mapIndexed { i, l -> "[$i] $l" }.joinToString("\n")
        val knownMappingsBlock = if (knownMappings.isEmpty()) "" else {
            "\nThis user's previously confirmed categorizations:\n" +
                knownMappings.joinToString("\n") { (name, category) -> "- $name → $category" } + "\n"
        }
        val recentBlock = if (recentTransactions.isEmpty()) "" else {
            val df = SimpleDateFormat("EEE d MMM", Locale.getDefault())
            "\nRecent transactions (last 3 days — use for pattern recognition):\n" +
                recentTransactions.joinToString("\n") { t ->
                    "- ₹${t.amount} to ${t.recipientName}${if (!t.recipientVpa.isNullOrBlank()) " (${t.recipientVpa})" else ""} → ${t.category} [${df.format(Date(t.timestamp))}]"
                } + "\n"
        }
        return "OCR lines:\n$numbered\n$knownMappingsBlock$recentBlock"
    }

    private fun parseResult(text: String, categories: List<String>): Result? {
        return try {
            val cleaned = text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val obj = JSONObject(cleaned)
            if (obj.isNull("amount")) return null
            val amount = runCatching { BigDecimal(obj.getDouble("amount").toString()) }.getOrNull()
                ?: return null

            val rawCategory = obj.optString("category", "Other").trim()
            val category = categories.find { it.equals(rawCategory, ignoreCase = true) }
                ?: categories.find { it.equals("Other", ignoreCase = true) }
                ?: categories.firstOrNull()
                ?: "Other"
            val confidence = obj.optInt("confidence", 50).coerceIn(0, 100)
            val recipientName = obj.optString("recipientName").takeUnless { it.isBlank() || it == "null" }
                ?: "Unknown"

            Result(amount, recipientName, category, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: $text", e)
            null
        }
    }
}
