package app.spendtracker.domain.parser

import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.util.Money
import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Pure-Kotlin message parser. All bank knowledge lives in bank_patterns.json.
 * SMS:           sender filter -> skip list -> bank patterns -> generic fallback -> normalise
 * Email:         bank by sender domain/name -> skip on subject+lead -> patterns -> generic
 * Notification:  bank by app package (or by title for Gmail) -> skip -> patterns -> generic
 */
class ParserEngine(
    val config: PatternConfig,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val senderFilter = SenderFilter(config.banks)

    // ---------------- SMS ----------------

    fun parse(sender: String?, body: String?, receivedAt: Long): ParseResult {
        if (body.isNullOrBlank()) return ParseResult.Ignored("empty")
        if (!senderFilter.isBankStyle(sender)) return ParseResult.Ignored("not a bank sender")
        val text = body.replace(WS, " ").trim()
        skipReason(text)?.let { return ParseResult.Ignored(it) }
        val bank = senderFilter.findBank(sender)
        return matchBank(bank, text, receivedAt)
            ?: generic(bank, senderFilter.normalize(sender) ?: "BANK", text, receivedAt, AccountRefRule.SMS)
    }

    // ---------------- Email ----------------

    /**
     * [from] is the raw From header ("HDFC Bank InstaAlerts <alerts@hdfcbank.net>").
     * Bank emails carry long footers ("never share your OTP"), so skip rules only look at the subject
     * and the opening of the body after disclaimers are removed.
     */
    fun parseEmail(from: String?, subject: String?, body: String?, receivedAt: Long): ParseResult {
        val sub = subject?.replace(WS, " ")?.trim().orEmpty()
        val cleanBody = stripDisclaimers(body?.replace(WS, " ")?.trim().orEmpty())
        if (sub.isEmpty() && cleanBody.isEmpty()) return ParseResult.Ignored("empty")
        val bank = findBankForEmail(from, sub)
        val text = (if (sub.isNotEmpty()) "$sub. " else "") + cleanBody.take(MAX_EMAIL_CHARS)
        skipReason(sub + " " + cleanBody.take(400))?.let { return ParseResult.Ignored(it) }
        return matchBank(bank, text, receivedAt)
            ?: generic(bank, bank?.name ?: emailDomain(from) ?: "EMAIL", text, receivedAt, AccountRefRule.STRICT)
    }

    fun findBankForEmail(from: String?, subject: String?): BankConfig? {
        val f = from?.lowercase().orEmpty()
        val domain = emailDomain(from)
        if (domain != null) {
            config.banks.firstOrNull { b -> b.emailDomains.any { domain == it || domain.endsWith(".$it") } }?.let { return it }
        }
        return findBankByName("$f ${subject.orEmpty()}")
    }

    // ---------------- Notification ----------------

    /** [packageName] of the posting app; [title] + [text] as shown in the shade (big text if expanded). */
    fun parseNotification(packageName: String, title: String?, text: String?, postedAt: Long): ParseResult {
        val t = title?.replace(WS, " ")?.trim().orEmpty()
        val body = text?.replace(WS, " ")?.trim().orEmpty()
        if (body.isEmpty()) return ParseResult.Ignored("empty")
        val isEmailApp = packageName in config.emailApps
        val bank = if (isEmailApp) findBankByName("$t $body") else config.banks.firstOrNull { packageName in it.packages }
        if (bank == null) return ParseResult.Ignored("not a bank app")
        val full = if (isEmailApp) "$t. $body" else "$body"
        val cleaned = stripDisclaimers(full)
        skipReason(cleaned.take(400))?.let { return ParseResult.Ignored(it) }
        return matchBank(bank, cleaned, postedAt)
            ?: generic(bank, bank.name, cleaned, postedAt, if (isEmailApp) AccountRefRule.STRICT else AccountRefRule.NONE)
    }

    fun isBankLikeSender(sender: String?): Boolean = senderFilter.isBankStyle(sender)

    fun isWatchedPackage(packageName: String): Boolean =
        packageName in config.emailApps || config.banks.any { packageName in it.packages }

    // ---------------- Shared ----------------

    private fun skipReason(text: String): String? {
        config.skipPatterns.firstOrNull { it.matcher(text).find() }?.let { return "skip: ${it.pattern()}" }
        config.promoPatterns.firstOrNull { it.matcher(text).find() }?.let { return "promo" }
        return null
    }

    private fun matchBank(bank: BankConfig?, text: String, receivedAt: Long): ParseResult.Parsed? {
        if (bank == null) return null
        for (p in bank.patterns) {
            val m = p.regex.matcher(text)
            if (m.find()) build(bank, p, m, text, receivedAt)?.let { return ParseResult.Parsed(it) }
        }
        return null
    }

    private fun findBankByName(text: String): BankConfig? {
        val lower = text.lowercase()
        return config.banks
            .flatMap { b -> b.names.map { n -> b to n.lowercase() } }
            .sortedByDescending { it.second.length }
            .firstOrNull { (_, n) -> Regex("""\b${Regex.escape(n)}\b""").containsMatchIn(lower) }
            ?.first
    }

    private fun build(bank: BankConfig, p: BankPattern, m: Matcher, text: String, receivedAt: Long): ParsedSms? {
        val amount = Money.parseToPaise(m.groupOrNull("amount")) ?: return null
        val type = p.type ?: typeFromKeywords(m.groupOrNull("type") ?: text) ?: return null
        val mode = p.mode ?: inferMode(text).let { if (it == TxnMode.OTHER && bank.isUpiApp) TxnMode.UPI else it }
        val accountType = p.accountType ?: if (mode == TxnMode.CARD && CREDIT_CARD.matcher(text).find()) AccountType.CREDIT_CARD else bank.defaultAccountType
        val merchantRaw = m.groupOrNull("merchant")?.trim()
        return ParsedSms(
            bankId = bank.id,
            bankName = bank.name,
            isUpiApp = bank.isUpiApp,
            amountPaise = amount,
            type = type,
            mode = refineMode(mode, merchantRaw, text),
            accountType = accountType,
            last4 = m.groupOrNull("last4")?.takeLast(4),
            merchantRaw = merchantRaw,
            merchantName = MerchantCleaner.clean(merchantRaw, mode),
            upiRef = m.groupOrNull("ref")?.trim()?.takeIf { it.isNotEmpty() } ?: findRef(text),
            balancePaise = Money.parseToPaise(m.groupOrNull("balance")) ?: findBalance(text),
            availableLimitPaise = Money.parseToPaise(m.groupOrNull("limit")),
            timestamp = SmsDate.resolve(m.groupOrNull("date"), receivedAt, zone),
            patternId = p.id,
        )
    }

    private enum class AccountRefRule {
        /** Known bank: account ref or UPI word needed; unknown sender: account ref needed. */
        SMS,
        /** Always need an account / card reference (emails can be order confirmations etc.). */
        STRICT,
        /** Bank / UPI app notifications: the source itself is trusted. */
        NONE,
    }

    /** Amount + debit/credit keywords, used when no bank regex matched. */
    private fun generic(bank: BankConfig?, sourceName: String, text: String, receivedAt: Long, rule: AccountRefRule): ParseResult {
        val amountMatch = GENERIC_AMOUNT.matcher(text)
        val amount = if (amountMatch.find()) Money.parseToPaise(amountMatch.group(1)) else null
        val type = typeFromKeywords(text)
        val hasAccountRef = ACCOUNT_REF.matcher(text).find()

        if (amount == null) return ParseResult.Ignored("no amount")
        if (type == null) {
            return if (bank != null && hasAccountRef) ParseResult.Failed("amount without debit/credit keyword")
            else ParseResult.Ignored("not a transaction")
        }
        when (rule) {
            AccountRefRule.SMS -> {
                // Unknown senders (shops, apps) must reference an account or card, otherwise order
                // confirmations like "Rs 499 paid for your order" would double count spends.
                if (bank == null && !hasAccountRef) return ParseResult.Ignored("unknown sender without account")
                if (bank != null && !hasAccountRef && !UPI_WORD.matcher(text).find()) return ParseResult.Failed("no account reference")
            }
            AccountRefRule.STRICT -> if (!hasAccountRef) {
                return if (bank != null) ParseResult.Failed("no account reference") else ParseResult.Ignored("no account reference")
            }
            AccountRefRule.NONE -> Unit
        }

        val mode = inferMode(text).let { if (it == TxnMode.OTHER && bank?.isUpiApp == true) TxnMode.UPI else it }
        val last4 = ACCOUNT_REF.matcher(text).let { if (it.find()) it.group(1) else null }
        val merchantRaw = GENERIC_MERCHANT.matcher(text).let { mm -> if (mm.find()) mm.group(1) else null }
        val dateText = GENERIC_DATE.matcher(text).let { dm -> if (dm.find()) dm.group(1) else null }
        val accountType = when {
            CREDIT_CARD.matcher(text).find() -> AccountType.CREDIT_CARD
            WALLET.matcher(text).find() -> AccountType.WALLET
            else -> bank?.defaultAccountType ?: AccountType.SAVINGS
        }
        return ParseResult.Parsed(
            ParsedSms(
                bankId = bank?.id ?: "generic-${sourceName.lowercase().replace(Regex("[^a-z0-9]+"), "")}",
                bankName = bank?.name ?: sourceName,
                isUpiApp = bank?.isUpiApp ?: false,
                amountPaise = amount,
                type = type,
                mode = refineMode(mode, merchantRaw, text),
                accountType = accountType,
                last4 = last4?.takeLast(4),
                merchantRaw = merchantRaw,
                merchantName = MerchantCleaner.clean(merchantRaw, mode),
                upiRef = findRef(text),
                balancePaise = findBalance(text),
                availableLimitPaise = null,
                timestamp = SmsDate.resolve(dateText, receivedAt, zone),
                patternId = "generic",
            ),
        )
    }

    private fun refineMode(mode: TxnMode, merchantRaw: String?, text: String): TxnMode =
        if (ATM.matcher(merchantRaw ?: "").find() || (ATM.matcher(text).find() && WITHDRAW.matcher(text).find())) TxnMode.ATM else mode

    companion object {
        private val WS = Regex("""\s+""")
        private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        private const val MAX_EMAIL_CHARS = 1500

        val GENERIC_AMOUNT: Pattern = Pattern.compile("""(?:Rs\.?|INR|₹)\s?([\d,]+(?:\.\d{1,2})?)""", FLAGS)
        private val DEBIT_WORDS: Pattern = Pattern.compile("""\b(debited|debit|spent|paid|sent|withdrawn|withdrawal|purchase|deducted|txn of)\b""", FLAGS)
        private val CREDIT_WORDS: Pattern = Pattern.compile("""\b(credited|credit(?!\s*card)|received|deposited|refund(?:ed)?|reversal)\b""", FLAGS)
        private val ACCOUNT_REF: Pattern = Pattern.compile("""(?:a/c|ac|acct|account|card)(?:\s*(?:no\.?|number|ending(?:\s*with)?|xx))?[\s:.-]*[xX*]+\s?(\d{3,4})\b""", FLAGS)
        private val UPI_WORD: Pattern = Pattern.compile("""\bUPI\b""", FLAGS)
        private val REF: Pattern = Pattern.compile("""(?:UPI\s*Ref(?:erence)?\.?\s*(?:No\.?|number(?: is)?)?|(?:transaction )?reference number(?: is)?|Ref(?:erence)?\s*No\.?|Ref\.?|Refno|RRN|UTR(?:\s*No\.?)?|UPI)[\s:#-]*(\d{9,})""", FLAGS)
        private val BALANCE: Pattern = Pattern.compile("""(?:Avl\.?\s*Bal(?:ance)?|Available\s+Bal(?:ance)?|Bal(?:ance)?)\s*(?:is|:)?\s*(?:Rs\.?|INR|₹)\s?([\d,]+(?:\.\d{1,2})?)""", FLAGS)
        private val GENERIC_MERCHANT: Pattern = Pattern.compile("""\b(?:to|at|towards|from|by|for)\s+(?:VPA\s+)?([A-Za-z0-9@._&'*/ -]{2,40}?)(?=\s+(?:on|ref|via|using|upi|avl|bal|not you|if not)\b|[.,;(]|$)""", FLAGS)
        private val GENERIC_DATE: Pattern = Pattern.compile("""\bon\s+(?:date\s+)?(\d{1,2}[-/ ]?(?:\d{1,2}|[A-Za-z]{3})[-/ ]?\d{2,4}|\d{4}-\d{2}-\d{2})""", FLAGS)
        private val CREDIT_CARD: Pattern = Pattern.compile("""credit\s*card""", FLAGS)
        private val WALLET: Pattern = Pattern.compile("""\bwallet\b""", FLAGS)
        private val ATM: Pattern = Pattern.compile("""\bATM\b""", FLAGS)
        private val WITHDRAW: Pattern = Pattern.compile("""withdr[ae]w""", FLAGS)
        private val DISCLAIMER = Regex("""(?i)[^.]*\b(never|do not|don't|dont)\s+share\b[^.]*\.?""")

        fun stripDisclaimers(text: String): String = text.replace(DISCLAIMER, " ").replace(WS, " ").trim()

        fun emailDomain(from: String?): String? {
            val m = Regex("""[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})""").find(from ?: return null) ?: return null
            return m.groupValues[1].lowercase()
        }

        fun typeFromKeywords(text: String): TxnType? {
            val d = DEBIT_WORDS.matcher(text).let { if (it.find()) it.start() else -1 }
            val c = CREDIT_WORDS.matcher(text).let { if (it.find()) it.start() else -1 }
            return when {
                d < 0 && c < 0 -> null
                d < 0 -> TxnType.CREDIT
                c < 0 -> TxnType.DEBIT
                // "debited from your a/c ... credited to X" -> the first verb describes the user's side.
                d <= c -> TxnType.DEBIT
                else -> TxnType.CREDIT
            }
        }

        fun inferMode(text: String): TxnMode {
            val t = text.uppercase()
            return when {
                Regex("""\bATM\b""").containsMatchIn(t) && Regex("""WITHDR""").containsMatchIn(t) -> TxnMode.ATM
                Regex("""\bUPI\b|VPA|@[A-Z]{2,}""").containsMatchIn(t) -> TxnMode.UPI
                Regex("""\bNEFT\b|\bRTGS\b""").containsMatchIn(t) -> TxnMode.NEFT
                Regex("""\bIMPS\b""").containsMatchIn(t) -> TxnMode.IMPS
                Regex("""\bCARD\b""").containsMatchIn(t) -> TxnMode.CARD
                else -> TxnMode.OTHER
            }
        }

        private fun findRef(text: String): String? = REF.matcher(text).let { if (it.find()) it.group(1) else null }
        private fun findBalance(text: String): Long? = BALANCE.matcher(text).let { if (it.find()) Money.parseToPaise(it.group(1)) else null }

        private fun Matcher.groupOrNull(name: String): String? = try {
            group(name)?.takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
