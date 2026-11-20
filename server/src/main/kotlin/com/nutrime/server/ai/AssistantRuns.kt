package com.nutrime.server.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One assistant turn that runs on the server, apart from the HTTP request that started it.
 * Closing the app or losing the connection doesn't stop it: the client reconnects and
 * replays the events it missed ([follow] with the number it already has).
 */
class AssistantRun(val userId: String, val conversationId: String, val baseCount: Int) {
    private val lock = Any()
    private val events = ArrayList<Pair<String, JsonObject>>()
    /** Goes up on every change, so followers wake up. */
    private val version = MutableStateFlow(0L)
    @Volatile var finishedAt: Long? = null
        private set
    internal var job: Job? = null

    val running get() = finishedAt == null

    fun add(event: String, payload: JsonObject) {
        synchronized(lock) {
            if (finishedAt != null) return
            events.add(event to payload)
            version.value++
        }
    }

    fun finish() {
        synchronized(lock) {
            if (finishedAt != null) return
            finishedAt = System.currentTimeMillis()
            version.value++
        }
    }

    /** Sends the events after the first [after], then the new ones as they come, until the run ends. */
    suspend fun follow(after: Int, send: suspend (String, JsonObject) -> Unit) {
        var next = after.coerceAtLeast(0)
        while (true) {
            val v: Long
            val batch: List<Pair<String, JsonObject>>
            val done: Boolean
            synchronized(lock) {
                v = version.value
                batch = if (next < events.size) events.subList(next, events.size).toList() else emptyList()
                done = finishedAt != null
            }
            for ((event, payload) in batch) send(event, payload)
            next += batch.size
            if (done) return
            version.first { it != v }
        }
    }
}

/** The assistant turns in progress (and the ones that ended a short time ago, for reconnects). */
class AssistantRuns(private val keepMillis: Long = 5 * 60_000L) {
    private val runs = ConcurrentHashMap<String, AssistantRun>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun prune() {
        val cutoff = System.currentTimeMillis() - keepMillis
        runs.values.removeIf { r -> r.finishedAt?.let { it < cutoff } == true }
    }

    fun get(conversationId: String): AssistantRun? {
        prune()
        return runs[conversationId]
    }

    fun active(conversationId: String): AssistantRun? = get(conversationId)?.takeIf { it.running }

    /**
     * Starts [block] in the background, or returns null when this conversation already has a turn running.
     * The run always ends with [AssistantRun.finish], also when it fails or is stopped.
     */
    fun start(run: AssistantRun, block: suspend (AssistantRun) -> Unit): AssistantRun? {
        prune()
        var started = false
        runs.compute(run.conversationId) { _, old -> if (old != null && old.running) old else run.also { started = true } }
        if (!started) return null
        run.job = scope.launch {
            try {
                block(run)
            } finally {
                run.finish()
            }
        }
        return run
    }

    /** Stops the running turn of this conversation. Returns false when nothing was running. */
    fun stop(conversationId: String): Boolean {
        val run = active(conversationId) ?: return false
        run.job?.cancel()
        return true
    }
}
