package com.dabhiram.expensetracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dabhiram.expensetracker.data.model.BudgetAlert
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.SourceApp
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.notification.BudgetNotificationHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

data class HomeUiState(
    val transactions: List<Transaction> = emptyList(),
    val totalToday: String = "₹0",
    val transactionCount: Int = 0
)

class HomeViewModel(
    private val repository: TransactionRepository,
    private val application: Application
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.todayTransactions()
        .map { txns ->
            val total = txns.fold(BigDecimal.ZERO) { acc, t ->
                acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
            }
            HomeUiState(
                transactions = txns,
                totalToday = "₹${DecimalFormat("#,##,##0.##").format(total)}",
                transactionCount = txns.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, HomeUiState())

    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val budgetAlerts: StateFlow<List<BudgetAlert>> = combine(
        repository.monthTransactions(),
        repository.allCategoriesFlow
    ) { txns, cats ->
        computeBudgetAlerts(txns, cats)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        budgetAlerts.onEach { alerts ->
            BudgetNotificationHelper.checkAndNotify(application, alerts)
        }.launchIn(viewModelScope)
    }

    fun addCategory(name: String) {
        viewModelScope.launch { repository.addCategory(name) }
    }

    fun deleteCategory(name: String) {
        viewModelScope.launch { repository.deleteCategory(name) }
    }

    suspend fun categoryUsageCount(name: String): Int = repository.transactionCountForCategory(name)

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch { repository.deleteTransaction(transaction) }
    }

    fun editTransaction(
        transaction: Transaction,
        newAmount: BigDecimal,
        newCategory: String,
        splitMyShare: BigDecimal? = null,
        splitPeopleCount: Int = 0
    ) {
        viewModelScope.launch {
            repository.updateTransaction(
                transaction.copy(
                    amount = newAmount.toPlainString(),
                    category = newCategory,
                    categorizedBy = CategorizedBy.USER,
                    splitMyShare = splitMyShare?.toPlainString(),
                    splitPeopleCount = splitPeopleCount
                )
            )
        }
    }

    fun applySplit(transaction: Transaction, myShare: BigDecimal, peopleCount: Int) {
        viewModelScope.launch {
            repository.updateTransaction(
                transaction.copy(
                    splitMyShare = myShare.toPlainString(),
                    splitPeopleCount = peopleCount,
                    categorizedBy = CategorizedBy.USER
                )
            )
        }
    }

    fun addTransaction(
        amount: BigDecimal,
        recipientName: String,
        recipientVpa: String?,
        category: String,
        timestamp: Long
    ) {
        viewModelScope.launch {
            repository.insert(
                Transaction(
                    id = "MANUAL_${timestamp}_${amount.toPlainString()}_${System.nanoTime()}",
                    amount = amount.toPlainString(),
                    recipientName = recipientName,
                    recipientVpa = recipientVpa?.takeIf { it.isNotBlank() },
                    sourceApp = SourceApp.MANUAL,
                    timestamp = timestamp,
                    category = category,
                    categorizedBy = CategorizedBy.USER,
                    rawDump = "Added manually"
                )
            )
        }
    }

    fun addTransactionWithSplit(
        amount: BigDecimal,
        recipientName: String,
        recipientVpa: String?,
        category: String,
        timestamp: Long,
        myShare: BigDecimal,
        peopleCount: Int
    ) {
        viewModelScope.launch {
            repository.insert(
                Transaction(
                    id = "MANUAL_${timestamp}_${amount.toPlainString()}_${System.nanoTime()}",
                    amount = amount.toPlainString(),
                    recipientName = recipientName,
                    recipientVpa = recipientVpa?.takeIf { it.isNotBlank() },
                    sourceApp = SourceApp.MANUAL,
                    timestamp = timestamp,
                    category = category,
                    categorizedBy = CategorizedBy.USER,
                    rawDump = "Added manually",
                    splitMyShare = myShare.toPlainString(),
                    splitPeopleCount = peopleCount
                )
            )
        }
    }

    class Factory(
        private val repository: TransactionRepository,
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository, application) as T
    }
}

private fun computeBudgetAlerts(
    txns: List<Transaction>,
    cats: List<com.dabhiram.expensetracker.data.model.Category>
): List<BudgetAlert> {
    val monthSpend = txns.groupBy { it.category }
        .mapValues { (_, list) ->
            list.fold(BigDecimal.ZERO) { acc, t ->
                acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
            }
        }
    return cats.mapNotNull { cat ->
        val limit = cat.budget?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: return@mapNotNull null
        val spent = monthSpend[cat.name] ?: BigDecimal.ZERO
        val percent = runCatching {
            spent.divide(limit, 4, RoundingMode.HALF_UP).toFloat()
        }.getOrDefault(0f)
        if (percent < 0.80f) return@mapNotNull null
        BudgetAlert(category = cat.name, spent = spent, limit = limit, percentUsed = percent)
    }
}
