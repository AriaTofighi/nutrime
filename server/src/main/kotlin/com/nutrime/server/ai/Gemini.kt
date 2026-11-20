package com.nutrime.server.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A streaming chat model. [stream] takes an Interactions API request (without `model`) and emits the data of
 * each server-sent event (`step.start`, `step.delta`, `step.stop`, `interaction.completed`, ...), with its `event_type`.
 */
fun interface LlmClient {
    fun stream(request: JsonObject): Flow<JsonObject>
}

/** Error codes in the event stream that a later try may not get. */
private val RETRYABLE_STREAM_ERRORS = setOf("gateway_timeout", "unavailable", "resource_exhausted", "internal", "deadline_exceeded")

class LlmException(message: String, val retryable: Boolean = false) : RuntimeException(message)

/** The Gemini Interactions API (`POST /interactions` with `stream: true`) over server-sent events. */
class GeminiClient(
    private val apiKey: String,
    /** Read for each request, so an administrator can change the model without a restart. */
    private val model: () -> String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmClient {
    constructor(apiKey: String, model: String, baseUrl: String = "https://generativelanguage.googleapis.com/v1beta") : this(apiKey, { model }, baseUrl)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(request: JsonObject): Flow<JsonObject> = flow {
        val body = JsonObject(mapOf("model" to JsonPrimitive(model())) + request).toString()
        var attempt = 0
        while (true) {
            attempt++
            val req = HttpRequest.newBuilder(URI("$baseUrl/interactions"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofLines())
            if (res.statusCode() != 200) {
                val text = res.body().use { lines -> lines.toList().joinToString("\n") }
                val msg = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                }.getOrNull() ?: text.take(300)
                val retryable = res.statusCode() == 429 || res.statusCode() >= 500
                // Overloaded or rate limited before anything streamed: one quick retry.
                if (retryable && attempt < 2) {
                    delay(1500)
                    continue
                }
                throw LlmException("Gemini error ${res.statusCode()}: $msg", retryable)
            }
            res.body().use { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val event = json.parseToJsonElement(data).jsonObject
                    (event["error"] as? JsonObject)?.let { error ->
                        val code = (error["code"] as? JsonPrimitive)?.content.orEmpty()
                        val msg = (error["message"] as? JsonPrimitive)?.content ?: code
                        throw LlmException("Gemini error $code: $msg", code in RETRYABLE_STREAM_ERRORS)
                    }
                    emit(event)
                }
            }
            return@flow
        }
    }.flowOn(Dispatchers.IO)
}
