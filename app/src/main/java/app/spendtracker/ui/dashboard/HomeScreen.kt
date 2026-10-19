package app.spendtracker.ui.dashboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.BillEntity
import app.spendtracker.data.db.TxnRow
import app.spendtracker.data.notify.BankNotificationListener
import app.spendtracker.data.repository.BillRepository
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.util.Money
import app.spendtracker.domain.util.Period
import app.spendtracker.domain.util.PeriodKind
import app.spendtracker.ui.components.BudgetBar
import app.spendtracker.ui.components.CategoryBadge
import app.spendtracker.ui.components.CategorySlice
import app.spendtracker.ui.components.DonutChart
import app.spendtracker.ui.components.EmptyState
import app.spendtracker.ui.components.PeriodChips
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.components.TransactionItem
import app.spendtracker.ui.components.byCategory
import app.spendtracker.ui.components.counts
import app.spendtracker.ui.components.income
import app.spendtracker.ui.components.spend
import app.spendtracker.ui.theme.AmountStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class HomeState(
    val period: Period = Period.THIS_MONTH,
    val periodLabel: String = "This month",
    val spent: Long = 0,
    val received: Long = 0,
    val payments: Int = 0,
    val perDay: Long? = null,
    val budget: Long = 0,
    val slices: List<CategorySlice> = emptyList(),
    val recent: List<TxnRow> = emptyList(),
    val toReview: Int = 0,
    val bills: List<BillEntity> = emptyList(),
    val loaded: Boolean = false,
) {
    val net: Long get() = received - spent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    repo: TransactionRepository,
    settings: SettingsRepository,
    bills: BillRepository,
) : ViewModel() {
    private val period = MutableStateFlow(Period.THIS_MONTH)

    fun setPeriod(p: Period) { period.value = p }

    private val rowsForPeriod = period.flatMapLatest { p ->
        val (from, to) = p.millis(LocalDate.now())
        repo.observeBetween(from, to)
    }

    val state: StateFlow<HomeState> = combine(
        combine(period, rowsForPeriod) { p, rows -> p to rows },
        repo.observeRecent(5),
        combine(repo.observeUncategorised(), repo.observeFailedSms()) { u, f -> u.size + f.size },
        settings.settings,
        bills.observeUpcoming(),
    ) { (p, rows), recent, review, s, upcoming ->
        val today = LocalDate.now()
        val spent = rows.spend()
        val days = p.dayCount(today)
        HomeState(
            period = p,
            periodLabel = p.label(today),
            spent = spent,
            received = rows.income(),
            payments = rows.count { it.counts() && it.t.type == TxnType.DEBIT },
            perDay = if (days != null && days > 1) spent / days else null,
            budget = if (p.kind == PeriodKind.THIS_MONTH) s.monthlyBudgetPaise else 0,
            slices = rows.byCategory(),
            recent = recent,
            toReview = review,
            bills = upcoming,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onReview: () -> Unit,
    onSeeAll: (Period) -> Unit,
    onCategory: (Long?, Period) -> Unit,
    onInsights: () -> Unit,
    onBills: () -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notifAccess by remember { mutableStateOf(BankNotificationListener.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifAccess = BankNotificationListener.isEnabled(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add cash") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Spend Tracker", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PeriodChips(selected = s.period, onSelect = vm::setPeriod)
            }
            item { Box(Modifier.padding(horizontal = 16.dp)) { SummaryCard(s) { onSeeAll(s.period) } } }
            if (!notifAccess) {
                item {
                    Banner(
                        icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) },
                        text = "Turn on notification access so payments from bank and UPI apps are captured even without an SMS.",
                        action = "Turn on",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (s.bills.isNotEmpty()) item { Box(Modifier.padding(horizontal = 16.dp)) { BillsCard(s.bills, onBills) } }
            if (s.toReview > 0) {
                item {
                    Banner(
                        icon = { Icon(Icons.Rounded.Inbox, null, tint = MaterialTheme.colorScheme.onTertiaryContainer) },
                        text = "${s.toReview} item${if (s.toReview == 1) "" else "s"} to review",
                        action = "Fix now",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = onReview,
                    )
                }
            }
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    BreakdownCard(s, onCategory = { id -> onCategory(id, s.period) }, onInsights = onInsights)
                }
            }
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    SectionCard(contentPadding = PaddingValues(vertical = 16.dp)) {
                        SectionTitle("Latest transactions", Modifier.padding(horizontal = 20.dp)) {
                            TextButton(onClick = { onSeeAll(Period(PeriodKind.ALL)) }) { Text("See all") }
                        }
                        if (s.recent.isEmpty() && s.loaded) {
                            EmptyState("No transactions yet", "New bank SMS and app notifications appear here within seconds.")
                        }
                        s.recent.forEach { row -> TransactionItem(row, onClick = { onOpen(row.t.id) }) }
                    }
                }
            }
        }
    }
}

/** Big, unambiguous numbers: Spent, Money in, and Net (= money in − spent) with sign and colour. */
@Composable
private fun SummaryCard(s: HomeState, onClick: () -> Unit) {
    val positive = Color(0xFF7BE0B8)
    val negative = Color(0xFFFFB4A6)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E2A22), Color(0xFF14513F), Color(0xFF1E6B54))))
            .clickable(onClick = onClick)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Spent · ${s.periodLabel}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
            Text("${s.payments} payment${if (s.payments == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            Money.format(s.spent),
            style = MaterialTheme.typography.displayMedium.merge(AmountStyle).let { if (Money.format(s.spent).length > 10) it.copy(fontSize = 40.sp) else it },
            color = Color.White,
            maxLines = 1,
        )
        s.perDay?.let {
            Text("${Money.format(it)} a day on average", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Spacer(Modifier.height(14.dp))
        Row {
            Figure("Money in", "+" + Money.format(s.received), positive, Modifier.weight(1f))
            Figure("Money out", "−" + Money.format(s.spent), Color.White, Modifier.weight(1f))
            val net = s.net
            Figure(
                "Net (in − out)",
                (if (net >= 0) "+" else "−") + Money.format(kotlin.math.abs(net)),
                if (net >= 0) positive else negative,
                Modifier.weight(1f),
            )
        }
        if (s.budget > 0) {
            Spacer(Modifier.height(16.dp))
            val pct = (s.spent * 100 / s.budget).toInt()
            Row {
                Text("Monthly budget ${Money.format(s.budget)}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Text(
                    if (s.spent <= s.budget) "${Money.format(s.budget - s.spent)} left" else "${Money.format(s.spent - s.budget)} over",
                    style = MaterialTheme.typography.labelMedium.merge(AmountStyle),
                    color = if (pct >= 100) negative else if (pct >= 80) Color(0xFFF2C66D) else Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
            BudgetBar(s.spent, s.budget)
        }
    }
}

@Composable
private fun Figure(label: String, value: String, colour: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        // Shrink long amounts instead of cutting them off.
        val style: TextStyle = MaterialTheme.typography.titleMedium.merge(AmountStyle).let {
            when {
                value.length > 13 -> it.copy(fontSize = 12.sp)
                value.length > 10 -> it.copy(fontSize = 14.sp)
                else -> it
            }
        }
        Text(value, style = style, color = colour, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Banner(
    icon: @Composable () -> Unit,
    text: String,
    action: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = container, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(action, style = MaterialTheme.typography.labelLarge, color = content)
            Icon(Icons.Rounded.ChevronRight, null, tint = content)
        }
    }
}

@Composable
private fun BillsCard(bills: List<BillEntity>, onOpen: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    SectionCard(contentPadding = PaddingValues(vertical = 14.dp)) {
        SectionTitle("Upcoming bills", Modifier.padding(horizontal = 20.dp)) {
            TextButton(onClick = onOpen) { Text("All bills") }
        }
        bills.take(3).forEach { b ->
            val days = b.dueDate - today
            val whenText = when {
                days < 0 -> "Overdue by ${-days} day${if (days == -1L) "" else "s"}"
                days == 0L -> "Due today"
                days == 1L -> "Due tomorrow"
                else -> "Due in $days days"
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                        .background((if (days <= 1) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null, tint = if (days <= 1) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.biller, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        whenText + " · " + LocalDate.ofEpochDay(b.dueDate).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (days <= 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Money.format(b.amountPaise), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
            }
        }
    }
}

/** Every row opens that category's transactions for the selected period. */
@Composable
private fun BreakdownCard(s: HomeState, onCategory: (Long?) -> Unit, onInsights: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(vertical = 16.dp)) {
        SectionTitle("Where it went · ${s.periodLabel}", Modifier.padding(horizontal = 20.dp)) {
            TextButton(onClick = onInsights) { Text("Insights") }
        }
        if (s.slices.isEmpty()) {
            Text(
                "No spending in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            DonutChart(s.slices, Modifier.size(150.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Money.compact(s.spent), style = MaterialTheme.typography.titleLarge.merge(AmountStyle))
                    Text("spent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val total = s.slices.sumOf { it.amount }.coerceAtLeast(1)
        s.slices.forEach { slice ->
            Row(
                Modifier.fillMaxWidth().clickable { onCategory(slice.categoryId) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(slice.icon, slice.colour, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(slice.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${slice.amount * 100 / total}% of spend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.format(slice.amount), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Open ${slice.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
