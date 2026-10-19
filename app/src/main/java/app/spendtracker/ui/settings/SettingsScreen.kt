package app.spendtracker.ui.settings

import android.content.Context
import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.pm.PackageManager
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import app.spendtracker.data.email.TokenResult
import app.spendtracker.work.DailySummaryWorker
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.spendtracker.data.email.EmailSyncOutcome
import app.spendtracker.data.email.EmailSyncer
import app.spendtracker.data.email.GmailClient
import app.spendtracker.data.notify.BankNotificationListener
import app.spendtracker.work.EmailSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.spendtracker.BuildConfig
import app.spendtracker.data.db.AccountEntity
import app.spendtracker.data.repository.AccountRepository
import app.spendtracker.data.repository.AppSettings
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.repository.ThemeMode
import app.spendtracker.data.repository.TransactionRepository
import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.label
import app.spendtracker.domain.util.Money
import app.spendtracker.ui.components.SectionCard
import app.spendtracker.ui.components.SectionTitle
import app.spendtracker.ui.components.formatDay
import app.spendtracker.ui.onboarding.ScanProgress
import app.spendtracker.ui.onboarding.toProgress
import app.spendtracker.work.InboxScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val txns: TransactionRepository,
    private val gmail: GmailClient,
    private val syncer: EmailSyncer,
) : ViewModel() {
    val emailSync: StateFlow<ScanProgress?> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(EmailSyncWorker.NAME)
        .map { it.lastOrNull()?.toProgress() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _gmailError = MutableStateFlow<String?>(null)
    val gmailError: StateFlow<String?> = _gmailError.asStateFlow()

    /** Google's approval screen to show; the UI launches it and calls [consentShown]. */
    private val _consentRequest = MutableStateFlow<Intent?>(null)
    val consentRequest: StateFlow<Intent?> = _consentRequest.asStateFlow()
    fun consentShown() { _consentRequest.value = null }

    /** Foreground sync state (right after connecting, and "Sync now"). */
    private val _fgSync = MutableStateFlow<ScanProgress?>(null)
    val fgSync: StateFlow<ScanProgress?> = _fgSync.asStateFlow()

    private var pendingEmail: String? = null
    private var consentAttempts = 0

    fun accountPickerIntent(): Intent = gmail.accountPickerIntent()

    /** Step 1: the user picked a Google account in the system chooser. */
    fun onAccountPicked(email: String?) = viewModelScope.launch {
        if (email.isNullOrBlank()) {
            _gmailError.value = "No account was chosen."
            return@launch
        }
        pendingEmail = email
        consentAttempts = 0
        requestToken(email)
    }

    /** Step 2: back from Google's "allow Spend Tracker to read your email" screen. */
    fun onConsentResult(approved: Boolean) = viewModelScope.launch {
        val email = pendingEmail ?: state.value.gmailAccount ?: return@launch
        if (!approved) {
            _gmailError.value = "Gmail access was not approved, so emails can't be read. Tap Connect Gmail to try again."
            return@launch
        }
        requestToken(email)
    }

    fun approveAgain() = viewModelScope.launch {
        val email = state.value.gmailAccount ?: return@launch
        pendingEmail = email
        consentAttempts = 0
        requestToken(email)
    }

    private suspend fun requestToken(email: String) {
        _gmailError.value = null
        when (val t = gmail.token(email)) {
            is TokenResult.Ok -> {
                settings.setGmailAccount(email)
                EmailSyncWorker.schedule(context)
                runForeground()
            }
            is TokenResult.NeedsConsent -> {
                if (consentAttempts++ < 2) _consentRequest.value = t.intent
                else _gmailError.value = "Google keeps asking for approval. Check that your Gmail is a Test user in Google Cloud (Audience > Test users)."
            }
            is TokenResult.Error -> _gmailError.value = t.message
        }
    }

    private suspend fun runForeground() {
        _fgSync.value = ScanProgress(running = true, finished = false, done = 0, total = 0, found = 0, failed = false)
        val r = syncer.run { d, t, f -> _fgSync.value = ScanProgress(true, false, d, t, f, false) }
        _fgSync.value = when (r) {
            is EmailSyncOutcome.Ok -> ScanProgress(false, true, r.done, r.total, r.found, false)
            is EmailSyncOutcome.Err -> ScanProgress(false, false, 0, 0, 0, true, if (r.consent) "consent:" + r.message else r.message)
        }
    }

    fun syncEmail() = viewModelScope.launch { runForeground() }

    fun disconnectGmail() = viewModelScope.launch {
        EmailSyncWorker.cancel(context)
        settings.setGmailAccount(null)
        pendingEmail = null
        _fgSync.value = null
        _gmailError.value = null
    }

    // ---- Daily summary ----
    fun setDailySummary(on: Boolean) = viewModelScope.launch {
        settings.setDailySummary(on)
        if (on) DailySummaryWorker.schedule(context, state.value.summaryHour, replace = true) else DailySummaryWorker.cancel(context)
    }

    fun setSummaryHour(h: Int) = viewModelScope.launch {
        settings.setSummaryHour(h)
        if (state.value.dailySummary) DailySummaryWorker.schedule(context, h, replace = true)
    }

    fun sendTestSummary() = DailySummaryWorker.runNow(context)

    val state: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings(onboardingDone = true))
    val scan: StateFlow<ScanProgress?> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(InboxScanWorker.NAME)
        .map { it.lastOrNull()?.toProgress() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setLock(v: Boolean) = viewModelScope.launch { settings.setAppLock(v) }
    fun setTheme(m: ThemeMode) = viewModelScope.launch { settings.setThemeMode(m) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { settings.setDynamicColour(v) }
    fun setMonths(m: Int) = viewModelScope.launch { settings.setScanMonths(m) }
    fun setBudget(paise: Long) = viewModelScope.launch { settings.setMonthlyBudget(paise) }
    fun rescan() = InboxScanWorker.enqueue(context, state.value.scanMonths)

    /** Removes SMS-derived data (keeps manual entries, categories and learned rules) and scans again. */
    fun resetAndRescan() = viewModelScope.launch {
        txns.wipeSmsData()
        InboxScanWorker.enqueue(context, state.value.scanMonths)
    }
}

@Composable
fun SettingsScreen(
    onCategories: () -> Unit,
    onAccounts: () -> Unit,
    onReview: () -> Unit,
    onImport: () -> Unit,
    onBills: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val bgSync by vm.emailSync.collectAsStateWithLifecycle()
    val fgSync by vm.fgSync.collectAsStateWithLifecycle()
    // The foreground run (connect / Sync now) wins over the background worker's last state.
    val emailSync = fgSync ?: bgSync
    val gmailError by vm.gmailError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notifAccess by remember { mutableStateOf(BankNotificationListener.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifAccess = BankNotificationListener.isEnabled(context) }
    val consentRequest by vm.consentRequest.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.onConsentResult(res.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consentRequest) {
        consentRequest?.let { consent.launch(it); vm.consentShown() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) vm.onAccountPicked(res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setDailySummary(true)
    }
    var budgetDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.statusBars) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }

            item { SectionTitle("Money", Modifier.padding(start = 4.dp)) }
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    NavRow(Icons.Rounded.AccountBalance, "Accounts", "Banks, cards and wallets found in SMS", onAccounts)
                    Div()
                    NavRow(Icons.Rounded.Category, "Categories", "Add, rename, recolour and merge", onCategories)
                    Div()
                    NavRow(Icons.AutoMirrored.Rounded.ReceiptLong, "Bills and due dates", "Credit card dues, EMIs and utility bills found in messages", onBills)
                    Div()
                    NavRow(Icons.Rounded.Inbox, "Review", "Uncategorised and unread SMS", onReview)
                    Div()
                    NavRow(
                        Icons.Rounded.Savings, "Monthly budget",
                        if (s.monthlyBudgetPaise > 0) Money.format(s.monthlyBudgetPaise) else "Not set",
                    ) { budgetDialog = true }
                }
            }

            item { SectionTitle("SMS scan", Modifier.padding(start = 4.dp, top = 8.dp)) }
            item {
                SectionCard {
                    Text("History to scan", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 6, 12, 24).forEach { m ->
                            FilterChip(selected = s.scanMonths == m, onClick = { vm.setMonths(m) }, label = { Text(if (m >= 12) "${m / 12}y" else "${m}m") })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val running = scan?.running == true
                    if (running) {
                        val p = scan!!
                        LinearProgressIndicator(
                            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${p.done} of ${p.total} checked · ${p.found} new", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            if (s.lastScanAt > 0) "Last scan ${formatDay(s.lastScanAt)}" else "Not scanned yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(onClick = { vm.rescan() }, enabled = !running) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Re-scan inbox")
                        }
                        TextButton(onClick = { resetDialog = true }, enabled = !running) { Text("Reset & rebuild") }
                    }
                }
            }

            item { SectionTitle("Other sources", Modifier.padding(start = 4.dp, top = 8.dp)) }
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    NavRow(Icons.Rounded.Description, "Import bank statement", "PDF from your bank; missing transactions are added", onImport)
                    Div()
                    NavRow(
                        Icons.Rounded.NotificationsActive,
                        "Read notifications",
                        if (notifAccess) "On: Gmail, bank and UPI app alerts are captured" else "Off: tap to allow notification access",
                    ) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Div()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Email, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Gmail", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    when {
                                        s.gmailAccount == null -> "Read past and new bank alert and bill emails (read-only)"
                                        emailSync?.running == true -> "${s.gmailAccount} · syncing ${emailSync?.done ?: 0} of ${emailSync?.total ?: 0}"
                                        emailSync?.finished == true -> "${s.gmailAccount} · synced, ${emailSync?.found ?: 0} new transactions"
                                        s.lastEmailSyncAt > 0 -> "${s.gmailAccount} · last sync ${formatDay(s.lastEmailSyncAt)} · every 6 hours"
                                        else -> "${s.gmailAccount} · connected"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (emailSync?.running == true) {
                            val p = emailSync!!
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
                        }
                        val syncError = emailSync?.error?.takeIf { emailSync?.failed == true && s.gmailAccount != null }
                        val needsApproval = syncError?.startsWith("consent:") == true || syncError == EmailSyncWorker.ERR_CONSENT
                        (gmailError ?: syncError?.removePrefix("consent:")?.let { if (it == EmailSyncWorker.ERR_CONSENT) "Gmail needs your approval again." else it })?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row {
                            if (s.gmailAccount == null) {
                                TextButton(onClick = { picker.launch(vm.accountPickerIntent()) }) { Text("Connect Gmail") }
                            } else {
                                if (needsApproval) TextButton(onClick = { vm.approveAgain() }) { Text("Approve access") }
                                TextButton(onClick = { vm.syncEmail() }, enabled = emailSync?.running != true) { Text("Sync now") }
                                TextButton(onClick = { vm.disconnectGmail() }) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Notifications", Modifier.padding(start = 4.dp, top = 8.dp)) }
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    SwitchRow(
                        Icons.Rounded.NotificationsActive,
                        "Daily summary",
                        "Every evening: today's spend, top category and bills due soon",
                        s.dailySummary,
                    ) { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setDailySummary(on)
                        }
                    }
                    if (s.dailySummary) {
                        Row(Modifier.padding(start = 60.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(19, 20, 21, 22, 23).forEach { h ->
                                FilterChip(selected = s.summaryHour == h, onClick = { vm.setSummaryHour(h) }, label = { Text("${h - 12} PM") })
                            }
                        }
                        TextButton(onClick = { vm.sendTestSummary() }, modifier = Modifier.padding(start = 52.dp)) { Text("Send today's summary now") }
                    }
                }
            }

            item { SectionTitle("Security and look", Modifier.padding(start = 4.dp, top = 8.dp)) }
            item {
                SectionCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    SwitchRow(Icons.Rounded.Fingerprint, "App lock", "Fingerprint or device PIN on open and after 1 minute away", s.appLock) { vm.setLock(it) }
                    Div()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = s.themeMode == m,
                                    onClick = { vm.setTheme(m) },
                                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                ) { Text(m.name.lowercase().replaceFirstChar { it.titlecase() }) }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Div()
                        SwitchRow(Icons.Rounded.Palette, "Wallpaper colours", "Use Material You dynamic colour", s.dynamicColour) { vm.setDynamic(it) }
                    }
                    Div()
                    NavRow(Icons.Rounded.IosShare, "Export", "CSV, Excel and PDF arrive in a later version", null)
                }
            }

            item { SectionTitle("About and privacy", Modifier.padding(start = 4.dp, top = 8.dp)) }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Your data never leaves this phone", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No account and no server of ours. SMS, notifications and statements are read on this phone; Gmail, if you connect it, is read directly from Google to this phone (read-only). Everything is stored in an encrypted database whose key is protected by Android Keystore, excluded from cloud backups, and screenshots are blocked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (budgetDialog) {
        var text by remember { mutableStateOf(if (s.monthlyBudgetPaise > 0) (s.monthlyBudgetPaise / 100).toString() else "") }
        AlertDialog(
            onDismissRequest = { budgetDialog = false },
            title = { Text("Monthly budget") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() } },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    supportingText = { Text("Leave empty to turn off. Alerts at 80% and 100% come in Phase 2.") },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.setBudget((text.toLongOrNull() ?: 0L) * 100); budgetDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { budgetDialog = false }) { Text("Cancel") } },
        )
    }
    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text("Reset and rebuild?") },
            text = { Text("Removes all SMS-based transactions and scans the inbox again with the current rules. Manual entries, categories and your learned rules stay.") },
            confirmButton = { TextButton(onClick = { vm.resetAndRescan(); resetDialog = false }) { Text("Rebuild") } },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Div() = HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------- Accounts ----------------

@HiltViewModel
class AccountsViewModel @Inject constructor(private val repo: AccountRepository) : ViewModel() {
    val accounts: StateFlow<List<AccountEntity>> = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun rename(a: AccountEntity, name: String) = viewModelScope.launch { repo.rename(a, name) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: () -> Unit, vm: AccountsViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<AccountEntity?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Found automatically from your SMS. Tap to rename. Balances shown are the latest an SMS reported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            accounts.groupBy { it.type }.forEach { (type, list) ->
                item { SectionTitle(type.label(), Modifier.padding(start = 4.dp, top = 8.dp)) }
                item {
                    SectionCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                        list.forEach { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable { renaming = a }.padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOfNotNull(a.bankName, a.last4?.let { "••$it" }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val value = if (a.type == AccountType.CREDIT_CARD) a.availableLimitPaise else a.lastBalancePaise
                                if (value != null) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(Money.format(value), style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            (if (a.type == AccountType.CREDIT_CARD) "limit left" else "balance") + (a.lastBalanceAt?.let { " · ${formatDay(it)}" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename account") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(a, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
