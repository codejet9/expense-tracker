package com.dabhiram.expensetracker.categorizer

import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.data.model.Transaction
import java.math.BigDecimal

/**
 * Catches the same real-world payment landing twice via different capture
 * paths — e.g. a screenshot import (from GPay's success screen, before
 * "Done") and the accessibility auto-capture (fires after "Done", from the
 * contact history screen) for the same transaction. Each path generates its
 * own transaction ref in its own format, so exact-ID dedup never catches this.
 */
object DuplicateDetector {

    private const val WINDOW_MS = 60 * 1000L

    suspend fun findRecent(
        db: AppDatabase,
        amount: BigDecimal,
        recipientVpa: String?,
        recipientName: String,
        timestamp: Long
    ): Transaction? {
        val candidates = db.transactionDao().getRange(timestamp - WINDOW_MS, timestamp + WINDOW_MS)
        return candidates.find { tx ->
            val sameAmount = runCatching { BigDecimal(tx.amount).compareTo(amount) == 0 }.getOrDefault(false)
            if (!sameAmount) return@find false
            val sameVpa = recipientVpa != null && tx.recipientVpa != null &&
                recipientVpa.equals(tx.recipientVpa, ignoreCase = true)
            val sameName = recipientName.isNotBlank() && tx.recipientName.isNotBlank() &&
                (recipientName.contains(tx.recipientName, ignoreCase = true) ||
                    tx.recipientName.contains(recipientName, ignoreCase = true))
            sameVpa || sameName
        }
    }
}
