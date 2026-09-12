package com.dabhiram.expensetracker.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provider calls tried in a fixed priority order — OpenAI, then Groq, then
 * Gemini — using whichever keys are configured. Callers get back the raw
 * JSON text the prompt asked for from a single provider call; this is the
 * only place that needs to know each provider's request/response envelope
 * shape. Iteration/fallback (including "keep trying if confidence is low,
 * not just on outright failure") is the caller's job, since only the caller
 * knows how to parse its own domain-specific confidence field — see
 * [LlmCategorizer] and [LlmScreenshotTextAnalyzer] for that loop.
 *
 * OpenAI and Groq share the same request/response format (Groq is an
 * OpenAI-compatible endpoint), so one function serves both. Gemini's shape
 * is different and gets its own.
 */
object MultiProviderLlmClient {

    private const val TAG = "MultiProviderLlmClient"
    private const val MAX_COMPLETION_TOKENS = 400

    private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    private const val OPENAI_MODEL = "gpt-5-nano"

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODEL = "qwen/qwen3.6-27b"

    private const val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"

    data class Keys(val openai: String?, val groq: String?, val gemini: String?)

    // Ordered (name, apiKey) pairs for whichever providers actually have a
    // configured key — priority order is OpenAI, then Groq, then Gemini.
    fun configuredProviders(keys: Keys): List<Pair<String, String>> = listOfNotNull(
        keys.openai?.let { "openai" to it },
        keys.groq?.let { "groq" to it },
        keys.gemini?.let { "gemini" to it }
    )

    fun callProvider(name: String, apiKey: String, systemPrompt: String, userPrompt: String): String? = when (name) {
        "openai" ->
            // No reasoning_effort — unlike Groq's Qwen, we're not certain
            // "none" is a valid value for whichever OpenAI model is
            // configured, and an unrecognized enum value would just fail
            // the whole call.
            callOpenAiCompatible(OPENAI_URL, OPENAI_MODEL, apiKey, systemPrompt, userPrompt, reasoningEffort = null)
        "groq" ->
            // Qwen3 defaults to an extended "thinking" mode; disabling it
            // keeps this simple classification call fast and predictable.
            callOpenAiCompatible(GROQ_URL, GROQ_MODEL, apiKey, systemPrompt, userPrompt, reasoningEffort = "none")
        "gemini" -> callGemini(systemPrompt, userPrompt, apiKey)
        else -> null
    }

    private fun callOpenAiCompatible(
        url: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        reasoningEffort: String?
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 180_000

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
                put("response_format", JSONObject().put("type", "json_object"))
                put("temperature", 0.0)
                put("max_completion_tokens", MAX_COMPLETION_TOKENS)
                put("stream", false)
                if (reasoningEffort != null) put("reasoning_effort", reasoningEffort)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != 200) {
                val err = connection.errorStream?.bufferedReader()?.readText()
                Log.w(TAG, "$url HTTP $code: $err")
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Call to $url failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun callGemini(systemPrompt: String, userPrompt: String, apiKey: String): String? {
        val connection = URL(GEMINI_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-goog-api-key", apiKey)
            connection.doOutput = true
            connection.connectTimeout = 10_000
            // Gemini's actual response time can run up to ~3 minutes under
            // load (confirmed via direct curl test) — not a bug, just slow.
            connection.readTimeout = 180_000

            val body = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", systemPrompt))
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", userPrompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("maxOutputTokens", MAX_COMPLETION_TOKENS)
                    put("responseMimeType", "application/json")
                })
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != 200) {
                val err = connection.errorStream?.bufferedReader()?.readText()
                Log.w(TAG, "Gemini HTTP $code: $err")
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }
}
