package app.spendtracker.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.RawSmsEntity
import app.spendtracker.data.db.TxnRow
import app.spendtracker.data.repository.CategoryRepository
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.data.sms.SmsProcessor
import app.spendtracker.ui.components.CategoryPickerSheet
import app.spendtracker.ui.components.EmptyState
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.components.TransactionItem
import app.spendtracker.ui.components.formatDay
import app.spendtracker.ui.components.formatTime
import app.spendtracker.ui.transactions.ApplyToMerchantDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repo: TransactionRepository,
    categories: CategoryRepository,
    private val processor: SmsProcessor,
) : ViewModel() {
    val uncategorised: StateFlow<List<TxnRow>> = repo.observeUncategorised().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val failed: StateFlow<List<RawSmsEntity>> = repo.observeFailedSms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<CategoryEntity>> = categories.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCategory(id: Long, categoryId: Long, all: Boolean) = viewModelScope.launch { repo.setCategory(id, categoryId, all) }
    fun dismiss(id: Long) = viewModelScope.launch { repo.dismissSms(id) }
    fun retry() = viewModelScope.launch { processor.reparseUnparsed() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, vm: ReviewViewModel = hiltViewModel()) {
    val uncategorised by vm.uncategorised.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<TxnRow?>(null) }
    var confirm by remember { mutableStateOf<Pair<TxnRow, CategoryEntity>?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uncategorised.isEmpty() && failed.isEmpty()) {
                item { EmptyState("All caught up", "Every transaction has a category and every bank SMS was understood.") }
            }
            if (uncategorised.isNotEmpty()) {
                item {
                    SectionCard(contentPadding = PaddingValues(vertical = 16.dp)) {
                        SectionTitle("Needs a category · ${uncategorised.size}", Modifier.padding(horizontal = 20.dp))
                        Text(
                            "Tap Set to pick one. Choose \"All from merchant\" and the app learns it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                        uncategorised.take(50).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                TransactionItem(row, onClick = { onOpen(row.t.id) }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { pickFor = row }, modifier = Modifier.padding(end = 8.dp, top = 8.dp)) { Text("Set") }
                            }
                        }
                    }
                }
            }
            if (failed.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth()) {
                        SectionTitle("Bank SMS not understood · ${failed.size}", Modifier.weight(1f).padding(start = 4.dp, top = 12.dp))
                        TextButton(onClick = { vm.retry() }) { Text("Retry all") }
                    }
                }
                items(failed, key = { it.id }) { raw ->
                    SectionCard {
                        Text("${raw.sender} · ${formatDay(raw.receivedAt)}, ${formatTime(raw.receivedAt)}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(raw.body, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(12.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add a pattern for this shape to assets/bank_patterns.json, or add it as a manual entry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(onClick = { vm.dismiss(raw.id) }) { Text("Not a transaction") }
                        }
                    }
                }
            }
        }
    }

    pickFor?.let { row ->
        CategoryPickerSheet(cats, row.t.categoryId, onDismiss = { pickFor = null }, onPick = { c -> pickFor = null; confirm = row to c })
    }
    confirm?.let { (row, c) ->
        ApplyToMerchantDialog(
            row.t.merchantName, c.name,
            onOne = { vm.setCategory(row.t.id, c.id, false); confirm = null },
            onAll = { vm.setCategory(row.t.id, c.id, true); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}
