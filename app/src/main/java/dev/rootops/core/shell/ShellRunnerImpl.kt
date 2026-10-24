package dev.rootops.core.shell

import android.content.Context
import dev.rootops.core.api.ShellHandle
import dev.rootops.core.api.ShellResult
import dev.rootops.core.api.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Escapes [s] for a POSIX shell by wrapping it in single quotes.
 *
 * Embedded single quotes are written as the classic `'\''` sequence, so the
 * result is always a single literal word for `/system/bin/sh` and `su -c`.
 */
internal fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * [ShellRunner] backed by [ProcessBuilder].
 *
 * Every command runs either through `su -c <cmd>` (when root was requested and a
 * working `su` was detected) or through `/system/bin/sh -c <cmd>`. stdout and
 * stderr are drained on separate threads so a chatty child process can never
 * fill a pipe and deadlock.
 *
 * Root detection is cached in [rootAvailable] and probed lazily on first use;
 * [refreshRoot] forces a new probe. All IO happens on [Dispatchers.IO], and no
 * method ever throws for a failed process start.
 */
class ShellRunnerImpl(@Suppress("unused") private val context: Context) : ShellRunner {

    /** Latest root probe result; `false` until the first probe finishes. */
    override val rootAvailable: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val probeMutex = Mutex()

    @Volatile
    private var probed = false

    /** Re-runs `su -c id` and caches the result. Never throws. */
    override suspend fun refreshRoot(): Boolean = probeMutex.withLock {
        val available = probeRoot()
        rootAvailable.value = available
        probed = true
        available
    }

    override suspend fun run(cmd: String, root: Boolean, timeoutMs: Long): ShellResult {
        ensureProbed()
        return withContext(Dispatchers.IO) { execute(commandFor(cmd, root), timeoutMs) }
    }

    override fun stream(cmd: String, root: Boolean): Flow<String> = callbackFlow<String> {
        ensureProbed()
        val process = try {
            withContext(Dispatchers.IO) { ProcessBuilder(commandFor(cmd, root)).start() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            close()
            awaitClose { }
            return@callbackFlow
        }

        val outPump = launch(Dispatchers.IO) { pumpLines(process.inputStream, channel) }
        val errPump = launch(Dispatchers.IO) { pumpLines(process.errorStream, channel) }
        val waiter = launch(Dispatchers.IO) {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                // Process torn down underneath us; treat as finished.
            }
            outPump.join()
            errPump.join()
            channel.close()
        }

        awaitClose {
            killQuietly(process)
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
            outPump.cancel()
            errPump.cancel()
            waiter.cancel()
        }
    }

    override fun open(cmd: String, root: Boolean): ShellHandle = ShellHandleImpl(cmd, root)

    /** True when [binary] resolves on the shell's `PATH` (`command -v`). Never throws. */
    suspend fun which(binary: String): Boolean {
        val name = binary.trim()
        if (name.isEmpty()) return false
        val result = run("command -v ${shQuote(name)}", root = false, timeoutMs = WHICH_TIMEOUT_MS)
        return result.code == 0 && result.out.isNotBlank()
    }

    /** Cheap, non-suspending check for a `su` binary in the well-known locations. */
    fun suAvailable(): Boolean = SU_PATHS.any { path ->
        try {
            File(path).exists()
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- internals

    /** Probes root at most once per instance unless [refreshRoot] is called. */
    private suspend fun ensureProbed() {
        if (probed) return
        probeMutex.withLock {
            if (probed) return
            val available = probeRoot()
            rootAvailable.value = available
            probed = true
        }
    }

    private suspend fun probeRoot(): Boolean = withContext(Dispatchers.IO) {
        val result = execute(listOf("su", "-c", "id"), ROOT_PROBE_TIMEOUT_MS)
        result.out.contains("uid=0") || result.err.contains("uid=0")
    }

    private fun commandFor(cmd: String, root: Boolean): List<String> =
        if (root && rootAvailable.value) {
            listOf("su", "-c", cmd)
        } else {
            listOf("/system/bin/sh", "-c", cmd)
        }

    /**
     * Runs [command] to completion (or [timeoutMs]), collecting stdout and stderr
     * separately. Returns [ShellResult] for every failure mode instead of throwing.
     */
    private fun execute(command: List<String>, timeoutMs: Long): ShellResult {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            return ShellResult(127, "", e.message ?: e.toString())
        } catch (t: Throwable) {
            return ShellResult(127, "", t.message ?: t.toString())
        }

        val outSink = ByteArrayOutputStream()
        val errSink = ByteArrayOutputStream()
        val outPump = drainAsync(process.inputStream, outSink, "rootops-sh-out")
        val errPump = drainAsync(process.errorStream, errSink, "rootops-sh-err")

        val finished = try {
            process.waitFor(effectiveTimeout(timeoutMs), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            killQuietly(process)
            Thread.currentThread().interrupt()
            return ShellResult(-1, "", "interrupted")
        } catch (t: Throwable) {
            killQuietly(process)
            return ShellResult(-1, "", t.message ?: t.toString())
        }

        if (!finished) {
            killQuietly(process)
            // Unblock the drain threads even if a grandchild still holds the pipe.
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
            joinQuietly(outPump, JOIN_GRACE_MS)
            joinQuietly(errPump, JOIN_GRACE_MS)
            return ShellResult(-1, "", "timeout after ${timeoutMs}ms")
        }

        joinQuietly(outPump, JOIN_GRACE_MS)
        joinQuietly(errPump, JOIN_GRACE_MS)

        val code = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }
        closeQuietly(process.outputStream)
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
        return ShellResult(
            code,
            String(outSink.toByteArray(), Charsets.UTF_8),
            String(errSink.toByteArray(), Charsets.UTF_8),
        )
    }

    /**
     * Copies [stream] into [sink] on a daemon thread until EOF.
     * A null stream (defensive) is treated as empty.
     */
    private fun drainAsync(
        stream: InputStream?,
        sink: ByteArrayOutputStream,
        name: String,
    ): Thread {
        val thread = Thread({
            if (stream != null) {
                val buffer = ByteArray(8 * 1024)
                try {
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read > 0) sink.write(buffer, 0, read)
                    }
                } catch (_: Throwable) {
                    // Stream closed because the process died or was killed.
                } finally {
                    closeQuietly(stream)
                }
            }
        }, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Emits one element per line of [stream] (UTF-8) into [channel] until EOF.
     *
     * Performs blocking reads, so it must be called on [Dispatchers.IO].
     */
    private suspend fun pumpLines(stream: InputStream?, channel: SendChannel<String>) {
        if (stream == null) return
        val reader = try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        } catch (_: Throwable) {
            return
        }
        try {
            while (true) {
                val line = reader.readLine() ?: break
                channel.send(line)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Channel closed or pipe torn down; nothing left to deliver.
        } finally {
            closeQuietly(reader)
        }
    }

    private fun effectiveTimeout(timeoutMs: Long): Long =
        if (timeoutMs <= 0L) Long.MAX_VALUE else timeoutMs

    private fun joinQuietly(thread: Thread, millis: Long) {
        try {
            thread.join(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun killQuietly(process: Process) {
        try {
            process.destroyForcibly()
        } catch (_: Throwable) {
            // Already gone.
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
            // Nothing useful to do with a failed close.
        }
    }

    /**
     * Long-lived shell process with a writable stdin, a merged stdout+stderr line
     * flow, and an exit latch.
     *
     * Startup happens on an internal IO scope so [open] never blocks; every public
     * member resolves through the [started] latch, so callers can write, await or
     * close before the process exists.
     */
    private inner class ShellHandleImpl(cmd: String, root: Boolean) : ShellHandle {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val exitState = MutableStateFlow(false)
        private val lines = Channel<String>(Channel.BUFFERED)
        private val processRef = AtomicReference<Process?>(null)
        private val started = CompletableDeferred<Process?>()
        private val exitCode = CompletableDeferred<Int>()

        @Volatile
        private var closing = false

        override val exited: StateFlow<Boolean> = exitState
        override val output: Flow<String> = lines.receiveAsFlow()

        init {
            scope.launch {
                ensureProbed()
                if (closing) {
                    finishWithoutProcess()
                    return@launch
                }
                val process = try {
                    ProcessBuilder(commandFor(cmd, root)).start()
                } catch (_: Throwable) {
                    finishWithoutProcess()
                    return@launch
                }
                processRef.set(process)
                if (closing) killQuietly(process)
                started.complete(process)

                val outPump = launch { pumpLines(process.inputStream, lines) }
                val errPump = launch { pumpLines(process.errorStream, lines) }

                val code = try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    EXIT_NOT_STARTED
                } catch (_: Throwable) {
                    EXIT_NOT_STARTED
                }
                exitCode.complete(code)
                exitState.value = true

                // Let buffered output drain, but never let a stalled collector
                // pin the pumps forever.
                val drained = withTimeoutOrNull(DRAIN_GRACE_MS) {
                    outPump.join()
                    errPump.join()
                }
                lines.close()
                if (drained == null) {
                    outPump.cancel()
                    errPump.cancel()
                }
            }
        }

        override suspend fun write(text: String) {
            val process = started.await() ?: return
            if (closing) return
            withContext(Dispatchers.IO) {
                try {
                    val stdin = process.outputStream
                    stdin.write(text.toByteArray(Charsets.UTF_8))
                    stdin.flush()
                } catch (_: IOException) {
                    // Process already exited; a dead stdin is not an error.
                } catch (_: Throwable) {
                    // Same as above for torn-down streams.
                }
            }
        }

        override suspend fun await(): Int {
            started.await()
            return exitCode.await()
        }

        override suspend fun close() {
            closing = true
            val process = processRef.get()
            if (process != null) {
                closeQuietly(process.outputStream)
                killQuietly(process)
                closeQuietly(process.inputStream)
                closeQuietly(process.errorStream)
            } else {
                // Startup has not produced a process yet; resolve any waiters.
                started.complete(null)
                exitCode.complete(EXIT_NOT_STARTED)
                exitState.value = true
                lines.close()
            }
        }

        private fun finishWithoutProcess() {
            started.complete(null)
            exitCode.complete(EXIT_NOT_STARTED)
            exitState.value = true
            lines.close()
        }
    }

    companion object {
        private const val ROOT_PROBE_TIMEOUT_MS = 3_000L
        private const val WHICH_TIMEOUT_MS = 5_000L
        private const val JOIN_GRACE_MS = 1_000L
        private const val DRAIN_GRACE_MS = 5_000L
        private const val EXIT_NOT_STARTED = 127

        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )

        /** Public alias of [shQuote] for other workers, e.g. `ShellRunnerImpl.quote(path)`. */
        fun quote(s: String): String = shQuote(s)
    }
}
