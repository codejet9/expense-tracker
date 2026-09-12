package com.dabhiram.expensetracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.SourceApp
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DecimalFormat

data class HomeUiState(
    val transactions: List<Transaction> = emptyList(),
    val totalToday: String = "₹0",
    val transactionCount: Int = 0
)

class HomeViewModel(private val repository: TransactionRepository) : ViewModel() {

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

    fun editTransaction(transaction: Transaction, newAmount: BigDecimal, newCategory: String) {
        viewModelScope.launch {
            repository.updateTransaction(
                transaction.copy(
                    amount = newAmount.toPlainString(),
                    category = newCategory,
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

    fun splitTransaction(transaction: Transaction, myContribution: BigDecimal, paidOnBehalfTotal: BigDecimal, peopleCount: Int) {
        viewModelScope.launch { repository.splitTransaction(transaction, myContribution, paidOnBehalfTotal, peopleCount) }
    }

    fun addTransactionWithSplit(
        amount: BigDecimal,
        recipientName: String,
        recipientVpa: String?,
        category: String,
        timestamp: Long,
        myContribution: BigDecimal,
        paidOnBehalfTotal: BigDecimal,
        peopleCount: Int
    ) {
        viewModelScope.launch {
            val transaction = Transaction(
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
            repository.insert(transaction)
            repository.splitTransaction(transaction, myContribution, paidOnBehalfTotal, peopleCount)
        }
    }

    class Factory(private val repository: TransactionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository) as T
    }
}
