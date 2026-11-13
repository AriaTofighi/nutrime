package com.nutrime.server

import at.favre.lib.crypto.bcrypt.BCrypt
import com.nutrime.server.food.FoodCatalog
import com.nutrime.server.food.foodRoutes
import com.nutrime.server.ai.Assistant
import com.nutrime.server.ai.LlmClient
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable data class AuthRequest(val email: String, val password: String, val invitationCode: String = "")
@Serializable data class AuthResponse(val token: String, val userId: String, val email: String)
@Serializable data class ErrorResponse(val error: String)
@Serializable data class SyncChange(
    val collection: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JsonElement? = null,
)
@Serializable data class SyncRequest(val since: Long = 0, val changes: List<SyncChange> = emptyList())
@Serializable data class SyncResponse(val cursor: Long, val changes: List<SyncChange>, val hasMore: Boolean = false)

class ApiException(val status: HttpStatusCode, message: String) : RuntimeException(message)

val COLLECTIONS = setOf("diary", "foods", "weights", "profile", "days", "meals")
const val PAGE_SIZE = 2000
const val MAX_CHANGES = 5000
const val MAX_RECORD_BYTES = 256 * 1024

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    if (args.firstOrNull() == "import-branded") {
        // Operator command: load USDA Branded Foods prepared by tools/import_usda_branded.py (see server/README.md).
        require(args.size == 2) { "Usage: nutrime-server import-branded FILE.jsonl.gz" }
        val file = java.io.File(args[1])
        require(file.isFile) { "File not found: ${args[1]}" }
        val result = com.nutrime.server.food.BrandedImport.run(connectDatabase(config), file)
        println("USDA Branded release ${result.release ?: "unknown"}: ${result.imported} foods imported, ${result.skipped} skipped")
        return
    }
    if (args.isNotEmpty()) {
        require(args[0] == "invite" && args.size in 2..3) { "Usage: nutrime-server invite EMAIL [DAYS]" }
        val days = args.getOrNull(2)?.toIntOrNull() ?: if (args.size == 2) 7 else error("DAYS must be an integer")
        val invite = InvitationStore(connectDatabase(config)).create(args[1], days)
        println("Invitation for ${invite.invitation.email}; expires ${Date(invite.invitation.expiresAt)}")
        println("Share this code privately. It is shown only once:")
        println(invite.code)
        return
    }
    val db = connectDatabase(config)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config, db) }.start(wait = true)
}

/** Simple in-memory limiter for auth endpoints: N attempts per window per client IP. */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val list = hits.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < now - windowMs }
            if (list.size >= max) return false
            list += now
            return true
        }
    }
}

fun Application.module(config: Config, db: Database, llm: LlmClient? = null) {
    val catalog = FoodCatalog(config, db)
    val assistant = Assistant(config, db, llm, catalog)
    val invitations = InvitationStore(db)
    val algorithm = Algorithm.HMAC256(config.jwtSecret)
    val issuer = "nutrime"
    val limiter = RateLimiter(max = 20, windowMs = TimeUnit.MINUTES.toMillis(5))

    install(ContentNegotiation) { json(json) }
    install(CallLogging) { filter { it.request.path().startsWith("/api") } }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorResponse(e.message ?: "Error")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
    install(Authentication) {
        jwt("auth") {
            realm = "nutrime"
            verifier(JWT.require(algorithm).withIssuer(issuer).build())
            validate { cred ->
                val uid = cred.payload.subject ?: return@validate null
                val exists = transaction(db) { Users.selectAll().where { Users.id eq uid }.any() }
                if (exists) JWTPrincipal(cred.payload) else null
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session")) }
        }
    }

    fun token(userId: String) = JWT.create()
        .withIssuer(issuer)
        .withSubject(userId)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(config.tokenDays)))
        .sign(algorithm)

    fun ApplicationCall.checkRate() {
        if (!limiter.allow(request.origin.remoteHost)) throw ApiException(HttpStatusCode.TooManyRequests, "Too many attempts, try again later")
    }

    fun validate(req: AuthRequest): Pair<String, String> {
        val email = normalizedEmail(req.email)
        if (req.password.length < 8) throw ApiException(HttpStatusCode.BadRequest, "Password must be at least 8 characters")
        if (req.password.length > 72) throw ApiException(HttpStatusCode.BadRequest, "Password must be at most 72 characters")
        return email to req.password
    }

    routing {
        get("/") { call.respond(mapOf("name" to "nutrime-server", "status" to "ok")) }
        get("/api/health") {
            call.respond(mapOf("status" to "ok", "registration" to config.allowRegistration.toString(), "invitationRequired" to "true"))
        }

        post("/api/auth/register") {
            call.checkRate()
            val request = call.receive<AuthRequest>()
            val (email, password) = validate(request)
            if (!config.allowRegistration) throw ApiException(HttpStatusCode.Forbidden, "Registration is disabled on this server")
            if (!Regex("[A-Za-z0-9_-]{43}").matches(request.invitationCode.trim())) {
                throw ApiException(HttpStatusCode.Forbidden, InvitationStore.INVALID_INVITATION)
            }
            val id = UUID.randomUUID().toString()
            val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
            transaction(db) {
                invitations.consume(request.invitationCode, email)
                if (Users.selectAll().where { Users.email eq email }.any()) throw ApiException(HttpStatusCode.Conflict, "An account with this email already exists")
                Users.insert {
                    it[Users.id] = id
                    it[Users.email] = email
                    it[passwordHash] = hash
                    it[createdAt] = System.currentTimeMillis()
                }
            }
            call.respond(HttpStatusCode.Created, AuthResponse(token(id), id, email))
        }

        post("/api/auth/login") {
            call.checkRate()
            val req = call.receive<AuthRequest>()
            val email = req.email.trim().lowercase()
            val row = transaction(db) { Users.selectAll().where { Users.email eq email }.singleOrNull() }
            val ok = row != null && BCrypt.verifyer().verify(req.password.toCharArray(), row[Users.passwordHash]).verified
            if (!ok) throw ApiException(HttpStatusCode.Unauthorized, "Wrong email or password")
            call.respond(AuthResponse(token(row!![Users.id]), row[Users.id], email))
        }

        authenticate("auth") {
            post("/api/sync") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receive<SyncRequest>()
                call.respond(sync(db, uid, req))
            }
            foodRoutes(catalog)
            AdminFoods(config, db).routes(this)
            InvitationRoutes(config, db).routes(this)
            assistant.routes(this)
            FoodCandidates(config, db).routes(this)
        }
    }
}

fun sync(db: Database, uid: String, req: SyncRequest): SyncResponse {
    if (req.changes.size > MAX_CHANGES) throw ApiException(HttpStatusCode.PayloadTooLarge, "Too many changes in one request")
    req.changes.forEach { c ->
        if (c.collection !in COLLECTIONS) throw ApiException(HttpStatusCode.BadRequest, "Unknown collection ${c.collection}")
        if (c.id.isBlank() || c.id.length > 200) throw ApiException(HttpStatusCode.BadRequest, "Invalid record id")
    }
    return transaction(db) {
        // Lock the user row so concurrent syncs get distinct, ordered sequence numbers.
        var seq = Users.selectAll().where { Users.id eq uid }.forUpdate().single()[Users.seq]
        for (c in req.changes) {
            val data = c.data?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "null"
            if (data.length > MAX_RECORD_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "Record too large")
            val existing = Records.selectAll().where {
                (Records.userId eq uid) and (Records.collection eq c.collection) and (Records.recordId eq c.id)
            }.singleOrNull()
            // Last writer wins, by the client's modification time.
            if (existing != null && existing[Records.updatedAt] > c.updatedAt) continue
            seq += 1
            Records.upsert {
                it[userId] = uid
                it[collection] = c.collection
                it[recordId] = c.id
                it[Records.data] = data
                it[updatedAt] = c.updatedAt
                it[deleted] = c.deleted
                it[Records.seq] = seq
            }
        }
        Users.update({ Users.id eq uid }) { it[Users.seq] = seq }

        val rows = Records.selectAll()
            .where { (Records.userId eq uid) and (Records.seq greater req.since) }
            .orderBy(Records.seq)
            .limit(PAGE_SIZE + 1)
            .toList()
        val page = rows.take(PAGE_SIZE)
        SyncResponse(
            cursor = page.lastOrNull()?.get(Records.seq) ?: maxOf(req.since, 0),
            changes = page.map { r ->
                SyncChange(
                    collection = r[Records.collection],
                    id = r[Records.recordId],
                    updatedAt = r[Records.updatedAt],
                    deleted = r[Records.deleted],
                    data = json.parseToJsonElement(r[Records.data]),
                )
            },
            hasMore = rows.size > PAGE_SIZE,
        )
    }
}
