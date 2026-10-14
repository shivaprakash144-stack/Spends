package app.spendtracker

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.ui.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsRepository

    private var locked by mutableStateOf(false)
    private var backgroundedAt: Long = -1L
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Balances never appear in screenshots or the recents screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            AppRoot(locked = locked, onUnlockRequest = { authenticate() })
        }
    }

    override fun onStart() {
        super.onStart()
        val away = if (backgroundedAt < 0) Long.MAX_VALUE else SystemClock.elapsedRealtime() - backgroundedAt
        if (away >= LOCK_AFTER_MS) {
            lifecycleScope.launch {
                val s = settings.current()
                if (s.appLock && s.onboardingDone && canAuthenticate()) {
                    locked = true
                    authenticate()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    private fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!locked || promptShowing) return
        if (!canAuthenticate()) {
            locked = false
            return
        }
        promptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Spend Tracker")
                .setSubtitle("Your money data stays on this phone")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    private companion object {
        const val LOCK_AFTER_MS = 60_000L
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
