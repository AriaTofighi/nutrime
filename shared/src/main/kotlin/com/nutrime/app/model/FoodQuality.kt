package com.nutrime.app.model

/**
 * How complete and trustworthy a food's nutrient data is, for ranking search results.
 *
 * Lab analyses (USDA SR Legacy, Foundation, FNDDS, and NutriMe foods derived from them) list
 * vitamins and minerals. Package labels (USDA Branded, Open Food Facts) usually list only the
 * macros and a few minerals, so a diary built from them shows gaps that aren't real.
 */
object FoodQuality {
    /** Nutrients that make a food "complete": everything except energy and the label macros. */
    private val LABEL_NUTRIENTS = setOf(
        N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.FIBER, N.SUGARS, N.ADDED_SUGARS, N.SAT_FAT, N.TRANS_FAT,
        N.CHOLESTEROL, N.SODIUM,
    )

    /** Vitamins and minerals listed beyond what a nutrition label prints. */
    fun micronutrients(food: Food): Int = food.nutrients.keys.count { it !in LABEL_NUTRIENTS }

    /** Lab-analysis foods list at least this many micronutrients; label foods list 0–5. */
    const val FULL_MICROS = 12

    fun isComplete(food: Food): Boolean = micronutrients(food) >= FULL_MICROS

    /** 0..1: share of a typical full analysis that the food lists. */
    fun coverage(food: Food): Double = (micronutrients(food).toDouble() / 25.0).coerceIn(0.0, 1.0)

    /** 0..1 trust in the source, before looking at the data itself. */
    fun sourcePrior(food: Food): Double = when (food.source) {
        FoodSource.CUSTOM, FoodSource.RECIPE -> 1.0
        FoodSource.NUTRIME -> 0.95
        FoodSource.USDA -> 0.85
        FoodSource.USDA_BRANDED -> 0.45
        FoodSource.OPEN_FOOD_FACTS -> 0.3
    }

    /**
     * 0..1 quality: mostly how complete the data is, partly the source. An Open Food Facts product
     * with full vitamins and minerals still ranks well; a label-only one ranks below USDA.
     */
    fun score(food: Food): Double = 0.6 * coverage(food) + 0.4 * sourcePrior(food)

    /** Query words that a food's name or brand contains (word prefixes and simple plurals match). */
    fun matchedWords(queryWords: List<String>, food: Food, extra: String = ""): Int {
        val words = words("${food.name} ${food.brand.orEmpty()} $extra")
        return queryWords.count { t -> words.any { w -> w.startsWith(t) || (w.length >= 4 && t.startsWith(w)) } }
    }

    fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in STOP_WORDS }

    private val STOP_WORDS = setOf("and", "with", "in", "of", "the", "a", "an", "or", "to")

    /**
     * Order online results. Relevance comes first (how many query words the name contains); among
     * equally relevant foods, complete data and better sources come first, and products sold in the
     * user's country get a small lift. A label-only product never outranks an equally named USDA food
     * only because it's sold locally, but a local product beats a foreign one of the same quality.
     */
    fun rank(foods: List<Food>, query: String, country: String? = null): List<Food> {
        val q = words(query)
        if (q.isEmpty()) return foods.sortedByDescending { score(it) }
        return foods.withIndex().sortedWith(
            compareByDescending<IndexedValue<Food>> { (_, f) ->
                val relevance = matchedWords(q, f).toDouble() / q.size
                // A name that starts with the query's first word reads as the thing itself ("milk, 2%"),
                // not something that contains it ("chocolate milk").
                val lead = if (words(f.name).firstOrNull()?.startsWith(q.first()) == true) 0.1 else 0.0
                val local = if (country != null && country in f.countries) 0.08 else 0.0
                relevance + lead + 0.3 * score(f) + local
            }.thenBy { it.index },
        ).map { it.value }
    }
}
