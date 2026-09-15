package com.dabhiram.expensetracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dabhiram.expensetracker.data.InsightsCache
import com.dabhiram.expensetracker.data.TransactionEventBus
import com.dabhiram.expensetracker.data.model.CATEGORY_LENT
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.model.isSplit
import com.dabhiram.expensetracker.data.model.netAmount
import com.dabhiram.expensetracker.data.model.reimbursableAmount
import com.dabhiram.expensetracker.data.repository.TransactionRepository
import com.dabhiram.expensetracker.llm.ApiKeyManager
import com.dabhiram.expensetracker.llm.LlmSpendingAnalyzer
import com.dabhiram.expensetracker.llm.MultiProviderLlmClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

enum class ReportPeriod { WEEK, MONTH, CUSTOM }

data class CategorySpend(val category: String, val amount: BigDecimal, val count: Int)

data class PagedTransactionState(
    val transactions: List<Transaction> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0
)

private const val PAGE_SIZE = 10

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.WEEK,
    val totalSpend: String = "₹0",
    val categoryBreakdown: List<CategorySpend> = emptyList(),
    val topMerchants: List<Pair<String, String>> = emptyList(),
    val biggestExpense: Transaction? = null,
    val transactionCount: Int = 0,
    val reimbursableTotal: String? = null,
    val netSpend: String? = null,
    val spendingInsights: List<String> = emptyList(),
    val insightsLoading: Boolean = false
)

data class DrilldownState(val category: String, val transactions: List<Transaction>)

class ReportsViewModel(
    private val repository: TransactionRepository,
    private val application: Application
) : ViewModel() {

    private val _period = MutableStateFlow(ReportPeriod.WEEK)
    private val _customRange = MutableStateFlow<Pair<Long, Long>?>(null)
    private val _txnPage = MutableStateFlow(0)
    private val _insightsLoading = MutableStateFlow(false)
    private val _insightsText = MutableStateFlow<List<String>>(emptyList())

    private val periodTransactions = combine(_period, _customRange) { period, range -> period to range }
        .flatMapLatest { (period, range) ->
            when (period) {
                ReportPeriod.WEEK -> repository.weekTransactions()
                ReportPeriod.MONTH -> repository.monthTransactions()
                ReportPeriod.CUSTOM -> range?.let { (start, end) -> repository.rangeTransactions(start, end) }
                    ?: flowOf(emptyList())
            }
        }

    val uiState: StateFlow<ReportsUiState> = combine(
        _period,
        periodTransactions,
        _insightsLoading,
        _insightsText
    ) { period, txns, loading, insights ->
        buildUiState(period, txns, loading, insights)
    }.stateIn(viewModelScope, SharingStarted.Lazily, ReportsUiState())

    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _drilldown = MutableStateFlow<DrilldownState?>(null)
    val drilldown: StateFlow<DrilldownState?> = _drilldown.asStateFlow()

    fun loadDrilldown(category: String) {
        viewModelScope.launch {
            val (start, end) = periodBounds(_period.value, _customRange.value)
            val txns = repository.getTransactionsByCategory(category, start, end)
            _drilldown.value = DrilldownState(category, txns)
        }
    }

    fun clearDrilldown() { _drilldown.value = null }

    val pagedTxns: StateFlow<PagedTransactionState> = combine(
        _period, _customRange, _txnPage, TransactionEventBus.lastChangeMs
    ) { period, range, page, _ -> Triple(period, range, page) }
        .flatMapLatest { (period, range, page) ->
            flow {
                val (start, end) = periodBounds(period, range)
                val total = repository.countTransactions(start, end)
                val totalPages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
                val safePage = page.coerceIn(0, totalPages - 1)
                val items = repository.getTransactionsPaged(start, end, safePage, PAGE_SIZE)
                emit(PagedTransactionState(items, safePage, totalPages))
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, PagedTransactionState())

    fun nextPage() { _txnPage.value++ }
    fun prevPage() { if (_txnPage.value > 0) _txnPage.value-- }

    private fun periodBounds(period: ReportPeriod, customRange: Pair<Long, Long>?): Pair<Long, Long> =
        when (period) {
            ReportPeriod.WEEK -> repository.weekStartMs() to Long.MAX_VALUE
            ReportPeriod.MONTH -> repository.monthStartMs() to Long.MAX_VALUE
            ReportPeriod.CUSTOM -> customRange ?: (0L to Long.MAX_VALUE)
        }

    // Per-period jobs so switching periods never cancels an in-flight LLM call for another period.
    private val insightsJobs = mutableMapOf<ReportPeriod, Job>()
    private val llmInProgress = mutableSetOf<ReportPeriod>()

    init {
        _period.onEach { _txnPage.value = 0 }.launchIn(viewModelScope)
        _customRange.onEach { _txnPage.value = 0 }.launchIn(viewModelScope)

        _period.onEach { period ->
            triggerInsightsRefresh(period, debounceMs = 0)
        }.launchIn(viewModelScope)

        TransactionEventBus.lastChangeMs
            .onEach { _ ->
                _drilldown.value?.let { ds -> loadDrilldown(ds.category) }
                triggerInsightsRefresh(_period.value, debounceMs = 3000)
            }
            .launchIn(viewModelScope)
    }

    fun refreshInsights() {
        InsightsCache.invalidateAll(application)
        insightsJobs.values.forEach { it.cancel() }
        insightsJobs.clear()
        llmInProgress.clear()
        triggerInsightsRefresh(_period.value, debounceMs = 0)
    }

    fun setPeriod(period: ReportPeriod) {
        _period.value = period
    }

    fun setCustomRange(startMs: Long, endMs: Long) {
        val customAlreadySelected = _period.value == ReportPeriod.CUSTOM
        _customRange.value = startMs to endMs
        _period.value = ReportPeriod.CUSTOM
        if (customAlreadySelected) triggerInsightsRefresh(ReportPeriod.CUSTOM, debounceMs = 0)
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch { repository.deleteTransaction(transaction) }
    }

    fun applySplit(transaction: Transaction, myShare: BigDecimal, peopleCount: Int) {
        viewModelScope.launch {
            repository.updateTransaction(
                transaction.copy(
                    splitMyShare = myShare.toPlainString(),
                    splitPeopleCount = peopleCount,
                    categorizedBy = CategorizedBy.USER
                )
            )
        }
    }

    fun editTransaction(
        transaction: Transaction,
        newAmount: BigDecimal,
        newCategory: String,
        splitMyShare: BigDecimal? = null,
        splitPeopleCount: Int = 0
    ) {
        viewModelScope.launch {
            repository.updateTransaction(
                transaction.copy(
                    amount = newAmount.toPlainString(),
                    category = newCategory,
                    categorizedBy = CategorizedBy.USER,
                    splitMyShare = splitMyShare?.toPlainString(),
                    splitPeopleCount = splitPeopleCount
                )
            )
        }
    }

    private fun triggerInsightsRefresh(period: ReportPeriod, debounceMs: Long) {
        if (period != ReportPeriod.CUSTOM && period in llmInProgress) {
            if (_period.value == period) _insightsLoading.value = true
            return
        }

        insightsJobs[period]?.cancel()
        if (period == ReportPeriod.CUSTOM) llmInProgress.remove(period)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            if (debounceMs > 0) delay(debounceMs)

            val lastChange = TransactionEventBus.lastChangeMs.value
            if (period == ReportPeriod.WEEK) {
                InsightsCache.getWeek(application, lastChange, repository.weekStartMs())?.let { cached ->
                    if (_period.value == period) {
                        _insightsText.value = cached
                        _insightsLoading.value = false
                    }
                    return@launch
                }
            } else if (period == ReportPeriod.MONTH) {
                InsightsCache.getMonth(application, lastChange, repository.monthStartMs())?.let { cached ->
                    if (_period.value == period) {
                        _insightsText.value = cached
                        _insightsLoading.value = false
                    }
                    return@launch
                }
            }

            val keys = ApiKeyManager.providerKeys(application)
            if (!MultiProviderLlmClient.configuredProviders(keys).any()) {
                if (_period.value == period) {
                    _insightsText.value = listOf("Configure an API key in Settings to enable AI insights.")
                    _insightsLoading.value = false
                }
                return@launch
            }

            // Cache miss or CUSTOM — fetch transactions and call LLM.
            if (_period.value == period) _insightsLoading.value = true
            val transactionVersion = lastChange
            llmInProgress.add(period)
            try {
                val (start, end) = periodBounds(period, _customRange.value)
                val txns = repository.getTransactionsPaged(start, end, 0, Int.MAX_VALUE)
                if (txns.isEmpty()) {
                    if (_period.value == period) {
                        _insightsText.value = emptyList()
                        _insightsLoading.value = false
                    }
                    return@launch
                }

                val currentSpend = txns.groupBy { it.category }
                    .mapValues { (_, list) -> list.totalAmount() }
                val currentTotal = txns.totalAmount()
                val comparisonSpend: Map<String, BigDecimal>? = when (period) {
                    ReportPeriod.WEEK -> repository.getPreviousWeekTransactions()
                        .groupBy { it.category }.mapValues { (_, l) -> l.totalAmount() }
                    ReportPeriod.MONTH -> repository.getPreviousMonthTransactions()
                        .groupBy { it.category }.mapValues { (_, l) -> l.totalAmount() }
                    ReportPeriod.CUSTOM -> null
                }
                val periodLabel = when (period) {
                    ReportPeriod.WEEK -> "week"; ReportPeriod.MONTH -> "month"
                    ReportPeriod.CUSTOM -> "period"
                }
                val budgetViolations = buildBudgetViolations()
                val bullets = LlmSpendingAnalyzer.analyze(
                    currentSpend, currentTotal, comparisonSpend, periodLabel, keys, budgetViolations
                )
                if (bullets != null) {
                    val dataStillCurrent = TransactionEventBus.lastChangeMs.value == transactionVersion
                    if (dataStillCurrent) {
                        // Cache completed calls even if the user switched to another period.
                        when (period) {
                            ReportPeriod.WEEK -> InsightsCache.setWeek(application, bullets)
                            ReportPeriod.MONTH -> InsightsCache.setMonth(application, bullets)
                            ReportPeriod.CUSTOM -> Unit
                        }
                        if (_period.value == period) _insightsText.value = bullets
                    }
                } else {
                    if (_period.value == period && _insightsText.value.isEmpty()) {
                        _insightsText.value = listOf("Could not load insights — will retry on next transaction change.")
                    }
                }
            } finally {
                if (insightsJobs[period] == currentCoroutineContext()[Job]) {
                    llmInProgress.remove(period)
                    insightsJobs.remove(period)
                    if (_period.value == period) _insightsLoading.value = false
                    if (
                        _period.value == period &&
                        TransactionEventBus.lastChangeMs.value != transactionVersion
                    ) {
                        triggerInsightsRefresh(period, debounceMs = 0)
                    }
                }
            }
        }
        insightsJobs[period] = job
        job.start()
    }

    private fun buildUiState(
        period: ReportPeriod,
        txns: List<Transaction>,
        insightsLoading: Boolean,
        insights: List<String>
    ): ReportsUiState {
        if (txns.isEmpty()) return ReportsUiState(
            period = period,
            insightsLoading = insightsLoading,
            spendingInsights = insights
        )

        val df = DecimalFormat("#,##,##0.##")
        val total = txns.totalAmount()

        // Reimbursable = Case 1 (Lent category, non-split) + Case 2 (split metadata)
        val reimbursable = txns.fold(BigDecimal.ZERO) { acc, t ->
            acc + when {
                t.category == CATEGORY_LENT && !t.isSplit ->
                    runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
                else -> t.reimbursableAmount
            }
        }
        val net = total - reimbursable

        // For split txns: netAmount → actual category, reimbursableAmount → Lent slice.
        val effectiveBreakdown = mutableMapOf<String, Pair<BigDecimal, Int>>()
        for (t in txns) {
            val tAmount = runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
            if (t.isSplit) {
                val cat = t.category
                val prev = effectiveBreakdown[cat] ?: (BigDecimal.ZERO to 0)
                effectiveBreakdown[cat] = (prev.first + t.netAmount) to (prev.second + 1)
                val reimb = t.reimbursableAmount
                if (reimb > BigDecimal.ZERO) {
                    val prevL = effectiveBreakdown[CATEGORY_LENT] ?: (BigDecimal.ZERO to 0)
                    effectiveBreakdown[CATEGORY_LENT] = (prevL.first + reimb) to (prevL.second + 1)
                }
            } else {
                val cat = t.category
                val prev = effectiveBreakdown[cat] ?: (BigDecimal.ZERO to 0)
                effectiveBreakdown[cat] = (prev.first + tAmount) to (prev.second + 1)
            }
        }
        val breakdown = effectiveBreakdown
            .map { (cat, pair) -> CategorySpend(cat, pair.first, pair.second) }
            .sortedByDescending { it.amount }

        val topMerchants = txns
            .filter { !it.recipientVpa.isNullOrBlank() }
            .groupBy { it.recipientName }
            .map { (name, list) -> Pair(name, "₹${df.format(list.totalAmount())}") }
            .sortedByDescending { (_, amtStr) -> amtStr.replace("[₹,]".toRegex(), "").toBigDecimalOrNull() ?: BigDecimal.ZERO }
            .take(5)

        val biggest = txns.maxByOrNull {
            runCatching { BigDecimal(it.amount) }.getOrDefault(BigDecimal.ZERO)
        }

        return ReportsUiState(
            period = period,
            totalSpend = "₹${df.format(total)}",
            categoryBreakdown = breakdown,
            topMerchants = topMerchants,
            biggestExpense = biggest,
            transactionCount = txns.size,
            reimbursableTotal = if (reimbursable > BigDecimal.ZERO) "₹${df.format(reimbursable)}" else null,
            netSpend = if (reimbursable > BigDecimal.ZERO) "₹${df.format(net)}" else null,
            spendingInsights = insights,
            insightsLoading = insightsLoading
        )
    }

    private suspend fun buildBudgetViolations(): Map<String, Pair<BigDecimal, BigDecimal>> {
        val cats = repository.allCategoriesFlow.first()
        val monthStart = repository.monthStartMs()
        val monthTxns = repository.getTransactionsPaged(monthStart, Long.MAX_VALUE, 0, Int.MAX_VALUE)
        val monthSpend = monthTxns.groupBy { it.category }
            .mapValues { (_, list) ->
                list.fold(BigDecimal.ZERO) { acc, t ->
                    acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
                }
            }
        return cats.mapNotNull { cat ->
            val limit = cat.budget?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                ?: return@mapNotNull null
            val spent = monthSpend[cat.name] ?: BigDecimal.ZERO
            if (spent < limit * BigDecimal("0.80")) return@mapNotNull null
            cat.name to (spent to limit)
        }.toMap()
    }

    class Factory(private val repository: TransactionRepository, private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            ReportsViewModel(repository, application) as T
    }
}

private fun List<Transaction>.totalAmount(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, t ->
        acc + runCatching { BigDecimal(t.amount) }.getOrDefault(BigDecimal.ZERO)
    }
