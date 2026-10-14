package app.spendtracker.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Live listener: a new bank SMS shows up as a transaction within seconds. */
class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun processor(): SmsProcessor
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        // Long SMS arrive in several parts; stitch them per sender.
        val messages = parts.filterNotNull()
            .groupBy { it.originatingAddress ?: "" }
            .map { (sender, ps) -> Triple(sender, ps.joinToString("") { it.messageBody ?: "" }, System.currentTimeMillis()) }

        val processor = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java).processor()
        val pending = goAsync()
        scope.launch {
            try {
                messages.forEach { (sender, body, at) ->
                    if (sender.isNotBlank() && body.isNotBlank()) processor.process(sender, body, at, null)
                }
            } catch (_: Exception) {
                // Never crash the receiver; the inbox re-scan will pick the SMS up later.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
