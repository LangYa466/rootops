package dev.rootops.core.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ShellRunner {
    val rootAvailable: StateFlow<Boolean>
    suspend fun refreshRoot(): Boolean
    suspend fun run(cmd: String, root: Boolean = true, timeoutMs: Long = 30_000): ShellResult
    fun stream(cmd: String, root: Boolean = true): Flow<String>
    fun open(cmd: String, root: Boolean = true): ShellHandle
}

interface ShellHandle {
    val output: Flow<String>
    val exited: StateFlow<Boolean>
    suspend fun write(text: String)
    suspend fun close()
    suspend fun await(): Int
}

interface FileRepository {
    suspend fun list(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry?
    suspend fun exists(path: String): Boolean
    suspend fun readText(path: String, maxBytes: Int = 4_000_000): String
    suspend fun readBytes(path: String, maxBytes: Int = 16_000_000): ByteArray
    suspend fun writeText(path: String, text: String): Boolean
    suspend fun writeBytes(path: String, bytes: ByteArray): Boolean
    suspend fun canonical(path: String): String
    suspend fun search(root: String, query: String, limit: Int = 300): List<FileEntry>
    suspend fun diskUsage(path: String): Long
    suspend fun childrenCount(path: String): Int
}

interface FileOps {
    val progress: StateFlow<OpProgress>
    suspend fun copy(sources: List<String>, destDir: String): Boolean
    suspend fun move(sources: List<String>, destDir: String): Boolean
    suspend fun delete(paths: List<String>): Boolean
    suspend fun rename(path: String, newName: String): Boolean
    suspend fun mkdir(parent: String, name: String): Boolean
    suspend fun createFile(parent: String, name: String): Boolean
    suspend fun chmod(path: String, mode: String, recursive: Boolean = false): Boolean
    suspend fun chown(path: String, owner: String, group: String, recursive: Boolean = false): Boolean
    suspend fun symlink(target: String, linkPath: String): Boolean
}

interface ArchiveManager {
    suspend fun list(archivePath: String): List<ArchiveEntry>
    suspend fun extract(archivePath: String, destDir: String): Boolean
    suspend fun createZip(sources: List<String>, destZip: String): Boolean
    suspend fun createTarGz(sources: List<String>, destTarGz: String): Boolean
}

interface ProcessRepository {
    suspend fun list(): List<ProcInfo>
    suspend fun kill(pid: Int, signal: Int = 15): Boolean
    suspend fun detail(pid: Int): String
    suspend fun topSnapshot(): List<ProcInfo>
}

interface LogRepository {
    fun stream(level: Char = 'V', tag: String? = null, pid: Int? = null): Flow<LogLine>
    suspend fun clear()
    suspend fun export(path: String, lines: List<LogLine>): Boolean
}

interface NetworkTools {
    suspend fun interfaces(): List<NetInterface>
    suspend fun ports(): List<PortEntry>
    suspend fun ping(host: String, count: Int = 4): Flow<String>
    suspend fun dnsLookup(host: String): Flow<String>
    suspend fun httpProbe(url: String): Flow<String>
    suspend fun publicIp(): String?
    suspend fun traceroute(host: String): Flow<String>
}

interface ScriptStore {
    val scripts: StateFlow<List<ScriptItem>>
    suspend fun refresh()
    suspend fun save(item: ScriptItem): Long
    suspend fun delete(id: Long)
    suspend fun run(id: Long): Flow<String>
    fun get(id: Long): ScriptItem?
}

interface Scheduler {
    val schedules: StateFlow<List<ScheduleItem>>
    suspend fun refresh()
    suspend fun save(item: ScheduleItem): Long
    suspend fun delete(id: Long)
    fun start()
    fun stop()
}

interface MountRepository {
    suspend fun list(): List<MountEntry>
    suspend fun remount(path: String, readOnly: Boolean): Boolean
    suspend fun mount(device: String, mountPoint: String, fsType: String, options: String): Boolean
    suspend fun umount(path: String): Boolean
}

interface PackageRepository {
    suspend fun list(includeSystem: Boolean): List<PackageEntry>
    suspend fun setEnabled(pkg: String, enabled: Boolean): Boolean
    suspend fun clearData(pkg: String): Boolean
    suspend fun uninstall(pkg: String, keepData: Boolean): Boolean
    suspend fun launch(pkg: String): Boolean
    suspend fun apkPath(pkg: String): String?
}

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun update(block: (AppSettings) -> AppSettings)
}

interface TerminalEngine {
    fun open(shell: String, args: List<String>, cwd: String?, env: Map<String, String>, cols: Int, rows: Int): PtySession
}

interface PtySession {
    val output: Flow<ByteArray>
    val exited: StateFlow<Boolean>
    fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
    val pid: Int
}

interface SshManager {
    val sessions: StateFlow<List<SshSessionInfo>>
    suspend fun connect(profile: SshProfile): SshSessionInfo
    suspend fun disconnect(id: Long)
    suspend fun exec(id: Long, command: String): Flow<String>
}

interface SftpManager {
    val transfers: StateFlow<List<TransferItem>>
    suspend fun connect(profile: SshProfile): Long
    suspend fun list(handleId: Long, path: String): List<FileEntry>
    suspend fun download(handleId: Long, remotePath: String, localPath: String)
    suspend fun upload(handleId: Long, localPath: String, remoteDir: String)
    suspend fun mkdir(handleId: Long, path: String)
    suspend fun remove(handleId: Long, path: String, recursive: Boolean)
    suspend fun disconnect(handleId: Long)
    fun cancel(transferId: Long)
}
