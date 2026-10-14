package app.spendtracker.dedupe

import app.spendtracker.domain.dedupe.DedupeDecision
import app.spendtracker.domain.dedupe.Deduplicator
import app.spendtracker.domain.dedupe.TxnFingerprint
import app.spendtracker.domain.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeduplicatorTest {

    private val t0 = 1_760_000_000_000L

    private fun fp(
        id: Long, amount: Long = 25000, type: TxnType = TxnType.DEBIT, at: Long = t0,
        ref: String? = null, last4: String? = null, account: Long? = null,
        merchant: Boolean = true, balance: Boolean = false, upiApp: Boolean = false,
    ) = TxnFingerprint(id, amount, type, at, ref, last4, account, merchant, balance, upiApp)

    @Test
    fun bankAndUpiAppSmsForSamePaymentCountOnce() {
        val bank = fp(1, ref = "628512345678", last4 = "1234", balance = true)
        val app = fp(0, at = t0 + 40_000, ref = "628512345678", upiApp = true)
        assertEquals(DedupeDecision.NewIsDuplicate(1), Deduplicator.decide(app, listOf(bank)))
    }

    @Test
    fun richerNewRecordReplacesOld() {
        val app = fp(5, ref = "628512345678", upiApp = true)
        val bank = fp(0, at = t0 + 60_000, ref = "628512345678", last4 = "1234", balance = true)
        assertEquals(DedupeDecision.ExistingIsDuplicate(5), Deduplicator.decide(bank, listOf(app)))
    }

    @Test
    fun sameLast4WithinFiveMinutes() {
        val a = fp(1, last4 = "1234")
        val b = fp(0, at = t0 + 4 * 60_000, last4 = "1234")
        assertEquals(DedupeDecision.NewIsDuplicate(1), Deduplicator.decide(b, listOf(a)))
    }

    @Test
    fun twoRealCoffeesTenMinutesApartAreKept() {
        val a = fp(1, last4 = "1234")
        val b = fp(0, at = t0 + 10 * 60_000, last4 = "1234")
        assertEquals(DedupeDecision.Unique, Deduplicator.decide(b, listOf(a)))
    }

    @Test
    fun differentAmountIsUnique() {
        assertEquals(DedupeDecision.Unique, Deduplicator.decide(fp(0, amount = 100, last4 = "1"), listOf(fp(1, amount = 200, last4 = "1"))))
    }

    @Test
    fun selfTransferDetected() {
        val debit = fp(1, amount = 1_000_000, account = 10)
        val credit = fp(0, amount = 1_000_000, type = TxnType.CREDIT, at = t0 + 3 * 60_000, account = 11)
        assertNotNull(Deduplicator.findSelfTransfer(credit, listOf(debit)))
        val sameAccount = fp(0, amount = 1_000_000, type = TxnType.CREDIT, at = t0 + 60_000, account = 10)
        assertNull(Deduplicator.findSelfTransfer(sameAccount, listOf(debit)))
    }
}
