package com.nutrime.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** Server settings that an administrator changes at run time. A missing row uses the environment value. */
object ServerSettings : Table("server_settings") {
    val key = varchar("key", 64)
    val value = text("value")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(key)
}

val THINKING_LEVELS = listOf("minimal", "low", "medium", "high")

/** The assistant settings in effect. Null fields in [AiSettingsPatch] keep the environment value. */
@Serializable
data class AiSettings(
    val model: String,
    /** Null: the server sends no thinking level, so the model uses its own default. */
    val thinkingLevel: String? = null,
    val dailyMessages: Int,
    /** Web nutrition lookups (Gemini with Google Search) per user per rolling 24 hours; 0 turns them off. */
    val webLookupDailyLimit: Int,
)

/** What the administrator stored. A null field uses the environment value. */
@Serializable
data class AiSettingsPatch(
    val model: String? = null,
    /** "default" stores "the model's default" over an environment value; null uses the environment. */
    val thinkingLevel: String? = null,
    val dailyMessages: Int? = null,
    val webLookupDailyLimit: Int? = null,
)

@Serializable
data class AiSettingsView(
    val effective: AiSettings,
    val environment: AiSettings,
    val overrides: AiSettingsPatch,
    val apiKeySet: Boolean,
    val thinkingLevels: List<String> = THINKING_LEVELS,
    val updatedAt: Long? = null,
)

class AiSettingsStore(private val config: Config, private val db: Database) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val environment = AiSettings(config.geminiModel, config.geminiThinkingLevel, config.aiDailyLimit, config.webLookupDailyLimit)

    @Volatile private var cached: Pair<AiSettingsPatch, Long?>? = null

    private fun load(): Pair<AiSettingsPatch, Long?> = cached ?: transaction(db) {
        ServerSettings.selectAll().where { ServerSettings.key eq KEY }.singleOrNull()
            ?.let { runCatching { json.decodeFromString<AiSettingsPatch>(it[ServerSettings.value]) }.getOrNull()?.to(it[ServerSettings.updatedAt]) }
            ?: (AiSettingsPatch() to null)
    }.also { cached = it }

    val overrides: AiSettingsPatch get() = load().first

    fun current(): AiSettings {
        val o = overrides
        return AiSettings(
            model = o.model ?: environment.model,
            thinkingLevel = when (o.thinkingLevel) { null -> environment.thinkingLevel; DEFAULT -> null; else -> o.thinkingLevel },
            dailyMessages = o.dailyMessages ?: environment.dailyMessages,
            webLookupDailyLimit = o.webLookupDailyLimit ?: environment.webLookupDailyLimit,
        )
    }

    /** The Gemini `generationConfig` for assistant turns, or null to use the model's defaults. */
    fun generationConfig(): JsonObject? = current().thinkingLevel?.let { level ->
        buildJsonObject { putJsonObject("thinkingConfig") { put("thinkingLevel", level) } }
    }

    fun view() = AiSettingsView(current(), environment, overrides, config.geminiApiKey != null, updatedAt = load().second)

    fun save(patch: AiSettingsPatch) {
        val p = validated(patch)
        val now = System.currentTimeMillis()
        transaction(db) {
            if (p == AiSettingsPatch()) ServerSettings.deleteWhere { key eq KEY }
            else ServerSettings.upsert {
                it[key] = KEY
                it[value] = json.encodeToString(AiSettingsPatch.serializer(), p)
                it[updatedAt] = now
            }
        }
        cached = null
    }

    private fun validated(p: AiSettingsPatch): AiSettingsPatch {
        fun bad(msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, msg)
        val model = p.model?.trim()?.takeIf { it.isNotEmpty() }
        if (model != null && !Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$").matches(model)) bad("Use a model ID such as gemini-4-hydrogen")
        val level = p.thinkingLevel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (level != null && level != DEFAULT && level !in THINKING_LEVELS) bad("Thinking level must be default, ${THINKING_LEVELS.joinToString()}")
        if (p.dailyMessages != null && p.dailyMessages !in 0..10_000) bad("Daily messages must be 0 to 10000")
        if (p.webLookupDailyLimit != null && p.webLookupDailyLimit !in 0..1_000) bad("Daily web lookups must be 0 to 1000")
        return AiSettingsPatch(model, level, p.dailyMessages, p.webLookupDailyLimit)
    }

    fun routes(route: Route, admin: AdminAccess) = with(route) {
        get("/api/admin/ai-settings") {
            admin.requireAdmin(call.uid())
            call.respond(view())
        }
        put("/api/admin/ai-settings") {
            admin.requireAdmin(call.uid())
            save(call.receive<AiSettingsPatch>())
            call.respond(view())
        }
        delete("/api/admin/ai-settings") {
            admin.requireAdmin(call.uid())
            save(AiSettingsPatch())
            call.respond(view())
        }
    }

    private fun ApplicationCall.uid(): String = principal<JWTPrincipal>()!!.payload.subject

    companion object {
        private const val KEY = "ai"
        const val DEFAULT = "default"
    }
}
