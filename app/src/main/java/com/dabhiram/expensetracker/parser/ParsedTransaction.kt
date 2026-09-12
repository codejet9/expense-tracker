package com.dabhiram.expensetracker.parser

import com.dabhiram.expensetracker.data.model.SourceApp
import java.math.BigDecimal

data class ParsedTransaction(
    val transactionRef: String,
    val amount: BigDecimal,
    val recipientName: String,
    val recipientVpa: String?,
    val sourceApp: SourceApp,
    val timestamp: Long,
    val rawDump: String
)
