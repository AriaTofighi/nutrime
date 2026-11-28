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
        /** All searched query variants (original, synonym variants, corrected query) for ranking. */
        val variants: List<String> = emptyList(),
    )

    /** Fewer foods with every word than this, and words not in the word list are corrected. */
    const val FEW = 5

    fun tokens(q: String): List<String> {
        val all = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val kept = all.filter { it !in FoodQuality.STOP_WORDS }
        return kept.ifEmpty { all }
    }

    /** FTS4 match term for a single token: singular prefix when safe, or common stem / OR for -ies plurals. */
    fun ftsTerm(t: String): String {
        val s = FoodQuality.singular(t)
        if (s == t) return "$t*"
        if (t.startsWith(s)) return "$s*"
        return "($t* OR $s*)"
    }

    /** The query with one word or two-word phrase replaced by a synonym (at most 4 variants). */
    suspend fun synonymVariants(rows: FoodRows, tokens: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (n in 1..2) for (i in 0..tokens.size - n) {
            val sub = tokens.subList(i, i + n)
            val phrase = sub.joinToString(" ")
            var syns = rows.synonyms(phrase)
            if (syns.isEmpty()) {
                val sing = sub.joinToString(" ") { FoodQuality.singular(it) }
                if (sing != phrase) syns = rows.synonyms(sing)
            }
            syns.forEach { same -> out += tokens.subList(0, i) + tokens(same) + tokens.subList(i + n, tokens.size) }
        }
        return out.distinct().filter { it != tokens }.take(4)
    }

    private fun mergeBalanced(lists: List<List<Food>>, cap: Int, seen: MutableSet<String>): List<Food> {
        if (cap <= 0 || lists.isEmpty()) return emptyList()
        val out = ArrayList<Food>(cap)
        var idx = 0
        while (out.size < cap) {
            var any = false
            for (list in lists) {
                if (idx < list.size) {
                    any = true
                    val f = list[idx]
                    if (seen.add(f.id)) {
                        out += f
                        if (out.size == cap) return out
                    }
                }
            }
            if (!any) break
            idx++
        }
        return out
    }

    /**
     * Up to [limit] candidates for the query, its synonym variants and, when few foods have every word,
     * the query with typing errors corrected against the word list ("chiken" → "chicken").
     */
    suspend fun candidates(rows: FoodRows, query: String, limit: Int): Candidates {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return Candidates(emptyList())
        val completeIds = LinkedHashSet<String>()
        val completeLists = ArrayList<List<Food>>()
        val partialLists = ArrayList<List<Food>>()
        val searchedVariants = LinkedHashSet<String>()
        val seenBeforeCorrection = HashSet<String>()
        suspend fun collect(variants: List<List<String>>, trackNew: MutableSet<String>? = null) {
            variants.forEach { v ->
                searchedVariants += v.joinToString(" ")
                val m = rows.match(v, limit)
                val comp = m.foods.take(m.complete)
                val part = m.foods.drop(m.complete)
                if (comp.isNotEmpty()) completeLists += comp
                if (part.isNotEmpty()) partialLists += part
                comp.forEach { completeIds += it.id }
                if (trackNew != null) {
                    m.foods.forEach { if (it.id !in seenBeforeCorrection) trackNew += it.id }
                } else {
                    m.foods.forEach { seenBeforeCorrection += it.id }
                }
            }
        }
        fun merge(): List<Food> {
            val seen = HashSet<String>()
            val top = mergeBalanced(completeLists, limit, seen)
            return if (top.size >= limit) top else top + mergeBalanced(partialLists, limit - top.size, seen)
        }
        collect(listOf(tokens) + synonymVariants(rows, tokens))
        if (completeIds.size >= FEW) return Candidates(merge(), complete = completeIds.size, variants = searchedVariants.toList())
        var changed = false
        val fixed = tokens.map { t ->
            val st = FoodQuality.singular(t)
            if (t.length < 4 || rows.hasPrefix(t) || (st != t && rows.hasPrefix(st))) t
            else Fuzzy.corrections(t, rows.nearWords(t)).firstOrNull()?.also { changed = true } ?: t
        }
        if (!changed) return Candidates(merge(), complete = completeIds.size, variants = searchedVariants.toList())
        val corrected = LinkedHashSet<String>()
        collect(listOf(fixed) + synonymVariants(rows, fixed), corrected)
        val merged = merge()
        val keptCorrected = corrected.intersect(merged.map { it.id }.toSet())
        return Candidates(
            merged,
            keptCorrected,
            fixed.joinToString(" ").takeIf { keptCorrected.isNotEmpty() },
            completeIds.size,
            searchedVariants.toList(),
        )
    }

    /**
     * Ranked results, one row per product. [usage] and [favorites] may name hidden bundled duplicates:
     * [aliases] (old id → shown id) moves them to the food shown in their place.
     */
    fun rank(
        foods: List<Food>, query: String, usage: Map<String, FoodUsage>, meal: Int?, favorites: Set<String>,
        country: String? = null, corrected: Set<String> = emptySet(), aliases: Map<String, String> = emptyMap(),
        variants: List<String> = emptyList(),
    ): List<Food> {
        val u = SearchRank.withAliases(usage, aliases)
        val favs = SearchRank.withAliases(favorites, aliases)
        return SearchRank.collapse(SearchRank.rank(foods, query, u, meal, favs, country, corrected, variants), u.keys, favs, country)
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
