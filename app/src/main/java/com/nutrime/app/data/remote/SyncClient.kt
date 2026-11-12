package com.nutrime.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AuthRequest(val email: String, val password: String, val invitationCode: String = "")

@Serializable
data class AuthResponse(val token: String, val userId: String, val email: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class SyncChange(
    val collection: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JsonElement? = null,
)

@Serializable
data class SyncRequest(val since: Long, val changes: List<SyncChange>)

@Serializable
data class SyncResponse(val cursor: Long, val changes: List<SyncChange>, val hasMore: Boolean = false)

/** Client for the self-hosted NutriMe sync server (see /server). */
class SyncClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(baseUrl: String): Boolean = runCatching {
        Http.client.getString(Request.Builder().url(url(baseUrl, "api/health")).build())
        true
    }.getOrDefault(false)

    suspend fun login(baseUrl: String, email: String, password: String): AuthResponse =
        post(baseUrl, "api/auth/login", Http.json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password)), null)
            .let { Http.json.decodeFromString(AuthResponse.serializer(), it) }

    suspend fun register(baseUrl: String, email: String, password: String, invitationCode: String): AuthResponse =
        post(baseUrl, "api/auth/register", Http.json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password, invitationCode.trim())), null)
            .let { Http.json.decodeFromString(AuthResponse.serializer(), it) }

    suspend fun sync(baseUrl: String, token: String, request: SyncRequest): SyncResponse =
        post(baseUrl, "api/sync", Http.json.encodeToString(SyncRequest.serializer(), request), token)
            .let { Http.json.decodeFromString(SyncResponse.serializer(), it) }

    private suspend fun post(baseUrl: String, path: String, body: String, token: String?): String {
        val req = Request.Builder()
            .url(url(baseUrl, path))
            .post(body.toRequestBody(jsonType))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        Http.client.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { Http.json.decodeFromString(ErrorResponse.serializer(), text).error }
                    .getOrNull() ?: "Server error (HTTP ${r.code})"
                throw HttpException(r.code, msg)
            }
            return text
        }
    }

    private fun url(base: String, path: String) = base.trimEnd('/') + "/" + path
}
