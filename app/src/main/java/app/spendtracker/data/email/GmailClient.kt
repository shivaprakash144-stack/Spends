package app.spendtracker.data.email

import android.content.Context
import app.spendtracker.domain.email.EmailMessage
import app.spendtracker.domain.email.EmailText
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
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

/**
 * Read-only Gmail access. Uses Google's on-device authorization (no password is ever seen by the app)
 * and talks to the Gmail REST API directly. Mail flows Google -> this phone only.
 */
@Singleton
class GmailClient @Inject constructor(@ApplicationContext private val context: Context) {

    val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GMAIL_READONLY)))
        .build()

    /** Returns a result that either has an access token or needs the consent screen ([AuthorizationResult.hasResolution]). */
    suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request).await()

    /** Access token if the user already granted access; null if consent is needed again. */
    suspend fun tokenOrNull(): String? {
        val r = authorize()
        return if (r.hasResolution()) null else r.accessToken
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

    suspend fun revoke() {
        // Clearing the local token; the user can fully remove access at myaccount.google.com/permissions.
        runCatching { tokenOrNull()?.let { t -> withContext(Dispatchers.IO) { revokeToken(t) } } }
    }

    private fun revokeToken(token: String) {
        val conn = URL("https://oauth2.googleapis.com/revoke?token=" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    class AuthExpiredException : IOException("Gmail access expired")

    /** A non-network error from Google (API disabled, quota, bad request...). Retrying won't help. */
    class GmailHttpException(val code: Int, val reason: String?, override val message: String) : Exception(message)

    companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

        /** Gmail search for transaction alerts; promotions and OTP mails are left out. */
        fun query(sinceEpochSeconds: Long): String =
            "after:$sinceEpochSeconds {debited credited spent withdrawn \"transaction alert\" \"txn\" \"has been debited\" \"has been credited\" \"UPI txn\"} " +
                "-category:promotions -category:social -subject:OTP"
    }
}
