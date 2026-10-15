package app.spendtracker.domain.parser

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/** Parses the many date spellings banks use and reconciles them with the SMS receive time. */
object SmsDate {

    private fun fmt(p: String): DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(p)
        .toFormatter(Locale.ENGLISH)

    /** Two-digit years resolve to 2000-2099. */
    private fun fmt2y(p: String): DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(p)
        .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
        .toFormatter(Locale.ENGLISH)

    private val formats: List<DateTimeFormatter> = listOf(
        fmt("dd/MM/yyyy"), fmt2y("dd/MM/"),
        fmt("dd-MM-yyyy"), fmt2y("dd-MM-"),
        fmt("dd-MMM-yyyy"), fmt2y("dd-MMM-"),
        fmt("ddMMMyyyy"), fmt2y("ddMMM"),
        fmt("dd MMM yyyy"), fmt2y("dd MMM "),
        fmt("d-MMM-yyyy"), fmt2y("d-MMM-"),
        fmt("yyyy-MM-dd"),
        fmt("dd.MM.yyyy"), fmt2y("dd.MM."),
        fmt("d MMM yyyy"), fmt2y("d MMM "),
        fmt("d/M/yyyy"), fmt2y("d/M/"),
        fmt("d-M-yyyy"), fmt2y("d-M-"),
        fmt("dd MMMM yyyy"), fmt("d MMMM yyyy"),
    )

    fun parseDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        // "2026-10-12:10:11:12" and "01-10-26 10:11:12" -> keep the date part only.
        var t = text.trim().trimEnd('.', ',', ';')
        Regex("""^(\d{4}-\d{2}-\d{2})""").find(t)?.let { t = it.groupValues[1] }
        t = t.substringBefore(' ').let { first ->
            // keep "12 Oct 2026" style intact
            if (Regex("""^\d{1,2}$""").matches(first)) t else first
        }
        for (f in formats) {
            try {
                return LocalDate.parse(t, f)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    /**
     * Use the receive time when the body date is missing or is the same day (it is more precise).
     * When they differ (late delivery, old statements), keep the body's date at the receive time-of-day.
     */
    fun resolve(dateText: String?, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val bodyDate = parseDate(dateText) ?: return receivedAt
        val received = Instant.ofEpochMilli(receivedAt).atZone(zone)
        if (received.toLocalDate() == bodyDate) return receivedAt
        // Guard against misparsed day/month swaps far away from the receive date.
        val daysApart = kotlin.math.abs(bodyDate.toEpochDay() - received.toLocalDate().toEpochDay())
        if (daysApart > 45) return receivedAt
        val time: LocalTime = received.toLocalTime()
        return bodyDate.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}
