package app.spendtracker.data.repository

import app.spendtracker.data.db.AccountEntity
import app.spendtracker.data.db.AppDatabase
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.MerchantRuleEntity
import app.spendtracker.data.db.RawSmsEntity
import app.spendtracker.data.db.TransactionEntity
import app.spendtracker.data.db.TxnRow
import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.CategoryType
import app.spendtracker.domain.model.RuleSource
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnSource
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.parser.MerchantCleaner
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

fun YearMonth.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

fun YearMonth.endMillis(zone: ZoneId = ZoneId.systemDefault()): Long = plusMonths(1).startMillis(zone)

@Singleton
class TransactionRepository @Inject constructor(
    private val db: AppDatabase,
    private val rules: RulesSource,
) {
    private val dao get() = db.transactions()

    fun observeMonth(month: YearMonth): Flow<List<TxnRow>> = dao.observeBetween(month.startMillis(), month.endMillis())
    fun observeBetween(from: Long, to: Long): Flow<List<TxnRow>> = dao.observeBetween(from, to)
    fun observeAll(): Flow<List<TxnRow>> = dao.observeAll()
    fun observeRecent(limit: Int): Flow<List<TxnRow>> = dao.observeRecent(limit)
    fun observeRow(id: Long): Flow<TxnRow?> = dao.observeRow(id)
    fun observeUncategorised(): Flow<List<TxnRow>> = dao.observeUncategorised()
    fun observeFailedSms(): Flow<List<RawSmsEntity>> = db.rawSms().observeFailed()
    fun observeEarliest(): Flow<Long?> = dao.observeEarliest()

    suspend fun get(id: Long) = dao.get(id)
    suspend fun rawSms(id: Long) = db.rawSms().get(id)
    suspend fun dismissSms(id: Long) = db.rawSms().dismiss(id)

    suspend fun setExcluded(id: Long, excluded: Boolean) = dao.setExcluded(id, excluded)

    suspend fun delete(id: Long) {
        dao.releaseDuplicatesOf(id)
        dao.delete(id)
    }

    suspend fun updateNote(id: Long, note: String?) {
        val t = dao.get(id) ?: return
        dao.update(t.copy(note = note?.takeIf { it.isNotBlank() }))
    }

    suspend fun update(t: TransactionEntity) = dao.update(t)

    /**
     * Change one transaction's category. With [applyToMerchant] a USER rule is saved so this merchant's
     * past and future transactions follow the correction.
     */
    suspend fun setCategory(id: Long, categoryId: Long, applyToMerchant: Boolean) {
        val t = dao.get(id) ?: return
        if (applyToMerchant) {
            val pattern = MerchantCleaner.key(t.merchantName)
            if (pattern.isNotBlank()) {
                db.rules().deleteUserRule(pattern)
                db.rules().insert(MerchantRuleEntity(pattern = pattern, categoryId = categoryId, source = RuleSource.USER, priority = 100))
                rules.invalidate()
                dao.setCategoryForMerchant(t.merchantName, categoryId)
            }
        }
        dao.setCategoryLocked(id, categoryId)
    }

    suspend fun saveManual(
        id: Long?,
        amountPaise: Long,
        type: TxnType,
        categoryId: Long?,
        timestamp: Long,
        accountId: Long?,
        merchant: String,
        note: String?,
    ): Long {
        rules.ensureSeeded()
        val cashId = db.accounts().cashAccount()?.id
        val acc = accountId ?: cashId
        val mode = if (acc == cashId) TxnMode.CASH else TxnMode.OTHER
        val existing = id?.let { dao.get(it) }
        return if (existing != null) {
            dao.update(
                existing.copy(
                    amountPaise = amountPaise, type = type, categoryId = categoryId ?: existing.categoryId,
                    categoryLocked = categoryId != null || existing.categoryLocked,
                    timestamp = timestamp, accountId = acc, merchantName = merchant.ifBlank { existing.merchantName },
                    note = note?.takeIf { it.isNotBlank() },
                    mode = if (existing.source == TxnSource.MANUAL) mode else existing.mode,
                ),
            )
            existing.id
        } else {
            val cat = categoryId ?: rules.categorizer().categorize(merchant, null, null, type, mode).categoryId ?: rules.uncategorisedId()
            dao.insert(
                TransactionEntity(
                    amountPaise = amountPaise, type = type, timestamp = timestamp,
                    merchantRaw = null, merchantName = merchant.ifBlank { if (type == TxnType.DEBIT) "Cash spend" else "Cash received" },
                    categoryId = cat, accountId = acc, mode = mode, upiRef = null, balancePaise = null,
                    note = note?.takeIf { it.isNotBlank() }, source = TxnSource.MANUAL, smsId = null, smsHash = null,
                    categoryLocked = categoryId != null,
                ),
            )
        }
    }

    suspend fun wipeSmsData() = db.maintenance().wipeSmsData()
}

@Singleton
class CategoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val rules: RulesSource,
) {
    fun observe(): Flow<List<CategoryEntity>> = db.categories().observeAll()
    fun observeUserRules(): Flow<List<MerchantRuleEntity>> = db.rules().observeUserRules()

    suspend fun add(name: String, colour: Long, icon: String, type: CategoryType): Long {
        rules.ensureSeeded()
        val id = db.categories().insert(
            CategoryEntity(slug = null, name = name.trim(), icon = icon, colour = colour, type = type, isDefault = false, sortOrder = db.categories().maxSort() + 1),
        )
        rules.invalidate()
        return id
    }

    suspend fun update(c: CategoryEntity) {
        db.categories().update(c)
        rules.invalidate()
    }

    /** Moves every transaction and rule of [from] into [into] and deletes [from]. */
    suspend fun merge(from: Long, into: Long) {
        if (from == into) return
        val src = db.categories().get(from) ?: return
        if (src.slug == "uncategorised" || src.slug == "transfers" || src.slug == "income") return
        db.maintenance().mergeCategories(from, into)
        rules.invalidate()
    }

    suspend fun deleteUserRule(id: Long) {
        db.rules().delete(id)
        rules.invalidate()
    }
}

@Singleton
class AccountRepository @Inject constructor(private val db: AppDatabase) {
    fun observe(): Flow<List<AccountEntity>> = db.accounts().observeAll()
    suspend fun rename(a: AccountEntity, name: String) = db.accounts().update(a.copy(displayName = name.trim().ifBlank { a.displayName }))
    suspend fun cash(): AccountEntity? = db.accounts().cashAccount()
    fun isOwnCard(a: AccountEntity) = a.type == AccountType.CREDIT_CARD
}

@Singleton
class BillRepository @Inject constructor(private val db: AppDatabase) {
    /** Open bills due from a week ago onwards (recently overdue ones stay visible). */
    fun observeUpcoming(today: java.time.LocalDate = java.time.LocalDate.now()): Flow<List<app.spendtracker.data.db.BillEntity>> =
        db.bills().observeDueFrom(today.minusDays(7).toEpochDay())

    fun observeAll(): Flow<List<app.spendtracker.data.db.BillEntity>> = db.bills().observeAll()

    suspend fun markPaid(id: Long) = db.bills().setStatus(id, "PAID", null)
    suspend fun dismiss(id: Long) = db.bills().setStatus(id, "DISMISSED", null)
    suspend fun reopen(id: Long) = db.bills().setStatus(id, "DUE", null)
}
