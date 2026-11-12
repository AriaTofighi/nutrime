package com.nutrime.server.ai

import com.nutrime.server.AiConversations
import com.nutrime.server.AiMessages
import com.nutrime.server.AiProposals
import com.nutrime.server.ApiException
import com.nutrime.server.Config
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.http.CacheControl
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

@Serializable data class ChatImage(val mimeType: String, val data: String)
@Serializable data class ChatRequest(
    val conversationId: String? = null,
    val text: String = "",
    val images: List<ChatImage> = emptyList(),
    /** The user's local date (YYYY-MM-DD), so "today" matches their diary. */
    val today: String? = null,
    /** The user's local time (HH:mm), to guess the meal. */
    val localTime: String? = null,
    /** The app's region (ISO code, e.g. "CA"), to prefer products sold there. */
    val country: String? = null,
)
@Serializable data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)
@Serializable data class AiStatus(val enabled: Boolean, val model: String, val dailyLimit: Int, val usedToday: Int)

private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
private const val MAX_IMAGES = 4
private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
private const val MAX_TEXT = 4000

class Assistant(private val config: Config, private val db: Database, llm: LlmClient?, catalog: com.nutrime.server.food.FoodCatalog = com.nutrime.server.food.FoodCatalog(config, db)) {
    private val log = LoggerFactory.getLogger(Assistant::class.java)
    private val llm: LlmClient? = llm ?: config.geminiApiKey?.let { GeminiClient(it, config.geminiModel, config.geminiBaseUrl) }
    private val foods = FoodSearch(config.foodsDbPath, config.foodSearchOnline, catalog)
    private val proposals = ProposalStore(db)
    private val admin = com.nutrime.server.AdminAccess(config, db)
    private val generationConfig: JsonObject? = config.geminiThinkingLevel?.let { level ->
        buildJsonObject { putJsonObject("thinkingConfig") { put("thinkingLevel", level) } }
    }

    private fun usedToday(uid: String): Int = transaction(db) {
        val since = System.currentTimeMillis() - 24 * 3600 * 1000L
        AiMessages.selectAll().where { (AiMessages.userId eq uid) and (AiMessages.kind eq "user") and (AiMessages.createdAt greater since) }.count().toInt()
    }

    private fun ownConversation(uid: String, id: String) = transaction(db) {
        AiConversations.selectAll().where { (AiConversations.id eq id) and (AiConversations.userId eq uid) }.singleOrNull()
    } ?: throw ApiException(HttpStatusCode.NotFound, "Conversation not found")

    private fun messages(conversationId: String): List<StoredMessage> = transaction(db) {
        AiMessages.selectAll().where { AiMessages.conversationId eq conversationId }.orderBy(AiMessages.id).map {
            StoredMessage(it[AiMessages.kind], recordJson.parseToJsonElement(it[AiMessages.content]) as JsonObject)
        }
    }

    private fun append(uid: String, conversationId: String, kind: String, content: JsonObject) = transaction(db) {
        val now = System.currentTimeMillis()
        AiMessages.insert {
            it[AiMessages.conversationId] = conversationId
            it[userId] = uid
            it[AiMessages.kind] = kind
            it[AiMessages.content] = content.toString()
            it[createdAt] = now
        }
        AiConversations.update({ AiConversations.id eq conversationId }) { it[updatedAt] = now }
    }

    private fun today(s: String?): String = s?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() } ?: LocalDate.now().toString()

    fun routes(route: Route) = with(route) {
        get("/api/ai/status") {
            val uid = call.uid()
            call.respond(AiStatus(llm != null, config.geminiModel, config.aiDailyLimit, usedToday(uid)))
        }

        get("/api/ai/conversations") {
            val uid = call.uid()
            val list = transaction(db) {
                AiConversations.selectAll().where { AiConversations.userId eq uid }
                    .orderBy(AiConversations.updatedAt, SortOrder.DESC).limit(100)
                    .map { ConversationSummary(it[AiConversations.id], it[AiConversations.title], it[AiConversations.updatedAt]) }
            }
            call.respond(list)
        }

        get("/api/ai/conversations/{id}") {
            val uid = call.uid()
            val conv = ownConversation(uid, call.parameters["id"]!!)
            val msgs = messages(conv[AiConversations.id])
            val views = proposals.views(uid, proposalIds(msgs), today(call.request.queryParameters["today"]))
            call.respond(buildJsonObject {
                put("id", conv[AiConversations.id])
                put("title", conv[AiConversations.title])
                put("messages", displayMessages(msgs, views))
            })
        }

        delete("/api/ai/conversations/{id}") {
            val uid = call.uid()
            val id = ownConversation(uid, call.parameters["id"]!!)[AiConversations.id]
            transaction(db) {
                AiMessages.deleteWhere { conversationId eq id }
                AiProposals.deleteWhere { conversationId eq id }
                AiConversations.deleteWhere { AiConversations.id eq id }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/api/ai/proposals/{id}/apply") {
            val uid = call.uid()
            call.respond(proposals.apply(uid, call.parameters["id"]!!, today(call.request.queryParameters["today"])))
        }

        // Change one row of a pending card: remove it, change its amount or meal, or swap the food for another match.
        post("/api/ai/proposals/{id}/items/{index}") {
            val uid = call.uid()
            val id = call.parameters["id"]!!
            val index = call.parameters["index"]?.toIntOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid item")
            val edit = call.receive<ProposalItemEdit>()
            val actions = proposals.pendingActions(uid, id)
                ?: throw ApiException(HttpStatusCode.Conflict, "This card was already confirmed or dismissed")
            if (index !in actions.indices) throw ApiException(HttpStatusCode.BadRequest, "Invalid item")
            val changed = try {
                editAction(foods, UserData(db, uid), actions[index], edit)
            } catch (e: ToolError) {
                throw ApiException(HttpStatusCode.BadRequest, e.message ?: "Invalid change")
            }
            val next = actions.toMutableList()
            if (changed == null) {
                val removed = next.removeAt(index)
                // A new food and the entry that logs it go together.
                if (removed is Action.CreateFood) next.removeAll { it is Action.LogFood && it.foodId == removed.foodId }
            } else next[index] = changed
            if (!proposals.replace(uid, id, next)) throw ApiException(HttpStatusCode.Conflict, "This card was already confirmed or dismissed")
            call.respond(proposals.views(uid, listOf(id), today(call.request.queryParameters["today"])).getValue(id))
        }

        // Admin: every step of the admin's own conversations (model calls, function arguments and results), to see why
        // the assistant did something. Other people's conversations are never shown.
        get("/api/admin/ai/conversations") {
            val uid = call.uid()
            admin.requireAdmin(uid)
            call.respond(transaction(db) {
                AiConversations.selectAll().where { AiConversations.userId eq uid }
                    .orderBy(AiConversations.updatedAt, SortOrder.DESC).limit(50)
                    .map { ConversationSummary(it[AiConversations.id], it[AiConversations.title], it[AiConversations.updatedAt]) }
            })
        }

        get("/api/admin/ai/conversations/{id}/trace") {
            val uid = call.uid()
            admin.requireAdmin(uid)
            val conv = ownConversation(uid, call.parameters["id"]!!)
            val msgs = messages(conv[AiConversations.id])
            val views = proposals.views(uid, proposalIds(msgs), today(call.request.queryParameters["today"]))
            call.respond(buildJsonObject {
                put("id", conv[AiConversations.id])
                put("title", conv[AiConversations.title])
                put("steps", traceSteps(msgs))
                putJsonArray("proposals") { views.values.forEach { add(proposalJson(it)) } }
            })
        }

        post("/api/ai/proposals/{id}/undo") {
            val uid = call.uid()
            call.respond(proposals.undo(uid, call.parameters["id"]!!, today(call.request.queryParameters["today"])))
        }

        post("/api/ai/proposals/{id}/dismiss") {
            val uid = call.uid()
            call.respond(proposals.dismiss(uid, call.parameters["id"]!!, today(call.request.queryParameters["today"])))
        }

        post("/api/ai/chat") {
            val uid = call.uid()
            val llm = llm ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "The assistant isn't set up on this server (GEMINI_API_KEY is missing)")
            val req = call.receive<ChatRequest>()
            val text = req.text.trim()
            if (text.isEmpty() && req.images.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "Message is empty")
            if (text.length > MAX_TEXT) throw ApiException(HttpStatusCode.BadRequest, "Message is too long")
            if (req.images.size > MAX_IMAGES) throw ApiException(HttpStatusCode.BadRequest, "At most $MAX_IMAGES photos per message")
            req.images.forEach { img ->
                if (img.mimeType !in IMAGE_TYPES) throw ApiException(HttpStatusCode.BadRequest, "Unsupported image type ${img.mimeType}")
                val bytes = runCatching { Base64.getDecoder().decode(img.data).size }.getOrNull()
                    ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid image data")
                if (bytes > MAX_IMAGE_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, "Photo is too large")
            }
            if (usedToday(uid) >= config.aiDailyLimit) {
                throw ApiException(HttpStatusCode.TooManyRequests, "You've reached today's limit of ${config.aiDailyLimit} assistant messages")
            }
            val today = today(req.today)

            val conversationId = req.conversationId?.let { ownConversation(uid, it)[AiConversations.id] } ?: UUID.randomUUID().toString().also { id ->
                val title = text.ifEmpty { "Photo" }.lineSequence().first().take(60)
                transaction(db) {
                    val now = System.currentTimeMillis()
                    AiConversations.insert {
                        it[AiConversations.id] = id
                        it[userId] = uid
                        it[AiConversations.title] = title
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
            val title = ownConversation(uid, conversationId)[AiConversations.title]

            val userContent = buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    req.images.forEach { img -> add(buildJsonObject { putJsonObject("inlineData") { put("mimeType", img.mimeType); put("data", img.data) } }) }
                    if (text.isNotEmpty()) add(buildJsonObject { put("text", text) })
                }
            }
            append(uid, conversationId, "user", userContent)

            val stored = messages(conversationId)
            val statuses = proposals.views(uid, proposalIds(stored), today).mapValues { it.value.status }
            val history = historyForModel(withProposalStatus(stored, statuses))
            val data = UserData(db, uid)
            val ctx = ToolContext(data, foods, proposals, conversationId, today, com.nutrime.server.food.countryName(req.country))

            call.response.cacheControl(CacheControl.NoCache(null))
            // Tell nginx (and other proxies) not to buffer the stream.
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                suspend fun send(event: String, payload: JsonObject) {
                    write("event: $event\ndata: $payload\n\n")
                    flush()
                }
                send("conversation", buildJsonObject { put("id", conversationId); put("title", title) })
                try {
                    val agent = Agent(
                        llm, ctx, systemPrompt(data, today, req.localTime), generationConfig,
                        persist = { kind, content -> append(uid, conversationId, kind, content) },
                        emit = { e ->
                            when (e) {
                                is AgentEvent.Text -> send("text", buildJsonObject { put("delta", e.delta) })
                                is AgentEvent.Tool -> send("tool", buildJsonObject { put("name", e.name); put("label", e.label) })
                                is AgentEvent.Proposal -> send("proposal", buildJsonObject { put("proposal", proposalJson(e.view)) })
                            }
                        },
                    )
                    agent.run(history)
                    send("done", JsonObject(emptyMap()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Assistant turn failed", e)
                    val msg = when {
                        e is LlmException && e.retryable -> "The AI service is busy right now. Please try again in a moment."
                        e is LlmException -> "The AI service returned an error. Please try again."
                        else -> "Something went wrong. Please try again."
                    }
                    runCatching { send("error", buildJsonObject { put("message", msg) }) }
                }
            }
        }
    }
}

private fun ApplicationCall.uid(): String = principal<JWTPrincipal>()!!.payload.subject
