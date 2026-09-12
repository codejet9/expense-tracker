package com.dabhiram.expensetracker.data.repository

import com.dabhiram.expensetracker.data.TransactionEventBus
import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.Category
import com.dabhiram.expensetracker.data.model.MerchantRule
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.model.VpaCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar

class TransactionRepository(private val db: AppDatabase) {

    val uncategorized: Flow<List<Transaction>> = db.transactionDao().getUncategorizedFlow()
    val uncategorizedCount: Flow<Int> = db.transactionDao().getUncategorizedCountFlow()
    val merchantRules: Flow<List<MerchantRule>> = db.merchantRuleDao().getAllFlow()
    val categories: Flow<List<String>> = db.categoryDao().getAllFlow().map { list -> list.map { it.name } }

    fun todayTransactions(): Flow<List<Transaction>> {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return db.transactionDao().getFromFlow(startOfDay)
    }

    fun weekStartMs(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun monthStartMs(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun weekTransactions(): Flow<List<Transaction>> =
        db.transactionDao().getFromFlow(weekStartMs())

    fun monthTransactions(): Flow<List<Transaction>> =
        db.transactionDao().getFromFlow(monthStartMs())

    suspend fun getPreviousWeekTransactions(): List<Transaction> {
        val currentWeekStart = weekStartMs()
        val prevWeekStart = currentWeekStart - 7 * 24 * 60 * 60 * 1000L
        return db.transactionDao().getRange(prevWeekStart, currentWeekStart)
    }

    suspend fun getPreviousMonthTransactions(): List<Transaction> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val currentMonthStart = cal.timeInMillis
        cal.add(java.util.Calendar.MONTH, -1)
        val prevMonthStart = cal.timeInMillis
        return db.transactionDao().getRange(prevMonthStart, currentMonthStart)
    }

    fun rangeTransactions(startMs: Long, endMs: Long): Flow<List<Transaction>> =
        db.transactionDao().getRangeFlow(startMs, endMs)

    suspend fun insert(transaction: Transaction) {
        db.transactionDao().insert(transaction)
        TransactionEventBus.notifyChanged()
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        db.transactionDao().delete(transaction)
        TransactionEventBus.notifyChanged()
    }

    suspend fun updateTransaction(transaction: Transaction) {
        db.transactionDao().update(transaction)
        TransactionEventBus.notifyChanged()
    }

    suspend fun exists(id: String): Boolean = db.transactionDao().exists(id)

    suspend fun updateCategory(id: String, category: String, by: CategorizedBy = CategorizedBy.USER) {
        db.transactionDao().updateCategory(id, category, by)
        TransactionEventBus.notifyChanged()
    }

    suspend fun upsertVpaCategory(vpaCategory: VpaCategory) = db.vpaCategoryDao().upsert(vpaCategory)

    suspend fun upsertMerchantRule(rule: MerchantRule) = db.merchantRuleDao().upsert(rule)

    suspend fun deleteMerchantRule(rule: MerchantRule) = db.merchantRuleDao().delete(rule)

    suspend fun exportAllTransactions(): List<Transaction> = db.transactionDao().getFrom(0L)

    suspend fun getCategoryNames(): List<String> = db.categoryDao().getAll().map { it.name }

    suspend fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val existing = db.categoryDao().getAll().map { it.name }
        if (existing.any { it.equals(trimmed, ignoreCase = true) }) return
        val nextOrder = db.categoryDao().maxSortOrder() + 1
        db.categoryDao().insert(Category(trimmed, nextOrder))
    }

    suspend fun deleteCategory(name: String) = db.categoryDao().deleteByName(name)

    suspend fun transactionCountForCategory(category: String): Int =
        db.transactionDao().countByCategory(category)

    suspend fun countTransactions(startMs: Long, endMs: Long): Int =
        db.transactionDao().countRange(startMs, endMs)

    suspend fun getTransactionsByCategory(category: String, startMs: Long, endMs: Long): List<Transaction> =
        db.transactionDao().getByCategoryInRange(category, startMs, endMs)

    suspend fun getTransactionsPaged(startMs: Long, endMs: Long, page: Int, pageSize: Int): List<Transaction> =
        db.transactionDao().getRangePaged(startMs, endMs, pageSize, page * pageSize)

suspend fun getRecentCategorized(days: Int = 3, limit: Int = 30): List<Transaction> {
        val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return db.transactionDao().getRecentCategorized(since, limit)
    }

    suspend fun getByCategory(category: String): List<Transaction> =
        db.transactionDao().getByCategory(category)
}
