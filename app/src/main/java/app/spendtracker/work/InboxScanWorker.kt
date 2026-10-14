package app.spendtracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.data.sms.ProcessOutcome
import app.spendtracker.data.sms.SmsProcessor
import app.spendtracker.data.sms.SmsReader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.ZoneId

/** One-time inbox scan over the last N months, reporting live progress. Safe to re-run: seen SMS are skipped. */
@HiltWorker
class InboxScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reader: SmsReader,
    private val processor: SmsProcessor,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val months = inputData.getInt(KEY_MONTHS, 6)
        val since = LocalDate.now().minusMonths(months.toLong()).withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return try {
            val total = reader.count(since)
            var done = 0
            var found = 0
            setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to total, KEY_FOUND to 0))
            reader.forEach(since) { sms ->
                val outcome = processor.process(sms.sender, sms.body, sms.date, sms.id)
                if (outcome == ProcessOutcome.ADDED) found++
                done++
                if (done % 20 == 0 || done == total) {
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_FOUND to found))
                }
            }
            processor.reparseUnparsed()
            settings.setLastScanAt(System.currentTimeMillis())
            Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_FOUND to found))
        } catch (e: SecurityException) {
            Result.failure(workDataOf(KEY_ERROR to "permission"))
        }
    }

    companion object {
        const val NAME = "inbox_scan"
        const val KEY_MONTHS = "months"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FOUND = "found"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, months: Int) {
            val req = OneTimeWorkRequestBuilder<InboxScanWorker>()
                .setInputData(workDataOf(KEY_MONTHS to months))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
