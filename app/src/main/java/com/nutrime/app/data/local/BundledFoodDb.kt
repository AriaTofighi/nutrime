package com.nutrime.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
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

    suspend fun search(query: String, limit: Int = 60): List<Food> = searchDetailed(query, limit).foods

    /** Results, the ids found only through a typing correction, and the corrected query. */
    data class Results(val foods: List<Food>, val corrected: Set<String> = emptySet(), val correctedQuery: String? = null)

    /**
     * Search with synonyms ("yoghurt" also finds "yogurt") and, when few foods match, typing-error
     * correction against the database's word list ("chiken" → "chicken").
     */
    suspend fun searchDetailed(query: String, limit: Int = 60): Results = withContext(Dispatchers.IO) {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return@withContext Results(emptyList())
        val db = open()
        val found = LinkedHashMap<String, Food>()
        (listOf(tokens) + synonymVariants(db, tokens)).forEach { v -> match(v, limit).forEach { found.putIfAbsent(it.id, it) } }
        if (found.size >= 5) return@withContext Results(found.values.take(limit))
        // Few results: correct words that match nothing in the word list.
        var changed = false
        val fixed = tokens.map { t ->
            if (t.length < 4 || hasPrefix(db, t)) t
            else Fuzzy.corrections(t, candidates(db, t)).firstOrNull()?.also { changed = true } ?: t
        }
        if (!changed) return@withContext Results(found.values.take(limit))
        val corrected = LinkedHashSet<String>()
        (listOf(fixed) + synonymVariants(db, fixed)).forEach { v ->
            match(v, limit).forEach { f -> if (found.putIfAbsent(f.id, f) == null) corrected += f.id }
        }
        Results(found.values.take(limit), corrected, fixed.joinToString(" ").takeIf { corrected.isNotEmpty() })
    }

    private fun tokens(q: String) = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    /** The query with one word or two-word phrase replaced by a synonym (at most 4 variants). */
    private fun synonymVariants(db: SQLiteDatabase, tokens: List<String>): List<List<String>> {
        if (!hasTable(db, "food_syn")) return emptyList()
        val out = ArrayList<List<String>>()
        for (n in 1..2) for (i in 0..tokens.size - n) {
            val phrase = tokens.subList(i, i + n).joinToString(" ")
            db.rawQuery("SELECT same FROM food_syn WHERE word = ?", arrayOf(phrase)).use { c ->
                while (c.moveToNext()) out += tokens.subList(0, i) + tokens(c.getString(0)) + tokens.subList(i + n, tokens.size)
            }
        }
        return out.distinct().take(4)
    }

    private fun hasPrefix(db: SQLiteDatabase, t: String): Boolean {
        if (!hasTable(db, "food_vocab")) return true
        return db.rawQuery("SELECT 1 FROM food_vocab WHERE word >= ? AND word < ? LIMIT 1", arrayOf(t, t + "\uffff")).use { it.moveToFirst() }
    }

    private fun candidates(db: SQLiteDatabase, t: String): List<Pair<String, Int>> {
        val max = Fuzzy.maxDistance(t)
        return db.rawQuery(
            "SELECT word, n FROM food_vocab WHERE word >= ? AND word < ? AND length(word) BETWEEN ? AND ?",
            arrayOf(t.substring(0, 1), t.substring(0, 1) + "\uffff", (t.length - max).toString(), (t.length + max).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
    }

    private suspend fun match(tokens: List<String>, limit: Int): List<Food> {
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { "$it*" }
        val first = tokens.first()
        val sql = """
            SELECT $COLUMNS
            FROM food f JOIN food_fts t ON t.docid = f.id
            WHERE food_fts MATCH ?
            ORDER BY
              (f.source = 'nutrime') DESC,
              (LOWER(f.name) LIKE ? || '%') DESC,
              (LOWER(f.name) LIKE '%raw%') DESC,
              LENGTH(f.name)
            LIMIT ?
        """
        return open().rawQuery(sql, arrayOf(match, first, limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }
    }

    /** A bundled food by app id ("usda:<fdcId>" or "nm:<key>"), or null if it isn't bundled. */
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
        )
    }

    @kotlinx.serialization.Serializable
    private data class Portion(val l: String, val g: Double)

    companion object {
        /** Bump when assets/foods.db is regenerated so devices re-copy it. */
        const val ASSET_VERSION = 4

        private const val COLUMNS = "f.id, f.name, f.category, f.source, f.nutrients, f.portions, f.ref, f.brand, f.barcode"
    }
}
