package app.spendtracker.domain.parser

import app.spendtracker.domain.model.TxnMode

/** Turns raw payee text ("VPA swiggy@icici", "UPI/P2M/6285.../SWIGGY", "NEFT Cr-HDFC0001-ACME LTD") into a display name. */
object MerchantCleaner {

    private val NOISE_TOKENS = setOf(
        "NEFT", "IMPS", "RTGS", "UPI", "P2M", "P2A", "CR", "DR", "MB", "IB", "INB", "POS", "ECOM",
        "NEFT CR", "NEFT DR", "IMPS CR", "IMPS DR", "TRF", "TRANSFER", "VPA", "INFO", "BIL", "ONL",
    )
    private val IFSC = Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""", RegexOption.IGNORE_CASE)
    private val MOSTLY_DIGITS = Regex("""^[A-Z]?\d{5,}[A-Z]?$""", RegexOption.IGNORE_CASE)
    private val SMALL_WORDS = setOf("and", "of", "the", "for", "to", "in", "on", "at", "by")
    private val KEEP_UPPER = setOf("ATM", "EMI", "SIP", "LIC", "PVR", "OYO", "HPCL", "BPCL", "IOCL", "IRCTC", "BESCOM", "TNEB", "UPI", "NEFT", "IMPS", "ICICI", "HDFC", "SBI", "IDFC", "BSNL", "ACT", "DMART", "KFC", "LLP", "LTD", "PVT")

    fun clean(raw: String?, mode: TxnMode? = null): String {
        if (raw.isNullOrBlank()) return fallback(mode)
        var s = raw.trim()
            .removePrefix("VPA").removePrefix("vpa").trim()
            .trim('.', ',', ';', ':', '-', '(', ')', ' ')

        // VPA: name@bank -> name
        if (s.contains('@') && !s.contains(' ')) {
            val handle = s.substringBefore('@')
            val words = handle.split('.', '-', '_').filter { it.isNotBlank() }
            val alpha = words.firstOrNull { w -> w.any { it.isLetter() } && !MOSTLY_DIGITS.matches(w) }
            return if (alpha == null) {
                // Phone-number VPA: keep it recognisable without the full number.
                val digits = handle.filter { it.isDigit() }
                if (digits.length >= 4) "UPI ••${digits.takeLast(4)}" else s
            } else {
                titleCase(alpha.trimEnd { it.isDigit() }.ifBlank { alpha })
            }
        }

        // Segment strings like "UPI/P2M/6285.../SWIGGY" or "NEFT Cr-HDFC0000001-ACME CORP-N123"
        if (s.contains('/') || s.count { it == '-' } >= 2) {
            val parts = s.split('/', '-').map { it.trim() }.filter { it.isNotEmpty() }
            val meaningful = parts.filter { p ->
                p.uppercase() !in NOISE_TOKENS && !IFSC.matches(p) && !MOSTLY_DIGITS.matches(p) &&
                    p.any { it.isLetter() } && p.length > 1
            }
            if (meaningful.isNotEmpty()) s = meaningful.maxByOrNull { it.count(Char::isLetter) } ?: meaningful.first()
            else return fallback(mode)
        }

        s = s.replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)\b(pvt|private)\.?\s*(ltd|limited)\.?$"""), "")
            .replace(Regex("""(?i)\s+(ltd|limited|llp|inc)\.?$"""), "")
            .replace(Regex("""(?i)\s+(on|ref|via|using)\b.*$"""), "")
            .trim('.', ',', ';', ':', '-', ' ', '*')
        if (s.isEmpty() || s.uppercase() in NOISE_TOKENS) return fallback(mode)
        if (s.length > 40) s = s.take(40).trimEnd()
        return titleCase(s)
    }

    private fun fallback(mode: TxnMode?): String = when (mode) {
        TxnMode.ATM -> "ATM withdrawal"
        TxnMode.NEFT -> "NEFT transfer"
        TxnMode.IMPS -> "IMPS transfer"
        TxnMode.UPI -> "UPI payment"
        TxnMode.CARD -> "Card payment"
        TxnMode.CASH -> "Cash"
        else -> "Unknown"
    }

    fun titleCase(s: String): String = s.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
        val up = w.uppercase()
        when {
            up in KEEP_UPPER -> up
            i > 0 && w.lowercase() in SMALL_WORDS -> w.lowercase()
            w.length <= 2 && w.all { it.isUpperCase() } -> w
            else -> w.lowercase().replaceFirstChar { it.titlecase() }
        }
    }.joinToString(" ")

    /** Lower-case alphanumeric key used for merchant rules and grouping. */
    fun key(name: String): String = name.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
}
