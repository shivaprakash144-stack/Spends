package app.spendtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.spendtracker.data.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        TransactionEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        MerchantRuleEntity::class,
        RawSmsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun rules(): MerchantRuleDao
    abstract fun rawSms(): RawSmsDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        const val NAME = "spend.db"

        /** Room on top of SQLCipher. The passphrase is random, generated once and wrapped by Android Keystore. */
        fun build(context: Context, keys: DatabaseKeyManager): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = keys.passphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .build()
        }
    }
}
