package app.spendtracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val TXN_ROW_SELECT =
    "SELECT t.*, c.name AS catName, c.icon AS catIcon, c.colour AS catColour, c.type AS catType, c.slug AS catKey, " +
        "a.displayName AS accName, a.last4 AS accLast4, a.type AS accType " +
        "FROM transactions t LEFT JOIN categories c ON c.id = t.categoryId LEFT JOIN accounts a ON a.id = t.accountId"

@Dao
interface TransactionDao {

    @Query("$TXN_ROW_SELECT WHERE t.isDuplicateOf IS NULL AND t.timestamp >= :from AND t.timestamp < :to ORDER BY t.timestamp DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TxnRow>>

    @Query("$TXN_ROW_SELECT WHERE t.isDuplicateOf IS NULL ORDER BY t.timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TxnRow>>

    @Query("$TXN_ROW_SELECT WHERE t.isDuplicateOf IS NULL ORDER BY t.timestamp DESC")
    fun observeAll(): Flow<List<TxnRow>>

    @Query("$TXN_ROW_SELECT WHERE t.id = :id")
    fun observeRow(id: Long): Flow<TxnRow?>

    @Query("$TXN_ROW_SELECT WHERE t.isDuplicateOf IS NULL AND c.slug = 'uncategorised' AND t.excluded = 0 ORDER BY t.timestamp DESC")
    fun observeUncategorised(): Flow<List<TxnRow>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE amountPaise = :amount AND timestamp BETWEEN :from AND :to AND isDuplicateOf IS NULL")
    suspend fun findSameAmountBetween(amount: Long, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT MIN(timestamp) FROM transactions WHERE isDuplicateOf IS NULL")
    fun observeEarliest(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: TransactionEntity): Long

    @Update
    suspend fun update(t: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE transactions SET isDuplicateOf = :keepId WHERE id = :id OR isDuplicateOf = :id")
    suspend fun markDuplicate(id: Long, keepId: Long)

    @Query("UPDATE transactions SET isDuplicateOf = NULL WHERE isDuplicateOf = :id")
    suspend fun releaseDuplicatesOf(id: Long)

    @Query("UPDATE transactions SET excluded = :excluded WHERE id = :id")
    suspend fun setExcluded(id: Long, excluded: Boolean)

    @Query("UPDATE transactions SET categoryId = :categoryId, merchantName = :merchantName WHERE id = :id")
    suspend fun setCategoryAndName(id: Long, categoryId: Long, merchantName: String)

    @Query("UPDATE transactions SET categoryId = :categoryId, categoryLocked = 1 WHERE id = :id")
    suspend fun setCategoryLocked(id: Long, categoryId: Long)

    /** Applies a merchant rule to every matching transaction the user has not hand-picked. */
    @Query("UPDATE transactions SET categoryId = :categoryId WHERE merchantName = :merchantName COLLATE NOCASE AND categoryLocked = 0")
    suspend fun setCategoryForMerchant(merchantName: String, categoryId: Long)

    @Query("UPDATE transactions SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveCategory(from: Long, to: Long)

    @Query("DELETE FROM transactions WHERE source = 'SMS'")
    suspend fun deleteAllSms()
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY type, bankName, last4")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE bankId = :bankId AND type = :type AND ((:last4 IS NULL AND last4 IS NULL) OR last4 = :last4) LIMIT 1")
    suspend fun find(bankId: String, last4: String?, type: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE type = 'CASH' LIMIT 1")
    suspend fun cashAccount(): AccountEntity?

    @Insert
    suspend fun insert(a: AccountEntity): Long

    @Update
    suspend fun update(a: AccountEntity)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE slug = :slug")
    suspend fun bySlug(slug: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM categories")
    suspend fun maxSort(): Int

    @Insert
    suspend fun insert(c: CategoryEntity): Long

    @Insert
    suspend fun insertAll(c: List<CategoryEntity>): List<Long>

    @Update
    suspend fun update(c: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rules")
    suspend fun getAll(): List<MerchantRuleEntity>

    @Query("SELECT * FROM merchant_rules WHERE source = 'USER' ORDER BY id DESC")
    fun observeUserRules(): Flow<List<MerchantRuleEntity>>

    @Insert
    suspend fun insert(r: MerchantRuleEntity): Long

    @Insert
    suspend fun insertAll(r: List<MerchantRuleEntity>)

    @Query("DELETE FROM merchant_rules WHERE source = 'USER' AND pattern = :pattern")
    suspend fun deleteUserRule(pattern: String)

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE merchant_rules SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveCategory(from: Long, to: Long)
}

@Dao
interface RawSmsDao {
    @Query("SELECT * FROM raw_sms WHERE hash = :hash")
    suspend fun byHash(hash: String): List<RawSmsEntity>

    @Insert
    suspend fun insert(r: RawSmsEntity): Long

    @Query("UPDATE raw_sms SET parseStatus = :status, reason = :reason WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, reason: String?)

    @Query("SELECT * FROM raw_sms WHERE parseStatus = 'FAILED' AND dismissed = 0 ORDER BY receivedAt DESC")
    fun observeFailed(): Flow<List<RawSmsEntity>>

    @Query("SELECT * FROM raw_sms WHERE parseStatus != 'PARSED' ORDER BY receivedAt")
    suspend fun unparsed(): List<RawSmsEntity>

    @Query("SELECT * FROM raw_sms WHERE id = :id")
    suspend fun get(id: Long): RawSmsEntity?

    @Query("UPDATE raw_sms SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: Long)

    @Query("SELECT COUNT(*) FROM raw_sms")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM raw_sms")
    suspend fun deleteAll()
}

/** Multi-table operations that must be atomic. */
@Dao
abstract class MaintenanceDao {
    @Query("UPDATE transactions SET categoryId = :to WHERE categoryId = :from")
    abstract suspend fun moveTxns(from: Long, to: Long)

    @Query("UPDATE merchant_rules SET categoryId = :to WHERE categoryId = :from")
    abstract suspend fun moveRules(from: Long, to: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    abstract suspend fun deleteCategory(id: Long)

    @Transaction
    open suspend fun mergeCategories(from: Long, into: Long) {
        moveTxns(from, into)
        moveRules(from, into)
        deleteCategory(from)
    }

    @Query("DELETE FROM transactions WHERE source = 'SMS'")
    abstract suspend fun deleteSmsTxns()

    @Query("DELETE FROM raw_sms")
    abstract suspend fun deleteRaw()

    @Transaction
    open suspend fun wipeSmsData() {
        deleteSmsTxns()
        deleteRaw()
    }
}
