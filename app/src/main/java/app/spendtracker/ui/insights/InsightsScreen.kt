package app.spendtracker.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.TxnRow
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.components.CategoryBars
import app.spendtracker.ui.components.CategorySlice
import app.spendtracker.ui.components.EmptyState
import app.spendtracker.ui.components.MerchantTotal
import app.spendtracker.ui.components.MonthSwitcher
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.components.TrendLine
import app.spendtracker.ui.components.byCategory
import app.spendtracker.ui.components.counts
import app.spendtracker.ui.components.income
import app.spendtracker.ui.components.spend
import app.spendtracker.ui.components.topMerchants
import app.spendtracker.ui.theme.AmountStyle
import app.spendtracker.ui.theme.LocalMoneyColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class CategoryChange(val name: String, val now: Long, val before: Long)

data class InsightsState(
    val month: YearMonth = YearMonth.now(),
    val spent: Long = 0,
    val income: Long = 0,
    val lastSpent: Long = 0,
    val slices: List<CategorySlice> = emptyList(),
    val cumulative: List<Long> = emptyList(),
    val lastCumulative: List<Long> = emptyList(),
    val merchants: List<MerchantTotal> = emptyList(),
    val biggestDay: Pair<LocalDate, Long>? = null,
    val changes: List<CategoryChange> = emptyList(),
    val txnCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(private val repo: TransactionRepository) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())

    val state: StateFlow<InsightsState> = month.flatMapLatest { m ->
        combine(repo.observeMonth(m), repo.observeMonth(m.minusMonths(1))) { cur, prev -> build(m, cur, prev) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    fun shift(by: Long) {
        val next = month.value.plusMonths(by)
        if (!next.isAfter(YearMonth.now())) month.value = next
    }

    private fun build(m: YearMonth, cur: List<TxnRow>, prev: List<TxnRow>): InsightsState {
        val zone = ZoneId.systemDefault()
        fun daily(rows: List<TxnRow>, days: Int): LongArray {
            val arr = LongArray(days)
            rows.filter { it.counts() && it.t.type == TxnType.DEBIT }.forEach {
                val d = Instant.ofEpochMilli(it.t.timestamp).atZone(zone).dayOfMonth
                if (d in 1..days) arr[d - 1] += it.t.amountPaise
            }
            return arr
        }
        val days = m.lengthOfMonth()
        val today = LocalDate.now()
        val visibleDays = if (m == YearMonth.from(today)) today.dayOfMonth else days
        val curDaily = daily(cur, days)
        val prevDaily = daily(prev, m.minusMonths(1).lengthOfMonth())
        fun cumulative(arr: LongArray, n: Int): List<Long> {
            var acc = 0L
            return (0 until n.coerceAtMost(arr.size)).map { acc += arr[it]; acc }
        }
        val maxIdx = curDaily.indices.maxByOrNull { curDaily[it] }
        val curSlices = cur.byCategory()
        val prevByName = prev.byCategory().associate { it.name to it.amount }
        return InsightsState(
            month = m,
            spent = cur.spend(),
            income = cur.income(),
            lastSpent = prev.spend(),
            slices = curSlices,
            cumulative = cumulative(curDaily, visibleDays),
            lastCumulative = cumulative(prevDaily, prevDaily.size),
            merchants = cur.topMerchants(6),
            biggestDay = maxIdx?.takeIf { curDaily[it] > 0 }?.let { m.atDay(it + 1) to curDaily[it] },
            changes = curSlices.take(6).map { CategoryChange(it.name, it.amount, prevByName[it.name] ?: 0L) },
            txnCount = cur.count { it.counts() },
        )
    }
}

@Composable
fun InsightsScreen(vm: InsightsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val money = LocalMoneyColors.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.statusBars) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Insights", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    MonthSwitcher(
                        s.month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)),
                        onPrev = { vm.shift(-1) }, onNext = { vm.shift(1) },
                        canNext = s.month.isBefore(YearMonth.now()),
                    )
                }
            }
            item {
                SectionCard {
                    SectionTitle("This month vs last")
                    Spacer(Modifier.height(10.dp))
                    Text(Money.format(s.spent), style = MaterialTheme.typography.displaySmall.merge(AmountStyle))
                    val diff = s.spent - s.lastSpent
                    val pct = if (s.lastSpent > 0) (diff * 100 / s.lastSpent) else null
                    Text(
                        when {
                            pct == null -> "No spend recorded last month"
                            diff > 0 -> "${Money.format(diff)} more than last month (+$pct%)"
                            diff < 0 -> "${Money.format(-diff)} less than last month ($pct%)"
                            else -> "Same as last month"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (diff <= 0) money.credit else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    TrendLine(s.cumulative, Modifier.fillMaxWidth().height(160.dp), compare = s.lastCumulative)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(MaterialTheme.colorScheme.primary, "This month (cumulative)")
                        Spacer(Modifier.width(16.dp))
                        Legend(MaterialTheme.colorScheme.outline, "Last month")
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        MiniStat("Income", Money.format(s.income), Modifier.weight(1f))
                        MiniStat("Transactions", s.txnCount.toString(), Modifier.weight(1f))
                        MiniStat(
                            "Biggest day",
                            s.biggestDay?.let { "${it.first.dayOfMonth} ${it.first.month.name.take(3).lowercase().replaceFirstChar(Char::titlecase)}" } ?: "—",
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            if (s.slices.isEmpty()) {
                item { EmptyState("No spend this month", "Insights fill in as transactions arrive.") }
            } else {
                item {
                    SectionCard {
                        SectionTitle("Spend by category")
                        Spacer(Modifier.height(16.dp))
                        CategoryBars(s.slices)
                    }
                }
                item {
                    SectionCard {
                        SectionTitle("Top merchants")
                        Spacer(Modifier.height(12.dp))
                        s.merchants.forEachIndexed { i, mt ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                                Box(Modifier.size(8.dp).clip(CircleShape).background(mt.colour))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(mt.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${mt.count} payment${if (mt.count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(Money.format(mt.amount), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                            }
                        }
                    }
                }
                item {
                    SectionCard {
                        SectionTitle("Category change vs last month")
                        Spacer(Modifier.height(12.dp))
                        s.changes.forEach { c ->
                            val d = c.now - c.before
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(
                                    if (c.before == 0L) "new" else (if (d >= 0) "+" else "-") + Money.format(kotlin.math.abs(d)),
                                    style = MaterialTheme.typography.labelLarge.merge(AmountStyle),
                                    color = if (d <= 0) money.credit else money.budgetOver,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(colour: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 4.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.merge(AmountStyle), maxLines = 1)
    }
}
