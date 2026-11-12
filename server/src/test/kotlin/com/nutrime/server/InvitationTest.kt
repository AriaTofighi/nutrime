package com.nutrime.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.test.*

/** Existing suites still exercise real registration, with fresh invitations in their isolated DBs. */
fun invitedAuthRequest(db: Database, email: String, password: String) =
    AuthRequest(email, password, InvitationStore(db).create(email).code)

class InvitationTest {
    private fun config() = Config(0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        null, null, "x".repeat(40), true, foodSearchOnline = false, adminEmails = setOf("owner@example.com"))

    @Test
    fun requiresMatchingEmailAndRejectsMissingUnknownExpiredRevokedAndUsedCodes() = testApplication {
        val cfg = config()
        val db = connectDatabase(cfg)
        val invitations = InvitationStore(db)
        application { module(cfg, db) }
        val c = createClient { install(ContentNegotiation) { json() } }
        suspend fun register(request: AuthRequest) = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(request)
        }
        val request = invitedAuthRequest(db, "friend@example.com", "password123")
        assertEquals(HttpStatusCode.Forbidden, register(request.copy(invitationCode = "")).status)
        assertEquals(HttpStatusCode.Forbidden, register(request.copy(invitationCode = "a".repeat(43))).status)
        assertEquals(HttpStatusCode.Forbidden, register(request.copy(email = "other@example.com")).status)
        assertEquals(HttpStatusCode.Created, register(request.copy(email = " Friend@Example.com ")).status)
        assertEquals(HttpStatusCode.Forbidden, register(request).status)

        val expired = invitations.create("expired@example.com")
        transaction(db) { Invitations.update({ Invitations.id eq expired.invitation.id }) { it[expiresAt] = 0 } }
        assertEquals(HttpStatusCode.Forbidden, register(AuthRequest("expired@example.com", "password123", expired.code)).status)
        val revoked = invitations.create("revoked@example.com")
        invitations.revoke(revoked.invitation.id)
        assertEquals(HttpStatusCode.Forbidden, register(AuthRequest("revoked@example.com", "password123", revoked.code)).status)
        assertEquals(1, transaction(db) { Users.selectAll().count().toInt() })
    }

    @Test
    fun failedAccountCreationRollsBackCodeConsumptionAndExistingAccountsCanStillLogin() = testApplication {
        val cfg = config()
        val db = connectDatabase(cfg)
        val invitations = InvitationStore(db)
        val first = invitedAuthRequest(db, "friend@example.com", "password123")
        val second = invitations.create(first.email)
        application { module(cfg, db) }
        val c = createClient { install(ContentNegotiation) { json() } }
        suspend fun register(request: AuthRequest) = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.Created, register(first).status)
        assertEquals(HttpStatusCode.Conflict, register(first.copy(invitationCode = second.code)).status)
        assertNull(invitations.list().single { it.id == second.invitation.id }.usedAt)
        val login = c.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody(AuthRequest(first.email, first.password))
        }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun adminOnlyManagementReturnsRawCodeOnceAndPersistsOnlyHash() = testApplication {
        val cfg = config()
        val db = connectDatabase(cfg)
        application { module(cfg, db) }
        val c = createClient { install(ContentNegotiation) { json() } }
        suspend fun account(email: String) = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody(invitedAuthRequest(db, email, "password123"))
        }.body<AuthResponse>()
        val owner = account("owner@example.com")
        val friend = account("friend@example.com")
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/admin/invitations").status)
        assertEquals(HttpStatusCode.Forbidden, c.get("/api/admin/invitations") { bearerAuth(friend.token) }.status)
        val body = """{"email":"next@example.com","days":7}"""
        assertEquals(HttpStatusCode.Forbidden, c.post("/api/admin/invitations") {
            bearerAuth(friend.token); contentType(ContentType.Application.Json); setBody(body)
        }.status)
        val created = c.post("/api/admin/invitations") {
            bearerAuth(owner.token); contentType(ContentType.Application.Json); setBody(body)
        }.body<CreatedInvitation>()
        assertEquals(43, created.code.length)
        val hash = transaction(db) { Invitations.selectAll().where { Invitations.id eq created.invitation.id }.single()[Invitations.codeHash] }
        assertEquals(64, hash.length)
        assertNotEquals(created.code, hash)
        val list = c.get("/api/admin/invitations") { bearerAuth(owner.token) }
        assertEquals("no-store", list.headers["Cache-Control"])
        assertTrue(list.body<List<InvitationInfo>>().any { it.id == created.invitation.id })
        assertEquals(HttpStatusCode.Forbidden, c.post("/api/admin/invitations/${created.invitation.id}/revoke") { bearerAuth(friend.token) }.status)
        assertEquals(HttpStatusCode.OK, c.post("/api/admin/invitations/${created.invitation.id}/revoke") { bearerAuth(owner.token) }.status)
        assertNotNull(InvitationStore(db).list().single { it.id == created.invitation.id }.revokedAt)
    }
}
