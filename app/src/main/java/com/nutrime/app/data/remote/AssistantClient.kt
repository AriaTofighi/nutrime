package com.nutrime.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

@Serializable data class AiStatus(val enabled: Boolean, val model: String, val dailyLimit: Int, val usedToday: Int)
@Serializable data class ConversationSummary(val id: String, val title: String, val updatedAt: Long)
@Serializable data class ProposalAlternative(val foodId: String, val name: String, val kcal: Double)
@Serializable data class ProposalItem(
    val title: String,
    val detail: String,
    val kind: String = "",
    val name: String? = null,
    val amount: String? = null,
    val meal: Int? = null,
    val kcal: Double? = null,
    val quantity: Double? = null,
    val servingLabel: String? = null,
    val source: String? = null,
    /** "substitute", "estimate", "match" or "differs". */
    val badge: String? = null,
    val note: String? = null,
    val alternatives: List<ProposalAlternative> = emptyList(),
    val editable: Boolean = false,
)
@Serializable data class Proposal(val id: String, val title: String, val items: List<ProposalItem>, val status: String, val summary: String? = null)
/** A change to one row of a pending card. */
@Serializable data class ProposalItemEdit(
    val remove: Boolean = false,
    val meal: Int? = null,
    val quantity: Double? = null,
    val grams: Double? = null,
    val foodId: String? = null,
)
@Serializable data class ChatImage(val mimeType: String, val data: String)
@Serializable data class ChatRequest(
    val conversationId: String?,
    val text: String,
    val images: List<ChatImage>,
    val today: String,
    val localTime: String,
    /** The phone's region (ISO code), so products sold there come first. */
    val country: String? = null,
)

/** One piece of a chat message. */
sealed interface ChatBlock {
    data class Text(val text: String) : ChatBlock
    /** [data] is base64. */
    data class Image(val mimeType: String, val data: String) : ChatBlock
    data class Tool(val name: String, val label: String) : ChatBlock
    data class ProposalCard(val proposal: Proposal) : ChatBlock
}

data class ChatMessage(val role: String, val blocks: List<ChatBlock>) {
    val isUser: Boolean get() = role == "user"
}

/** Server-sent events from POST /api/ai/chat. */
sealed interface ChatEvent {
    data class Conversation(val id: String, val title: String) : ChatEvent
    data class Text(val delta: String) : ChatEvent
    data class Tool(val name: String, val label: String) : ChatEvent
    data class ProposalEvent(val proposal: Proposal) : ChatEvent
    data class Error(val message: String) : ChatEvent
    data object Done : ChatEvent
}

/** Client for the server's AI assistant (see server/…/ai). */
class AssistantClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    /** The model can think and call tools for a while between streamed events. */
    private val streaming = Http.client.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()

    suspend fun status(base: String, token: String): AiStatus =
        Http.json.decodeFromString(AiStatus.serializer(), call(base, token, "api/ai/status"))

    suspend fun conversations(base: String, token: String): List<ConversationSummary> =
        Http.json.decodeFromString(ListSerializer(ConversationSummary.serializer()), call(base, token, "api/ai/conversations"))

    suspend fun conversation(base: String, token: String, id: String, today: String): List<ChatMessage> {
        val root = Http.json.parseToJsonElement(call(base, token, "api/ai/conversations/$id?today=$today")).jsonObject
        return (root["messages"] as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            ChatMessage(o["role"].str() ?: return@mapNotNull null, (o["blocks"] as? JsonArray).orEmpty().mapNotNull { parseBlock(it.obj()) })
        }
    }

    suspend fun delete(base: String, token: String, id: String) {
        call(base, token, "api/ai/conversations/$id", method = "DELETE")
    }

    suspend fun apply(base: String, token: String, proposalId: String, today: String): Proposal =
        Http.json.decodeFromString(Proposal.serializer(), call(base, token, "api/ai/proposals/$proposalId/apply?today=$today", method = "POST"))

    suspend fun editItem(base: String, token: String, proposalId: String, index: Int, edit: ProposalItemEdit, today: String): Proposal =
        Http.json.decodeFromString(Proposal.serializer(), call(
            base, token, "api/ai/proposals/$proposalId/items/$index?today=$today", method = "POST",
            body = Http.json.encodeToString(ProposalItemEdit.serializer(), edit),
        ))

    suspend fun undo(base: String, token: String, proposalId: String, today: String): Proposal =
        Http.json.decodeFromString(Proposal.serializer(), call(base, token, "api/ai/proposals/$proposalId/undo?today=$today", method = "POST"))

    suspend fun dismiss(base: String, token: String, proposalId: String, today: String): Proposal =
        Http.json.decodeFromString(Proposal.serializer(), call(base, token, "api/ai/proposals/$proposalId/dismiss?today=$today", method = "POST"))

    fun chat(base: String, token: String, request: ChatRequest): Flow<ChatEvent> = flow {
        val req = Request.Builder()
            .url(url(base, "api/ai/chat"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(Http.json.encodeToString(ChatRequest.serializer(), request).toRequestBody(jsonType))
            .build()
        val call = streaming.newCall(req)
        coroutineScope {
            // Reading the stream blocks a thread; cancel the call so stopping the reply takes effect at once.
            val canceller = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.await().use { r -> read(r) }
            } finally {
                canceller.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ChatEvent>.read(r: Response) {
        if (!r.isSuccessful) throw error(r)
        val source = r.body!!.source()
        var event = "message"
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) parseEvent(event, data.toString())?.let { emit(it) }
                    event = "message"
                    data.clear()
                }
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
            }
        }
    }

    private fun parseEvent(event: String, data: String): ChatEvent? {
        val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when (event) {
            "conversation" -> ChatEvent.Conversation(o["id"].str() ?: return null, o["title"].str().orEmpty())
            "text" -> ChatEvent.Text(o["delta"].str() ?: return null)
            "tool" -> ChatEvent.Tool(o["name"].str().orEmpty(), o["label"].str().orEmpty())
            "proposal" -> ChatEvent.ProposalEvent(Http.json.decodeFromJsonElement(Proposal.serializer(), o["proposal"] ?: return null))
            "error" -> ChatEvent.Error(o["message"].str() ?: "Something went wrong")
            "done" -> ChatEvent.Done
            else -> null
        }
    }

    private fun parseBlock(o: JsonObject?): ChatBlock? {
        if (o == null) return null
        return when (o["type"].str()) {
            "text" -> ChatBlock.Text(o["text"].str().orEmpty())
            "image" -> ChatBlock.Image(o["mimeType"].str() ?: "image/jpeg", o["data"].str() ?: return null)
            "tool" -> ChatBlock.Tool(o["name"].str().orEmpty(), o["label"].str().orEmpty())
            "proposal" -> runCatching { ChatBlock.ProposalCard(Http.json.decodeFromJsonElement(Proposal.serializer(), o["proposal"]!!)) }.getOrNull()
            else -> null
        }
    }

    private suspend fun call(base: String, token: String, path: String, method: String = "GET", body: String? = null): String {
        val req = Request.Builder()
            .url(url(base, path))
            .header("Authorization", "Bearer $token")
            .apply { if (method != "GET") method(method, body?.toRequestBody(jsonType) ?: ByteArray(0).toRequestBody(null)) }
            .build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw error(r)
            return r.body?.string().orEmpty()
        }
    }

    private fun error(r: Response): HttpException {
        val text = r.body?.string().orEmpty()
        val msg = runCatching { Http.json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
            ?: "Server error (HTTP ${r.code})"
        return HttpException(r.code, msg)
    }

    private fun url(base: String, path: String) = base.trimEnd('/') + "/" + path
}
