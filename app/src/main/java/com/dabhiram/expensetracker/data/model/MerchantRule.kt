package com.dabhiram.expensetracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "merchant_rules")
data class MerchantRule(
    @PrimaryKey val pattern: String,
    val category: String,
    val isUserAdded: Boolean
)
