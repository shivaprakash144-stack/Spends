package app.spendtracker.domain.parser

/**
 * Indian commercial SMS come from DLT headers like "VM-HDFCBK", "AD-ICICIB-S" or "JX-SBIUPI-T".
 * Personal numbers (+91..., 10 digits) are never treated as bank senders.
 */
class SenderFilter(private val banks: List<BankConfig>) {

    /** "VM-HDFCBK-S" -> "HDFCBK". Returns null when the sender is a phone number. */
    fun normalize(sender: String?): String? {
        if (sender.isNullOrBlank()) return null
        var s = sender.trim().uppercase()
        if (PHONE.matches(s)) return null
        s = s.replace(PREFIX, "")
        s = s.replace(SUFFIX, "")
        if (s.isEmpty() || s.all { it.isDigit() }) return null
        return s
    }

    fun isBankStyle(sender: String?): Boolean {
        val n = normalize(sender) ?: return false
        return BANK_STYLE.matches(n)
    }

    fun findBank(sender: String?): BankConfig? {
        val n = normalize(sender) ?: return null
        banks.firstOrNull { b -> b.senders.any { it == n } }?.let { return it }
        // Tolerate small header variations (e.g. "HDFCBANK" vs "HDFCBK").
        return banks.firstOrNull { b -> b.senders.any { it.length >= 5 && (n.startsWith(it) || it.startsWith(n) && n.length >= 5) } }
    }

    private companion object {
        val PHONE = Regex("""^\+?\d[\d\s-]{6,}$""")
        val PREFIX = Regex("""^[A-Z]{2}-""")
        val SUFFIX = Regex("""-[A-Z]$""")
        val BANK_STYLE = Regex("""^[A-Z0-9]{3,11}$""")
    }
}
