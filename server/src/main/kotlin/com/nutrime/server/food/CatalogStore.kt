package com.nutrime.server.food

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.server.ai.recordJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.Locale

/** Only public provider data belongs here. User foods, diaries and account ids are never copied. */
object CatalogFoods : Table("catalog_foods") {
    val id = varchar("id", 200)
    val provider = varchar("provider", 16)
    val searchText = text("search_text")
    val barcode = varchar("barcode", 32).nullable().index()
    val payload = text("payload")
    val fetchedAt = long("fetched_at")
    val sourceUrl = text("source_url")
    val license = varchar("license", 32)
    override val primaryKey = PrimaryKey(id)
    init { index(false, provider, fetchedAt) }
}

/** Successful queries persist indefinitely; empty results and failures have a short lifetime. */
object CatalogQueries : Table("catalog_queries") {
    val key = varchar("key", 64)
    val ids = text("ids").default("[]")
    val expiresAt = long("expires_at").default(0)
    val leaseUntil = long("lease_until").default(0)
    val error = text("error").nullable()
    override val primaryKey = PrimaryKey(key)
}

object ProviderBudgets : Table("provider_budgets") {
    val key = varchar("key", 32)
    val hits = text("hits").default("[]")
    val blockedUntil = long("blocked_until").default(0)
    override val primaryKey = PrimaryKey(key)
}

data class QueryClaim(val key: String, val foods: List<Food>, val owner: Boolean,
                      val error: String? = null, val retryAfter: Int? = null)

class CatalogStore(private val db: Database) {

    fun get(id: String): Food? = transaction(db) {
        CatalogFoods.selectAll().where { CatalogFoods.id eq id }.singleOrNull()?.food()
    }

    fun byBarcode(code: String): Food? = transaction(db) {
        CatalogFoods.selectAll().where { CatalogFoods.barcode eq code.trimStart('0') }.limit(1).singleOrNull()?.food()
    }

    /**
     * Saved foods matching the query. On PostgreSQL, queries the full-text index (`to_tsvector('english', search_text)`)
     * ordered by `ts_rank` (fixing heap sampling, substring false positives like "ice" matching "rice", plurals, and
     * short tokens like "2%"), with a `pg_trgm` fallback when FTS returns few results. On H2 (tests), uses whole-word
     * prefix/singular matching across every non-stop token.
     */
    fun search(query: String, limit: Int, provider: String? = null): List<Food> = transaction(db) {
        val norm = normalize(query)
        val all = norm.split(' ').filter { it.isNotEmpty() }.take(12)
        if (all.isEmpty()) return@transaction emptyList()
        val nonStop = all.filter { it !in com.nutrime.app.model.FoodQuality.STOP_WORDS }.ifEmpty { all }
        if (db.vendor != "postgresql") {
            return@transaction searchH2(nonStop, limit, provider)
        }
        val fts = searchPostgresFts(norm, limit, provider)
        if (fts.size >= minOf(limit, FTS_FALLBACK_MIN)) return@transaction fts
        val seen = fts.mapTo(HashSet()) { it.id }
        val fallback = searchPostgresTrgm(nonStop, limit, provider).filter { it.id !in seen }
        (fts + fallback).take(limit)
    }

    private fun searchH2(tokens: List<String>, limit: Int, provider: String?): List<Food> {
        val sql = buildString {
            append("SELECT payload FROM catalog_foods WHERE ")
            append(tokens.joinToString(" AND ") {
                "((' ' || search_text || ' ') LIKE ? OR (' ' || search_text || ' ') LIKE ?)"
            })
            if (provider != null) append(" AND provider = ?")
            append(" ORDER BY CHAR_LENGTH(search_text) ASC, fetched_at DESC LIMIT ?")
        }
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection.connection as java.sql.Connection
        return conn.prepareStatement(sql).use { st ->
            var idx = 1
            tokens.forEach { t ->
                val s = com.nutrime.app.model.FoodQuality.singular(t)
                st.setString(idx++, "% $t%")
                st.setString(idx++, "% $s%")
            }
            if (provider != null) st.setString(idx++, provider)
            st.setInt(idx, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(recordJson.decodeFromString<Food>(rs.getString(1))) }
            }
        }
    }

    private fun searchPostgresFts(normQuery: String, limit: Int, provider: String?): List<Food> {
        val sql = buildString {
            append("WITH q AS (SELECT plainto_tsquery('english', ?) AS tsq) ")
            append("SELECT c.payload FROM catalog_foods c, q ")
            append("WHERE numnode(q.tsq) > 0 AND to_tsvector('english', c.search_text) @@ q.tsq")
            if (provider != null) append(" AND c.provider = ?")
            append(" ORDER BY ts_rank(to_tsvector('english', c.search_text), q.tsq) DESC, char_length(c.search_text) ASC, c.fetched_at DESC LIMIT ?")
        }
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection.connection as java.sql.Connection
        return conn.prepareStatement(sql).use { st ->
            var idx = 1
            st.setString(idx++, normQuery)
            if (provider != null) st.setString(idx++, provider)
            st.setInt(idx, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(recordJson.decodeFromString<Food>(rs.getString(1))) }
            }
        }
    }

    private fun searchPostgresTrgm(tokens: List<String>, limit: Int, provider: String?): List<Food> {
        val longWords = tokens.filter { it.length >= 3 }
        val shortWords = tokens.filter { it.length < 3 }
        val sql = buildString {
            append("SELECT payload FROM catalog_foods WHERE ")
            val parts = mutableListOf<String>()
            longWords.forEach { _ -> parts += "(search_text LIKE ? AND (' ' || search_text || ' ') LIKE ?)" }
            shortWords.forEach { _ -> parts += "(' ' || search_text || ' ') LIKE ?" }
            append(parts.joinToString(" AND "))
            if (provider != null) append(" AND provider = ?")
            append(" ORDER BY char_length(search_text) ASC, fetched_at DESC LIMIT ?")
        }
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection.connection as java.sql.Connection
        return conn.prepareStatement(sql).use { st ->
            var idx = 1
            longWords.forEach { w ->
                val s = com.nutrime.app.model.FoodQuality.singular(w)
                st.setString(idx++, "%$s%")
                st.setString(idx++, "% $s%")
            }
            shortWords.forEach { w -> st.setString(idx++, "% $w %") }
            if (provider != null) st.setString(idx++, provider)
            st.setInt(idx, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(recordJson.decodeFromString<Food>(rs.getString(1))) }
            }
        }
    }

    /** Row locks and a lease also coalesce concurrent queries from separate server processes. */
    fun claim(identity: String): QueryClaim = transaction(db) {
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        CatalogQueries.insertIgnore { it[CatalogQueries.key] = key }
        val row = CatalogQueries.selectAll().where { CatalogQueries.key eq key }.forUpdate().single()
        val now = System.currentTimeMillis()
        val ids = recordJson.decodeFromString<List<String>>(row[CatalogQueries.ids])
        val foods = if (ids.isEmpty()) emptyList() else {
            val found = CatalogFoods.selectAll().where { CatalogFoods.id inList ids }.associate { it[CatalogFoods.id] to it.food() }
            ids.mapNotNull(found::get)
        }
        when {
            row[CatalogQueries.expiresAt] > now -> QueryClaim(key, foods, false, row[CatalogQueries.error],
                row[CatalogQueries.error]?.let { seconds(row[CatalogQueries.expiresAt] - now) })
            row[CatalogQueries.leaseUntil] > now -> QueryClaim(key, foods, false,
                "This search is already in progress. Try again shortly.", seconds(row[CatalogQueries.leaseUntil] - now))
            else -> {
                CatalogQueries.update({ CatalogQueries.key eq key }) { it[leaseUntil] = now + 60_000 }
                QueryClaim(key, foods, true)
            }
        }
    }

    fun complete(claim: QueryClaim, foods: List<Food>, error: String? = null, retryAfter: Int = 60) = transaction(db) {
        val now = System.currentTimeMillis()
        foods.forEach { food ->
            require(food.source in setOf(FoodSource.USDA, FoodSource.USDA_BRANDED, FoodSource.OPEN_FOOD_FACTS))
            val off = food.source == FoodSource.OPEN_FOOD_FACTS
            CatalogFoods.upsert {
                it[id] = food.id
                it[provider] = if (off) "off" else "usda"
                it[searchText] = normalize("${food.name} ${food.brand.orEmpty()}")
                it[barcode] = food.barcode?.trimStart('0')
                it[payload] = recordJson.encodeToString(food)
                it[fetchedAt] = now
                it[sourceUrl] = if (off) "https://world.openfoodfacts.org/product/${food.barcode}"
                    else "https://fdc.nal.usda.gov/food-details/${food.id.removePrefix("usda:")}/nutrients"
                it[license] = if (off) "ODbL-1.0" else "CC0-1.0"
            }
        }
        CatalogQueries.update({ CatalogQueries.key eq claim.key }) {
            it[ids] = recordJson.encodeToString(foods.map { f -> f.id })
            it[expiresAt] = when {
                error != null -> now + retryAfter.coerceAtLeast(1) * 1000L
                foods.isEmpty() -> now + 24 * 3600_000L
                else -> now + QUERY_TTL_MS
            }
            it[leaseUntil] = 0
            it[CatalogQueries.error] = error
        }
    }

    companion object {
        /** Fall back to pg_trgm when PostgreSQL FTS returns fewer than this many rows. */
        private const val FTS_FALLBACK_MIN = 10
        /** Cached provider query results expire after 60 days so updated catalogs are picked up. */
        const val QUERY_TTL_MS = 60L * 24 * 3600_000L

        fun normalize(query: String) = query.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun seconds(ms: Long) = ((ms.coerceAtLeast(0) + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        private fun ResultRow.food() = recordJson.decodeFromString<Food>(this[CatalogFoods.payload])
    }
}
