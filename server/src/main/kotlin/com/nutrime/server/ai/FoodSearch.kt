package com.nutrime.server.ai

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodQuality
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.SearchRank
import com.nutrime.app.model.Serving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections

/** What one search found, and which words of the query no result matched (often a brand we don't have). */
data class FoodSearchResult(val foods: List<Food>, val missingWords: List<String>, val onlineFailed: Boolean = false, val warnings: List<String> = emptyList(), val retryAfterSeconds: Int? = null)

/** Food lookup for the assistant: the user's own foods, the bundled USDA database, USDA online and Open Food Facts. */
class FoodSearch(foodsDbPath: String?, private val online: Boolean, private val catalog: com.nutrime.server.food.FoodCatalog) {
    private val log = LoggerFactory.getLogger(FoodSearch::class.java)
    private val bundled: Connection? = foodsDbPath?.let { path ->
        if (!File(path).exists()) {
            log.warn("Food database not found at {}; the assistant will search online only", path)
            null
        } else {
            runCatching { DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro") }
                .onFailure { log.warn("Could not open food database {}", path, it) }
                .getOrNull()
        }
    }

    /** Foods seen in online results, so the model can log them by id afterwards. */
    private val cache: MutableMap<String, Food> = Collections.synchronizedMap(object : LinkedHashMap<String, Food>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Food>) = size > 5000
    })

    suspend fun search(data: UserData, query: String, limit: Int = 10): List<Food> = searchDetailed(data, query, limit).foods

    /**
     * Own foods, then the bundled database, then branded foods online, ranked by how many words of the
     * query each food's name and brand contain. Online sources are asked when local results are few or
     * leave a word unmatched, which is usually a brand ("kirkland greek yogurt").
     */
    suspend fun searchDetailed(data: UserData, query: String, limit: Int = 10, country: String? = null): FoodSearchResult {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return FoodSearchResult(emptyList(), emptyList())
        val own = data.foods.map { it.toFood() }
            .filter { matched(tokens, it) >= minOf(tokens.size, maxOf(1, tokens.size - 1)) }
            .sortedBy { it.name.length }
            .take(limit)
        val local = searchBundled(tokens, limit)
        val stored = catalog.stored(query, limit)
        val missingLocally = tokens.filter { t -> (own + local + stored).none { f -> t in matchedTokens(listOf(t), f) } }
        val onlineResult = if (!online || (own.size + local.size + stored.size >= limit && missingLocally.isEmpty())) null
            else catalog.search(query)
        val onlineFoods = stored + onlineResult?.foods.orEmpty()
        onlineFoods.forEach { cache[it.id] = it }
        val ownIds = own.map { it.id }.toSet()
        val queryWords = FoodQuality.words(query)
        // Relevance first. Then own foods, then data quality: NutriMe and USDA analyses (with vitamins and minerals)
        // before label-only products (most Open Food Facts and branded entries), and products sold in the user's country.
        // Stable sort: at equal scores own foods stay first, then curated, then the rest of the bundled database, then online.
        val ranked = (own + local + onlineFoods).distinctBy { it.id }
            .sortedByDescending { f ->
                matched(tokens, f) + (if (f.id in ownIds) 0.5 else if (f.source == FoodSource.NUTRIME) 0.05 else 0.0) +
                    // The app's name rules: how much of the name the query covers, same words, restaurant and brand rules.
                    SearchRank.nameAdjustments(queryWords, f, query) +
                    SearchRank.QUALITY_WEIGHT * FoodQuality.score(f) +
                    (if (country != null && country in f.countries) 0.05 else 0.0)
            }
            .take(limit)
        val missing = tokens.filter { t -> ranked.none { f -> t in matchedTokens(listOf(t), f) } }
        return FoodSearchResult(ranked, missing, onlineResult?.errors?.isNotEmpty() == true, onlineResult?.errors.orEmpty(), onlineResult?.retryAfterSeconds)
    }

    suspend fun get(data: UserData, id: String): Food? {
        data.food(id)?.let { return it.toFood() }
        cache[id]?.let { return it }
        bundledById(id)?.let { return it }
        return catalog.get(id, online).foods.firstOrNull()?.also { cache[it.id] = it }
    }

    /** True for foods in the bundled database (USDA and NutriMe curated), which every device already has. */
    suspend fun isBundled(id: String): Boolean = bundledById(id) != null

    private fun tokens(q: String) = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in STOP_WORDS }

    /** Query words found in the food's name or brand, matching word prefixes and simple plurals. */
    private fun matchedTokens(tokens: List<String>, f: Food): List<String> {
        val words = "${f.name} ${f.brand.orEmpty()} ${keywords[f.id].orEmpty()}".lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return tokens.filter { t -> words.any { w -> w.startsWith(t) || (w.length >= 4 && t.startsWith(w)) } }
    }

    private fun matched(tokens: List<String>, f: Food): Double = matchedTokens(tokens, f).size.toDouble() / tokens.size

    /** All words first; if that finds too little, every combination missing one word ("yukon gold potatoes" → "gold potatoes"). */
    private suspend fun searchBundled(tokens: List<String>, limit: Int): List<Food> {
        val all = queryBundled(tokens, limit)
        if (all.size >= limit || tokens.size < 2) return all
        val partial = tokens.indices.flatMap { skip -> queryBundled(tokens.filterIndexed { i, _ -> i != skip }, limit) }
            .sortedBy { it.name.length }
        return (all + partial).distinctBy { it.id }.take(limit)
    }

    private suspend fun queryBundled(tokens: List<String>, limit: Int): List<Food> = withContext(Dispatchers.IO) {
        val conn = bundled ?: return@withContext emptyList()
        synchronized(conn) {
            conn.prepareStatement(
                """
                SELECT $COLUMNS
                FROM food f JOIN food_fts t ON t.docid = f.id
                WHERE food_fts MATCH ?
                ORDER BY (f.source = 'nutrime') DESC, (LOWER(f.name) LIKE ? || '%') DESC, (LOWER(f.name) LIKE '%raw%') DESC, LENGTH(f.name)
                LIMIT ?
                """.trimIndent()
            ).use { st ->
                st.setString(1, tokens.joinToString(" ") { "$it*" })
                st.setString(2, tokens.first())
                st.setInt(3, limit)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(bundledRow(rs)) } }
            }
        }
    }

    /** A bundled food by app id: "usda:<fdcId>" or "nm:<key>". */
    private suspend fun bundledById(id: String): Food? = withContext(Dispatchers.IO) {
        val conn = bundled ?: return@withContext null
        val (where, arg) = when {
            id.startsWith("usda:") -> "f.id = ? AND f.ref IS NULL" to (id.removePrefix("usda:").toLongOrNull() ?: return@withContext null).toString()
            id.startsWith("nm:") -> "f.ref = ?" to id
            else -> return@withContext null
        }
        synchronized(conn) {
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
        rs.getString(10)?.let { keywords[id] = it }
        return Food(
            id = id,
            source = if (rs.getString(4) == "nutrime") FoodSource.NUTRIME else FoodSource.USDA,
            name = rs.getString(2),
            brand = rs.getString(8),
            category = rs.getString(3),
            nutrients = decodeNutrients(rs.getString(5)),
            servings = portions.map { Serving(it.l, it.g) },
            barcode = rs.getString(9),
        )
    }

    companion object {
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
