package com.nutrime.server

import com.nutrime.server.ai.FoodSearch
import com.nutrime.server.ai.UserData
import com.nutrime.server.food.FoodCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/** The assistant's food search over the bundled foods.db against shared/testdata/search_quality.json. */
class SearchQualityTest {
    private fun config() = Config(
        port = 0,
        databaseUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        databaseUser = null,
        databasePassword = null,
        jwtSecret = "x".repeat(40),
        allowRegistration = true,
        geminiApiKey = "test",
        foodsDbPath = System.getProperty("nutrime.foodsDb"),
        foodSearchOnline = false,
    )

    @Test
    fun searchQualityCases() = runBlocking {
        val file = listOf("../shared/testdata/search_quality.json", "shared/testdata/search_quality.json").map(::File).first { it.exists() }
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val top = root["top"]?.jsonPrimitive?.int ?: 5
        val cfg = config()
        val db = connectDatabase(cfg)
        val search = FoodSearch(cfg.foodsDbPath, false, FoodCatalog(cfg, db))
        val data = UserData(db, "quality-test")
        val failures = ArrayList<String>()
        root["cases"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val query = c["query"]!!.jsonPrimitive.content
            val r = search.searchDetailed(data, query, top)
            val names = r.foods.map { "${it.name} ${it.brand.orEmpty()}".lowercase() }
            c["name_has"]?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }?.let { words ->
                if (names.none { n -> words.all { it in n } }) failures += "$query: no result with $words in ${r.foods.map { it.name }}"
            }
            c["confidence"]?.jsonPrimitive?.content?.let { want ->
                if (r.confidence != want) failures += "$query: confidence ${r.confidence}, expected $want"
            }
            if (c["first_not_restaurant"]?.jsonPrimitive?.content == "true") {
                val first = r.foods.firstOrNull()
                if (first == null || com.nutrime.app.model.SearchRank.isRestaurant(first)) {
                    failures += "$query: first result must not be a restaurant item, got ${first?.name} [${first?.brand}] (${first?.category})"
                }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} search quality failures:\n" + failures.joinToString("\n"))
    }
}
