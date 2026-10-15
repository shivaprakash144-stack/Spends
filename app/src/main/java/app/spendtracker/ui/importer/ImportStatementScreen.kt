package app.spendtracker.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.spendtracker.data.sms.SmsProcessor
import app.spendtracker.data.statement.StatementReadResult
import app.spendtracker.data.statement.StatementReader
import app.spendtracker.domain.statement.ParsedStatement
import app.spendtracker.ui.components.AmountText
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.theme.LocalMoneyColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class NeedsPassword(val uri: Uri, val wrong: Boolean) : ImportState
    data class Preview(val statement: ParsedStatement, val duplicates: Set<Int>) : ImportState
    data class Done(val added: Int, val skipped: Int) : ImportState
    data class Error(val message: String) : ImportState
}

@HiltViewModel
class ImportStatementViewModel @Inject constructor(
    private val reader: StatementReader,
    private val processor: SmsProcessor,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun open(uri: Uri, password: String? = null) = viewModelScope.launch {
        _state.value = ImportState.Working
        _state.value = when (val r = reader.read(uri, password)) {
            is StatementReadResult.Ok -> ImportState.Preview(r.statement, processor.findStatementDuplicates(r.statement.rows))
            StatementReadResult.NeedsPassword -> ImportState.NeedsPassword(uri, wrong = false)
            StatementReadResult.WrongPassword -> ImportState.NeedsPassword(uri, wrong = true)
            is StatementReadResult.Error -> ImportState.Error(r.message)
        }
    }

    fun commit() = viewModelScope.launch {
        val p = _state.value as? ImportState.Preview ?: return@launch
        _state.value = ImportState.Working
        val added = processor.importStatement(p.statement, p.statement.rows)
        _state.value = ImportState.Done(added, p.statement.rows.size - added)
    }

    fun reset() { _state.value = ImportState.Idle }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportStatementScreen(onBack: () -> Unit, vm: ImportStatementViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.open(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Import statement") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val s = state
            if (s is ImportState.Preview) {
                val newCount = s.statement.rows.size - s.duplicates.size
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Button(
                        onClick = { vm.commit() },
                        enabled = newCount > 0,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                    ) { Text(if (newCount > 0) "Import $newCount new transaction${if (newCount == 1) "" else "s"}" else "Nothing new to import") }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                ImportState.Idle -> Intro { picker.launch(arrayOf("application/pdf")) }
                ImportState.Working -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Reading statement…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ImportState.NeedsPassword -> PasswordForm(s.wrong, onSubmit = { vm.open(s.uri, it) }, onCancel = { vm.reset() })
                is ImportState.Preview -> PreviewList(s)
                is ImportState.Done -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = LocalMoneyColors.current.credit, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("${s.added} transactions imported", style = MaterialTheme.typography.headlineSmall)
                    if (s.skipped > 0) Text("${s.skipped} were already captured and skipped", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onBack) { Text("Done") }
                    OutlinedButton(onClick = { vm.reset() }) { Text("Import another") }
                }
                is ImportState.Error -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text("Couldn't import", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { vm.reset() }) { Text("Try another file") }
                }
            }
        }
    }
}

@Composable
private fun Intro(onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("Fill the gaps from your bank statement", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Download the account or credit-card statement PDF from your bank's app or net banking, then pick it here. " +
                "Transactions already captured from SMS, email or notifications are skipped automatically. " +
                "The file is read on this phone and never uploaded.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Password-protected PDFs are fine. Indian banks often use your customer ID, date of birth (DDMMYYYY) or a PAN/name combination.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Choose statement PDF") }
    }
}

@Composable
private fun PasswordForm(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("This PDF is password protected", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (wrong) "That password didn't work. Try again." else "Enter the statement password. It is used once to open the file and not stored.",
            color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pw, onValueChange = { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(pw) }, enabled = pw.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun PreviewList(s: ImportState.Preview) {
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val st = s.statement
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Text(listOfNotNull(st.bankHint ?: "Statement", st.last4?.let { "••$it" }, if (st.isCreditCard) "Credit card" else null).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val first = st.rows.minOf { it.date }
                val last = st.rows.maxOf { it.date }
                Text("${first.format(fmt)} – ${last.format(fmt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Found", st.rows.size.toString(), Modifier.weight(1f))
                    Stat("New", (st.rows.size - s.duplicates.size).toString(), Modifier.weight(1f))
                    Stat("Already captured", s.duplicates.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Check a few rows: if debits and credits look swapped, tell us the bank so the reader can be tuned.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                st.rows.forEachIndexed { i, r ->
                    val dup = i in s.duplicates
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.merchantName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                r.date.format(fmt) + if (dup) " · already captured" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dup) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        AmountText(r.amountPaise, r.type, MaterialTheme.typography.titleSmall, excluded = dup)
                    }
                    if (i < st.rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
