package com.dabhiram.expensetracker.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dabhiram.expensetracker.MainActivity
import com.dabhiram.expensetracker.R
import com.dabhiram.expensetracker.data.model.BudgetAlert
import com.dabhiram.expensetracker.data.model.isExceeded
import java.text.DecimalFormat
import java.util.Calendar

object BudgetNotificationHelper {

    private const val PREFS = "budget_notif_prefs"
    private val fmt = DecimalFormat("#,##,##0.##")

    fun checkAndNotify(context: Context, alerts: List<BudgetAlert>) {
        val exceeded = alerts.filter { it.isExceeded }
        if (exceeded.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val monthKey = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.MONTH)}"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        exceeded.forEach { alert ->
            val dedupKey = "${alert.category}_$monthKey"
            if (prefs.getBoolean(dedupKey, false)) return@forEach

            val exceededBy = alert.spent - alert.limit
            val notifId = 5000 + Math.abs(alert.category.hashCode() % 1000)

            val contentIntent = PendingIntent.getActivity(
                context,
                notifId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_NAV_DESTINATION, NAV_DESTINATION_REPORTS)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_BUDGET)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Budget exceeded: ${alert.category}")
                .setContentText("₹${fmt.format(alert.spent)} spent vs ₹${fmt.format(alert.limit)} limit")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "₹${fmt.format(alert.spent)} spent on ${alert.category} this month, " +
                            "exceeding your ₹${fmt.format(alert.limit)} budget by ₹${fmt.format(exceededBy)}. " +
                            "Tap to review your spending."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()

            manager.notify(notifId, notif)
            prefs.edit().putBoolean(dedupKey, true).apply()
        }
    }
}
