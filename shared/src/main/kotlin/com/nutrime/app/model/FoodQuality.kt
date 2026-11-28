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

    val RESTAURANT_CATEGORIES = setOf("Restaurant Foods", "Fast Foods")

    /** 0..1: share of a full lab analysis (0.95 at 25 micros, rising to 1.0 at 32 so SR Legacy beats 29-micro FNDDS). */
    fun coverage(food: Food): Double {
        val m = micronutrients(food).toDouble()
        return if (m <= 25.0) (m / 25.0).coerceIn(0.0, 1.0) * 0.95
        else 0.95 + ((m - 25.0) / 7.0).coerceIn(0.0, 1.0) * 0.05
    }

    /** 0..1 trust in the source, before looking at the data itself. */
    fun sourcePrior(food: Food): Double = when (food.source) {
        FoodSource.CUSTOM, FoodSource.RECIPE -> 1.0
        FoodSource.NUTRIME -> if (food.category in RESTAURANT_CATEGORIES) 0.45 else 0.95
        FoodSource.USDA -> if (food.category in RESTAURANT_CATEGORIES) 0.45 else 0.85
        FoodSource.USDA_BRANDED -> 0.45
        FoodSource.OPEN_FOOD_FACTS -> 0.3
    }

    /**
     * 0..1 quality: mostly how complete the data is, partly the source. An Open Food Facts product
     * with full vitamins and minerals still ranks well; a label-only one ranks below USDA.
     */
    fun score(food: Food): Double = 0.6 * coverage(food) + 0.4 * sourcePrior(food)

    /** Query words that a food's name, brand, or curated aliases contain (word prefixes and simple plurals match). */
    fun matchedWords(queryWords: List<String>, food: Food, extra: String = ""): Int {
        val words = words("${food.name} ${food.brand.orEmpty()} ${food.keywords.orEmpty()} $extra")
        return queryWords.count { t -> words.any { w -> wordMatches(t, w) } }
    }

    private val IE_PLURAL = setOf(
        "cookies", "brownies", "smoothies", "veggies", "calories",
        "fries", "perogies", "pierogies", "smarties", "timmies", "smokies", "krispies",
    )

    /** A simple singular form, kept conservative: "tenderloins" → "tenderloin", "strawberries" → "strawberry", "cookies" → "cookie", "hummus" stays. */
    fun singular(w: String): String {
        if (w.length < 4 || w == "species") return w
        if (w.length >= 5 && w.endsWith("ies") && w !in IE_PLURAL) return w.dropLast(3) + "y"
        if (w.length >= 5 && ES_PLURAL.containsMatchIn(w)) return w.dropLast(2)
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) return w.dropLast(1)
        return w
    }

    private val ES_PLURAL = Regex("(x|z|ch|sh|o)es$")

    /** A query word matches a name word by prefix, either as typed or in singular form. */
    fun wordMatches(t: String, w: String): Boolean {
        if (w.startsWith(t) || (w.length >= 4 && t.startsWith(w))) return true
        val st = singular(t)
        val sw = singular(w)
        return sw.startsWith(st) || (sw.length >= 4 && st.startsWith(sw))
    }

    fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in STOP_WORDS }

    val STOP_WORDS = setOf("and", "with", "in", "of", "the", "a", "an", "or", "to")

    /**
     * Order online results (the server's catalog search) with the same scoring as the app's search,
     * without usage or favourites: name match and fit, data quality, country, restaurant and brand rules.
     */
    fun rank(foods: List<Food>, query: String, country: String? = null): List<Food> {
        if (words(query).isEmpty()) return foods.sortedByDescending { score(it) }
        return SearchRank.rank(foods, query, emptyMap(), null, emptySet(), country)
    }
}
