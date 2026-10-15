package app.spendtracker.parser

import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.statement.StatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Text shapes as PDFBox extracts them from Indian bank statements. Replace with your own anonymised extracts. */
class StatementParserTest {

    private val hdfc = """
        HDFC BANK LTD
        Statement of account
        Account No : 50100012341234   Cust ID : 1234567
        Statement From : 01/10/2026 To : 31/10/2026
        Opening Balance 52,310.00
        Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance
        01/10/26 UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-628512345678-PAYMENT 0000628512345678 01/10/26 250.00 52,060.00
        02/10/26 NEFT CR-CITI0000004-ACME TECHNOLOGIES PVT LTD-SALARY OCT CITIN26123456 02/10/26 85,000.00 1,37,060.00
        03/10/26 POS 512345XXXXXX1234 AMAZON PAY INDIA 0000412345678901 03/10/26 1,499.00 1,35,561.00
        04/10/26 ATW-512345XXXXXX1234-S1ANBG01-BENGALURU 0000000012345 04/10/26 2,000.00 1,33,561.00
        05/10/26 UPI-RAHUL KUMAR-RAHUL.K@OKAXIS-UTIB0001234-628599988877-DINNER
        SPLIT 0000628599988877 05/10/26 600.00 1,34,161.00
        Page 1 of 2
        STATEMENT SUMMARY
    """.trimIndent()

    @Test
    fun hdfcSavingsStatement() {
        val s = StatementParser.parse(hdfc)
        assertEquals("HDFC", s.bankHint)
        assertEquals("1234", s.last4)
        assertEquals(5, s.rows.size)
        val (swiggy, salary, amazon, atm, rahul) = s.rows
        assertEquals(TxnType.DEBIT, swiggy.type); assertEquals(25000L, swiggy.amountPaise); assertEquals("Swiggy", swiggy.merchantName)
        assertEquals("628512345678", swiggy.ref); assertEquals(TxnMode.UPI, swiggy.mode); assertEquals(LocalDate.of(2026, 10, 1), swiggy.date)
        assertEquals(TxnType.CREDIT, salary.type); assertEquals(8500000L, salary.amountPaise); assertEquals("Acme Technologies", salary.merchantName)
        assertEquals(TxnType.DEBIT, amazon.type); assertEquals(149900L, amazon.amountPaise)
        assertEquals(TxnType.DEBIT, atm.type); assertEquals(TxnMode.ATM, atm.mode); assertEquals("ATM withdrawal", atm.merchantName)
        assertEquals(TxnType.CREDIT, rahul.type); assertEquals(60000L, rahul.amountPaise); assertEquals("Rahul Kumar", rahul.merchantName)
    }

    @Test
    fun sbiNewestFirstWithoutOpeningBalance() {
        val text = """
            State Bank of India
            Account Number : 00000031234569012
            Txn Date Value Date Description Ref No./Cheque No. Debit Credit Balance
            12 Oct 2026 12 Oct 2026 TO TRANSFER-UPI/DR/628598765432/UBER/YESB/uber@yes/Payment - 120.00 9,880.00
            11 Oct 2026 11 Oct 2026 BY TRANSFER-UPI/CR/628500000001/JOHN/SBIN/john@oksbi/rent share - 5,000.00 10,000.00
            10 Oct 2026 10 Oct 2026 TO TRANSFER-UPI/DR/628500000002/ZOMATO/HDFC/zomato@hdfc/Order - 500.00 5,000.00
        """.trimIndent()
        val s = StatementParser.parse(text)
        assertEquals("9012", s.last4)
        assertEquals(3, s.rows.size)
        // Re-ordered oldest first.
        assertEquals(LocalDate.of(2026, 10, 10), s.rows[0].date)
        assertEquals("Zomato", s.rows[0].merchantName)
        assertEquals(TxnType.DEBIT, s.rows[0].type)
        assertEquals(TxnType.CREDIT, s.rows[1].type)
        assertEquals("John", s.rows[1].merchantName)
        assertEquals(TxnType.DEBIT, s.rows[2].type)
        assertEquals("Uber", s.rows[2].merchantName)
    }

    @Test
    fun creditCardStatementWithDrCr() {
        val text = """
            ICICI Bank Credit Card Statement
            Card Number 4375XXXXXXXX5678
            Date SerNo. Transaction Details Reward Points Intl.# amount Amount (in`)
            12/10/2026 1234567890 AMAZON PAY INDIA BANGALORE IN 14 1,499.00 Dr
            13/10/2026 1234567891 NETFLIX.COM MUMBAI IN 6 649.00 Dr
            15/10/2026 1234567892 BBPS PAYMENT RECEIVED - THANK YOU 0 10,000.00 Cr
        """.trimIndent()
        val s = StatementParser.parse(text)
        assertTrue(s.isCreditCard)
        assertEquals("5678", s.last4)
        assertEquals(3, s.rows.size)
        assertEquals(TxnType.DEBIT, s.rows[0].type); assertEquals(149900L, s.rows[0].amountPaise)
        assertEquals(TxnType.DEBIT, s.rows[1].type); assertEquals(64900L, s.rows[1].amountPaise)
        assertEquals(TxnType.CREDIT, s.rows[2].type)
    }
}
