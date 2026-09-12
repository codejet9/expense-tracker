package com.dabhiram.expensetracker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dabhiram.expensetracker.notification.createNotificationChannels
import com.dabhiram.expensetracker.worker.WeeklySummaryWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ExpenseTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        scheduleWeeklySummary(this)
    }
}

fun scheduleWeeklySummary(context: Context) {
    val initialDelay = millisUntilNextSundayNinePm()
    val request = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(7, TimeUnit.DAYS)
        .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WeeklySummaryWorker.WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )

    Log.i("ExpenseTrackerApp", "Weekly summary scheduled in ${initialDelay / 3_600_000}h")
}

private fun millisUntilNextSundayNinePm(): Long {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        set(Calendar.HOUR_OF_DAY, 21)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
    }
    return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
}
