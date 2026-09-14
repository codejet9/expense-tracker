package com.dabhiram.expensetracker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.ui.theme.CategoryColors
import com.dabhiram.expensetracker.ui.viewmodel.CategorySpend
import com.dabhiram.expensetracker.ui.viewmodel.DrilldownState
import com.dabhiram.expensetracker.ui.viewmodel.PagedTransactionState
import com.dabhiram.expensetracker.ui.viewmodel.ReportPeriod
import com.dabhiram.expensetracker.ui.viewmodel.ReportsViewModel
import com.dabhiram.expensetracker.ui.viewmodel.ReportsUiState
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun ReportsScreen(repository: TransactionRepository) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: ReportsViewModel = viewModel(factory = ReportsViewModel.Factory(repository, app))
    val state by vm.uiState.collectAsState()
    val categories by vm.categories.collectAsState()
    val pagedTxns by vm.pagedTxns.collectAsState()
    val drilldown by vm.drilldown.collectAsState()
    val groupedByDay = remember(pagedTxns.transactions) { groupByDay(pagedTxns.transactions) }

    var showRangePicker by remember { mutableStateOf(false) }
    var customRangeLabel by remember { mutableStateOf<String?>(null) }
    var excludedCategories by remember(state.period) { mutableStateOf(emptySet<String>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PeriodSelector(
                selected = state.period,
                onSelect = { vm.setPeriod(it) },
                onPickCustomRange = { showRangePicker = true },
                customRangeLabel = customRangeLabel
            )
        }

        item {
            TotalSpendCard(
                total = state.totalSpend,
                count = state.transactionCount,
                netSpend = state.netSpend,
                reimbursableTotal = state.reimbursableTotal
            )
        }

        item {
            SpendingInsightsCard(
                loading = state.insightsLoading,
                insights = state.spendingInsights,
                period = state.period,
                onRefresh = { vm.refreshInsights() }
            )
        }

        if (state.categoryBreakdown.isNotEmpty()) {
            item {
                CategoryDonutChart(
                    breakdown = state.categoryBreakdown,
                    excludedCategories = excludedCategories,
                    onToggleCategory = { cat ->
                        excludedCategories = if (cat in excludedCategories) excludedCategories - cat else excludedCategories + cat
                    }
                )
            }
            item {
                CategoryBreakdownCard(
                    breakdown = state.categoryBreakdown,
                    excludedCategories = excludedCategories,
                    onToggleCategory = { cat ->
                        excludedCategories = if (cat in excludedCategories) excludedCategories - cat else excludedCategories + cat
                    },
                    onDrilldown = { cat -> vm.loadDrilldown(cat) }
                )
            }
        }

        if (state.topMerchants.isNotEmpty()) {
            item {
                TopMerchantsCard(merchants = state.topMerchants)
            }
        }

        state.biggestExpense?.let { expense ->
            item {
                BiggestExpenseCard(transaction = expense)
            }
        }

        if (pagedTxns.totalPages > 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "All Transactions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { vm.prevPage() },
                        enabled = pagedTxns.currentPage > 0
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Previous page")
                    }
                    Text(
                        "${pagedTxns.currentPage + 1} / ${pagedTxns.totalPages}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { vm.nextPage() },
                        enabled = pagedTxns.currentPage < pagedTxns.totalPages - 1
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Next page")
                    }
                }
            }
            groupedByDay.forEach { day ->
                item {
                    DayHeader(label = day.label, total = day.total)
                }
                items(day.transactions, key = { it.id }) { transaction ->
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
                    HorizontalDivider()
                }
            }
        }
    }

    if (showRangePicker) {
        CustomRangeDialog(
            onDismiss = { showRangePicker = false },
            onConfirm = { startUtcMidnight, endUtcMidnight ->
                val (start, endExclusive) = utcMidnightToLocalRange(startUtcMidnight, endUtcMidnight)
                vm.setCustomRange(start, endExclusive)
                val labelFormat = SimpleDateFormat("d MMM", Locale.getDefault())
                customRangeLabel = "${labelFormat.format(Date(start))} – ${labelFormat.format(Date(endExclusive - 1))}"
                showRangePicker = false
            }
        )
    }

    drilldown?.let { ds ->
        CategoryDrilldownDialog(
            category = ds.category,
            transactions = ds.transactions,
            onDismiss = { vm.clearDrilldown() }
        )
    }
}

// Material3's DateRangePicker reports selections as UTC midnight for the
// picked calendar date, not a real instant — convert to the device's local
// start-of-day so the range lines up with how transaction timestamps
// (System.currentTimeMillis(), a real local instant) are stored.
private fun utcMidnightToLocalRange(startUtcMidnight: Long, endUtcMidnight: Long): Pair<Long, Long> {
    fun localStartOfDay(utcMidnight: Long): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return Calendar.getInstance().apply {
            clear()
            set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
    val start = localStartOfDay(startUtcMidnight)
    val endExclusive = localStartOfDay(endUtcMidnight) + 24 * 60 * 60 * 1000L
    return start to endExclusive
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 580.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column {
                DateRangePicker(
                    state = state,
                    modifier = Modifier.weight(1f, fill = false),
                    title = null,
                    headline = {
                        Text(
                            "Select date range",
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    showModeToggle = false
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val start = state.selectedStartDateMillis
                            val end = state.selectedEndDateMillis
                            if (start != null && end != null) onConfirm(start, end)
                        },
                        enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null
                    ) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun CategoryDrilldownDialog(
    category: String,
    transactions: List<Transaction>,
    onDismiss: () -> Unit
) {
    val df = DecimalFormat("#,##,##0.##")
    val dateFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
    val total = transactions.fold(BigDecimal.ZERO) { acc, t ->
        acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 580.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(category, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${transactions.size} transactions · ₹${df.format(total)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (transactions.isEmpty()) {
                    Text(
                        "No transactions in this period",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(transactions, key = { it.id }) { txn ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(txn.recipientName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        dateFmt.format(Date(txn.timestamp)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "₹${df.format(runCatching { BigDecimal(txn.amount) }.getOrDefault(BigDecimal.ZERO))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private data class DayGroup(val label: String, val total: String, val transactions: List<Transaction>)

private fun groupByDay(transactions: List<Transaction>): List<DayGroup> {
    val df = DecimalFormat("#,##,##0.##")
    val labelFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    val cal = Calendar.getInstance()

    return transactions
        .groupBy { txn ->
            cal.timeInMillis = txn.timestamp
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }
        .toSortedMap(compareByDescending { it })
        .map { (_, txns) ->
            val total = txns.fold(BigDecimal.ZERO) { acc, t ->
                acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
            }
            DayGroup(
                label = labelFormat.format(Date(txns.first().timestamp)),
                total = "₹${df.format(total)}",
                transactions = txns.sortedByDescending { it.timestamp }
            )
        }
}

@Composable
private fun DayHeader(label: String, total: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            total,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PeriodSelector(
    selected: ReportPeriod,
    onSelect: (ReportPeriod) -> Unit,
    onPickCustomRange: () -> Unit,
    customRangeLabel: String?
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selected == ReportPeriod.WEEK,
            onClick = { onSelect(ReportPeriod.WEEK) },
            label = { Text("This Week") }
        )
        FilterChip(
            selected = selected == ReportPeriod.MONTH,
            onClick = { onSelect(ReportPeriod.MONTH) },
            label = { Text("This Month") }
        )
        FilterChip(
            selected = selected == ReportPeriod.CUSTOM,
            onClick = onPickCustomRange,
            label = { Text(if (selected == ReportPeriod.CUSTOM) customRangeLabel ?: "Custom" else "Custom") },
            leadingIcon = {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = "Pick date range",
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

@Composable
private fun TotalSpendCard(total: String, count: Int, netSpend: String?, reimbursableTotal: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Total Spent", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                total,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "$count transactions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            if (netSpend != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Net spend: $netSpend",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Text(
                    "↩ $reimbursableTotal reimbursable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
private fun SpendingInsightsCard(
    loading: Boolean,
    insights: List<String>,
    period: ReportPeriod,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val comparisonLabel = when (period) {
        ReportPeriod.WEEK -> "vs last week"
        ReportPeriod.MONTH -> "vs last month"
        ReportPeriod.CUSTOM -> null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "AI Spending Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (comparisonLabel != null && !expanded) {
                    Text(
                        comparisonLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Icon(
                    if (expanded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Analyzing your spending...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (insights.isEmpty()) {
                        Text(
                            "No transactions to analyze yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        insights.forEach { bullet ->
                            Text("• $bullet", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onRefresh,
                            enabled = !loading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("↺ Refresh", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDonutChart(
    breakdown: List<CategorySpend>,
    excludedCategories: Set<String>,
    onToggleCategory: (String) -> Unit
) {
    val activeBreakdown = breakdown.filter { it.category !in excludedCategories }
    val activeTotal = activeBreakdown.fold(BigDecimal.ZERO) { acc, c -> acc + c.amount }
    val allTotal = breakdown.fold(BigDecimal.ZERO) { acc, c -> acc + c.amount }
    if (allTotal <= BigDecimal.ZERO) return

    val df = DecimalFormat("#,##,##0.##")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Category Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                    val diameter = minOf(size.width, size.height) * 0.85f
                    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                    val arcSize = Size(diameter, diameter)
                    var startAngle = -90f
                    val drawTotal = if (activeTotal > BigDecimal.ZERO) activeTotal.toDouble() else 1.0

                    breakdown.forEachIndexed { index, cat ->
                        val excluded = cat.category in excludedCategories
                        val sweep = if (excluded) 0f else (cat.amount.toDouble() / drawTotal).toFloat() * 360f
                        val color = if (excluded) Color.Gray.copy(alpha = 0.2f) else CategoryColors[index % CategoryColors.size]
                        if (!excluded) {
                            drawArc(
                                color = color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = diameter * 0.18f)
                            )
                            startAngle += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "₹${df.format(activeTotal)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (excludedCategories.isNotEmpty()) {
                        Text(
                            "of ₹${df.format(allTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            breakdown.take(6).forEachIndexed { index, cat ->
                val excluded = cat.category in excludedCategories
                val pct = if (activeTotal > BigDecimal.ZERO)
                    (cat.amount.toDouble() / activeTotal.toDouble() * 100).toInt() else 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleCategory(cat.category) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (excluded) Color.Gray.copy(alpha = 0.3f) else CategoryColors[index % CategoryColors.size],
                                shape = MaterialTheme.shapes.small
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        cat.category,
                        modifier = Modifier.weight(1f).alpha(if (excluded) 0.4f else 1f),
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (!excluded) {
                        Text(
                            "$pct%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Icon(
                            Icons.Default.VisibilityOff,
                            contentDescription = "Excluded",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(
    breakdown: List<CategorySpend>,
    excludedCategories: Set<String>,
    onToggleCategory: (String) -> Unit,
    onDrilldown: (String) -> Unit
) {
    val df = DecimalFormat("#,##,##0.##")
    val activeBreakdown = breakdown.filter { it.category !in excludedCategories }
    val max = activeBreakdown.firstOrNull()?.amount ?: breakdown.firstOrNull()?.amount ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "By Category",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Tap a row to see transactions · tap eye to exclude from chart",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            breakdown.forEachIndexed { index, cat ->
                val excluded = cat.category in excludedCategories
                Column(
                    modifier = Modifier
                        .clickable { onDrilldown(cat.category) }
                        .alpha(if (excluded) 0.45f else 1f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cat.category,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "₹${df.format(cat.amount)} (${cat.count})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { onToggleCategory(cat.category) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                if (excluded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (excluded) "Include in chart" else "Exclude from chart",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!excluded) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (cat.amount.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = CategoryColors[index % CategoryColors.size],
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopMerchantsCard(merchants: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Top Recipients",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            merchants.forEach { (name, amount) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        amount,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun BiggestExpenseCard(transaction: Transaction) {
    val df = DecimalFormat("#,##,##0.##")
    val amount = runCatching { "₹${df.format(transaction.amount.toBigDecimal())}" }.getOrDefault("₹${transaction.amount}")
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            overlineContent = { Text("Biggest Expense") },
            headlineContent = { Text(transaction.recipientName, fontWeight = FontWeight.Medium) },
            trailingContent = {
                Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            supportingContent = { CategoryChip(transaction.category) }
        )
    }
}
