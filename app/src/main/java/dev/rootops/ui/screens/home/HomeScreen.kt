package dev.rootops.ui.screens.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.StorageStat
import dev.rootops.core.api.SystemSnapshot
import dev.rootops.core.util.formatBytes
import dev.rootops.core.util.formatDuration
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.KeyValueRow
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.StatTile
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.nav.LocalNavigator
import dev.rootops.ui.nav.Route
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val REFRESH_INTERVAL_MS = 3_000L
private const val PROC_READ_LIMIT = 256 * 1024
private const val INTERNAL_STORAGE_PATH = "/storage/emulated/0"
private const val DATA_PATH = "/data"

/**
 * Operator dashboard: live device metrics read straight from `/proc` plus shortcuts into
 * every other area of the app. Refreshes itself every three seconds while visible.
 */
@Composable
fun HomeScreen(container: AppContainer) {
    val context = LocalContext.current
    val navigate = LocalNavigator.current
    val dashboard = remember(container) { HomeDashboard(context.applicationContext, container) }
    val rootAvailable by container.shell.rootAvailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(dashboard) {
        while (true) {
            dashboard.refresh()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    val model = Build.MODEL.orEmpty().ifBlank { "Android device" }
    val release = Build.VERSION.RELEASE.orEmpty().ifBlank { "-" }

    AppScaffold(
        title = "RootOps",
        subtitle = "$model · Android $release",
        actions = {
            IconButton(onClick = { scope.launch { dashboard.refresh() } }) {
                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val error = dashboard.error
            if (error != null) {
                ErrorBanner(
                    message = error,
                    onRetry = { scope.launch { dashboard.refresh() } },
                )
            }

            val snapshot = dashboard.snapshot
            when {
                snapshot != null -> {
                    HeaderCard(snapshot = snapshot, rootAvailable = rootAvailable)
                    SectionHeader(text = "Metrics")
                    MetricGrid(snapshot = snapshot)
                    SectionHeader(text = "Quick actions")
                    QuickActions(onNavigate = navigate)
                    Spacer(Modifier.height(24.dp))
                }

                dashboard.loading -> LoadingRow(text = "Reading system state…")

                else -> EmptyState(
                    icon = Icons.Outlined.Info,
                    title = "No system data",
                    message = "The device state could not be read.",
                    actionLabel = "Retry",
                    onAction = { scope.launch { dashboard.refresh() } },
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(snapshot: SystemSnapshot, rootAvailable: Boolean) {
    val rootMode = when {
        rootAvailable -> "root"
        snapshot.rootMode == "denied" -> "denied"
        else -> "no root"
    }
    val rootColor = when (rootMode) {
        "root" -> MaterialTheme.colorScheme.primary
        "denied" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val uptime = if (snapshot.uptimeSec >= 0) {
        formatDuration(snapshot.uptimeSec * 1_000L)
    } else {
        "-"
    }
    val load = if (snapshot.load1.isNaN()) {
        "-"
    } else {
        String.format(
            Locale.US,
            "%.2f %.2f %.2f",
            snapshot.load1,
            snapshot.load5,
            snapshot.load15,
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagChip(text = rootMode, color = rootColor, selected = true)
                Spacer(Modifier.weight(1f))
                MonoText(
                    text = "up $uptime",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow(label = "Load average", value = load)
            KeyValueRow(
                label = "Kernel",
                value = snapshot.kernel.ifBlank { "-" },
            )
        }
    }
}

private data class TileSpec(val label: String, val value: String, val caption: String?)

@Composable
private fun MetricGrid(snapshot: SystemSnapshot) {
    val tiles = buildList {
        val totalKb = snapshot.memTotalKb
        val availableKb = snapshot.memAvailableKb
        if (totalKb > 0 && availableKb >= 0) {
            val usedKb = (totalKb - availableKb).coerceAtLeast(0)
            val percent = (usedKb * 100.0 / totalKb).roundToInt()
            add(
                TileSpec(
                    label = "RAM",
                    value = "${formatBytes(usedKb * 1_024L)} / ${formatBytes(totalKb * 1_024L)}",
                    caption = "$percent% used",
                )
            )
        } else {
            add(TileSpec(label = "RAM", value = "-", caption = "unavailable"))
        }

        if (snapshot.cpuCores > 0) {
            add(
                TileSpec(
                    label = "CPU",
                    value = "${snapshot.cpuCores} cores",
                    caption = snapshot.cpuModel.ifBlank { "unknown model" },
                )
            )
        } else {
            add(TileSpec(label = "CPU", value = "-", caption = "unavailable"))
        }

        if (snapshot.storage.isEmpty()) {
            add(TileSpec(label = "Storage", value = "-", caption = "unavailable"))
        } else {
            snapshot.storage.forEach { stat ->
                add(storageTile(stat))
            }
        }

        if (snapshot.batteryPercent >= 0) {
            add(
                TileSpec(
                    label = "Battery",
                    value = "${snapshot.batteryPercent}%",
                    caption = if (snapshot.charging) "charging" else "on battery",
                )
            )
        } else {
            add(TileSpec(label = "Battery", value = "-", caption = "no battery data"))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTiles.forEach { tile ->
                    StatTile(
                        label = tile.label,
                        value = tile.value,
                        caption = tile.caption,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTiles.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun storageTile(stat: StorageStat): TileSpec {
    val total = stat.totalBytes
    val free = stat.freeBytes.coerceIn(0L, total.coerceAtLeast(0L))
    val used = (total - free).coerceAtLeast(0L)
    val percent = if (total > 0) (used * 100.0 / total).roundToInt() else 0
    return TileSpec(
        label = volumeLabel(stat.path),
        value = "${formatBytes(used)} used",
        caption = "$percent% · ${formatBytes(free)} free",
    )
}

private fun volumeLabel(path: String): String = when (path) {
    DATA_PATH -> "Data"
    INTERNAL_STORAGE_PATH -> "Internal"
    else -> path.substringAfterLast('/').ifBlank { path }
}

private data class QuickAction(val label: String, val icon: ImageVector, val route: Route)

@Composable
private fun QuickActions(onNavigate: (Route) -> Unit) {
    val actions = remember {
        listOf(
            QuickAction("Files", Icons.Outlined.Folder, Route.Files),
            QuickAction("Terminal", Icons.Outlined.Terminal, Route.Terminal),
            QuickAction("Processes", Icons.Outlined.Memory, Route.Processes),
            QuickAction("Logcat", Icons.Outlined.Article, Route.Logcat),
            QuickAction("Network", Icons.Outlined.Wifi, Route.Network),
            QuickAction("Scripts", Icons.Outlined.Code, Route.Scripts),
            QuickAction("Mounts", Icons.Outlined.Storage, Route.Mounts),
            QuickAction("Packages", Icons.Outlined.Apps, Route.Packages),
            QuickAction("SSH", Icons.Outlined.Cloud, Route.Ssh),
            QuickAction("SFTP", Icons.Outlined.CloudUpload, Route.Sftp),
            QuickAction("Settings", Icons.Outlined.Settings, Route.Settings),
        )
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(actions, key = { it.label }) { action ->
            OutlinedButton(onClick = { onNavigate(action.route) }) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = action.label)
            }
        }
    }
}

/** Owns the polling state for [HomeScreen]; every read is defensive so a failing procfs entry never crashes. */
private class HomeDashboard(
    private val context: Context,
    private val container: AppContainer,
) {
    var snapshot: SystemSnapshot? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private val refreshLock = Mutex()

    suspend fun refresh() {
        if (!refreshLock.tryLock()) return
        try {
            loading = true
            try {
                container.shell.refreshRoot()
                val next = buildSnapshot()
                snapshot = next
                error = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                error = "Unable to read system state: " +
                    (failure.message ?: failure.javaClass.simpleName)
            }
        } finally {
            loading = false
            refreshLock.unlock()
        }
    }

    private suspend fun buildSnapshot(): SystemSnapshot = withContext(Dispatchers.IO) {
        val uptimeSec = readProcText("/proc/uptime")
            ?.trim()
            ?.substringBefore(' ')
            ?.toDoubleOrNull()
            ?.toLong()
            ?: -1L
        val load = parseLoadAverage(readProcText("/proc/loadavg"))
        val memory = parseMemInfo(readProcText("/proc/meminfo"))
        val cpu = parseCpuInfo(readProcText("/proc/cpuinfo"))
        val battery = readBattery(context)
        val rootAvailable = container.shell.rootAvailable.value

        SystemSnapshot(
            uptimeSec = uptimeSec,
            load1 = load?.get(0) ?: Float.NaN,
            load5 = load?.get(1) ?: Float.NaN,
            load15 = load?.get(2) ?: Float.NaN,
            memTotalKb = memory?.first ?: -1L,
            memAvailableKb = memory?.second ?: -1L,
            cpuCores = cpu.cores,
            cpuModel = cpu.model,
            kernel = System.getProperty("os.version").orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            rootAvailable = rootAvailable,
            rootMode = when {
                rootAvailable -> "root"
                suBinaryPresent() -> "denied"
                else -> "no root"
            },
            storage = readStorage(),
            batteryPercent = battery.first,
            charging = battery.second,
        )
    }
}

private fun readProcText(path: String): String? = try {
    val file = File(path)
    if (!file.exists() || !file.canRead()) {
        null
    } else {
        file.bufferedReader().use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(8 * 1_024)
            while (builder.length < PROC_READ_LIMIT) {
                val read = reader.read(buffer)
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }
            builder.toString()
        }
    }
} catch (e: Exception) {
    null
}

private fun parseLoadAverage(text: String?): List<Float>? {
    if (text == null) return null
    val parts = text.trim().split(Regex("\\s+")).take(3)
    if (parts.size < 3) return null
    val load1 = parts[0].toFloatOrNull() ?: return null
    val load5 = parts[1].toFloatOrNull() ?: return null
    val load15 = parts[2].toFloatOrNull() ?: return null
    return listOf(load1, load5, load15)
}

private fun parseMemInfo(text: String?): Pair<Long, Long>? {
    if (text == null) return null
    var total = -1L
    var available = -1L
    var free = -1L
    var buffers = 0L
    var cached = 0L
    for (line in text.lineSequence()) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
        when (key) {
            "MemTotal" -> total = value
            "MemAvailable" -> available = value
            "MemFree" -> free = value
            "Buffers" -> buffers = value
            "Cached" -> cached = value
        }
    }
    if (total <= 0) return null
    val usable = when {
        available >= 0 -> available
        free >= 0 -> free + buffers + cached
        else -> -1L
    }
    return total to usable
}

private class CpuInfo(val cores: Int, val model: String)

private fun parseCpuInfo(text: String?): CpuInfo {
    var cores = 0
    var model = ""
    if (text != null) {
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "processor" -> cores++
                "model name" -> if (model.isEmpty()) model = value
                "Hardware" -> if (model.isEmpty()) model = value
                "Processor" -> if (model.isEmpty()) model = value
            }
        }
    }
    if (cores <= 0) cores = Runtime.getRuntime().availableProcessors()
    return CpuInfo(cores = cores, model = model)
}

private fun readStorage(): List<StorageStat> {
    val paths = listOf(DATA_PATH, INTERNAL_STORAGE_PATH)
    return paths.mapNotNull { path ->
        runCatching {
            if (path != DATA_PATH && !File(path).exists()) {
                null
            } else {
                val stat = StatFs(path)
                val total = stat.totalBytes
                if (total <= 0L) null else StorageStat(path, total, stat.availableBytes)
            }
        }.getOrNull()
    }
}

private fun readBattery(context: Context): Pair<Int, Boolean> {
    var percent = -1
    var charging = false
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    if (manager != null) {
        val capacity = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        if (capacity in 0..100) percent = capacity
        charging = runCatching { manager.isCharging }.getOrDefault(false)
    }
    if (percent !in 0..100 || !charging) {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (percent !in 0..100 && level >= 0 && scale > 0) {
                percent = (level * 100f / scale).roundToInt().coerceIn(0, 100)
            }
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = charging ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
    return percent to charging
}

private fun suBinaryPresent(): Boolean {
    val candidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
    )
    if (candidates.any { candidate -> runCatching { File(candidate).exists() }.getOrDefault(false) }) {
        return true
    }
    val path = System.getenv("PATH").orEmpty()
    return path.split(File.pathSeparatorChar).any { dir ->
        dir.isNotBlank() && runCatching { File(dir, "su").exists() }.getOrDefault(false)
    }
}
