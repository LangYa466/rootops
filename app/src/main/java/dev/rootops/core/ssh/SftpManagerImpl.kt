package dev.rootops.core.ssh

import android.content.Context
import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import dev.rootops.core.api.FileEntry
import dev.rootops.core.api.SftpManager
import dev.rootops.core.api.SshAuth
import dev.rootops.core.api.SshProfile
import dev.rootops.core.api.TransferItem
import dev.rootops.core.api.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JSch backed [SftpManager].
 *
 * One SSH [Session] and one SFTP [ChannelSftp] are kept per handle. A single
 * channel multiplexes every request over the same SSH connection and JSch does
 * not tolerate concurrent callers on it, so all channel work for a handle is
 * serialized through a per-handle [Mutex]. That is also what the
 * `QUEUED -> RUNNING` transfer states describe: a transfer waits for the
 * channel, then owns it until it finishes or is cancelled.
 */
class SftpManagerImpl(private val context: Context) : SftpManager {

    private val handles = ConcurrentHashMap<Long, ChannelSftp>()
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /** Transfer id -> cancelled flag, flipped by [cancel] and polled by the copy loops. */
    private val cancelled = ConcurrentHashMap<Long, Boolean>()

    private val nextHandleId = AtomicLong(1L)
    private val nextTransferId = AtomicLong(1L)

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    override val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    // ---------------------------------------------------------------- connection

    override suspend fun connect(profile: SshProfile): Long = withContext(Dispatchers.IO) {
        val session = try {
            openSession(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "SSH connection to ${profile.user}@${profile.host}:${profile.port} failed: ${e.message}",
                e,
            )
        }
        val channel = try {
            (session.openChannel("sftp") as ChannelSftp).also { it.connect(CONNECT_TIMEOUT_MS) }
        } catch (e: CancellationException) {
            runCatching { session.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw IllegalStateException(
                "SFTP subsystem unavailable on ${profile.host}:${profile.port}: ${e.message}",
                e,
            )
        }
        val handleId = nextHandleId.getAndIncrement()
        locks[handleId] = Mutex()
        handles[handleId] = channel
        sessions[handleId] = session
        handleId
    }

    override suspend fun disconnect(handleId: Long): Unit = withContext(Dispatchers.IO) {
        val channel = handles.remove(handleId)
        val session = sessions.remove(handleId)
        locks.remove(handleId)
        channel?.let {
            // exit() closes the channel request stream, disconnect() the SSH channel itself.
            runCatching { it.exit() }
            runCatching { it.disconnect() }
        }
        runCatching { session?.disconnect() }
        Unit
    }

    private fun openSession(profile: SshProfile): Session {
        val jsch = JSch()
        JSch.setConfig("StrictHostKeyChecking", "no")
        if (profile.auth == SshAuth.KEY) {
            val keyPath = profile.keyPath?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No private key configured for \"${profile.name}\"")
            addIdentity(jsch, keyPath, profile.passphrase)
        }
        val port = profile.port.takeIf { it in 1..65_535 } ?: DEFAULT_SSH_PORT
        val session = jsch.getSession(profile.user, profile.host, port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        if (profile.auth == SshAuth.PASSWORD) {
            val password = profile.password
                ?: throw IllegalStateException("No password configured for \"${profile.name}\"")
            session.setPassword(password)
        }
        // No socket read timeout: JSch drops the whole session when a read times out,
        // which would kill an idle connection. Keepalives detect dead links instead.
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }

    /**
     * Loads a private key. `content://` and `file://` sources (the SAF picker hands
     * those out) are copied to a private cache file first because JSch needs a
     * readable path; the copy is removed as soon as the key is loaded.
     */
    private fun addIdentity(jsch: JSch, keyPath: String, passphrase: String?) {
        val pass = passphrase?.takeIf { it.isNotEmpty() }
        if (!keyPath.startsWith("content://") && !keyPath.startsWith("file://")) {
            if (pass == null) jsch.addIdentity(keyPath) else jsch.addIdentity(keyPath, pass)
            return
        }
        val temp = File(context.cacheDir, "sftp-identity-${System.nanoTime()}")
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(keyPath))
                ?: throw IllegalStateException("Cannot open private key $keyPath")
            input.use { source -> temp.outputStream().use { sink -> source.copyTo(sink) } }
            if (pass == null) jsch.addIdentity(temp.absolutePath)
            else jsch.addIdentity(temp.absolutePath, pass)
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------------ listing

    override suspend fun list(handleId: Long, path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val channel = handles[handleId] ?: return@withContext emptyList()
            try {
                lockFor(handleId).withLock { readDirectory(channel, path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Listing failures are reported as an empty directory; the screen
                // shows its empty state instead of crashing.
                emptyList()
            }
        }

    private fun readDirectory(channel: ChannelSftp, path: String): List<FileEntry> {
        val entries = channel.ls(path) ?: return emptyList()
        val result = ArrayList<FileEntry>(entries.size)
        for (entry in entries) {
            val name = entry.filename ?: continue
            if (name == "." || name == "..") continue
            result += toFileEntry(joinPath(path, name), name, entry.attrs)
        }
        result.sortWith(ENTRY_ORDER)
        return result
    }

    private fun toFileEntry(path: String, name: String, attrs: SftpATTRS?): FileEntry {
        val permissions = attrs?.getPermissions() ?: 0
        return FileEntry(
            name = name,
            path = path,
            isDir = attrs?.isDir == true,
            isLink = attrs?.isLink == true,
            size = attrs?.getSize() ?: 0L,
            // getPermissionsString() prefixes the entry type (`-`, `d`, `l`).
            mode = attrs?.getPermissionsString()?.drop(1) ?: "---------",
            owner = attrs?.getUId()?.toString() ?: "",
            group = attrs?.getGId()?.toString() ?: "",
            modified = (attrs?.getMTime() ?: 0).toLong(),
            readable = (permissions and OWNER_READ) != 0,
            writable = (permissions and OWNER_WRITE) != 0,
            executable = (permissions and OWNER_EXEC) != 0,
            special = (permissions and SPECIAL_BITS) != 0,
        )
    }

    // ---------------------------------------------------------------- transfers

    override suspend fun download(handleId: Long, remotePath: String, localPath: String) {
        val transferId = nextTransferId.getAndIncrement()
        val name = remoteName(remotePath)
        val channel = handles[handleId]
        if (channel == null) {
            publish(
                TransferItem(
                    id = transferId,
                    name = name,
                    localPath = localPath,
                    remotePath = remotePath,
                    upload = false,
                    done = 0,
                    total = 0,
                    state = TransferState.FAILED,
                    error = "No SFTP handle $handleId",
                ),
            )
            throw IllegalStateException("No SFTP handle $handleId (disconnected?)")
        }
        publish(TransferItem(transferId, name, localPath, remotePath, false, 0, 0, TransferState.QUEUED))
        try {
            withContext(Dispatchers.IO) {
                lockFor(handleId).withLock {
                    if (isCancelled(transferId)) {
                        markState(transferId, TransferState.CANCELLED)
                        return@withLock
                    }
                    val active = handles[handleId]
                        ?: throw IllegalStateException("SFTP handle $handleId disconnected")
                    runDownload(transferId, active, remotePath, File(localPath))
                }
            }
        } catch (e: CancellationException) {
            markAbandoned(transferId)
            throw e
        } catch (e: Exception) {
            if (isCancelled(transferId)) {
                markState(transferId, TransferState.CANCELLED)
            } else {
                markState(transferId, TransferState.FAILED, e.message ?: e.javaClass.simpleName)
            }
            throw IllegalStateException("Download of $remotePath failed: ${e.message}", e)
        } finally {
            cancelled.remove(transferId)
        }
    }

    private fun runDownload(transferId: Long, channel: ChannelSftp, remotePath: String, target: File) {
        val total = runCatching { channel.lstat(remotePath).getSize() }.getOrDefault(0L)
        markRunning(transferId, total)
        target.absoluteFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        var done = 0L
        var lastEmit = System.nanoTime()
        var aborted = false
        var targetOwned = false
        try {
            channel.get(remotePath).use { input ->
                val output = FileOutputStream(target)
                // From here on the local file is ours: it was just created or truncated.
                targetOwned = true
                output.use { sink ->
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    while (true) {
                        if (isCancelled(transferId)) {
                            aborted = true
                            break
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        val now = System.nanoTime()
                        if (now - lastEmit >= PROGRESS_INTERVAL_NANOS) {
                            lastEmit = now
                            emitProgress(transferId, done, total)
                        }
                    }
                    sink.flush()
                }
            }
        } catch (e: Exception) {
            if (targetOwned) target.delete()
            throw e
        }
        if (aborted) {
            target.delete()
            markState(transferId, TransferState.CANCELLED)
        } else {
            markDone(transferId, done)
        }
    }

    override suspend fun upload(handleId: Long, localPath: String, remoteDir: String) {
        val transferId = nextTransferId.getAndIncrement()
        val source = File(localPath)
        val remotePath = joinPath(remoteDir, source.name)
        val channel = handles[handleId]
        if (channel == null) {
            publish(
                TransferItem(
                    id = transferId,
                    name = source.name,
                    localPath = localPath,
                    remotePath = remotePath,
                    upload = true,
                    done = 0,
                    total = 0,
                    state = TransferState.FAILED,
                    error = "No SFTP handle $handleId",
                ),
            )
            throw IllegalStateException("No SFTP handle $handleId (disconnected?)")
        }
        publish(TransferItem(transferId, source.name, localPath, remotePath, true, 0, 0, TransferState.QUEUED))
        try {
            withContext(Dispatchers.IO) {
                lockFor(handleId).withLock {
                    if (isCancelled(transferId)) {
                        markState(transferId, TransferState.CANCELLED)
                        return@withLock
                    }
                    val active = handles[handleId]
                        ?: throw IllegalStateException("SFTP handle $handleId disconnected")
                    runUpload(transferId, active, source, remotePath)
                }
            }
        } catch (e: CancellationException) {
            markAbandoned(transferId)
            throw e
        } catch (e: Exception) {
            if (isCancelled(transferId)) {
                markState(transferId, TransferState.CANCELLED)
            } else {
                markState(transferId, TransferState.FAILED, e.message ?: e.javaClass.simpleName)
            }
            throw IllegalStateException("Upload of $localPath failed: ${e.message}", e)
        } finally {
            cancelled.remove(transferId)
        }
    }

    private fun runUpload(transferId: Long, channel: ChannelSftp, source: File, remotePath: String) {
        if (!source.isFile) throw IllegalStateException("Local file ${source.path} does not exist")
        val total = source.length()
        markRunning(transferId, total)
        var done = 0L
        var lastEmit = System.nanoTime()
        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit

            override fun count(count: Long): Boolean {
                done += count
                val now = System.nanoTime()
                if (now - lastEmit >= PROGRESS_INTERVAL_NANOS) {
                    lastEmit = now
                    emitProgress(transferId, done, total)
                }
                // Returning false aborts the transfer at the next chunk boundary.
                return !isCancelled(transferId)
            }

            override fun end() = Unit
        }
        try {
            FileInputStream(source).use { input ->
                channel.put(input, remotePath, monitor, ChannelSftp.OVERWRITE)
            }
        } catch (e: Exception) {
            discardPartialUpload(channel, remotePath, transferId, done)
            if (isCancelled(transferId)) {
                markState(transferId, TransferState.CANCELLED)
                return
            }
            throw e
        }
        if (isCancelled(transferId)) {
            discardPartialUpload(channel, remotePath, transferId, done)
            markState(transferId, TransferState.CANCELLED)
        } else {
            markDone(transferId, total)
        }
    }

    /**
     * Removes a half-written remote file. Only called when the transfer actually
     * wrote bytes or was cancelled, so a file that merely failed to open is left
     * alone instead of deleting something we never touched.
     */
    private fun discardPartialUpload(channel: ChannelSftp, remotePath: String, transferId: Long, done: Long) {
        if (done > 0 || isCancelled(transferId)) {
            runCatching { channel.rm(remotePath) }
        }
    }

    override fun cancel(transferId: Long) {
        val item = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (item.isFinished) return
        cancelled[transferId] = true
    }

    // ------------------------------------------------------------- remote edits

    override suspend fun mkdir(handleId: Long, path: String): Unit = withContext(Dispatchers.IO) {
        val channel = channelOrThrow(handleId)
        lockFor(handleId).withLock {
            try {
                channel.mkdir(path)
            } catch (e: SftpException) {
                // Some servers (OpenSSH included) answer a bare SSH_FX_FAILURE for an
                // existing directory, so fall back to checking the path itself.
                if (!isAlreadyExists(e) && !isExistingDirectory(channel, path)) {
                    throw IllegalStateException("mkdir $path failed: ${e.message}", e)
                }
            }
        }
    }

    override suspend fun remove(handleId: Long, path: String, recursive: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val channel = channelOrThrow(handleId)
            lockFor(handleId).withLock {
                try {
                    val attrs = channel.lstat(path)
                    if (attrs.isDir) {
                        if (recursive) deleteDirectory(channel, path, 0) else channel.rmdir(path)
                    } else {
                        channel.rm(path)
                    }
                } catch (e: SftpException) {
                    throw IllegalStateException("Remove $path failed: ${e.message}", e)
                }
            }
        }

    private fun deleteDirectory(channel: ChannelSftp, path: String, depth: Int) {
        if (depth >= MAX_RECURSION_DEPTH) {
            throw IllegalStateException(
                "Refusing to delete $path: directory nesting deeper than $MAX_RECURSION_DEPTH",
            )
        }
        for (entry in channel.ls(path)) {
            val name = entry.filename ?: continue
            if (name == "." || name == "..") continue
            val childPath = joinPath(path, name)
            val attrs = entry.attrs
            // Symlinks are removed as links, never followed (a loop would recurse forever).
            if (attrs != null && attrs.isDir && !attrs.isLink) {
                deleteDirectory(channel, childPath, depth + 1)
            } else {
                channel.rm(childPath)
            }
        }
        channel.rmdir(path)
    }

    // --------------------------------------------------------------- transfer log

    private fun publish(item: TransferItem) {
        _transfers.update { pruneFinished(it + item) }
    }

    private fun pruneFinished(items: List<TransferItem>): List<TransferItem> {
        var excess = items.count { it.isFinished } - MAX_FINISHED_TRANSFERS
        if (excess <= 0) return items
        val kept = ArrayList<TransferItem>(items.size)
        for (item in items) {
            if (excess > 0 && item.isFinished) {
                excess--
                continue
            }
            kept += item
        }
        return kept
    }

    private fun updateTransfer(id: Long, block: (TransferItem) -> TransferItem) {
        _transfers.update { list ->
            if (list.none { it.id == id }) list else list.map { if (it.id == id) block(it) else it }
        }
    }

    private fun markRunning(id: Long, total: Long) = updateTransfer(id) {
        it.copy(
            done = 0,
            total = if (total > 0) total else it.total,
            state = TransferState.RUNNING,
            error = null,
        )
    }

    private fun emitProgress(id: Long, done: Long, total: Long) = updateTransfer(id) {
        it.copy(done = done, total = maxOf(it.total, total, done))
    }

    private fun markDone(id: Long, done: Long) = updateTransfer(id) {
        it.copy(done = done, total = maxOf(it.total, done), state = TransferState.DONE, error = null)
    }

    private fun markState(id: Long, state: TransferState, error: String? = null) =
        updateTransfer(id) { it.copy(state = state, error = error) }

    /** The coroutine running a transfer went away (scope cancelled) without finishing it. */
    private fun markAbandoned(id: Long) = updateTransfer(id) {
        if (it.state == TransferState.QUEUED || it.state == TransferState.RUNNING) {
            it.copy(state = TransferState.CANCELLED, error = "Cancelled")
        } else {
            it
        }
    }

    // -------------------------------------------------------------------- helpers

    private fun channelOrThrow(handleId: Long): ChannelSftp =
        handles[handleId] ?: throw IllegalStateException("No SFTP handle $handleId (disconnected?)")

    private fun lockFor(handleId: Long): Mutex = locks.computeIfAbsent(handleId) { Mutex() }

    private fun isCancelled(transferId: Long): Boolean = cancelled[transferId] == true

    private fun isAlreadyExists(e: SftpException): Boolean =
        e.message?.contains("exist", ignoreCase = true) == true

    private fun isExistingDirectory(channel: ChannelSftp, path: String): Boolean =
        runCatching { channel.stat(path).isDir }.getOrDefault(false)

    private fun joinPath(dir: String, name: String): String = when {
        dir.isEmpty() || dir == "." -> name
        dir == "/" -> "/$name"
        dir.endsWith("/") -> dir + name
        else -> "$dir/$name"
    }

    private fun remoteName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    private companion object {
        const val DEFAULT_SSH_PORT = 22
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SERVER_ALIVE_INTERVAL_MS = 30_000
        const val SERVER_ALIVE_COUNT_MAX = 3
        const val TRANSFER_BUFFER_SIZE = 32 * 1024
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        const val MAX_RECURSION_DEPTH = 64
        const val MAX_FINISHED_TRANSFERS = 50

        // Owner permission bits and the setuid/setgid/sticky bits.
        const val OWNER_READ = 0x100
        const val OWNER_WRITE = 0x80
        const val OWNER_EXEC = 0x40
        const val SPECIAL_BITS = 0xE00

        val ENTRY_ORDER: Comparator<FileEntry> = compareByDescending<FileEntry> { it.isDir }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
    }
}

private val TransferItem.isFinished: Boolean
    get() = state == TransferState.DONE ||
        state == TransferState.FAILED ||
        state == TransferState.CANCELLED
