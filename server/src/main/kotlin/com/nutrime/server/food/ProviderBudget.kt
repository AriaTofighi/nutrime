package com.nutrime.server.food

import com.nutrime.server.ai.recordJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class ProviderFailure(val reason: String, val retryAfter: Int = 60) : RuntimeException(reason)

/** Persistent rolling windows: shared by Android, web, AI, replicas and restarts. */
class ProviderBudget(private val db: Database, private val demoKey: Boolean) {
    fun reserve(bucket: String) = transaction(db) {
        ProviderBudgets.insertIgnore { it[key] = bucket }
        val row = ProviderBudgets.selectAll().where { ProviderBudgets.key eq bucket }.forUpdate().single()
        val now = System.currentTimeMillis()
        if (row[ProviderBudgets.blockedUntil] > now) throw ProviderFailure(
            "Provider requests are paused. Use saved results for now.", CatalogStore.seconds(row[ProviderBudgets.blockedUntil] - now))
        val windows = when (bucket) {
            "off-search" -> listOf(8 to 60_000L)
            "off-product" -> listOf(12 to 60_000L)
            "usda" -> if (demoKey) listOf(30 to 3600_000L, 50 to 86_400_000L) else listOf(900 to 3600_000L)
            else -> error("Unknown provider budget")
        }
        val history = recordJson.decodeFromString<List<Long>>(row[ProviderBudgets.hits])
            .filter { it > now - windows.maxOf { w -> w.second } }
        val waits = windows.mapNotNull { (max, window) ->
            val recent = history.filter { it > now - window }
            if (recent.size < max) null else recent[recent.size - max] + window - now
        }
        if (waits.isNotEmpty()) throw ProviderFailure("Provider request limit reached. Use saved results for now.",
            CatalogStore.seconds(waits.max()))
        ProviderBudgets.update({ ProviderBudgets.key eq bucket }) { it[hits] = recordJson.encodeToString(history + now) }
    }

    fun pause(bucket: String, seconds: Int) = transaction(db) {
        ProviderBudgets.insertIgnore { it[key] = bucket }
        val row = ProviderBudgets.selectAll().where { ProviderBudgets.key eq bucket }.forUpdate().single()
        ProviderBudgets.update({ ProviderBudgets.key eq bucket }) {
            it[blockedUntil] = maxOf(row[ProviderBudgets.blockedUntil], System.currentTimeMillis() + seconds * 1000L)
        }
    }
}
