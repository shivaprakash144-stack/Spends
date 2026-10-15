package app.spendtracker.domain.statement

import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.parser.MerchantCleaner
import app.spendtracker.domain.parser.ParserEngine
import app.spendtracker.domain.parser.SmsDate
import app.spendtracker.domain.util.Money
import java.time.LocalDate

data class StatementRow(
    val date: LocalDate,
    val narration: String,
    val merchantName: String,
    val amountPaise: Long,
    val type: TxnType,
    val mode: TxnMode,
    val balancePaise: Long?,
    val ref: String?,
)

data class ParsedStatement(
    val bankHint: String?,
    val last4: String?,
    val isCreditCard: Boolean,
    val rows: List<StatementRow>,
)

/**
 * Reads the plain text of a bank / card statement PDF (one transaction per line, narration may wrap).
 *
 * Debit vs credit is decided, in order, by: an explicit Dr/Cr marker on the amount, the running
 * balance going down or up, then keywords in the narration. Works for both oldest-first and
 * newest-first statements.
 */
object StatementParser {

    private const val MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
    private val ROW_START = Regex(
        """^\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{1,2}[ -](?:$MONTHS)[a-z]*[ ,-]*\d{2,4}|\d{4}-\d{2}-\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT = Regex("""(?<![\d.,/-])(\d{1,3}(?:,\d{2,3})+\.\d{2}|\d+\.\d{2})(?:\s?(Cr|Dr|CR|DR|cr|dr)\b)?(?![\d/])""")
    private val DATE_ANY = Regex("""\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b|\b\d{1,2}[ -](?:$MONTHS)[a-z]*[ ,-]*\d{2,4}\b""", RegexOption.IGNORE_CASE)
    private val LONG_NUMBER = Regex("""\b\d{9,}\b""")
    private val REF = Regex("""(?<!\d)(\d{12})(?!\d)""")
    private val OPENING = Regex("""(?i)opening\s+balance[^\d]{0,40}?(\d{1,3}(?:,\d{2,3})+\.\d{2}|\d+\.\d{2})""")
    private val ACCOUNT = Regex("""(?i)(?:a/?c(?:count)?|card)\s*(?:no|number|num)?\.?\s*:?\s*[X*x\d\s-]{0,20}?(\d{4})\b""")
    private val STOP_LINE = Regex(
        """(?i)^\s*(page\s+\d|statement\s+summary|closing\s+balance|opening\s+balance|total|grand\s+total|\*+\s*end|generated\s+on|this\s+is\s+a\s+computer)""",
    )
    private val HEADER_LINE = Regex("""(?i)\b(narration|particulars|description|withdrawal|deposit|chq|value\s*dt|txn\s*date|transaction\s*date)\b.*\b(balance|amount|withdrawal|deposit|credit|debit)\b""")
    private val CREDIT_HINT = Regex("""(?i)\b(salary|sal\b|neft\s*cr|imps\s*cr|upi/cr|/cr/|cr-|by transfer|deposit|refund|reversal|cashback|interest|int\.?pd|payment received|thank you|credit)\b""")
    private val DEBIT_HINT = Regex("""(?i)\b(upi/dr|/dr/|dr-|to transfer|withdrawal|atm|wdl|pos|purchase|debit|ach\s*d|nach|emi|charges?)\b""")

    fun parse(text: String): ParsedStatement {
        val head = text.take(3000)
        val bank = listOf("HDFC", "ICICI", "State Bank of India", "SBI", "Axis", "Kotak", "IDFC", "Yes Bank", "Federal", "IndusInd", "Bank of Baroda", "Canara", "Punjab National", "AU Small")
            .firstOrNull { head.contains(it, ignoreCase = true) }
        val isCard = Regex("""(?i)credit\s*card""").containsMatchIn(head)
        val last4 = ACCOUNT.find(head)?.groupValues?.get(1)
        val opening = OPENING.find(text)?.groupValues?.get(1)?.let { Money.parseToPaise(it) }

        // 1. Group lines into blocks that each start with a date.
        val blocks = mutableListOf<Pair<LocalDate, StringBuilder>>()
        var current: Pair<LocalDate, StringBuilder>? = null
        for (raw in text.lines()) {
            val line = raw.replace(' ', ' ').trim()
            if (line.isEmpty()) continue
            if (HEADER_LINE.containsMatchIn(line)) { current = null; continue }
            val start = ROW_START.find(line)
            val date = start?.let { SmsDate.parseDate(it.groupValues[1].replace(",", " ").replace(Regex("\\s+"), " ")) }
            if (start != null && date != null) {
                current = date to StringBuilder(line.substring(start.range.last + 1))
                blocks += current
            } else if (STOP_LINE.containsMatchIn(line)) {
                current = null
            } else {
                current?.second?.append(' ')?.append(line)
            }
        }

        // 2. Pull amounts out of each block.
        data class Raw(val date: LocalDate, val text: String, val amount: Long, val marker: String?, val balance: Long?)
        val raws = blocks.mapNotNull { (date, sb) ->
            val t = sb.toString()
            val amounts = AMOUNT.findAll(t).toList()
            if (amounts.isEmpty()) return@mapNotNull null
            val withBalance = !isCard && amounts.size >= 2
            val amtMatch = if (withBalance) amounts[amounts.size - 2] else amounts.last()
            val amount = Money.parseToPaise(amtMatch.groupValues[1]) ?: return@mapNotNull null
            val balance = if (withBalance) Money.parseToPaise(amounts.last().groupValues[1]) else null
            Raw(date, t, amount, amtMatch.groupValues[2].ifEmpty { null }?.uppercase(), balance)
        }

        // 3. Order oldest-first so the running balance can tell debit from credit.
        val descending = raws.size > 2 && raws.first().date.isAfter(raws.last().date)
        val ordered = if (descending) raws.reversed() else raws

        var prevBalance: Long? = opening
        val rows = ordered.map { r ->
            val type = when {
                r.marker == "CR" -> TxnType.CREDIT
                r.marker == "DR" -> TxnType.DEBIT
                r.balance != null && prevBalance != null -> if (r.balance < prevBalance!!) TxnType.DEBIT else TxnType.CREDIT
                else -> keywordType(r.text) ?: TxnType.DEBIT
            }
            if (r.balance != null) prevBalance = r.balance
            val narration = cleanNarration(r.text)
            StatementRow(
                date = r.date,
                narration = narration,
                merchantName = NarrationCleaner.merchant(narration),
                amountPaise = r.amount,
                type = type,
                mode = NarrationCleaner.mode(narration),
                balancePaise = r.balance,
                ref = REF.find(r.text)?.groupValues?.get(1),
            )
        }
        return ParsedStatement(bank, last4, isCard, rows)
    }

    private fun keywordType(t: String): TxnType? {
        val c = CREDIT_HINT.find(t)?.range?.first ?: -1
        val d = DEBIT_HINT.find(t)?.range?.first ?: -1
        return when {
            c < 0 && d < 0 -> ParserEngine.typeFromKeywords(t)
            c < 0 -> TxnType.DEBIT
            d < 0 -> TxnType.CREDIT
            d <= c -> TxnType.DEBIT
            else -> TxnType.CREDIT
        }
    }

    private fun cleanNarration(t: String): String = t
        .replace(AMOUNT, " ")
        .replace(DATE_ANY, " ")
        .replace(LONG_NUMBER) { m -> if (m.value.length == 12) m.value else " " }
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '/', '|')
}

/** Pulls a merchant out of bank narrations like "UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-6285...-PAYMENT". */
object NarrationCleaner {
    private val NOISE = setOf(
        "UPI", "DR", "CR", "P2M", "P2A", "NEFT", "IMPS", "RTGS", "PAYMENT", "PAY", "TRANSFER", "TO", "BY", "FROM",
        "MB", "IB", "INB", "ACH", "NACH", "POS", "ECOM", "REF", "NA", "TRF", "TXN", "PAYMENT FROM PHONE", "SENT USING PAYTM UPI",
        "COLLECT", "PAID VIA", "NO REMARKS", "OK", "UPIINTENT", "INTENT", "TO TRANSFER", "BY TRANSFER", "NEFT CR", "NEFT DR",
        "IMPS CR", "IMPS DR", "UPI CR", "UPI DR", "ATM WDL", "WDL", "ATW", "NWD", "EAW",
    )
    private val BANK_CODE = Regex("""^(YESB|ICIC|HDFC|SBIN|UTIB|KKBK|PYTM|AIRP|IDFB|BARB|FDRL|INDB|CNRB|PUNB|IOBA|UBIN|AUBL|IBKL|RATN|BKID|MAHB|CBIN|KARB|JAKA|SIBL|CIUB|DBSS|ESFB|SCBL|CITI|HSBC)[A-Z0-9]*$""", RegexOption.IGNORE_CASE)
    private val IFSC = Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""", RegexOption.IGNORE_CASE)
    private val MOSTLY_DIGITS = Regex("""^[A-Z]{0,2}\d{4,}[A-Z]{0,2}$""", RegexOption.IGNORE_CASE)

    fun merchant(narration: String): String {
        if (Regex("""(?i)\bATM\b|\bATW\b|\bNWD\b|ATM\s*WDL|CASH\s*WDL""").containsMatchIn(narration)) return "ATM withdrawal"
        val parts = narration.split('/', '-', '|', ':').map { it.trim() }.filter { it.isNotEmpty() }
        val candidate = parts.firstOrNull { p ->
            p.uppercase() !in NOISE && !p.contains('@') && !IFSC.matches(p) && !BANK_CODE.matches(p) &&
                !MOSTLY_DIGITS.matches(p) && p.count { it.isLetter() } >= 2
        }
        val vpa = parts.firstOrNull { it.contains('@') }
        return when {
            candidate != null -> MerchantCleaner.clean(candidate, mode(narration))
            vpa != null -> MerchantCleaner.clean(vpa, TxnMode.UPI)
            else -> MerchantCleaner.clean(narration.take(40), mode(narration))
        }
    }

    fun mode(narration: String): TxnMode {
        val n = narration.uppercase()
        return when {
            Regex("""\bATM\b|\bATW\b|\bNWD\b|WDL""").containsMatchIn(n) -> TxnMode.ATM
            n.contains("UPI") -> TxnMode.UPI
            Regex("""\bNEFT\b|\bRTGS\b""").containsMatchIn(n) -> TxnMode.NEFT
            n.contains("IMPS") -> TxnMode.IMPS
            Regex("""\bPOS\b|\bECOM\b|CARD""").containsMatchIn(n) -> TxnMode.CARD
            else -> TxnMode.OTHER
        }
    }
}
