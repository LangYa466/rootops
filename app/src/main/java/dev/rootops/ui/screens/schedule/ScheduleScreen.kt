package dev.rootops.ui.screens.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.ScheduleItem
import dev.rootops.core.api.Scheduler
import dev.rootops.core.api.ScriptItem
import dev.rootops.core.util.formatTimestamp
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.TagChip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_INTERVAL = 1
private const val MAX_INTERVAL = 10_080
private const val DEFAULT_INTERVAL = 60
private const val RECENT_RUN_LIMIT = 30

/**
 * Schedule management for [dev.rootops.core.api.Scheduler].
 *
 * The scheduler is an in-app component: it only ticks while the process is
 * alive. Entering this screen starts it (a no-op when already running) and the
 * toolbar exposes a stop/start toggle plus a live status chip. Schedules can be
 * created, edited, enabled/disabled and deleted; deleting a schedule never
 * touches the underlying script.
 */
@Composable
fun ScheduleScreen(container: AppContainer) {
    val scheduler = container.scheduler
    val scriptStore = container.scripts

    val schedules by scheduler.schedules.collectAsStateWithLifecycle()
    val scripts by scriptStore.scripts.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var schedulerRunning by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleItem?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduleItem?>(null) }

    // The run log is not part of the Scheduler contract. Resolve it
    // reflectively so the screen also works with implementations that omit it.
    val recentRuns by produceState<List<String>>(
        initialValue = emptyList(),
        key1 = scheduler,
    ) {
        val flow = scheduler.recentRunsFlow() ?: return@produceState
        try {
            flow.collect { value = it }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // A broken log stream must not take the screen down.
        }
    }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                scheduler.refresh()
                scriptStore.refresh()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = t.message ?: "Could not load schedules"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        schedulerRunning = try {
            scheduler.start()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun toggle(item: ScheduleItem, enabled: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { scheduler.save(item.copy(enabled = enabled)) }
                error = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not update the schedule"
            }
        }
    }

    fun save(item: ScheduleItem) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { scheduler.save(item) }
                error = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not save the schedule"
            }
        }
    }

    fun delete(item: ScheduleItem) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { scheduler.delete(item.id) }
                error = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not delete the schedule"
            }
        }
    }

    AppScaffold(
        title = "Schedules",
        subtitle = when {
            loading && schedules.isEmpty() -> "Loading…"
            schedules.size == 1 -> "1 schedule"
            else -> "${schedules.size} schedules"
        },
        actions = {
            TagChip(
                text = if (schedulerRunning) "scheduler running" else "scheduler stopped",
                color = if (schedulerRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                selected = schedulerRunning,
            )
            IconButton(
                onClick = {
                    if (schedulerRunning) {
                        runCatching { scheduler.stop() }
                        schedulerRunning = false
                    } else {
                        schedulerRunning = runCatching {
                            scheduler.start()
                            true
                        }.getOrDefault(false)
                    }
                },
            ) {
                Icon(
                    imageVector = if (schedulerRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (schedulerRunning) "Stop scheduler" else "Start scheduler",
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = null
                    showEditor = true
                },
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "New schedule")
            }
        },
    ) { padding ->
        val currentError = error
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (currentError != null) {
                item(key = "error") {
                    ErrorBanner(
                        message = currentError,
                        onRetry = { reloadKey++ },
                    )
                }
            }
            if (loading && schedules.isEmpty()) {
                item(key = "loading") { LoadingRow("Loading schedules…") }
            }
            if (!loading && schedules.isEmpty() && currentError == null) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.Schedule,
                        title = "No schedules yet",
                        message = "Run a stored script automatically on a fixed interval.",
                        actionLabel = "New schedule",
                        onAction = {
                            editing = null
                            showEditor = true
                        },
                    )
                }
            }
            items(schedules, key = { it.id }) { item ->
                val scriptName = scripts.firstOrNull { it.id == item.scriptId }?.name
                    ?: scriptStore.get(item.scriptId)?.name
                ScheduleCard(
                    item = item,
                    scriptName = scriptName,
                    onToggle = { enabled -> toggle(item, enabled) },
                    onEdit = {
                        editing = item
                        showEditor = true
                    },
                    onDelete = { pendingDelete = item },
                )
            }
            if (recentRuns.isNotEmpty()) {
                item(key = "recent-header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("Recent runs")
                }
                items(recentRuns.takeLast(RECENT_RUN_LIMIT).asReversed()) { line ->
                    MonoText(
                        text = line,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp),
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showEditor) {
        ScheduleEditorDialog(
            initial = editing,
            scripts = scripts,
            onDismiss = { showEditor = false },
            onSave = { item ->
                showEditor = false
                save(item)
            },
        )
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDialog(
            title = "Delete schedule?",
            message = "Remove \"${target.name}\" from the scheduler. The script itself is kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                delete(target)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** One schedule row: name, script, interval, last run, enable switch and delete. */
@Composable
private fun ScheduleCard(
    item: ScheduleItem,
    scriptName: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .clickable(onClick = onEdit),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name.ifBlank { "Schedule #${item.id}" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = item.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = scriptName ?: "missing",
                    modifier = Modifier.weight(1f),
                    color = if (scriptName == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                TagChip(text = "every ${item.intervalMinutes} min")
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagChip(
                    text = if (item.enabled) "enabled" else "paused",
                    color = if (item.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    selected = item.enabled,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.lastRun?.let { "last run ${formatTimestamp(it)}" } ?: "never run",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete schedule",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Create/edit form. [initial] is `null` when a new schedule is being added. */
@Composable
private fun ScheduleEditorDialog(
    initial: ScheduleItem?,
    scripts: List<ScriptItem>,
    onDismiss: () -> Unit,
    onSave: (ScheduleItem) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var scriptId by remember(initial) { mutableStateOf(initial?.scriptId) }
    var intervalText by remember(initial) {
        mutableStateOf((initial?.intervalMinutes ?: DEFAULT_INTERVAL).toString())
    }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(scripts) {
        if (scriptId == null) scriptId = scripts.firstOrNull()?.id
    }

    val intervalValue = intervalText.trim().toIntOrNull()
    val intervalError: String? = when {
        intervalText.isBlank() -> "Interval is required"
        intervalValue == null -> "Enter a whole number of minutes"
        intervalValue < MIN_INTERVAL || intervalValue > MAX_INTERVAL ->
            "Must be between $MIN_INTERVAL and $MAX_INTERVAL"
        else -> null
    }
    val selectedScript = scripts.firstOrNull { it.id == scriptId }
    val canSave = scriptId != null && intervalError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New schedule" else "Edit schedule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        enabled = scripts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = selectedScript?.let { script ->
                                script.name.ifBlank { "Script #${script.id}" }
                            }
                                ?: scriptId?.let { "missing script (#$it)" }
                                ?: if (scripts.isEmpty()) "No scripts available" else "Select a script",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        scripts.forEach { script ->
                            DropdownMenuItem(
                                text = { Text(script.name.ifBlank { "Script #${script.id}" }) },
                                onClick = {
                                    scriptId = script.id
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                if (scripts.isEmpty()) {
                    Text(
                        text = "Create a script first — schedules run stored scripts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { value -> intervalText = value.filter { it.isDigit() }.take(5) },
                    label = { Text("Interval (minutes)") },
                    singleLine = true,
                    isError = intervalError != null,
                    supportingText = {
                        Text(intervalError ?: "$MIN_INTERVAL – $MAX_INTERVAL minutes")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val interval = intervalValue ?: return@TextButton
                    val selected = scriptId ?: return@TextButton
                    onSave(
                        ScheduleItem(
                            id = initial?.id ?: 0L,
                            scriptId = selected,
                            name = name.trim().ifBlank {
                                scripts.firstOrNull { it.id == selected }?.name ?: "Schedule"
                            },
                            intervalMinutes = interval,
                            enabled = enabled,
                            lastRun = initial?.lastRun,
                        ),
                    )
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The shipped scheduler publishes its execution log through a `recentRuns`
 * property that is not part of the [Scheduler] contract. Resolve it at runtime
 * and return `null` when an implementation does not expose it.
 */
@Suppress("UNCHECKED_CAST")
private fun Scheduler.recentRunsFlow(): StateFlow<List<String>>? = try {
    val getter = javaClass.getMethod("getRecentRuns")
    getter.invoke(this) as? StateFlow<List<String>>
} catch (_: Throwable) {
    null
}
