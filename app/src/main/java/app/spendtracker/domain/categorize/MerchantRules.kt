package app.spendtracker.domain.categorize

import app.spendtracker.domain.model.CategoryType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsed merchant_rules.json. Categories are referenced by their stable key ("food", "income", ...). */
data class MerchantRulesFile(
    val categories: List<CategorySeed>,
    val rules: List<RuleSeed>,
    val bodyHints: List<HintSeed>,
    val incomeHints: List<String>,
) {
    data class CategorySeed(val key: String, val name: String, val icon: String, val color: String, val type: CategoryType)

    /** [isBrand] patterns are proper names and also become the merchant's display name. */
    data class RuleSeed(val categoryKey: String, val pattern: String, val isBrand: Boolean, val priority: Int)

    data class HintSeed(val categoryKey: String, val keywords: List<String>)

    companion object {
        fun parse(json: String): MerchantRulesFile {
            val root = Json.parseToJsonElement(json).jsonObject
            val categories = root.array("categories").map { el ->
                val o = el.jsonObject
                CategorySeed(
                    key = o.str("key")!!,
                    name = o.str("name")!!,
                    icon = o.str("icon") ?: "help",
                    color = o.str("color") ?: "#A0A4A8",
                    type = CategoryType.entries.firstOrNull { it.name == o.str("type") } ?: CategoryType.EXPENSE,
                )
            }
            val rules = root.array("rules").flatMap { el ->
                val o = el.jsonObject
                val cat = o.str("category")!!
                val brands = o.array("merchants").map { RuleSeed(cat, it.jsonPrimitive.content, true, 10) }
                val keywords = o.array("keywords").map { RuleSeed(cat, it.jsonPrimitive.content, false, 0) }
                brands + keywords
            }
            val hints = root.array("bodyHints").map { el ->
                val o = el.jsonObject
                HintSeed(o.str("category")!!, o.array("keywords").map { it.jsonPrimitive.content })
            }
            val income = root.array("incomeHints").map { it.jsonPrimitive.content }
            return MerchantRulesFile(categories, rules, hints, income)
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
        private fun JsonObject.array(k: String): JsonArray = this[k] as? JsonArray ?: JsonArray(emptyList())
    }
}
