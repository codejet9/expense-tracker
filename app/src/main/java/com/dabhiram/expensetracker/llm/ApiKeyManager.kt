package com.dabhiram.expensetracker.llm

import android.content.Context

object ApiKeyManager {

    private const val PREFS_NAME = "expense_tracker_prefs"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_LLM_ENABLED = "llm_enabled"
    private const val KEY_DEFAULT_SPLIT_PEOPLE = "default_split_people"

    fun getOpenAiKey(context: Context): String = prefs(context).getString(KEY_OPENAI, "") ?: ""
    fun setOpenAiKey(context: Context, key: String) = prefs(context).edit().putString(KEY_OPENAI, key.trim()).apply()

    fun getGroqKey(context: Context): String = prefs(context).getString(KEY_GROQ, "") ?: ""
    fun setGroqKey(context: Context, key: String) = prefs(context).edit().putString(KEY_GROQ, key.trim()).apply()

    fun getGeminiKey(context: Context): String = prefs(context).getString(KEY_GEMINI, "") ?: ""
    fun setGeminiKey(context: Context, key: String) = prefs(context).edit().putString(KEY_GEMINI, key.trim()).apply()

    fun hasAnyKey(context: Context): Boolean =
        getOpenAiKey(context).isNotBlank() || getGroqKey(context).isNotBlank() || getGeminiKey(context).isNotBlank()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LLM_ENABLED, true) && hasAnyKey(context)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_LLM_ENABLED, enabled).apply()

    fun getDefaultSplitPeople(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_SPLIT_PEOPLE, 2)

    fun setDefaultSplitPeople(context: Context, count: Int) =
        prefs(context).edit().putInt(KEY_DEFAULT_SPLIT_PEOPLE, count.coerceAtLeast(2)).apply()

    // Tried in this order — OpenAI, then Groq, then Gemini — whichever has a
    // configured key. See MultiProviderLlmClient for the actual fallback call.
    fun providerKeys(context: Context) = MultiProviderLlmClient.Keys(
        openai = getOpenAiKey(context).takeIf { it.isNotBlank() },
        groq = getGroqKey(context).takeIf { it.isNotBlank() },
        gemini = getGeminiKey(context).takeIf { it.isNotBlank() }
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
