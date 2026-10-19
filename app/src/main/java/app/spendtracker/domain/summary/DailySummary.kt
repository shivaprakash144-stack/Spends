package app.spendtracker.domain.summary

import app.spendtracker.domain.util.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SummaryTxn(val amountPaise: Long, val isDebit: Boolean, val counts: Boolean, val category: String?, val merchant: String)

data class SummaryBill(val biller: String, val amountPaise: Long, val dueDate: LocalDate)

data class SummaryText(val title: String, val body: String)

/** Text for the end-of-day notification. Pure so it can be unit tested. */
object DailySummary {

    fun build(date: LocalDate, txns: List<SummaryTxn>, bills: List<SummaryBill>, monthSpentPaise: Long? = null, monthBudgetPaise: Long? = null): SummaryText {
        val spends = txns.filter { it.counts && it.isDebit }
        val received = txns.filter { it.counts && !it.isDebit }.sumOf { it.amountPaise }
        val spent = spends.sumOf { it.amountPaise }

        val title = if (spends.isEmpty()) "No spending today" else "Today you spent ${Money.format(spent)}"
        val lines = mutableListOf<String>()
        if (spends.isNotEmpty()) {
            val topCat = spends.groupBy { it.category ?: "Uncategorised" }.mapValues { e -> e.value.sumOf { it.amountPaise } }.maxByOrNull { it.value }
            val topMerchant = spends.groupBy { it.merchant }.mapValues { e -> e.value.sumOf { it.amountPaise } }.maxByOrNull { it.value }
            lines += "${spends.size} payment${if (spends.size == 1) "" else "s"}" +
                (topCat?.let { " · most on ${it.key} (${Money.format(it.value)})" } ?: "") +
                (topMerchant?.takeIf { spends.size > 1 }?.let { " · biggest: ${it.key} ${Money.format(it.value)}" } ?: "")
        }
        if (received > 0) lines += "Received ${Money.format(received)}"
        if (monthSpentPaise != null && monthBudgetPaise != null && monthBudgetPaise > 0) {
            val left = monthBudgetPaise - monthSpentPaise
            lines += if (left >= 0) "${Money.format(left)} left in this month's budget" else "${Money.format(-left)} over this month's budget"
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        bills.filter { !it.dueDate.isBefore(date) && !it.dueDate.isAfter(date.plusDays(3)) }
            .sortedBy { it.dueDate }
            .take(3)
            .forEach { b ->
                val whenText = when (b.dueDate) {
                    date -> "today"
                    date.plusDays(1) -> "tomorrow"
                    else -> "on ${b.dueDate.format(fmt)}"
                }
                lines += "Due $whenText: ${b.biller} ${Money.format(b.amountPaise)}"
            }
        return SummaryText(title, lines.joinToString("\n").ifEmpty { "Nothing recorded today." })
    }
}
