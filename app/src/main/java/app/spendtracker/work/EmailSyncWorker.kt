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
import app.spendtracker.data.email.EmailSyncOutcome
import app.spendtracker.data.email.EmailSyncer
import app.spendtracker.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Pulls bank alert emails from Gmail and runs them through the same pipeline as SMS. */
@HiltWorker
class EmailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: EmailSyncer,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (settings.current().gmailAccount == null) return Result.success()
        return when (val r = syncer.run { d, t, f -> setProgress(workDataOf(KEY_DONE to d, KEY_TOTAL to t, KEY_FOUND to f)) }) {
            is EmailSyncOutcome.Ok -> Result.success(workDataOf(KEY_DONE to r.done, KEY_TOTAL to r.total, KEY_FOUND to r.found))
            is EmailSyncOutcome.Err ->
                if (r.retryable && runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to r.message))
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

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun syncNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<EmailSyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }

        /** Every 6 hours while Gmail is connected. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<EmailSyncWorker>(6, TimeUnit.HOURS).setConstraints(online).setInitialDelay(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
