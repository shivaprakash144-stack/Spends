package app.spendtracker.ui.addedit

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.db.AccountEntity
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.repository.AccountRepository
import app.spendtracker.data.repository.CategoryRepository
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.components.CategoryBadge
import app.spendtracker.ui.components.CategoryPickerSheet
import app.spendtracker.ui.components.toColor
import app.spendtracker.ui.theme.AmountStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class EditForm(
    val amount: String = "",
    val type: TxnType = TxnType.DEBIT,
    val merchant: String = "",
    val categoryId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now(),
    val accountId: Long? = null,
    val note: String = "",
    val isEdit: Boolean = false,
)

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
) : ViewModel() {
    private val id: Long? = savedState.get<Long>("id")?.takeIf { it > 0 }

    private val _form = MutableStateFlow(EditForm())
    val form: StateFlow<EditForm> = _form.asStateFlow()
    val categories: StateFlow<List<CategoryEntity>> = categories.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts: StateFlow<List<AccountEntity>> = accounts.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (id != null) viewModelScope.launch {
            val t = repo.get(id) ?: return@launch
            val dt = Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault())
            _form.value = EditForm(
                amount = Money.format(t.amountPaise, symbol = false).replace(",", ""),
                type = t.type, merchant = t.merchantName, categoryId = t.categoryId,
                date = dt.toLocalDate(), time = dt.toLocalTime(), accountId = t.accountId, note = t.note ?: "", isEdit = true,
            )
        }
    }

    fun update(f: (EditForm) -> EditForm) = _form.update(f)

    fun save(done: () -> Unit) {
        val f = _form.value
        val paise = Money.parseToPaise(f.amount) ?: return
        viewModelScope.launch {
            val ts = f.date.atTime(f.time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repo.saveManual(id, paise, f.type, f.categoryId, ts, f.accountId, f.merchant.trim(), f.note)
            done()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(onDone: () -> Unit, vm: AddEditViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    val valid = Money.parseToPaise(f.amount) != null
    val category = cats.firstOrNull { it.id == f.categoryId }
    val account = accounts.firstOrNull { it.id == f.accountId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (f.isEdit) "Edit entry" else "Add cash entry") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TxnType.DEBIT to "Spent", TxnType.CREDIT to "Received").forEachIndexed { i, (t, label) ->
                    SegmentedButton(
                        selected = f.type == t,
                        onClick = { vm.update { it.copy(type = t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            OutlinedTextField(
                value = f.amount,
                onValueChange = { v -> vm.update { it.copy(amount = v.filter { c -> c.isDigit() || c == '.' }) } },
                prefix = { Text("₹", style = MaterialTheme.typography.displaySmall) },
                placeholder = { Text("0", style = MaterialTheme.typography.displaySmall) },
                textStyle = MaterialTheme.typography.displaySmall.merge(AmountStyle).copy(textAlign = TextAlign.Start),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(20.dp),
            )
            OutlinedTextField(
                value = f.merchant,
                onValueChange = { v -> vm.update { it.copy(merchant = v) } },
                label = { Text(if (f.type == TxnType.DEBIT) "Paid to" else "Received from") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            )
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
                if (category != null) {
                    CategoryBadge(category.icon, category.colour.toColor(), 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(category.name, modifier = Modifier.weight(1f))
                } else {
                    Text("Category (auto if empty)", modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d -> vm.update { it.copy(date = LocalDate.of(y, m + 1, d)) } }, f.date.year, f.date.monthValue - 1, f.date.dayOfMonth).show()
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) { Text(f.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))) }
                Column(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) {
                        Text(account?.displayName ?: "Cash", maxLines = 1)
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        accounts.forEach { a ->
                            DropdownMenuItem(text = { Text(a.displayName) }, onClick = { accountMenu = false; vm.update { it.copy(accountId = a.id) } })
                        }
                    }
                }
            }
            OutlinedTextField(
                value = f.note,
                onValueChange = { v -> vm.update { it.copy(note = v) } },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.save(onDone) }, enabled = valid, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (f.isEdit) "Save changes" else "Add entry")
            }
        }
    }

    if (picking) {
        CategoryPickerSheet(cats, f.categoryId, onDismiss = { picking = false }, onPick = { c -> picking = false; vm.update { it.copy(categoryId = c.id) } })
    }
}
