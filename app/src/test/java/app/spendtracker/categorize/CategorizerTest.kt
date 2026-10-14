package app.spendtracker.categorize

import app.spendtracker.domain.categorize.BodyHint
import app.spendtracker.domain.categorize.Categorizer
import app.spendtracker.domain.categorize.MatchedBy
import app.spendtracker.domain.categorize.MerchantRulesFile
import app.spendtracker.domain.categorize.Rule
import app.spendtracker.domain.categorize.SpecialCategories
import app.spendtracker.domain.model.RuleSource
import app.spendtracker.domain.model.TxnMode
import app.spendtracker.domain.model.TxnType
import app.spendtracker.domain.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorizerTest {

    private val file = MerchantRulesFile.parse(
        requireNotNull(javaClass.classLoader?.getResource("merchant_rules.json")).readText(),
    )
    // Give each category key a fake id = its index + 1, as the DB would.
    private val ids = file.categories.mapIndexed { i, c -> c.key to (i + 1L) }.toMap()
    private val types = file.categories.associate { it.key to it.type }

    private fun categorizer(extra: List<Rule> = emptyList()): Categorizer {
        val rules = file.rules.mapIndexed { i, r ->
            Rule(i + 1L, r.pattern, ids.getValue(r.categoryKey), types.getValue(r.categoryKey), RuleSource.DEFAULT, r.priority, if (r.isBrand) r.pattern else null)
        } + extra
        val hints = file.bodyHints.map { BodyHint(ids.getValue(it.categoryKey), types.getValue(it.categoryKey), it.keywords) }
        return Categorizer(rules, hints, file.incomeHints, SpecialCategories(ids["cash"], ids["income"], ids["transfers"]))
    }

    @Test
    fun defaultMerchantRules() {
        val c = categorizer()
        assertEquals(ids["food"], c.categorize("Swiggy", "SWIGGY", "", TxnType.DEBIT, TxnMode.UPI).categoryId)
        assertEquals(ids["shopping"], c.categorize("Amazon", "AMAZON", "", TxnType.DEBIT, TxnMode.CARD).categoryId)
        assertEquals(ids["transport"], c.categorize("Uber", "UBER", "", TxnType.DEBIT, TxnMode.UPI).categoryId)
        assertEquals(ids["subscriptions"], c.categorize("Netflix", null, "", TxnType.DEBIT, TxnMode.CARD).categoryId)
    }

    @Test
    fun longerBrandWins() {
        val c = categorizer()
        assertEquals(ids["groceries"], c.categorize("Jiomart", "JIOMART", "", TxnType.DEBIT, TxnMode.UPI).categoryId)
        assertEquals(ids["bills"], c.categorize("Jio", "JIO", "", TxnType.DEBIT, TxnMode.UPI).categoryId)
        assertEquals(ids["groceries"], c.categorize("Swiggy Instamart", null, "", TxnType.DEBIT, TxnMode.UPI).categoryId)
    }

    @Test
    fun tokenMatchingAvoidsFalseHits() {
        assertFalse(Categorizer.matches("Ola", "coca cola"))
        assertTrue(Categorizer.matches("Ola", "ola cabs"))
        assertTrue(Categorizer.matches("BigBasket", "bigbasket"))
        assertTrue(Categorizer.matches("Swiggy", "swiggyinstamart"))
    }

    @Test
    fun userRuleWins() {
        val user = Rule(999, "swiggy", ids.getValue("groceries"), types.getValue("groceries"), RuleSource.USER, 100)
        val r = categorizer(listOf(user)).categorize("Swiggy", "SWIGGY", "", TxnType.DEBIT, TxnMode.UPI)
        assertEquals(ids["groceries"], r.categoryId)
        assertEquals(MatchedBy.USER_RULE, r.matchedBy)
    }

    @Test
    fun keywordAndModeHints() {
        val c = categorizer()
        assertEquals(ids["fuel"], c.categorize("Sri Ganesh Agencies", null, "Spent Rs 1000 on card for fuel", TxnType.DEBIT, TxnMode.CARD).categoryId)
        assertEquals(ids["cash"], c.categorize("Sbin0001234", null, "withdrawn", TxnType.DEBIT, TxnMode.ATM).categoryId)
        assertEquals(ids["income"], c.categorize("Acme Technologies", null, "NEFT credit salary", TxnType.CREDIT, TxnMode.NEFT).categoryId)
        assertEquals(ids["income"], c.categorize("Amazon", null, "Refund of Rs 499 credited", TxnType.CREDIT, TxnMode.UPI).categoryId)
    }

    @Test
    fun unknownIsUncategorised() {
        val r = categorizer().categorize("Ramesh Kumar", null, "", TxnType.DEBIT, TxnMode.UPI)
        assertNull(r.categoryId)
    }

    @Test
    fun indianRupeeFormatting() {
        assertEquals("₹1,23,456", Money.format(12345600))
        assertEquals("₹1,499.50", Money.format(149950))
        assertEquals("₹250", Money.format(25000))
        assertEquals("₹12,34,56,789", Money.format(12345678900L))
        assertEquals(149900L, Money.parseToPaise("1,499.00"))
        assertEquals(12000L, Money.parseToPaise("120.0"))
        assertEquals("₹1.2L", Money.compact(12000000))
    }
}
