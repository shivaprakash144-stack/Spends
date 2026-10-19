package app.spendtracker.data.email

import android.accounts.Account
import android.content.Context
import android.content.Intent
import app.spendtracker.domain.email.EmailMessage
import app.spendtracker.domain.email.EmailText
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.AccountPicker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TokenResult {
    data class Ok(val token: String) : TokenResult
    /** Google wants the user to approve Gmail access: launch [intent] from an Activity. */
    data class NeedsConsent(val intent: Intent) : TokenResult
    data class Error(val message: String, val retryable: Boolean = false) : TokenResult
}

/**
 * Read-only Gmail access for one Google account picked on the phone.
 * Uses the phone's Google account system (no password ever reaches the app) and the Gmail REST API.
 * Mail flows Google -> this phone only.
 */
@Singleton
class GmailClient @Inject constructor(@ApplicationContext private val context: Context) {

    /** System account chooser limited to Google accounts. */
    fun accountPickerIntent(): Intent = AccountPicker.newChooseAccountIntent(
        AccountPicker.AccountChooserOptions.Builder()
            .setAllowableAccountsTypes(listOf(GOOGLE_TYPE))
            .build(),
    )

    suspend fun token(email: String): TokenResult = withContext(Dispatchers.IO) {
        try {
            TokenResult.Ok(GoogleAuthUtil.getToken(context, Account(email, GOOGLE_TYPE), "oauth2:$GMAIL_READONLY"))
        } catch (e: UserRecoverableAuthException) {
            val i = e.intent
            if (i != null) TokenResult.NeedsConsent(i) else TokenResult.Error("Google asked for approval but gave no screen to show. Try Connect again.")
        } catch (e: GoogleAuthException) {
            TokenResult.Error(explain(e.message))
        } catch (e: IOException) {
            TokenResult.Error("No internet connection.", retryable = true)
        } catch (e: Exception) {
            TokenResult.Error("Google sign-in error: ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(300))
        }
    }

    suspend fun clearToken(token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    /** Message ids matching [query], newest first, up to [max]. */
    suspend fun search(token: String, query: String, max: Int): List<String> = withContext(Dispatchers.IO) {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$BASE/messages?maxResults=100&q=").append(URLEncoder.encode(query, "UTF-8"))
                pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val json = get(token, url)
            (json["messages"] as? JsonArray)?.forEach { m ->
                (m.jsonObject["id"] as? JsonPrimitive)?.content?.let { ids += it }
            }
            pageToken = (json["nextPageToken"] as? JsonPrimitive)?.content
        } while (pageToken != null && ids.size < max)
        ids.take(max)
    }

    suspend fun message(token: String, id: String): EmailMessage? = withContext(Dispatchers.IO) {
        EmailText.fromGmailJson(get(token, "$BASE/messages/$id?format=full"))
    }

    private fun get(token: String, url: String): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code == 401) throw AuthExpiredException()
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(err).jsonObject["error"]?.jsonObject }.getOrNull()
                val message = (obj?.get("message") as? JsonPrimitive)?.content ?: err.take(200)
                val reason = runCatching {
                    ((obj?.get("errors") as? JsonArray)?.firstOrNull()?.jsonObject?.get("reason") as? JsonPrimitive)?.content
                }.getOrNull() ?: (obj?.get("status") as? JsonPrimitive)?.content
                throw GmailHttpException(code, reason, message)
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return Json.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    class AuthExpiredException : IOException("Gmail access expired")

    /** A non-network error from Google (API disabled, quota, bad request...). Retrying won't help. */
    class GmailHttpException(val code: Int, val reason: String?, override val message: String) : Exception(message)

    companion object {
        const val GOOGLE_TYPE = "com.google"
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

        /** Turns Google's terse auth error codes into a fix the user can act on. */
        fun explain(code: String?): String = when {
            code == null -> "Google sign-in failed."
            code.contains("UNREGISTERED_ON_API_CONSOLE", true) ->
                "Google Cloud doesn't recognise this app. In console.cloud.google.com > Clients, the Android client must have package name app.spendtracker.debug and SHA-1 7C:04:E2:D1:55:08:EA:9F:D0:9C:66:23:85:87:44:94:B8:6A:02:B4. Fix it, wait 5 minutes and try again."
            code.contains("INVALID_SCOPE", true) -> "Gmail permission isn't allowed for this Google Cloud project. Add the gmail.readonly scope under Data access."
            code.contains("ServiceDisabled", true) -> "Gmail API is disabled in your Google Cloud project. Enable it under APIs & Services > Library."
            code.contains("BadAuthentication", true) || code.contains("NeedPermission", true) -> "Google needs you to sign in to this account again in phone Settings > Accounts."
            code.contains("access_denied", true) || code.contains("AppNotAuthorized", true) ->
                "Google blocked access. Make sure your Gmail is added under Google Auth Platform > Audience > Test users."
            else -> "Google sign-in error: $code"
        }

        /** Gmail search for transaction alerts and bill reminders; promotions and OTP mails are left out. */
        fun query(sinceEpochSeconds: Long): String =
            "after:$sinceEpochSeconds {debited credited spent withdrawn \"transaction alert\" \"txn\" \"has been debited\" \"has been credited\" \"UPI txn\" \"due date\" \"is due\" \"amount due\" \"bill\"} " +
                "-category:promotions -category:social -subject:OTP"
    }
}
