package com.dabhiram.expensetracker.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.VpaCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CategoryActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CATEGORIZE) return

        val transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID) ?: return
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val vpa = intent.getStringExtra(EXTRA_VPA)

        if (notifId != -1) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notifId)
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                db.transactionDao().updateCategory(transactionId, category, CategorizedBy.USER)

                if (!vpa.isNullOrBlank()) {
                    val existing = db.transactionDao().getById(transactionId)
                    db.vpaCategoryDao().upsert(
                        VpaCategory(
                            vpa = vpa,
                            category = category,
                            recipientName = existing?.recipientName ?: "",
                            lastUsed = System.currentTimeMillis()
                        )
                    )
                }

                Log.d("CategoryReceiver", "Categorized $transactionId as $category")
            } catch (e: Exception) {
                Log.e("CategoryReceiver", "Failed to save category", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
