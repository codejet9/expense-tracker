package com.dabhiram.expensetracker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.math.RoundingMode

enum class SourceApp { GPAY, PHONEPE, SCREENSHOT, MANUAL }

enum class CategorizedBy { AUTO_VPA, AUTO_MERCHANT, AUTO_LLM, USER, UNRESOLVED }

const val CATEGORY_UNCATEGORIZED = "Uncategorized"
const val CATEGORY_LENT = "Lent"

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
    val rawDump: String,
    val splitMyShare: String? = null,
    val splitPeopleCount: Int = 0
)

val Transaction.isSplit: Boolean
    get() = splitPeopleCount >= 2 && splitMyShare != null

val Transaction.netAmount: BigDecimal
    get() {
        val full = runCatching { BigDecimal(amount) }.getOrDefault(BigDecimal.ZERO)
        if (!isSplit) return full
        val myShare = runCatching { BigDecimal(splitMyShare!!) }.getOrDefault(BigDecimal.ZERO)
        val paidOnBehalfTotal = full - myShare
        val perPerson = runCatching {
            paidOnBehalfTotal.divide(BigDecimal(splitPeopleCount), 2, RoundingMode.HALF_UP)
        }.getOrDefault(BigDecimal.ZERO)
        return myShare + perPerson
    }

val Transaction.reimbursableAmount: BigDecimal
    get() {
        if (!isSplit) return BigDecimal.ZERO
        val full = runCatching { BigDecimal(amount) }.getOrDefault(BigDecimal.ZERO)
        val myShare = runCatching { BigDecimal(splitMyShare!!) }.getOrDefault(BigDecimal.ZERO)
        val paidOnBehalfTotal = full - myShare
        val perPerson = runCatching {
            paidOnBehalfTotal.divide(BigDecimal(splitPeopleCount), 2, RoundingMode.HALF_UP)
        }.getOrDefault(BigDecimal.ZERO)
        return paidOnBehalfTotal - perPerson
    }
