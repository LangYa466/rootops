# RootOps — Shared Contracts

Authoritative API for every worker. The contract files below are **frozen**:

| File | Package | Owner |
| --- | --- | --- |
| `app/src/main/java/dev/rootops/core/api/Models.kt` | `dev.rootops.core.api` | foundation |
| `app/src/main/java/dev/rootops/core/api/Contracts.kt` | `dev.rootops.core.api` | foundation |
| `app/src/main/java/dev/rootops/core/util/Format.kt` | `dev.rootops.core.util` | foundation |
| `app/src/main/java/dev/rootops/core/util/JsonStore.kt` | `dev.rootops.core.util` | foundation |
| `app/src/main/java/dev/rootops/ui/theme/{Color,Type,Theme}.kt` | `dev.rootops.ui.theme` | foundation |
| `app/src/main/java/dev/rootops/ui/components/Common.kt` | `dev.rootops.ui.components` | foundation |
| `app/src/main/java/dev/rootops/AppContainer.kt` | `dev.rootops` | parent (integration) |
| `app/src/main/java/dev/rootops/RootOpsApp.kt` | `dev.rootops` | parent (integration) |
| `app/src/main/java/dev/rootops/MainActivity.kt` | `dev.rootops` | parent (integration) |
| `app/src/main/java/dev/rootops/ui/nav/Route.kt` | `dev.rootops.ui.nav` | parent (integration) |
| `app/src/main/java/dev/rootops/ui/nav/RootNav.kt` | `dev.rootops.ui.nav` | parent (integration) |

Do not edit them. If a signature is genuinely insufficient, message the foundation worker; do not fork a second convention.

Build: `./gradlew :app:assembleDebug` (Gradle wrapper 8.13, AGP 8.13.0, Kotlin 2.0.21, minSdk 26, jvmTarget 17).

---

## Models (`dev.rootops.core.api`)

```kotlin
enum class ThemeMode { SYSTEM, DARK, LIGHT }

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

data class FileEntry(
    val name: String, val path: String, val isDir: Boolean,
    val isLink: Boolean = false, val linkTarget: String? = null,
    val size: Long = 0, val mode: String = "", val owner: String = "", val group: String = "",
    val modified: Long = 0, val readable: Boolean = true, val writable: Boolean = false,
    val executable: Boolean = false, val special: Boolean = false,
) { val extension: String }            // "" for directories

data class ShellResult(val code: Int, val out: String, val err: String) { val ok: Boolean }

data class ProcInfo(
    val pid: Int, val ppid: Int, val user: String, val stat: Char,
    val cpuPercent: Float, val memPercent: Float, val rssKb: Long, val threads: Int,
    val name: String, val cmdline: String,
)

data class LogLine(
    val seq: Long, val timestamp: String, val pid: Int, val tid: Int,
    val level: Char, val tag: String, val message: String,
) { val levelName: String }            // VERBOSE/DEBUG/INFO/WARN/ERROR/ASSERT

data class MountEntry(
    val device: String, val mountPoint: String, val fsType: String, val options: List<String>,
) { val readOnly: Boolean }            // options.contains("ro")

data class PackageEntry(
    val pkg: String, val label: String?, val versionName: String?, val versionCode: Long,
    val system: Boolean, val enabled: Boolean, val uid: Int, val apkPath: String?,
)

data class NetInterface(
    val name: String, val up: Boolean, val addresses: List<String>,
    val rxBytes: Long, val txBytes: Long,
)

data class PortEntry(
    val proto: String, val local: String, val remote: String, val state: String,
    val pid: Int?, val process: String?,
)

data class ScriptItem(
    val id: Long, val name: String, val body: String, val created: Long,
    val lastRun: Long?, val lastExit: Int?, val favorite: Boolean = false,
)

data class ScheduleItem(
    val id: Long, val scriptId: Long, val name: String,
    val intervalMinutes: Int, val enabled: Boolean, val lastRun: Long?,
)

data class TransferItem(
    val id: Long, val name: String, val localPath: String, val remotePath: String,
    val upload: Boolean, val done: Long, val total: Long, val state: TransferState,
    val error: String? = null,
)
enum class TransferState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class SshProfile(
    val id: Long, val name: String, val host: String, val port: Int, val user: String,
    val auth: SshAuth, val password: String? = null, val keyPath: String? = null,
    val passphrase: String? = null,
)
enum class SshAuth { PASSWORD, KEY }

data class SshSessionInfo(
    val id: Long, val profileName: String, val host: String, val connected: Boolean,
    val lastError: String? = null,
)

data class StorageStat(val path: String, val totalBytes: Long, val freeBytes: Long)

data class SystemSnapshot(
    val uptimeSec: Long = 0, val load1: Float = 0f, val load5: Float = 0f, val load15: Float = 0f,
    val memTotalKb: Long = 0, val memAvailableKb: Long = 0, val cpuCores: Int = 0,
    val cpuModel: String = "", val kernel: String = "", val androidRelease: String = "",
    val deviceModel: String = "", val rootAvailable: Boolean = false, val rootMode: String = "",
    val storage: List<StorageStat> = emptyList(), val batteryPercent: Int = -1,
    val charging: Boolean = false,
)

data class OpProgress(
    val label: String = "", val done: Long = 0, val total: Long = 0,
    val running: Boolean = false, val error: String? = null,
) { val fraction: Float }              // 0f..1f, 0f when total <= 0

data class ArchiveEntry(val path: String, val size: Long, val isDir: Boolean, val compressedSize: Long = 0)

enum class SortKey { NAME, SIZE, MODIFIED, TYPE }
enum class SortOrder { ASC, DESC }
```

`modified`, `created`, `lastRun` are **epoch seconds** (not millis).

---

## Interfaces (`dev.rootops.core.api`, `Contracts.kt`)

```kotlin
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
```

---

## AppContainer (parent-owned, written during integration)

```kotlin
class AppContainer(context: android.content.Context) {
    val settings: SettingsStore
    val shell: ShellRunner
    val files: FileRepository
    val fileOps: FileOps
    val archives: ArchiveManager
    val terminal: TerminalEngine
    val processes: ProcessRepository
    val logs: LogRepository
    val network: NetworkTools
    val scripts: ScriptStore
    val scheduler: Scheduler
    val mounts: MountRepository
    val packages: PackageRepository
    val ssh: SshManager
    val sftp: SftpManager
}
```

FQN: `dev.rootops.AppContainer` (file `app/src/main/java/dev/rootops/AppContainer.kt`, root package —
**not** `dev.rootops.di`).

Screens never construct `AppContainer`; it is provided by the integrator. Do not create
`AppContainer.kt`, `ui/nav/*.kt`, `MainActivity.kt` or `RootOpsApp.kt`.

---

## Implementation class names (each owned by one worker)

```
dev.rootops.core.shell.ShellRunnerImpl(context: Context) : ShellRunner
dev.rootops.core.pty.PtyEngineImpl() : TerminalEngine
dev.rootops.core.fs.FileRepositoryImpl(shell: ShellRunner) : FileRepository
dev.rootops.core.fs.FileOpsImpl(shell: ShellRunner, files: FileRepository) : FileOps
dev.rootops.core.fs.ArchiveManagerImpl(shell: ShellRunner) : ArchiveManager
dev.rootops.core.sys.ProcessRepositoryImpl(shell: ShellRunner) : ProcessRepository
dev.rootops.core.sys.LogRepositoryImpl(shell: ShellRunner) : LogRepository
dev.rootops.core.net.NetworkToolsImpl(context: Context, shell: ShellRunner) : NetworkTools
dev.rootops.core.scripts.ScriptStoreImpl(context: Context, shell: ShellRunner) : ScriptStore
dev.rootops.core.scripts.SchedulerImpl(context: Context, store: ScriptStore, shell: ShellRunner) : Scheduler
dev.rootops.core.mount.MountRepositoryImpl(shell: ShellRunner) : MountRepository
dev.rootops.core.pkg.PackageRepositoryImpl(context: Context, shell: ShellRunner) : PackageRepository
dev.rootops.core.prefs.SettingsStoreImpl(context: Context) : SettingsStore
dev.rootops.core.ssh.SshManagerImpl(context: Context) : SshManager
dev.rootops.core.ssh.SftpManagerImpl(context: Context) : SftpManager
```

Constructors are exactly these positional parameters (`Context` is `android.content.Context`).
All implementations are `class` (not `object`), thread-safe for concurrent coroutine use, and
never throw from a `suspend fun` that returns `Boolean`/`List` — return `false`/`emptyList()` and
publish the failure through `progress`/`lastError`/empty state instead.

---

## Screens

Every screen is `@Composable fun XxxScreen(container: AppContainer)` in `dev.rootops.ui.screens.<area>`,
self-contained (own state, no cross-screen dependencies). Screens may use `viewModel { }`? NO — use
`remember { ... }` / `LaunchedEffect` / `collectAsStateWithLifecycle` and coroutine scopes only.
Screens must never crash when the shell command fails (show ErrorBanner/EmptyState instead).

Conventions:

* One file per screen: `ui/screens/<area>/<Name>Screen.kt`, package `dev.rootops.ui.screens.<area>`.
* Signature is `@Composable fun XxxScreen(container: AppContainer)` plus only optional args with
  defaults when a route carries a value (e.g. `EditorScreen(container, initialPath: String? = null)`,
  `PropertiesScreen(container, path: String? = null)`). No required params, no `NavController`.
  Navigation is the parent's job.
* `collectAsStateWithLifecycle()` for `StateFlow`, `LaunchedEffect(Unit)` for initial loads,
  `rememberCoroutineScope()` for user actions.
* Every data load is wrapped in `runCatching` (or a `try/catch`) and mapped to
  `ErrorBanner(message = ..., onRetry = ...)` or `EmptyState(...)`; never let an exception escape
  a composable or a launched coroutine.
* Loading state → `LoadingRow()`. Empty list → `EmptyState(Icons.Filled.X, title, message)`.
* Screen chrome → `AppScaffold(title, subtitle, onBack = null, actions = { ... })`.
* Destructive actions → `ConfirmDialog(..., destructive = true)` when
  `container.settings.settings.value.confirmDelete`.
* Colors come from `MaterialTheme.colorScheme` (`primary`, `secondary`, `error`, `tertiary` = warning,
  `onSurfaceVariant` = muted, `outline` = border) or the named palette in `dev.rootops.ui.theme`.

### Navigation (integrator-owned, `dev.rootops.ui.nav`)

File `app/src/main/java/dev/rootops/ui/nav/Route.kt`. The integrator writes this file; screens only
receive the resulting callbacks. Verbatim contract:

```kotlin
package dev.rootops.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

sealed interface Route {
    data object Home : Route
    data object Files : Route
    data object Terminal : Route
    data object Processes : Route
    data object Logcat : Route
    data object Network : Route
    data object Scripts : Route
    data object Schedule : Route
    data object Mounts : Route
    data object Packages : Route
    data object Ssh : Route
    data object Sftp : Route
    data object Settings : Route
    data object Analyzer : Route
    data object Tools : Route
    data class Editor(val path: String) : Route
    data class FileProperties(val path: String) : Route
}

val LocalNavigator = staticCompositionLocalOf<(Route) -> Unit> { {} }
```

Screens do **not** import `Route`/`LocalNavigator`; the integrator's NavHost resolves them.

---

## Utilities (`dev.rootops.core.util`)

```kotlin
fun formatBytes(bytes: Long): String            // "1.4 MiB", "512 B" (binary units)
fun formatTimestamp(epochSeconds: Long): String // "2026-10-24 15:03" (local zone)
fun formatDuration(ms: Long): String            // "310ms", "42s", "3m 07s", "1h 04m"
fun truncateMiddle(s: String, max: Int): String // middle elision with "…"
fun modeToRwx(mode: String): String             // "0755" -> "rwxr-xr-x"; rwx input passes through

class JsonStore(val file: File) {
    fun read(): JSONObject                                   // empty object when missing/corrupt
    fun write(obj: JSONObject)                               // atomic temp+rename, creates parents
    fun update(block: (JSONObject) -> Unit)                  // read-modify-write under one lock
}
```

All `JsonStore` methods are `synchronized(this)`; safe to share one instance across coroutines.

---

## Theme (`dev.rootops.ui.theme`)

Palette values (`Color.kt`): `ConsoleBackground` `#0B0F0D`, `ConsoleSurface` `#131A16`,
`ConsoleAccent` `#7BD88F`, `ConsoleSecondary` `#6FB3D2`, `ConsoleError` `#E06C75`,
`ConsoleOnSurface` `#D8E3DA`, `ConsoleMuted` `#8A9A8F`, `ConsoleWarning` `#E5C07B`,
`ConsoleBorder` `#24302A`; plus `Light*` counterparts.

`Type.kt`: `val MonoFont = FontFamily.Monospace`, `val RootOpsTypography = Typography(...)`.

`Theme.kt`:

```kotlin
@Composable fun RootOpsTheme(darkTheme: Boolean = true, content: @Composable () -> Unit)
```

ColorScheme mapping: `primary` accent, `secondary` blue, `tertiary` warning, `error` red,
`background`/`surface` console tones, `onSurfaceVariant` muted, `outline` border.

---

## Components (`dev.rootops.ui.components`)

```kotlin
@Composable fun AppScaffold(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
)
@Composable fun EmptyState(
    icon: ImageVector, title: String, message: String? = null,
    actionLabel: String? = null, onAction: (() -> Unit)? = null,
)
@Composable fun LoadingRow(text: String = "Loading…")
@Composable fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null)
@Composable fun ConfirmDialog(
    title: String, message: String, confirmLabel: String = "Confirm",
    destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit,
)
@Composable fun SectionHeader(text: String, trailing: (@Composable () -> Unit)? = null)
@Composable fun StatTile(label: String, value: String, caption: String? = null, modifier: Modifier = Modifier)
@Composable fun MonoText(text: String, modifier: Modifier = Modifier, size: TextUnit = 12.sp, color: Color = MaterialTheme.colorScheme.onSurface)
@Composable fun TagChip(text: String, color: Color = MaterialTheme.colorScheme.primary, selected: Boolean = false, onClick: (() -> Unit)? = null)
@Composable fun KeyValueRow(label: String, value: String, mono: Boolean = true)
```

Only `material3` + `material-icons-extended` are used — no extra dependencies exist.

---

## Ground rules for workers

* Stay inside your assigned package; never edit `core/api`, `core/util`, `ui/theme`,
  `ui/components`, `ui/nav`, `AppContainer.kt`, or the Gradle files.
* No new Gradle dependencies. Available: Compose BOM 2024.12.01 (material3, ui, tooling-preview),
  material-icons-extended, activity-compose, lifecycle-runtime/viewmodel-compose,
  navigation-compose 2.8.5, kotlinx-coroutines-android 1.9.0, jsch 0.2.20.
* No `viewModel { }` in screens; no blocking calls on the main thread (`withContext(Dispatchers.IO)`).
* No TODO/stub bodies, no placeholders, no fake data in shipped impls.
