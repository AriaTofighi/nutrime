package com.nutrime.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.nutrime.app.ui.theme.DesignTokens
import com.nutrime.app.ui.theme.ThemeMode

private val Context.dataStore by preferencesDataStore("settings")

data class ReminderSettings(
    /** Meals (0..3) with a "not logged yet" reminder. */
    val meals: Set<Int> = emptySet(),
    val weighIn: Boolean = false,
    /** Minutes after midnight. */
    val weighMinutes: Int = 7 * 60 + 30,
    /** ISO weekdays (1 = Monday). */
    val weighDays: Set<Int> = (1..7).toSet(),
    val checkIn: Boolean = false,
) {
    val any: Boolean get() = meals.isNotEmpty() || weighIn || checkIn
}

data class HealthSettings(
    val importWeights: Boolean,
    val importActivity: Boolean,
    val writeNutrition: Boolean,
    val lastRun: Long,
    val lastError: String?,
) {
    val any: Boolean get() = importWeights || importActivity || writeNutrition
}

data class Appearance(val mode: ThemeMode, val accent: DesignTokens.Accent)

data class Account(
    val email: String?,
    val token: String?,
    val userId: String?,
    val cursor: Long,
    val lastSync: Long,
    val lastError: String?,
) {
    val loggedIn: Boolean get() = !token.isNullOrBlank()
}

class SettingsStore(private val context: Context) {
    private object K {
        val server = stringPreferencesKey("server_url")
        val email = stringPreferencesKey("email")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val cursor = longPreferencesKey("sync_cursor")
        val lastSync = longPreferencesKey("last_sync")
        val lastError = stringPreferencesKey("last_error")
        val themeMode = stringPreferencesKey("theme_mode")
        val accent = stringPreferencesKey("accent")
        val hiddenSuggestionDay = stringPreferencesKey("hidden_suggestion_day")
        val trendsRange = stringPreferencesKey("trends_range")
        val goalReachedHidden = stringPreferencesKey("goal_reached_hidden")
        val mealReminders = stringPreferencesKey("meal_reminders")
        val weighReminder = booleanPreferencesKey("weigh_reminder")
        val weighTime = intPreferencesKey("weigh_time")
        val weighDays = stringPreferencesKey("weigh_days")
        val checkInReminder = booleanPreferencesKey("check_in_reminder")
        val remindersSent = stringPreferencesKey("reminders_sent")
        val hcWeights = booleanPreferencesKey("hc_weights")
        val hcActivity = booleanPreferencesKey("hc_activity")
        val hcWrite = booleanPreferencesKey("hc_write")
        val hcLastRun = longPreferencesKey("hc_last_run")
        val hcLastError = stringPreferencesKey("hc_last_error")
    }

    /** The last Trends range, encoded (TrendRange.encode). */
    val trendsRange: Flow<String?> = context.dataStore.data.map { it[K.trendsRange] }
    suspend fun setTrendsRange(v: String) { context.dataStore.edit { it[K.trendsRange] = v } }

    /** The goal weight for which "You reached your goal weight" was dismissed. */
    val goalReachedHidden: Flow<String?> = context.dataStore.data.map { it[K.goalReachedHidden] }
    suspend fun hideGoalReached(v: String) { context.dataStore.edit { it[K.goalReachedHidden] = v } }

    val reminders: Flow<ReminderSettings> = context.dataStore.data.map { p ->
        ReminderSettings(
            meals = (p[K.mealReminders] ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet(),
            weighIn = p[K.weighReminder] ?: false,
            weighMinutes = p[K.weighTime] ?: (7 * 60 + 30),
            weighDays = (p[K.weighDays] ?: "1,2,3,4,5,6,7").split(',').mapNotNull { it.toIntOrNull() }.toSet(),
            checkIn = p[K.checkInReminder] ?: false,
        )
    }

    suspend fun setReminders(r: ReminderSettings) {
        context.dataStore.edit {
            it[K.mealReminders] = r.meals.sorted().joinToString(",")
            it[K.weighReminder] = r.weighIn
            it[K.weighTime] = r.weighMinutes
            it[K.weighDays] = r.weighDays.sorted().joinToString(",")
            it[K.checkInReminder] = r.checkIn
        }
    }

    /** Reminders already sent, as "yyyy-mm-dd:kind" (today's only). */
    suspend fun remindersSent(): Set<String> = context.dataStore.data.first()[K.remindersSent]?.split('|')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    suspend fun markReminderSent(today: String, kind: String) {
        context.dataStore.edit { p ->
            val cur = p[K.remindersSent]?.split('|')?.filter { it.startsWith(today) }.orEmpty()
            p[K.remindersSent] = (cur + "$today:$kind").joinToString("|")
        }
    }

    val health: Flow<HealthSettings> = context.dataStore.data.map { p ->
        HealthSettings(p[K.hcWeights] ?: false, p[K.hcActivity] ?: false, p[K.hcWrite] ?: false, p[K.hcLastRun] ?: 0L, p[K.hcLastError])
    }

    suspend fun setHealth(weights: Boolean? = null, activity: Boolean? = null, write: Boolean? = null) {
        context.dataStore.edit { p ->
            weights?.let { p[K.hcWeights] = it }
            activity?.let { p[K.hcActivity] = it }
            write?.let { p[K.hcWrite] = it }
        }
    }

    suspend fun setHealthRun(time: Long, error: String?) {
        context.dataStore.edit { p ->
            p[K.hcLastRun] = time
            if (error == null) p.remove(K.hcLastError) else p[K.hcLastError] = error
        }
    }

    val appearance: Flow<Appearance> = context.dataStore.data.map { p ->
        Appearance(
            mode = ThemeMode.entries.firstOrNull { it.name == p[K.themeMode] } ?: ThemeMode.SYSTEM,
            accent = DesignTokens.Accent.entries.firstOrNull { it.name == p[K.accent] } ?: DesignTokens.Accent.Default,
        )
    }

    suspend fun setAppearance(mode: ThemeMode? = null, accent: DesignTokens.Accent? = null) {
        context.dataStore.edit {
            mode?.let { m -> it[K.themeMode] = m.name }
            accent?.let { a -> it[K.accent] = a.name }
        }
    }

    /** The diary day (yyyy-mm-dd) on which the food suggestion was dismissed, if any. */
    val hiddenSuggestionDay: Flow<String?> = context.dataStore.data.map { it[K.hiddenSuggestionDay] }

    suspend fun hideSuggestion(day: String) {
        context.dataStore.edit { it[K.hiddenSuggestionDay] = day }
    }

    val account: Flow<Account> = context.dataStore.data.map { p ->
        Account(p[K.email], p[K.token], p[K.userId], p[K.cursor] ?: 0L, p[K.lastSync] ?: 0L, p[K.lastError])
    }

    suspend fun current(): Account = account.first()

    suspend fun setLogin(serverUrl: String, email: String, token: String, userId: String) {
        context.dataStore.edit {
            val sameUser = it[K.userId] == userId && it[K.server] == serverUrl
            it[K.server] = serverUrl
            it[K.email] = email
            it[K.token] = token
            it[K.userId] = userId
            if (!sameUser) it[K.cursor] = 0L
            it.remove(K.lastError)
        }
    }

    suspend fun logout() {
        context.dataStore.edit {
            it.remove(K.token)
            it.remove(K.lastError)
        }
    }

    suspend fun setSynced(cursor: Long, time: Long) {
        context.dataStore.edit {
            it[K.cursor] = cursor
            it[K.lastSync] = time
            it.remove(K.lastError)
        }
    }

    suspend fun setError(message: String?) {
        context.dataStore.edit { if (message == null) it.remove(K.lastError) else it[K.lastError] = message }
    }
}
