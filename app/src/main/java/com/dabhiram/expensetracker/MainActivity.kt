package com.dabhiram.expensetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.dabhiram.expensetracker.data.db.AppDatabase
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.notification.EXTRA_NAV_DESTINATION
import com.dabhiram.expensetracker.notification.EXTRA_TRANSACTION_ID
import com.dabhiram.expensetracker.ui.navigation.AppNavigation
import com.dabhiram.expensetracker.ui.theme.ExpenseTrackerTheme

class MainActivity : ComponentActivity() {

    private var pendingTransactionId by mutableStateOf<String?>(null)
    private var pendingNavDestination by mutableStateOf<String?>(null)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // Only consume the notification intent on a fresh launch.
        // On Activity recreation (rotation, low-memory restore) the same
        // old intent is replayed — if we read it again pendingTransactionId
        // becomes non-null after the user already navigated away from Inbox.
        if (savedInstanceState == null) {
            pendingTransactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
            pendingNavDestination = intent.getStringExtra(EXTRA_NAV_DESTINATION)
        }

        val db = AppDatabase.getInstance(this)
        val repository = TransactionRepository(db)

        setContent {
            ExpenseTrackerTheme {
                AppNavigation(
                    repository = repository,
                    pendingTransactionId = pendingTransactionId,
                    pendingNavDestination = pendingNavDestination
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TRANSACTION_ID)?.let { pendingTransactionId = it }
        intent.getStringExtra(EXTRA_NAV_DESTINATION)?.let { pendingNavDestination = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
