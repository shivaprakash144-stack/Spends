package app.spendtracker.parser

import app.spendtracker.domain.email.EmailText
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.parser.ParseResult
import app.spendtracker.domain.parser.ParsedSms
import app.spendtracker.domain.parser.ParserEngine
import app.spendtracker.domain.parser.PatternConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

class EmailAndNotificationTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val engine = ParserEngine(
        PatternConfig.parse(requireNotNull(javaClass.classLoader?.getResource("bank_patterns.json")).readText()), zone,
    )
    private val at = LocalDateTime.of(2026, 10, 12, 14, 30).atZone(zone).toInstant().toEpochMilli()

    private fun ok(r: ParseResult): ParsedSms {
        assertTrue("Expected Parsed but was $r", r is ParseResult.Parsed)
        return (r as ParseResult.Parsed).sms
    }

    @Test
    fun hdfcUpiEmailWithOtpFooter() {
        val s = ok(
            engine.parseEmail(
                "HDFC Bank InstaAlerts <alerts@hdfcbank.net>",
                "❗ You have done a UPI txn. Check details!",
                "Dear Customer, Rs.250.00 has been debited from account **1234 to VPA swiggy@icici SWIGGY on 12-10-26. " +
                    "Your UPI transaction reference number is 628512345678. Please never share your OTP, PIN or password with anyone. " +
                    "If you did not authorize this transaction, call us.",
                at,
            ),
        )
        assertEquals(25000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals("1234", s.last4)
        assertEquals("628512345678", s.upiRef)
        assertEquals("hdfc", s.bankId)
        assertEquals("Swiggy", s.merchantName)
    }

    @Test
    fun genericBankEmailByDomain() {
        val s = ok(
            engine.parseEmail(
                "Axis Bank Alerts <alerts@axisbank.com>",
                "Debit transaction alert",
                "INR 1,250.00 was debited from your A/c no. XX3456 on 12-10-2026 at BIGBASKET. Available balance INR 9,000.00.",
                at,
            ),
        )
        assertEquals(125000L, s.amountPaise)
        assertEquals("axis", s.bankId)
        assertEquals("3456", s.last4)
    }

    @Test
    fun shopEmailWithoutAccountIgnored() {
        val r = engine.parseEmail("Swiggy <noreply@swiggy.in>", "Your order is confirmed", "You paid Rs 349 for your order.", at)
        assertTrue("$r", r is ParseResult.Ignored)
    }

    @Test
    fun otpEmailIgnored() {
        val r = engine.parseEmail("alerts@hdfcbank.net", "OTP for transaction", "Your OTP is 123456 for Rs 2,000.00 at AMAZON on card XX4321.", at)
        assertTrue("$r", r is ParseResult.Ignored)
    }

    @Test
    fun gpayNotification() {
        val s = ok(engine.parseNotification("com.google.android.apps.nbu.paisa.user", "Payment successful", "₹250 paid to Swiggy", at))
        assertEquals(25000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals(TxnMode.UPI, s.mode)
        assertEquals("Swiggy", s.merchantName)
    }

    @Test
    fun phonepeReceivedNotification() {
        val s = ok(engine.parseNotification("com.phonepe.app", "Money received", "Received ₹1,200 from Rahul Kumar", at))
        assertEquals(TxnType.CREDIT, s.type)
        assertEquals(120000L, s.amountPaise)
    }

    @Test
    fun gmailNotificationForBankEmail() {
        val s = ok(
            engine.parseNotification(
                "com.google.android.gm",
                "HDFC Bank InstaAlerts",
                "You have done a UPI txn. Check details! Dear Customer, Rs.180.00 has been debited from account **1234 to VPA rapido@axisbank RAPIDO on 12-10-26.",
                at,
            ),
        )
        assertEquals(18000L, s.amountPaise)
        assertEquals("hdfc", s.bankId)
    }

    @Test
    fun otherBankAppNotification() {
        val s = ok(engine.parseNotification("com.idfcfirstbank.optimus", "Transaction alert", "INR 1,250.00 debited from your A/c XX1234 on 19-10-2026 towards UPI/SWIGGY. Avl bal INR 9,000.00", at))
        assertEquals(125000L, s.amountPaise)
        assertEquals(TxnType.DEBIT, s.type)
        assertEquals("1234", s.last4)
    }

    @Test
    fun hdfcAppNotification() {
        val s = ok(engine.parseNotification("com.snapwork.hdfc", "HDFC Bank", "Rs.499.00 spent on HDFC Bank Card XX4321 at NETFLIX on 2026-10-12", at))
        assertEquals(49900L, s.amountPaise)
        assertEquals("hdfc", s.bankId)
    }

    @Test
    fun randomAppNotificationIgnored() {
        val r = engine.parseNotification("com.whatsapp", "Rahul", "Paid ₹500 to you", at)
        assertTrue(r is ParseResult.Ignored)
    }

    @Test
    fun gmailJsonExtractsHtmlBody() {
        val html = "<html><body><p>Dear Customer,</p><p>Rs.99.00 has been debited&nbsp;from account **1234</p></body></html>"
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(html.toByteArray())
        val json = """
            {"id":"abc","internalDate":"1760000000000","snippet":"Dear Customer",
             "payload":{"mimeType":"multipart/alternative","headers":[{"name":"From","value":"HDFC <alerts@hdfcbank.net>"},{"name":"Subject","value":"Alert"}],
               "parts":[{"mimeType":"text/html","body":{"data":"$data"}}]}}
        """.trimIndent()
        val msg = EmailText.fromGmailJson(Json.parseToJsonElement(json).jsonObject)!!
        assertEquals("Alert", msg.subject)
        assertEquals("Dear Customer, Rs.99.00 has been debited from account **1234", msg.body)
        assertEquals(1760000000000L, msg.receivedAt)
    }
}
