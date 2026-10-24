package dev.rootops.core.ssh

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import dev.rootops.core.api.SshAuth
import dev.rootops.core.api.SshManager
import dev.rootops.core.api.SshProfile
import dev.rootops.core.api.SshSessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JSch-backed [SshManager].
 *
 * Every network operation runs on [Dispatchers.IO]; [connect] never throws and
 * reports failures through [SshSessionInfo.lastError] instead. Sessions are
 * kept in a map keyed by the generated id and mirrored into [sessions] so the
 * UI always sees the live set.
 */
class SshManagerImpl(private val context: Context) : SshManager {

    private val lock = Any()

    private val nextId = AtomicLong(1L)

    private val liveSessions = ConcurrentHashMap<Long, Session>()

    private val _sessions = MutableStateFlow<List<SshSessionInfo>>(emptyList())
    override val sessions: StateFlow<List<SshSessionInfo>> = _sessions.asStateFlow()

    /** The live JSch session for [id], or null when nothing is connected under that id. */
    fun session(id: Long): Session? = liveSessions[id]?.takeIf { it.isConnected }

    /**
     * Builds a configured [JSch] for [profile], loading the private key when the
     * profile uses [SshAuth.KEY]. Exposed so the SFTP layer can reuse the exact
     * same identity handling (content URIs, temporary key material).
     */
    fun createJSch(profile: SshProfile): JSch {
        val jsch = JSch()
        if (profile.auth != SshAuth.KEY) return jsch

        val keyPath = profile.keyPath?.trim().orEmpty()
        if (keyPath.isEmpty()) throw JSchException("no private key configured")

        val key = resolveKeyFile(keyPath)
        try {
            val passphrase = profile.passphrase?.takeIf { it.isNotEmpty() }
            if (passphrase == null) {
                jsch.addIdentity(key.file.absolutePath)
            } else {
                jsch.addIdentity(key.file.absolutePath, passphrase)
            }
        } finally {
            if (key.temporary) {
                // JSch reads and parses the key into memory inside addIdentity,
                // so the private key copy never has to outlive this call.
                runCatching { key.file.delete() }
            }
        }
        return jsch
    }

    override suspend fun connect(profile: SshProfile): SshSessionInfo = withContext(Dispatchers.IO) {
        val id = nextId.getAndIncrement()
        var opened: Session? = null
        try {
            val jsch = createJSch(profile)
            val port = if (profile.port > 0) profile.port else DEFAULT_PORT
            val session = jsch.getSession(profile.user, profile.host, port)
            opened = session
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig(
                "PreferredAuthentications",
                if (profile.auth == SshAuth.PASSWORD) {
                    "password,keyboard-interactive"
                } else {
                    "publickey"
                },
            )
            if (profile.auth == SshAuth.PASSWORD) {
                val password = profile.password
                if (password.isNullOrEmpty()) throw JSchException("no password configured")
                session.setPassword(password)
            }
            session.setServerAliveInterval(KEEPALIVE_MS)
            session.setServerAliveCountMax(3)
            session.connect(CONNECT_TIMEOUT_MS)
            if (!session.isConnected) throw JSchException("session closed during connect")

            liveSessions[id] = session
            val info = SshSessionInfo(
                id = id,
                profileName = profile.name,
                host = profile.host,
                connected = true,
            )
            publish { current -> current.filterNot { it.id == id } + info }
            info
        } catch (t: Throwable) {
            runCatching { opened?.disconnect() }
            Log.w(TAG, "ssh connect failed: ${profile.user}@${profile.host}", t)
            SshSessionInfo(
                id = -1L,
                profileName = profile.name,
                host = profile.host,
                connected = false,
                lastError = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    override suspend fun disconnect(id: Long) = withContext(Dispatchers.IO) {
        val session = liveSessions.remove(id)
        if (session != null) {
            runCatching { session.disconnect() }
        }
        publish { current -> current.filterNot { it.id == id } }
    }

    override suspend fun exec(id: Long, command: String): Flow<String> = flow {
        val session = liveSessions[id]
        if (session == null || !session.isConnected) {
            emit(NOT_CONNECTED)
            return@flow
        }

        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (t: Throwable) {
            Log.w(TAG, "ssh exec channel open failed", t)
            emit("! ${t.message ?: t.javaClass.simpleName}")
            return@flow
        }

        val stderr = StderrCollector()
        try {
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setErrStream(stderr)
            try {
                channel.connect(CONNECT_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "ssh exec channel connect failed", t)
                emit("! ${t.message ?: t.javaClass.simpleName}")
                return@flow
            }

            coroutineScope {
                val stdout = try {
                    channel.inputStream
                } catch (t: Throwable) {
                    Log.w(TAG, "ssh exec stream unavailable", t)
                    emit("! ${t.message ?: t.javaClass.simpleName}")
                    return@coroutineScope
                }
                // Closing the channel is what unblocks the (interrupt-unaware)
                // socket read when the collector cancels the flow.
                val guard = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { channel.disconnect() }
                    }
                }
                try {
                    val reader = InputStreamReader(stdout, Charsets.UTF_8)
                    val buffer = CharArray(READ_BUFFER)
                    val pending = StringBuilder()
                    while (true) {
                        val read = try {
                            reader.read(buffer)
                        } catch (_: IOException) {
                            break
                        }
                        if (read < 0) break
                        for (i in 0 until read) {
                            val c = buffer[i]
                            when {
                                c == '\n' -> {
                                    emit(pending.toString())
                                    pending.setLength(0)
                                    for (line in stderr.drain()) emit(STDERR_PREFIX + line)
                                }
                                c != '\r' -> pending.append(c)
                            }
                        }
                    }
                    if (pending.isNotEmpty()) emit(pending.toString())
                    for (line in stderr.drainAll()) emit(STDERR_PREFIX + line)
                } finally {
                    guard.cancel()
                }
            }

            emit("[exit ${awaitExitCode(channel)}]")
        } finally {
            runCatching { channel.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun awaitExitCode(channel: Channel): Int {
        val deadline = System.currentTimeMillis() + EXIT_WAIT_MS
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            delay(EXIT_POLL_MS)
        }
        return if (channel.isClosed) channel.exitStatus else -1
    }

    private fun publish(transform: (List<SshSessionInfo>) -> List<SshSessionInfo>) {
        synchronized(lock) {
            _sessions.value = transform(_sessions.value)
        }
    }

    private fun resolveKeyFile(keyPath: String): KeyFile {
        val uri = runCatching { Uri.parse(keyPath) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (scheme == null) {
            val file = File(keyPath)
            if (file.isFile && file.canRead()) return KeyFile(file, temporary = false)
        } else if (scheme == "file") {
            val file = File(uri.path.orEmpty())
            if (file.isFile && file.canRead()) return KeyFile(file, temporary = false)
        }

        // content:// keys and paths that are not readable files: copy the bytes
        // into a private cache file so JSch can parse them from disk.
        val bytes = readKeyBytes(keyPath, uri)
        val temp = File.createTempFile("rootops-id-", ".key", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setWritable(true, true)
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
        return KeyFile(temp, temporary = true)
    }

    private fun readKeyBytes(keyPath: String, uri: Uri?): ByteArray {
        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw JSchException("cannot open key: $keyPath")
        }
        val file = File(keyPath)
        if (!file.exists()) throw JSchException("key file not found: $keyPath")
        if (!file.isFile || !file.canRead()) throw JSchException("key file not readable: $keyPath")
        return file.readBytes()
    }

    private data class KeyFile(val file: File, val temporary: Boolean)

    /**
     * Collects stderr into complete lines on JSch's reader thread; the flow
     * drains them on its own thread so emission stays single-threaded.
     */
    private class StderrCollector : OutputStream() {

        private val lock = Any()
        private val lines = ArrayDeque<String>()
        private val pending = ByteArrayOutputStream()

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                val end = off + len
                var start = off
                for (i in off until end) {
                    if (b[i] == NEWLINE) {
                        pending.write(b, start, i - start)
                        lines.addLast(decode(pending.toByteArray()))
                        pending.reset()
                        start = i + 1
                    }
                }
                if (start < end) pending.write(b, start, end - start)
            }
        }

        private fun decode(bytes: ByteArray): String {
            var length = bytes.size
            if (length > 0 && bytes[length - 1] == CARRIAGE_RETURN) length--
            return String(bytes, 0, length, Charsets.UTF_8)
        }

        fun drain(): List<String> {
            synchronized(lock) {
                if (lines.isEmpty()) return emptyList()
                val out = lines.toList()
                lines.clear()
                return out
            }
        }

        fun drainAll(): List<String> {
            synchronized(lock) {
                if (pending.size() > 0) {
                    lines.addLast(decode(pending.toByteArray()))
                    pending.reset()
                }
                return drain()
            }
        }

        private companion object {
            val NEWLINE = '\n'.code.toByte()
            val CARRIAGE_RETURN = '\r'.code.toByte()
        }
    }

    private companion object {
        const val TAG = "SshManagerImpl"
        const val DEFAULT_PORT = 22
        const val CONNECT_TIMEOUT_MS = 10_000
        const val KEEPALIVE_MS = 30_000
        const val EXIT_WAIT_MS = 5_000L
        const val EXIT_POLL_MS = 10L
        const val READ_BUFFER = 4 * 1024
        const val STDERR_PREFIX = "! "
        const val NOT_CONNECTED = "! not connected"
    }
}
