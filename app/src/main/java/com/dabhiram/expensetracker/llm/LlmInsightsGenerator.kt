package com.dabhiram.expensetracker.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL

object LlmInsightsGenerator {

    private const val TAG = "LlmInsightsGenerator"
    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent"

    data class SpendingContext(
        val currentWeek: Map<String, BigDecimal>,
        val priorWeeks: List<Map<String, BigDecimal>>,   // index 0 = most recent prior
        val topMerchants: List<Pair<String, BigDecimal>>,
        val totalThisWeek: BigDecimal,
        val totalLastWeek: BigDecimal
    )

    suspend fun generateInsights(ctx: SpendingContext, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(ctx)
                callGemini(prompt, apiKey)
            } catch (e: Exception) {
                Log.e(TAG, "Insights generation failed", e)
                null
            }
        }

    private fun buildPrompt(ctx: SpendingContext): String {
        val df = java.text.DecimalFormat("#,##0")
        val weekLines = ctx.currentWeek.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  ${it.key}: ₹${df.format(it.value)}" }

        val avgLines = if (ctx.priorWeeks.isNotEmpty()) {
            val allCats = ctx.priorWeeks.flatMap { it.keys }.toSet()
            allCats.map { cat ->
                val avg = ctx.priorWeeks.mapNotNull { it[cat] }
                    .fold(BigDecimal.ZERO) { a, b -> a + b }
                    .divide(BigDecimal(ctx.priorWeeks.size), 2, RoundingMode.HALF_UP)
                "  $cat: ₹${df.format(avg)} avg"
            }.sortedByDescending { it }.joinToString("\n")
        } else "  (no history yet)"

        val merchantLines = ctx.topMerchants.take(5)
            .joinToString("\n") { "  ${it.first}: ₹${df.format(it.second)}" }

        val trend = when {
            ctx.totalLastWeek == BigDecimal.ZERO -> "first recorded week"
            ctx.totalThisWeek > ctx.totalLastWeek ->
                "up ₹${df.format(ctx.totalThisWeek - ctx.totalLastWeek)} vs last week"
            else ->
                "down ₹${df.format(ctx.totalLastWeek - ctx.totalThisWeek)} vs last week"
        }

        return """You are a personal finance advisor for Indian UPI users. Analyze this weekly spending and give 2-3 specific, actionable money-saving tips. Be direct, concrete, and encouraging.

THIS WEEK: ₹${df.format(ctx.totalThisWeek)} ($trend)
$weekLines

3-WEEK AVERAGES:
$avgLines

TOP MERCHANTS:
$merchantLines

Rules:
- Reference specific amounts and merchants from the data
- If Food/Groceries > 30% of spend, suggest meal planning or cooking at home
- If Personal Transfers are high, note it's fine but not a saving opportunity
- Skip "Lent" — it's reimbursable
- Write exactly 2-3 bullet points starting with •
- Each bullet max 25 words, no fluff

Insights:"""
    }

    private fun callGemini(prompt: String, apiKey: String): String? {
        val url = URL("$API_URL?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 150)
                })
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != 200) {
                Log.w(TAG, "HTTP $code: ${connection.errorStream?.bufferedReader()?.readText()}")
                return null
            }

            val raw = connection.inputStream.bufferedReader().readText()
            return JSONObject(raw)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } finally {
            connection.disconnect()
        }
    }
}
