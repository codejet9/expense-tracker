package com.dabhiram.expensetracker.data.db.dao

import androidx.room.*
import com.dabhiram.expensetracker.data.model.MerchantRule
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantRuleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<MerchantRule>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRule)

    @Query("SELECT * FROM merchant_rules ORDER BY pattern ASC")
    fun getAllFlow(): Flow<List<MerchantRule>>

    @Query("SELECT * FROM merchant_rules")
    suspend fun getAll(): List<MerchantRule>

    @Delete
    suspend fun delete(rule: MerchantRule)
}
