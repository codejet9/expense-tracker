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

object LlmCategorizer {

    private const val TAG = "LlmCategorizer"

    // A response below this confidence keeps trying the next configured
    // provider instead of settling — a fast "Other, 30%" from the first
    // provider isn't actually better than what a second one might say.
    private const val CONFIDENCE_RETRY_THRESHOLD = 70

    data class Result(val category: String, val confidence: Int)

    /**
     * @param categories the user's current, live category list (editable in
     * Settings) — never a hardcoded set, so an added/deleted category takes
     * effect on the very next call.
     * @param knownMappings past recipient→category choices the user has
     * already confirmed (from the VPA cache — every entry there was written
     * by an explicit user choice, never a silent AI guess), given as context
     * so the model can reason by analogy — e.g. recognizing a new payment as
     * "probably the same kind of thing" as a previously-categorized one,
     * which plain VPA-equality lookup can't do (a different auto-rickshaw
     * driver's VPA every ride, for instance, never matches a cached entry
     * directly).
     */
    suspend fun categorize(
        vpa: String?,
        recipientName: String,
        amount: BigDecimal?,
        categories: List<String>,
        keys: MultiProviderLlmClient.Keys,
        knownMappings: List<Pair<String, String>> = emptyList(),
        recentTransactions: List<Transaction> = emptyList()
    ): Result? = withContext(Dispatchers.IO) {
        val system = buildSystemPrompt(categories)
        val user = buildUserPrompt(vpa, recipientName, amount, knownMappings, recentTransactions)

        var best: Result? = null
        for ((providerName, apiKey) in MultiProviderLlmClient.configuredProviders(keys)) {
            Log.d(TAG, "Trying $providerName")
            val parsed = try {
                MultiProviderLlmClient.callProvider(providerName, apiKey, system, user)?.let { parseResult(it, categories) }
            } catch (e: Exception) {
                Log.e(TAG, "Categorization via $providerName failed", e)
                null
            } ?: continue
            Log.d(TAG, "$providerName answered: ${parsed.category} @ ${parsed.confidence}%")

            if (best == null || parsed.confidence > best!!.confidence) best = parsed
            if (parsed.confidence >= CONFIDENCE_RETRY_THRESHOLD) return@withContext parsed
        }
        best
    }

    private fun buildSystemPrompt(categories: List<String>): String =
        """You are a UPI payment category classifier for Indian payments. Given a recipient's UPI VPA, name, amount, and optionally the user's previously confirmed categorizations, classify the expense. Return ONLY a JSON object — no markdown, no explanation.

Categories: ${categories.joinToString(", ")}

Important principles:
1. The recipient is NOT necessarily the merchant or service — a Rapido/Ola ride is often paid directly to the driver's personal UPI account, a food-delivery order may go to a payment processor or parent company rather than a name containing "Swiggy"/"Zomato". Do not invent a specific merchant or brand that isn't supported by the evidence — just use this reasoning to pick the category.
2. An unknown recipient does NOT automatically mean "Other" — try to infer the most likely category from whatever evidence is available (name, amount, past patterns). Use "Other" only when there's genuinely insufficient evidence.
3. Phone-number or individual-looking VPAs are NOT automatically "Personal Transfers" — they may represent Travel (auto/cab payment), Food, Shopping, or another category.
4. Swiggy/Zomato are NOT inherently Food — they also cover grocery/quick-commerce orders (e.g. Instamart). Pick Food vs Groceries based on whatever evidence distinguishes them; default to Food only if there's no signal either way.
5. Amount may be used only as a weak secondary signal and must never override stronger evidence (a recipient match, or a past confirmed categorization).
6. The user's previously confirmed categorizations (if given) are strong evidence — a new payment with a different recipient can still be the same kind of expense (e.g. every ride is a different driver, but still Travel).

Confidence scale:
- 90-100: very strong evidence, or matches a confirmed historical mapping
- 75-89: strong inference
- 50-74: plausible but uncertain
- 0-49: very weak evidence

JSON format (one line, no markdown):
{"category": "<name from list>", "confidence": <0-100>}"""

    private fun buildUserPrompt(
        vpa: String?,
        recipientName: String,
        amount: BigDecimal?,
        knownMappings: List<Pair<String, String>>,
        recentTransactions: List<Transaction>
    ): String {
        val recipient = buildString {
            if (!vpa.isNullOrBlank()) append("VPA: $vpa")
            if (recipientName.isNotBlank() && recipientName != "Unknown") {
                if (isNotEmpty()) append(" | ")
                append("Name: $recipientName")
            }
            if (amount != null) {
                if (isNotEmpty()) append(" | ")
                append("Amount: ₹${amount.toPlainString()}")
            }
        }
        val knownMappingsBlock = if (knownMappings.isEmpty()) "" else {
            "\nThis user's previously confirmed categorizations:\n" +
                knownMappings.joinToString("\n") { (name, category) -> "- $name → $category" }
        }
        val recentBlock = if (recentTransactions.isEmpty()) "" else {
            val df = SimpleDateFormat("EEE d MMM", Locale.getDefault())
            "\nRecent transactions (last 3 days — use for pattern recognition):\n" +
                recentTransactions.joinToString("\n") { t ->
                    "- ₹${t.amount} to ${t.recipientName}${if (!t.recipientVpa.isNullOrBlank()) " (${t.recipientVpa})" else ""} → ${t.category} [${df.format(Date(t.timestamp))}]"
                }
        }
        return "Recipient: $recipient$knownMappingsBlock$recentBlock"
    }

    private fun parseResult(text: String, categories: List<String>): Result? {
        return try {
            val cleaned = text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val obj = JSONObject(cleaned)
            val rawCategory = obj.getString("category").trim()
            val confidence = obj.optInt("confidence", 50).coerceIn(0, 100)
            val matched = categories.find { it.equals(rawCategory, ignoreCase = true) }
                ?: return null

            Result(matched, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: $text", e)
            null
        }
    }
}
