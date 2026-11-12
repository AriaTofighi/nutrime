package com.nutrime.server

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.Serving
import com.nutrime.server.ai.decodeNutrients
import com.nutrime.server.ai.recordJson
import com.nutrime.server.food.CatalogFoods
import com.nutrime.server.food.CatalogStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet

@Serializable
private data class AdminFood(
    val food: Food,
    val sourceLabel: String,
    val reviewStatus: String,
    val sourceUrl: String? = null,
    val license: String? = null,
    val fetchedAt: Long? = null,
    val basis: String? = null,
    val aliases: List<String> = emptyList(),
)

@Serializable
private data class AdminFoodPage(val items: List<AdminFood>, val total: Long, val page: Int, val pageSize: Int = 25)

/** Read-only stored data. This class has no provider client and cannot make online food requests. */
class AdminFoods(private val config: Config, private val db: Database) {
    private val access = AdminAccess(config, db)

    @Serializable
    private data class Notes(val key: String, val basis: String? = null, val aliases: List<String> = emptyList())
    @Serializable
    private data class NotesFile(val foods: List<Notes>)
    @Serializable
    private data class Portion(val l: String, val g: Double)

    private val notes by lazy {
        config.curatedFoodsPath?.let { File(it).listFiles() }.orEmpty()
            .filter { it.extension == "json" }.sortedBy { it.name }.flatMap { file ->
                runCatching { recordJson.decodeFromString<NotesFile>(file.readText()).foods }
                    .onFailure { LoggerFactory.getLogger(AdminFoods::class.java).warn("Cannot read curated notes {}", file.name, it) }
                    .getOrDefault(emptyList())
            }.associateBy { "nm:${it.key}" }
    }

    fun routes(route: Route) = with(route) {
        get("/api/admin/status") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val uid = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(mapOf("admin" to withContext(Dispatchers.IO) { access.isAdmin(uid) }))
        }
        get("/api/admin/foods") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val uid = call.principal<JWTPrincipal>()!!.payload.subject
            withContext(Dispatchers.IO) { access.requireAdmin(uid) }
            val params = call.request.queryParameters
            val collection = params["collection"] ?: "curated"
            if (collection !in setOf("curated", "cached", "library")) badRequest("Unknown food collection")
            val query = params["q"].orEmpty()
            if (query.length > 200) badRequest("Search must be at most 200 characters")
            val page = params["page"]?.let { it.toIntOrNull() ?: badRequest("Invalid page") } ?: 1
            if (page !in 1..100_000) badRequest("Invalid page")
            val words = CatalogStore.normalize(query).split(' ').filter { it.isNotEmpty() }.take(12)
            call.respond(withContext(Dispatchers.IO) {
                if (collection == "cached") cached(words, page) else bundled(collection, words, page)
            })
        }
    }

    private fun cached(words: List<String>, page: Int): AdminFoodPage = transaction(db) {
        val condition = words.map { word ->
            (CatalogFoods.searchText like "%$word%") or (CatalogFoods.barcode like "%${word.trimStart('0').ifEmpty { "0" }}%")
        }.fold<Op<Boolean>, Op<Boolean>>(Op.TRUE) { a, b -> a and b }
        val total = CatalogFoods.selectAll().where { condition }.count()
        val items = CatalogFoods.selectAll().where { condition }
            .orderBy(CatalogFoods.fetchedAt to SortOrder.DESC, CatalogFoods.id to SortOrder.ASC)
            .limit(25).offset((page - 1) * 25L).map { row ->
                AdminFood(
                    food = recordJson.decodeFromString<Food>(row[CatalogFoods.payload]),
                    sourceLabel = if (row[CatalogFoods.provider] == "off") "Open Food Facts" else "USDA",
                    reviewStatus = "Not reviewed by NutriMe",
                    sourceUrl = row[CatalogFoods.sourceUrl], license = row[CatalogFoods.license],
                    fetchedAt = row[CatalogFoods.fetchedAt],
                )
            }
        AdminFoodPage(items, total, page)
    }

    private fun bundled(collection: String, words: List<String>, page: Int): AdminFoodPage {
        val path = config.foodsDbPath?.takeIf { File(it).isFile }
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "The bundled food database is unavailable")
        val where = buildList {
            add(if (collection == "curated") "source = 'nutrime'" else "source <> 'nutrime'")
            words.forEach { _ -> add("LOWER(name || ' ' || COALESCE(brand, '') || ' ' || COALESCE(barcode, '') || ' ' || COALESCE(keywords, '')) LIKE ?") }
        }.joinToString(" AND ")
        return DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { connection ->
            val total = connection.prepareStatement("SELECT COUNT(*) FROM food WHERE $where").use { st ->
                words.forEachIndexed { i, word -> st.setString(i + 1, "%$word%") }
                st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            val items = connection.prepareStatement("SELECT * FROM food WHERE $where ORDER BY name COLLATE NOCASE, id LIMIT 25 OFFSET ?").use { st ->
                words.forEachIndexed { i, word -> st.setString(i + 1, "%$word%") }
                st.setLong(words.size + 1, (page - 1) * 25L)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(bundledRow(rs)) } }
            }
            AdminFoodPage(items, total, page)
        }
    }

    private fun bundledRow(rs: ResultSet): AdminFood {
        val curated = rs.getString("source") == "nutrime"
        val id = rs.getString("ref") ?: "usda:${rs.getLong("id")}"
        val metadata = if (curated) notes[id] else null
        return AdminFood(
            food = Food(
                id = id, source = if (curated) FoodSource.NUTRIME else FoodSource.USDA,
                name = rs.getString("name"), brand = rs.getString("brand"), category = rs.getString("category"),
                barcode = rs.getString("barcode"), nutrients = decodeNutrients(rs.getString("nutrients")),
                servings = recordJson.decodeFromString<List<Portion>>(rs.getString("portions")).map { Serving(it.l, it.g) },
            ),
            sourceLabel = when (rs.getString("source")) {
                "nutrime" -> "NutriMe"
                "sr_legacy" -> "USDA · SR Legacy"
                "foundation" -> "USDA · Foundation"
                "fndds" -> "USDA · FNDDS"
                else -> "USDA"
            },
            reviewStatus = if (curated) "Curated by NutriMe" else "USDA reference food",
            sourceUrl = if (curated) null else "https://fdc.nal.usda.gov/food-details/${rs.getLong("id")}/nutrients",
            license = if (curated) null else "CC0-1.0",
            basis = metadata?.basis, aliases = metadata?.aliases.orEmpty(),
        )
    }

    private fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
}
