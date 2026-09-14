package com.dabhiram.expensetracker.data.model

import java.math.BigDecimal

data class BudgetAlert(
    val category: String,
    val spent: BigDecimal,
    val limit: BigDecimal,
    val percentUsed: Float
)

val BudgetAlert.isExceeded get() = percentUsed > 1.0f
