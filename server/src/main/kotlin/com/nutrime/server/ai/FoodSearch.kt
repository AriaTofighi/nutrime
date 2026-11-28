package com.nutrime.server.ai

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodQuality
import com.nutrime.app.model.FoodRows
import com.nutrime.app.model.FoodSearchEngine
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.FoodUsage
import com.nutrime.app.model.Fuzzy
import com.nutrime.app.model.SearchRank
import com.nutrime.app.model.Serving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Collections

/** How well a result matches its query: every word and most of the name, every word, or only some words. */
enum class MatchQuality { EXACT, CLOSE, WEAK }

/**
 * What one search found, which words of the query no result matched (often a brand we don't have),
 * how well each food matches, and how sure the search is (high: an exact match near the top; low: close
 * matches, or foods with most words; none: nothing better than that).
 */
data class FoodSearchResult(
    val foods: List<Food>,
    val missingWords: List<String>,
    val onlineFailed: Boolean = false,
    val warnings: List<String> = emptyList(),
    val retryAfterSeconds: Int? = null,
    val matches: Map<String, MatchQuality> = emptyMap(),
    val confidence: String = if (foods.isEmpty()) "none" else "low",
    /** The live providers did not answer within [FoodSearch.ONLINE_TIMEOUT_MS]. */
    val onlineTimedOut: Boolean = false,
    val correctedQuery: String? = null,
)

/**
 * Food lookup for the assistant, through [FoodSearchEngine] like the app's search: the user's own foods,
 * the bundled database (NutriMe and USDA) and the stored catalog first, ranked with the user's diary.
 * The live providers (USDA online, Open Food Facts) are asked only when no food has every word.
 */
class FoodSearch(foodsDbPath: String?, private val online: Boolean, private val catalog: com.nutrime.server.food.FoodCatalog) {
    private val log = LoggerFactory.getLogger(FoodSearch::class.java)

    /** Read-only connections to foods.db, borrowed one per query, so parallel searches don't wait on each other. */
    private val pool: java.util.concurrent.BlockingQueue<Connection>? = foodsDbPath?.let { path ->
        if (!File(path).exists()) {
            log.warn("Food database not found at {}; the assistant will search online only", path)
            null
        } else {
            runCatching {
                java.util.concurrent.ArrayBlockingQueue<Connection>(POOL_SIZE).also { q ->
                    repeat(POOL_SIZE) { q.add(DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro")) }
                }
            }.onFailure { log.warn("Could not open food database {}", path, it) }.getOrNull()
        }
    }

    private fun <T> withConnection(default: T, block: (Connection) -> T): T {
        val q = pool ?: return default
        val conn = q.take()
        try { return block(conn) } finally { q.put(conn) }
    }

    /** Foods seen in online results, so the model can log them by id afterwards. */
    private val cache: MutableMap<String, Food> = Collections.synchronizedMap(object : LinkedHashMap<String, Food>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Food>) = size > 5000
    })

    /** Bundled candidates by query, for [CACHE_MS]: the assistant often searches the same foods. */
    private class Cached(val at: Long, val value: FoodSearchEngine.Candidates)
    private val candidateCache: MutableMap<String, Cached> = Collections.synchronizedMap(object : LinkedHashMap<String, Cached>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > 2000
    })

    suspend fun search(data: UserData, query: String, limit: Int = 10): List<Food> = searchDetailed(data, query, limit).foods

    /**
     * Own foods, the bundled database and the stored catalog, ranked like the app ranks them (usage from the
     * diary, favourites, one row per product). Online sources are asked only when no food has every word
     * of the query, which is usually a brand ("kirkland greek yogurt"), and are given [ONLINE_TIMEOUT_MS].
     */
    suspend fun searchDetailed(data: UserData, query: String, limit: Int = 10, country: String? = null, today: String? = null): FoodSearchResult {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return FoodSearchResult(emptyList(), emptyList(), confidence = "none")
        val text = tokens.joinToString(" ")
        val own = data.foods.map { it.toFood() }
            .filter { matched(tokens, it) >= minOf(tokens.size, maxOf(1, tokens.size - 1)) }
        val local = bundledCandidates(text)
        val stored = catalog.stored(query, CANDIDATES / 2)
        stored.forEach { cache[it.id] = it }
        val variantTokens = (listOf(tokens) + local.variants.map { tokens(it) }.filter { it.isNotEmpty() }).distinct()
        fun complete(f: Food) = variantTokens.any { vt -> matchedTokens(vt, f).size == vt.size }
        val weak = (own + local.foods + stored).none(::complete)
        val onlineResult = if (!online || !weak) null else withTimeoutOrNull(ONLINE_TIMEOUT_MS) { catalog.search(query) }
        val timedOut = online && weak && onlineResult == null
        onlineResult?.foods?.forEach { cache[it.id] = it }
        val usage = usage(data, today)
        val favorites = data.profile.favoriteFoodIds.toSet()
        val ranked = FoodSearchEngine.rank(
            (own + local.foods + stored + onlineResult?.foods.orEmpty()).distinctBy { it.id }, text, usage, null, favorites,
            country, local.corrected, aliases, local.variants,
        ).take(limit)
        val matches = ranked.associate { f -> f.id to quality(variantTokens, f, f.id in local.corrected) }
        val confidence = when {
            ranked.take(3).any { matches[it.id] == MatchQuality.EXACT } -> "high"
            ranked.any { matches[it.id] != MatchQuality.WEAK || 2 * matchedTokens(tokens, it).size > tokens.size } -> "low"
            else -> "none"
        }
        val missing = tokens.filter { t -> ranked.none { f -> t in matchedTokens(listOf(t), f) } }
        return FoodSearchResult(
            ranked, missing, onlineResult?.errors?.isNotEmpty() == true, onlineResult?.errors.orEmpty(), onlineResult?.retryAfterSeconds,
            matches, confidence, timedOut, local.correctedQuery,
        )
    }

    /** Exact: every word, and the query covers most of the name. Close: every word (or a typing correction). Weak: some words. */
    private fun quality(variantTokens: List<List<String>>, f: Food, corrected: Boolean): MatchQuality {
        if (corrected) return MatchQuality.CLOSE
        val matchedVariant = variantTokens.firstOrNull { vt -> matchedTokens(vt, f).size == vt.size } ?: return MatchQuality.WEAK
        val qw = FoodQuality.words(matchedVariant.joinToString(" "))
        return if (SearchRank.nameFit(qw, f) >= EXACT_FIT) MatchQuality.EXACT else MatchQuality.CLOSE
    }

    /** How the user logged each food in the last 180 days (the app's [FoodUsage]), from the synced diary. */
    private fun usage(data: UserData, today: String?): Map<String, FoodUsage> {
        val day = today?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val entries = data.diary.mapNotNull { e ->
            val d = runCatching { LocalDate.parse(e.date).toEpochDay() }.getOrNull() ?: return@mapNotNull null
            if (e.foodId.startsWith("quick:")) null
            else FoodSearchEngine.UsageEntry(e.foodId, d, e.meal, e.servingLabel, e.servingGrams, e.quantity)
        }
        return FoodSearchEngine.usage(entries, day.toEpochDay())
    }

    private suspend fun bundledCandidates(text: String): FoodSearchEngine.Candidates {
        val now = System.currentTimeMillis()
        candidateCache[text]?.takeIf { now - it.at < CACHE_MS }?.let { return it.value }
        val c = withContext(Dispatchers.IO) { FoodSearchEngine.candidates(rows, text, CANDIDATES) }
        candidateCache[text] = Cached(now, c)
        return c
    }

    /** Hidden bundled duplicate id → the food shown in its place (foods.db `food_alias`). */
    private val aliases: Map<String, String> by lazy {
        withConnection(emptyMap()) { conn ->
            if (!hasTable(conn, "food_alias")) emptyMap()
            else conn.createStatement().use { st ->
                st.executeQuery("SELECT old_id, new_id FROM food_alias").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
        }
    }

    private fun hasTable(conn: Connection, name: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { st ->
            st.setString(1, name); st.executeQuery().use { it.next() }
        }

    /**
     * The bundled rows for [FoodSearchEngine]: foods with every word, then foods missing one word
     * ("yukon gold potatoes" also finds "gold potatoes"). Non-restaurant foods and restaurant foods are
     * queried separately so Restaurant Foods never starve plain curated or USDA candidates.
     */
    private val rows = object : FoodRows {
        override suspend fun match(tokens: List<String>, limit: Int): FoodRows.Match {
            val words = tokens.filter { it.isNotBlank() }.take(MAX_WORDS)
            if (words.isEmpty()) return FoodRows.Match(emptyList())
            val groups = listOf(words) + if (words.size > 1) words.indices.map { skip -> words.filterIndexed { i, _ -> i != skip } } else emptyList()
            val matchExpr = groups.joinToString(" OR ") { g -> "(" + g.joinToString(" ") { FoodSearchEngine.ftsTerm(it) } + ")" }
            val hits = words.joinToString(" + ") {
                "(instr(LOWER(f.name || ' ' || COALESCE(f.brand, '') || ' ' || COALESCE(f.keywords, '')), ?) > 0 OR " +
                    "instr(LOWER(f.name || ' ' || COALESCE(f.brand, '') || ' ' || COALESCE(f.keywords, '')), ?) > 0)"
            }
            val first = words.first()
            val firstSing = FoodQuality.singular(first)
            fun queryPart(conn: Connection, categoryFilter: String, preferRaw: Boolean): List<Food> {
                val rawOrder = if (preferRaw) "(LOWER(f.name) LIKE '%raw%') DESC, " else ""
                val sql = """
                    SELECT $COLUMNS, $hits AS hits
                    FROM food f JOIN food_fts t ON t.docid = f.id
                    WHERE food_fts MATCH ?$shownOnly AND $categoryFilter
                    ORDER BY hits DESC, (f.source = 'nutrime') DESC,
                      (LOWER(f.name) LIKE ? || '%' OR LOWER(f.name) LIKE ? || '%') DESC,
                      ${rawOrder}LENGTH(f.name)
                    LIMIT ?
                """.trimIndent()
                return conn.prepareStatement(sql).use { st ->
                    var i = 1
                    words.forEach { w -> st.setString(i++, w); st.setString(i++, FoodQuality.singular(w)) }
                    st.setString(i++, matchExpr)
                    st.setString(i++, first)
                    st.setString(i++, firstSing)
                    st.setInt(i, limit)
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(bundledRow(rs)) } }
                }
            }
            val (nonRest, rest) = withConnection(emptyList<Food>() to emptyList<Food>()) { conn ->
                val nr = queryPart(conn, "COALESCE(f.category, '') NOT IN ('Restaurant Foods', 'Fast Foods')", true)
                val r = queryPart(conn, "f.category IN ('Restaurant Foods', 'Fast Foods')", false)
                nr to r
            }
            // The SQL count is a substring test; word matching decides which foods have every word.
            val (nrAll, nrSome) = nonRest.partition { matchedTokens(words, it).size == words.size }
            val (rAll, rSome) = rest.partition { matchedTokens(words, it).size == words.size }
            fun merge(primary: List<Food>, secondary: List<Food>, cap: Int): List<Food> {
                if (cap <= 0) return emptyList()
                val secReserve = minOf(secondary.size, cap / 4)
                val priTake = minOf(primary.size, cap - secReserve)
                val secTake = minOf(secondary.size, cap - priTake)
                return primary.take(priTake) + secondary.take(secTake)
            }
            val completeFoods = merge(nrAll, rAll, limit)
            val partialFoods = merge(nrSome, rSome, limit - completeFoods.size)
            return FoodRows.Match(completeFoods + partialFoods, completeFoods.size)
        }

        override suspend fun synonyms(phrase: String): List<String> = withConnection(emptyList()) { conn ->
            if (!hasSyn) emptyList()
            else conn.prepareStatement("SELECT same FROM food_syn WHERE word = ?").use { st ->
                st.setString(1, phrase)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

        override suspend fun hasPrefix(word: String): Boolean = withConnection(true) { conn ->
            if (!hasVocab) true
            else conn.prepareStatement("SELECT 1 FROM food_vocab WHERE word >= ? AND word < ? LIMIT 1").use { st ->
                st.setString(1, word); st.setString(2, word + "\uffff")
                st.executeQuery().use { it.next() }
            }
        }

        override suspend fun nearWords(word: String): List<Pair<String, Int>> = withConnection(emptyList()) { conn ->
            if (!hasVocab) return@withConnection emptyList()
            val max = Fuzzy.maxDistance(word)
            conn.prepareStatement("SELECT word, n FROM food_vocab WHERE word >= ? AND word < ? AND length(word) BETWEEN ? AND ?").use { st ->
                st.setString(1, word.substring(0, 1)); st.setString(2, word.substring(0, 1) + "\uffff")
                st.setInt(3, word.length - max); st.setInt(4, word.length + max)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) } }
            }
        }
    }

    private val hasSyn: Boolean by lazy { withConnection(false) { hasTable(it, "food_syn") } }
    private val hasVocab: Boolean by lazy { withConnection(false) { hasTable(it, "food_vocab") } }

    suspend fun get(data: UserData, id: String): Food? {
        data.food(id)?.let { return it.toFood() }
        cache[id]?.let { return it }
        bundledById(id)?.let { return it }
        return catalog.get(id, online).foods.firstOrNull()?.also { cache[it.id] = it }
    }

    /** True for foods in the bundled database (USDA and NutriMe curated), which every device already has. */
    suspend fun isBundled(id: String): Boolean = bundledById(id) != null

    private fun tokens(q: String) = FoodSearchEngine.tokens(q)

    /** Query words found in the food's name, brand, or aliases, matching word prefixes and plurals. */
    private fun matchedTokens(tokens: List<String>, f: Food): List<String> {
        val kw = f.keywords ?: keywords[f.id].orEmpty()
        val words = "${f.name} ${f.brand.orEmpty()} $kw".lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return tokens.filter { t -> words.any { w -> FoodQuality.wordMatches(t, w) } }
    }

    private fun matched(tokens: List<String>, f: Food): Double = matchedTokens(tokens, f).size.toDouble() / tokens.size

    /** " AND f.hidden = 0" so hidden duplicates stay out of search, or "" for an older db without the column. */
    private val shownOnly: String by lazy {
        withConnection(false) { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(food)").use { rs -> var has = false; while (rs.next()) if (rs.getString(2) == "hidden") has = true; has }
            }
        }.let { if (it) " AND f.hidden = 0" else "" }
    }

    /** A bundled food by app id: "usda:<fdcId>" or "nm:<key>", hidden or not. */
    private suspend fun bundledById(id: String): Food? = withContext(Dispatchers.IO) {
        if (pool == null) return@withContext null
        val (where, arg) = when {
            id.startsWith("usda:") -> "f.id = ? AND f.ref IS NULL" to (id.removePrefix("usda:").toLongOrNull() ?: return@withContext null).toString()
            id.startsWith("nm:") -> "f.ref = ?" to id
            else -> return@withContext null
        }
        withConnection(null) { conn ->
            conn.prepareStatement("SELECT $COLUMNS FROM food f WHERE $where").use { st ->
                st.setString(1, arg)
                st.executeQuery().use { rs -> if (rs.next()) bundledRow(rs) else null }
            }
        }
    }

    /** Aliases of curated foods, by id, so they count as matched words. */
    private val keywords: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    @Serializable
    private data class Portion(val l: String, val g: Double)

    private fun bundledRow(rs: java.sql.ResultSet): Food {
        val portions = recordJson.decodeFromString(ListSerializer(Portion.serializer()), rs.getString(6))
        val id = rs.getString(7) ?: "usda:${rs.getLong(1)}"
        val kw = rs.getString(10)?.also { keywords[id] = it }
        return Food(
            id = id,
            source = if (rs.getString(4) == "nutrime") FoodSource.NUTRIME else FoodSource.USDA,
            name = rs.getString(2),
            brand = rs.getString(8),
            category = rs.getString(3),
            nutrients = decodeNutrients(rs.getString(5)),
            servings = portions.map { Serving(it.l, it.g) },
            barcode = rs.getString(9),
            keywords = kw,
        )
    }

    companion object {
        private const val POOL_SIZE = 4
        /** Bundled candidates per query, before ranking. */
        private const val CANDIDATES = 60
        private const val MAX_WORDS = 8
        const val CACHE_MS = 10 * 60_000L
        const val ONLINE_TIMEOUT_MS = 3_000L
        /** The share of the name the query must cover for an exact match ([SearchRank.nameFit]). */
        private const val EXACT_FIT = 0.5
        private const val COLUMNS = "f.id, f.name, f.category, f.source, f.nutrients, f.portions, f.ref, f.brand, f.barcode, f.keywords"
        /** Foods with at least this many nutrients come from an analysis, not just a label. */
        const val FULL_NUTRIENTS = 15
        private val STOP_WORDS = setOf("and", "with", "in", "of", "the", "a", "an", "or", "to")
        fun canonicalize(list: List<JsonObject>) = com.nutrime.server.food.FoodProviders.canonicalize(list)
    }
}

fun FoodRecord.toFood() = Food(
    id = id,
    source = when (kind) {
        "custom" -> FoodSource.CUSTOM
        "recipe" -> FoodSource.RECIPE
        "off" -> FoodSource.OPEN_FOOD_FACTS
        "usda_branded" -> FoodSource.USDA_BRANDED
        else -> FoodSource.USDA
    },
    name = name,
    brand = brand,
    nutrients = decodeNutrients(nutrientsJson),
    servings = decodeServings(servingsJson),
    barcode = barcode,
)
