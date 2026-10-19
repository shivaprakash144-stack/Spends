package app.spendtracker.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.BillEntity
import app.spendtracker.data.repository.BillRepository
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.components.EmptyState
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.theme.AmountStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(private val repo: BillRepository) : ViewModel() {
    val bills: StateFlow<List<BillEntity>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun paid(id: Long) = viewModelScope.launch { repo.markPaid(id) }
    fun dismiss(id: Long) = viewModelScope.launch { repo.dismiss(id) }
    fun reopen(id: Long) = viewModelScope.launch { repo.reopen(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(onBack: () -> Unit, vm: BillsViewModel = hiltViewModel()) {
    val bills by vm.bills.collectAsStateWithLifecycle()
    val today = LocalDate.now().toEpochDay()
    val open = bills.filter { it.status == "DUE" }.sortedBy { it.dueDate }
    val done = bills.filter { it.status != "DUE" }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Bills and due dates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Captured automatically from bank SMS, emails and notifications (credit card dues, EMIs, electricity, mobile, broadband and more). " +
                        "A bill is marked paid when a matching payment comes in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (bills.isEmpty()) item { EmptyState("No bills yet", "Due-date reminders from your bank and billers will show up here.") }
            if (open.isNotEmpty()) item { SectionTitle("To pay · ${open.size}", Modifier.padding(start = 4.dp, top = 4.dp)) }
            items(open, key = { it.id }) { b -> BillCard(b, today, onPaid = { vm.paid(b.id) }, onDismiss = { vm.dismiss(b.id) }, onReopen = null) }
            if (done.isNotEmpty()) item { SectionTitle("Paid or dismissed", Modifier.padding(start = 4.dp, top = 8.dp)) }
            items(done, key = { it.id }) { b -> BillCard(b, today, onPaid = null, onDismiss = null, onReopen = { vm.reopen(b.id) }) }
        }
    }
}

@Composable
private fun BillCard(b: BillEntity, today: Long, onPaid: (() -> Unit)?, onDismiss: (() -> Unit)?, onReopen: (() -> Unit)?) {
    val days = b.dueDate - today
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val urgent = b.status == "DUE" && days <= 1
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.biller, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        b.status == "PAID" -> "Paid"
                        b.status == "DISMISSED" -> "Dismissed"
                        days < 0 -> "Overdue by ${-days} day${if (days == -1L) "" else "s"}"
                        days == 0L -> "Due today"
                        days == 1L -> "Due tomorrow"
                        else -> "Due in $days days"
                    } + " · " + LocalDate.ofEpochDay(b.dueDate).format(fmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(b.amountPaise), style = MaterialTheme.typography.titleMedium.merge(AmountStyle))
                b.minDuePaise?.let {
                    Text("Min ${Money.format(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(b.sourceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
            onPaid?.let { FilledTonalButton(onClick = it) { Text("Mark paid") } }
            onReopen?.let { TextButton(onClick = it) { Text("Mark unpaid") } }
        }
    }
}
