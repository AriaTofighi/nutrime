package com.nutrime.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private fun config(allowRegistration: Boolean = true) = Config(
        port = 0,
        databaseUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        databaseUser = null,
        databasePassword = null,
        jwtSecret = "x".repeat(40),
        allowRegistration = allowRegistration,
    )

    private fun run(cfg: Config = config(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val db = connectDatabase(cfg)
        application { module(cfg, db) }
        block()
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private fun change(id: String, updatedAt: Long, kcal: Int, deleted: Boolean = false) = SyncChange(
        collection = "diary", id = id, updatedAt = updatedAt, deleted = deleted,
        data = buildJsonObject { put("id", id); put("kcal", kcal); put("updatedAt", updatedAt); put("deleted", deleted) },
    )

    @Test
    fun healthWorks() = run {
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }

    @Test
    fun registerLoginAndSync() = run {
        val c = jsonClient()
        val reg = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("Me@Example.com", "password123"))
        }
        assertEquals(HttpStatusCode.Created, reg.status)

        val dup = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        val bad = c.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "wrongpassword"))
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)

        val login = c.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "password123"))
        }.body<AuthResponse>()

        // Device A pushes two records.
        val r1 = c.post("/api/sync") {
            bearerAuth(login.token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(0, listOf(change("a", 100, 200), change("b", 100, 300))))
        }.body<SyncResponse>()
        assertEquals(2, r1.changes.size)
        assertEquals(2L, r1.cursor)

        // Device B (cursor 0) pulls both, then pushes a newer edit of "a".
        val r2 = c.post("/api/sync") {
            bearerAuth(login.token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(0, listOf(change("a", 200, 250))))
        }.body<SyncResponse>()
        assertEquals(2, r2.changes.size)

        // A stale edit of "a" (older timestamp) is ignored; A sees only B's newer edit.
        val r3 = c.post("/api/sync") {
            bearerAuth(login.token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(r1.cursor, listOf(change("a", 150, 999))))
        }.body<SyncResponse>()
        assertEquals(1, r3.changes.size)
        assertEquals(200L, r3.changes.single().updatedAt)

        // Deletions propagate.
        val r4 = c.post("/api/sync") {
            bearerAuth(login.token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(r3.cursor, listOf(change("b", 300, 300, deleted = true))))
        }.body<SyncResponse>()
        assertTrue(r4.changes.single().deleted)
        assertFalse(r4.hasMore)
    }

    @Test
    fun syncRequiresAuth() = run {
        val c = jsonClient()
        val r = c.post("/api/sync") { contentType(ContentType.Application.Json); setBody(SyncRequest(0, emptyList())) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun usersAreIsolated() = run {
        val c = jsonClient()
        suspend fun reg(email: String) = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(AuthRequest(email, "password123"))
        }.body<AuthResponse>()
        val a = reg("a@example.com")
        val b = reg("b@example.com")
        c.post("/api/sync") {
            bearerAuth(a.token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(0, listOf(change("x", 1, 1))))
        }
        val rb = c.post("/api/sync") {
            bearerAuth(b.token); contentType(ContentType.Application.Json); setBody(SyncRequest(0, emptyList()))
        }.body<SyncResponse>()
        assertTrue(rb.changes.isEmpty())
    }

    @Test
    fun registrationCanBeDisabled() = run(config(allowRegistration = false)) {
        val c = jsonClient()
        val r = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("me@example.com", "password123"))
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun foodCandidatesRankBrandedFoodsAndKeepOthersCustomFoodsPrivate() = run(config().copy(adminEmails = setOf("admin@example.com"))) {
        val c = jsonClient()
        suspend fun register(email: String) = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(AuthRequest(email, "password123"))
        }.body<AuthResponse>()
        fun food(id: String, kind: String, name: String, kcal: Double, protein: Double, barcode: String? = null) = SyncChange(
            collection = "foods", id = id, updatedAt = 1, deleted = false,
            data = buildJsonObject {
                put("id", id); put("kind", kind); put("name", name); put("brand", "Brand"); barcode?.let { put("barcode", it) }
                put("nutrientsJson", "{\"1008\":$kcal,\"1003\":$protein,\"1005\":0.0,\"1004\":0.0}")
                put("servingsJson", "[{\"label\":\"1 scoop\",\"grams\":30.0}]"); put("updatedAt", 1)
            },
        )
        fun log(id: String, foodId: String) = SyncChange(
            collection = "diary", id = id, updatedAt = 1, deleted = false,
            data = buildJsonObject {
                put("id", id); put("date", "2026-11-09"); put("meal", 0); put("foodId", foodId); put("foodName", "x")
                put("servingLabel", "g"); put("servingGrams", 1.0); put("quantity", 30.0); put("nutrientsJson", "{}")
                put("createdAt", 1); put("updatedAt", 1)
            },
        )
        suspend fun sync(token: String, vararg changes: SyncChange) = c.post("/api/sync") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(SyncRequest(0, changes.toList()))
        }
        val admin = register("admin@example.com")
        val other = register("other@example.com")
        sync(admin.token, food("off:111", "off", "Whey", 400.0, 80.0, "111"), food("custom:mine", "custom", "My bar", 400.0, 25.0),
            log("a1", "off:111"), log("a2", "custom:mine"))
        sync(other.token, food("off:111", "off", "Whey", 400.0, 80.0, "111"), food("usda:999", "usda_branded", "Typo bar", 5000.0, 10.0),
            food("custom:theirs", "custom", "Their secret recipe", 200.0, 10.0), log("b1", "off:111"), log("b2", "off:111"))

        assertEquals(HttpStatusCode.Forbidden, c.get("/api/foods/candidates") { bearerAuth(other.token) }.status)
        val body = c.get("/api/foods/candidates") { bearerAuth(admin.token) }.body<kotlinx.serialization.json.JsonObject>()
        val list = body["candidates"]!!.jsonArray.map { it.jsonObject }
        fun str(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]!!.jsonPrimitive.content
        // Most users first; the admin's own custom food is in, other people's aren't.
        assertEquals(listOf("off:111", "custom:mine", "usda:999"), list.map { str(it, "id") })
        assertTrue(list.none { str(it, "name").contains("secret") })
        assertEquals("2", str(list[0], "users")); assertEquals("3", str(list[0], "logs"))
        val draft = list[0]["draft"]!!.jsonObject
        assertEquals("brand-whey", str(draft, "key"))
        assertEquals("120.0", draft["label"]!!.jsonObject["calories"]!!.jsonPrimitive.content)
        assertTrue(str(draft, "basis").startsWith("TODO"))
        assertTrue(list[2]["flags"]!!.jsonArray.any { it.jsonPrimitive.content.contains("900 kcal") })
    }

    @Test
    fun syncsDaysAndSavedMeals() = run {
        val c = jsonClient()
        val auth = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(AuthRequest("days@example.com", "password123"))
        }.body<AuthResponse>()
        val day = SyncChange(
            collection = "days", id = "2026-11-10", updatedAt = 5, deleted = false,
            data = buildJsonObject { put("id", "2026-11-10"); put("complete", true); put("note", "Ate out"); put("updatedAt", 5) },
        )
        val meal = SyncChange(
            collection = "meals", id = "m1", updatedAt = 6, deleted = false,
            data = buildJsonObject { put("id", "m1"); put("name", "Usual breakfast"); put("itemsJson", "[]"); put("defaultMeal", 0); put("updatedAt", 6) },
        )
        val push = c.post("/api/sync") {
            bearerAuth(auth.token); contentType(ContentType.Application.Json); setBody(SyncRequest(0, listOf(day, meal)))
        }
        assertEquals(HttpStatusCode.OK, push.status)
        // A second device pulls both records.
        val pulled = c.post("/api/sync") {
            bearerAuth(auth.token); contentType(ContentType.Application.Json); setBody(SyncRequest(0, emptyList()))
        }.body<SyncResponse>()
        assertEquals(setOf("days", "meals"), pulled.changes.map { it.collection }.toSet())
        // Unknown collections are still refused.
        val bad = c.post("/api/sync") {
            bearerAuth(auth.token); contentType(ContentType.Application.Json)
            setBody(SyncRequest(0, listOf(day.copy(collection = "nope"))))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
