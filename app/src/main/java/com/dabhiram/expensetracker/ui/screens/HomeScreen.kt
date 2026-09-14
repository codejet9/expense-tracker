package com.dabhiram.expensetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.dabhiram.expensetracker.llm.ApiKeyManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color
import com.dabhiram.expensetracker.data.model.BudgetAlert
import com.dabhiram.expensetracker.data.model.CATEGORY_LENT
import com.dabhiram.expensetracker.data.model.isExceeded
import com.dabhiram.expensetracker.data.model.CATEGORY_UNCATEGORIZED
import com.dabhiram.expensetracker.data.model.isSplit
import com.dabhiram.expensetracker.data.model.netAmount
import com.dabhiram.expensetracker.data.model.reimbursableAmount
import com.dabhiram.expensetracker.data.model.SourceApp
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repository: TransactionRepository, onNavigateToInbox: (() -> Unit)? = null) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(repository, application))
    val state by vm.uiState.collectAsState()
    val categories by vm.categories.collectAsState()
    val budgetAlerts by vm.budgetAlerts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showManageCategoriesDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Home",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = { showManageCategoriesDialog = true }) {
                    Icon(Icons.Default.Category, contentDescription = "Manage categories")
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add transaction")
                }
            }
        }

        TodaySummaryCard(
            total = state.totalToday,
            count = state.transactionCount
        )

        if (budgetAlerts.isNotEmpty()) {
            val exceededCount = budgetAlerts.count { it.isExceeded }
            val warningCount = budgetAlerts.size - exceededCount
            val label = buildString {
                if (exceededCount > 0) append("$exceededCount budget${if (exceededCount > 1) "s" else ""} exceeded")
                if (exceededCount > 0 && warningCount > 0) append(", ")
                if (warningCount > 0) append("$warningCount near limit")
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onNavigateToInbox?.invoke() },
                color = if (exceededCount > 0)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🔔 $label · View →",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (state.transactions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No UPI payments today",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enable the Accessibility Service to start tracking",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.transactions, key = { it.id }) { transaction ->
                    SwipeToDeleteTransactionRow(
                        transaction = transaction,
                        categories = categories,
                        onDelete = { vm.deleteTransaction(transaction) },
                        onEdit = { newAmount, newCategory, splitMyShare, splitPeopleCount ->
                            vm.editTransaction(transaction, newAmount, newCategory, splitMyShare, splitPeopleCount)
                        },
                        onSplit = { myShare, peopleCount ->
                            vm.applySplit(transaction, myShare, peopleCount)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddTransactionDialog(
            categories = categories,
            onDismiss = { showAddDialog = false },
            onSave = { amount, recipientName, recipientVpa, category, timestamp ->
                vm.addTransaction(amount, recipientName, recipientVpa, category, timestamp)
                showAddDialog = false
            },
            onSaveWithSplit = { amount, recipientName, recipientVpa, category, timestamp, myShare, peopleCount ->
                vm.addTransactionWithSplit(amount, recipientName, recipientVpa, category, timestamp, myShare, peopleCount)
                showAddDialog = false
            }
        )
    }

    if (showManageCategoriesDialog) {
        ManageCategoriesDialog(
            categories = categories,
            onDismiss = { showManageCategoriesDialog = false },
            onAdd = { name -> vm.addCategory(name) },
            onDelete = { name -> vm.deleteCategory(name) },
            usageCount = { name -> vm.categoryUsageCount(name) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTransactionDialog(
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (BigDecimal, String, String?, String, Long) -> Unit,
    onSaveWithSplit: ((BigDecimal, String, String?, String, Long, BigDecimal, Int) -> Unit)? = null
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val defaultPeopleAdd = ApiKeyManager.getDefaultSplitPeople(LocalContext.current)
    val df = DecimalFormat("#,##,##0.##")
    var amountText by remember { mutableStateOf("") }
    var recipientText by remember { mutableStateOf("") }
    var vpaText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(categories.firstOrNull() ?: "Other") }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    var splitExpanded by remember { mutableStateOf(false) }
    var myShareText by remember { mutableStateOf("") }
    var peopleCount by remember { mutableIntStateOf(defaultPeopleAdd) }

    val totalAmount = amountText.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val myShare = myShareText.toBigDecimalOrNull()
    val splitValid = myShare != null && totalAmount > BigDecimal.ZERO &&
        myShare > BigDecimal.ZERO && myShare < totalAmount
    val paidOnBehalfTotal = if (splitValid) totalAmount - myShare!! else null
    val perPerson = if (paidOnBehalfTotal != null && peopleCount >= 2)
        runCatching { paidOnBehalfTotal.divide(BigDecimal(peopleCount), 2, java.math.RoundingMode.HALF_UP) }.getOrNull()
    else null

    var selectedDateMidnight by remember { mutableStateOf(startOfTodayMillis()) }
    val displayDate = remember(selectedDateMidnight) {
        SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(selectedDateMidnight))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            // Fixed viewport = ~50% screen height. Content scrolls inside; dialog never grows.
            Box(modifier = Modifier.heightIn(max = screenHeight * 0.5f)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .animateContentSize()
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                OutlinedTextField(
                    value = recipientText,
                    onValueChange = { recipientText = it },
                    label = { Text("Recipient") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vpaText,
                    onValueChange = { vpaText = it },
                    label = { Text("VPA (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = showCategoryMenu,
                    onExpandedChange = { showCategoryMenu = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        categories.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    category = option
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = displayDate,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Pick date")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true }
                )

                if (onSaveWithSplit != null) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { splitExpanded = !splitExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CallSplit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Split details",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (splitExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (splitExpanded) {
                        SplitFields(
                            totalAmount = totalAmount,
                            df = df,
                            myShareText = myShareText,
                            onMyShareChange = { myShareText = it },
                            peopleCount = peopleCount,
                            onPeopleCountChange = { peopleCount = it },
                            myShare = myShare,
                            splitValid = splitValid,
                            perPerson = perPerson,
                            paidOnBehalfTotal = paidOnBehalfTotal
                        )
                    }
                }
            } // Column
            } // Box
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.toBigDecimalOrNull() ?: return@TextButton
                    if (recipientText.isBlank()) return@TextButton
                    val now = Calendar.getInstance()
                    val timestamp = Calendar.getInstance().apply {
                        timeInMillis = selectedDateMidnight
                        set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
                        set(Calendar.MINUTE, now.get(Calendar.MINUTE))
                        set(Calendar.SECOND, now.get(Calendar.SECOND))
                    }.timeInMillis
                    if (splitExpanded && splitValid && myShare != null) {
                        onSaveWithSplit?.invoke(amount, recipientText.trim(), vpaText.trim(), category, timestamp, myShare, peopleCount)
                    } else {
                        onSave(amount, recipientText.trim(), vpaText.trim(), category, timestamp)
                    }
                },
                enabled = amountText.toBigDecimalOrNull() != null && recipientText.isNotBlank() &&
                    (!splitExpanded || splitValid)
            ) { Text(if (splitExpanded && onSaveWithSplit != null) "Save & Split" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        DatePickerDialogCompact(
            initialMillis = selectedDateMidnight,
            onDismiss = { showDatePicker = false },
            onConfirm = { pickedUtcMidnight ->
                selectedDateMidnight = utcMidnightToLocalStartOfDay(pickedUtcMidnight)
                showDatePicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogCompact(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column {
                DatePicker(state = state, modifier = Modifier.weight(1f, fill = false))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { state.selectedDateMillis?.let(onConfirm) },
                        enabled = state.selectedDateMillis != null
                    ) { Text("OK") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageCategoriesDialog(
    categories: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    usageCount: suspend (String) -> Int
) {
    val scope = rememberCoroutineScope()
    var newCategoryText by remember { mutableStateOf("") }
    var duplicateError by remember { mutableStateOf(false) }
    // Name + how many existing transactions use it — only asked about when
    // that count is > 0, so a genuinely unused category deletes immediately.
    var pendingDelete by remember { mutableStateOf<Pair<String, Int>?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Manage categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(categories, key = { it }) { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(
                                enabled = categories.size > 1,
                                onClick = {
                                    scope.launch {
                                        val count = usageCount(name)
                                        if (count > 0) pendingDelete = name to count else onDelete(name)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete $name")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newCategoryText,
                    onValueChange = { newCategoryText = it; duplicateError = false },
                    label = { Text("New category") },
                    isError = duplicateError,
                    supportingText = { if (duplicateError) Text("Already exists") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val trimmed = newCategoryText.trim()
                        if (trimmed.isBlank()) return@Button
                        if (categories.any { it.equals(trimmed, ignoreCase = true) }) {
                            duplicateError = true
                            return@Button
                        }
                        onAdd(trimmed)
                        newCategoryText = ""
                    }) { Text("Add") }
                }
            }
        }
    }

    pendingDelete?.let { (name, count) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = {
                Text(
                    "$count transaction${if (count != 1) "s" else ""} currently use this category. " +
                        "They'll keep it, but you won't be able to pick it for new ones."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(name)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// Material3's DatePicker reports the selection as UTC midnight for the picked
// calendar date, not a real instant — convert to the device's local
// start-of-day (same fix as the custom date-range picker in ReportsScreen).
private fun utcMidnightToLocalStartOfDay(utcMidnight: Long): Long {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return Calendar.getInstance().apply {
        clear()
        set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

@Composable
private fun TodaySummaryCard(total: String, count: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                total,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$count transaction${if (count != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteTransactionRow(
    transaction: Transaction,
    categories: List<String>,
    onDelete: () -> Unit,
    onEdit: (newAmount: BigDecimal, newCategory: String, splitMyShare: BigDecimal?, splitPeopleCount: Int) -> Unit,
    onSplit: ((myShare: BigDecimal, peopleCount: Int) -> Unit)? = null
) {
    var showEditDialog by remember(transaction.id) { mutableStateOf(false) }
    var showSplitDialog by remember(transaction.id) { mutableStateOf(false) }

    SwipeToDeleteRow(
        transaction = transaction,
        onDelete = onDelete,
        onSplitSwipe = if (onSplit != null) { { showSplitDialog = true } } else null
    ) {
        Box(modifier = Modifier.clickable { showEditDialog = true }) {
            TransactionRow(transaction = transaction)
        }
    }

    if (showEditDialog) {
        EditTransactionDialog(
            transaction = transaction,
            categories = categories,
            onDismiss = { showEditDialog = false },
            onSave = { newAmount, newCategory, splitMyShare, splitPeopleCount ->
                onEdit(newAmount, newCategory, splitMyShare, splitPeopleCount)
                showEditDialog = false
            }
        )
    }

    if (showSplitDialog && onSplit != null) {
        SplitExpenseDialog(
            transaction = transaction,
            onDismiss = { showSplitDialog = false },
            onSplit = { myShare, peopleCount ->
                onSplit(myShare, peopleCount)
                showSplitDialog = false
            }
        )
    }
}

@Composable
private fun SplitFields(
    totalAmount: BigDecimal,
    df: DecimalFormat,
    myShareText: String,
    onMyShareChange: (String) -> Unit,
    peopleCount: Int,
    onPeopleCountChange: (Int) -> Unit,
    myShare: BigDecimal?,
    splitValid: Boolean,
    perPerson: BigDecimal?,
    paidOnBehalfTotal: BigDecimal?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Total paid: ₹${df.format(totalAmount)}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        OutlinedTextField(
            value = myShareText,
            onValueChange = onMyShareChange,
            label = { Text("My personal share") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
            ),
            isError = myShareText.isNotEmpty() && (myShare == null || myShare >= totalAmount)
        )
        if (paidOnBehalfTotal != null) {
            Text(
                "Lent to group: ₹${df.format(paidOnBehalfTotal)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "People (incl. you)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { if (peopleCount > 2) onPeopleCountChange(peopleCount - 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer")
            }
            Text(
                "$peopleCount",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(
                onClick = { onPeopleCountChange(peopleCount + 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "More")
            }
        }
        if (myShareText.isNotEmpty() && myShare != null && myShare >= totalAmount && totalAmount > BigDecimal.ZERO) {
            Text(
                "My share must be less than ₹${df.format(totalAmount)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (splitValid && perPerson != null && myShare != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val myTotal = myShare + perPerson
                    val owed = paidOnBehalfTotal!! - perPerson
                    Text("Your entry: ₹${df.format(myTotal)} (₹${df.format(myShare)} + ₹${df.format(perPerson)})", style = MaterialTheme.typography.bodySmall)
                    Text("₹${df.format(owed)} owed (₹${df.format(perPerson)} × ${peopleCount - 1})", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTransactionDialog(
    transaction: Transaction,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (newAmount: BigDecimal, newCategory: String, splitMyShare: BigDecimal?, splitPeopleCount: Int) -> Unit
) {
    val df = DecimalFormat("#,##,##0.##")
    val defaultPeopleEdit = ApiKeyManager.getDefaultSplitPeople(LocalContext.current)

    var amountText by remember { mutableStateOf(transaction.amount) }
    var category by remember { mutableStateOf(transaction.category) }
    var showCategoryMenu by remember { mutableStateOf(false) }

    // Prefill split from existing split metadata
    var splitExpanded by remember { mutableStateOf(transaction.isSplit) }
    var myShareText by remember { mutableStateOf(transaction.splitMyShare ?: "") }
    var peopleCount by remember { mutableIntStateOf(if (transaction.splitPeopleCount >= 2) transaction.splitPeopleCount else defaultPeopleEdit) }

    val totalAmount = amountText.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val myShare = myShareText.toBigDecimalOrNull()
    val splitValid = myShare != null && totalAmount > BigDecimal.ZERO &&
        myShare > BigDecimal.ZERO && myShare < totalAmount
    val paidOnBehalfTotal = if (splitValid) totalAmount - myShare!! else null
    val perPerson = if (paidOnBehalfTotal != null && peopleCount >= 2)
        runCatching { paidOnBehalfTotal.divide(BigDecimal(peopleCount), 2, java.math.RoundingMode.HALF_UP) }.getOrNull()
    else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transaction") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .animateContentSize()
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                ExposedDropdownMenuBox(
                    expanded = showCategoryMenu,
                    onExpandedChange = { showCategoryMenu = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        categories.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    category = option
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { splitExpanded = !splitExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CallSplit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Split details",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (splitExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (splitExpanded) {
                    SplitFields(
                        totalAmount = totalAmount,
                        df = df,
                        myShareText = myShareText,
                        onMyShareChange = { myShareText = it },
                        peopleCount = peopleCount,
                        onPeopleCountChange = { peopleCount = it },
                        myShare = myShare,
                        splitValid = splitValid,
                        perPerson = perPerson,
                        paidOnBehalfTotal = paidOnBehalfTotal
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.toBigDecimalOrNull() ?: return@TextButton
                    if (splitExpanded && splitValid && myShare != null) {
                        onSave(amount, category, myShare, peopleCount)
                    } else {
                        onSave(amount, category, null, 0)
                    }
                },
                enabled = if (splitExpanded) splitValid && amountText.toBigDecimalOrNull() != null
                          else amountText.toBigDecimalOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SplitExpenseDialog(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onSplit: (myShare: BigDecimal, peopleCount: Int) -> Unit
) {
    val df = DecimalFormat("#,##,##0.##")
    val totalAmount = runCatching { transaction.amount.toBigDecimal() }.getOrDefault(BigDecimal.ZERO)
    val defaultPeopleSwipe = ApiKeyManager.getDefaultSplitPeople(LocalContext.current)

    var myShareText by remember { mutableStateOf(transaction.splitMyShare ?: "") }
    var peopleCount by remember { mutableIntStateOf(if (transaction.splitPeopleCount >= 2) transaction.splitPeopleCount else defaultPeopleSwipe) }

    val myShare = myShareText.toBigDecimalOrNull()
    val splitValid = myShare != null && totalAmount > BigDecimal.ZERO &&
        myShare > BigDecimal.ZERO && myShare < totalAmount
    val paidOnBehalfTotal = if (splitValid) totalAmount - myShare!! else null
    val perPerson = if (paidOnBehalfTotal != null && peopleCount >= 2)
        runCatching { paidOnBehalfTotal.divide(BigDecimal(peopleCount), 2, java.math.RoundingMode.HALF_UP) }.getOrNull()
    else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split expense") },
        text = {
            SplitFields(
                totalAmount = totalAmount,
                df = df,
                myShareText = myShareText,
                onMyShareChange = { myShareText = it },
                peopleCount = peopleCount,
                onPeopleCountChange = { peopleCount = it },
                myShare = myShare,
                splitValid = splitValid,
                perPerson = perPerson,
                paidOnBehalfTotal = paidOnBehalfTotal
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (splitValid && myShare != null) onSplit(myShare, peopleCount) },
                enabled = splitValid
            ) { Text("Split") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// Generic swipe-to-delete/split wrapper.
// Swipe left (EndToStart)  → confirm delete.
// Swipe right (StartToEnd) → trigger onSplitSwipe (if non-null).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    transaction: Transaction,
    onDelete: () -> Unit,
    onSplitSwipe: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var pendingDelete by remember(transaction.id) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { pendingDelete = true; false }
                SwipeToDismissBoxValue.StartToEnd -> { onSplitSwipe?.invoke(); false }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = onSplitSwipe != null,
        backgroundContent = {
            val target = dismissState.targetValue
            val bgColor = when (target) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            }
            val alignment = when (target) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (target) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.CallSplit
                else -> null
            }
            val iconTint = when (target) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier.fillMaxSize().background(bgColor).padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = iconTint)
            }
        }
    ) {
        // 5% | 90% content | 5% layout — thin columns, full-height icons.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(0.05f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (onSplitSwipe != null) {
                    Icon(
                        Icons.Default.CallSplit,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).alpha(0.5f),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Box(modifier = Modifier.weight(0.9f)) {
                content()
            }
            Box(
                modifier = Modifier.weight(0.05f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).alpha(0.5f),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete transaction?") },
            text = {
                Text("₹${transaction.amount} to ${transaction.recipientName} will be permanently removed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    pendingDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun TransactionRow(transaction: Transaction) {
    val df = DecimalFormat("#,##,##0.##")
    val displayAmount = "₹${df.format(transaction.netAmount)}"
    val reimbursable = transaction.reimbursableAmount
    val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault())
        .format(Date(transaction.timestamp))

    ListItem(
        headlineContent = {
            Text(
                transaction.recipientName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(transaction.category)
                Text(
                    timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        displayAmount,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (reimbursable > java.math.BigDecimal.ZERO) {
                    Text(
                        "₹${df.format(reimbursable)} owed",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE65100)
                    )
                } else {
                    Text(
                        transaction.sourceApp.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

@Composable
fun CategoryChip(category: String) {
    val containerColor = when (category) {
        CATEGORY_LENT -> Color(0xFFFFF8E1)
        CATEGORY_UNCATEGORIZED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (category) {
        CATEGORY_LENT -> Color(0xFFE65100)
        CATEGORY_UNCATEGORIZED -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val label = when (category) {
        CATEGORY_LENT -> "↩ $category"
        else -> category
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        modifier = Modifier
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

private fun SourceApp.displayName(): String = when (this) {
    SourceApp.GPAY -> "GPay"
    SourceApp.PHONEPE -> "PhonePe"
    SourceApp.SCREENSHOT -> "Screenshot"
    SourceApp.MANUAL -> "Manual"
}
