package app.spendtracker.data.email

import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.sms.ProcessOutcome
import app.spendtracker.data.sms.SmsProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EmailSyncOutcome {
    /** [more] = there are older emails still to fetch; another batch is scheduled. */
    data class Ok(val done: Int, val total: Int, val found: Int, val more: Boolean = false) : EmailSyncOutcome
    /** [retryable] = network / quota trouble; [consent] = the user must approve Gmail access again (needs the app open). */
    data class Err(val message: String, val consent: Boolean = false, val retryable: Boolean = false) : EmailSyncOutcome
}

/**
 * One Gmail sync pass for the picked account. Used by the background worker and by "Sync now".
 *
 * Stays inside Gmail's per-user quota: requests are paced, a "rate limit exceeded" reply makes it
 * wait and retry, and big mailboxes are read in batches (oldest first) with a saved checkpoint, so an
 * interrupted sync resumes instead of starting over.
 */
@Singleton
class EmailSyncer @Inject constructor(
    private val gmail: GmailClient,
    private val processor: SmsProcessor,
    private val settings: SettingsRepository,
) {
    private val running = Mutex()

    suspend fun run(onProgress: suspend (done: Int, total: Int, found: Int) -> Unit): EmailSyncOutcome {
        if (!running.tryLock()) return EmailSyncOutcome.Err("A Gmail sync is already running. Please wait for it to finish.", retryable = true)
        try {
            return runLocked(onProgress)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runLocked(onProgress: suspend (Int, Int, Int) -> Unit): EmailSyncOutcome {
        val s = settings.current()
        val email = s.gmailAccount ?: return EmailSyncOutcome.Err("No Gmail account connected.")

        var token = when (val t = gmail.token(email)) {
            is TokenResult.Ok -> t.token
            is TokenResult.NeedsConsent -> return EmailSyncOutcome.Err("Gmail needs your approval. Open Settings > Gmail and tap Approve access.", consent = true)
            is TokenResult.Error -> return EmailSyncOutcome.Err(t.message, retryable = t.retryable)
        }
        var refreshed = false

        /** One Gmail call with token refresh on 401 and patient retries on quota errors. */
        suspend fun <T> call(block: suspend (String) -> T): T {
            var wait = FIRST_WAIT_MS
            var attempt = 0
            while (true) {
                try {
                    return block(token)
                } catch (e: GmailClient.AuthExpiredException) {
                    if (refreshed) throw e
                    refreshed = true
                    gmail.clearToken(token)
                    token = (gmail.token(email) as? TokenResult.Ok)?.token ?: throw e
                } catch (e: GmailClient.RateLimitedException) {
                    if (++attempt > MAX_RATE_RETRIES) throw e
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_WAIT_MS)
                }
            }
        }

        val since = if (s.lastEmailSyncAt > 0) s.lastEmailSyncAt - 2 * DAY
        else LocalDate.now().minusMonths(s.scanMonths.toLong()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        return try {
            // Gmail lists newest first; read oldest first so the checkpoint only moves forward.
            val all = call { t -> gmail.search(t, GmailClient.query(since / 1000), MAX_LISTED) }.reversed()
            val batch = all.take(BATCH)
            val more = all.size > batch.size
            var done = 0
            var found = 0
            var checkpoint = 0L
            onProgress(0, batch.size, 0)
            for (id in batch) {
                val msg = call { t -> gmail.message(t, id) }
                if (msg != null) {
                    if (processor.processEmail(msg.from, msg.subject, msg.body, msg.receivedAt) == ProcessOutcome.ADDED) found++
                    if (msg.receivedAt > checkpoint) checkpoint = msg.receivedAt
                }
                done++
                if (done % 10 == 0 || done == batch.size) {
                    onProgress(done, batch.size, found)
                    if (more && checkpoint > 0) settings.setLastEmailSyncAt(checkpoint)
                }
                delay(PACE_MS)
            }
            settings.setLastEmailSyncAt(if (more && checkpoint > 0) checkpoint else System.currentTimeMillis())
            EmailSyncOutcome.Ok(done, batch.size, found, more)
        } catch (e: GmailClient.AuthExpiredException) {
            EmailSyncOutcome.Err("Gmail rejected the sign-in (401). Tap Approve access.", consent = true)
        } catch (e: GmailClient.RateLimitedException) {
            EmailSyncOutcome.Err("Gmail is limiting how fast emails can be read. Progress is saved; it will continue automatically in a few minutes.", retryable = true)
        } catch (e: GmailClient.GmailHttpException) {
            EmailSyncOutcome.Err(
                when {
                    e.code == 403 && (e.reason == "accessNotConfigured" || e.reason == "SERVICE_DISABLED" ||
                        e.message.contains("has not been used", true) || e.message.contains("disabled", true)) ->
                        "Gmail API is not enabled in your Google Cloud project. Open console.cloud.google.com > APIs & Services > Library > Gmail API > Enable (same project as the Android client), wait 2 minutes, then Sync now."
                    e.code == 403 && e.message.contains("insufficient", true) ->
                        "Gmail permission wasn't granted. Tap Approve access and allow \"Read your email\"."
                    e.code == 403 -> "Google refused access (403 ${e.reason ?: ""}): ${e.message}"
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
        const val MAX_LISTED = 5000
        /** Emails fully read per run; the rest follow in the next scheduled batch. */
        const val BATCH = 300
        /** ~4 reads a second keeps well under Gmail's per-user per-minute quota. */
        const val PACE_MS = 250L
        const val FIRST_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 120_000L
        const val MAX_RATE_RETRIES = 4
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
