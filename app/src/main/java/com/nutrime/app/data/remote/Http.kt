package com.nutrime.app.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    const val USER_AGENT = "NutriMe/1.0 (Android; https://github.com/AriaTofighi/nutrime)"
}

class HttpException(val code: Int, message: String) : IOException(message)

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

suspend fun OkHttpClient.getString(request: Request): String {
    newCall(request).await().use { r ->
        val body = r.body?.string().orEmpty()
        // Keep the body out of the message: it's often an HTML error page, and messages reach the UI.
        if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
        return body
    }
}

/**
 * A short, user-facing reason an online food source failed, e.g. "USDA isn't responding right now".
 * Never shows raw response bodies or exception text.
 */
fun sourceErrorMessage(source: String, e: Throwable): String = when {
    e is HttpException && e.code == 429 -> "$source is busy. Try again in a minute."
    e is HttpException && (e.code == 401 || e.code == 403) -> "$source search isn't available (access was refused)."
    e is HttpException -> "$source isn't responding right now."
    e is java.net.SocketTimeoutException -> "$source took too long to respond."
    e is IOException -> "Couldn't reach $source. Check your connection."
    else -> "$source results couldn't be read."
}

// Small helpers for loosely-typed JSON
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
