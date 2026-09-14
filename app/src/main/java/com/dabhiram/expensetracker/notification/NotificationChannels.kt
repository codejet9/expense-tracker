package com.dabhiram.expensetracker.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val CHANNEL_CATEGORIZE = "channel_categorize"
const val CHANNEL_SERVICE = "channel_service"
const val CHANNEL_WEEKLY = "channel_weekly"
const val CHANNEL_INSIGHTS = "channel_insights"
const val CHANNEL_BUDGET = "channel_budget"

const val ACTION_CATEGORIZE = "com.dabhiram.expensetracker.action.CATEGORIZE"
const val ACTION_DISMISS = "com.dabhiram.expensetracker.action.DISMISS_NOTIFICATION"
const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
const val EXTRA_CATEGORY = "extra_category"
const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
const val EXTRA_VPA = "extra_vpa"
const val EXTRA_NAV_DESTINATION = "nav_destination"
const val NAV_DESTINATION_REPORTS = "reports"

const val NOTIFICATION_ID_SERVICE = 1

fun createNotificationChannels(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_CATEGORIZE,
            "Categorize Payments",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Asks you to categorize unrecognized UPI payments"
            enableVibration(true)
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_SERVICE,
            "Service Status",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows that the UPI tracking service is running"
            setShowBadge(false)
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_WEEKLY,
            "Weekly Summary",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Weekly spending summary every Sunday evening"
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_INSIGHTS, "Spending Insights", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "AI-driven weekly spending analysis and optimization tips"
            enableVibration(true)
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_BUDGET, "Budget Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Notifies when monthly category spending exceeds your set budget"
        }
    )
}
