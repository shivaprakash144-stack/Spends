package app.spendtracker.data.sms

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class InboxSms(val id: Long, val sender: String, val body: String, val date: Long)

/** Reads the SMS inbox through the content provider (needs READ_SMS). */
@Singleton
class SmsReader @Inject constructor(@ApplicationContext private val context: Context) {

    fun count(since: Long): Int =
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), null,
        )?.use { it.count } ?: 0

    /** Streams messages oldest first so balances end on the latest value. */
    suspend fun forEach(since: Long, block: suspend (InboxSms) -> Unit) {
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val sender = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: continue
                block(InboxSms(c.getLong(iId), sender, body, c.getLong(iDate)))
            }
        }
    }
}
