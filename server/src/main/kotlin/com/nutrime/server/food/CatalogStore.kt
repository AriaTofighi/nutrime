package com.nutrime.server.food

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.server.ai.recordJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
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

    fun search(query: String, limit: Int, provider: String? = null): List<Food> = transaction(db) {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }.take(12)
        if (words.isEmpty()) return@transaction emptyList()
        val condition = words.map { word -> CatalogFoods.searchText like "%$word%" }.compoundAnd()
        CatalogFoods.selectAll().where { if (provider == null) condition else condition and (CatalogFoods.provider eq provider) }
            .orderBy(CatalogFoods.fetchedAt, SortOrder.DESC).limit(limit).map { it.food() }
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
                else -> Long.MAX_VALUE
            }
            it[leaseUntil] = 0
            it[CatalogQueries.error] = error
        }
    }

    companion object {
        fun normalize(query: String) = query.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun seconds(ms: Long) = ((ms.coerceAtLeast(0) + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        private fun ResultRow.food() = recordJson.decodeFromString<Food>(this[CatalogFoods.payload])
    }
}
