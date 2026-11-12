package com.nutrime.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * Runs the assistant with the real model on the fixed cases in server/evals/cases.json and checks
 * the card it proposes: one card per message, the right foods, calories close to what the user gave,
 * no estimates where the database has the food.
 *
 * It calls the Gemini API, so run it only when asked:
 *   cd server && GEMINI_API_KEY=... ./gradlew evalAssistant
 * EVAL_ONLINE=1 also lets food search call USDA and Open Food Facts (off by default, for repeatable runs).
 * EVAL_CASES=path picks another cases file.
 */

@Serializable private data class EvalItem(val name: String, val kcal: Double? = null, val tolerance: Double = 0.15)
@Serializable private data class EvalExpect(val maxCards: Int = 1, val noEstimates: Boolean = false, val items: List<EvalItem> = emptyList())
@Serializable private data class EvalCase(val name: String, val message: String, val localTime: String = "12:00", val expect: EvalExpect = EvalExpect())
@Serializable private data class EvalFile(val cases: List<EvalCase>)

private val evalJson = Json { ignoreUnknownKeys = true }

fun main() {
    val key = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: run { System.err.println("Set GEMINI_API_KEY to run the assistant evaluation."); exitProcess(2) }
    val file = File(System.getenv("EVAL_CASES") ?: "evals/cases.json")
    val cases = evalJson.decodeFromString<EvalFile>(file.readText()).cases
    var failed = 0
    cases.forEach { case ->
        val problems = runCase(key, case)
        if (problems.isEmpty()) println("PASS  ${case.name}") else {
            failed++
            println("FAIL  ${case.name}")
            problems.forEach { println("      - $it") }
        }
    }
    println("\n${cases.size - failed} of ${cases.size} cases passed")
    exitProcess(if (failed == 0) 0 else 1)
}

private fun runCase(key: String, case: EvalCase): List<String> {
    val cfg = Config(
        port = 0,
        databaseUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        databaseUser = null, databasePassword = null,
        jwtSecret = "x".repeat(40), allowRegistration = true,
        geminiApiKey = key,
        geminiModel = System.getenv("GEMINI_MODEL") ?: Config(0, "", null, null, "", false).geminiModel,
        foodsDbPath = System.getProperty("nutrime.foodsDb"),
        foodSearchOnline = System.getenv("EVAL_ONLINE") == "1",
    )
    val problems = mutableListOf<String>()
    testApplication {
        val db = connectDatabase(cfg)
        application { module(cfg, db, null) }
        val c = createClient { install(ContentNegotiation) { json() } }
        val auth = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(invitedAuthRequest(db, "eval@example.com", "password123"))
        }.body<AuthResponse>()
        val sse = c.post("/api/ai/chat") {
            bearerAuth(auth.token); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", case.message); put("today", LocalDate.now().toString()); put("localTime", case.localTime) })
        }.bodyAsText()
        val events = sse.split("\n\n").filter { it.isNotBlank() }.mapNotNull { block ->
            val lines = block.lines()
            val event = lines.firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ") ?: return@mapNotNull null
            event to evalJson.parseToJsonElement(lines.first { it.startsWith("data: ") }.removePrefix("data: ")).jsonObject
        }
        events.filter { it.first == "error" }.forEach { problems += "Error: ${it.second["message"]}" }
        val cards = events.filter { it.first == "proposal" }.map { it.second["proposal"]!!.jsonObject }.distinctBy { it["id"].toString() }
        if (cards.size > case.expect.maxCards) problems += "${cards.size} cards, expected at most ${case.expect.maxCards}"
        val items = cards.flatMap { it["items"]!!.jsonArray.map { i -> i.jsonObject } }
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
        if (case.expect.noEstimates) items.filter { it.s("kind") == "quick_add" }.forEach { problems += "Estimate instead of a database food: ${it.s("title")}" }
        case.expect.items.forEach { want ->
            val words = want.name.lowercase().split(' ').filter { it.isNotBlank() }
            val match = items.firstOrNull { item -> val t = (item.s("name") ?: item.s("title")).orEmpty().lowercase(); words.all { it in t } }
            if (match == null) { problems += "No item for \"${want.name}\""; return@forEach }
            val kcal = match["kcal"]?.jsonPrimitive?.doubleOrNull
            if (want.kcal != null && kcal != null && abs(kcal - want.kcal) / want.kcal > want.tolerance) {
                problems += "\"${want.name}\": ${kcal.toInt()} kcal, expected ${want.kcal.toInt()} (${match.s("title")})"
            }
        }
        println("      ${items.size} items: " + items.joinToString("; ") { "${it.s("title")} ${it["kcal"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: "?"} kcal" })
    }
    return problems
}
