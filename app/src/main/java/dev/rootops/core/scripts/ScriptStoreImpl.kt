package dev.rootops.core.scripts

import android.content.Context
import dev.rootops.core.api.ScriptItem
import dev.rootops.core.api.ScriptStore
import dev.rootops.core.api.ShellRunner
import dev.rootops.core.util.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * JSON backed implementation of [ScriptStore].
 *
 * The store keeps every script in `filesDir/scripts.json` as
 * `{"nextId":N,"items":[{id,name,body,created,lastRun,lastExit,favorite}]}`.
 * The in-memory snapshot is exposed as a [StateFlow] ordered by favourite first
 * and name second; every mutation is serialised through a [Mutex] and written
 * back atomically by [JsonStore].
 *
 * Execution runs the script body through a root shell handle so the exit code
 * can be recorded alongside the captured output. No method throws: I/O and
 * shell failures are reported as flow lines or swallowed into the last state.
 */
class ScriptStoreImpl(
    private val context: Context,
    private val shell: ShellRunner,
) : ScriptStore {

    private val store = JsonStore(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()

    private val _scripts = MutableStateFlow<List<ScriptItem>>(emptyList())
    override val scripts: StateFlow<List<ScriptItem>> = _scripts

    /** Id handed to the next inserted script; persisted with the items. */
    @Volatile
    private var nextId: Long = 1L

    init {
        loadLocked()
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    override suspend fun save(item: ScriptItem): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = nowSeconds()
            val current = _scripts.value
            if (item.id <= 0L) {
                val id = nextId
                nextId = id + 1L
                val created = if (item.created > 0L) item.created else now
                publishLocked(current + item.copy(id = id, created = created))
                persistLocked()
                id
            } else {
                val existing = current.firstOrNull { it.id == item.id }
                val created = when {
                    item.created > 0L -> item.created
                    existing != null -> existing.created
                    else -> now
                }
                publishLocked(current.filterNot { it.id == item.id } + item.copy(created = created))
                if (item.id >= nextId) nextId = item.id + 1L
                persistLocked()
                item.id
            }
        }
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _scripts.value
            if (current.none { it.id == id }) return@withLock
            _scripts.value = current.filterNot { it.id == id }
            persistLocked()
        }
    }

    override fun get(id: Long): ScriptItem? = _scripts.value.firstOrNull { it.id == id }

    override suspend fun run(id: Long): Flow<String> = channelFlow {
        val item = _scripts.value.firstOrNull { it.id == id }
        if (item == null) {
            send(LINE_NOT_FOUND)
            return@channelFlow
        }
        send("$LINE_RUNNING_PREFIX${item.name}")
        val exitCode = try {
            execute(item)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            send("$LINE_ERROR_PREFIX${t.message ?: t.javaClass.simpleName}")
            EXIT_FAILURE
        }
        send("$LINE_EXIT_PREFIX$exitCode")
        recordRun(id, exitCode)
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- execution

    /**
     * Opens a root shell for [item]'s body, forwards its output to the flow and
     * returns the process exit code.
     */
    private suspend fun ProducerScope<String>.execute(item: ScriptItem): Int {
        val handle = shell.open("sh -c ${shellQuote(item.body)}", root = true)
        val pump = launch(Dispatchers.IO) {
            try {
                handle.output.collect { line -> send(line) }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // A broken output stream must not fail the whole run.
            }
        }
        return try {
            handle.await()
        } finally {
            // The handle is gone once await() returns; drain what is still queued.
            if (withTimeoutOrNull(DRAIN_TIMEOUT_MS) { pump.join() } == null) {
                pump.cancel()
            }
            runCatching { handle.close() }
        }
    }

    /** Records the outcome of a run in memory and on disk. */
    private suspend fun recordRun(id: Long, exitCode: Int) {
        val now = nowSeconds()
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val current = _scripts.value
                    if (current.none { it.id == id }) return@withLock
                    val updated = current.map {
                        if (it.id == id) it.copy(lastRun = now, lastExit = exitCode) else it
                    }
                    _scripts.value = sortScripts(updated)
                    persistLocked()
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Losing the bookkeeping must not break the caller's collection.
        }
    }

    // ------------------------------------------------------------- persistence

    /** Reloads the file; seeds the examples when the store is empty. */
    private fun loadLocked() {
        val (storedNext, storedItems) = try {
            decode(store.read())
        } catch (_: Throwable) {
            1L to emptyList<ScriptItem>()
        }
        val items = if (storedItems.isEmpty()) SEED_SCRIPTS.mapIndexed { index, seed ->
            ScriptItem(
                id = storedNext + index,
                name = seed.first,
                body = seed.second,
                created = nowSeconds(),
                lastRun = null,
                lastExit = null,
                favorite = false,
            )
        } else {
            storedItems
        }
        nextId = maxOf(storedNext, (items.maxOfOrNull { it.id } ?: 0L) + 1L, 1L)
        _scripts.value = sortScripts(items)
        if (storedItems.isEmpty()) persistLocked()
    }

    private fun publishLocked(items: List<ScriptItem>) {
        _scripts.value = sortScripts(items)
    }

    private fun persistLocked() {
        try {
            val root = JSONObject()
            root.put(KEY_NEXT_ID, nextId)
            val array = JSONArray()
            _scripts.value.forEach { script ->
                array.put(
                    JSONObject().apply {
                        put(KEY_ID, script.id)
                        put(KEY_NAME, script.name)
                        put(KEY_BODY, script.body)
                        put(KEY_CREATED, script.created)
                        put(KEY_FAVORITE, script.favorite)
                        put(KEY_LAST_RUN, script.lastRun ?: JSONObject.NULL)
                        put(KEY_LAST_EXIT, script.lastExit ?: JSONObject.NULL)
                    }
                )
            }
            root.put(KEY_ITEMS, array)
            store.write(root)
        } catch (_: Throwable) {
            // Disk failures leave the in-memory state usable.
        }
    }

    private fun decode(root: JSONObject): Pair<Long, List<ScriptItem>> {
        val array = root.optJSONArray(KEY_ITEMS) ?: JSONArray()
        val items = ArrayList<ScriptItem>(array.length())
        var maxId = 0L
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optLong(KEY_ID, 0L)
            if (id <= 0L) continue
            val name = obj.optString(KEY_NAME, "").trim().ifBlank { "script-$id" }
            val body = obj.optString(KEY_BODY, "")
            val created = obj.optLong(KEY_CREATED, 0L).takeIf { it > 0L }
                ?: nowSeconds()
            val lastRun = if (obj.has(KEY_LAST_RUN) && !obj.isNull(KEY_LAST_RUN)) {
                obj.optLong(KEY_LAST_RUN, 0L).takeIf { it > 0L }
            } else {
                null
            }
            val lastExit = if (obj.has(KEY_LAST_EXIT) && !obj.isNull(KEY_LAST_EXIT)) {
                obj.optInt(KEY_LAST_EXIT, 0)
            } else {
                null
            }
            val favorite = obj.optBoolean(KEY_FAVORITE, false)
            items += ScriptItem(
                id = id,
                name = name,
                body = body,
                created = created,
                lastRun = lastRun,
                lastExit = lastExit,
                favorite = favorite,
            )
            if (id > maxId) maxId = id
        }
        val next = maxOf(root.optLong(KEY_NEXT_ID, 0L), maxId + 1L, 1L)
        return next to items.distinctBy { it.id }
    }

    // ------------------------------------------------------------------ helpers

    private fun sortScripts(items: List<ScriptItem>): List<ScriptItem> =
        items.sortedWith(
            compareBy<ScriptItem> { if (it.favorite) 0 else 1 }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )

    /** POSIX single-quote escaping so the body survives `sh -c` and `su -c`. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Wall-clock time in epoch seconds, the unit used by [ScriptItem]. */
    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000L

    private companion object {
        const val FILE_NAME = "scripts.json"

        const val KEY_NEXT_ID = "nextId"
        const val KEY_ITEMS = "items"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_BODY = "body"
        const val KEY_CREATED = "created"
        const val KEY_LAST_RUN = "lastRun"
        const val KEY_LAST_EXIT = "lastExit"
        const val KEY_FAVORITE = "favorite"

        const val LINE_RUNNING_PREFIX = "# running "
        const val LINE_EXIT_PREFIX = "# exit "
        const val LINE_ERROR_PREFIX = "# error "
        const val LINE_NOT_FOUND = "# script not found"

        const val EXIT_FAILURE = -1
        const val DRAIN_TIMEOUT_MS = 10_000L

        /** Example scripts installed on first run. */
        val SEED_SCRIPTS: List<Pair<String, String>> = listOf(
            "disk-usage" to "du -sh /data/* 2>/dev/null | sort -h | tail -20",
            "top-cpu" to "top -b -n 1 -m 15",
        )
    }
}
