package com.dabhiram.expensetracker.data.db.dao

import androidx.room.*
import com.dabhiram.expensetracker.data.model.VpaCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface VpaCategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vpaCategory: VpaCategory)

    @Query("SELECT * FROM vpa_categories WHERE vpa = :vpa")
    suspend fun getByVpa(vpa: String): VpaCategory?

    @Query("SELECT * FROM vpa_categories ORDER BY lastUsed DESC")
    fun getAllFlow(): Flow<List<VpaCategory>>

    @Query("SELECT * FROM vpa_categories")
    suspend fun getAll(): List<VpaCategory>
}
