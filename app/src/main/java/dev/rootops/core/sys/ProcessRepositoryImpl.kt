package dev.rootops.core.sys

import android.os.SystemClock
import dev.rootops.core.api.ProcInfo
import dev.rootops.core.api.ProcessRepository
import dev.rootops.core.api.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Kernel clock ticks per second (`USER_HZ`); every Android ABI uses 100. */
private const val CLK_TCK = 100.0

/** Maximum number of rows returned by [ProcessRepositoryImpl.topSnapshot]. */
private const val TOP_LIMIT = 60

/** Number of `maps` lines shown by [ProcessRepositoryImpl.detail]. */
private const val DETAIL_MAPS_LINES = 40

/** Upper bound for a single process' CPU usage (a process may saturate several cores). */
private const val MAX_CPU_PERCENT = 999f

/** Highest signal number accepted by [ProcessRepositoryImpl.kill]. */
private const val MAX_SIGNAL = 64

/** Bytes read from `/proc/<pid>/cmdline` before truncating. */
private const val CMDLINE_MAX_BYTES = 4_096

/** Number of `/proc/<pid>/stat` paths bundled into one root `cat` invocation. */
private const val STAT_BATCH_SIZE = 200

private const val PS_DETAILED = "ps -A -o PID,PPID,USER,STAT,RSS,NAME"
private const val PS_PLAIN = "ps -A"

/**
 * Reads the process table from `ps` and enriches it with data parsed straight out of `/proc`.
 *
 * The metadata columns (user, parent, state, RSS) come from `ps`, which is executed through the
 * root shell when one is available so that processes of other UIDs are visible too. CPU time and
 * thread counts are parsed from `/proc/<pid>/stat`; the app can read that file directly for its own
 * UID, and the remaining PIDs are fetched with a single batched `cat` through the root shell.
 *
 * CPU usage is sampled as a delta between two consecutive calls: the previous
 * `pid -> utime+stime` map and the wall clock of that sample are kept under [sampleMutex], and the
 * percentage is `100 * (dTicks / CLK_TCK) / (elapsedSec * cores)`. The very first sample therefore
 * reports `0%` for everything.
 *
 * Every public method swallows failures (except coroutine cancellation) and returns an empty or
 * neutral value instead, so the UI never has to guard against exceptions.
 */
class ProcessRepositoryImpl(private val shell: ShellRunner) : ProcessRepository {

    private val sampleMutex = Mutex()

    /** `pid -> utime+stime` (in clock ticks) captured by the previous successful sample. */
    private var previousTicks: Map<Int, Long> = emptyMap()

    /** [SystemClock.elapsedRealtime] of the previous successful sample, or `0` before the first one. */
    private var previousSampleMs: Long = 0L

    private val cpuCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Scratch buffer for `/proc/<pid>/cmdline`; only touched from [sampleProcesses] (mutex-held). */
    private val cmdlineBuffer = ByteArray(CMDLINE_MAX_BYTES)

    override suspend fun list(): List<ProcInfo> = withContext(Dispatchers.IO) {
        try {
            sampleProcesses().sortedBy { it.pid }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun topSnapshot(): List<ProcInfo> = withContext(Dispatchers.IO) {
        try {
            sampleProcesses()
                .sortedWith(
                    compareByDescending<ProcInfo> { it.cpuPercent }
                        .thenByDescending { it.rssKb }
                )
                .take(TOP_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun kill(pid: Int, signal: Int): Boolean = withContext(Dispatchers.IO) {
        // pid 1 is init and pid <= 0 is not a real process; never signal either of them.
        if (pid <= 1 || signal < 0 || signal > MAX_SIGNAL) return@withContext false
        val command = "kill -$signal $pid"
        val rooted = runShell(command, root = true)
        if (rooted?.ok == true) return@withContext true
        // Fall back to an unprivileged kill: it still works for processes owned by this UID.
        runShell(command, root = false)?.ok == true
    }

    override suspend fun detail(pid: Int): String = withContext(Dispatchers.IO) {
        if (pid <= 0) return@withContext "Invalid pid: $pid"
        try {
            buildDetail(pid)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            "Unable to read /proc/$pid: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sampling
    // ---------------------------------------------------------------------------------------

    /**
     * Takes one process sample, updating the delta-sampling baseline.
     *
     * The mutex serialises sampling so that two concurrent callers cannot interleave their
     * baseline updates (which would produce bogus deltas).
     */
    private suspend fun sampleProcesses(): List<ProcInfo> = sampleMutex.withLock<List<ProcInfo>> {
        val memTotalKb = readMemTotalKb()
        val rows = readPsRows()
        if (rows.isEmpty()) return@withLock emptyList()

        val stats = readStats(rows)
        val nowMs = SystemClock.elapsedRealtime()
        val baselineTicks = previousTicks
        val baselineMs = previousSampleMs
        val elapsedSec = (nowMs - baselineMs) / 1000.0
        val hasBaseline = baselineMs > 0L && elapsedSec > 0.0

        val currentTicks = HashMap<Int, Long>(stats.size)
        val seen = HashSet<Int>(rows.size)
        val result = ArrayList<ProcInfo>(rows.size)

        for (row in rows) {
            if (!seen.add(row.pid)) continue
            val stat = stats[row.pid]
            if (stat != null) currentTicks[row.pid] = stat.ticks

            val cpuPercent = if (hasBaseline && stat != null) {
                computeCpuPercent(baselineTicks[row.pid], stat.ticks, elapsedSec)
            } else {
                0f
            }
            val threads = stat?.threads ?: readStatusThreads(row.pid)
            val cmdline = readCmdline(row.pid).ifBlank { row.commandLine }
            val name = row.name.ifBlank {
                cmdline.substringBefore(' ').substringAfterLast('/')
            }.ifBlank { "pid ${row.pid}" }

            result.add(
                ProcInfo(
                    pid = row.pid,
                    ppid = row.ppid,
                    user = row.user,
                    stat = row.state,
                    cpuPercent = cpuPercent,
                    memPercent = computeMemPercent(row.rssKb, memTotalKb),
                    rssKb = row.rssKb,
                    threads = threads,
                    name = name,
                    cmdline = cmdline.ifBlank { name },
                )
            )
        }

        previousTicks = currentTicks
        previousSampleMs = nowMs
        result
    }

    /**
     * `100 * (dTicks / CLK_TCK) / (elapsedSec * cores)`, clamped to `0..999`.
     * Returns `0` when there is no usable baseline (new process, counter reset, pid reuse).
     */
    private fun computeCpuPercent(beforeTicks: Long?, afterTicks: Long, elapsedSec: Double): Float {
        if (beforeTicks == null || elapsedSec <= 0.0 || afterTicks < beforeTicks) return 0f
        val deltaTicks = afterTicks - beforeTicks
        val percent = 100.0 * (deltaTicks / CLK_TCK) / (elapsedSec * cpuCores)
        return percent.toFloat().coerceIn(0f, MAX_CPU_PERCENT)
    }

    private fun computeMemPercent(rssKb: Long, memTotalKb: Long): Float {
        if (rssKb <= 0L || memTotalKb <= 0L) return 0f
        return (100.0 * rssKb / memTotalKb).toFloat().coerceIn(0f, 100f)
    }

    // ---------------------------------------------------------------------------------------
    // ps parsing
    // ---------------------------------------------------------------------------------------

    private suspend fun readPsRows(): List<PsRow> {
        val preferRoot = rootAvailable()

        val detailed = runPs(PS_DETAILED, preferRoot)
        val parsed = parseDetailedPs(detailed)
        if (parsed.isNotEmpty()) return parsed

        // Older/odd `ps` implementations reject -o; parse the default column layout instead.
        val plain = runPs(PS_PLAIN, preferRoot)
        return parsePlainPs(plain)
    }

    private suspend fun runPs(command: String, preferRoot: Boolean): String {
        if (preferRoot) {
            val rooted = runShell(command, root = true)
            if (rooted != null && rooted.ok && rooted.out.isNotBlank()) return rooted.out
        }
        val plain = runShell(command, root = false)
        return if (plain != null && plain.ok) plain.out else ""
    }

    /** Parses `ps -A -o PID,PPID,USER,STAT,RSS,NAME` output. */
    private fun parseDetailedPs(output: String): List<PsRow> {
        val rows = ArrayList<PsRow>()
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val tokens = splitTokens(line, 6)
            if (tokens.size < 5) continue
            if (tokens[0].equals("PID", ignoreCase = true)) continue
            val pid = tokens[0].toIntOrNull() ?: continue
            if (pid <= 0) continue
            rows.add(
                PsRow(
                    pid = pid,
                    ppid = tokens[1].toIntOrNull() ?: 0,
                    user = tokens[2],
                    state = tokens[3].firstOrNull() ?: '?',
                    rssKb = tokens[4].toLongOrNull() ?: 0L,
                    name = tokens.getOrElse(5) { "" }.trim(),
                    commandLine = "",
                )
            )
        }
        return rows
    }

    /**
     * Parses the default `ps -A` layout (`USER PID PPID VSZ RSS WCHAN ADDR S TTY TIME CMD`),
     * using the header row to locate the columns when it is present.
     */
    private fun parsePlainPs(output: String): List<PsRow> {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()

        val header = splitTokens(lines[0], 32)
        val pidIdx = header.indexOfFirst { it.equals("PID", true) }
        val hasHeader = pidIdx >= 0
        val userIdx = header.indexOfFirst { it.equals("USER", true) }
        val ppidIdx = header.indexOfFirst { it.equals("PPID", true) }
        val statIdx = header.indexOfFirst { it == "S" || it.equals("STAT", true) }
        val rssIdx = header.indexOfFirst { it.equals("RSS", true) }
        val cmdIdx = header.indexOfFirst {
            it.equals("CMD", true) || it.equals("COMMAND", true) || it.equals("NAME", true)
        }

        val body = if (hasHeader) lines.drop(1) else lines
        val rows = ArrayList<PsRow>(body.size)
        for (line in body) {
            val limit = if (cmdIdx >= 0) cmdIdx + 1 else 32
            val tokens = splitTokens(line, limit)
            if (hasHeader && pidIdx < tokens.size) {
                val pid = tokens[pidIdx].toIntOrNull() ?: continue
                if (pid <= 0) continue
                val commandLine = tokens.getOrNull(cmdIdx).orEmpty()
                rows.add(
                    PsRow(
                        pid = pid,
                        ppid = tokens.getOrNull(ppidIdx)?.toIntOrNull() ?: 0,
                        user = tokens.getOrNull(userIdx).orEmpty(),
                        state = tokens.getOrNull(statIdx)?.firstOrNull() ?: '?',
                        rssKb = tokens.getOrNull(rssIdx)?.toLongOrNull() ?: 0L,
                        name = commandLine.substringBefore(' ').substringAfterLast('/'),
                        commandLine = commandLine,
                    )
                )
            } else {
                // Fallback positions for the classic `USER PID PPID VSZ RSS WCHAN ADDR S TTY TIME CMD`.
                if (tokens.size < 3) continue
                val pid = tokens[1].toIntOrNull() ?: continue
                if (pid <= 0) continue
                val commandLine = if (tokens.size > 9) {
                    tokens.subList(9, tokens.size).joinToString(" ")
                } else {
                    tokens.lastOrNull().orEmpty()
                }
                rows.add(
                    PsRow(
                        pid = pid,
                        ppid = tokens[2].toIntOrNull() ?: 0,
                        user = tokens[0],
                        state = tokens.getOrNull(6)?.firstOrNull() ?: '?',
                        rssKb = tokens.getOrNull(4)?.toLongOrNull() ?: 0L,
                        name = commandLine.substringBefore(' ').substringAfterLast('/'),
                        commandLine = commandLine,
                    )
                )
            }
        }
        return rows
    }

    // ---------------------------------------------------------------------------------------
    // /proc parsing
    // ---------------------------------------------------------------------------------------

    /**
     * Reads `utime+stime` and the thread count for every PID in [rows].
     *
     * Direct reads cover the processes of our own UID; everything else (invisible under
     * `hidepid`) is collected with a single root `cat`: an explicit path list for small sets, the
     * `/proc/[0-9]*` glob once more than [STAT_BATCH_SIZE] PIDs are unreadable.
     */
    private suspend fun readStats(rows: List<PsRow>): Map<Int, ProcStat> {
        val stats = HashMap<Int, ProcStat>(rows.size * 2)
        val missing = ArrayList<Int>()
        for (row in rows) {
            val stat = readStatFile(row.pid)
            if (stat != null) stats[row.pid] = stat else missing.add(row.pid)
        }
        if (missing.isEmpty() || !rootAvailable()) return stats

        // One root invocation covers every unreadable PID: either the glob for a large set, or an
        // explicit (shorter) path list. `cat` keeps going when a PID vanished mid-sample.
        val command = if (missing.size > STAT_BATCH_SIZE) {
            "cat /proc/[0-9]*/stat"
        } else {
            "cat " + missing.joinToString(" ") { "/proc/$it/stat" }
        }
        val result = runShell(command, root = true) ?: return stats
        if (result.out.isBlank()) return stats
        for (line in result.out.lineSequence()) {
            val stat = parseStatLine(line) ?: continue
            stats[stat.pid] = stat
        }
        return stats
    }

    private fun readStatFile(pid: Int): ProcStat? = try {
        parseStatLine(File("/proc/$pid/stat").readText())
    } catch (ignored: Throwable) {
        null
    }

    /**
     * Parses one `/proc/<pid>/stat` line.
     *
     * The command name (field 2) is the only field that may contain spaces, so parsing resumes
     * after the *last* `)`. From there the indices are: `utime` = 11, `stime` = 12,
     * `num_threads` = 17 (fields 14, 15 and 20 of proc(5)).
     */
    private fun parseStatLine(line: String): ProcStat? {
        val close = line.lastIndexOf(')')
        if (close <= 0) return null
        val pid = line.substringBefore(' ').toIntOrNull() ?: return null
        val rest = line.substring(close + 1).trim()
        if (rest.isEmpty()) return null
        val fields = rest.split(' ').filter { it.isNotEmpty() }
        if (fields.size < 22) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        val threads = fields[17].toIntOrNull() ?: 0
        return ProcStat(pid = pid, ticks = utime + stime, threads = threads)
    }

    /** Thread count from `/proc/<pid>/status`; used when the stat line is unavailable. */
    private fun readStatusThreads(pid: Int): Int = try {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
        }
    } catch (ignored: Throwable) {
        0
    }

    /** Full command line from `/proc/<pid>/cmdline`; empty when unreadable. */
    private fun readCmdline(pid: Int): String {
        val buffer = cmdlineBuffer
        val read = try {
            File("/proc/$pid/cmdline").inputStream().use { stream ->
                var total = 0
                while (total < buffer.size) {
                    val count = stream.read(buffer, total, buffer.size - total)
                    if (count <= 0) break
                    total += count
                }
                total
            }
        } catch (ignored: Throwable) {
            0
        }
        if (read <= 0) return ""
        return String(buffer, 0, read, Charsets.UTF_8).replace('\u0000', ' ').trim()
    }

    /** `MemTotal` in KiB, read once per sample. */
    private fun readMemTotalKb(): Long = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.let { line -> line.filter { it.isDigit() }.toLongOrNull() }
                ?: 0L
        }
    } catch (ignored: Throwable) {
        0L
    }

    // ---------------------------------------------------------------------------------------
    // Detail dump
    // ---------------------------------------------------------------------------------------

    private suspend fun buildDetail(pid: Int): String {
        val status = readDirect("/proc/$pid/status") ?: catAsRoot("/proc/$pid/status")
        val cmdline = (readDirect("/proc/$pid/cmdline") ?: catAsRoot("/proc/$pid/cmdline"))
            ?.replace('\u0000', ' ')
            ?.trim()
            .orEmpty()
        // maps is usually only readable through the root shell.
        val maps = (catAsRoot("/proc/$pid/maps") ?: readDirect("/proc/$pid/maps"))
            ?.lineSequence()
            ?.take(DETAIL_MAPS_LINES)
            ?.joinToString("\n")
            .orEmpty()

        val statusText = status?.trimEnd().orEmpty()
        if (statusText.isEmpty() && cmdline.isEmpty() && maps.isEmpty()) {
            return "No data available for pid $pid (process gone or permission denied)."
        }

        return buildString(4_096) {
            append("=== /proc/").append(pid).append("/status ===\n")
            append(statusText.ifEmpty { "(unavailable)" })
            append("\n\n=== /proc/").append(pid).append("/cmdline ===\n")
            append(cmdline.ifEmpty { "(unavailable)" })
            append("\n\n=== /proc/").append(pid).append("/maps (first ")
            append(DETAIL_MAPS_LINES).append(" lines) ===\n")
            append(maps.ifEmpty { "(unavailable)" })
        }
    }

    private fun readDirect(path: String): String? = try {
        File(path).readText()
    } catch (ignored: Throwable) {
        null
    }

    private suspend fun catAsRoot(path: String): String? {
        if (!rootAvailable()) return null
        val result = runShell("cat $path", root = true) ?: return null
        return if (result.ok && result.out.isNotEmpty()) result.out else null
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun runShell(command: String, root: Boolean) = try {
        shell.run(command, root = root)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        null
    }

    private fun rootAvailable(): Boolean = try {
        shell.rootAvailable.value
    } catch (ignored: Throwable) {
        false
    }

    /**
     * Whitespace split that keeps the remainder of the line in the final token, so a command
     * name/command line containing spaces stays intact.
     */
    private fun splitTokens(line: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val tokens = ArrayList<String>(limit)
        var index = 0
        while (index < line.length && tokens.size < limit) {
            while (index < line.length && line[index].isWhitespace()) index++
            if (index >= line.length) break
            if (tokens.size == limit - 1) {
                tokens.add(line.substring(index).trim())
                break
            }
            val start = index
            while (index < line.length && !line[index].isWhitespace()) index++
            tokens.add(line.substring(start, index))
        }
        return tokens
    }

    private data class PsRow(
        val pid: Int,
        val ppid: Int,
        val user: String,
        val state: Char,
        val rssKb: Long,
        val name: String,
        /** Command line reported by `ps` (only the default layout has one); empty otherwise. */
        val commandLine: String = "",
    )

    private data class ProcStat(
        val pid: Int,
        val ticks: Long,
        val threads: Int,
    )
}
