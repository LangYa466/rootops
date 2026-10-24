package dev.rootops.ui.screens.processes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rootops.AppContainer
import dev.rootops.core.api.ProcInfo
import dev.rootops.core.util.truncateMiddle
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.TagChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val AUTO_REFRESH_MS = 2_000L
private const val METRICS_WIDTH_DP = 86
private const val BAR_HEIGHT_DP = 3

/** Ordering applied to the visible process list. */
private enum class ProcSort(val label: String) {
    CPU("CPU"),
    MEM("MEM"),
    PID("PID"),
    NAME("NAME"),
}

/** A signal the screen is allowed to send through [dev.rootops.core.api.ProcessRepository.kill]. */
private data class ProcSignal(val name: String, val number: Int, val description: String)

private val SIGNALS: List<ProcSignal> = listOf(
    ProcSignal("SIGTERM", 15, "Graceful termination request"),
    ProcSignal("SIGKILL", 9, "Immediate, uncatchable kill"),
    ProcSignal("SIGSTOP", 19, "Suspend the process"),
    ProcSignal("SIGCONT", 18, "Resume a suspended process"),
)

private val KILL_SIGNAL: ProcSignal = SIGNALS.first { it.number == 9 }

/**
 * Live process table backed by [dev.rootops.core.api.ProcessRepository].
 *
 * Auto-refreshes every two seconds while enabled, supports a substring filter over
 * name/pid/user, four sort orders, a per-process detail sheet and signal delivery.
 */
@Composable
fun ProcessScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val rootAvailable by container.shell.rootAvailable.collectAsState()

    var processes by remember { mutableStateOf<List<ProcInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    var autoRefresh by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ProcSort.CPU) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var detailFor by remember { mutableStateOf<ProcInfo?>(null) }
    var detailText by remember { mutableStateOf("") }
    var detailBusy by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }

    var actionTarget by remember { mutableStateOf<ProcInfo?>(null) }
    var killConfirm by remember { mutableStateOf<ProcInfo?>(null) }

    suspend fun loadProcesses(showSpinner: Boolean) {
        if (showSpinner) loading = true
        val result = runCatching {
            withContext(Dispatchers.IO) { container.processes.topSnapshot() }
        }
        result.onSuccess { snapshot ->
            processes = snapshot
            loadError = null
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            loadError = failure.message?.takeIf { it.isNotBlank() }
                ?: "Unable to read the process table"
        }
        loading = false
    }

    fun sendSignal(target: ProcInfo, signal: ProcSignal) {
        actionTarget = null
        if (target.pid <= 1) {
            status = "Refusing to signal PID ${target.pid} (${target.name}): init must keep running"
            return
        }
        scope.launch {
            val delivered = runCatching {
                withContext(Dispatchers.IO) {
                    container.processes.kill(target.pid, signal.number)
                }
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                false
            }
            status = if (delivered) {
                "Sent ${signal.name} (${signal.number}) to PID ${target.pid} ${target.name}"
            } else {
                "Failed to send ${signal.name} to PID ${target.pid} ${target.name} — root is required"
            }
            if (delivered) loadProcesses(false)
        }
    }

    val visible = remember(processes, query, sort) {
        val needle = query.trim().lowercase()
        val filtered = if (needle.isEmpty()) {
            processes
        } else {
            processes.filter { info ->
                info.name.lowercase().contains(needle) ||
                    info.pid.toString().contains(needle) ||
                    info.user.lowercase().contains(needle)
            }
        }
        when (sort) {
            ProcSort.CPU -> filtered.sortedWith(
                compareByDescending<ProcInfo> { it.cpuPercent }.thenBy { it.pid },
            )
            ProcSort.MEM -> filtered.sortedWith(
                compareByDescending<ProcInfo> { it.memPercent }.thenBy { it.pid },
            )
            ProcSort.PID -> filtered.sortedBy { it.pid }
            ProcSort.NAME -> filtered.sortedWith(
                compareBy<ProcInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.pid },
            )
        }
    }

    LaunchedEffect(container) {
        loadProcesses(true)
    }

    LaunchedEffect(autoRefresh) {
        if (!autoRefresh) return@LaunchedEffect
        while (true) {
            delay(AUTO_REFRESH_MS)
            loadProcesses(false)
        }
    }

    LaunchedEffect(detailFor?.pid) {
        val pid = detailFor?.pid ?: return@LaunchedEffect
        detailBusy = true
        detailError = null
        detailText = ""
        runCatching {
            withContext(Dispatchers.IO) { container.processes.detail(pid) }
        }.onSuccess { text ->
            detailText = text
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            detailError = failure.message?.takeIf { it.isNotBlank() }
                ?: "Unable to read /proc/$pid"
        }
        detailBusy = false
    }

    AppScaffold(
        title = "Processes",
        subtitle = if (processes.isEmpty()) null else "${processes.size} processes",
        actions = {
            IconButton(onClick = { autoRefresh = !autoRefresh }) {
                Icon(
                    imageVector = if (autoRefresh) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (autoRefresh) "Stop auto-refresh" else "Start auto-refresh",
                )
            }
            IconButton(onClick = { scope.launch { loadProcesses(true) } }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            text = "Filter name / pid / user",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text(text = sort.label, style = MaterialTheme.typography.labelMedium)
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Sort order",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        ProcSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                onClick = {
                                    sort = option
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${processes.size} total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (visible.size != processes.size) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${visible.size} shown",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (autoRefresh) {
                    TagChip(
                        text = "live 2s",
                        color = MaterialTheme.colorScheme.tertiary,
                        selected = true,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                TagChip(
                    text = "root",
                    color = MaterialTheme.colorScheme.primary,
                    selected = rootAvailable,
                )
            }

            if (loadError != null) {
                ErrorBanner(
                    message = loadError.orEmpty(),
                    onRetry = { scope.launch { loadProcesses(true) } },
                )
            }

            if (status != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            text = status.orEmpty(),
                            modifier = Modifier.weight(1f),
                            size = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { status = null }) {
                            Text(text = "Dismiss", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    loading && processes.isEmpty() -> LoadingRow("Reading process table…")
                    processes.isEmpty() && loadError != null -> EmptyState(
                        icon = Icons.Filled.Memory,
                        title = "Process table unavailable",
                        message = "The shell could not read /proc. Check that the root shell is working.",
                        actionLabel = "Retry",
                        onAction = { scope.launch { loadProcesses(true) } },
                    )
                    processes.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Memory,
                        title = "No processes",
                        message = "The process table came back empty.",
                        actionLabel = "Reload",
                        onAction = { scope.launch { loadProcesses(true) } },
                    )
                    visible.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Memory,
                        title = "No matches",
                        message = "No process name, pid or user contains \"${query.trim()}\".",
                        actionLabel = "Clear filter",
                        onAction = { query = "" },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = visible, key = { it.pid }) { info ->
                            ProcessRow(
                                info = info,
                                onOpenDetail = { detailFor = info },
                                onOpenActions = { actionTarget = info },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }

    val detail = detailFor
    if (detail != null) {
        AlertDialog(
            onDismissRequest = { detailFor = null },
            title = {
                Text(
                    text = "${detail.name} · pid ${detail.pid}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                when {
                    detailBusy -> LoadingRow("Reading /proc/${detail.pid}…")
                    detailError != null -> Text(
                        text = detailError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MonoText(
                            text = detailText.ifBlank { "No details reported for pid ${detail.pid}." },
                            size = 11.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailFor = null }) { Text("Close") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val target = actionTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                Text(
                    text = "Signal ${target.name}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MonoText(
                        text = "pid ${target.pid} · ppid ${target.ppid} · ${truncateMiddle(target.user, 24)}",
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (target.pid <= 1) {
                        Text(
                            text = "Refusing to signal PID ${target.pid} (${target.name}). " +
                                "Init and its direct children are system critical.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        SIGNALS.forEach { signal ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (signal.number == KILL_SIGNAL.number) {
                                            killConfirm = target
                                            actionTarget = null
                                        } else {
                                            sendSignal(target, signal)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = signal.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (signal.number == KILL_SIGNAL.number) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = signal.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MonoText(
                                    text = signal.number.toString(),
                                    size = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Close") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val pendingKill = killConfirm
    if (pendingKill != null) {
        ConfirmDialog(
            title = "Force kill ${pendingKill.name}?",
            message = "PID ${pendingKill.pid} will be terminated immediately with SIGKILL (9). " +
                "Unsaved data in that process is lost and cannot be recovered.",
            confirmLabel = "SIGKILL",
            destructive = true,
            onConfirm = {
                killConfirm = null
                sendSignal(pendingKill, KILL_SIGNAL)
            },
            onDismiss = { killConfirm = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcessRow(
    info: ProcInfo,
    onOpenDetail: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpenDetail,
                onLongClick = onOpenActions,
            )
            .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = info.pid.toString(),
                    modifier = Modifier.width(52.dp),
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                MonoText(
                    text = truncateMiddle(info.name.ifBlank { "(unnamed)" }, 30),
                    modifier = Modifier.weight(1f),
                    size = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = truncateMiddle(info.user.ifBlank { "?" }, 18),
                    size = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                MonoText(
                    text = info.stat.toString(),
                    size = 10.sp,
                    color = stateColor(info.stat),
                )
                Spacer(Modifier.width(8.dp))
                MonoText(
                    text = formatMiB(info.rssKb),
                    size = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                MonoText(
                    text = "${info.threads}t",
                    size = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.width(METRICS_WIDTH_DP.dp)) {
            MetricBar(
                label = "CPU",
                percent = info.cpuPercent,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            MetricBar(
                label = "MEM",
                percent = info.memPercent,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        IconButton(onClick = onOpenActions) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Actions for ${info.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricBar(label: String, percent: Float, color: Color) {
    val fraction = (percent / 100f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        MonoText(
            text = "${percent.toInt()}%",
            size = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(2.dp))
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        strokeCap = StrokeCap.Round,
    )
}

@Composable
private fun stateColor(state: Char): Color = when (state) {
    'R' -> MaterialTheme.colorScheme.primary
    'D' -> MaterialTheme.colorScheme.tertiary
    'Z' -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Resident set size expressed in MiB, e.g. `84.2M`. */
private fun formatMiB(rssKb: Long): String {
    if (rssKb <= 0L) return "0.0M"
    return String.format(Locale.US, "%.1fM", rssKb / 1024.0)
}
