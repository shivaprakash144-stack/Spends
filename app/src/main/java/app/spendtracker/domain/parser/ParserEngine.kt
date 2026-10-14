package app.spendtracker.domain.parser

import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.util.Money
import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Pure-Kotlin SMS parser. All bank knowledge lives in bank_patterns.json:
 * sender filter -> skip list -> bank patterns -> generic fallback -> normalise.
 */
class ParserEngine(
    val config: PatternConfig,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val senderFilter = SenderFilter(config.banks)

    fun parse(sender: String?, body: String?, receivedAt: Long): ParseResult {
        if (body.isNullOrBlank()) return ParseResult.Ignored("empty")
        if (!senderFilter.isBankStyle(sender)) return ParseResult.Ignored("not a bank sender")
        val text = body.replace(WS, " ").trim()

        config.skipPatterns.firstOrNull { it.matcher(text).find() }?.let {
            return ParseResult.Ignored("skip: ${it.pattern()}")
        }
        config.promoPatterns.firstOrNull { it.matcher(text).find() }?.let {
            return ParseResult.Ignored("promo")
        }

        val bank = senderFilter.findBank(sender)
        if (bank != null) {
            for (p in bank.patterns) {
                val m = p.regex.matcher(text)
                if (m.find()) {
                    build(bank, p, m, text, receivedAt)?.let { return ParseResult.Parsed(it) }
                }
            }
        }
        return generic(bank, sender, text, receivedAt)
    }

    private fun build(bank: BankConfig, p: BankPattern, m: Matcher, text: String, receivedAt: Long): ParsedSms? {
        val amount = Money.parseToPaise(m.groupOrNull("amount")) ?: return null
        val type = p.type ?: typeFromKeywords(m.groupOrNull("type") ?: text) ?: return null
        val mode = p.mode ?: inferMode(text)
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

    /** Step 4 of the pipeline: amount + debit/credit keywords, used when no bank regex matched. */
    private fun generic(bank: BankConfig?, sender: String?, text: String, receivedAt: Long): ParseResult {
        val amountMatch = GENERIC_AMOUNT.matcher(text)
        val amount = if (amountMatch.find()) Money.parseToPaise(amountMatch.group(1)) else null
        val type = typeFromKeywords(text)
        val hasAccountRef = ACCOUNT_REF.matcher(text).find()

        if (amount == null) return ParseResult.Ignored("no amount")
        if (type == null) {
            return if (bank != null && hasAccountRef) ParseResult.Failed("amount without debit/credit keyword")
            else ParseResult.Ignored("not a transaction")
        }
        // Unknown senders (shops, apps) must reference an account or card, otherwise order
        // confirmations like "Rs 499 paid for your order" would double count spends.
        if (bank == null && !hasAccountRef) return ParseResult.Ignored("unknown sender without account")
        if (bank != null && !hasAccountRef && !UPI_WORD.matcher(text).find()) {
            return ParseResult.Failed("no account reference")
        }

        val mode = inferMode(text)
        val last4 = ACCOUNT_REF.matcher(text).let { if (it.find()) it.group(1) else null }
        val merchantRaw = GENERIC_MERCHANT.matcher(text).let { mm -> if (mm.find()) mm.group(1) else null }
        val dateText = GENERIC_DATE.matcher(text).let { dm -> if (dm.find()) dm.group(1) else null }
        val accountType = when {
            CREDIT_CARD.matcher(text).find() -> AccountType.CREDIT_CARD
            WALLET.matcher(text).find() -> AccountType.WALLET
            else -> bank?.defaultAccountType ?: AccountType.SAVINGS
        }
        val normalizedSender = senderFilter.normalize(sender) ?: "BANK"
        return ParseResult.Parsed(
            ParsedSms(
                bankId = bank?.id ?: "generic-${normalizedSender.lowercase()}",
                bankName = bank?.name ?: normalizedSender,
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

        val GENERIC_AMOUNT: Pattern = Pattern.compile("""(?:Rs\.?|INR|₹)\s?([\d,]+(?:\.\d{1,2})?)""", FLAGS)
        private val DEBIT_WORDS: Pattern = Pattern.compile("""\b(debited|debit|spent|paid|sent|withdrawn|withdrawal|purchase|deducted|txn of)\b""", FLAGS)
        private val CREDIT_WORDS: Pattern = Pattern.compile("""\b(credited|credit(?!\s*card)|received|deposited|refund(?:ed)?|reversal)\b""", FLAGS)
        private val ACCOUNT_REF: Pattern = Pattern.compile("""(?:a/c|ac|acct|account|card)(?:\s*(?:no\.?|number|ending(?:\s*with)?|xx))?[\s:.-]*[xX*]+\s?(\d{3,4})\b""", FLAGS)
        private val UPI_WORD: Pattern = Pattern.compile("""\bUPI\b""", FLAGS)
        private val REF: Pattern = Pattern.compile("""(?:UPI\s*Ref(?:erence)?\.?\s*(?:No\.?)?|Ref(?:erence)?\s*No\.?|Ref\.?|Refno|RRN|UTR(?:\s*No\.?)?|UPI)[\s:#-]*(\d{9,})""", FLAGS)
        private val BALANCE: Pattern = Pattern.compile("""(?:Avl\.?\s*Bal(?:ance)?|Available\s+Bal(?:ance)?|Bal(?:ance)?)\s*(?:is|:)?\s*(?:Rs\.?|INR|₹)\s?([\d,]+(?:\.\d{1,2})?)""", FLAGS)
        private val GENERIC_MERCHANT: Pattern = Pattern.compile("""\b(?:to|at|towards|from|by|for)\s+(?:VPA\s+)?([A-Za-z0-9@._&'*/ -]{2,40}?)(?=\s+(?:on|ref|via|using|upi|avl|bal|not you|if not)\b|[.,;(]|$)""", FLAGS)
        private val GENERIC_DATE: Pattern = Pattern.compile("""\bon\s+(?:date\s+)?(\d{1,2}[-/ ]?(?:\d{1,2}|[A-Za-z]{3})[-/ ]?\d{2,4}|\d{4}-\d{2}-\d{2})""", FLAGS)
        private val CREDIT_CARD: Pattern = Pattern.compile("""credit\s*card""", FLAGS)
        private val WALLET: Pattern = Pattern.compile("""\bwallet\b""", FLAGS)
        private val ATM: Pattern = Pattern.compile("""\bATM\b""", FLAGS)
        private val WITHDRAW: Pattern = Pattern.compile("""withdr[ae]w""", FLAGS)

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
