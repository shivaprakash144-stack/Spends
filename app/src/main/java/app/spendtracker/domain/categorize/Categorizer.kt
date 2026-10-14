package app.spendtracker.domain.categorize

import app.spendtracker.domain.model.CategoryType
import app.spendtracker.domain.model.RuleSource
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.parser.MerchantCleaner

/** A merchant rule as the categoriser sees it (from the merchant_rules table). */
data class Rule(
    val id: Long,
    val pattern: String,
    val categoryId: Long,
    val categoryType: CategoryType,
    val source: RuleSource,
    val priority: Int,
    /** Brand display name ("Swiggy") when the rule is a brand; null for generic keywords. */
    val displayName: String? = null,
)

data class BodyHint(val categoryId: Long, val categoryType: CategoryType, val keywords: List<String>)

data class SpecialCategories(val cashId: Long?, val incomeId: Long?, val transferId: Long?)

enum class MatchedBy { USER_RULE, DEFAULT_RULE, KEYWORD_HINT, MODE_HINT, NONE }

data class Categorization(val categoryId: Long?, val displayName: String?, val matchedBy: MatchedBy)

/**
 * Order: the user's own rule, then default merchant rules, then keyword hints in the SMS body,
 * then mode hints (ATM -> cash, NEFT/IMPS salary-style credit -> income), otherwise uncategorised.
 */
class Categorizer(
    rules: List<Rule>,
    private val bodyHints: List<BodyHint>,
    private val incomeHints: List<String>,
    private val special: SpecialCategories,
) {
    private val userRules = rules.filter { it.source == RuleSource.USER }
        .sortedWith(compareByDescending<Rule> { it.priority }.thenByDescending { it.id })
    private val defaultRules = rules.filter { it.source == RuleSource.DEFAULT }
        .sortedWith(compareByDescending<Rule> { it.priority }.thenByDescending { it.pattern.length })

    fun categorize(
        merchantName: String?,
        merchantRaw: String?,
        body: String?,
        type: TxnType,
        mode: TxnMode,
    ): Categorization {
        val keys = listOfNotNull(merchantName, merchantRaw).map { MerchantCleaner.key(it) }.filter { it.isNotBlank() }
        val bodyKey = MerchantCleaner.key(body ?: "")

        // 1. User rules always win.
        userRules.firstOrNull { r -> keys.any { matches(r.pattern, it) } }?.let {
            return Categorization(it.categoryId, null, MatchedBy.USER_RULE)
        }

        if (type == TxnType.CREDIT) {
            // Money coming in: refunds, salary, cashback -> Income; transfer hints -> Transfers.
            bodyHints.firstOrNull { h -> h.categoryType == CategoryType.TRANSFER && h.keywords.any { matches(it, bodyKey) } }
                ?.let { return Categorization(it.categoryId, null, MatchedBy.KEYWORD_HINT) }
            if (special.incomeId != null && incomeHints.any { matches(it, bodyKey) }) {
                return Categorization(special.incomeId, brandName(keys), MatchedBy.KEYWORD_HINT)
            }
            defaultRules.firstOrNull { r -> r.categoryType != CategoryType.EXPENSE && keys.any { matches(r.pattern, it) } }
                ?.let { return Categorization(it.categoryId, it.displayName, MatchedBy.DEFAULT_RULE) }
            if (special.incomeId != null && (mode == TxnMode.NEFT || mode == TxnMode.IMPS)) {
                return Categorization(special.incomeId, null, MatchedBy.MODE_HINT)
            }
            return Categorization(null, brandName(keys), MatchedBy.NONE)
        }

        // 2. Default merchant rules.
        defaultRules.firstOrNull { r -> keys.any { matches(r.pattern, it) } }?.let {
            return Categorization(it.categoryId, it.displayName, MatchedBy.DEFAULT_RULE)
        }
        // 3. Keyword hints in the body.
        bodyHints.firstOrNull { h -> h.keywords.any { matches(it, bodyKey) } }?.let {
            return Categorization(it.categoryId, null, MatchedBy.KEYWORD_HINT)
        }
        // 4. Mode hints.
        if (mode == TxnMode.ATM && special.cashId != null) {
            return Categorization(special.cashId, "ATM withdrawal", MatchedBy.MODE_HINT)
        }
        return Categorization(null, null, MatchedBy.NONE)
    }

    private fun brandName(keys: List<String>): String? =
        defaultRules.firstOrNull { r -> r.displayName != null && keys.any { matches(r.pattern, it) } }?.displayName

    companion object {
        /**
         * Token-aware match so "ola" does not hit "coca cola" but "jiomart" still beats "jio".
         * A pattern starting with "re:" is a regex over the lower-case key.
         */
        fun matches(pattern: String, key: String): Boolean {
            if (key.isBlank()) return false
            if (pattern.startsWith("re:")) {
                return runCatching { Regex(pattern.removePrefix("re:"), RegexOption.IGNORE_CASE).containsMatchIn(key) }.getOrDefault(false)
            }
            val p = MerchantCleaner.key(pattern)
            if (p.isEmpty()) return false
            val tokens = key.split(' ')
            val pTokens = p.split(' ')
            if (containsSequence(tokens, pTokens)) return true
            val joined = p.replace(" ", "")
            if (joined.length >= 5) {
                if (tokens.any { it.startsWith(joined) }) return true
                if (key.replace(" ", "").startsWith(joined)) return true
            }
            return false
        }

        private fun containsSequence(tokens: List<String>, seq: List<String>): Boolean {
            if (seq.size > tokens.size) return false
            for (i in 0..tokens.size - seq.size) {
                var ok = true
                for (j in seq.indices) if (tokens[i + j] != seq[j]) { ok = false; break }
                if (ok) return true
            }
            return false
        }
    }
}
