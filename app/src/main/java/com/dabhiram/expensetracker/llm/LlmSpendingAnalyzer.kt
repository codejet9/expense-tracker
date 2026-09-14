package com.dabhiram.expensetracker.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal

object LlmSpendingAnalyzer {

    private const val TAG = "LlmSpendingAnalyzer"

    suspend fun analyze(
        currentSpend: Map<String, BigDecimal>,
        currentTotal: BigDecimal,
        comparisonSpend: Map<String, BigDecimal>?,
        periodLabel: String,
        keys: MultiProviderLlmClient.Keys,
        budgetViolations: Map<String, Pair<BigDecimal, BigDecimal>> = emptyMap()
    ): List<String>? = withContext(Dispatchers.IO) {
        val system = buildSystemPrompt()
        val user = buildUserPrompt(currentSpend, currentTotal, comparisonSpend, periodLabel, budgetViolations)
        for ((providerName, apiKey) in MultiProviderLlmClient.configuredProviders(keys)) {
            try {
                val raw = MultiProviderLlmClient.callProvider(providerName, apiKey, system, user)
                    ?: continue
                val bullets = parseInsights(raw)
                if (bullets.isNotEmpty()) return@withContext bullets
            } catch (e: Exception) {
                Log.e(TAG, "Analysis via $providerName failed", e)
            }
        }
        null
    }

    // Provider always returns JSON (response_format: json_object is enforced by the client).
    // Ask for {"insights": ["...", "...", "..."]} and parse that array.
    private fun parseInsights(raw: String): List<String> {
        return try {
            val arr = JSONObject(raw).getJSONArray("insights")
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.take(4)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildSystemPrompt() = """
You are a personal finance analyst for Indian UPI payments. Analyze the spending data and return a JSON object with exactly this shape:
{"insights": ["insight 1", "insight 2", "insight 3", "insight 4"]}
Return 3-4 insights. Be specific with ₹ amounts. Compare with previous period when provided. Focus on: total change, biggest category shifts, notable patterns. No markdown, no extra keys.
""".trimIndent()

    private fun buildUserPrompt(
        currentSpend: Map<String, BigDecimal>,
        currentTotal: BigDecimal,
        comparisonSpend: Map<String, BigDecimal>?,
        periodLabel: String,
        budgetViolations: Map<String, Pair<BigDecimal, BigDecimal>>
    ): String {
        val fmt = java.text.DecimalFormat("#,##,##0.##")
        val sb = StringBuilder()
        sb.appendLine("Current $periodLabel spending (total ₹${fmt.format(currentTotal)}):")
        currentSpend.entries.sortedByDescending { it.value }.forEach { (cat, amt) ->
            sb.appendLine("  $cat: ₹${fmt.format(amt)}")
        }
        if (comparisonSpend != null) {
            val prevTotal = comparisonSpend.values.fold(BigDecimal.ZERO) { a, b -> a + b }
            sb.appendLine()
            sb.appendLine("Previous $periodLabel spending (total ₹${fmt.format(prevTotal)}):")
            comparisonSpend.entries.sortedByDescending { it.value }.forEach { (cat, amt) ->
                sb.appendLine("  $cat: ₹${fmt.format(amt)}")
            }
        } else {
            sb.appendLine()
            sb.appendLine("No previous period data available. Analyze the current spending only.")
        }
        if (budgetViolations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("MONTHLY BUDGET STATUS (reference for insights):")
            budgetViolations.forEach { (cat, pair) ->
                val (spent, limit) = pair
                val status = if (spent > limit)
                    "EXCEEDED by ₹${fmt.format(spent - limit)}"
                else
                    "${(spent.toDouble() / limit.toDouble() * 100).toInt()}% used"
                sb.appendLine("  $cat: ₹${fmt.format(spent)} / ₹${fmt.format(limit)} ($status)")
            }
            sb.appendLine("Reference exceeded budgets specifically when giving advice.")
        }
        return sb.toString()
    }
}
