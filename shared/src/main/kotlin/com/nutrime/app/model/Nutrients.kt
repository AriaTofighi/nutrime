package com.nutrime.app.model

/** Nutrient ids are USDA FoodData Central nutrient ids; all values are stored per 100 g. */
object N {
    const val ENERGY = 1008
    const val PROTEIN = 1003
    const val FAT = 1004
    const val CARBS = 1005
    const val FIBER = 1079
    const val SUGARS = 2000
    const val ADDED_SUGARS = 1235
    const val WATER = 1051
    const val CAFFEINE = 1057
    const val ALCOHOL = 1018
    const val SAT_FAT = 1258
    const val MONO_FAT = 1292
    const val POLY_FAT = 1293
    const val TRANS_FAT = 1257
    const val CHOLESTEROL = 1253
    const val OMEGA3_ALA = 1404
    const val EPA = 1278
    const val DHA = 1272
    const val OMEGA6_LA = 1316
    const val VIT_A = 1106
    const val VIT_C = 1162
    const val VIT_D = 1114
    const val VIT_E = 1109
    const val VIT_K = 1185
    const val THIAMIN = 1165
    const val RIBOFLAVIN = 1166
    const val NIACIN = 1167
    const val PANTOTHENIC = 1170
    const val B6 = 1175
    const val FOLATE = 1190
    const val B12 = 1178
    const val CHOLINE = 1180
    const val CALCIUM = 1087
    const val COPPER = 1098
    const val IRON = 1089
    const val MAGNESIUM = 1090
    const val MANGANESE = 1101
    const val PHOSPHORUS = 1091
    const val POTASSIUM = 1092
    const val SELENIUM = 1103
    const val SODIUM = 1093
    const val ZINC = 1095
}

enum class NutrientGroup(val title: String) {
    GENERAL("General"),
    CARBS("Carbohydrates"),
    LIPIDS("Lipids"),
    PROTEIN("Protein"),
    VITAMINS("Vitamins"),
    MINERALS("Minerals"),
}

data class NutrientDef(
    val id: Int,
    val name: String,
    val unit: String,
    val group: NutrientGroup,
    val shortName: String = name,
)

object Nutrients {
    val all: List<NutrientDef> = listOf(
        NutrientDef(N.ENERGY, "Energy", "kcal", NutrientGroup.GENERAL, "Calories"),
        NutrientDef(N.WATER, "Water", "g", NutrientGroup.GENERAL),
        NutrientDef(N.ALCOHOL, "Alcohol", "g", NutrientGroup.GENERAL),
        NutrientDef(N.CAFFEINE, "Caffeine", "mg", NutrientGroup.GENERAL),

        NutrientDef(N.CARBS, "Carbohydrates", "g", NutrientGroup.CARBS, "Carbs"),
        NutrientDef(N.FIBER, "Fiber", "g", NutrientGroup.CARBS),
        NutrientDef(N.SUGARS, "Sugars", "g", NutrientGroup.CARBS),
        NutrientDef(N.ADDED_SUGARS, "Added Sugars", "g", NutrientGroup.CARBS),

        NutrientDef(N.FAT, "Fat", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.SAT_FAT, "Saturated", "g", NutrientGroup.LIPIDS, "Sat. Fat"),
        NutrientDef(N.MONO_FAT, "Monounsaturated", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.POLY_FAT, "Polyunsaturated", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.OMEGA3_ALA, "Omega-3 (ALA)", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.EPA, "Omega-3 (EPA)", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.DHA, "Omega-3 (DHA)", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.OMEGA6_LA, "Omega-6 (LA)", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.TRANS_FAT, "Trans Fat", "g", NutrientGroup.LIPIDS),
        NutrientDef(N.CHOLESTEROL, "Cholesterol", "mg", NutrientGroup.LIPIDS),

        NutrientDef(N.PROTEIN, "Protein", "g", NutrientGroup.PROTEIN),

        NutrientDef(N.VIT_A, "Vitamin A", "µg", NutrientGroup.VITAMINS),
        NutrientDef(N.THIAMIN, "B1 (Thiamine)", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.RIBOFLAVIN, "B2 (Riboflavin)", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.NIACIN, "B3 (Niacin)", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.PANTOTHENIC, "B5 (Pantothenic Acid)", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.B6, "B6 (Pyridoxine)", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.B12, "B12 (Cobalamin)", "µg", NutrientGroup.VITAMINS),
        NutrientDef(N.FOLATE, "Folate", "µg", NutrientGroup.VITAMINS),
        NutrientDef(N.VIT_C, "Vitamin C", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.VIT_D, "Vitamin D", "µg", NutrientGroup.VITAMINS),
        NutrientDef(N.VIT_E, "Vitamin E", "mg", NutrientGroup.VITAMINS),
        NutrientDef(N.VIT_K, "Vitamin K", "µg", NutrientGroup.VITAMINS),
        NutrientDef(N.CHOLINE, "Choline", "mg", NutrientGroup.VITAMINS),

        NutrientDef(N.CALCIUM, "Calcium", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.COPPER, "Copper", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.IRON, "Iron", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.MAGNESIUM, "Magnesium", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.MANGANESE, "Manganese", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.PHOSPHORUS, "Phosphorus", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.POTASSIUM, "Potassium", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.SELENIUM, "Selenium", "µg", NutrientGroup.MINERALS),
        NutrientDef(N.SODIUM, "Sodium", "mg", NutrientGroup.MINERALS),
        NutrientDef(N.ZINC, "Zinc", "mg", NutrientGroup.MINERALS),
    )

    val byId: Map<Int, NutrientDef> = all.associateBy { it.id }

    /** Nutrients shown in the simple custom-food editor (as printed on a nutrition label). */
    val labelNutrients = listOf(
        N.ENERGY, N.FAT, N.SAT_FAT, N.TRANS_FAT, N.CHOLESTEROL, N.SODIUM, N.CARBS, N.FIBER,
        N.SUGARS, N.ADDED_SUGARS, N.PROTEIN, N.VIT_D, N.CALCIUM, N.IRON, N.POTASSIUM,
    )

    fun format(value: Double, unit: String): String {
        val abs = kotlin.math.abs(value)
        val s = when {
            abs >= 100 -> "%.0f".format(value)
            abs >= 10 -> "%.1f".format(value)
            abs >= 1 -> "%.1f".format(value)
            abs == 0.0 -> "0"
            else -> "%.2f".format(value)
        }
        return "$s $unit"
    }
}

typealias NutrientMap = Map<Int, Double>

operator fun NutrientMap.plus(other: NutrientMap): NutrientMap {
    val out = HashMap<Int, Double>(this)
    other.forEach { (k, v) -> out[k] = (out[k] ?: 0.0) + v }
    return out
}

fun NutrientMap.scaled(factor: Double): NutrientMap = mapValues { it.value * factor }

/** Scale a per-100 g map to an amount in grams. */
fun NutrientMap.forGrams(grams: Double): NutrientMap = scaled(grams / 100.0)

fun sumNutrients(maps: Iterable<NutrientMap>): NutrientMap {
    val out = HashMap<Int, Double>()
    maps.forEach { m -> m.forEach { (k, v) -> out[k] = (out[k] ?: 0.0) + v } }
    return out
}
