package dev.rootops.core.sys

import dev.rootops.core.api.LogLine
import dev.rootops.core.api.LogRepository
import dev.rootops.core.api.ShellRunner
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `logcat` backend: live streaming, buffer clearing and plain-text export.
 *
 * [stream] spawns `logcat -v threadtime` through `su` whenever root is available and falls
 * back to the unprivileged binary otherwise (and again if a root process exits without
 * producing a single line, which happens when the `su` policy denies the command). The
 * process is destroyed as soon as the collector goes away.
 *
 * Parsing is deliberately forgiving: a line that does not match the threadtime header is
 * appended to the message of the line before it, so multi-line entries (stack traces,
 * `dumpsys` dumps) arrive as one [LogLine] with embedded newlines. A line is therefore only
 * emitted once the *next* header has been seen; [ThreadtimeParser.flush] releases the
 * trailing one when the stream ends.
 *
 * No method throws: failures surface as an empty flow, a `false` or a silent no-op.
 */
class LogRepositoryImpl(
    private val shell: ShellRunner,
) : LogRepository {

    /** Monotonic identifier shared by every line this repository has produced so far. */
    private val seq = AtomicLong(0L)

    // ---------------------------------------------------------------- LogRepository

    override fun stream(level: Char, tag: String?, pid: Int?): Flow<LogLine> = callbackFlow {
        val parser = ThreadtimeParser(seq)
        val filter = LineFilter(level, tag, pid)
        var current: Process? = null

        val pump = launch(Dispatchers.IO) {
            var useRoot = shell.rootAvailable.value
            var attempt = 0
            try {
                while (isActive && attempt < MAX_ATTEMPTS) {
                    attempt++
                    val process = runCatching { spawn(useRoot) }.getOrNull()
                    if (process == null) {
                        if (useRoot) {
                            useRoot = false
                            continue
                        }
                        break
                    }
                    current = process
                    val drain = launch(Dispatchers.IO) { drainQuietly(process.errorStream) }
                    val emitted = try {
                        readLogcat(process, parser, filter) { send(it) }
                    } finally {
                        drain.cancel()
                        closeQuietly(process.errorStream)
                    }
                    val code = exitCodeOf(process)
                    destroyQuietly(process)
                    current = null
                    if (useRoot && emitted == 0 && code != 0) {
                        // Root exists but logcat refused to run under it: retry unprivileged.
                        useRoot = false
                        continue
                    }
                    break
                }
                parser.flush()?.takeIf(filter::matches)?.let { send(it) }
            } finally {
                destroyQuietly(current)
                current = null
                close()
            }
        }

        awaitClose {
            pump.cancel()
            destroyQuietly(current)
            current = null
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                shell.run(CLEAR_COMMAND, root = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Nothing sensible to do: the buffer stays as it is.
            }
        }
    }

    override suspend fun export(path: String, lines: List<LogLine>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val target = File(path)
                target.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                target.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (line in lines) writeEntry(writer, line)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }

    // ---------------------------------------------------------------- process plumbing

    private fun spawn(root: Boolean): Process {
        val argv = if (root) {
            arrayOf(SU_BINARY, "-c", LOGCAT_THREADTIME)
        } else {
            arrayOf(SH_BINARY, "-c", LOGCAT_THREADTIME)
        }
        return ProcessBuilder(*argv).start()
    }

    /**
     * Reads [process] until it closes its output, handing every completed [LogLine] to [emit].
     *
     * @return how many lines passed the filter, which tells the caller whether the process
     *   was merely silent or failed outright.
     */
    private suspend fun readLogcat(
        process: Process,
        parser: ThreadtimeParser,
        filter: LineFilter,
        emit: suspend (LogLine) -> Unit,
    ): Int {
        var emitted = 0
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val raw = reader.readLine() ?: break
                val line = parser.accept(raw) ?: continue
                if (!filter.matches(line)) continue
                emit(line)
                emitted++
            }
        } finally {
            runCatching { reader.close() }
        }
        return emitted
    }

    private fun drainQuietly(input: InputStream) {
        try {
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                while (reader.readLine() != null) {
                    // stderr is only drained so the child never blocks on a full pipe
                }
            }
        } catch (_: Throwable) {
            // The stream is closed while a read may be in flight; nothing to report.
        }
    }

    private fun exitCodeOf(process: Process): Int {
        val finished = runCatching {
            process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) destroyQuietly(process)
        return runCatching { process.exitValue() }.getOrDefault(0)
    }

    private fun destroyQuietly(process: Process?) {
        if (process == null) return
        runCatching { process.destroy() }
    }

    private fun closeQuietly(stream: Closeable?) {
        if (stream == null) return
        runCatching { stream.close() }
    }

    // ---------------------------------------------------------------- export formatting

    private fun writeEntry(writer: BufferedWriter, line: LogLine) {
        val message = line.message
        val firstBreak = message.indexOf('\n')
        val head = if (firstBreak < 0) message else message.substring(0, firstBreak)
        writer.write("${line.timestamp} ${line.pid} ${line.tid} ${line.level} ${line.tag}: ")
        writer.write(head)
        writer.write("\n")

        var index = firstBreak
        while (index >= 0) {
            val start = index + 1
            val next = message.indexOf('\n', start)
            val end = if (next < 0) message.length else next
            writer.write(CONTINUATION_INDENT)
            writer.write(message.substring(start, end))
            writer.write("\n")
            index = next
        }
    }

    private companion object {
        const val LOGCAT_THREADTIME = "logcat -v threadtime"
        const val CLEAR_COMMAND = "logcat -c"
        const val SU_BINARY = "su"
        const val SH_BINARY = "/system/bin/sh"

        /** Root attempt plus one unprivileged retry. */
        const val MAX_ATTEMPTS = 2

        /** Grace period for a finished stream to be reaped before we report no exit code. */
        const val EXIT_WAIT_MS = 200L

        const val CONTINUATION_INDENT = "    "
    }
}

/**
 * Incremental `logcat -v threadtime` parser.
 *
 * Header shape (the year is only present with `-v year`, or `threadtime,year`):
 * `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`
 * `YYYY-MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`
 *
 * The parser keeps the most recent header as `pending` and returns it once the next header
 * starts, which is what makes multi-line messages complete by the time they are emitted.
 * Malformed lines never throw: they either extend the pending message or are dropped when
 * there is nothing to extend.
 */
private class ThreadtimeParser(private val seq: AtomicLong) {

    private var pending: LogLine? = null

    /** Feeds one raw line; returns the line that this line completed, if any. */
    fun accept(raw: String): LogLine? {
        val text = raw.trimEnd('\r')
        if (text.isEmpty()) return null
        if (text.startsWith(SEPARATOR_PREFIX)) return null

        val match = THREADTIME.matchEntire(text)
        if (match != null) {
            val line = toLogLine(match) ?: return null
            val completed = pending
            pending = line
            return completed
        }

        val previous = pending
        if (previous != null) {
            pending = previous.copy(message = previous.message + '\n' + text)
        }
        return null
    }

    /** Releases the last line, which no following header will ever complete. */
    fun flush(): LogLine? {
        val line = pending
        pending = null
        return line
    }

    private fun toLogLine(match: MatchResult): LogLine? {
        val values = match.groupValues
        val pid = values[4].toIntOrNull() ?: return null
        val tid = values[5].toIntOrNull() ?: return null
        val level = values[6].firstOrNull()?.uppercaseChar() ?: return null
        val year = values[1]
        val timestamp = buildString {
            if (year.isNotEmpty()) {
                append(year)
                append('-')
            }
            append(values[2]) // MM-DD
            append(' ')
            append(values[3]) // HH:MM:SS.mmm
        }
        return LogLine(
            seq = seq.incrementAndGet(),
            timestamp = timestamp,
            pid = pid,
            tid = tid,
            level = level,
            tag = values[7].trim(),
            message = values[8],
        )
    }

    private companion object {
        /** `--------- beginning of main` style markers carry no payload. */
        const val SEPARATOR_PREFIX = "---------"

        val THREADTIME = Regex(
            "^(?:(\\d{4})-)?(\\d{2}-\\d{2})\\s+" +
                "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3,9})\\s+" +
                "(\\d+)\\s+(\\d+)\\s+([VDIWEFSA])\\s+(.*?):\\s?(.*)$"
        )
    }
}

/** Client-side predicate applied to parsed lines. */
private class LineFilter(level: Char, tag: String?, pid: Int?) {

    private val minRank = rankOf(level)
    private val tagQuery = tag?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
    private val pidFilter = pid?.takeIf { it > 0 }

    fun matches(line: LogLine): Boolean {
        if (rankOf(line.level) < minRank) return false
        val query = tagQuery
        if (query != null && !line.tag.lowercase(Locale.US).contains(query)) return false
        val wantedPid = pidFilter
        if (wantedPid != null && line.pid != wantedPid) return false
        return true
    }
}

/** `V < D < I < W < E < F == A < S`, mirroring `android.util.Log` priorities. */
private fun rankOf(level: Char): Int = when (level.uppercaseChar()) {
    'V' -> 0
    'D' -> 1
    'I' -> 2
    'W' -> 3
    'E' -> 4
    'F', 'A' -> 5
    'S' -> 6
    else -> 0
}
