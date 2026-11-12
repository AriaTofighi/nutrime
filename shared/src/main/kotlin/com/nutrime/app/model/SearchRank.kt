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
 * One ranking for every search result: how well the name matches, how often the user logs the food
 * (and at this meal), favourites, data quality and country. Copied in web/src/lib/searchRank.ts.
 */
object SearchRank {
    fun textScore(queryWords: List<String>, food: Food, query: String): Double {
        if (queryWords.isEmpty()) return 0.0
        var s = FoodQuality.matchedWords(queryWords, food).toDouble() / queryWords.size
        val nameWords = FoodQuality.words(food.name)
        if (nameWords.firstOrNull()?.startsWith(queryWords.first()) == true) s += 0.10
        if (food.name.trim().equals(query.trim(), ignoreCase = true)) s += 0.20
        return s
    }

    fun score(
        food: Food, queryWords: List<String>, query: String, usage: FoodUsage?, meal: Int?,
        favorite: Boolean, country: String?, corrected: Boolean,
    ): Double {
        val u = usage?.score ?: 0.0
        val share = if (meal != null && usage != null) usage.mealShare(meal) else 0.0
        return textScore(queryWords, food, query) +
            0.35 * u + 0.15 * share * u +
            0.30 * FoodQuality.score(food) +
            (if (favorite) 0.15 else 0.0) +
            (if (country != null && country in food.countries) 0.08 else 0.0) -
            (if (corrected) 0.25 else 0.0)
    }

    fun rank(
        foods: List<Food>, query: String, usage: Map<String, FoodUsage>, meal: Int?, favorites: Set<String>,
        country: String? = null, corrected: Set<String> = emptySet(),
    ): List<Food> {
        val q = FoodQuality.words(query)
        return foods.distinctBy { it.id }.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Food>> { (_, f) ->
                score(f, q, query, usage[f.id], meal, f.id in favorites, country, f.id in corrected)
            }.thenBy { it.index })
            .map { it.value }
    }

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
