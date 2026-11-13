package com.nutrime.app.model

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** How the user has logged one food in the last 180 days. */
data class FoodUsage(
    val foodId: String,
    val count: Int,
    /** Days since the last log. */
    val daysSince: Double,
    /** Logs at each meal (breakfast, lunch, dinner, snacks). */
    val byMeal: List<Int>,
    val lastServingLabel: String? = null,
    val lastServingGrams: Double? = null,
    val lastQuantity: Double? = null,
) {
    /** 0..1: frequent and recent foods score high. */
    val score: Double get() = min(1.0, ln(1.0 + count) / ln(31.0)) * 0.5.pow(daysSince / 45.0)

    fun mealShare(meal: Int): Double = if (count == 0) 0.0 else (byMeal.getOrNull(meal) ?: 0).toDouble() / count
}

/**
 * One ranking for every search result: how well the name matches (and how much of the name the query
 * covers), how often the user logs the food (and at this meal), favourites, data quality and country,
 * less for restaurant items the query did not ask for and products without a brand.
 * Copied in web/src/lib/searchRank.ts; shared/testdata/search_rank.json checks both.
 */
object SearchRank {
    /** Weight of data quality ([FoodQuality.score]) in the ranking. */
    const val QUALITY_WEIGHT = 0.2

    private fun singular(w: String) = FoodQuality.singular(w)
    private fun words(s: String) = FoodQuality.words(s)

    /** The same word, ignoring a plural ("tenderloins" = "tenderloin"). */
    fun sameWord(t: String, w: String): Boolean = singular(t) == singular(w)

    /** Descriptors that plain foods carry ("raw", "cooked", "no added fat"): not counted unless the query has them. */
    private val NEUTRAL = setOf(
        "raw", "fresh", "plain", "whole", "nfs", "ns", "cooked", "boiled", "unsalted", "without", "salt", "no", "added", "fat",
        "prepared", "unprepared", "regular",
    )

    private val BRACKETS = Regex("\\([^)]*\\)")

    /** The name without details in brackets: "Salmon (Atlantic, farmed), raw" → "Salmon, raw". */
    private fun withoutBrackets(name: String) = name.replace(BRACKETS, " ")

    /** Name words that count for the fit, each once: brand words, details in brackets and neutral descriptors are left out. */
    private fun fitWords(name: String, brand: String?, q: List<String>): List<String> {
        val b = words(brand.orEmpty()).map { singular(it) }.toSet()
        val all = words(withoutBrackets(name)).map { singular(it) }.distinct()
        val kept = all.filter { w -> w !in b && (w !in NEUTRAL || q.any { t -> sameWord(t, w) }) }
        return kept.ifEmpty { all }
    }

    /** 0..1: share of the name's words that are query words. "Chicken tenderloin" → 1; "Cracker Barrel, chicken tenderloin platter, fried" → 2/6. */
    fun nameFit(q: List<String>, food: Food): Double {
        val ws = fitWords(food.name, food.brand, q)
        if (q.isEmpty() || ws.isEmpty()) return 0.0
        return ws.count { w -> q.any { t -> sameWord(t, w) } }.toDouble() / ws.size
    }

    /** Query words found as whole words (not only as the start of a longer word: "egg" in "eggplant"). */
    fun wholeWords(q: List<String>, food: Food): Int {
        val ws = words("${food.name} ${food.brand.orEmpty()}")
        return q.count { t -> ws.any { w -> sameWord(t, w) } }
    }

    /** The same words as the query, ignoring case, plurals, punctuation and neutral words: "Chicken Tenderloins" = "chicken tenderloin". */
    private fun sameWords(name: String, query: String): Boolean {
        val y = words(query).map { singular(it) }
        val x = words(withoutBrackets(name)).map { singular(it) }.filter { it !in NEUTRAL || it in y }
        return x.isNotEmpty() && x == y
    }

    private val RESTAURANT_CATEGORIES = setOf("Restaurant Foods", "Fast Foods")
    private val RESTAURANT_NAME = Regex("\\b(fast ?foods?|restaurant)", RegexOption.IGNORE_CASE)

    /** A restaurant or fast-food item: the USDA categories, or a name that says so ("…, from fast food"). */
    fun isRestaurant(food: Food): Boolean = food.category in RESTAURANT_CATEGORIES || RESTAURANT_NAME.containsMatchIn(food.name)

    /** True when the query names the restaurant: a word of a SHOUTED leading brand ("CRACKER BARREL, …") or of the brand, or "restaurant"/"fast". */
    private fun asksForRestaurant(q: List<String>, food: Food): Boolean {
        val lead = food.name.substringBefore(",")
        val part = (if (FoodNames.isShouted(lead)) words(lead) else emptyList()) + words(food.brand.orEmpty()) + listOf("restaurant", "fast")
        return q.any { t -> part.any { w -> FoodQuality.wordMatches(t, w) } }
    }

    /**
     * Penalties that are not about the words: restaurant items the query did not ask for, and products
     * without a brand. Foods the user has logged get none (the caller leaves them out).
     */
    fun penalties(q: List<String>, food: Food): Double =
        (if (q.isNotEmpty() && isRestaurant(food) && !asksForRestaurant(q, food)) 0.20 else 0.0) +
            (if (food.source == FoodSource.OPEN_FOOD_FACTS && food.brand.isNullOrBlank()) 0.10 else 0.0)

    /** Name match: share of query words found, the first word leading, the same words, whole words, and the fit. */
    fun textScore(queryWords: List<String>, food: Food, query: String): Double {
        if (queryWords.isEmpty()) return 0.0
        var s = FoodQuality.matchedWords(queryWords, food).toDouble() / queryWords.size
        val first = words(food.name).firstOrNull()
        if (first != null && (first.startsWith(queryWords.first()) || singular(first) == singular(queryWords.first()))) s += 0.10
        if (sameWords(food.name, query)) s += 0.20
        return s + 0.15 * wholeWords(queryWords, food) / queryWords.size + 0.25 * nameFit(queryWords, food)
    }

    /**
     * The name rules without usage, for callers with their own word matching (the assistant's search):
     * fit, same words, whole words, and the penalties.
     */
    fun nameAdjustments(queryWords: List<String>, food: Food, query: String): Double {
        if (queryWords.isEmpty()) return 0.0
        return textScore(queryWords, food, query) - FoodQuality.matchedWords(queryWords, food).toDouble() / queryWords.size -
            penalties(queryWords, food)
    }

    fun score(
        food: Food, queryWords: List<String>, query: String, usage: FoodUsage?, meal: Int?,
        favorite: Boolean, country: String?, corrected: Boolean,
    ): Double {
        val u = usage?.score ?: 0.0
        val share = if (meal != null && usage != null) usage.mealShare(meal) else 0.0
        return textScore(queryWords, food, query) +
            0.35 * u + 0.15 * share * u +
            QUALITY_WEIGHT * FoodQuality.score(food) +
            (if (favorite) 0.15 else 0.0) +
            (if (country != null && country in food.countries) 0.08 else 0.0) -
            (if (corrected) 0.25 else 0.0) -
            (if (usage != null) 0.0 else penalties(queryWords, food))
    }

    fun rank(
        foods: List<Food>, query: String, usage: Map<String, FoodUsage>, meal: Int?, favorites: Set<String>,
        country: String? = null, corrected: Set<String> = emptySet(),
    ): List<Food> {
        val q = FoodQuality.words(query)
        return foods.distinctBy { it.id }.withIndex()
            .map { (i, f) -> Triple(i, f, score(f, q, query, usage[f.id], meal, f.id in favorites, country, f.id in corrected)) }
            .sortedWith(compareByDescending<Triple<Int, Food, Double>> { it.third }.thenBy { it.first })
            .map { it.second }
    }

    private val LOCAL_SOURCES = setOf(FoodSource.CUSTOM, FoodSource.RECIPE, FoodSource.NUTRIME)
    private fun sameEnergy(a: Double, b: Double) = kotlin.math.abs(a - b) <= 0.05 * maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
    private fun productKey(f: Food) =
        words(FoodNames.display(f.name, f.brand)).joinToString(" ") { singular(it) } + "|" + words(f.brand.orEmpty()).joinToString(" ")

    /**
     * One row per product: foods in rank order with the same cleaned name and brand and energy within 5%
     * are one product. The row kept (at the place of the best-ranked one) is the user's own or used food,
     * then a favourite, then the one with more nutrients, with a serving, sold in the user's country.
     * Custom, recipe and NutriMe foods are never merged, and a food the user logged or made a favourite
     * is never hidden.
     */
    fun collapse(ranked: List<Food>, used: Set<String>, favorites: Set<String>, country: String? = null): List<Food> {
        fun kept(f: Food) = f.id in used || f.id in favorites
        class Group(val key: String, val kcal: Double, val members: MutableList<Food>)
        val groups = ArrayList<Group>()
        for (f in ranked) {
            val kcal = f.nutrients[N.ENERGY] ?: 0.0
            if (f.source in LOCAL_SOURCES) { groups += Group("", kcal, mutableListOf(f)); continue }
            val key = productKey(f)
            val g = groups.firstOrNull { it.key == key && sameEnergy(it.kcal, kcal) && !(kept(f) && it.members.any(::kept)) }
            if (g != null) g.members += f else groups += Group(key, kcal, mutableListOf(f))
        }
        fun tie(f: Food) = listOf(
            if (f.id in used) 1 else 0, if (f.id in favorites) 1 else 0, f.nutrients.size,
            if (f.servings.isNotEmpty()) 1 else 0, if (country != null && country in f.countries) 1 else 0,
        )
        return groups.map { g ->
            var best = g.members.first()
            for (f in g.members.drop(1)) {
                val a = tie(f)
                val b = tie(best)
                val i = a.indices.firstOrNull { a[it] != b[it] }
                if (i != null && a[i] > b[i]) best = f
            }
            best
        }
    }

    /**
     * Open Food Facts as a fallback: the main list has every other source plus Open Food Facts products the
     * user has logged or made a favourite; the rest go under "More results". The order inside each is kept.
     */
    fun splitMoreResults(ranked: List<Food>, used: Set<String>, favorites: Set<String>): Pair<List<Food>, List<Food>> =
        ranked.partition { it.source != FoodSource.OPEN_FOOD_FACTS || it.id in used || it.id in favorites }

    /** Foods for the empty search screen: usual for this meal, best first. */
    fun usualFor(meal: Int, usage: Collection<FoodUsage>, limit: Int = 6): List<FoodUsage> =
        usage.filter { it.mealShare(meal) > 0 }
            .sortedByDescending { it.mealShare(meal) * it.score }
            .take(limit)
}

/** Typing-error tolerance for food search. */
object Fuzzy {
    /** Damerau-Levenshtein (optimal string alignment) distance. */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    fun maxDistance(word: String): Int = if (word.length >= 8) 2 else 1

    /**
     * Up to [limit] corrections for [word] from a vocabulary of (word, food count) pairs: same first
     * letter, within [maxDistance], most common first.
     */
    fun corrections(word: String, vocab: List<Pair<String, Int>>, limit: Int = 3): List<String> {
        if (word.length < 4) return emptyList()
        val max = maxDistance(word)
        return vocab.asSequence()
            .filter { (w, _) -> w.isNotEmpty() && w[0] == word[0] && kotlin.math.abs(w.length - word.length) <= max && w != word }
            .map { (w, n) -> Triple(w, n, distance(word, w)) }
            .filter { it.third <= max }
            .sortedWith(compareBy<Triple<String, Int, Int>> { it.third }.thenByDescending { it.second })
            .take(limit).map { it.first }.toList()
    }
}

/** An amount typed with a food: "200g chicken", "2 eggs", "1.5 cup rice", "½ avocado". */
data class QueryAmount(val query: String, val amount: Double, val unit: String?) {
    /** Grams per unit for weight units; null for counts and volume words. */
    val gramsPerUnit: Double?
        get() = when (unit) {
            "g" -> 1.0; "kg" -> 1000.0; "oz" -> 28.3495; "lb" -> 453.592; "ml" -> 1.0; "l" -> 1000.0
            else -> null
        }

    val label: String get() = (if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, amount).trimEnd('0').trimEnd('.')) + (unit?.let { " $it" } ?: "")

    /** The serving and quantity to open the food with. */
    fun servingFor(food: Food): Pair<Serving, Double> {
        gramsPerUnit?.let { return Serving("g", 1.0) to amount * it }
        if (unit != null) {
            food.servings.firstOrNull { it.label.lowercase().contains(unit) }?.let { return it to amount }
            return food.defaultServing to food.defaultQuantity
        }
        val s = food.servings.firstOrNull() ?: return Serving("g", 1.0) to 100.0 * amount
        return s to amount
    }

    companion object {
        private val UNITS = mapOf(
            "g" to "g", "gr" to "g", "gram" to "g", "grams" to "g", "kg" to "kg", "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
            "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb", "ml" to "ml", "l" to "l",
            "cup" to "cup", "cups" to "cup", "tbsp" to "tbsp", "tablespoon" to "tbsp", "tablespoons" to "tbsp",
            "tsp" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp",
        )
        private val FRACTIONS = mapOf("½" to 0.5, "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0 / 3, "⅔" to 2.0 / 3)
        private const val NUM = """(\d+(?:[.,]\d+)?|\d+/\d+|[½¼¾⅓⅔])"""
        private val UNIT_RE = UNITS.keys.sortedByDescending { it.length }.joinToString("|")
        private val LEAD = Regex("""^$NUM\s*(?:($UNIT_RE)\b\.?|x\b|×)?\s*(?:of\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        private val TRAIL = Regex("""^(.+?)\s+$NUM\s*($UNIT_RE)?\.?$""", RegexOption.IGNORE_CASE)

        private fun num(s: String): Double? = FRACTIONS[s] ?: if (s.contains('/')) {
            s.split('/').let { (a, b) -> a.toDoubleOrNull()?.let { x -> b.toDoubleOrNull()?.takeIf { it != 0.0 }?.let { x / it } } }
        } else s.replace(',', '.').toDoubleOrNull()

        fun parse(input: String): QueryAmount? {
            val s = input.trim()
            LEAD.matchEntire(s)?.let { m ->
                val n = num(m.groupValues[1]) ?: return@let
                val unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.lowercase()?.let { UNITS[it] }
                val rest = m.groupValues[3].trim()
                if (rest.isEmpty() || rest.first().isDigit() || n <= 0 || n > 5000) return@let
                return QueryAmount(rest, n, unit)
            }
            TRAIL.matchEntire(s)?.let { m ->
                val n = num(m.groupValues[2]) ?: return@let
                val unit = m.groupValues[3].takeIf { it.isNotEmpty() }?.lowercase()?.let { UNITS[it] }
                // A bare trailing number is often part of the name ("milk 2%", "coke zero 330") — require a unit.
                if (unit == null || n <= 0 || n > 5000) return@let
                return QueryAmount(m.groupValues[1].trim(), n, unit)
            }
            return null
        }
    }
}
