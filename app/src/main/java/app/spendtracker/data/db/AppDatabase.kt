package app.spendtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.spendtracker.data.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        TransactionEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        MerchantRuleEntity::class,
        RawSmsEntity::class,
        BillEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun rules(): MerchantRuleDao
    abstract fun rawSms(): RawSmsDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun bills(): BillDao

    companion object {
        const val NAME = "spend.db"

        /** v2 adds the bills table. Written by hand to match what Room generates for [BillEntity]. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `biller` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `minDuePaise` INTEGER, `dueDate` INTEGER NOT NULL, " +
                        "`last4` TEXT, `status` TEXT NOT NULL, `sourceText` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `paidTxnId` INTEGER)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bills_dedupeKey` ON `bills` (`dedupeKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_dueDate` ON `bills` (`dueDate`)")
            }
        }

        /** Room on top of SQLCipher. The passphrase is random, generated once and wrapped by Android Keystore. */
        fun build(context: Context, keys: DatabaseKeyManager): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = keys.passphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
