package app.spendtracker.logic

import app.spendtracker.domain.bills.BillKind
import app.spendtracker.domain.bills.BillParser
import app.spendtracker.domain.summary.DailySummary
import app.spendtracker.domain.summary.SummaryBill
import app.spendtracker.domain.summary.SummaryTxn
import app.spendtracker.domain.util.Period
import app.spendtracker.domain.util.PeriodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class NewFeaturesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 19) // Monday
    private val at = LocalDateTime.of(2026, 10, 15, 10, 0).atZone(zone).toInstant().toEpochMilli()

    // ---------- Periods ----------

    @Test
    fun todayRangeIsOneDay() {
        val (from, to) = Period(PeriodKind.TODAY).millis(today, zone)
        assertEquals(today.atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), to)
        assertEquals("Today", Period(PeriodKind.TODAY).label(today))
    }

    @Test
    fun weekStartsMonday() {
        val (s, e) = Period(PeriodKind.THIS_WEEK).dates(LocalDate.of(2026, 10, 22))
        assertEquals(LocalDate.of(2026, 10, 19), s)
        assertEquals(LocalDate.of(2026, 10, 22), e)
    }

    @Test
    fun customRangeInclusiveAndSwapped() {
        val p = Period(PeriodKind.CUSTOM, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 10), p.dates(today))
        assertEquals(10L, p.dayCount(today))
        assertEquals("1 Oct – 10 Oct 2026", p.label(today))
        assertEquals(p, Period.decode(p.encode()))
        assertEquals(Period(PeriodKind.TODAY), Period.decode("TODAY"))
    }

    @Test
    fun lastMonthFullMonth() {
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30), Period(PeriodKind.LAST_MONTH).dates(today))
    }

    // ---------- Bills ----------

    @Test
    fun iciciCardDue() {
        val b = BillParser.parse("Payment of INR 12,450.00 for ICICI Bank Credit Card XX5678 is due on 20-Oct-26. Min amount due INR 620.00. Pay now via iMobile.", at, zone)
        assertNotNull(b); b!!
        assertEquals(BillKind.CREDIT_CARD, b.kind)
        assertEquals(1245000L, b.amountPaise)
        assertEquals(62000L, b.minDuePaise)
        assertEquals(LocalDate.of(2026, 10, 20), b.dueDate)
        assertEquals("5678", b.last4)
        assertEquals("ICICI credit card ••5678", b.biller)
    }

    @Test
    fun hdfcStatementStyle() {
        val b = BillParser.parse("HDFC Bank Credit Card XX1234 statement: Total Amt Due Rs 5,000.00, Min Amt Due Rs 250.00, Due Date 05/11/2026.", at, zone)!!
        assertEquals(500000L, b.amountPaise)
        assertEquals(25000L, b.minDuePaise)
        assertEquals(LocalDate.of(2026, 11, 5), b.dueDate)
        assertEquals("HDFC credit card ••1234", b.biller)
    }

    @Test
    fun electricityBill() {
        val b = BillParser.parse("Dear Customer, your BESCOM electricity bill of Rs.1,234 for RR No 1234567 is due on 25th Oct 2026. Pay via BBPS.", at, zone)!!
        assertEquals(BillKind.ELECTRICITY, b.kind)
        assertEquals(123400L, b.amountPaise)
        assertEquals(LocalDate.of(2026, 10, 25), b.dueDate)
        assertEquals("BESCOM electricity bill", b.biller)
    }

    @Test
    fun loanEmi() {
        val b = BillParser.parse("EMI of Rs 8,999 for your Bajaj Finserv loan a/c XX4455 is due on 02-11-2026. Keep sufficient balance.", at, zone)!!
        assertEquals(BillKind.LOAN, b.kind)
        assertEquals(899900L, b.amountPaise)
        assertEquals("4455", b.last4)
    }

    @Test
    fun paymentConfirmationIsNotABill() {
        assertNull(BillParser.parse("Payment of Rs 12,450.00 received towards your ICICI Bank Credit Card XX5678. Thank you.", at, zone))
    }

    @Test
    fun normalDebitIsNotABill() {
        assertNull(BillParser.parse("Rs 500.00 debited from a/c XX7890 on 12-10-26 to VPA zomato@kotak", at, zone))
    }

    @Test
    fun offerIsNotABill() {
        assertNull(BillParser.parse("Pre-approved loan of Rs 5,00,000, EMI starting Rs 999. Apply now, offer valid till 30-10-2026.", at, zone))
    }

    // ---------- Daily summary ----------

    @Test
    fun summaryText() {
        val s = DailySummary.build(
            today,
            listOf(
                SummaryTxn(42000, true, true, "Food and dining", "Swiggy"),
                SummaryTxn(23000, true, true, "Food and dining", "Zomato"),
                SummaryTxn(150000, true, true, "Shopping", "Amazon"),
                SummaryTxn(100000, true, false, "Transfers", "Self"),
                SummaryTxn(50000, false, true, "Income", "Refund"),
            ),
            listOf(SummaryBill("ICICI credit card ••5678", 1245000, today.plusDays(1)), SummaryBill("Far", 100, today.plusDays(10))),
            monthSpentPaise = 2_000_000, monthBudgetPaise = 3_000_000,
        )
        assertEquals("Today you spent ₹2,150", s.title)
        assertTrue(s.body, s.body.contains("3 payments · most on Shopping (₹1,500) · biggest: Amazon ₹1,500"))
        assertTrue(s.body, s.body.contains("Received ₹500"))
        assertTrue(s.body, s.body.contains("₹10,000 left in this month's budget"))
        assertTrue(s.body, s.body.contains("Due tomorrow: ICICI credit card ••5678 ₹12,450"))
        assertTrue(!s.body.contains("Far"))
    }

    @Test
    fun summaryNoSpend() {
        assertEquals("No spending today", DailySummary.build(today, emptyList(), emptyList()).title)
    }
}
