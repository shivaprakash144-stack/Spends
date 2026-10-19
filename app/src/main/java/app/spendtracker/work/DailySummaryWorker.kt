package app.spendtracker.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.spendtracker.MainActivity
import app.spendtracker.R
import app.spendtracker.data.db.AppDatabase
import app.spendtracker.data.repository.SettingsRepository
import app.spendtracker.domain.model.CategoryType
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.summary.DailySummary
import app.spendtracker.domain.summary.SummaryBill
import app.spendtracker.domain.summary.SummaryTxn
import app.spendtracker.domain.util.Period
import app.spendtracker.domain.util.PeriodKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** End-of-day notification: today's spend, top category, budget left and bills due soon. */
@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        if (!s.dailySummary) return Result.success()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val (from, to) = Period(PeriodKind.TODAY).millis(today, zone)
        val rows = db.transactions().between(from, to)
        val txns = rows.map {
            SummaryTxn(
                amountPaise = it.t.amountPaise,
                isDebit = it.t.type == TxnType.DEBIT,
                counts = !it.t.excluded && it.categoryType != CategoryType.TRANSFER,
                category = it.categoryName,
                merchant = it.t.merchantName,
            )
        }
        val bills = db.bills().dueBetween(today.toEpochDay(), today.plusDays(3).toEpochDay())
            .map { SummaryBill(it.biller, it.amountPaise, LocalDate.ofEpochDay(it.dueDate)) }
        val monthSpent = if (s.monthlyBudgetPaise > 0) {
            val (mf, mt) = Period.THIS_MONTH.millis(today, zone)
            db.transactions().between(mf, mt)
                .filter { !it.t.excluded && it.categoryType != CategoryType.TRANSFER && it.t.type == TxnType.DEBIT }
                .sumOf { it.t.amountPaise }
        } else null
        val text = DailySummary.build(today, txns, bills, monthSpent, s.monthlyBudgetPaise.takeIf { it > 0 })
        notify(applicationContext, text.title, text.body)
        return Result.success()
    }

    companion object {
        private const val NAME = "daily_summary"
        private const val CHANNEL = "daily_summary"
        private const val NOTIFICATION_ID = 2101

        /** Runs every 24h, first at the next [hour]:00. Call again whenever the hour changes. */
        fun schedule(context: Context, hour: Int, replace: Boolean = false) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(hour, 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)

        /** One-off run (Settings > "Send today's summary now"). */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DailySummaryWorker>().build())
        }

        fun notify(context: Context, title: String, body: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
