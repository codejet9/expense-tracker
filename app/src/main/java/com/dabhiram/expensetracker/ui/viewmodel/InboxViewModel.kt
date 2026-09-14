package com.dabhiram.expensetracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dabhiram.expensetracker.data.model.BudgetAlert
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.model.VpaCategory
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

class InboxViewModel(private val repository: TransactionRepository) : ViewModel() {

    val uncategorized: StateFlow<List<Transaction>> = repository.uncategorized
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val budgetAlerts: StateFlow<List<BudgetAlert>> = combine(
        repository.monthTransactions(),
        repository.allCategoriesFlow
    ) { txns, cats ->
        val monthSpend = txns.groupBy { it.category }
            .mapValues { (_, list) ->
                list.fold(BigDecimal.ZERO) { acc, t ->
                    acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
                }
            }
        cats.mapNotNull { cat ->
            val limit = cat.budget?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                ?: return@mapNotNull null
            val spent = monthSpend[cat.name] ?: BigDecimal.ZERO
            val percent = runCatching {
                spent.divide(limit, 4, RoundingMode.HALF_UP).toFloat()
            }.getOrDefault(0f)
            if (percent < 0.80f) return@mapNotNull null
            BudgetAlert(category = cat.name, spent = spent, limit = limit, percentUsed = percent)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun categorize(transaction: Transaction, category: String) {
        viewModelScope.launch {
            repository.updateCategory(transaction.id, category, CategorizedBy.USER)
            if (!transaction.recipientVpa.isNullOrBlank()) {
                repository.upsertVpaCategory(
                    VpaCategory(
                        vpa = transaction.recipientVpa,
                        category = category,
                        recipientName = transaction.recipientName,
                        lastUsed = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch { repository.deleteTransaction(transaction) }
    }

    class Factory(private val repository: TransactionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            InboxViewModel(repository) as T
    }
}
