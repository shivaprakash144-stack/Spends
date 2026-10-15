package app.spendtracker.data.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.spendtracker.data.repository.RulesSource
import app.spendtracker.data.sms.SmsProcessor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads notifications from Gmail / Outlook and bank / UPI apps, so payments whose SMS never
 * arrived are still captured. Works fully offline. Only packages listed in bank_patterns.json
 * ("emailApps" and each bank's "packages") are looked at; everything else is ignored unread.
 */
class BankNotificationListener : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun processor(): SmsProcessor
        fun rules(): RulesSource
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deps by lazy { EntryPointAccessors.fromApplication(applicationContext, Deps::class.java) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return
        if (pkg == packageName) return
        val rules = deps.rules()
        if (!rules.parser.isWatchedPackage(pkg)) return
        val notification = n.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()

        // Inbox-style summaries (several emails) carry one line per message.
        val bodies = when {
            lines.isNotEmpty() -> lines
            !big.isNullOrBlank() -> listOf(big)
            !text.isNullOrBlank() -> listOf(text)
            else -> emptyList()
        }
        if (bodies.isEmpty()) return
        val postedAt = n.postTime
        scope.launch {
            bodies.forEach { body ->
                runCatching { deps.processor().processNotification(pkg, title, body, postedAt) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun component(context: Context) = ComponentName(context, BankNotificationListener::class.java)
    }
}
