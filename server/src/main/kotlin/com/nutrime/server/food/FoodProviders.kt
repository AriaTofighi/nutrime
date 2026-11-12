package com.nutrime.server.food

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.N
import com.nutrime.app.model.UsdaNutrients
import com.nutrime.app.model.OpenFoodFactsNutrients
import com.nutrime.app.model.Serving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import com.nutrime.server.ai.recordJson

/** Provider requests and parsers; every network attempt must reserve a shared budget. */
class FoodProviders(private val usdaApiKey: String, private val budget: ProviderBudget) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val log = LoggerFactory.getLogger(FoodProviders::class.java)

    private suspend fun usdaGet(path: String): JsonElement {
        val sep = if ('?' in path) '&' else '?'
        return getJson("$USDA$path${sep}api_key=${enc(usdaApiKey)}", "usda")
    }

    private suspend fun getJson(url: String, bucket: String): JsonElement = withContext(Dispatchers.IO) {
        // This is the only send site. Retries and fallback requests must pass the same gate.
        budget.reserve(bucket)
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "NutriMe-Server/1.0 (https://github.com/AriaTofighi/nutrime)")
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        val status = res.statusCode()
        if (status != 200) {
            log.warn("Food provider {} returned HTTP {}", bucket, status)
            val header = res.headers().firstValue("Retry-After").orElse("")
            val retry = header.toLongOrNull()?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt()
                ?: runCatching { CatalogStore.seconds(java.time.ZonedDateTime.parse(header,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()) }.getOrNull()
                ?: if (bucket == "usda" && status == 429) 3600 else 60
            if (status == 429) budget.pause(bucket, retry)
            throw ProviderHttpFailure(status, retry)
        }
        recordJson.parseToJsonElement(res.body())
    }

    suspend fun searchOff(query: String, limit: Int): List<Food> {
        val root = try {
            getJson("https://search.openfoodfacts.org/search?q=${enc(query)}&langs=en&page_size=$limit&fields=$OFF_FIELDS", "off-search") as? JsonObject
                ?: throw IllegalStateException("Open Food Facts returned an invalid result.")
        } catch (e: ProviderHttpFailure) {
            // Never bypass a rate limit. Only an unavailable new endpoint uses the old one.
            if (e.status !in setOf(404, 502, 503, 504)) throw e
            val legacy = getJson("$OFF/cgi/search.pl?search_terms=${enc(query)}&search_simple=1&action=process&json=1&page_size=$limit&fields=$OFF_FIELDS", "off-search") as? JsonObject
                ?: throw IllegalStateException("Open Food Facts returned an invalid result.")
            return products(legacy, "products").mapNotNull { parseOff(it) }
        }
        return products(root, "hits").mapNotNull { parseOff(it) }
    }

    private fun products(root: JsonObject, field: String): JsonArray = root[field] as? JsonArray
        ?: throw IllegalStateException("The provider returned an invalid result.")

    suspend fun offByCode(code: String): Food? {
        if (!code.all { it.isDigit() }) return null
        val root = try {
            getJson("$OFF/api/v2/product/$code.json?fields=$OFF_FIELDS", "off-product") as? JsonObject
        } catch (e: ProviderHttpFailure) { if (e.status == 404) return null else throw e } ?: return null
        if (root["status"].num()?.toInt() != 1) return null
        return parseOff(root["product"])
    }

    /** Normalize both legacy and Search-a-licious products without inventing missing values. */
    private fun parseOff(e: JsonElement?): Food? {
        val p = e as? JsonObject ?: return null
        val code = p["code"].str()?.takeIf { it.matches(Regex("[0-9]{4,14}")) } ?: return null
        // Prefer the English name. A product named only in another language is left out: NutriMe is in English, and
        // such names can't be checked against what the user wrote ("Petits pois et jeunes carottes").
        val lang = p["lang"].str()?.lowercase()
        val english = p["product_name_en"].str()?.trim()?.takeIf { it.isNotEmpty() }
        val main = p["product_name"].str()?.trim()?.takeIf { it.isNotEmpty() && (lang == null || lang == "en") }
        val brand = ((p["brands"] as? JsonArray)?.firstOrNull()?.str() ?: p["brands"].str()?.substringBefore(","))?.trim()?.takeIf { it.isNotEmpty() }
        val name = (english ?: main)?.let { cleanFoodName(it, brand) } ?: return null
        val nm = p["nutriments"] as? JsonObject ?: return null
        val nutrients = OpenFoodFactsNutrients.parseNutriments(nm)
        // Crowd-sourced data has typos; nothing has more energy than pure fat (~900 kcal per 100 g).
        val kcal = nutrients[N.ENERGY] ?: return null
        if (!kcal.isFinite() || kcal < 0 || kcal > 900 || nutrients.values.any { !it.isFinite() || it < 0 }) return null
        val servings = mutableListOf<Serving>()
        val sq = p["serving_quantity"].num()
        if (sq != null && sq.isFinite() && sq > 0 && sq <= 2000) servings += Serving("serving (${p["serving_size"].str()?.takeIf { it.isNotBlank() } ?: "${fmt(sq)} g"})", sq)
        return Food(
            id = "off:$code",
            source = FoodSource.OPEN_FOOD_FACTS,
            name = name,
            brand = brand,
            nutrients = nutrients,
            servings = servings,
            barcode = code,
            countries = (p["countries_tags"] as? JsonArray).orEmpty().mapNotNull { it.str()?.substringAfter(':')?.lowercase() }.take(40),
        )
    }

    suspend fun searchUsda(query: String, limit: Int): List<Food> {
        val root = usdaGet("foods/search?query=${enc(query)}&pageSize=$limit&dataType=${enc("Branded,Survey (FNDDS)")}")
        return products(root as? JsonObject ?: throw IllegalStateException("USDA returned an invalid result."), "foods").mapNotNull { parseUsda(it) }
    }

    suspend fun usdaById(fdc: Long): Food? = try { parseUsda(usdaGet("food/$fdc")) }
        catch (e: ProviderHttpFailure) { if (e.status == 404) null else throw e }

    private fun parseUsda(e: JsonElement): Food? {
        val o = e as? JsonObject ?: return null
        val fdcId = o["fdcId"].long() ?: return null
        val branded = o["dataType"].str() == "Branded"
        val nutrients = canonicalize(o["foodNutrients"].arr().mapNotNull { it as? JsonObject })
        if (nutrients[N.ENERGY] == null || nutrients.values.any { !it.isFinite() || it < 0 } || nutrients.getValue(N.ENERGY) > 900) return null
        val servings = mutableListOf<Serving>()
        val unit = o["servingSizeUnit"].str()?.lowercase()
        val size = o["servingSize"].num()
        if (size != null && size.isFinite() && size > 0 && size <= 2000 && unit in setOf("g", "grm", "ml", "mlt")) {
            val household = o["householdServingFullText"].str()?.takeIf { it.isNotBlank() }
            servings += Serving(if (household != null) "$household (${fmt(size)} g)" else "serving (${fmt(size)} g)", size)
        }
        (o["foodMeasures"].arr() + o["foodPortions"].arr()).mapNotNull { it as? JsonObject }.forEach { m ->
            val g = m["gramWeight"].num() ?: return@forEach
            val text = (m["disseminationText"].str() ?: m["portionDescription"].str())
                ?.takeIf { it.isNotBlank() && !it.startsWith("Quantity not specified") } ?: return@forEach
            if (g.isFinite() && g > 0 && g <= 2000) servings += Serving(text, g)
        }
        val name = o["description"].str()?.let { prettify(it, branded) } ?: return null
        return Food(
            id = "usda:$fdcId",
            source = if (branded) FoodSource.USDA_BRANDED else FoodSource.USDA,
            name = name,
            brand = (o["brandName"].str() ?: o["brandOwner"].str())?.let { prettify(it, true) },
            nutrients = nutrients,
            servings = servings.distinctBy { it.label },
            barcode = o["gtinUpc"].str()?.takeIf { it.matches(Regex("[0-9]{4,14}")) },
            countries = listOfNotNull(o["marketCountry"].str()?.lowercase()?.replace(Regex("[^a-z]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() }),
        )
    }

    companion object {
        private const val USDA = "https://api.nal.usda.gov/fdc/v1/"
        private const val OFF = "https://world.openfoodfacts.org"
        private const val OFF_FIELDS = "code,lang,countries_tags,product_name,product_name_en,brands,nutriments,serving_size,serving_quantity"

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        /** Read either USDA search or detail nutrient fields before applying shared unit conversion. */
        fun canonicalize(list: List<JsonObject>): Map<Int, Double> {
            val raw = HashMap<Int, Pair<Double, String>>()
            list.forEach { n ->
                val nested = n["nutrient"] as? JsonObject
                val id = (n["nutrientId"].long() ?: nested?.get("id").long())?.toInt() ?: return@forEach
                val v = n["value"].num() ?: n["amount"].num() ?: return@forEach
                raw[id] = v to (n["unitName"].str() ?: nested?.get("unitName").str()).orEmpty().uppercase()
            }
            return UsdaNutrients.canonicalize(raw)
        }

        fun fmt(d: Double) = UsdaNutrients.fmt(d)
        private fun prettify(s: String, branded: Boolean) = UsdaNutrients.prettify(s, branded)
    }
}
private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

class ProviderHttpFailure(val status: Int, val retryAfter: Int) : RuntimeException("HTTP $status")
