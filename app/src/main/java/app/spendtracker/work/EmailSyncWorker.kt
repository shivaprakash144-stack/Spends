package app.spendtracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.spendtracker.data.email.GmailClient
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.sms.ProcessOutcome
import app.spendtracker.data.sms.SmsProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Pulls bank alert emails from Gmail and runs them through the same pipeline as SMS. */
@HiltWorker
class EmailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val gmail: GmailClient,
    private val processor: SmsProcessor,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        if (!s.gmailConnected) return Result.success()
        val token = try {
            gmail.tokenOrNull()
        } catch (e: Exception) {
            return if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to "Google sign-in failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(300)))
        } ?: return Result.failure(workDataOf(KEY_ERROR to ERR_CONSENT))

        // First sync goes back as far as the SMS scan; later syncs overlap by two days.
        val since = if (s.lastEmailSyncAt > 0) s.lastEmailSyncAt - 2 * DAY
        else LocalDate.now().minusMonths(s.scanMonths.toLong()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        return try {
            val ids = gmail.search(token, GmailClient.query(since / 1000), MAX_MESSAGES)
            var done = 0
            var found = 0
            setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to ids.size, KEY_FOUND to 0))
            for (id in ids) {
                val msg = gmail.message(token, id)
                if (msg != null && processor.processEmail(msg.from, msg.subject, msg.body, msg.receivedAt) == ProcessOutcome.ADDED) found++
                done++
                if (done % 10 == 0 || done == ids.size) setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to ids.size, KEY_FOUND to found))
            }
            settings.setLastEmailSyncAt(System.currentTimeMillis())
            Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to ids.size, KEY_FOUND to found))
        } catch (e: GmailClient.AuthExpiredException) {
            Result.failure(workDataOf(KEY_ERROR to ERR_CONSENT))
        } catch (e: GmailClient.GmailHttpException) {
            val text = when {
                e.code == 403 && (e.reason == "accessNotConfigured" || e.reason == "SERVICE_DISABLED" || e.message.contains("has not been used", true) || e.message.contains("disabled", true)) ->
                    "Gmail API is not enabled in your Google Cloud project. Open console.cloud.google.com > APIs & Services > Library > Gmail API > Enable (same project as the Android client), wait 2 minutes, then Sync now."
                e.code == 403 -> "Google refused access (403 ${e.reason ?: ""}): ${e.message}"
                e.code == 429 -> "Gmail rate limit reached. It will try again in the next sync."
                else -> "Gmail error ${e.code}: ${e.message}"
            }
            Result.failure(workDataOf(KEY_ERROR to text.take(400)))
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "No internet connection. Will try again later."))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to "Sync crashed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(300)))
        }
    }

    companion object {
        const val NAME = "email_sync"
        const val PERIODIC = "email_sync_periodic"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FOUND = "found"
        const val KEY_ERROR = "error"
        const val ERR_CONSENT = "consent"
        private const val MAX_MESSAGES = 1500
        private const val DAY = 24 * 60 * 60 * 1000L

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun syncNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<EmailSyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }

        /** Every 6 hours while Gmail is connected. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<EmailSyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
