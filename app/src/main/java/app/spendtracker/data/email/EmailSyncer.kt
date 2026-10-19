package app.spendtracker.data.email

import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.sms.ProcessOutcome
import app.spendtracker.data.sms.SmsProcessor
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EmailSyncOutcome {
    data class Ok(val done: Int, val total: Int, val found: Int) : EmailSyncOutcome
    /** [retryable] = network trouble; [consent] = the user must approve Gmail access again (needs the app open). */
    data class Err(val message: String, val consent: Boolean = false, val retryable: Boolean = false) : EmailSyncOutcome
}

/** One Gmail sync pass for the picked account. Used by the background worker and by "Sync now". */
@Singleton
class EmailSyncer @Inject constructor(
    private val gmail: GmailClient,
    private val processor: SmsProcessor,
    private val settings: SettingsRepository,
) {
    suspend fun run(onProgress: suspend (done: Int, total: Int, found: Int) -> Unit): EmailSyncOutcome {
        val s = settings.current()
        val email = s.gmailAccount ?: return EmailSyncOutcome.Err("No Gmail account connected.")

        var token = when (val t = gmail.token(email)) {
            is TokenResult.Ok -> t.token
            is TokenResult.NeedsConsent -> return EmailSyncOutcome.Err("Gmail needs your approval. Open Settings > Gmail and tap Connect.", consent = true)
            is TokenResult.Error -> return EmailSyncOutcome.Err(t.message, retryable = t.retryable)
        }
        var refreshed = false

        suspend fun <T> call(block: suspend (String) -> T): T = try {
            block(token)
        } catch (e: GmailClient.AuthExpiredException) {
            if (refreshed) throw e
            refreshed = true
            gmail.clearToken(token)
            token = (gmail.token(email) as? TokenResult.Ok)?.token ?: throw e
            block(token)
        }

        val since = if (s.lastEmailSyncAt > 0) s.lastEmailSyncAt - 2 * DAY
        else LocalDate.now().minusMonths(s.scanMonths.toLong()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        return try {
            val ids = call { t -> gmail.search(t, GmailClient.query(since / 1000), MAX_MESSAGES) }
            var done = 0
            var found = 0
            onProgress(0, ids.size, 0)
            for (id in ids) {
                val msg = call { t -> gmail.message(t, id) }
                if (msg != null && processor.processEmail(msg.from, msg.subject, msg.body, msg.receivedAt) == ProcessOutcome.ADDED) found++
                done++
                if (done % 10 == 0 || done == ids.size) onProgress(done, ids.size, found)
            }
            settings.setLastEmailSyncAt(System.currentTimeMillis())
            EmailSyncOutcome.Ok(done, ids.size, found)
        } catch (e: GmailClient.AuthExpiredException) {
            EmailSyncOutcome.Err("Gmail rejected the sign-in (401). Tap Connect again.", consent = true)
        } catch (e: GmailClient.GmailHttpException) {
            EmailSyncOutcome.Err(
                when {
                    e.code == 403 && (e.reason == "accessNotConfigured" || e.reason == "SERVICE_DISABLED" ||
                        e.message.contains("has not been used", true) || e.message.contains("disabled", true)) ->
                        "Gmail API is not enabled in your Google Cloud project. Open console.cloud.google.com > APIs & Services > Library > Gmail API > Enable (same project as the Android client), wait 2 minutes, then Sync now."
                    e.code == 403 && e.message.contains("insufficient", true) ->
                        "Gmail permission wasn't granted. Tap Connect and allow \"Read your email\"."
                    e.code == 403 -> "Google refused access (403 ${e.reason ?: ""}): ${e.message}"
                    e.code == 429 -> "Gmail rate limit reached. It will try again in the next sync."
                    else -> "Gmail error ${e.code}: ${e.message}"
                }.take(400),
            )
        } catch (e: IOException) {
            EmailSyncOutcome.Err("Network problem: ${e.message.orEmpty()}".take(200), retryable = true)
        } catch (e: Exception) {
            EmailSyncOutcome.Err("Sync crashed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(300))
        }
    }

    private companion object {
        const val MAX_MESSAGES = 1500
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
