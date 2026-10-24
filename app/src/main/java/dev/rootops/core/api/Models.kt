package dev.rootops.core.api

/** Theme selection for the whole application. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Persisted user preferences. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: Long = 0xFF7BD88F,
    val terminalFontSize: Int = 13,
    val terminalShell: String = "/system/bin/sh",
    val useRoot: Boolean = true,
    val showHidden: Boolean = false,
    val defaultPath: String = "/sdcard",
    val logcatLimit: Int = 4000,
    val confirmDelete: Boolean = true,
)

/** A single filesystem entry as reported by the file repository. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean = false,
    val linkTarget: String? = null,
    val size: Long = 0,
    val mode: String = "",
    val owner: String = "",
    val group: String = "",
    val modified: Long = 0,
    val readable: Boolean = true,
    val writable: Boolean = false,
    val executable: Boolean = false,
    val special: Boolean = false,
) {
    val extension: String get() = if (isDir) "" else name.substringAfterLast('.', "")
}

/** Result of a one-shot shell command. */
data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

/** A running process from /proc. */
data class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val user: String,
    val stat: Char,
    val cpuPercent: Float,
    val memPercent: Float,
    val rssKb: Long,
    val threads: Int,
    val name: String,
    val cmdline: String,
)

/** A single logcat record. */
data class LogLine(
    val seq: Long,
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
) {
    val levelName: String
        get() = when (level.uppercaseChar()) {
            'V' -> "VERBOSE"
            'D' -> "DEBUG"
            'I' -> "INFO"
            'W' -> "WARN"
            'E' -> "ERROR"
            'F' -> "ASSERT"
            else -> level.toString()
        }
}

/** A mounted filesystem. */
data class MountEntry(
    val device: String,
    val mountPoint: String,
    val fsType: String,
    val options: List<String>,
) {
    val readOnly: Boolean get() = options.contains("ro")
}

/** An installed application package. */
data class PackageEntry(
    val pkg: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    val system: Boolean,
    val enabled: Boolean,
    val uid: Int,
    val apkPath: String?,
)

/** A network interface with its addresses and counters. */
data class NetInterface(
    val name: String,
    val up: Boolean,
    val addresses: List<String>,
    val rxBytes: Long,
    val txBytes: Long,
)

/** An open local/remote port with the owning process when known. */
data class PortEntry(
    val proto: String,
    val local: String,
    val remote: String,
    val state: String,
    val pid: Int?,
    val process: String?,
)

/** A stored shell script. */
data class ScriptItem(
    val id: Long,
    val name: String,
    val body: String,
    val created: Long,
    val lastRun: Long?,
    val lastExit: Int?,
    val favorite: Boolean = false,
)

/** A recurring script execution schedule. */
data class ScheduleItem(
    val id: Long,
    val scriptId: Long,
    val name: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val lastRun: Long?,
)

/** One file transfer tracked by the SFTP manager. */
data class TransferItem(
    val id: Long,
    val name: String,
    val localPath: String,
    val remotePath: String,
    val upload: Boolean,
    val done: Long,
    val total: Long,
    val state: TransferState,
    val error: String? = null,
)

enum class TransferState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** A saved SSH connection profile. */
data class SshProfile(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
    val password: String? = null,
    val keyPath: String? = null,
    val passphrase: String? = null,
)

enum class SshAuth { PASSWORD, KEY }

/** Runtime view of an SSH connection. */
data class SshSessionInfo(
    val id: Long,
    val profileName: String,
    val host: String,
    val connected: Boolean,
    val lastError: String? = null,
)

/** Disk usage of one path. */
data class StorageStat(val path: String, val totalBytes: Long, val freeBytes: Long)

/** Aggregated device state for the dashboard. */
data class SystemSnapshot(
    val uptimeSec: Long = 0,
    val load1: Float = 0f,
    val load5: Float = 0f,
    val load15: Float = 0f,
    val memTotalKb: Long = 0,
    val memAvailableKb: Long = 0,
    val cpuCores: Int = 0,
    val cpuModel: String = "",
    val kernel: String = "",
    val androidRelease: String = "",
    val deviceModel: String = "",
    val rootAvailable: Boolean = false,
    val rootMode: String = "",
    val storage: List<StorageStat> = emptyList(),
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
)

/** Progress of a long-running file operation. */
data class OpProgress(
    val label: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val running: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** One member of an archive. */
data class ArchiveEntry(
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val compressedSize: Long = 0,
)

enum class SortKey { NAME, SIZE, MODIFIED, TYPE }

enum class SortOrder { ASC, DESC }
