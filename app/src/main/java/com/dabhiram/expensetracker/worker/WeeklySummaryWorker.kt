package com.dabhiram.expensetracker.worker

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dabhiram.expensetracker.R
import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.llm.ApiKeyManager
import com.dabhiram.expensetracker.llm.LlmInsightsGenerator
import com.dabhiram.expensetracker.notification.CHANNEL_INSIGHTS
import com.dabhiram.expensetracker.notification.CHANNEL_WEEKLY
import java.math.BigDecimal
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicInteger

class WeeklySummaryWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(context)
        val endMs = System.currentTimeMillis()
        val weekMs = 7 * 24 * 60 * 60 * 1000L
        val startMs = endMs - weekMs
        val transactions = db.transactionDao().getRange(startMs, endMs)

        if (transactions.isEmpty()) return Result.success()

        val total = transactions.fold(BigDecimal.ZERO) { acc, t ->
            acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
        }

        val byCategory = transactions.groupBy { it.category }
        val topCategory = byCategory.maxByOrNull { (_, txns) ->
            txns.fold(BigDecimal.ZERO) { acc, t ->
                acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
            }
        }

        val df = DecimalFormat("#,##,##0")
        val topCategoryText = topCategory?.let { (cat, txns) ->
            val catTotal = txns.fold(BigDecimal.ZERO) { acc, t ->
                acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
            }
            "Top: $cat (₹${df.format(catTotal)})"
        } ?: ""

        val notif = NotificationCompat.Builder(context, CHANNEL_WEEKLY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Weekly Spending Summary")
            .setContentText("Last week: ₹${df.format(total)} across ${transactions.size} transactions")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Last week you spent ₹${df.format(total)} across ${transactions.size} payments. $topCategoryText")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(WEEKLY_NOTIF_ID.incrementAndGet(), notif)

        // AI spending insights
        if (ApiKeyManager.isEnabled(context)) {
            val apiKey = ApiKeyManager.getGeminiKey(context)

            // Build current week category map
            val currentWeek = transactions
                .groupBy { it.category }
                .mapValues { (_, txns) ->
                    txns.fold(BigDecimal.ZERO) { acc, t ->
                        acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
                    }
                }

            // Build prior weeks (weeks 1-3)
            val priorWeeks = (1..3).map { weekOffset ->
                val wEnd = endMs - weekOffset * weekMs
                val wStart = wEnd - weekMs
                val weekTxns = db.transactionDao().getRange(wStart, wEnd)
                weekTxns.groupBy { it.category }
                    .mapValues { (_, txns) ->
                        txns.fold(BigDecimal.ZERO) { acc, t ->
                            acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
                        }
                    }
            }

            // Top merchants for current week
            val topMerchants = transactions
                .groupBy { it.recipientName }
                .map { (name, txns) ->
                    Pair(
                        name,
                        txns.fold(BigDecimal.ZERO) { acc, t ->
                            acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
                        }
                    )
                }
                .sortedByDescending { it.second }
                .take(5)

            // Total for last week (week offset 1)
            val totalLastWeek = priorWeeks.firstOrNull()?.values
                ?.fold(BigDecimal.ZERO) { acc, v -> acc + v } ?: BigDecimal.ZERO

            val ctx = LlmInsightsGenerator.SpendingContext(
                currentWeek = currentWeek,
                priorWeeks = priorWeeks,
                topMerchants = topMerchants,
                totalThisWeek = total,
                totalLastWeek = totalLastWeek
            )

            val insights = LlmInsightsGenerator.generateInsights(ctx, apiKey)
            if (insights != null) {
                val shortText = if (insights.length > 60) insights.take(60) + "…" else insights
                val insightsNotif = NotificationCompat.Builder(context, CHANNEL_INSIGHTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Your weekly spending insight")
                    .setContentText(shortText)
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(insights)
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                manager.notify(INSIGHTS_NOTIF_ID.incrementAndGet(), insightsNotif)
            }
        }

        return Result.success()
    }

    companion object {
        val WEEKLY_NOTIF_ID = AtomicInteger(5000)
        val INSIGHTS_NOTIF_ID = AtomicInteger(6000)
        const val WORK_NAME = "weekly_summary"
    }
}
