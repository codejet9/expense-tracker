package com.dabhiram.expensetracker.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dabhiram.expensetracker.scheduleWeeklySummary

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed — rescheduling weekly summary")
            scheduleWeeklySummary(context)
        }
    }
}
