package app.spendtracker.data.repository

import android.content.Context
import app.spendtracker.data.db.AccountEntity
import app.spendtracker.data.db.AppDatabase
import app.spendtracker.data.db.CategoryEntity
import app.spendtracker.data.db.MerchantRuleEntity
import app.spendtracker.domain.categorize.BodyHint
import app.spendtracker.domain.categorize.Categorizer
import app.spendtracker.domain.categorize.MerchantRulesFile
import app.spendtracker.domain.categorize.Rule
import app.spendtracker.domain.categorize.SpecialCategories
import app.spendtracker.domain.model.AccountType
import app.spendtracker.domain.model.RuleSource
import app.spendtracker.domain.parser.ParserEngine
import app.spendtracker.domain.parser.PatternConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Loads the JSON rule files from assets, seeds the database once and caches the categoriser. */
@Singleton
class RulesSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    private val mutex = Mutex()
    @Volatile private var seeded = false
    @Volatile private var categorizer: Categorizer? = null

    val parser: ParserEngine by lazy {
        ParserEngine(PatternConfig.parse(asset("bank_patterns.json")))
    }

    private val rulesFile: MerchantRulesFile by lazy { MerchantRulesFile.parse(asset("merchant_rules.json")) }

    private fun asset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }

    suspend fun ensureSeeded() {
        if (seeded) return
        mutex.withLock {
            if (seeded) return
            if (db.categories().count() == 0) {
                val cats = rulesFile.categories.mapIndexed { i, c ->
                    CategoryEntity(
                        slug = c.key, name = c.name, icon = c.icon,
                        colour = parseColour(c.color), type = c.type, isDefault = true, sortOrder = i,
                    )
                }
                val ids = db.categories().insertAll(cats)
                val idBySlug = rulesFile.categories.map { it.key }.zip(ids).toMap()
                db.rules().insertAll(
                    rulesFile.rules.mapNotNull { r ->
                        val cid = idBySlug[r.categoryKey] ?: return@mapNotNull null
                        MerchantRuleEntity(
                            pattern = r.pattern, categoryId = cid, source = RuleSource.DEFAULT,
                            priority = r.priority, displayName = if (r.isBrand) r.pattern else null,
                        )
                    },
                )
            }
            if (db.accounts().cashAccount() == null) {
                db.accounts().insert(AccountEntity(bankId = "cash", bankName = "Cash", last4 = null, type = AccountType.CASH, displayName = "Cash"))
            }
            seeded = true
        }
    }

    suspend fun categorizer(): Categorizer {
        categorizer?.let { return it }
        ensureSeeded()
        val cats = db.categories().getAll()
        val byId = cats.associateBy { it.id }
        val bySlug = cats.filter { it.slug != null }.associateBy { it.slug!! }
        val rules = db.rules().getAll().mapNotNull { r ->
            val c = byId[r.categoryId] ?: return@mapNotNull null
            Rule(r.id, r.pattern, r.categoryId, c.type, r.source, r.priority, r.displayName)
        }
        val hints = rulesFile.bodyHints.mapNotNull { h ->
            val c = bySlug[h.categoryKey] ?: return@mapNotNull null
            BodyHint(c.id, c.type, h.keywords)
        }
        val built = Categorizer(
            rules, hints, rulesFile.incomeHints,
            SpecialCategories(bySlug["cash"]?.id, bySlug["income"]?.id, bySlug["transfers"]?.id),
        )
        categorizer = built
        return built
    }

    fun invalidate() {
        categorizer = null
    }

    suspend fun uncategorisedId(): Long? { ensureSeeded(); return db.categories().bySlug("uncategorised")?.id }
    suspend fun transfersId(): Long? { ensureSeeded(); return db.categories().bySlug("transfers")?.id }

    companion object {
        fun parseColour(hex: String): Long {
            val h = hex.removePrefix("#")
            val v = h.toLong(16)
            return if (h.length <= 6) 0xFF000000L or v else v
        }
    }
}
