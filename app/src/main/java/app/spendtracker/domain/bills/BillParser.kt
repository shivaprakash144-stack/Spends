package app.spendtracker.domain.bills

import app.spendtracker.domain.parser.SmsDate
import app.spendtracker.domain.util.Money
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

enum class BillKind { CREDIT_CARD, LOAN, ELECTRICITY, MOBILE, BROADBAND, GAS, WATER, INSURANCE, RENT, OTHER }

data class BillReminder(
    val biller: String,
    val kind: BillKind,
    val amountPaise: Long,
    val minDuePaise: Long?,
    val dueDate: LocalDate,
    val last4: String?,
)

/**
 * Picks bill / due-date reminders out of SMS, email and notification text, e.g.
 * "Payment of INR 12,450.00 for ICICI Bank Credit Card XX5678 is due on 20-Oct-26" or
 * "Total Amt Due: Rs 5,000; Min Amt Due: Rs 250; Due Date: 20/10/2026".
 * Payment confirmations ("payment received, thank you") are not reminders.
 */
object BillParser {
    private const val AMT = """(?:Rs\.?|INR|₹)\s?([\d,]+(?:\.\d{1,2})?)"""
    private const val DATE = """(\d{1,2}(?:st|nd|rd|th)?[-/ .](?:\d{1,2}|[A-Za-z]{3,9})[-/ .,]*\d{2,4}|\d{4}-\d{2}-\d{2}|\d{1,2}[A-Za-z]{3}\d{2,4})"""
    private val O = setOf(RegexOption.IGNORE_CASE)

    private val TRIGGER = Regex("""\b(due|payable|pay by|last date|overdue|bill (?:of|amount|generated|is)|emi)\b""", O)
    private val PAID = Regex("""payment (?:of .{0,40})?(?:has been |is )?(?:received|successful|credited)|thank you for (?:the |your )?payment|has been paid|paid successfully|we have received""", O)
    private val NOISE = Regex("""\bOTP\b|one[ -]?time pass|pre-?approved|\boffer\b|cashback offer|apply now""", O)

    private val TOTAL = Regex(
        """(?:total\s*(?:amt\.?|amount)?\s*due|amount\s*due|amt\s*due|bill\s*(?:amount|amt)|payment\s*of|bill\s*of|emi\s*(?:of|amount|amt)?|outstanding(?:\s*(?:amount|amt))?|amount\s*of|premium\s*of)\D{0,20}?$AMT""",
        O,
    )
    private val MIN = Regex("""min(?:imum)?\.?\s*(?:amt\.?|amount)?\s*due\D{0,15}?$AMT""", O)
    private val ANY_AMT = Regex(AMT, O)
    private val DUE = Regex(
        """(?:due\s*(?:date|on|by)?|pay(?:able)?\s*(?:by|on|before)|last\s*date(?:\s*(?:for\s*payment|to\s*pay))?|before)\s*(?:is|:|-)?\s*$DATE""",
        O,
    )
    private val CARD = Regex("""Credit\s*Card(?:\s*(?:no\.?|ending(?:\s*with)?)?\s*[xX*]+\s*(\d{4}))?""", O)
    private val LAST4 = Regex("""(?:card|a/c|acct|account|loan)\s*(?:no\.?|number|ending(?:\s*with)?)?[\s:.-]*[xX*]+\s?(\d{3,4})\b""", O)

    private val KINDS: List<Pair<Regex, Pair<BillKind, String>>> = listOf(
        Regex("""credit\s*card""", O) to (BillKind.CREDIT_CARD to "Credit card"),
        Regex("""\bEMI\b|\bloan\b""", O) to (BillKind.LOAN to "Loan EMI"),
        Regex("""electricity|BESCOM|TNEB|TANGEDCO|MSEDCL|BSES|power\s*bill|KSEB|Tata Power|Adani Electricity""", O) to (BillKind.ELECTRICITY to "Electricity bill"),
        Regex("""broadband|fiber|fibre|wi-?fi|ACT Fibernet|Hathway|JioFiber|Airtel Xstream""", O) to (BillKind.BROADBAND to "Broadband bill"),
        Regex("""postpaid|mobile bill|\brecharge\b|prepaid plan|validity""", O) to (BillKind.MOBILE to "Mobile bill"),
        Regex("""\bgas\b|LPG|Indane|HP Gas|Bharat Gas|IGL|Mahanagar Gas""", O) to (BillKind.GAS to "Gas bill"),
        Regex("""\bwater\b|BWSSB""", O) to (BillKind.WATER to "Water bill"),
        Regex("""insurance|premium|LIC\b|policy""", O) to (BillKind.INSURANCE to "Insurance premium"),
        Regex("""\brent\b|maintenance""", O) to (BillKind.RENT to "Rent / maintenance"),
    )

    private val BRAND = Regex("""\b(Airtel|Jio|Vi|Vodafone Idea|BSNL|ACT|Hathway|BESCOM|TNEB|TANGEDCO|MSEDCL|BSES|Tata Power|LIC|HDFC Life|ICICI Prudential|Bajaj Finserv|Bajaj Finance|HDFC|ICICI|SBI|Axis|Kotak|IDFC|AU|IndusInd|Yes Bank|RBL|OneCard|Amex|American Express)\b""", O)

    fun parse(text: String?, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): BillReminder? {
        if (text.isNullOrBlank()) return null
        val t = text.replace(Regex("""\s+"""), " ").trim()
        if (!TRIGGER.containsMatchIn(t) || PAID.containsMatchIn(t) || NOISE.containsMatchIn(t)) return null

        val dueText = DUE.find(t)?.groupValues?.get(1) ?: return null
        val cleanedDate = dueText.replace(Regex("""(?<=\d)(st|nd|rd|th)\b""", O), "").replace(",", " ").replace(".", "-").replace(Regex("\\s+"), " ").trim()
        val dueDate = SmsDate.parseDate(cleanedDate) ?: return null
        // Sanity: reminders talk about the near past or future.
        val received = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        if (dueDate.isBefore(received.minusDays(60)) || dueDate.isAfter(received.plusDays(120))) return null

        val total = TOTAL.find(t)?.groupValues?.get(1)?.let { Money.parseToPaise(it) }
            ?: ANY_AMT.find(t)?.groupValues?.get(1)?.let { Money.parseToPaise(it) }
            ?: return null
        val min = MIN.find(t)?.groupValues?.get(1)?.let { Money.parseToPaise(it) }?.takeIf { it < total }

        val (kind, generic) = KINDS.firstOrNull { it.first.containsMatchIn(t) }?.second ?: (BillKind.OTHER to "Bill")
        val card = if (kind == BillKind.CREDIT_CARD) CARD.find(t) else null
        val last4 = card?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: LAST4.find(t)?.groupValues?.get(1)?.takeLast(4)
        val brand = BRAND.find(t)?.groupValues?.get(1)
        val base = if (brand != null) "${brand.trim()} ${generic.lowercase()}" else generic
        val biller = (if (last4 != null) "$base ••$last4" else base).replaceFirstChar { it.uppercase() }
        return BillReminder(biller, kind, total, min, dueDate, last4)
    }
}
