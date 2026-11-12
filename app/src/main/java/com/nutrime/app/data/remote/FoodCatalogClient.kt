package com.nutrime.app.data.remote

import android.util.Log
import com.nutrime.app.BuildConfig
import com.nutrime.app.data.SettingsStore
import com.nutrime.app.model.Food
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

@Serializable
data class CatalogResults(val foods: List<Food> = emptyList(), val errors: List<String> = emptyList(),
                          val retryAfterSeconds: Int? = null)

/** Authenticated food lookup. Provider credentials and limits belong to the server. */
class FoodCatalogClient(private val settings: SettingsStore) {
    suspend fun search(query: String): CatalogResults = get("search", "q", query)

    suspend fun barcode(code: String): Food? {
        val result = get("barcode/${code.filter(Char::isDigit)}")
        if (result.foods.isEmpty() && result.errors.isNotEmpty()) throw IOExceptionMessage(result.errors.joinToString(" "))
        return result.foods.firstOrNull()
    }

    private suspend fun get(path: String, key: String? = null, value: String? = null): CatalogResults {
        val token = settings.current().token?.takeIf { it.isNotBlank() }
            ?: throw IOExceptionMessage("Sign in to search online. Offline foods are still available.")
        val url = (BuildConfig.SYNC_SERVER_URL.trimEnd('/') + "/api/foods/$path").toHttpUrl().newBuilder()
            .apply {
                if (key != null) addQueryParameter(key, value)
                // Products sold in the phone's region come first.
                if (path == "search") java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.let { addQueryParameter("country", it) }
            }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        Http.client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w("FoodSearch", "NutriMe food lookup HTTP ${response.code}")
                val message = if (response.code == 401) "Sign in again to search online."
                    else runCatching { Http.json.decodeFromString(ErrorResponse.serializer(), body).error }.getOrNull()
                        ?: "Food search is unavailable (HTTP ${response.code}). Try again later."
                throw IOExceptionMessage(message)
            }
            return Http.json.decodeFromString(CatalogResults.serializer(), body)
        }
    }
}

class IOExceptionMessage(message: String) : java.io.IOException(message)

fun foodLookupError(e: Throwable): String = if (e is IOExceptionMessage) e.message.orEmpty()
    else sourceErrorMessage("NutriMe food search", e)
