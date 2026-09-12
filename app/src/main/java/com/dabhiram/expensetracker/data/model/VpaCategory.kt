package com.dabhiram.expensetracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpa_categories")
data class VpaCategory(
    @PrimaryKey val vpa: String,
    val category: String,
    val recipientName: String,
    val lastUsed: Long
)
