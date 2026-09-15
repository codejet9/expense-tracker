package com.dabhiram.expensetracker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TransactionEventBus {
    private const val PREFS = "transaction_events"
    private const val KEY_LAST_CHANGE_MS = "last_change_ms"

    private val _lastChangeMs = MutableStateFlow(0L)
    val lastChangeMs = _lastChangeMs.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        val eventPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = eventPrefs
        _lastChangeMs.value = maxOf(
            _lastChangeMs.value,
            eventPrefs.getLong(KEY_LAST_CHANGE_MS, 0L)
        )
    }

    fun notifyChanged() {
        _lastChangeMs.update { previous ->
            maxOf(System.currentTimeMillis(), previous + 1)
        }
        prefs?.edit()?.putLong(KEY_LAST_CHANGE_MS, _lastChangeMs.value)?.apply()
    }
}
