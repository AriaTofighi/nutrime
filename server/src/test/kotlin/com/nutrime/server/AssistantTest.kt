package com.nutrime.server

import com.nutrime.server.ai.LlmClient
import com.nutrime.server.ai.StoredMessage
import com.nutrime.server.ai.historyForModel
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A model that replays scripted turns and records every request it gets. */
class FakeLlm(private val turns: List<(JsonObject) -> List<JsonObject>>) : LlmClient {
    val requests = mutableListOf<JsonObject>()
    override fun stream(request: JsonObject): Flow<JsonObject> {
        requests += request
        return flowOf(*turns[requests.size - 1](request).toTypedArray())
    }
}

private fun chunk(vararg parts: JsonObject) = buildJsonObject {
    putJsonArray("candidates") {
        add(buildJsonObject { putJsonObject("content") { put("role", "model"); put("parts", JsonArray(parts.toList())) } })
    }
}
private fun text(t: String) = buildJsonObject { put("text", t) }
private fun call(name: String, args: JsonObject, signature: String? = null) = buildJsonObject {
    putJsonObject("functionCall") { put("name", name); put("args", args) }
    signature?.let { put("thoughtSignature", it) }
}

/** The response the fake model got back for its last function call. */
private fun lastResponse(req: JsonObject): JsonObject = req["contents"]!!.jsonArray.last().jsonObject["parts"]!!.jsonArray
    .first().jsonObject["functionResponse"]!!.jsonObject["response"]!!.jsonObject

class AssistantTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today = LocalDate.now().toString()

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

    /** Registers a user, sends one chat message, and returns the SSE events. */
    private fun chat(llm: FakeLlm, message: String): List<Pair<String, JsonObject>> {
        val cfg = config()
        var out: List<Pair<String, JsonObject>> = emptyList()
        testApplication {
            val db = connectDatabase(cfg)
            application { module(cfg, db, llm) }
            val c = createClient { install(ContentNegotiation) { json() } }
            val auth = c.post("/api/auth/register") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "password123"))
            }.body<AuthResponse>()
            out = events(c.post("/api/ai/chat") {
                bearerAuth(auth.token); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("text", message); put("today", today) })
            }.bodyAsText())
        }
        return out
    }

    private fun search(vararg queries: String) = buildJsonObject { putJsonArray("queries") { queries.forEach { add(it) } } }

    private fun events(sse: String): List<Pair<String, JsonObject>> = sse.split("\n\n").filter { it.isNotBlank() }.map { block ->
        val lines = block.lines()
        val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
        event to json.parseToJsonElement(lines.first { it.startsWith("data: ") }.removePrefix("data: ")).jsonObject
    }

    @Test
    fun searchProposeConfirmAndSync() {
        val llm = FakeLlm(listOf(
            { _ -> listOf(chunk(text("Let me ")), chunk(text("look that up.")), chunk(call("search_foods", buildJsonObject { put("query", "banana raw") }, "sig-1"))) },
            { req ->
                val first = lastResponse(req)["results"]!!.jsonArray.first().jsonObject["foods"]!!.jsonArray.first().jsonObject["food_id"]!!.jsonPrimitive.content
                listOf(chunk(call("propose_log_foods", buildJsonObject {
                    putJsonArray("items") { add(buildJsonObject { put("food_id", first); put("meal", "breakfast"); put("grams", 120) }) }
                }, "sig-2")))
            },
            { _ -> listOf(chunk(text("I've proposed **1 banana** for breakfast."))) },
            // Second user turn: the model should now see the proposal as saved.
            { _ -> listOf(chunk(text("Done."))) },
        ))
        val cfg = config()
        testApplication {
            val db = connectDatabase(cfg)
            application { module(cfg, db, llm) }
            val c = createClient { install(ContentNegotiation) { json() } }
            val auth = c.post("/api/auth/register") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "password123"))
            }.body<AuthResponse>()

            val status = c.get("/api/ai/status") { bearerAuth(auth.token) }.body<JsonObject>()
            assertEquals(true, status["enabled"]!!.jsonPrimitive.content.toBoolean())

            val sse = c.post("/api/ai/chat") {
                bearerAuth(auth.token); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("text", "I had a banana for breakfast"); put("today", today) })
            }.bodyAsText()
            val ev = events(sse)
            // The message's one card comes after the reply text.
            assertEquals(listOf("conversation", "text", "text", "tool", "tool", "text", "proposal", "done"), ev.map { it.first })
            val conversationId = ev[0].second["id"]!!.jsonPrimitive.content
            val proposal = ev.first { it.first == "proposal" }.second["proposal"]!!.jsonObject
            assertEquals("pending", proposal["status"]!!.jsonPrimitive.content)
            assertTrue(proposal["items"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content.contains("Banana", ignoreCase = true))

            // Thought signatures go back to the model verbatim, and streamed text is merged into one part.
            val modelTurn = llm.requests[1]["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
            assertEquals("Let me look that up.", modelTurn[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("sig-1", modelTurn[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
            assertTrue(llm.requests[0]["systemInstruction"].toString().contains("Today is $today"))

            // Nothing is written until the user confirms.
            fun pull() = SyncRequest(0, emptyList())
            assertTrue(c.post("/api/sync") { bearerAuth(auth.token); contentType(ContentType.Application.Json); setBody(pull()) }
                .body<SyncResponse>().changes.none { it.collection == "diary" })

            val applied = c.post("/api/ai/proposals/${proposal["id"]!!.jsonPrimitive.content}/apply?today=$today") { bearerAuth(auth.token) }
                .body<JsonObject>()
            assertEquals("applied", applied["status"]!!.jsonPrimitive.content)

            val diary = c.post("/api/sync") { bearerAuth(auth.token); contentType(ContentType.Application.Json); setBody(pull()) }
                .body<SyncResponse>().changes.single { it.collection == "diary" }.data!!.jsonObject
            assertEquals(today, diary["date"]!!.jsonPrimitive.content)
            assertEquals(0, diary["meal"]!!.jsonPrimitive.content.toInt())
            assertEquals("g", diary["servingLabel"]!!.jsonPrimitive.content)
            assertEquals(120.0, diary["quantity"]!!.jsonPrimitive.content.toDouble())
            assertTrue(diary["nutrientsJson"]!!.jsonPrimitive.content.contains("\"1008\""))

            // The conversation shows the applied proposal.
            val conv = c.get("/api/ai/conversations/$conversationId") { bearerAuth(auth.token) }.body<JsonObject>()
            val msgs = conv["messages"]!!.jsonArray
            assertEquals(listOf("user", "assistant"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            val blocks = msgs[1].jsonObject["blocks"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "tool", "tool", "proposal", "text"), blocks.map { it["type"]!!.jsonPrimitive.content })
            assertEquals("applied", blocks[3]["proposal"]!!.jsonObject["status"]!!.jsonPrimitive.content)

            // Next turn: the model learns the proposal was saved.
            c.post("/api/ai/chat") {
                bearerAuth(auth.token); contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("conversationId", conversationId); put("text", "thanks"); put("today", today) })
            }.bodyAsText()
            assertTrue(llm.requests[3]["contents"].toString().contains("confirmed by the user and saved"))

            val list = c.get("/api/ai/conversations") { bearerAuth(auth.token) }.body<JsonArray>()
            assertEquals(1, list.size)
        }
    }

    @Test
    fun assistantOffWithoutKeyAndConversationsArePrivate() {
        val cfg = config().copy(geminiApiKey = null)
        testApplication {
            val db = connectDatabase(cfg)
            application { module(cfg, db) }
            val c = createClient { install(ContentNegotiation) { json() } }
            val auth = c.post("/api/auth/register") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "password123"))
            }.body<AuthResponse>()
            val r = c.post("/api/ai/chat") {
                bearerAuth(auth.token); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("text", "hi") })
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertEquals(HttpStatusCode.NotFound, c.get("/api/ai/conversations/nope") { bearerAuth(auth.token) }.status)
            assertEquals(HttpStatusCode.Unauthorized, c.get("/api/ai/conversations").status)
        }
    }

    @Test
    fun historyKeepsRecentImagesAndStartsAtUserMessage() {
        fun user(withImage: Boolean) = StoredMessage("user", buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                if (withImage) add(buildJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", "AAAA") } })
                add(buildJsonObject { put("text", "hi") })
            }
        })
        val model = StoredMessage("model", buildJsonObject { put("role", "model"); putJsonArray("parts") { add(buildJsonObject { put("text", "ok") }) } })
        val msgs = (1..5).flatMap { listOf(user(true), model) }
        val h = historyForModel(msgs, keepImages = 2)
        val withImages = h.count { it.toString().contains("inlineData") }
        assertEquals(2, withImages)
        assertTrue(h.first()["role"]!!.jsonPrimitive.content == "user")
        val trimmed = historyForModel(listOf(model) + msgs, maxMessages = 3)
        assertEquals("user", (trimmed.first()["role"] as JsonPrimitive).content)
    }

    @Test
    fun historyRepairsInterruptedTurns() {
        fun msg(kind: String, role: String, vararg parts: JsonObject) =
            StoredMessage(kind, buildJsonObject { put("role", role); put("parts", JsonArray(parts.toList())) })
        val callOnly = buildJsonObject { putJsonObject("functionCall") { put("name", "get_day") }; put("thoughtSignature", "s") }
        val h = historyForModel(listOf(
            msg("user", "user", text("hi")),
            msg("model", "model", text("Let me check"), callOnly), // its tool response was never saved
            msg("user", "user", text("hello?")),
            msg("model", "model", callOnly),                        // nothing left after dropping the call
            msg("user", "user", text("still there?")),
        ))
        assertEquals(listOf("user", "model", "user"), h.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("[{\"text\":\"Let me check\"}]", h[1]["parts"].toString())
        assertEquals(2, h[2]["parts"]!!.jsonArray.size)
    }

    @Test
    fun groupedSearchFindsEveryFoodInOneCallAndFlagsRepeats() {
        val llm = FakeLlm(listOf(
            { _ -> listOf(chunk(call("search_foods", search("banana raw", "yukon gold potatoes", "kirkland greek yogurt", "dal")))) },
            { _ -> listOf(chunk(call("search_foods", search("Greek yogurt, Kirkland")))) },
            { _ -> listOf(chunk(text("Found them."))) },
        ))
        val ev = chat(llm, "log these")
        assertEquals(listOf("Searching 4 foods", "Searching foods for “Greek yogurt, Kirkland”"),
            ev.filter { it.first == "tool" }.map { it.second["label"]!!.jsonPrimitive.content })

        val results = lastResponse(llm.requests[1])["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("banana raw", "yukon gold potatoes", "kirkland greek yogurt", "dal"), results.map { it["query"]!!.jsonPrimitive.content })
        fun names(r: JsonObject) = r["foods"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names(results[0]).first().contains("Banana", ignoreCase = true))
        // "yukon" isn't in the database, but "gold potatoes" is.
        assertTrue(names(results[1]).any { it.contains("gold", ignoreCase = true) && it.contains("Potatoes") }, names(results[1]).toString())
        assertEquals(listOf("yukon"), results[1]["missing_words"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The brand is missing, but plain Greek yogurt still comes back.
        assertEquals(listOf("kirkland"), results[2]["missing_words"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(names(results[2]).any { it.contains("Greek", ignoreCase = true) }, names(results[2]).toString())
        assertTrue(results.none { it.containsKey("repeated") })
        // Curated NutriMe foods come first and are found by their aliases.
        val dal = results[3]["foods"]!!.jsonArray.first().jsonObject
        assertEquals("nm:lentils-cooked", dal["food_id"]!!.jsonPrimitive.content)
        assertEquals("NutriMe", dal["source"]!!.jsonPrimitive.content)
        assertTrue(!results[3].containsKey("missing_words"))

        // The same search in other words is flagged as a repeat.
        val again = lastResponse(llm.requests[2])["results"]!!.jsonArray.single().jsonObject
        assertTrue(again.containsKey("repeated"))
    }

    @Test
    fun runningOutOfStepsStillEndsWithAProposal() {
        val busy: (JsonObject) -> List<JsonObject> = { _ -> listOf(chunk(call("get_day", buildJsonObject {}), call("get_weight_history", buildJsonObject {}))) }
        val llm = FakeLlm(List(10) { busy } + listOf(
            { _ ->
                listOf(chunk(call("propose_log_foods", buildJsonObject {
                    putJsonArray("items") { add(buildJsonObject { put("food_id", "usda:173944"); put("meal", "lunch"); put("grams", 100) }) }
                })))
            },
            { _ -> listOf(chunk(text("Proposed what I found."))) },
        ))
        val ev = chat(llm, "log my lunch")
        // 10 rounds of 2 calls hit the round limit; the model then only gets propose_* tools and a note.
        val wrapUp = llm.requests[10]
        val tools = wrapUp["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(tools.isNotEmpty() && tools.all { it.startsWith("propose_") }, tools.toString())
        assertTrue(wrapUp["contents"].toString().contains("Step limit for this message reached"))
        assertEquals(1, ev.count { it.first == "proposal" }, ev.map { it.first }.toString())
        val text = ev.filter { it.first == "text" }.joinToString("") { it.second["delta"]!!.jsonPrimitive.content }
        assertEquals("Proposed what I found.", text)
        assertEquals("done", ev.last().first)
    }

    @Test
    fun proposalsAreCheckedAgainstExpectedCalories() {
        fun propose(allow: Boolean) = buildJsonObject {
            putJsonArray("items") {
                // 100 g of raw banana is ~89 kcal, nowhere near 300.
                add(buildJsonObject { put("food_id", "usda:173944"); put("meal", "snacks"); put("grams", 100); put("expected_kcal", 300) })
            }
            if (allow) put("allow_kcal_mismatch", true)
        }
        val llm = FakeLlm(listOf(
            { _ -> listOf(chunk(call("propose_log_foods", propose(false)))) },
            { _ -> listOf(chunk(call("propose_log_foods", propose(true)))) },
            { _ -> listOf(chunk(text("Proposed."))) },
        ))
        val ev = chat(llm, "a banana, 300 kcal")
        val first = lastResponse(llm.requests[1])
        assertEquals("not_proposed", first["status"]!!.jsonPrimitive.content)
        assertTrue(first["kcal_mismatches"]!!.jsonArray.single().jsonPrimitive.content.contains("expected 300"))
        assertEquals("pending", lastResponse(llm.requests[2])["status"]!!.jsonPrimitive.content)
        assertEquals(1, ev.count { it.first == "proposal" })
    }
}
