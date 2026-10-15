package app.spendtracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val scanMonths: Int = 6,
    val appLock: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColour: Boolean = false,
    val monthlyBudgetPaise: Long = 0,
    val lastScanAt: Long = 0,
    val gmailConnected: Boolean = false,
    val lastEmailSyncAt: Long = 0,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val scanMonths = intPreferencesKey("scan_months")
        val appLock = booleanPreferencesKey("app_lock")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_colour")
        val budget = longPreferencesKey("monthly_budget_paise")
        val lastScan = longPreferencesKey("last_scan_at")
        val gmail = booleanPreferencesKey("gmail_connected")
        val lastEmail = longPreferencesKey("last_email_sync_at")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[K.onboarding] ?: false,
            scanMonths = p[K.scanMonths] ?: 6,
            appLock = p[K.appLock] ?: true,
            themeMode = p[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
            dynamicColour = p[K.dynamic] ?: false,
            monthlyBudgetPaise = p[K.budget] ?: 0L,
            lastScanAt = p[K.lastScan] ?: 0L,
            gmailConnected = p[K.gmail] ?: false,
            lastEmailSyncAt = p[K.lastEmail] ?: 0L,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[K.onboarding] = done }
    suspend fun setScanMonths(months: Int) = context.dataStore.edit { it[K.scanMonths] = months.coerceIn(1, 24) }
    suspend fun setAppLock(enabled: Boolean) = context.dataStore.edit { it[K.appLock] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }
    suspend fun setDynamicColour(enabled: Boolean) = context.dataStore.edit { it[K.dynamic] = enabled }
    suspend fun setMonthlyBudget(paise: Long) = context.dataStore.edit { it[K.budget] = paise.coerceAtLeast(0) }
    suspend fun setLastScanAt(at: Long) = context.dataStore.edit { it[K.lastScan] = at }
    suspend fun setGmailConnected(v: Boolean) = context.dataStore.edit { it[K.gmail] = v; if (!v) it[K.lastEmail] = 0L }
    suspend fun setLastEmailSyncAt(at: Long) = context.dataStore.edit { it[K.lastEmail] = at }
}
