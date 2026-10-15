package app.spendtracker.domain.email

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

data class EmailMessage(
    val id: String,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Long,
)

/** Turns a Gmail API "full" message into plain text. Pure JVM so it can be unit tested. */
object EmailText {

    fun fromGmailJson(msg: JsonObject): EmailMessage? {
        val id = (msg["id"] as? JsonPrimitive)?.content ?: return null
        val internalDate = (msg["internalDate"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val payload = msg["payload"] as? JsonObject ?: return null
        val headers = (payload["headers"] as? JsonArray)?.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val n = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val v = (o["value"] as? JsonPrimitive)?.content ?: ""
            n.lowercase() to v
        }?.toMap().orEmpty()
        val plain = findPart(payload, "text/plain")
        val html = findPart(payload, "text/html")
        val snippet = (msg["snippet"] as? JsonPrimitive)?.content.orEmpty()
        val body = when {
            !html.isNullOrBlank() -> htmlToText(html)
            !plain.isNullOrBlank() -> plain
            else -> snippet
        }
        return EmailMessage(id, headers["from"].orEmpty(), headers["subject"].orEmpty(), body.trim(), internalDate)
    }

    private fun findPart(part: JsonObject, mime: String): String? {
        val type = (part["mimeType"] as? JsonPrimitive)?.content
        if (type == mime) {
            val data = ((part["body"] as? JsonObject)?.get("data") as? JsonPrimitive)?.content
            if (!data.isNullOrBlank()) return decode(data)
        }
        (part["parts"] as? JsonArray)?.forEach { child ->
            findPart(child.jsonObject, mime)?.let { return it }
        }
        return null
    }

    fun decode(data: String): String =
        String(Base64.getUrlDecoder().decode(data.trim().replace("\n", "").replace("\r", "")), Charsets.UTF_8)

    private val ENTITIES = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'",
        "&apos;" to "'", "&rupee;" to "₹", "&#8377;" to "₹", "&#x20b9;" to "₹", "&#X20B9;" to "₹",
    )

    fun htmlToText(html: String): String {
        var s = html
            .replace(Regex("""(?is)<(style|script|head)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</tr>|</td>|</li>"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v, ignoreCase = true) }
        s = s.replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: " " }
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
