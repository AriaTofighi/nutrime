package com.nutrime.server

data class Config(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String?,
    val databasePassword: String?,
    val jwtSecret: String,
    /** Accept valid invitations. False pauses all new accounts; open registration is never allowed. */
    val allowRegistration: Boolean,
    val tokenDays: Long = 365,
    /** Gemini API key for the AI assistant; the assistant is off without one. */
    val geminiApiKey: String? = null,
    val geminiModel: String = "gemini-3.8-flash",
    val geminiBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    /** Optional Gemini thinking level ("minimal", "low", "medium", "high"); the model's default when unset. */
    val geminiThinkingLevel: String? = null,
    /** Assistant messages each user may send per rolling 24 hours. */
    val aiDailyLimit: Int = 150,
    /** USDA FoodData Central key for the shared catalog used by Android, web and the assistant. */
    val usdaApiKey: String = "DEMO_KEY",
    /** The bundled USDA food database (Android app's assets/foods.db). */
    val foodsDbPath: String? = null,
    /** Whether the shared catalog may call USDA and Open Food Facts online (off in tests). */
    val foodSearchOnline: Boolean = true,
    /** Emails (lowercase) of admins, who can browse the food database and curation candidates. */
    val adminEmails: Set<String> = emptySet(),
    /** Source notes and aliases for bundled NutriMe entries. */
    val curatedFoodsPath: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun v(k: String) = env[k]?.takeIf { it.isNotBlank() }
            val secret = v("JWT_SECRET")
                ?: error("JWT_SECRET must be set (use a long random string, e.g. `openssl rand -hex 32`)")
            require(secret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            return Config(
                port = v("PORT")?.toInt() ?: 8080,
                databaseUrl = v("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nutrime",
                databaseUser = v("DATABASE_USER"),
                databasePassword = v("DATABASE_PASSWORD"),
                jwtSecret = secret,
                allowRegistration = v("ALLOW_REGISTRATION")?.lowercase() !in setOf("false", "0", "no"),
                geminiApiKey = v("GEMINI_API_KEY"),
                geminiModel = v("GEMINI_MODEL") ?: "gemini-3.8-flash",
                geminiBaseUrl = v("GEMINI_BASE_URL") ?: "https://generativelanguage.googleapis.com/v1beta",
                geminiThinkingLevel = v("GEMINI_THINKING_LEVEL"),
                aiDailyLimit = v("AI_DAILY_MESSAGES")?.toInt() ?: 150,
                usdaApiKey = v("USDA_API_KEY")?.trim() ?: "DEMO_KEY",
                foodsDbPath = v("FOODS_DB_PATH") ?: "/app/foods.db",
                curatedFoodsPath = v("CURATED_FOODS_PATH") ?: "/app/curated-foods",
                adminEmails = v("ADMIN_EMAILS")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            )
        }
    }
}
