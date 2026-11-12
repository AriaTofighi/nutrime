package com.nutrime.app.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Open Food Facts values converted to NutriMe units, per 100 g. */
object OpenFoodFactsNutrients {
    /** OFF key -> (canonical id, multiplier from OFF's base unit (g) to app unit). */
    private val MAP = listOf(
        "proteins" to (N.PROTEIN to 1.0),
        "fat" to (N.FAT to 1.0),
        "carbohydrates" to (N.CARBS to 1.0),
        "fiber" to (N.FIBER to 1.0),
        "sugars" to (N.SUGARS to 1.0),
        "added-sugars" to (N.ADDED_SUGARS to 1.0),
        "saturated-fat" to (N.SAT_FAT to 1.0),
        "monounsaturated-fat" to (N.MONO_FAT to 1.0),
        "polyunsaturated-fat" to (N.POLY_FAT to 1.0),
        "trans-fat" to (N.TRANS_FAT to 1.0),
        "cholesterol" to (N.CHOLESTEROL to 1000.0),
        "alpha-linolenic-acid" to (N.OMEGA3_ALA to 1.0),
        "eicosapentaenoic-acid" to (N.EPA to 1.0),
        "docosahexaenoic-acid" to (N.DHA to 1.0),
        "linoleic-acid" to (N.OMEGA6_LA to 1.0),
        "alcohol" to (N.ALCOHOL to 1.0),
        "caffeine" to (N.CAFFEINE to 1000.0),
        "sodium" to (N.SODIUM to 1000.0),
        "potassium" to (N.POTASSIUM to 1000.0),
        "calcium" to (N.CALCIUM to 1000.0),
        "iron" to (N.IRON to 1000.0),
        "magnesium" to (N.MAGNESIUM to 1000.0),
        "phosphorus" to (N.PHOSPHORUS to 1000.0),
        "zinc" to (N.ZINC to 1000.0),
        "copper" to (N.COPPER to 1000.0),
        "manganese" to (N.MANGANESE to 1000.0),
        "selenium" to (N.SELENIUM to 1_000_000.0),
        "vitamin-a" to (N.VIT_A to 1_000_000.0),
        "vitamin-c" to (N.VIT_C to 1000.0),
        "vitamin-d" to (N.VIT_D to 1_000_000.0),
        "vitamin-e" to (N.VIT_E to 1000.0),
        "vitamin-k" to (N.VIT_K to 1_000_000.0),
        "vitamin-b1" to (N.THIAMIN to 1000.0),
        "vitamin-b2" to (N.RIBOFLAVIN to 1000.0),
        "vitamin-pp" to (N.NIACIN to 1000.0),
        "pantothenic-acid" to (N.PANTOTHENIC to 1000.0),
        "vitamin-b6" to (N.B6 to 1000.0),
        "vitamin-b9" to (N.FOLATE to 1_000_000.0),
        "folates" to (N.FOLATE to 1_000_000.0),
        "vitamin-b12" to (N.B12 to 1_000_000.0),
        "choline" to (N.CHOLINE to 1000.0),
    )

    fun parseNutriments(nm: JsonObject): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        val kcal = nm["energy-kcal_100g"].num() ?: nm["energy_100g"].num()?.let { kj -> kj / 4.184 }
        if (kcal != null) out[N.ENERGY] = kcal
        MAP.forEach { (key, target) ->
            val v = nm["${key}_100g"].num() ?: return@forEach
            if (!out.containsKey(target.first)) out[target.first] = v * target.second
        }
        if (!out.containsKey(N.SODIUM)) nm["salt_100g"].num()?.let { out[N.SODIUM] = it * 1000.0 / 2.5 }
        return out
    }
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let {
        it.doubleOrNull ?: it.content.toDoubleOrNull()
    }
}
