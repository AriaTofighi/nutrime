package com.nutrime.app.sync

import android.content.Context
import com.nutrime.app.BuildConfig
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrime.app.NutriMeApplication
import com.nutrime.app.data.SettingsStore
import com.nutrime.app.data.local.AppDatabase
import com.nutrime.app.data.local.DayEntity
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.data.local.SavedMealEntity
import com.nutrime.app.data.local.FoodEntity
import com.nutrime.app.data.local.ProfileEntity
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.data.remote.Http
import com.nutrime.app.data.remote.HttpException
import com.nutrime.app.data.remote.SyncChange
import com.nutrime.app.data.remote.SyncClient
import com.nutrime.app.data.remote.SyncRequest
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.TimeUnit

/**
 * Local-first, last-writer-wins sync. Local changes are flagged dirty; a sync pushes all
 * dirty rows and pulls every server change newer than our cursor.
 */
class SyncManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val client: SyncClient,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private class Collection<T>(
        val name: String,
        val serializer: KSerializer<T>,
        val dirty: suspend () -> List<T>,
        val id: (T) -> String,
        val updatedAt: (T) -> Long,
        val deleted: (T) -> Boolean,
        val isDirty: (T) -> Boolean,
        val get: suspend (String) -> T?,
        val save: suspend (T) -> Unit,
        val markClean: suspend (String, Long) -> Unit,
        val markAllDirty: suspend () -> Unit,
    )

    private val collections: List<Collection<*>> = listOf(
        Collection(
            "diary", DiaryEntryEntity.serializer(), { db.diary().dirty() }, { it.id }, { it.updatedAt }, { it.deleted },
            { it.dirty }, { db.diary().get(it) }, { db.diary().upsert(it.copy(dirty = false)) },
            { id, t -> db.diary().markClean(id, t) }, { db.diary().markAllDirty() },
        ),
        Collection(
            "foods", FoodEntity.serializer(), { db.foods().dirty() }, { it.id }, { it.updatedAt }, { it.deleted },
            { it.dirty }, { db.foods().get(it) }, { db.foods().upsert(it.copy(dirty = false)) },
            { id, t -> db.foods().markClean(id, t) }, { db.foods().markAllDirty() },
        ),
        Collection(
            "weights", WeightEntity.serializer(), { db.weights().dirty() }, { it.id }, { it.updatedAt }, { it.deleted },
            { it.dirty }, { db.weights().get(it) }, { db.weights().upsert(it.copy(dirty = false)) },
            { id, t -> db.weights().markClean(id, t) }, { db.weights().markAllDirty() },
        ),
        Collection(
            "profile", ProfileEntity.serializer(), { db.profile().dirty() }, { it.id }, { it.updatedAt }, { it.deleted },
            { it.dirty }, { db.profile().get() }, { db.profile().upsert(it.copy(dirty = false)) },
            { id, t -> db.profile().markClean(id, t) }, { db.profile().markAllDirty() },
        ),
        Collection(
            "days", DayEntity.serializer(), { db.days().dirty() }, { it.id }, { it.updatedAt }, { it.deleted },
            { it.dirty }, { db.days().get(it) }, { db.days().upsert(it.copy(dirty = false)) },
            { id, t -> db.days().markClean(id, t) }, { db.days().markAllDirty() },
        ),
        Collection(
            "meals", SavedMealEntity.serializer(), { db.meals().dirty() }, { it.id }, { it.updatedAt }, { it.deleted },
            { it.dirty }, { db.meals().get(it) }, { db.meals().upsert(it.copy(dirty = false)) },
            { id, t -> db.meals().markClean(id, t) }, { db.meals().markAllDirty() },
        ),
    )

    /** Returns null on success or an error message. */
    suspend fun syncNow(): String? = mutex.withLock {
        val account = settings.current()
        if (!account.loggedIn) return "Not signed in"
        _running.value = true
        try {
            // On the first sync with an account, push everything we have locally.
            if (account.cursor == 0L) collections.forEach { it.markAllDirty() }

            val pushed = mutableListOf<Triple<Collection<*>, String, Long>>()
            val outgoing = mutableListOf<SyncChange>()
            collections.forEach { c -> collectDirty(c, outgoing, pushed) }

            var response = client.sync(BuildConfig.SYNC_SERVER_URL, account.token!!, SyncRequest(account.cursor, outgoing))
            db.withTransaction {
                pushed.forEach { (c, id, t) -> c.markClean(id, t) }
                response.changes.forEach { change -> apply(change) }
            }
            settings.setSynced(response.cursor, System.currentTimeMillis())
            // Large first syncs arrive in pages.
            while (response.hasMore) {
                response = client.sync(BuildConfig.SYNC_SERVER_URL, account.token, SyncRequest(response.cursor, emptyList()))
                db.withTransaction { response.changes.forEach { change -> apply(change) } }
                settings.setSynced(response.cursor, System.currentTimeMillis())
            }
            null
        } catch (e: HttpException) {
            val msg = if (e.code == 401) "Session expired — please sign in again" else e.message ?: "Sync failed"
            if (e.code == 401) settings.logout()
            settings.setError(msg)
            msg
        } catch (e: Exception) {
            val msg = "Sync failed: ${e.message ?: e.javaClass.simpleName}"
            settings.setError(msg)
            msg
        } finally {
            _running.value = false
        }
    }

    private suspend fun <T> collectDirty(
        c: Collection<T>,
        out: MutableList<SyncChange>,
        pushed: MutableList<Triple<Collection<*>, String, Long>>,
    ) {
        c.dirty().forEach { row ->
            val data: JsonElement = Http.json.encodeToJsonElement(c.serializer, row)
            out += SyncChange(c.name, c.id(row), c.updatedAt(row), c.deleted(row), data)
            pushed += Triple(c, c.id(row), c.updatedAt(row))
        }
    }

    private suspend fun apply(change: SyncChange) {
        @Suppress("UNCHECKED_CAST")
        val c = collections.firstOrNull { it.name == change.collection } as Collection<Any?>? ?: return
        val data = change.data ?: return
        val incoming = runCatching { Http.json.decodeFromJsonElement(c.serializer, data) }.getOrNull() ?: return
        val local = c.get(change.id)
        if (local != null && c.isDirty(local) && c.updatedAt(local) > change.updatedAt) return
        c.save(incoming)
    }

    fun schedulePeriodic() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build()
        runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req) }
    }

    /** Debounced background sync after a local edit. */
    fun requestSync(delaySeconds: Long = 5) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Never let a failure to schedule background sync break the local save that triggered it.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork("sync-once", ExistingWorkPolicy.REPLACE, req) }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NutriMeApplication
        if (!app.container.settings.current().loggedIn) return Result.success()
        val err = app.container.sync.syncNow()
        return if (err == null || err.startsWith("Session expired")) Result.success() else Result.retry()
    }
}
