package dev.rootops.core.pty

import dev.rootops.core.api.PtySession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A live shell attached to a pty master fd owned by [PtyNative].
 *
 * Threading model:
 *  - [output] is a cold flow: the collecting coroutine performs the `read(2)`
 *    loop on `Dispatchers.IO`. A second concurrent collector waits for the first
 *    to finish (the fd is single-reader) instead of stealing bytes.
 *  - [write] and [resize] are fire-and-forget and serialised, so keystrokes
 *    reach the pty in the order the UI produced them.
 *  - A watcher coroutine parks in `waitpid` for the lifetime of the session so
 *    the child is always reaped, whether or not anyone collects [output].
 *
 * Lifecycle: the fd is closed exactly once, either when the reader hits EOF,
 * when [close] is called, or when the collector is cancelled.
 */
class PtySessionImpl(
    private val masterFd: Int,
    override val pid: Int,
) : PtySession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val fdClosed = AtomicBoolean(false)

    private val readerMutex = Mutex()
    private val writeMutex = Mutex()

    private val _exited = MutableStateFlow(false)
    override val exited: StateFlow<Boolean> = _exited.asStateFlow()

    init {
        // Reaps the child (no zombies) and flags exit even if nobody collects.
        scope.launch {
            PtyNative.waitChild(masterFd)
            markExited()
        }
    }

    override val output: Flow<ByteArray> = flow {
        readerMutex.withLock {
            if (!closed.get() && !fdClosed.get()) {
                val buffer = ByteArray(READ_BUFFER_SIZE)
                try {
                    while (!closed.get()) {
                        val n = PtyNative.readPty(masterFd, buffer, buffer.size)
                        when {
                            n > 0 -> emit(buffer.copyOf(n))
                            n == 0 -> delay(IDLE_POLL_MS) // EAGAIN: nothing yet
                            else -> break // EOF or EIO: the pty is gone
                        }
                    }
                } finally {
                    markExited()
                    closeFd()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun write(data: ByteArray) {
        if (data.isEmpty() || closed.get() || fdClosed.get()) {
            return
        }
        // Copy once: the caller may reuse its buffer as soon as we return.
        val payload = data.copyOf()
        scope.launch {
            writeMutex.withLock {
                var offset = 0
                while (offset < payload.size && !closed.get() && !fdClosed.get()) {
                    val chunk = if (offset == 0) payload else payload.copyOfRange(offset, payload.size)
                    val written = PtyNative.writePty(masterFd, chunk, chunk.size)
                    when {
                        written > 0 -> offset += written
                        written == 0 -> delay(IDLE_POLL_MS) // pty buffer full
                        else -> return@withLock // fd gone
                    }
                }
            }
        }
    }

    override fun resize(cols: Int, rows: Int) {
        if (closed.get() || fdClosed.get() || cols <= 0 || rows <= 0) {
            return
        }
        PtyNative.resizePty(masterFd, cols, rows)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        markExited()
        closeFd()
        scope.cancel()
    }

    private fun closeFd() {
        if (fdClosed.compareAndSet(false, true)) {
            PtyNative.closePty(masterFd)
        }
    }

    private fun markExited() {
        _exited.value = true
    }

    private companion object {
        const val READ_BUFFER_SIZE = 8192
        const val IDLE_POLL_MS = 8L
    }
}
