package app.spendtracker.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.spendtracker.data.repository.AppSettings
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.repository.ThemeMode
import app.spendtracker.domain.util.Period
import app.spendtracker.ui.addedit.AddEditScreen
import app.spendtracker.ui.bills.BillsScreen
import app.spendtracker.ui.categories.CategoriesScreen
import app.spendtracker.ui.dashboard.HomeScreen
import app.spendtracker.ui.detail.DetailScreen
import app.spendtracker.ui.importer.ImportStatementScreen
import app.spendtracker.ui.insights.InsightsScreen
import app.spendtracker.ui.onboarding.OnboardingScreen
import app.spendtracker.ui.review.ReviewScreen
import app.spendtracker.ui.settings.AccountsScreen
import app.spendtracker.ui.settings.SettingsScreen
import app.spendtracker.ui.theme.SpendTrackerTheme
import app.spendtracker.ui.transactions.TransactionsScreen
import app.spendtracker.work.DailySummaryWorker
import app.spendtracker.work.InboxScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Background jobs that should always exist once the app is set up. */
    fun ensureBackground() = viewModelScope.launch {
        val s = repo.current()
        if (s.dailySummary) DailySummaryWorker.schedule(context, s.summaryHour)
        if (!s.billsBackfilled) {
            // One-time re-scan so bill reminders already in the inbox are picked up.
            InboxScanWorker.enqueue(context, s.scanMonths.coerceAtMost(3))
            repo.setBillsBackfilled()
        }
    }
}

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val TRANSACTIONS_PATTERN = "transactions?categoryId={categoryId}&period={period}"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{id}"
    const val EDIT = "edit?id={id}"
    const val REVIEW = "review"
    const val CATEGORIES = "categories"
    const val ACCOUNTS = "accounts"
    const val IMPORT = "import"
    const val BILLS = "bills"

    fun detail(id: Long) = "detail/$id"
    fun edit(id: Long? = null) = if (id == null) "edit" else "edit?id=$id"
    fun transactions(categoryId: Long?, period: Period) =
        "transactions?categoryId=${categoryId ?: -1}&period=${Uri.encode(period.encode())}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.TRANSACTIONS, "Transactions", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab(Routes.INSIGHTS, "Insights", Icons.Rounded.Insights),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun AppRoot(locked: Boolean, onUnlockRequest: () -> Unit, vm: RootViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    SpendTrackerTheme(themeMode = s?.themeMode ?: ThemeMode.SYSTEM, dynamicColour = s?.dynamicColour ?: false) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                s == null -> Unit
                !s.onboardingDone -> OnboardingScreen()
                else -> MainScaffold(vm)
            }
            AnimatedVisibility(visible = locked, enter = fadeIn(), exit = fadeOut()) {
                LockScreen(onUnlockRequest)
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Spend Tracker is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}

@Composable
private fun MainScaffold(vm: RootViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route?.substringBefore('?')
    val showBar = tabs.any { it.route == route }
    val context = LocalContext.current

    // Daily summary needs notification permission on Android 13+; ask once.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        vm.ensureBackground()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpen = { nav.navigate(Routes.detail(it)) },
                    onAdd = { nav.navigate(Routes.edit()) },
                    onReview = { nav.navigate(Routes.REVIEW) },
                    onSeeAll = { period -> nav.navigate(Routes.transactions(null, period)) },
                    onCategory = { id, period -> nav.navigate(Routes.transactions(id, period)) },
                    onInsights = { nav.navigateTab(Routes.INSIGHTS) },
                    onBills = { nav.navigate(Routes.BILLS) },
                    onSettings = { nav.navigateTab(Routes.SETTINGS) },
                )
            }
            composable(
                Routes.TRANSACTIONS_PATTERN,
                arguments = listOf(
                    navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("period") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                TransactionsScreen(onOpen = { nav.navigate(Routes.detail(it)) }, onAdd = { nav.navigate(Routes.edit()) })
            }
            composable(Routes.INSIGHTS) { InsightsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                    onAccounts = { nav.navigate(Routes.ACCOUNTS) },
                    onReview = { nav.navigate(Routes.REVIEW) },
                    onImport = { nav.navigate(Routes.IMPORT) },
                    onBills = { nav.navigate(Routes.BILLS) },
                )
            }
            composable(Routes.IMPORT) { ImportStatementScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.BILLS) { BillsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DetailScreen(onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate(Routes.edit(id)) })
            }
            composable(
                Routes.EDIT,
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
            ) { AddEditScreen(onDone = { nav.popBackStack() }) }
            composable(Routes.REVIEW) { ReviewScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.detail(it)) }) }
            composable(Routes.CATEGORIES) { CategoriesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ACCOUNTS) { AccountsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
