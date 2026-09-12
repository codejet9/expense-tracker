package com.dabhiram.expensetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.ui.viewmodel.InboxViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InboxScreen(
    repository: TransactionRepository,
    pendingTransactionId: String? = null
) {
    val vm: InboxViewModel = viewModel(factory = InboxViewModel.Factory(repository))
    val uncategorized by vm.uncategorized.collectAsState()
    val categories by vm.categories.collectAsState()

    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    LaunchedEffect(pendingTransactionId, uncategorized) {
        if (pendingTransactionId != null && selectedTransaction == null) {
            selectedTransaction = uncategorized.find { it.id == pendingTransactionId }
        }
    }

    if (uncategorized.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "All caught up!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No transactions need categorization",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Text(
                    "${uncategorized.size} transactions need categorization",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(uncategorized, key = { it.id }) { txn ->
                SwipeToDeleteRow(
                    transaction = txn,
                    onDelete = { vm.deleteTransaction(txn) }
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
                vm.categorize(txn, category)
                selectedTransaction = null
            }
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
