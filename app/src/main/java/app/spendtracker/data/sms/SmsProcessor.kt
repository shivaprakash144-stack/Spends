package app.spendtracker.data.sms

import app.spendtracker.data.db.AccountEntity
import app.spendtracker.data.db.AppDatabase
import app.spendtracker.data.db.RawSmsEntity
import app.spendtracker.data.db.TransactionEntity
import app.spendtracker.data.repository.RulesSource
import app.spendtracker.domain.dedupe.DedupeDecision
import app.spendtracker.domain.dedupe.Deduplicator
import app.spendtracker.domain.dedupe.TxnFingerprint
import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.ParseStatus
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnSource
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.statement.ParsedStatement
import app.spendtracker.domain.statement.StatementRow
import app.spendtracker.domain.parser.ParseResult
import app.spendtracker.domain.parser.ParsedSms
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class ProcessOutcome { ALREADY_SEEN, IGNORED, FAILED, ADDED, DUPLICATE }

/**
 * The one-way pipeline every SMS goes through: raw_sms -> filter/parse -> dedupe -> categorise -> transactions.
 * Used by both the inbox scan and the live receiver; a mutex keeps them from racing.
 */
@Singleton
class SmsProcessor @Inject constructor(
    private val db: AppDatabase,
    private val rules: RulesSource,
) {
    private val mutex = Mutex()

    /** Bank alert email (from Gmail sync). Stored in raw_sms with an "email:" sender so it can be re-parsed. */
    suspend fun processEmail(from: String, subject: String, body: String, receivedAt: Long): ProcessOutcome =
        process(EMAIL_PREFIX + from, SUBJECT_PREFIX + subject + "\n\n" + body.take(MAX_EMAIL_BODY), receivedAt, null)

    /** Notification from Gmail or a bank / UPI app. */
    suspend fun processNotification(packageName: String, title: String, text: String, postedAt: Long): ProcessOutcome =
        process(NOTIF_PREFIX + packageName, title + "\n" + text, postedAt, null)

    suspend fun process(sender: String, body: String, receivedAt: Long, androidSmsId: Long?): ProcessOutcome = mutex.withLock {
        rules.ensureSeeded()
        val hash = sha256("${sender.uppercase().trim()}|$body")
        // Live receiver and inbox scan see the same SMS with slightly different times.
        val seen = db.rawSms().byHash(hash).any { abs(it.receivedAt - receivedAt) < SAME_SMS_WINDOW_MS }
        if (seen) return@withLock ProcessOutcome.ALREADY_SEEN
        // Personal emails and app notifications that are not transactions are never stored.
        if ((sender.startsWith(EMAIL_PREFIX) || sender.startsWith(NOTIF_PREFIX)) && parse(sender, body, receivedAt).first is ParseResult.Ignored) {
            return@withLock ProcessOutcome.IGNORED
        }

        val rawId = db.rawSms().insert(
            RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.FAILED, hash = hash, androidSmsId = androidSmsId),
        )
        handle(rawId, sender, body, receivedAt, androidSmsId)
    }

    /** Re-run the parser over everything not yet parsed (after patterns improve). */
    suspend fun reparseUnparsed(): Int = mutex.withLock {
        var added = 0
        db.rawSms().unparsed().forEach { raw ->
            if (handle(raw.id, raw.sender, raw.body, raw.receivedAt, raw.androidSmsId) == ProcessOutcome.ADDED) added++
        }
        added
    }

    private fun parse(sender: String, body: String, receivedAt: Long): Pair<ParseResult, TxnSource> = when {
        sender.startsWith(EMAIL_PREFIX) -> {
            val subject = body.substringBefore("\n\n").removePrefix(SUBJECT_PREFIX)
            val text = body.substringAfter("\n\n", "")
            rules.parser.parseEmail(sender.removePrefix(EMAIL_PREFIX), subject, text, receivedAt) to TxnSource.EMAIL
        }
        sender.startsWith(NOTIF_PREFIX) -> {
            val title = body.substringBefore("\n")
            val text = body.substringAfter("\n", "")
            rules.parser.parseNotification(sender.removePrefix(NOTIF_PREFIX), title, text, receivedAt) to TxnSource.NOTIFICATION
        }
        else -> rules.parser.parse(sender, body, receivedAt) to TxnSource.SMS
    }

    private suspend fun handle(rawId: Long, sender: String, body: String, receivedAt: Long, androidSmsId: Long?): ProcessOutcome {
        val (result, source) = parse(sender, body, receivedAt)
        return when (val r = result) {
            is ParseResult.Ignored -> {
                db.rawSms().setStatus(rawId, ParseStatus.IGNORED.name, r.reason)
                ProcessOutcome.IGNORED
            }
            is ParseResult.Failed -> {
                db.rawSms().setStatus(rawId, ParseStatus.FAILED.name, r.reason)
                ProcessOutcome.FAILED
            }
            is ParseResult.Parsed -> {
                db.rawSms().setStatus(rawId, ParseStatus.PARSED.name, null)
                store(r.sms, body, rawId, androidSmsId, source)
            }
        }
    }

    private suspend fun store(p: ParsedSms, body: String, rawId: Long, androidSmsId: Long?, source: TxnSource): ProcessOutcome {
        val account = upsertAccount(p)
        val cat = rules.categorizer().categorize(p.merchantName, p.merchantRaw, body, p.type, p.mode)
        val categoryId = cat.categoryId ?: rules.uncategorisedId()

        var txn = TransactionEntity(
            amountPaise = p.amountPaise,
            type = p.type,
            timestamp = p.timestamp,
            merchantRaw = p.merchantRaw,
            merchantName = cat.displayName ?: p.merchantName,
            categoryId = categoryId,
            accountId = account.id,
            mode = p.mode,
            upiRef = p.upiRef,
            balancePaise = p.balancePaise,
            note = null,
            source = source,
            smsId = androidSmsId,
            smsHash = "raw:$rawId",
        )

        val nearby = db.transactions().findSameAmountBetween(
            p.amountPaise, p.timestamp - Deduplicator.REF_WINDOW_MS, p.timestamp + Deduplicator.REF_WINDOW_MS,
        )
        val accounts = db.accounts().getAll().associateBy { it.id }
        val decision = Deduplicator.decide(fingerprint(txn, p.isUpiApp, accounts), nearby.map { fingerprint(it, accounts[it.accountId]?.let { a -> isUpiAppAccount(a) } ?: false, accounts) })

        return when (decision) {
            is DedupeDecision.NewIsDuplicate -> {
                db.transactions().insert(txn.copy(isDuplicateOf = decision.existingId))
                ProcessOutcome.DUPLICATE
            }
            is DedupeDecision.ExistingIsDuplicate -> {
                val existing = db.transactions().get(decision.existingId)
                // Keep the user's edits from the poorer copy.
                if (existing != null) {
                    txn = txn.copy(
                        note = existing.note,
                        excluded = existing.excluded,
                        categoryId = if (existing.categoryLocked) existing.categoryId else txn.categoryId,
                        categoryLocked = existing.categoryLocked,
                    )
                }
                val newId = db.transactions().insert(txn)
                if (newId > 0) db.transactions().markDuplicate(decision.existingId, newId)
                ProcessOutcome.ADDED
            }
            DedupeDecision.Unique -> {
                val newId = db.transactions().insert(txn)
                if (newId > 0) detectSelfTransfer(txn.copy(id = newId), nearby, accounts)
                ProcessOutcome.ADDED
            }
        }
    }

    // ---------------- Bank statement import ----------------

    /** Indexes of [rows] that already exist (captured from SMS, email or an earlier import). */
    suspend fun findStatementDuplicates(rows: List<StatementRow>): Set<Int> = mutex.withLock {
        rows.indices.filter { i -> existingFor(rows[i]) != null }.toSet()
    }

    /** Inserts the rows that are not already captured. Returns how many were added. */
    suspend fun importStatement(statement: ParsedStatement, rows: List<StatementRow>): Int = mutex.withLock {
        rules.ensureSeeded()
        val bankName = statement.bankHint ?: "Statement"
        val bank = rules.parser.config.banks.firstOrNull { b ->
            b.names.any { it.equals(bankName, true) } || b.name.contains(bankName, true) || bankName.contains(b.name, true)
        }
        val accountType = if (statement.isCreditCard) AccountType.CREDIT_CARD else AccountType.SAVINGS
        val account = upsertAccount(
            ParsedSms(
                bankId = bank?.id ?: "statement-" + bankName.lowercase().replace(Regex("[^a-z0-9]+"), ""),
                bankName = bank?.name ?: bankName, isUpiApp = false, amountPaise = 0, type = TxnType.DEBIT,
                mode = TxnMode.OTHER, accountType = accountType, last4 = statement.last4, merchantRaw = null,
                merchantName = "", upiRef = null, balancePaise = null, availableLimitPaise = null, timestamp = 0, patternId = "statement",
            ),
        )
        val categorizer = rules.categorizer()
        val uncategorised = rules.uncategorisedId()
        var added = 0
        var latest: StatementRow? = null
        for (r in rows) {
            if (existingFor(r) != null) continue
            val cat = categorizer.categorize(r.merchantName, r.narration, r.narration, r.type, r.mode)
            val id = db.transactions().insert(
                TransactionEntity(
                    amountPaise = r.amountPaise, type = r.type, timestamp = noon(r.date),
                    merchantRaw = r.narration.take(200), merchantName = cat.displayName ?: r.merchantName,
                    categoryId = cat.categoryId ?: uncategorised, accountId = account.id, mode = r.mode,
                    upiRef = r.ref, balancePaise = r.balancePaise, note = null, source = TxnSource.STATEMENT,
                    smsId = null, smsHash = null,
                ),
            )
            if (id > 0) added++
            if (r.balancePaise != null && (latest == null || !r.date.isBefore(latest.date))) latest = r
        }
        latest?.let { l ->
            val ts = noon(l.date)
            if ((account.lastBalanceAt ?: 0) < ts) db.accounts().update(account.copy(lastBalancePaise = l.balancePaise, lastBalanceAt = ts))
        }
        added
    }

    private suspend fun existingFor(r: StatementRow): TransactionEntity? {
        val day = noon(r.date)
        val candidates = db.transactions().findSameAmountBetween(r.amountPaise, day - 36 * HOUR, day + 36 * HOUR)
        return candidates.firstOrNull { t ->
            t.type == r.type && (r.ref == null || t.upiRef == null || t.upiRef == r.ref || t.upiRef.endsWith(r.ref) || r.ref.endsWith(t.upiRef))
        }
    }

    private fun noon(d: java.time.LocalDate): Long =
        d.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun detectSelfTransfer(txn: TransactionEntity, nearby: List<TransactionEntity>, accounts: Map<Long, AccountEntity>) {
        val counterpart = Deduplicator.findSelfTransfer(
            fingerprint(txn, false, accounts),
            nearby.map { fingerprint(it, false, accounts) },
        ) ?: return
        val transfersId = rules.transfersId() ?: return
        listOf(txn.id, counterpart.id).forEach { id ->
            val t = db.transactions().get(id) ?: return@forEach
            if (!t.categoryLocked) db.transactions().update(t.copy(categoryId = transfersId, excluded = true))
        }
    }

    private suspend fun upsertAccount(p: ParsedSms): AccountEntity {
        val dao = db.accounts()
        val existing = dao.find(p.bankId, p.last4, p.accountType.name)
        val acc = existing ?: run {
            val label = when (p.accountType) {
                AccountType.CREDIT_CARD -> "${p.bankName} card"
                AccountType.WALLET -> "${p.bankName} wallet"
                else -> p.bankName
            } + (p.last4?.let { " ••$it" } ?: "")
            val a = AccountEntity(bankId = p.bankId, bankName = p.bankName, last4 = p.last4, type = p.accountType, displayName = label)
            a.copy(id = dao.insert(a))
        }
        val newer = acc.lastBalanceAt == null || p.timestamp >= (acc.lastBalanceAt ?: 0)
        if (newer && (p.balancePaise != null || p.availableLimitPaise != null)) {
            val updated = acc.copy(
                lastBalancePaise = p.balancePaise ?: acc.lastBalancePaise,
                availableLimitPaise = p.availableLimitPaise ?: acc.availableLimitPaise,
                lastBalanceAt = p.timestamp,
            )
            dao.update(updated)
            return updated
        }
        return acc
    }

    private fun isUpiAppAccount(a: AccountEntity) = a.bankId in UPI_APP_IDS

    private fun fingerprint(t: TransactionEntity, fromUpiApp: Boolean, accounts: Map<Long, AccountEntity>) = TxnFingerprint(
        id = t.id,
        amountPaise = t.amountPaise,
        type = t.type,
        timestamp = t.timestamp,
        upiRef = t.upiRef,
        last4 = t.accountId?.let { accounts[it]?.last4 },
        accountId = t.accountId?.takeIf { accounts[it]?.type != AccountType.CASH },
        merchantKnown = t.merchantName.isNotBlank() && t.merchantName != "Unknown",
        hasBalance = t.balancePaise != null,
        fromUpiApp = fromUpiApp,
        weak = t.source == TxnSource.EMAIL || t.source == TxnSource.NOTIFICATION || t.source == TxnSource.STATEMENT,
    )

    companion object {
        const val SAME_SMS_WINDOW_MS = 10 * 60 * 1000L
        private const val HOUR = 60 * 60 * 1000L
        const val EMAIL_PREFIX = "email:"
        const val NOTIF_PREFIX = "notif:"
        private const val SUBJECT_PREFIX = "Subject: "
        private const val MAX_EMAIL_BODY = 4000
        private val UPI_APP_IDS = setOf("paytm", "phonepe", "gpay")

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
