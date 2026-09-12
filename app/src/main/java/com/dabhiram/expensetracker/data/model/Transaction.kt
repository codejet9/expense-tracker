package com.dabhiram.expensetracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SourceApp { GPAY, PHONEPE, SCREENSHOT, MANUAL }

enum class CategorizedBy { AUTO_VPA, AUTO_MERCHANT, AUTO_LLM, USER, UNRESOLVED }

const val CATEGORY_UNCATEGORIZED = "Uncategorized"
const val CATEGORY_PAID_ON_BEHALF = "Paid on Behalf"

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey val id: String,
    val amount: String,
    val recipientName: String,
    val recipientVpa: String?,
    val sourceApp: SourceApp,
    val timestamp: Long,
    val category: String,
    val categorizedBy: CategorizedBy,
    val rawDump: String
)
