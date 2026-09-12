package com.dabhiram.expensetracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TransactionEventBus {
    private val _lastChangeMs = MutableStateFlow(0L)
    val lastChangeMs = _lastChangeMs.asStateFlow()

    fun notifyChanged() {
        _lastChangeMs.value = System.currentTimeMillis()
    }
}
