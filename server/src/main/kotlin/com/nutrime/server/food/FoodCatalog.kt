package com.nutrime.server.food

import com.nutrime.app.model.Food
import com.nutrime.server.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

@Serializable
data class FoodResults(val foods: List<Food> = emptyList(), val errors: List<String> = emptyList(),
                       val retryAfterSeconds: Int? = null)

/** The single provider gateway, shared by the API and the assistant. */
class FoodCatalog(config: Config, private val db: Database) {
    private val store = CatalogStore(db)
    private val budget = ProviderBudget(db, config.usdaApiKey == "DEMO_KEY")
    private val providers = FoodProviders(config.usdaApiKey, budget)
    private val online = config.foodSearchOnline
    private val log = LoggerFactory.getLogger(FoodCatalog::class.java)

    suspend fun stored(query: String, limit: Int): List<Food> = withContext(Dispatchers.IO) {
        store.search(query, limit)
    }

    suspend fun search(query: String): FoodResults = coroutineScope {
        val q = CatalogStore.normalize(query)
        if (q.isEmpty()) return@coroutineScope FoodResults()
        val usda = async { sourceSearch("usda", q) }
        val off = async { sourceSearch("off", q) }
        combine(usda.await(), off.await())
    }

    /** Whether USDA Branded Foods were imported in bulk (checked at most once a minute). */
    @Volatile private var brandedChecked = 0L
    @Volatile private var brandedImported = false
    private fun brandedImported(): Boolean {
        val now = System.currentTimeMillis()
        if (now - brandedChecked > 60_000) {
            brandedImported = runCatching { BrandedImport.release(db) != null }.getOrDefault(false)
            brandedChecked = now
        }
        return brandedImported
    }

    private suspend fun sourceSearch(source: String, query: String): FoodResults = withContext(Dispatchers.IO) {
        // With USDA Branded Foods imported, USDA searches use the stored catalog only; the live API is left
        // for single foods that are not stored ([get]). Open Food Facts stays live for "More results".
        if (source == "usda" && brandedImported()) return@withContext FoodResults(store.search(query, 60, source))
        val saved = store.search(query, 30, source)
        if (!online || saved.size >= 10) return@withContext FoodResults(saved)
        fetch("$source:search:$query", source, if (source == "off") "off-search" else "usda", saved) {
            if (source == "off") providers.searchOff(query, 25) else providers.searchUsda(query, 30)
        }
    }

    suspend fun get(id: String, allowOnline: Boolean = true): FoodResults = withContext(Dispatchers.IO) {
        store.get(id)?.let { return@withContext FoodResults(listOf(it)) }
        if (!online || !allowOnline) return@withContext FoodResults()
        when {
            id.matches(Regex("off:[0-9]{4,14}")) -> fetch("off:product:${id.substringAfter(':')}", "off", "off-product") {
                listOfNotNull(providers.offByCode(id.substringAfter(':')))
            }
            id.matches(Regex("usda:[0-9]{1,12}")) -> fetch("usda:product:$id", "usda", "usda") {
                listOfNotNull(providers.usdaById(id.substringAfter(':').toLong()))
            }
            else -> FoodResults()
        }
    }

    suspend fun barcode(code: String): FoodResults = withContext(Dispatchers.IO) {
        store.byBarcode(code)?.let { return@withContext FoodResults(listOf(it)) }
        val off = get("off:$code")
        if (off.foods.isNotEmpty()) return@withContext off
        val usda = sourceSearch("usda", code)
        val found = usda.foods.filter { it.barcode?.trimStart('0') == code.trimStart('0') }
        combine(off, usda.copy(foods = found))
    }

    private suspend fun fetch(identity: String, source: String, bucket: String, saved: List<Food> = emptyList(),
                              request: suspend () -> List<Food>): FoodResults {
        val claim = store.claim(identity)
        if (!claim.owner) return FoodResults((claim.foods + saved).distinctBy { it.id },
            listOfNotNull(claim.error), claim.retryAfter)
        return try {
            val foods = request()
            store.complete(claim, foods)
            FoodResults((foods + saved).distinctBy { it.id })
        } catch (e: CancellationException) {
            // Leave the short lease in place: a disconnected caller must not cause a request storm.
            throw e
        } catch (e: Exception) {
            val label = if (source == "off") "Open Food Facts" else "USDA"
            val retry = when (e) {
                is ProviderFailure -> e.retryAfter
                is ProviderHttpFailure -> e.retryAfter
                else -> 60
            }
            val reason = when (e) {
                is ProviderFailure -> "$label: ${e.reason}"
                is ProviderHttpFailure -> "$label is unavailable (HTTP ${e.status}). Saved results are still available."
                else -> "$label could not be reached or its results could not be read. Saved results are still available."
            }
            // Never log URLs, queries, credentials, response bodies or exception messages.
            log.warn("Food provider {} failed ({})", source, e.javaClass.simpleName)
            if (e !is ProviderFailure) budget.pause(bucket, retry)
            store.complete(claim, emptyList(), reason, retry)
            FoodResults(saved, listOf(reason), retry)
        }
    }

    private fun combine(a: FoodResults, b: FoodResults) = FoodResults(
        (a.foods + b.foods).distinctBy { it.id }, a.errors + b.errors,
        listOfNotNull(a.retryAfterSeconds, b.retryAfterSeconds).maxOrNull())
}
