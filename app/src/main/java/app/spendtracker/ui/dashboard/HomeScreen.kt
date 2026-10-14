package app.spendtracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.TxnRow
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.components.BudgetBar
import app.spendtracker.ui.components.CategorySlice
import app.spendtracker.ui.components.DonutChart
import app.spendtracker.ui.components.EmptyState
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.components.TransactionItem
import app.spendtracker.ui.components.byCategory
import app.spendtracker.ui.components.income
import app.spendtracker.ui.components.spend
import app.spendtracker.ui.theme.AmountStyle
import app.spendtracker.ui.theme.LocalMoneyColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class HomeState(
    val monthLabel: String = "",
    val spent: Long = 0,
    val received: Long = 0,
    val budget: Long = 0,
    val slices: List<CategorySlice> = emptyList(),
    val recent: List<TxnRow> = emptyList(),
    val toReview: Int = 0,
    val loaded: Boolean = false,
    val dailyAverage: Long = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(repo: TransactionRepository, settings: SettingsRepository) : ViewModel() {
    private val month = YearMonth.now()

    val state: StateFlow<HomeState> = combine(
        repo.observeMonth(month),
        repo.observeRecent(5),
        repo.observeUncategorised(),
        repo.observeFailedSms(),
        settings.settings,
    ) { rows, recent, uncategorised, failed, s ->
        val spent = rows.spend()
        val day = LocalDate.now().dayOfMonth.coerceAtLeast(1)
        HomeState(
            monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)),
            spent = spent,
            received = rows.income(),
            budget = s.monthlyBudgetPaise,
            slices = rows.byCategory(),
            recent = recent,
            toReview = uncategorised.size + failed.size,
            loaded = true,
            dailyAverage = spent / day,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onReview: () -> Unit,
    onSeeAll: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Cash") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HeroCard(s) }
            if (s.toReview > 0) {
                item { ReviewChip(s.toReview, onReview) }
            }
            if (s.slices.isNotEmpty()) {
                item { BreakdownCard(s) }
            }
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 16.dp)) {
                    SectionTitle("Recent", Modifier.padding(horizontal = 20.dp)) {
                        TextButton(onClick = onSeeAll) { Text("See all") }
                    }
                    if (s.recent.isEmpty() && s.loaded) {
                        EmptyState("No transactions yet", "New bank and UPI SMS will appear here within seconds.")
                    }
                    s.recent.forEach { row -> TransactionItem(row, onClick = { onOpen(row.t.id) }) }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(s: HomeState) {
    val money = LocalMoneyColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E2A22), Color(0xFF14513F), Color(0xFF1E6B54))))
            .padding(24.dp),
    ) {
        Column {
            Text("Spent in ${s.monthLabel}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Text(Money.format(s.spent), style = MaterialTheme.typography.displayMedium.merge(AmountStyle), color = Color.White, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text("${Money.format(s.dailyAverage)} a day on average", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Received", s.received, Icons.Rounded.ArrowDownward, Color(0xFF7BE0B8), Modifier.weight(1f))
                val net = s.received - s.spent
                Stat("Net", net, if (net >= 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, if (net >= 0) Color(0xFF7BE0B8) else Color(0xFFFFB4A6), Modifier.weight(1f))
            }
            if (s.budget > 0) {
                Spacer(Modifier.height(20.dp))
                val pct = (s.spent * 100 / s.budget).toInt()
                Row {
                    Text("Budget", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    Text(
                        if (s.spent <= s.budget) "${Money.format(s.budget - s.spent)} left · $pct%" else "${Money.format(s.spent - s.budget)} over",
                        style = MaterialTheme.typography.labelLarge.merge(AmountStyle),
                        color = if (pct >= 100) money.budgetOver else if (pct >= 80) Color(0xFFF2C66D) else Color.White,
                    )
                }
                Spacer(Modifier.height(8.dp))
                BudgetBar(s.spent, s.budget)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Long, icon: ImageVector, tint: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.08f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                Text(Money.format(value), style = MaterialTheme.typography.titleSmall.merge(AmountStyle), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ReviewChip(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Inbox, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "$count to review",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text("Fix now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun BreakdownCard(s: HomeState) {
    SectionCard {
        SectionTitle("Where it went")
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(s.slices, Modifier.size(140.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.slices.size}", style = MaterialTheme.typography.headlineSmall)
                    Text("categories", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val total = s.slices.sumOf { it.amount }.coerceAtLeast(1)
                s.slices.take(5).forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(slice.colour))
                        Spacer(Modifier.width(8.dp))
                        Text(slice.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${slice.amount * 100 / total}%", style = MaterialTheme.typography.labelLarge.merge(AmountStyle))
                    }
                }
                if (s.slices.size > 5) {
                    Text("+${s.slices.size - 5} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
