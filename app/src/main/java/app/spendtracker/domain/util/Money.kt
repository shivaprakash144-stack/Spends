package app.spendtracker.domain.util

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Rupee helpers. All amounts in the app are Long paise. */
object Money {

    /** "1,499.00" -> 149900. Returns null for anything that is not a positive amount. */
    fun parseToPaise(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace(",", "").replace(" ", "").trimEnd('.')
        if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1) return null
        return try {
            val value = BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP)
            val paise = value.movePointRight(2).longValueExact()
            if (paise <= 0) null else paise
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Indian grouping: 12345678 paise -> "₹1,23,456.78"; whole rupees drop the decimals. */
    fun format(paise: Long, symbol: Boolean = true, forceDecimals: Boolean = false): String {
        val negative = paise < 0
        val absPaise = abs(paise)
        val rupees = absPaise / 100
        val fraction = (absPaise % 100).toInt()
        val grouped = groupIndian(rupees)
        val decimals = if (fraction != 0 || forceDecimals) "." + fraction.toString().padStart(2, '0') else ""
        val sign = if (negative) "-" else ""
        return sign + (if (symbol) "₹" else "") + grouped + decimals
    }

    /** Short form for charts and chips: ₹950, ₹12.4K, ₹1.2L, ₹3.4Cr. */
    fun compact(paise: Long): String {
        val rupees = abs(paise) / 100.0
        val sign = if (paise < 0) "-" else ""
        val body = when {
            rupees >= 1_00_00_000 -> trim(rupees / 1_00_00_000) + "Cr"
            rupees >= 1_00_000 -> trim(rupees / 1_00_000) + "L"
            rupees >= 1_000 -> trim(rupees / 1_000) + "K"
            else -> rupees.toLong().toString()
        }
        return "$sign₹$body"
    }

    private fun trim(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    fun groupIndian(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }
}
