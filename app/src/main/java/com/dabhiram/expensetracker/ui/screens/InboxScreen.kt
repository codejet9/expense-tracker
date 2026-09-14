package com.dabhiram.expensetracker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dabhiram.expensetracker.data.model.BudgetAlert
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.model.isExceeded
import com.dabhiram.expensetracker.ui.viewmodel.InboxViewModel
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InboxScreen(
    inboxVm: InboxViewModel,
    pendingTransactionId: String? = null
) {
    val uncategorized by inboxVm.uncategorized.collectAsState()
    val categories by inboxVm.categories.collectAsState()
    val budgetAlerts by inboxVm.budgetAlerts.collectAsState()

    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    LaunchedEffect(pendingTransactionId, uncategorized) {
        if (pendingTransactionId != null && selectedTransaction == null) {
            selectedTransaction = uncategorized.find { it.id == pendingTransactionId }
        }
    }

    val hasAlerts = budgetAlerts.isNotEmpty()
    val hasUncategorized = uncategorized.isNotEmpty()

    if (!hasAlerts && !hasUncategorized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "All caught up!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No transactions need categorization and no budget alerts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        if (hasAlerts) {
            item {
                BudgetAlertsCard(
                    alerts = budgetAlerts,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (hasUncategorized) {
            item {
                Text(
                    "${uncategorized.size} transaction${if (uncategorized.size == 1) "" else "s"} need categorization",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(uncategorized, key = { it.id }) { txn ->
                SwipeToDeleteRow(
                    transaction = txn,
                    onDelete = { inboxVm.deleteTransaction(txn) }
                ) {
                    UncategorizedTransactionItem(
                        transaction = txn,
                        onClick = { selectedTransaction = txn }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    selectedTransaction?.let { txn ->
        CategoryPickerSheet(
            transaction = txn,
            categories = categories,
            onDismiss = { selectedTransaction = null },
            onCategorize = { category ->
                inboxVm.categorize(txn, category)
                selectedTransaction = null
            }
        )
    }
}

@Composable
fun BudgetAlertsCard(
    alerts: List<BudgetAlert>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val fmt = DecimalFormat("#,##,##0.##")

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Budget Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    alerts.forEach { alert ->
                        BudgetAlertRow(alert = alert, fmt = fmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetAlertRow(alert: BudgetAlert, fmt: DecimalFormat) {
    val exceeded = alert.isExceeded
    val barColor = if (exceeded) MaterialTheme.colorScheme.error
    else Color(0xFFF59E0B) // amber for warning

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (exceeded) "🔴" else "🟡",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(6.dp))
            Text(
                alert.category,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "₹${fmt.format(alert.spent)} / ₹${fmt.format(alert.limit)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { alert.percentUsed.coerceAtMost(1f) },
            modifier = Modifier.fillMaxWidth(),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Text(
            if (exceeded) {
                val over = alert.spent - alert.limit
                "Exceeded by ₹${fmt.format(over)} this month"
            } else {
                val remaining = alert.limit - alert.spent
                "${(alert.percentUsed * 100).toInt()}% used · ₹${fmt.format(remaining)} remaining"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (exceeded) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UncategorizedTransactionItem(transaction: Transaction, onClick: () -> Unit) {
    val df = DecimalFormat("#,##,##0.##")
    val amount = runCatching { "₹${df.format(transaction.amount.toBigDecimal())}" }.getOrDefault("₹${transaction.amount}")
    val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(transaction.timestamp))

    ListItem(
        headlineContent = {
            Text(transaction.recipientName, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(
                "${transaction.recipientVpa ?: "Unknown VPA"} · $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                    Text("Categorize", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    transaction: Transaction,
    categories: List<String>,
    onDismiss: () -> Unit,
    onCategorize: (String) -> Unit
) {
    val df = DecimalFormat("#,##,##0.##")
    val amount = runCatching { "₹${df.format(transaction.amount.toBigDecimal())}" }.getOrDefault("₹${transaction.amount}")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                "What was this payment for?",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$amount to ${transaction.recipientName}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            val rows = categories.chunked(3)
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { category ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = false,
                            onClick = { onCategorize(category) },
                            label = {
                                Text(
                                    category,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
