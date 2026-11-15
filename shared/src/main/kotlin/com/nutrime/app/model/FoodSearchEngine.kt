package com.nutrime.app.model

/**
 * Where [FoodSearchEngine] gets its rows: the bundled food database on the phone (Android SQLite) or on the
 * server (JDBC). Each platform reads its own copy of foods.db; the engine does the rest the same way.
 */
interface FoodRows {
    /** Foods found for [tokens] (word prefixes), at most [limit]; [Match.complete] of them have every word. */
    suspend fun match(tokens: List<String>, limit: Int): Match

    /** Synonyms of a word or two-word phrase (foods.db `food_syn`: "yoghurt" → "yogurt"). */
    suspend fun synonyms(phrase: String): List<String>

    /** Whether any word in the word list starts with [word]; true when there is no word list. */
    suspend fun hasPrefix(word: String): Boolean

    /** Words of the word list (word, food count) near [word] in length with the same first letter. */
    suspend fun nearWords(word: String): List<Pair<String, Int>>

    data class Match(val foods: List<Food>, val complete: Int = foods.size)
}

/**
 * The food search shared by the app and the server: synonyms, typing-error correction, ranking
 * ([SearchRank.rank]) and one row per product ([SearchRank.collapse]). Bundled duplicates hidden in
 * foods.db count through their aliases. The web app does the same in web/src/lib/foods.ts (searchBundled)
 * and web/src/lib/searchRank.ts (rank, collapse, usageWithAliases).
 */
object FoodSearchEngine {
    data class Candidates(
        val foods: List<Food>,
        /** Ids found only through a typing correction. */
        val corrected: Set<String> = emptySet(),
        val correctedQuery: String? = null,
        /** How many of [foods] have every word of the query (or of a synonym variant). */
        val complete: Int = foods.size,
    )

    /** Fewer foods with every word than this, and words not in the word list are corrected. */
    const val FEW = 5

    fun tokens(q: String): List<String> = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** The query with one word or two-word phrase replaced by a synonym (at most 4 variants). */
    suspend fun synonymVariants(rows: FoodRows, tokens: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (n in 1..2) for (i in 0..tokens.size - n) {
            val phrase = tokens.subList(i, i + n).joinToString(" ")
            rows.synonyms(phrase).forEach { same -> out += tokens.subList(0, i) + tokens(same) + tokens.subList(i + n, tokens.size) }
        }
        return out.distinct().filter { it != tokens }.take(4)
    }

    /**
     * Up to [limit] candidates for the query, its synonym variants and, when few foods have every word,
     * the query with typing errors corrected against the word list ("chiken" → "chicken").
     */
    suspend fun candidates(rows: FoodRows, query: String, limit: Int): Candidates {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return Candidates(emptyList())
        val found = LinkedHashMap<String, Food>()
        var complete = 0
        suspend fun collect(variants: List<List<String>>, onNew: (Food) -> Unit = {}) {
            variants.forEach { v ->
                val m = rows.match(v, limit)
                m.foods.forEachIndexed { i, f ->
                    if (found.putIfAbsent(f.id, f) == null) {
                        if (i < m.complete) complete++
                        onNew(f)
                    }
                }
            }
        }
        collect(listOf(tokens) + synonymVariants(rows, tokens))
        if (complete >= FEW) return Candidates(found.values.take(limit), complete = complete)
        var changed = false
        val fixed = tokens.map { t ->
            if (t.length < 4 || rows.hasPrefix(t)) t
            else Fuzzy.corrections(t, rows.nearWords(t)).firstOrNull()?.also { changed = true } ?: t
        }
        if (!changed) return Candidates(found.values.take(limit), complete = complete)
        val corrected = LinkedHashSet<String>()
        collect(listOf(fixed) + synonymVariants(rows, fixed)) { corrected += it.id }
        return Candidates(found.values.take(limit), corrected, fixed.joinToString(" ").takeIf { corrected.isNotEmpty() }, complete)
    }

    /**
     * Ranked results, one row per product. [usage] and [favorites] may name hidden bundled duplicates:
     * [aliases] (old id → shown id) moves them to the food shown in their place.
     */
    fun rank(
        foods: List<Food>, query: String, usage: Map<String, FoodUsage>, meal: Int?, favorites: Set<String>,
        country: String? = null, corrected: Set<String> = emptySet(), aliases: Map<String, String> = emptyMap(),
    ): List<Food> {
        val u = SearchRank.withAliases(usage, aliases)
        val favs = SearchRank.withAliases(favorites, aliases)
        return SearchRank.collapse(SearchRank.rank(foods, query, u, meal, favs, country, corrected), u.keys, favs, country)
    }

    /** Usage per food from diary entries: (food id, epoch day, meal, serving label, grams, quantity), newest last or any order. */
    fun usage(entries: List<UsageEntry>, today: Long, days: Int = 180): Map<String, FoodUsage> =
        entries.filter { today - it.epochDay in 0 until days }.groupBy { it.foodId }.mapValues { (id, list) ->
            val last = list.maxBy { it.epochDay }
            FoodUsage(
                foodId = id, count = list.size, daysSince = (today - last.epochDay).toDouble(),
                byMeal = (0..3).map { m -> list.count { it.meal == m } },
                lastServingLabel = last.servingLabel, lastServingGrams = last.servingGrams, lastQuantity = last.quantity,
            )
        }

    data class UsageEntry(
        val foodId: String, val epochDay: Long, val meal: Int,
        val servingLabel: String? = null, val servingGrams: Double? = null, val quantity: Double? = null,
    )
}
