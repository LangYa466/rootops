package dev.rootops.core.scripts

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import android.content.Context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import org.json.JSONArray
import org.json.JSONObject

import dev.rootops.core.api.ScheduleItem
import dev.rootops.core.api.Scheduler
import dev.rootops.core.api.ScriptStore
import dev.rootops.core.api.ShellRunner
import dev.rootops.core.util.JsonStore

/**
 * In-app script scheduler backed by a JSON file in the app's private storage.
 *
 * **Scope:** this is an *in-app* scheduler only. It ticks while the process is
 * alive (typically while the app is in the foreground or recently backgrounded)
 * and stores its state in `schedules.json`. It deliberately does **not** use
 * `AlarmManager`, `WorkManager` or a foreground service, so nothing runs once
 * Android kills the process; missed intervals are simply due again on the next
 * tick after the app is restarted.
 *
 * @param context application context used to locate the private files directory.
 * @param store the script store that owns the script bodies and execution.
 * @param shell shared shell runner; script execution itself is delegated to
 *        [ScriptStore.run], so this is only kept for construction symmetry with
 *        the rest of the container.
 */
class SchedulerImpl(
    context: Context,
    private val store: ScriptStore,
    @Suppress("unused") private val shell: ShellRunner,
) : Scheduler {

    private val disk = JsonStore(File(context.filesDir, "schedules.json"))

    private val mutex = Mutex()

    private val _schedules = MutableStateFlow<List<ScheduleItem>>(emptyList())
    override val schedules: StateFlow<List<ScheduleItem>> = _schedules.asStateFlow()

    /** Tail of the execution log, newest last, capped at [MAX_LOG_LINES]. */
    private val _recentRuns = MutableStateFlow<List<String>>(emptyList())
    val recentRuns: StateFlow<List<String>> = _recentRuns.asStateFlow()

    private val logLock = Any()
    /** schedule id -> token of the run currently executing it. */
    private val running = ConcurrentHashMap<Long, Long>()
    private val runTokens = AtomicLong()
    private val lifecycleLock = Any()

    /** Next id to hand out; always greater than every persisted id. */
    private var nextId: Long = 1L

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    init {
        val obj = disk.read()
        nextId = normalizeNextId(obj.optLong(KEY_NEXT_ID, 1L), obj.optJSONArray(KEY_ITEMS))
        _schedules.value = parseItems(obj.optJSONArray(KEY_ITEMS))
    }

    // ---------------------------------------------------------------- contract

    override suspend fun refresh() {
        mutex.withLock {
            val obj = disk.read()
            val items = parseItems(obj.optJSONArray(KEY_ITEMS))
            nextId = normalizeNextId(obj.optLong(KEY_NEXT_ID, 1L), obj.optJSONArray(KEY_ITEMS))
            _schedules.value = items
        }
    }

    override suspend fun save(item: ScheduleItem): Long = mutex.withLock {
        val current = _schedules.value
        val stored: ScheduleItem
        if (item.id <= 0L) {
            val id = nextId
            stored = item.copy(id = id)
            nextId = id + 1L
            _schedules.value = current + stored
        } else {
            val index = current.indexOfFirst { it.id == item.id }
            stored = item
            _schedules.value = if (index >= 0) {
                current.toMutableList().also { it[index] = stored }
            } else {
                current + stored
            }
        }
        nextId = maxOf(nextId, (_schedules.value.maxOfOrNull { it.id } ?: 0L) + 1L)
        persistLocked()
        stored.id
    }

    override suspend fun delete(id: Long) {
        mutex.withLock {
            val current = _schedules.value
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return@withLock
            _schedules.value = next
            running.remove(id)
            persistLocked()
        }
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            // Drop a scope left over from a loop that died unexpectedly.
            scope?.cancel()
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            job = newScope.launch { loop(newScope) }
        }
        appendLog("scheduler started")
    }

    override fun stop() {
        val wasRunning = synchronized(lifecycleLock) {
            val active = job != null
            job?.cancel()
            scope?.cancel()
            job = null
            scope = null
            running.clear()
            active
        }
        if (wasRunning) appendLog("scheduler stopped")
    }

    // ------------------------------------------------------------------- loop

    private suspend fun loop(scope: CoroutineScope) {
        while (currentCoroutineContext().isActive) {
            try {
                tick(scope)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                appendLog("scheduler error: ${t.message ?: t::class.simpleName}")
            }
            delay(TICK_MS)
        }
    }

    /** Launches every schedule that is enabled, due and not already running. */
    private fun tick(scope: CoroutineScope) {
        if (!scope.isActive) return
        val now = System.currentTimeMillis()
        val due = _schedules.value.filter { item ->
            item.enabled && isDue(item, now)
        }
        for (item in due) {
            // `putIfAbsent` is atomic: it both claims the slot and reports whether the
            // schedule is already executing, so a run can never overlap itself.
            val token = runTokens.incrementAndGet()
            if (running.putIfAbsent(item.id, token) != null) continue
            scope.launch {
                try {
                    execute(item)
                } finally {
                    running.remove(item.id, token)
                }
            }
        }
    }

    private fun isDue(item: ScheduleItem, now: Long): Boolean {
        val interval = item.intervalMinutes.coerceAtLeast(1).toLong() * 60_000L
        val last = item.lastRun ?: return true
        return now - last >= interval
    }

    private suspend fun execute(item: ScheduleItem) {
        val label = item.name.ifBlank { "script #${item.scriptId}" }
        if (store.get(item.scriptId) == null) {
            appendLog("[$label] skipped: script #${item.scriptId} not found")
            // Record the attempt so a dangling reference is retried only once
            // per interval instead of every tick.
            markRun(item.id, System.currentTimeMillis())
            return
        }
        appendLog("[$label] start")
        var failed: String? = null
        try {
            store.run(item.scriptId).collect { line -> appendLog("[$label] $line") }
        } catch (e: CancellationException) {
            appendLog("[$label] cancelled")
            throw e
        } catch (t: Throwable) {
            failed = t.message ?: t::class.simpleName ?: "unknown error"
        }
        val finishedAt = System.currentTimeMillis()
        markRun(item.id, finishedAt)
        if (failed != null) {
            appendLog("[$label] failed: $failed")
        } else {
            appendLog("[$label] done")
        }
    }

    /** Persists the new `lastRun` value of a single schedule. */
    private suspend fun markRun(id: Long, at: Long) {
        mutex.withLock {
            val current = _schedules.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@withLock
            val updated = current.toMutableList().also { it[index] = it[index].copy(lastRun = at) }
            _schedules.value = updated
            persistLocked()
        }
    }

    // ------------------------------------------------------------ persistence

    /** Must be called while holding [mutex]. */
    private fun persistLocked() {
        val array = JSONArray()
        for (item in _schedules.value) {
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("scriptId", item.scriptId)
                    put("name", item.name)
                    put("intervalMinutes", item.intervalMinutes)
                    put("enabled", item.enabled)
                    put("lastRun", item.lastRun ?: JSONObject.NULL)
                },
            )
        }
        disk.write(
            JSONObject().apply {
                put(KEY_NEXT_ID, nextId)
                put(KEY_ITEMS, array)
            },
        )
    }

    private fun parseItems(array: JSONArray?): List<ScheduleItem> {
        if (array == null) return emptyList()
        val items = ArrayList<ScheduleItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L)
            if (id <= 0L) continue
            items += ScheduleItem(
                id = id,
                scriptId = obj.optLong("scriptId", 0L),
                name = obj.optString("name", ""),
                intervalMinutes = obj.optInt("intervalMinutes", 60).coerceAtLeast(1),
                enabled = obj.optBoolean("enabled", true),
                lastRun = if (obj.has("lastRun") && !obj.isNull("lastRun")) obj.optLong("lastRun") else null,
            )
        }
        return items.sortedBy { it.id }
    }

    private fun normalizeNextId(stored: Long, array: JSONArray?): Long {
        var maxId = 0L
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                if (id > maxId) maxId = id
            }
        }
        return maxOf(stored, maxId + 1L, 1L)
    }

    // ------------------------------------------------------------------- logs

    private fun appendLog(line: String) {
        val stamped = "${TIME_FORMAT.format(LocalTime.now())} $line"
        synchronized(logLock) {
            val next = _recentRuns.value + stamped
            _recentRuns.value = if (next.size > MAX_LOG_LINES) {
                next.subList(next.size - MAX_LOG_LINES, next.size).toList()
            } else {
                next
            }
        }
    }

    private companion object {
        const val KEY_NEXT_ID = "nextId"
        const val KEY_ITEMS = "items"
        const val TICK_MS = 15_000L
        const val MAX_LOG_LINES = 200

        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    }
}
