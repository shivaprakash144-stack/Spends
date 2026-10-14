package app.spendtracker.domain.dedupe

import app.spendtracker.domain.model.TxnType
import kotlin.math.abs

/** The fields dedupe needs, independent of Room. */
data class TxnFingerprint(
    val id: Long,
    val amountPaise: Long,
    val type: TxnType,
    val timestamp: Long,
    val upiRef: String?,
    val last4: String?,
    val accountId: Long?,
    val merchantKnown: Boolean,
    val hasBalance: Boolean,
    val fromUpiApp: Boolean,
)

sealed interface DedupeDecision {
    data object Unique : DedupeDecision
    /** The new record is the poorer copy: store it marked isDuplicateOf = [existingId]. */
    data class NewIsDuplicate(val existingId: Long) : DedupeDecision
    /** The new record is richer: store it and mark [existingId] as its duplicate. */
    data class ExistingIsDuplicate(val existingId: Long) : DedupeDecision
}

object Deduplicator {
    const val WINDOW_MS = 5 * 60 * 1000L
    const val REF_WINDOW_MS = 24 * 60 * 60 * 1000L
    const val SELF_TRANSFER_WINDOW_MS = 10 * 60 * 1000L

    /**
     * Same amount and type, within 5 minutes, and the UPI ref or the account last-4 match.
     * An identical UPI ref within a day is treated as the same payment on its own.
     */
    fun decide(candidate: TxnFingerprint, recent: List<TxnFingerprint>): DedupeDecision {
        val match = recent.firstOrNull { other ->
            if (other.id == candidate.id) return@firstOrNull false
            if (other.amountPaise != candidate.amountPaise || other.type != candidate.type) return@firstOrNull false
            val dt = abs(other.timestamp - candidate.timestamp)
            val refMatch = !candidate.upiRef.isNullOrBlank() && candidate.upiRef == other.upiRef
            val last4Match = !candidate.last4.isNullOrBlank() && candidate.last4 == other.last4
            (refMatch && dt <= REF_WINDOW_MS) || (dt <= WINDOW_MS && (refMatch || last4Match))
        } ?: return DedupeDecision.Unique

        return if (richness(candidate) > richness(match)) DedupeDecision.ExistingIsDuplicate(match.id)
        else DedupeDecision.NewIsDuplicate(match.id)
    }

    /** Bank SMS usually beat UPI-app SMS: they carry account, balance and a clean ref. */
    fun richness(t: TxnFingerprint): Int {
        var score = 0
        if (!t.upiRef.isNullOrBlank()) score += 2
        if (!t.last4.isNullOrBlank()) score += 2
        if (t.hasBalance) score += 1
        if (t.merchantKnown) score += 2
        if (!t.fromUpiApp) score += 1
        return score
    }

    /**
     * A debit and a credit of the same amount between two of the user's own accounts within
     * 10 minutes is a self-transfer. Returns the matching counterpart, if any.
     */
    fun findSelfTransfer(candidate: TxnFingerprint, recent: List<TxnFingerprint>): TxnFingerprint? =
        recent.firstOrNull { other ->
            other.id != candidate.id &&
                other.amountPaise == candidate.amountPaise &&
                other.type != candidate.type &&
                candidate.accountId != null && other.accountId != null &&
                other.accountId != candidate.accountId &&
                abs(other.timestamp - candidate.timestamp) <= SELF_TRANSFER_WINDOW_MS
        }
}
