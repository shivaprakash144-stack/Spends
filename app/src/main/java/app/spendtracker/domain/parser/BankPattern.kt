package app.spendtracker.domain.parser

import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.regex.Pattern

/**
 * One regex for one SMS shape. Named groups the engine understands:
 * amount, type, last4, merchant, ref, balance, limit, date.
 * [type] / [mode] may be null, in which case they are inferred from the body.
 */
data class BankPattern(
    val id: String,
    val type: TxnType?,
    val mode: TxnMode?,
    val accountType: AccountType?,
    val regex: Pattern,
)

data class BankConfig(
    val id: String,
    val name: String,
    val senders: List<String>,
    val isUpiApp: Boolean,
    val defaultAccountType: AccountType,
    val patterns: List<BankPattern>,
    /** Domains bank alert emails come from, e.g. "hdfcbank.net". */
    val emailDomains: List<String> = emptyList(),
    /** Names as they appear in email sender names / notification titles, e.g. "HDFC Bank". */
    val names: List<String> = emptyList(),
    /** Android package names of the bank / UPI app whose notifications we read. */
    val packages: List<String> = emptyList(),
)

data class PatternConfig(
    val version: Int,
    val banks: List<BankConfig>,
    val skipPatterns: List<Pattern>,
    val promoPatterns: List<Pattern>,
    /** Email apps whose notifications are read (Gmail, Outlook). */
    val emailApps: List<String> = emptyList(),
) {
    companion object {
        /** Shorthand tokens usable inside regex strings in bank_patterns.json. */
        val MACROS: Map<String, String> = mapOf(
            "{AMT}" to """[\d,]+(?:\.\d{1,2})?""",
            "{CUR}" to """(?:Rs\.?|INR|₹)\s?""",
            "{MASK}" to """(?:[xX*]+\s?)?""",
            "{DATE}" to """[0-9A-Za-z/:\-]+""",
        )

        fun expand(regex: String): String {
            var out = regex
            MACROS.forEach { (k, v) -> out = out.replace(k, v) }
            return out
        }

        fun parse(json: String): PatternConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val flags = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            val skip = root.strings("skip").map { Pattern.compile(expand(it), flags) }
            val promo = root.strings("promo").map { Pattern.compile(expand(it), flags) }
            val banks = (root["banks"] as? JsonArray ?: JsonArray(emptyList())).map { el ->
                val b = el.jsonObject
                val id = b.str("id") ?: error("bank without id")
                val patterns = (b["patterns"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapIndexed { i, pel ->
                        val p = pel.jsonObject
                        BankPattern(
                            id = p.str("id") ?: "$id-$i",
                            type = p.str("type")?.let { enumOrNull<TxnType>(it) },
                            mode = p.str("mode")?.let { enumOrNull<TxnMode>(it) },
                            accountType = p.str("accountType")?.let { enumOrNull<AccountType>(it) },
                            regex = Pattern.compile(expand(p.str("regex") ?: error("pattern without regex in $id")), flags),
                        )
                    }
                BankConfig(
                    id = id,
                    name = b.str("name") ?: id,
                    senders = b.strings("senders").map { it.uppercase() },
                    isUpiApp = b.str("kind") == "UPI_APP",
                    defaultAccountType = b.str("accountType")?.let { enumOrNull<AccountType>(it) } ?: AccountType.SAVINGS,
                    patterns = patterns,
                    emailDomains = b.strings("emailDomains").map { it.lowercase() },
                    names = b.strings("names"),
                    packages = b.strings("packages"),
                )
            }
            return PatternConfig(
                version = root.str("version")?.toIntOrNull() ?: 1,
                banks = banks,
                skipPatterns = skip,
                promoPatterns = promo,
                emailApps = root.strings("emailApps"),
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.content

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
            enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
