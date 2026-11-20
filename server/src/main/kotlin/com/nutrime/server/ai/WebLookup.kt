package com.nutrime.server.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.abs

/** One food to look up on the web: a branded product or a restaurant item. */
@Serializable
data class WebItem(val name: String, val brand: String? = null, val restaurant: String? = null, val amount: String? = null) {
    /** "Big Mac (McDonald's)". */
    val label: String get() = name + listOfNotNull(brand, restaurant).distinct().joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
}

/** Nutrition published on the web for one serving, as the source prints it. */
@Serializable
data class WebNutrition(
    val name: String,
    val brand: String? = null,
    @SerialName("serving_label") val servingLabel: String,
    /** Grams (or ml for drinks) of the serving, when the source gives it. */
    @SerialName("serving_grams") val servingGrams: Double? = null,
    val calories: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugars_g") val sugarsG: Double? = null,
    @SerialName("saturated_fat_g") val saturatedFatG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("source_note") val sourceNote: String? = null,
)

/** What a lookup found: [found], or [problem] saying why not ("not found: …", or a provider error when [error]). */
@Serializable
data class WebAnswer(val found: WebNutrition? = null, val problem: String? = null, val error: Boolean = false)

/** Finds published nutrition for one item. Tests pass a fake; production uses [GeminiWebNutritionClient]. */
fun interface WebNutritionClient {
    suspend fun lookup(item: WebItem): WebAnswer
}

/** Looked-up results by normalized item (a hash; the item text itself is not stored). Public data only. */
object WebLookupCache : Table("web_lookup_cache") {
    val key = varchar("key", 64)
    val result = text("result")
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(key)
}

/** One row per web request a user's assistant made, for the daily limit. */
object WebLookupUses : Table("web_lookup_uses") {
    val id = long("id").autoIncrement()
    val userId = varchar("user_id", 36)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId, createdAt) }
}

/**
 * The web_nutrition_lookup tool: limits, the 90-day cache and server-side checks of the numbers.
 * Never logs the items or the responses.
 */
class WebLookup(private val db: Database, private val client: WebNutritionClient?, private val limit: () -> Int) {
    constructor(db: Database, client: WebNutritionClient?, dailyLimit: Int) : this(db, client, { dailyLimit })
    private val dailyLimit: Int get() = limit()
    private val log = LoggerFactory.getLogger(WebLookup::class.java)

    val available: Boolean get() = client != null && dailyLimit > 0

    suspend fun lookup(userId: String, items: List<WebItem>): JsonObject {
        val client = client
        val dailyLimit = dailyLimit
        if (client == null || dailyLimit <= 0) return buildJsonObject {
            put("status", "unavailable")
            put("note", "Web lookup is not set up on this server. Use the closest database food (substitute_for), a recipe, or propose_quick_add.")
        }
        val now = System.currentTimeMillis()
        val cached = items.associateWith { cachedAnswer(it, now) }
        val toFetch = items.filter { cached[it] == null }
        val used = usedToday(userId, now)
        val allowed = (dailyLimit - used).coerceAtLeast(0)
        val fetching = toFetch.take(allowed)
        if (fetching.isNotEmpty()) recordUses(userId, fetching.size, now)
        val fetched: Map<WebItem, WebAnswer> = coroutineScope {
            fetching.map { item ->
                async {
                    val answer = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
                        runCatching { client.lookup(item) }.getOrElse {
                            log.warn("Web nutrition lookup failed: {}", it.javaClass.simpleName)
                            WebAnswer(problem = "The web lookup failed.", error = true)
                        }
                    } ?: WebAnswer(problem = "The web lookup took too long.", error = true)
                    item to checked(answer)
                }
            }.awaitAll().toMap()
        }
        fetched.forEach { (item, answer) -> if (!answer.error) store(item, answer, now) }
        return buildJsonObject {
            putJsonArray("results") {
                items.forEach { item ->
                    val answer = cached[item] ?: fetched[item]
                    addJsonObject {
                        put("item", item.label)
                        item.amount?.let { put("amount", it) }
                        when {
                            answer == null -> {
                                put("status", "limit_reached")
                                put("note", "Daily web lookup limit reached ($dailyLimit). Use the closest database food or propose_quick_add.")
                            }
                            answer.found != null -> foundJson(this, answer.found)
                            else -> {
                                put("status", if (answer.error) "error" else "not_found")
                                put("note", (answer.problem ?: "Not found.") + " Use the closest database food (substitute_for), a recipe, or propose_quick_add.")
                            }
                        }
                    }
                }
            }
            put("lookups_left_today", (allowed - fetching.size).coerceAtLeast(0))
        }
    }

    private fun foundJson(b: kotlinx.serialization.json.JsonObjectBuilder, n: WebNutrition) = with(b) {
        put("status", "found")
        put("name", n.name)
        n.brand?.let { put("brand", it) }
        put("serving_label", n.servingLabel)
        n.servingGrams?.let { put("serving_grams", it) }
        putJsonObject("per_serving") {
            put("calories", n.calories); put("protein_g", n.proteinG); put("carbs_g", n.carbsG); put("fat_g", n.fatG)
            n.fiberG?.let { put("fiber_g", it) }; n.sugarsG?.let { put("sugars_g", it) }
            n.saturatedFatG?.let { put("saturated_fat_g", it) }; n.sodiumMg?.let { put("sodium_mg", it) }
        }
        n.servingGrams?.let { g -> put("kcal_per_100g", r(n.calories * 100 / g, 0)) }
        put("source_url", n.sourceUrl)
        put("source_host", host(n.sourceUrl) ?: "")
        n.sourceNote?.let { put("source_note", it) }
        put("next", if (n.servingGrams != null)
            "Call propose_create_food with these per-serving values, serving_label and serving_grams, and source_url/source_note; log it if they ate it."
        else "The source gives no serving weight. Ask the user for it, or use propose_quick_add with these calories and macros.")
    }

    /** The answer, or a problem when its numbers fail [validate]. */
    private fun checked(a: WebAnswer): WebAnswer {
        val n = a.found ?: return a
        val problem = validate(n) ?: return a
        return WebAnswer(problem = "The numbers found don't add up ($problem).")
    }

    private fun cachedAnswer(item: WebItem, now: Long): WebAnswer? = transaction(db) {
        WebLookupCache.selectAll().where { WebLookupCache.key eq key(item) }.singleOrNull()
    }?.let { row ->
        val answer = runCatching { recordJson.decodeFromString(WebAnswer.serializer(), row[WebLookupCache.result]) }.getOrNull() ?: return null
        val maxAge = if (answer.found != null) CACHE_DAYS * DAY_MS else NOT_FOUND_CACHE_MS
        answer.takeIf { now - row[WebLookupCache.fetchedAt] < maxAge }
    }

    private fun store(item: WebItem, answer: WebAnswer, now: Long) = transaction(db) {
        WebLookupCache.upsert {
            it[key] = key(item)
            it[result] = recordJson.encodeToString(WebAnswer.serializer(), answer)
            it[fetchedAt] = now
        }
        WebLookupCache.deleteWhere { fetchedAt less now - CACHE_DAYS * DAY_MS }
    }

    private fun usedToday(userId: String, now: Long): Int = transaction(db) {
        WebLookupUses.selectAll().where { (WebLookupUses.userId eq userId) and (WebLookupUses.createdAt greater now - DAY_MS) }.count().toInt()
    }

    private fun recordUses(userId: String, n: Int, now: Long) = transaction(db) {
        WebLookupUses.deleteWhere { createdAt less now - 2 * DAY_MS }
        repeat(n) { WebLookupUses.insert { it[WebLookupUses.userId] = userId; it[createdAt] = now } }
    }

    companion object {
        const val MAX_ITEMS = 5
        const val MAX_CALLS_PER_MESSAGE = 2
        const val CACHE_DAYS = 90L
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val NOT_FOUND_CACHE_MS = DAY_MS
        private const val ITEM_TIMEOUT_MS = 45_000L

        /** The cache key: a hash of the item's words (name, brand, restaurant; not the amount). */
        fun key(item: WebItem): String {
            fun words(s: String?) = s.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.joinToString(" ")
            val text = "${words(item.name)}|${words(item.brand)}|${words(item.restaurant)}"
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun host(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

        /**
         * Why published numbers can't be right, or null when they pass: calories within 15% (or 20 kcal) of
         * 4 × protein + 4 × carbs + 9 × fat, at most 900 kcal per 100 g, sane ranges and an http(s) source.
         */
        fun validate(n: WebNutrition): String? {
            val values = listOfNotNull(n.calories, n.proteinG, n.carbsG, n.fatG, n.fiberG, n.sugarsG, n.saturatedFatG, n.sodiumMg)
            if (values.any { it.isNaN() || it < 0 }) return "negative or missing values"
            if (n.calories > 5000) return "more than 5000 kcal per serving"
            val atwater = 4 * n.proteinG + 4 * n.carbsG + 9 * n.fatG
            if (abs(atwater - n.calories) > maxOf(20.0, 0.15 * n.calories)) return "calories ${n.calories.toInt()} vs ${atwater.toInt()} from macros"
            n.servingGrams?.let { g ->
                if (g <= 0 || g > 5000) return "serving weight out of range"
                if (n.calories * 100 / g > 900) return "more than 900 kcal per 100 g"
                if (n.proteinG + n.carbsG + n.fatG > g * 1.05) return "macros weigh more than the serving"
            }
            val scheme = runCatching { URI(n.sourceUrl).scheme?.lowercase() }.getOrNull()
            if (scheme != "https" && scheme != "http") return "no source link"
            return null
        }
    }
}

/**
 * Looks up published nutrition with Gemini and Google Search grounding: one non-streaming generateContent
 * request per item with the `google_search` tool, answered as strict JSON (search grounding does not allow
 * a response schema, so the JSON is asked for in the prompt and checked here).
 */
class GeminiWebNutritionClient(
    private val apiKey: String,
    private val model: () -> String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : WebNutritionClient {
    constructor(apiKey: String, model: String, baseUrl: String = "https://generativelanguage.googleapis.com/v1beta") : this(apiKey, { model }, baseUrl)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val log = LoggerFactory.getLogger(GeminiWebNutritionClient::class.java)

    override suspend fun lookup(item: WebItem): WebAnswer {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt(item)) } }
                }
            }
            putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
            putJsonObject("generationConfig") { put("temperature", 0) }
        }
        val req = HttpRequest.newBuilder(URI("$baseUrl/models/${model()}:generateContent"))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .timeout(Duration.ofSeconds(40))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = withContext(Dispatchers.IO) { http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await() }
        if (res.statusCode() != 200) {
            log.warn("Web nutrition lookup: HTTP {}", res.statusCode())
            return WebAnswer(problem = if (res.statusCode() == 429) "The web lookup is busy." else "The web lookup failed.", error = true)
        }
        return parse(res.body())
    }

    /** The answer in a generateContent response: the JSON in the text, with the source from grounding when missing. */
    fun parse(responseBody: String): WebAnswer {
        val root = runCatching { json.parseToJsonElement(responseBody) as JsonObject }.getOrNull()
            ?: return WebAnswer(problem = "The web lookup gave no answer.", error = true)
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return WebAnswer(problem = "The web lookup gave no answer.", error = true)
        val text = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }.joinToString("")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return WebAnswer(problem = "The web lookup gave no answer.", error = true)
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as JsonObject }.getOrNull()
            ?: return WebAnswer(problem = "The web lookup gave no answer.", error = true)
        if ((obj["found"] as? JsonPrimitive)?.content != "true") {
            return WebAnswer(problem = "Not found: " + ((obj["reason"] as? JsonPrimitive)?.content ?: "no published nutrition").take(200) + ".")
        }
        val grounding = ((candidate["groundingMetadata"] as? JsonObject)?.get("groundingChunks") as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("web") as? JsonObject) }
        val url = (obj["source_url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") }
            ?: grounding.firstNotNullOfOrNull { (it["uri"] as? JsonPrimitive)?.content }
            ?: return WebAnswer(problem = "Not found: no source.")
        val withUrl = JsonObject(obj + ("source_url" to JsonPrimitive(url)))
        return runCatching { WebAnswer(found = json.decodeFromJsonElement(WebNutrition.serializer(), withUrl)) }
            .getOrElse { WebAnswer(problem = "Not found: incomplete numbers.") }
    }

    private fun prompt(item: WebItem): String = """
        Find the published nutrition facts for this food with Google Search.
        Food: ${item.name}
        ${item.brand?.let { "Brand: $it" } ?: ""}
        ${item.restaurant?.let { "Restaurant: $it" } ?: ""}
        ${item.amount?.let { "Amount eaten (for choosing the size): $it" } ?: ""}

        Rules:
        - Use the brand's or restaurant's official nutrition page or the package label first; prefer the Canadian page
          when one exists; otherwise a reputable nutrition database that shows the label. Never use forums or recipes.
        - Copy the numbers exactly as the source prints them for ONE serving. Never estimate or calculate values.
        - If the source gives the serving weight in grams (or ml for drinks), include it; otherwise use null.
        - If you can't find a source with these numbers, answer found false.

        Answer with ONLY one JSON object, no other text:
        {"found": true, "name": "...", "brand": "... or null", "serving_label": "1 sandwich", "serving_grams": 215,
         "calories": 0, "protein_g": 0, "carbs_g": 0, "fat_g": 0, "fiber_g": null, "sugars_g": null,
         "saturated_fat_g": null, "sodium_mg": null, "source_url": "https://...", "source_note": "short note on the source"}
        or {"found": false, "reason": "short reason"}
    """.trimIndent()
}
