package com.nutrime.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.FoodRows
import com.nutrime.app.model.FoodSearchEngine
import com.nutrime.app.model.Fuzzy
import com.nutrime.app.model.Serving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Read-only food database shipped in assets/foods.db: USDA SR Legacy, Foundation and FNDDS foods
 * ("usda:<fdcId>") and NutriMe's curated foods ("nm:<key>"). See tools/update_food_db.py.
 * Duplicates are hidden (`food.hidden`): search leaves them out, [get] still finds them, and
 * [aliases] names the food shown in place of each.
 */
class BundledFoodDb(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    private suspend fun open(): SQLiteDatabase = mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            val file = File(context.noBackupFilesDir, "foods-$ASSET_VERSION.db")
            if (!file.exists()) {
                context.noBackupFilesDir.listFiles { f -> f.name.startsWith("foods-") }?.forEach { it.delete() }
                val tmp = File(file.path + ".tmp")
                context.assets.open("foods.db").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    /**
     * Up to [limit] candidates for [com.nutrime.app.model.SearchRank], which orders them. The SQL order only
     * decides which candidates are kept when more match, so the limit leaves room for short, plain names.
     */
    suspend fun search(query: String, limit: Int = CANDIDATES): List<Food> = searchDetailed(query, limit).foods

    /** Results, the ids found only through a typing correction, the corrected query, and all searched query variants. */
    data class Results(
        val foods: List<Food>,
        val corrected: Set<String> = emptySet(),
        val correctedQuery: String? = null,
        val variants: List<String> = emptyList(),
    )

    /**
     * Search with synonyms ("yoghurt" also finds "yogurt") and, when few foods match, typing-error
     * correction against the database's word list ("chiken" → "chicken"). See [FoodSearchEngine.candidates].
     */
    suspend fun searchDetailed(query: String, limit: Int = CANDIDATES): Results = withContext(Dispatchers.IO) {
        val c = FoodSearchEngine.candidates(rows, query, limit)
        Results(c.foods, c.corrected, c.correctedQuery, c.variants)
    }

    /** The bundled rows for [FoodSearchEngine]: every query word must match (no partial matches). */
    private val rows = object : FoodRows {
        override suspend fun match(tokens: List<String>, limit: Int) = FoodRows.Match(this@BundledFoodDb.match(tokens, limit))

        override suspend fun synonyms(phrase: String): List<String> {
            val db = open()
            if (!hasTable(db, "food_syn")) return emptyList()
            return db.rawQuery("SELECT same FROM food_syn WHERE word = ?", arrayOf(phrase)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        }

        override suspend fun hasPrefix(word: String): Boolean = this@BundledFoodDb.hasPrefix(open(), word)

        override suspend fun nearWords(word: String): List<Pair<String, Int>> = this@BundledFoodDb.candidates(open(), word)
    }

    /** Hidden duplicate id → the food shown in its place (foods.db `food_alias`; empty for an older db). */
    suspend fun aliases(): Map<String, String> {
        aliasCache?.let { return it }
        return withContext(Dispatchers.IO) {
            val db = open()
            val map = if (!hasTable(db, "food_alias")) emptyMap() else db.rawQuery("SELECT old_id, new_id FROM food_alias", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
            map.also { aliasCache = it }
        }
    }

    @Volatile private var aliasCache: Map<String, String>? = null
    @Volatile private var hasHidden: Boolean? = null

    /** " AND f.hidden = 0", or "" for an older db without the column. */
    private fun shownOnly(db: SQLiteDatabase): String {
        val has = hasHidden ?: db.rawQuery("PRAGMA table_info(food)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "hidden") found = true
            found
        }.also { hasHidden = it }
        return if (has) " AND f.hidden = 0" else ""
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun hasPrefix(db: SQLiteDatabase, t: String): Boolean {
        if (!hasTable(db, "food_vocab")) return true
        return db.rawQuery("SELECT 1 FROM food_vocab WHERE word >= ? AND word < ? LIMIT 1", arrayOf(t, t + "\uffff")).use { it.moveToFirst() }
    }

    private fun candidates(db: SQLiteDatabase, t: String): List<Pair<String, Int>> {
        if (!hasTable(db, "food_vocab")) return emptyList()
        val max = Fuzzy.maxDistance(t)
        return db.rawQuery(
            "SELECT word, n FROM food_vocab WHERE word >= ? AND word < ? AND length(word) BETWEEN ? AND ?",
            arrayOf(t.substring(0, 1), t.substring(0, 1) + "\uffff", (t.length - max).toString(), (t.length + max).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
    }

    private suspend fun match(tokens: List<String>, limit: Int): List<Food> {
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { FoodSearchEngine.ftsTerm(it) }
        val first = tokens.first()
        val firstSing = com.nutrime.app.model.FoodQuality.singular(first)
        val db = open()
        val shown = shownOnly(db)
        val nonRestSql = """
            SELECT $COLUMNS
            FROM food f JOIN food_fts t ON t.docid = f.id
            WHERE food_fts MATCH ?$shown AND COALESCE(f.category, '') NOT IN ('Restaurant Foods', 'Fast Foods')
            ORDER BY
              (f.source = 'nutrime') DESC,
              (LOWER(f.name) LIKE ? || '%' OR LOWER(f.name) LIKE ? || '%') DESC,
              (LOWER(f.name) LIKE '%raw%') DESC,
              LENGTH(f.name)
            LIMIT ?
        """
        val restSql = """
            SELECT $COLUMNS
            FROM food f JOIN food_fts t ON t.docid = f.id
            WHERE food_fts MATCH ?$shown AND f.category IN ('Restaurant Foods', 'Fast Foods')
            ORDER BY
              (f.source = 'nutrime') DESC,
              (LOWER(f.name) LIKE ? || '%' OR LOWER(f.name) LIKE ? || '%') DESC,
              LENGTH(f.name)
            LIMIT ?
        """
        val args = arrayOf(match, first, firstSing, limit.toString())
        val nonRest = db.rawQuery(nonRestSql, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
        val rest = db.rawQuery(restSql, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
        val restReserve = minOf(rest.size, limit / 4)
        val nonRestTake = minOf(nonRest.size, limit - restReserve)
        val restTake = minOf(rest.size, limit - nonRestTake)
        return nonRest.take(nonRestTake) + rest.take(restTake)
    }

    /** A bundled food by app id ("usda:<fdcId>" or "nm:<key>"), hidden or not, or null if it isn't bundled. */
    suspend fun get(id: String): Food? = withContext(Dispatchers.IO) {
        val (where, arg) = when {
            id.startsWith("usda:") -> "f.id = ? AND f.ref IS NULL" to (id.removePrefix("usda:").toLongOrNull() ?: return@withContext null).toString()
            id.startsWith("nm:") -> "f.ref = ?" to id
            else -> return@withContext null
        }
        open().rawQuery("SELECT $COLUMNS FROM food f WHERE $where", arrayOf(arg))
            .use { c -> if (c.moveToFirst()) row(c) else null }
    }

    private fun row(c: android.database.Cursor): Food {
        val nutrients: Map<Int, Double> = json.decodeFromString(c.getString(4))
        val portions = json.decodeFromString<List<Portion>>(c.getString(5))
        val ref = c.getString(6)
        return Food(
            id = ref ?: "usda:${c.getLong(0)}",
            source = if (c.getString(3) == "nutrime") FoodSource.NUTRIME else FoodSource.USDA,
            name = c.getString(1),
            brand = c.getString(7),
            category = c.getString(2),
            nutrients = nutrients,
            servings = portions.map { Serving(it.l, it.g) },
            barcode = c.getString(8),
            keywords = c.getString(9),
        )
    }

    @kotlinx.serialization.Serializable
    private data class Portion(val l: String, val g: Double)

    companion object {
        /** Bump when assets/foods.db is regenerated so devices re-copy it. */
        const val ASSET_VERSION = 11

        /** Candidates per search (also in web/src/lib/foods.ts). */
        const val CANDIDATES = 100

        private const val COLUMNS = "f.id, f.name, f.category, f.source, f.nutrients, f.portions, f.ref, f.brand, f.barcode, f.keywords"
    }
}
