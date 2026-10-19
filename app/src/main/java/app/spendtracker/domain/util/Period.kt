package app.spendtracker.domain.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodKind(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_3_MONTHS("3 months"),
    ALL("All time"),
    CUSTOM("Custom"),
}

/** A date filter. [customStart]/[customEnd] are inclusive and only used for [PeriodKind.CUSTOM]. */
data class Period(
    val kind: PeriodKind,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
) {
    /** Inclusive first and last day; null means open-ended. */
    fun dates(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (kind) {
        PeriodKind.TODAY -> today to today
        PeriodKind.YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        PeriodKind.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
        PeriodKind.THIS_MONTH -> today.withDayOfMonth(1) to today
        PeriodKind.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        PeriodKind.LAST_3_MONTHS -> today.minusMonths(2).withDayOfMonth(1) to today
        PeriodKind.ALL -> null to null
        PeriodKind.CUSTOM -> {
            val s = customStart ?: today
            val e = customEnd ?: s
            if (e.isBefore(s)) e to s else s to e
        }
    }

    /** [from, toExclusive) in epoch millis. */
    fun millis(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val (s, e) = dates(today)
        val from = s?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
        val to = e?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        return from to to
    }

    fun label(today: LocalDate): String {
        if (kind != PeriodKind.CUSTOM) return kind.label
        val (s, e) = dates(today)
        s ?: return kind.label
        val short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val long = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        return when {
            s == e -> s.format(long)
            s.year == e!!.year -> "${s.format(short)} – ${e.format(long)}"
            else -> "${s.format(long)} – ${e.format(long)}"
        }
    }

    /** Number of days covered (for "per day" averages); null if open-ended. */
    fun dayCount(today: LocalDate): Long? {
        val (s, e) = dates(today)
        if (s == null || e == null) return null
        return e.toEpochDay() - s.toEpochDay() + 1
    }

    /** Compact form for navigation arguments: "TODAY" or "CUSTOM:2026-10-01:2026-10-15". */
    fun encode(): String = if (kind == PeriodKind.CUSTOM) "CUSTOM:${customStart ?: ""}:${customEnd ?: ""}" else kind.name

    companion object {
        val THIS_MONTH = Period(PeriodKind.THIS_MONTH)

        fun decode(s: String?): Period? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(':')
            val kind = PeriodKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            if (kind != PeriodKind.CUSTOM) return Period(kind)
            val start = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val end = parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            return Period(kind, start, end)
        }
    }
}
