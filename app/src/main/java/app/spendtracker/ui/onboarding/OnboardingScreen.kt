package app.spendtracker.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.work.InboxScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanProgress(val running: Boolean, val finished: Boolean, val done: Int, val total: Int, val found: Int, val failed: Boolean)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    val progress: StateFlow<ScanProgress?> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(InboxScanWorker.NAME)
        .map { infos -> infos.lastOrNull()?.toProgress() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startScan(months: Int) {
        viewModelScope.launch {
            settings.setScanMonths(months)
            InboxScanWorker.enqueue(context, months)
        }
    }

    fun finish() {
        viewModelScope.launch { settings.setOnboardingDone(true) }
    }
}

fun WorkInfo.toProgress(): ScanProgress {
    val data = if (state.isFinished) outputData else progress
    return ScanProgress(
        running = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED,
        finished = state == WorkInfo.State.SUCCEEDED,
        done = data.getInt(InboxScanWorker.KEY_DONE, 0),
        total = data.getInt(InboxScanWorker.KEY_TOTAL, 0),
        found = data.getInt(InboxScanWorker.KEY_FOUND, 0),
        failed = state == WorkInfo.State.FAILED,
    )
}

private fun hasSmsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var months by rememberSaveable { mutableIntStateOf(6) }
    var denied by remember { mutableStateOf(false) }
    val progress by vm.progress.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_SMS] == true) {
            denied = false
            vm.startScan(months)
            step = 3
        } else {
            denied = true
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
    ) {
        StepDots(current = step.coerceAtMost(3), total = 4)
        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { s ->
            when (s) {
                0 -> InfoCard(
                    Icons.Rounded.AutoGraph,
                    "See where your money goes",
                    "Spend Tracker reads the payment SMS your bank and UPI apps already send you, turns each one into a transaction and sorts it into a category. No typing, no linking accounts.",
                )
                1 -> InfoCard(
                    Icons.Rounded.Shield,
                    "Your SMS never leave this phone",
                    "The app has no internet permission at all, so nothing can be uploaded. Data is stored in an encrypted database, the app locks with your fingerprint or PIN and screenshots are blocked.",
                )
                2 -> PermissionCard(
                    months = months,
                    onMonths = { months = it },
                    denied = denied,
                    context = context,
                )
                else -> ScanCard(progress)
            }
        }
        Spacer(Modifier.height(16.dp))
        when (step) {
            0, 1 -> Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Continue") }
            2 -> {
                Button(
                    onClick = {
                        if (hasSmsPermission(context)) {
                            vm.startScan(months); step = 3
                        } else {
                            launcher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(if (denied) "Try again" else "Allow SMS access") }
                TextButton(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now, I'll add cash manually") }
            }
            else -> Button(
                onClick = { vm.finish() },
                enabled = progress?.running != true,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (progress?.running == true) "Scanning…" else "Start using Spend Tracker") }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.height(6.dp).width(if (i == current) 28.dp else 6.dp).clip(CircleShape)
                    .background(if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionCard(months: Int, onMonths: (Int) -> Unit, denied: Boolean, context: Context) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Sms, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(32.dp))
        Text("Allow access to SMS", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Android will ask for permission to read and receive SMS. Only messages from bank-style senders (like VM-HDFCBK) are processed; OTPs, promotions and personal chats are skipped.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text("How far back should the first scan go?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 6, 12).forEach { m ->
                FilterChip(selected = months == m, onClick = { onMonths(m) }, label = { Text(if (m == 12) "1 year" else "$m mo") })
            }
        }
        if (denied) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Permission was denied. Spend Tracker cannot find transactions without it. You can try again, or open app settings and allow SMS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open app settings") }
        }
    }
}

@Composable
private fun ScanCard(p: ScanProgress?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val done = p?.done ?: 0
        val total = p?.total ?: 0
        Text(
            if (p?.finished == true) "All set" else if (p?.failed == true) "Scan stopped" else "Reading your inbox",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text("${p?.found ?: 0}", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
        Text("transactions found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        if (total > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
            Spacer(Modifier.height(8.dp))
            Text("$done of $total messages checked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (p?.running == true) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
        }
        if (p?.failed == true) {
            Spacer(Modifier.height(16.dp))
            Text("SMS permission is missing. You can re-scan later from Settings.", color = MaterialTheme.colorScheme.error)
        }
    }
}
