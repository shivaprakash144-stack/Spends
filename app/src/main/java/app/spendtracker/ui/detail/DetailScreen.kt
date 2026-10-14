package app.spendtracker.ui.detail

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.RawSmsEntity
import app.spendtracker.data.db.TxnRow
import app.spendtracker.data.repository.CategoryRepository
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.domain.model.TxnSource
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.model.label
import app.spendtracker.ui.components.AmountText
import app.spendtracker.ui.components.CategoryBadge
import app.spendtracker.ui.components.CategoryPickerSheet
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.formatDay
import app.spendtracker.ui.components.formatTime
import app.spendtracker.ui.components.toColor
import app.spendtracker.ui.transactions.ApplyToMerchantDialog
import app.spendtracker.domain.util.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: TransactionRepository,
    categories: CategoryRepository,
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: -1L

    val row: StateFlow<TxnRow?> = repo.observeRow(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val categories: StateFlow<List<CategoryEntity>> = categories.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sms = MutableStateFlow<RawSmsEntity?>(null)
    val sms: StateFlow<RawSmsEntity?> = _sms.asStateFlow()

    init {
        viewModelScope.launch {
            val t = repo.get(id) ?: return@launch
            val rawId = t.smsHash?.removePrefix("raw:")?.toLongOrNull() ?: return@launch
            _sms.value = repo.rawSms(rawId)
        }
    }

    fun setExcluded(v: Boolean) = viewModelScope.launch { repo.setExcluded(id, v) }
    fun setNote(note: String) = viewModelScope.launch { repo.updateNote(id, note) }
    fun setCategory(categoryId: Long, all: Boolean) = viewModelScope.launch { repo.setCategory(id, categoryId, all) }
    fun delete(done: () -> Unit) = viewModelScope.launch { repo.delete(id); done() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val row by vm.row.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val sms by vm.sms.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CategoryEntity?>(null) }
    var askDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (row?.t?.source == TxnSource.MANUAL) IconButton(onClick = { onEdit(vm.id) }) { Icon(Icons.Rounded.Edit, "Edit") }
                    IconButton(onClick = { askDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        val r = row ?: return@Scaffold
        var note by remember(r.t.id) { mutableStateOf(r.t.note ?: "") }
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CategoryBadge(r.categoryIcon, r.categoryColour.toColor(), 64.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(r.t.merchantName, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    AmountText(r.t.amountPaise, r.t.type, MaterialTheme.typography.displayMedium, excluded = r.t.excluded)
                    Text(
                        "${if (r.t.type == TxnType.DEBIT) "Paid" else "Received"} · ${formatDay(r.t.timestamp)}, ${formatTime(r.t.timestamp)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    Field("Category", r.categoryName ?: "Uncategorised", action = "Change") { picking = true }
                    Divider()
                    Field("Account", r.accountName ?: "—")
                    Divider()
                    Field("Mode", r.t.mode.label())
                    r.t.upiRef?.let { Divider(); Field("Reference", it) }
                    r.t.balancePaise?.let { Divider(); Field("Balance after", Money.format(it)) }
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Exclude from spend", style = MaterialTheme.typography.bodyLarge)
                            Text("For self-transfers, reimbursements and the like", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = r.t.excluded, onCheckedChange = { vm.setExcluded(it) })
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    trailingIcon = {
                        if (note != (r.t.note ?: "")) TextButton(onClick = { vm.setNote(note) }) { Text("Save") }
                    },
                )
            }
            sms?.let { raw ->
                item {
                    SectionCard {
                        Text("ORIGINAL SMS · ${raw.sender}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(raw.body, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(14.dp))
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        CategoryPickerSheet(cats, row?.t?.categoryId, onDismiss = { picking = false }, onPick = { picking = false; confirm = it })
    }
    confirm?.let { c ->
        ApplyToMerchantDialog(
            merchant = row?.t?.merchantName ?: "",
            category = c.name,
            onOne = { vm.setCategory(c.id, false); confirm = null },
            onAll = { vm.setCategory(c.id, true); confirm = null },
            onDismiss = { confirm = null },
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("It will be removed from all totals. The original SMS stays in your inbox.") },
            confirmButton = { TextButton(onClick = { askDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
private fun Field(label: String, value: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.6f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}
