package app.spendtracker.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.AccountEntity
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.TxnRow
import app.spendtracker.data.repository.AccountRepository
import app.spendtracker.data.repository.CategoryRepository
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.data.repository.startMillis
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.model.label
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.components.CategoryPickerSheet
import app.spendtracker.ui.components.EmptyState
import app.spendtracker.ui.components.TransactionItem
import app.spendtracker.ui.components.counts
import app.spendtracker.ui.theme.AmountStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

enum class Period(val label: String) { THIS_MONTH("This month"), LAST_MONTH("Last month"), THREE_MONTHS("3 months"), ALL("All time") }

data class TxnFilters(
    val query: String = "",
    val period: Period = Period.THIS_MONTH,
    val type: TxnType? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val mode: TxnMode? = null,
)

data class DayGroup(val date: LocalDate, val label: String, val total: Long, val rows: List<TxnRow>)

data class TxnListState(
    val filters: TxnFilters = TxnFilters(),
    val groups: List<DayGroup> = emptyList(),
    val spent: Long = 0,
    val count: Int = 0,
    val categories: List<CategoryEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repo: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(TxnFilters())

    val state: StateFlow<TxnListState> = combine(
        repo.observeAll(), filters, categories.observe(), accounts.observe(),
    ) { all, f, cats, accs ->
        val zone = ZoneId.systemDefault()
        val now = YearMonth.now()
        val (from, to) = when (f.period) {
            Period.THIS_MONTH -> now.startMillis() to Long.MAX_VALUE
            Period.LAST_MONTH -> now.minusMonths(1).startMillis() to now.startMillis()
            Period.THREE_MONTHS -> now.minusMonths(2).startMillis() to Long.MAX_VALUE
            Period.ALL -> 0L to Long.MAX_VALUE
        }
        val q = f.query.trim().lowercase()
        val rows = all.filter { r ->
            r.t.timestamp in from until to &&
                (f.type == null || r.t.type == f.type) &&
                (f.categoryId == null || r.t.categoryId == f.categoryId) &&
                (f.accountId == null || r.t.accountId == f.accountId) &&
                (f.mode == null || r.t.mode == f.mode) &&
                (q.isEmpty() || r.t.merchantName.lowercase().contains(q) || (r.t.note?.lowercase()?.contains(q) == true) ||
                    (r.categoryName?.lowercase()?.contains(q) == true) || Money.format(r.t.amountPaise).contains(q))
        }
        val today = LocalDate.now()
        val fmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
        val groups = rows.groupBy { Instant.ofEpochMilli(it.t.timestamp).atZone(zone).toLocalDate() }
            .map { (date, list) ->
                val label = when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> date.format(fmt)
                }
                val net = list.filter { it.counts() }.sumOf { if (it.t.type == TxnType.DEBIT) it.t.amountPaise else 0L }
                DayGroup(date, label, net, list)
            }
        TxnListState(
            filters = f,
            groups = groups,
            spent = rows.filter { it.counts() && it.t.type == TxnType.DEBIT }.sumOf { it.t.amountPaise },
            count = rows.size,
            categories = cats,
            accounts = accs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnListState())

    fun setFilters(transform: (TxnFilters) -> TxnFilters) = filters.update(transform)

    fun toggleExcluded(row: TxnRow) = viewModelScope.launch { repo.setExcluded(row.t.id, !row.t.excluded) }

    fun setCategory(id: Long, categoryId: Long, all: Boolean) = viewModelScope.launch { repo.setCategory(id, categoryId, all) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit, vm: TransactionsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<TxnRow?>(null) }
    var confirm by remember { mutableStateOf<Pair<TxnRow, CategoryEntity>?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Rounded.Add, "Add cash entry") }
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text("Transactions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp))
                Text(
                    "${s.count} entries · ${Money.format(s.spent)} spent",
                    style = MaterialTheme.typography.bodyMedium.merge(AmountStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                OutlinedTextField(
                    value = s.filters.query,
                    onValueChange = { q -> vm.setFilters { it.copy(query = q) } },
                    placeholder = { Text("Search merchant, note, amount") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (s.filters.query.isNotEmpty()) IconButton(onClick = { vm.setFilters { it.copy(query = "") } }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
                FilterRow(s, vm)
            }
            if (s.groups.isEmpty()) {
                item { EmptyState("Nothing here", "Try another period or clear the filters.") }
            }
            s.groups.forEach { g ->
                item(key = "h-${g.date}") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)) {
                        Text(g.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (g.total > 0) {
                            Text("-${Money.format(g.total)}", style = MaterialTheme.typography.labelLarge.merge(AmountStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(g.rows, key = { it.t.id }) { row ->
                    SwipeRow(
                        row = row,
                        onOpen = { onOpen(row.t.id) },
                        onExclude = { vm.toggleExcluded(row) },
                        onCategory = { pickFor = row },
                    )
                }
            }
        }
    }

    pickFor?.let { row ->
        CategoryPickerSheet(
            categories = s.categories,
            selectedId = row.t.categoryId,
            onDismiss = { pickFor = null },
            onPick = { c -> pickFor = null; confirm = row to c },
        )
    }
    confirm?.let { (row, c) ->
        ApplyToMerchantDialog(
            merchant = row.t.merchantName,
            category = c.name,
            onOne = { vm.setCategory(row.t.id, c.id, false); confirm = null },
            onAll = { vm.setCategory(row.t.id, c.id, true); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
fun ApplyToMerchantDialog(merchant: String, category: String, onOne: () -> Unit, onAll: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply to all from $merchant?") },
        text = { Text("Future and past transactions from $merchant will go to $category. You can undo this from Categories.") },
        confirmButton = { TextButton(onClick = onAll) { Text("All from merchant") } },
        dismissButton = { TextButton(onClick = onOne) { Text("Just this one") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(row: TxnRow, onOpen: () -> Unit, onExclude: () -> Unit, onCategory: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> onExclude()
                SwipeToDismissBoxValue.EndToStart -> onCategory()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the action already happened
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(
                    if (toEnd) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                ).padding(horizontal = 24.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (toEnd) Icons.Rounded.VisibilityOff else Icons.Rounded.Category, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (toEnd) (if (row.t.excluded) "Include" else "Exclude") else "Category", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            TransactionItem(row, onClick = onOpen)
        }
    }
}

@Composable
private fun FilterRow(s: TxnListState, vm: TransactionsViewModel) {
    val f = s.filters
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuChip(f.period.label, true, Period.entries.map { it.label to { vm.setFilters { x -> x.copy(period = it) } } })
        MenuChip(
            when (f.type) { TxnType.DEBIT -> "Spent"; TxnType.CREDIT -> "Received"; null -> "Type" },
            f.type != null,
            listOf(
                "Any type" to { vm.setFilters { it.copy(type = null) } },
                "Spent" to { vm.setFilters { it.copy(type = TxnType.DEBIT) } },
                "Received" to { vm.setFilters { it.copy(type = TxnType.CREDIT) } },
            ),
        )
        MenuChip(
            s.categories.firstOrNull { it.id == f.categoryId }?.name ?: "Category",
            f.categoryId != null,
            listOf("Any category" to { vm.setFilters { it.copy(categoryId = null) } }) +
                s.categories.map { c -> c.name to { vm.setFilters { it.copy(categoryId = c.id) } } },
        )
        MenuChip(
            s.accounts.firstOrNull { it.id == f.accountId }?.displayName ?: "Account",
            f.accountId != null,
            listOf("Any account" to { vm.setFilters { it.copy(accountId = null) } }) +
                s.accounts.map { a -> a.displayName to { vm.setFilters { it.copy(accountId = a.id) } } },
        )
        MenuChip(
            f.mode?.label() ?: "Mode",
            f.mode != null,
            listOf("Any mode" to { vm.setFilters { it.copy(mode = null) } }) +
                TxnMode.entries.map { m -> m.label() to { vm.setFilters { it.copy(mode = m) } } },
        )
    }
}

@Composable
private fun MenuChip(label: String, selected: Boolean, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() })
            }
        }
    }
}
