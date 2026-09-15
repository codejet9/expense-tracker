package com.dabhiram.expensetracker.data

import android.content.Context
import org.json.JSONArray

object InsightsCache {

    private const val PREFS = "insights_cache_v2"
    private const val KEY_WEEK_DATA = "week_data"
    private const val KEY_WEEK_TS = "week_ts"
    private const val KEY_MONTH_DATA = "month_data"
    private const val KEY_MONTH_TS = "month_ts"

    fun getWeek(ctx: Context, lastChangeMs: Long, weekStartMs: Long): List<String>? =
        getCached(ctx, KEY_WEEK_DATA, KEY_WEEK_TS, lastChangeMs, periodStartMs = weekStartMs)

    fun setWeek(ctx: Context, bullets: List<String>) =
        setCached(ctx, KEY_WEEK_DATA, KEY_WEEK_TS, bullets)

    fun getMonth(ctx: Context, lastChangeMs: Long, monthStartMs: Long): List<String>? =
        getCached(ctx, KEY_MONTH_DATA, KEY_MONTH_TS, lastChangeMs, periodStartMs = monthStartMs)

    fun setMonth(ctx: Context, bullets: List<String>) =
        setCached(ctx, KEY_MONTH_DATA, KEY_MONTH_TS, bullets)

    private fun getCached(
        ctx: Context,
        dataKey: String,
        tsKey: String,
        lastChangeMs: Long,
        periodStartMs: Long
    ): List<String>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val computedAt = prefs.getLong(tsKey, 0L)
        // Expired if: computed before any transaction change, OR computed in a previous period
        // (week/month rolled over since last compute).
        if (computedAt < lastChangeMs) return null
        if (computedAt < periodStartMs) return null
        val json = prefs.getString(dataKey, null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    fun invalidateAll(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_WEEK_DATA).remove(KEY_WEEK_TS)
            .remove(KEY_MONTH_DATA).remove(KEY_MONTH_TS)
            .apply()
    }

    private fun setCached(ctx: Context, dataKey: String, tsKey: String, bullets: List<String>) {
        val json = JSONArray().apply { bullets.forEach { put(it) } }.toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(dataKey, json)
            .putLong(tsKey, System.currentTimeMillis())
            .apply()
    }
}
