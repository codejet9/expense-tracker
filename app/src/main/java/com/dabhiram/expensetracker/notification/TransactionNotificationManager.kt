package com.dabhiram.expensetracker.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dabhiram.expensetracker.MainActivity
import com.dabhiram.expensetracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicInteger

object TransactionNotificationManager {

    private val notificationIdCounter = AtomicInteger(1000)
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Posts a categorization notification. When [llmSuggestion] is provided, the notification
     * frames it as an AI suggestion to confirm. Otherwise it shows the top suggested categories
     * as quick-tap actions.
     */
    fun postCategorizationNotification(
        context: Context,
        transactionId: String,
        amount: BigDecimal,
        recipientName: String,
        vpa: String?,
        suggestedCategories: List<String>,
        llmSuggestion: String? = null
    ) {
        val notifId = notificationIdCounter.incrementAndGet()
        val formattedAmount = formatAmount(amount)

        val builder = NotificationCompat.Builder(context, CHANNEL_CATEGORIZE)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent(context, transactionId, notifId))

        if (llmSuggestion != null) {
            builder
                .setContentTitle("₹$formattedAmount to $recipientName")
                .setContentText("AI suggests: $llmSuggestion — correct?")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("AI thinks this is \"$llmSuggestion\". Tap to confirm or choose a different category.")
                )
            builder.addAction(
                0,
                "✓ $llmSuggestion",
                categoryPendingIntent(context, transactionId, llmSuggestion, vpa, notifId)
            )
            builder.addAction(0, "Other →", openAppPendingIntent(context, transactionId, notifId))
        } else {
            builder
                .setContentTitle("What was ₹$formattedAmount to $recipientName for?")
                .setContentText("Tap a category to save it.")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("₹$formattedAmount to $recipientName\nTap a category to save it.")
                )
            val cats = suggestedCategories.take(2)
            cats.forEach { category ->
                builder.addAction(
                    0,
                    category,
                    categoryPendingIntent(context, transactionId, category, vpa, notifId)
                )
            }
            builder.addAction(0, "Other →", openAppPendingIntent(context, transactionId, notifId))
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notifId, builder.build())

        scope.launch {
            delay(120_000L)
            manager.cancel(notifId)
        }
    }

    private fun categoryPendingIntent(
        context: Context,
        transactionId: String,
        category: String,
        vpa: String?,
        notifId: Int
    ): PendingIntent {
        val intent = Intent(ACTION_CATEGORIZE).apply {
            setClass(context, CategoryActionReceiver::class.java)
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            putExtra(EXTRA_CATEGORY, category)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
            if (vpa != null) putExtra(EXTRA_VPA, vpa)
        }
        return PendingIntent.getBroadcast(
            context,
            notifId * 100 + category.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(
        context: Context,
        transactionId: String,
        notifId: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        return PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatAmount(amount: BigDecimal): String {
        val df = DecimalFormat("#,##,##0.##")
        return df.format(amount)
    }
}
