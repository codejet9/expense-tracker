package com.dabhiram.expensetracker.data.db.dao

import androidx.room.*
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs ORDER BY timestamp DESC")
    fun getFromFlow(startMs: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun getRangeFlow(startMs: Long, endMs: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE category = 'Uncategorized' ORDER BY timestamp DESC")
    fun getUncategorizedFlow(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): Transaction?

    @Query("UPDATE transactions SET category = :category, categorizedBy = :by WHERE id = :id")
    suspend fun updateCategory(id: String, category: String, by: CategorizedBy)

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs ORDER BY timestamp DESC")
    suspend fun getFrom(startMs: Long): List<Transaction>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getRange(startMs: Long, endMs: Long): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE category = 'Uncategorized'")
    fun getUncategorizedCountFlow(): Flow<Int>

    @Delete
    suspend fun delete(transaction: Transaction)

    @Update
    suspend fun update(transaction: Transaction)

    @Query("SELECT COUNT(*) FROM transactions WHERE category = :category")
    suspend fun countByCategory(category: String): Int

    @Query("SELECT * FROM transactions WHERE category != 'Uncategorized' AND timestamp >= :startMs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentCategorized(startMs: Long, limit: Int): List<Transaction>

    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs")
    suspend fun countRange(startMs: Long, endMs: Long): Int

    @Query("SELECT * FROM transactions WHERE category = :category AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getByCategoryInRange(category: String, startMs: Long, endMs: Long): List<Transaction>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getRangePaged(startMs: Long, endMs: Long, limit: Int, offset: Int): List<Transaction>
}
