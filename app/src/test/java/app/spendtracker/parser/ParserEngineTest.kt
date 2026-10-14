package app.spendtracker.parser

import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.parser.ParseResult
import app.spendtracker.domain.parser.ParsedSms
import app.spendtracker.domain.parser.ParserEngine
import app.spendtracker.domain.parser.PatternConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fixtures are the illustrative shapes from the handoff plus common variants.
 * Replace / extend with anonymised copies of your own SMS: wording changes by bank and over time.
 */
class ParserEngineTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val engine = ParserEngine(
        PatternConfig.parse(
            requireNotNull(javaClass.classLoader?.getResource("bank_patterns.json")) { "bank_patterns.json not on test classpath" }.readText(),
        ),
        zone,
    )
    private val received = LocalDateTime.of(2026, 10, 12, 14, 30).atZone(zone).toInstant().toEpochMilli()

    private fun parsed(sender: String, body: String): ParsedSms {
        val r = engine.parse(sender, body, received)
        assertTrue("Expected Parsed but was $r for: $body", r is ParseResult.Parsed)
        return (r as ParseResult.Parsed).sms
    }

    private fun ignored(sender: String, body: String) {
        val r = engine.parse(sender, body, received)
        assertTrue("Expected Ignored but was $r for: $body", r is ParseResult.Ignored)
    }

    private fun dateOf(sms: ParsedSms): LocalDate =
        java.time.Instant.ofEpochMilli(sms.timestamp).atZone(zone).toLocalDate()

    // ---- Handoff samples ----

    @Test
    fun hdfcUpiSent() {
        val s = parsed("VM-HDFCBK", "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 12/10/26\nRef 628512345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808")
        assertEquals(25000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals(TxnMode.UPI, s.mode)
        assertEquals("1234", s.last4)
        assertEquals("Swiggy", s.merchantName)
        assertEquals("628512345678", s.upiRef)
        assertEquals("hdfc", s.bankId)
        assertEquals(LocalDate.of(2026, 10, 12), dateOf(s))
    }

    @Test
    fun iciciCardSpent() {
        val s = parsed("AD-ICICIB", "INR 1,499.00 spent using ICICI Bank Card XX5678 on 12-Oct-26 on AMAZON. Avl Limit: INR 48,501.00. If not you, call 1800 2662/SMS BLOCK 5678 to 9215676766.")
        assertEquals(149900L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals(TxnMode.CARD, s.mode)
        assertEquals(AccountType.CREDIT_CARD, s.accountType)
        assertEquals("5678", s.last4)
        assertEquals("Amazon", s.merchantName)
        assertEquals(4850100L, s.availableLimitPaise)
    }

    @Test
    fun sbiUpiDebit() {
        val s = parsed("VM-SBIUPI", "Dear UPI user A/C X9012 debited by 120.0 on date 12Oct26 trf to UBER Refno 628598765432. If not u? call 1800111109. -SBI")
        assertEquals(12000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals(TxnMode.UPI, s.mode)
        assertEquals("9012", s.last4)
        assertEquals("Uber", s.merchantName)
        assertEquals("628598765432", s.upiRef)
        assertEquals(LocalDate.of(2026, 10, 12), dateOf(s))
    }

    @Test
    fun axisNeftCreditWithBalance() {
        val s = parsed("AX-AXISBK-S", "INR 45,000.00 credited to A/c no. XX3456 on 01-10-26 by NEFT. Avl Bal INR 52,310.00")
        assertEquals(4500000L, s.amountPaise)
        assertEquals(TxnType.CREDIT, s.type)
        assertEquals(TxnMode.NEFT, s.mode)
        assertEquals("3456", s.last4)
        assertEquals(5231000L, s.balancePaise)
        assertEquals(LocalDate.of(2026, 10, 1), dateOf(s))
    }

    @Test
    fun kotakVpaDebit() {
        val s = parsed("JD-KOTAKB", "Rs 500.00 debited from a/c XX7890 on 12-10-26 to VPA zomato@kotak")
        assertEquals(50000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals("7890", s.last4)
        assertEquals("Zomato", s.merchantName)
    }

    // ---- Variants ----

    @Test
    fun hdfcCardSpent() {
        val s = parsed("VM-HDFCBK", "Spent Rs.649 On HDFC Bank Card 4321 At NETFLIX On 2026-10-12:09:15:01 Not You? To Block+Reissue Call 18002586161")
        assertEquals(64900L, s.amountPaise)
        assertEquals(AccountType.CREDIT_CARD, s.accountType)
        assertEquals("Netflix", s.merchantName)
    }

    @Test
    fun hdfcSalaryDeposit() {
        val s = parsed("VM-HDFCBK", "Update! INR 85,000.00 deposited in HDFC Bank A/c XX1234 on 01-OCT-26 for NEFT Cr-CITI0000004-ACME TECHNOLOGIES PVT LTD-CITIN26123456.Avl bal INR 1,02,500.00. Cheque deposits in A/C are subject to clearing")
        assertEquals(8500000L, s.amountPaise)
        assertEquals(TxnType.CREDIT, s.type)
        assertEquals(10250000L, s.balancePaise)
        assertEquals("Acme Technologies", s.merchantName)
    }

    @Test
    fun hdfcUpiReceived() {
        val s = parsed("VM-HDFCBK", "Money Received - INR 1,200.00 in A/c XX1234 on 12-10-26 by VPA rahul.k@okaxis (UPI Ref No 628500011122)")
        assertEquals(TxnType.CREDIT, s.type)
        assertEquals(120000L, s.amountPaise)
        assertEquals("Rahul", s.merchantName)
        assertEquals("628500011122", s.upiRef)
    }

    @Test
    fun iciciUpiDebit() {
        val s = parsed("VK-ICICIT", "ICICI Bank Acct XX123 debited for Rs 349.00 on 12-Oct-26; BLINKIT credited. UPI:628577788899. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.")
        assertEquals(34900L, s.amountPaise)
        assertEquals("123", s.last4)
        assertEquals("Blinkit", s.merchantName)
        assertEquals("628577788899", s.upiRef)
    }

    @Test
    fun kotakSent() {
        val s = parsed("AX-KOTAKB-S", "Sent Rs.180.00 from Kotak Bank AC X7890 to rapido.bike@axisbank on 12-10-26.UPI Ref 628511122233. Not you, https://kotak.com/KBANKT/Fraud")
        assertEquals(18000L, s.amountPaise)
        assertEquals("Rapido", s.merchantName)
        assertEquals("628511122233", s.upiRef)
    }

    @Test
    fun axisUpiShort() {
        val s = parsed("AD-AXISBK", "Debit INR 99.00 A/c no. XX3456 12-10-26 13:01:12 UPI/P2M/628533344455/SPOTIFY Not you? SMS BLOCK 3456 to 919951860002")
        assertEquals(9900L, s.amountPaise)
        assertEquals("628533344455", s.upiRef)
        assertEquals("Spotify", s.merchantName)
    }

    @Test
    fun paytmWallet() {
        val s = parsed("VM-PAYTMB", "Paid Rs.75 to Chaayos from Paytm Balance. Updated Balance: Paytm Wallet- Rs 225. Ref: 39812345678")
        assertEquals(7500L, s.amountPaise)
        assertEquals(AccountType.WALLET, s.accountType)
        assertTrue(s.isUpiApp)
        assertEquals("Chaayos", s.merchantName)
    }

    @Test
    fun sbiAtmWithdrawal() {
        val s = parsed("VM-ATMSBI", "Dear Customer, transaction number 1234 for Rs.2000.00 by SBI Debit Card X4455 done at ATM SBIN0001234 on 12Oct26 at 10:11:12. Your updated available balance is Rs.5000.00. If not done by you, forward this SMS to 7400165218")
        assertEquals(200000L, s.amountPaise)
        assertEquals(TxnMode.ATM, s.mode)
        assertEquals(500000L, s.balancePaise)
    }

    @Test
    fun genericFallbackForUnknownBank() {
        val s = parsed("VM-IDFCFB", "Your A/C XXXXXX6789 has been debited with INR 1,250.00 on 12-OCT-2026 towards UPI to BIGBASKET. Avl Bal INR 9,000.00")
        assertEquals(125000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals("6789", s.last4)
        assertEquals(900000L, s.balancePaise)
        assertEquals("generic", s.patternId)
    }

    // ---- Things that must be ignored ----

    @Test
    fun otpIgnored() = ignored("VM-HDFCBK", "OTP is 482910 for txn of INR 2,000.00 at AMAZON on HDFC Bank card ending 4321. Valid till 14:35. Do not share OTP for security reasons.")

    @Test
    fun promoIgnored() = ignored("VM-HDFCBK", "Congratulations! You are pre-approved for a Personal Loan of up to Rs 5,00,000. Apply now.")

    @Test
    fun failedIgnored() = ignored("VM-SBIUPI", "Your UPI transaction of Rs.300.00 to SWIGGY has failed. Amount if debited will be refunded.")

    @Test
    fun declinedIgnored() = ignored("AD-ICICIB", "Transaction of INR 5,000.00 on ICICI Bank Card XX5678 has been declined due to incorrect PIN.")

    @Test
    fun paymentRequestIgnored() = ignored("VM-HDFCBK", "rahul@okaxis has requested money from you. On approving, INR 500.00 will be debited from your A/c.")

    @Test
    fun dueReminderIgnored() = ignored("AD-ICICIB", "Payment of INR 12,450.00 for ICICI Bank Credit Card XX5678 is due on 20-Oct-26.")

    @Test
    fun personalNumberIgnored() = ignored("+919876543210", "Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 12/10/26 Ref 628512345678")

    @Test
    fun orderConfirmationFromShopIgnored() = ignored("AD-SWIGGY", "Your order of Rs 349 has been paid successfully. Track your order in the app.")

    @Test
    fun noAmountIgnored() = ignored("VM-HDFCBK", "Your HDFC Bank NetBanking password was changed successfully.")

    @Test
    fun knownBankUnknownShapeFails() {
        val r = engine.parse("VM-HDFCBK", "Rs.900.00 txn on A/c XX1234 processed. Info: something new", received)
        // "txn of" is a debit word; "txn on" is not -> amount without type -> review list
        assertTrue("Expected Failed but was $r", r is ParseResult.Failed)
    }

    @Test
    fun bodyWithoutDateUsesReceivedTime() {
        val s = parsed("VM-PAYTMB", "Paid Rs.75 to Chaayos from Paytm Balance. Ref: 39812345678")
        assertEquals(received, s.timestamp)
        assertNull(s.last4)
    }
}
