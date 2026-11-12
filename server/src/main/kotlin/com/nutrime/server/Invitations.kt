package com.nutrime.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only a digest is stored. The random code is returned once, when it is created. */
object Invitations : Table("invitations") {
    val id = varchar("id", 36)
    val codeHash = varchar("code_hash", 64).uniqueIndex()
    val email = varchar("email", 320)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val usedAt = long("used_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class InvitationInfo(
    val id: String,
    val email: String,
    val createdAt: Long,
    val expiresAt: Long,
    val usedAt: Long? = null,
    val revokedAt: Long? = null,
)

@Serializable
data class CreatedInvitation(val invitation: InvitationInfo, val code: String)

@Serializable
private data class CreateInvitationRequest(val email: String, val days: Int = 7)

fun normalizedEmail(value: String): String {
    val email = value.trim().lowercase()
    if (email.length > 320 || !Regex("^[^@\\s]+@[^@\\s]+$").matches(email)) {
        throw ApiException(HttpStatusCode.BadRequest, "Invalid email address")
    }
    return email
}

class InvitationStore(private val db: Database) {
    fun create(email: String, days: Int = 7): CreatedInvitation {
        val recipient = normalizedEmail(email)
        if (days !in 1..30) throw ApiException(HttpStatusCode.BadRequest, "Choose an expiry from 1 to 30 days")
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        val now = System.currentTimeMillis()
        val info = InvitationInfo(UUID.randomUUID().toString(), recipient, now, now + TimeUnit.DAYS.toMillis(days.toLong()), null, null)
        transaction(db) {
            if (Users.selectAll().where { Users.email eq recipient }.any()) {
                throw ApiException(HttpStatusCode.Conflict, "This email already has an account")
            }
            Invitations.insert {
                it[id] = info.id
                it[codeHash] = digest(code)
                it[Invitations.email] = recipient
                it[createdAt] = now
                it[expiresAt] = info.expiresAt
            }
        }
        return CreatedInvitation(info, code)
    }

    fun list(): List<InvitationInfo> = transaction(db) {
        Invitations.selectAll().orderBy(Invitations.createdAt to SortOrder.DESC).limit(200).map {
            InvitationInfo(it[Invitations.id], it[Invitations.email], it[Invitations.createdAt],
                it[Invitations.expiresAt], it[Invitations.usedAt], it[Invitations.revokedAt])
        }
    }

    fun revoke(id: String) = transaction(db) {
        Invitations.update({ (Invitations.id eq id) and Invitations.usedAt.isNull() and Invitations.revokedAt.isNull() }) {
            it[revokedAt] = System.currentTimeMillis()
        }
        Unit
    }

    /** Call inside the account-creation transaction so a failed insert does not consume the code. */
    fun consume(code: String, email: String) {
        val now = System.currentTimeMillis()
        val changed = Invitations.update({
            (Invitations.codeHash eq digest(code.trim())) and (Invitations.email eq email) and
                (Invitations.expiresAt greater now) and Invitations.usedAt.isNull() and Invitations.revokedAt.isNull()
        }) { it[usedAt] = now }
        if (changed != 1) throw ApiException(HttpStatusCode.Forbidden, INVALID_INVITATION)
    }

    companion object {
        const val INVALID_INVITATION = "Enter a valid invitation code for this email. It may have expired or already been used."
        private val random = SecureRandom()
        private fun digest(code: String) = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class InvitationRoutes(config: Config, db: Database) {
    private val access = AdminAccess(config, db)
    private val store = InvitationStore(db)

    fun routes(route: Route) = with(route) {
        get("/api/admin/invitations") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val uid = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(withContext(Dispatchers.IO) { access.requireAdmin(uid); store.list() })
        }
        post("/api/admin/invitations") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val uid = call.principal<JWTPrincipal>()!!.payload.subject
            withContext(Dispatchers.IO) { access.requireAdmin(uid) }
            val request = call.receive<CreateInvitationRequest>()
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { store.create(request.email, request.days) })
        }
        post("/api/admin/invitations/{id}/revoke") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val uid = call.principal<JWTPrincipal>()!!.payload.subject
            withContext(Dispatchers.IO) { access.requireAdmin(uid); store.revoke(call.parameters["id"].orEmpty()) }
            call.respond(mapOf("status" to "ok"))
        }
    }
}
