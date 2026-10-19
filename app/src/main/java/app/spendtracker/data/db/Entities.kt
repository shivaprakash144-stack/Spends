package app.spendtracker.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.CategoryType
import app.spendtracker.domain.model.ParseStatus
import app.spendtracker.domain.model.RuleSource
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnSource
import app.spendtracker.domain.model.TxnType

/** Amounts are paise (Long) everywhere to avoid rounding errors. */
@Entity(
    tableName = "transactions",
    indices = [
        Index("timestamp"),
        Index("categoryId"),
        Index("accountId"),
        Index("upiRef"),
        Index("merchantName"),
        Index(value = ["smsHash"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val type: TxnType,
    val timestamp: Long,
    val merchantRaw: String?,
    val merchantName: String,
    val categoryId: Long?,
    val accountId: Long?,
    val mode: TxnMode,
    val upiRef: String?,
    val balancePaise: Long?,
    val note: String?,
    val source: TxnSource,
    val smsId: Long?,
    val smsHash: String?,
    val excluded: Boolean = false,
    val isDuplicateOf: Long? = null,
    /** Set when the user picked the category by hand; rule changes then leave it alone. */
    val categoryLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["bankId", "last4", "type"])],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: String,
    val bankName: String,
    val last4: String?,
    val type: AccountType,
    val displayName: String,
    val lastBalancePaise: Long? = null,
    val lastBalanceAt: Long? = null,
    val availableLimitPaise: Long? = null,
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["slug"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable key for built-in categories ("food", "income"); null for user-created ones. */
    val slug: String?,
    val name: String,
    val icon: String,
    /** ARGB colour, e.g. 0xFFE8705A. */
    val colour: Long,
    val type: CategoryType,
    val isDefault: Boolean,
    val sortOrder: Int,
)

@Entity(
    tableName = "merchant_rules",
    indices = [Index("categoryId"), Index(value = ["pattern", "source"])],
)
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Keyword (token match) or "re:<regex>". */
    val pattern: String,
    val categoryId: Long,
    val source: RuleSource,
    val priority: Int,
    val displayName: String? = null,
)

@Entity(
    tableName = "raw_sms",
    indices = [Index("hash"), Index("parseStatus"), Index("receivedAt")],
)
data class RawSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val parseStatus: ParseStatus,
    /** sha-256 of sender + body; the same SMS seen by the live receiver and the inbox scan shares it. */
    val hash: String,
    val androidSmsId: Long? = null,
    val reason: String? = null,
    /** User dismissed it from the Review list. */
    val dismissed: Boolean = false,
)

/** A transaction joined with its category and account for display. */
data class TxnRow(
    @Embedded val t: TransactionEntity,
    @ColumnInfo(name = "catName") val categoryName: String?,
    @ColumnInfo(name = "catIcon") val categoryIcon: String?,
    @ColumnInfo(name = "catColour") val categoryColour: Long?,
    @ColumnInfo(name = "catType") val categoryType: CategoryType?,
    @ColumnInfo(name = "catKey") val categoryKey: String?,
    @ColumnInfo(name = "accName") val accountName: String?,
    @ColumnInfo(name = "accLast4") val accountLast4: String?,
    @ColumnInfo(name = "accType") val accountType: AccountType?,
)

/** A bill / due-date reminder captured from SMS, email or a notification. Dates are epoch days. */
@Entity(
    tableName = "bills",
    indices = [Index(value = ["dedupeKey"], unique = true), Index("dueDate")],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val biller: String,
    /** BillKind name. */
    val kind: String,
    val amountPaise: Long,
    val minDuePaise: Long?,
    val dueDate: Long,
    val last4: String?,
    /** DUE, PAID or DISMISSED. */
    val status: String,
    val sourceText: String,
    val dedupeKey: String,
    val createdAt: Long,
    val paidTxnId: Long?,
)
