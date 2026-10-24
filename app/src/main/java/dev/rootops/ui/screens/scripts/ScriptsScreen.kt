package dev.rootops.ui.screens.scripts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.ScriptItem
import dev.rootops.core.util.formatTimestamp
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Lines kept in the run console; older output is dropped once the cap is hit. */
private const val MAX_CONSOLE_LINES = 2_000

/** Height of the docked console panel. */
private val ConsoleHeight = 240.dp

private val PanelShape = RoundedCornerShape(12.dp)

/**
 * Stored shell scripts: browse, edit, favourite, run and delete.
 *
 * Scripts are streamed from [ScriptStore.scripts][dev.rootops.core.api.ScriptStore.scripts];
 * running a script collects [ScriptStore.run][dev.rootops.core.api.ScriptStore.run]
 * into a docked console that can be stopped at any time.
 */
@Composable
fun ScriptsScreen(container: AppContainer) {
    val scripts by container.scripts.scripts.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<ScriptItem?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScriptItem?>(null) }

    var consoleVisible by remember { mutableStateOf(false) }
    var consoleTitle by remember { mutableStateOf("") }
    var consoleLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var runningId by remember { mutableStateOf<Long?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    // Bumped on every start/stop so a cancelled run cannot clobber the next one.
    var runToken by remember { mutableStateOf(0L) }

    fun appendConsole(line: String) {
        consoleLines = (consoleLines + line).takeLast(MAX_CONSOLE_LINES)
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                container.scripts.refresh()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not reload scripts"
            } finally {
                loading = false
            }
        }
    }

    fun startRun(item: ScriptItem) {
        runJob?.cancel()
        val token = runToken + 1L
        runToken = token
        consoleTitle = item.name
        consoleLines = emptyList()
        consoleVisible = true
        runningId = item.id
        runJob = scope.launch {
            try {
                container.scripts.run(item.id).collect { line ->
                    if (runToken == token) appendConsole(line)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (runToken == token) {
                    appendConsole("# error ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                if (runToken == token) runningId = null
            }
        }
    }

    fun stopRun() {
        val job = runJob
        runJob = null
        runToken += 1L
        runningId = null
        job?.cancel()
        appendConsole("# stopped by user")
    }

    fun toggleFavorite(item: ScriptItem) {
        scope.launch {
            try {
                container.scripts.save(item.copy(favorite = !item.favorite))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not update \"${item.name}\""
            }
        }
    }

    fun deleteScript(item: ScriptItem) {
        scope.launch {
            try {
                container.scripts.delete(item.id)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not delete \"${item.name}\""
            }
        }
    }

    fun requestDelete(item: ScriptItem) {
        if (settings.confirmDelete) {
            pendingDelete = item
        } else {
            deleteScript(item)
        }
    }

    fun saveScript(name: String, body: String) {
        val target = editorTarget
        scope.launch {
            saving = true
            try {
                container.scripts.save(
                    ScriptItem(
                        id = target?.id ?: 0L,
                        name = name.trim(),
                        body = body,
                        created = target?.created ?: 0L,
                        lastRun = target?.lastRun,
                        lastExit = target?.lastExit,
                        favorite = target?.favorite ?: false,
                    )
                )
                editorOpen = false
                editorTarget = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.message ?: "Could not save the script"
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            container.scripts.refresh()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = t.message ?: "Could not load scripts"
        }
    }

    AppScaffold(
        title = "Scripts",
        subtitle = "${scripts.size} stored",
        actions = {
            IconButton(onClick = { refresh() }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh scripts")
            }
        },
        floatingActionButton = {
            // The console owns the bottom of the screen while it is open, so the
            // FAB steps aside instead of covering the newest output line.
            if (!consoleVisible) {
                FloatingActionButton(
                    onClick = {
                        editorTarget = null
                        editorOpen = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New script")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (error != null) {
                ErrorBanner(message = error.orEmpty(), onRetry = { refresh() })
            }

            when {
                loading && scripts.isEmpty() -> LoadingRow("Loading scripts…")

                scripts.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Code,
                        title = "No scripts yet",
                        message = "Create a shell script to run it as root.",
                        actionLabel = "New script",
                        onAction = {
                            editorTarget = null
                            editorOpen = true
                        },
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(items = scripts, key = { it.id }) { item ->
                        ScriptRow(
                            item = item,
                            running = runningId == item.id,
                            onOpen = {
                                editorTarget = item
                                editorOpen = true
                            },
                            onRun = { startRun(item) },
                            onToggleFavorite = { toggleFavorite(item) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                }
            }

            if (consoleVisible) {
                ConsolePanel(
                    title = consoleTitle,
                    lines = consoleLines,
                    running = runningId != null,
                    onStop = { stopRun() },
                    onClose = {
                        runJob?.cancel()
                        runJob = null
                        runToken += 1L
                        runningId = null
                        consoleVisible = false
                    },
                )
            }
        }
    }

    if (editorOpen) {
        val editTarget = editorTarget
        var deleteAction: (() -> Unit)? = null
        editTarget?.let { target ->
            deleteAction = {
                editorOpen = false
                requestDelete(target)
            }
        }
        ScriptEditorDialog(
            initial = editTarget,
            saving = saving,
            onDismiss = {
                if (!saving) {
                    editorOpen = false
                    editorTarget = null
                }
            },
            onSave = { name, body -> saveScript(name, body) },
            onRequestDelete = deleteAction,
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        ConfirmDialog(
            title = "Delete script",
            message = "Delete \"${deleteTarget.name}\"? This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                deleteScript(deleteTarget)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// --------------------------------------------------------------------- list

@Composable
private fun ScriptRow(
    item: ScriptItem,
    running: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = item.lastRun?.let { formatTimestamp(it) } ?: "never run",
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val lastExit = item.lastExit
                if (lastExit != null) {
                    Spacer(Modifier.width(8.dp))
                    TagChip(
                        text = "exit $lastExit",
                        color = exitColor(lastExit),
                    )
                }
            }
        }

        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
        }

        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (item.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (item.favorite) "Remove favourite" else "Mark favourite",
                tint = if (item.favorite) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onRun, enabled = !running) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Run ${item.name}",
                tint = if (running) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

// ------------------------------------------------------------------ console

@Composable
private fun ConsolePanel(
    title: String,
    lines: List<String>,
    running: Boolean,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            // Let the new line be measured before jumping to the bottom.
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(ConsoleHeight),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title.ifBlank { "Console" },
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = MonoFont,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStop, enabled = running) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close console",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background, PanelShape)
                    .padding(8.dp)
                    .verticalScroll(scroll),
            ) {
                if (lines.isEmpty()) {
                    MonoText(
                        text = if (running) "waiting for output…" else "no output",
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        Column {
                            lines.forEach { line -> ConsoleLine(line) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleLine(line: String) {
    val color = when {
        line.startsWith("# error") -> MaterialTheme.colorScheme.error
        line.startsWith("# exit ") ->
            if (line.removePrefix("# exit ").trim() == "0") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        line.startsWith("# running") -> MaterialTheme.colorScheme.secondary
        line.startsWith("# stopped") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    MonoText(text = line.ifEmpty { " " }, size = 11.sp, color = color)
}

// ------------------------------------------------------------------- editor

@Composable
private fun ScriptEditorDialog(
    initial: ScriptItem?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, body: String) -> Unit,
    onRequestDelete: (() -> Unit)?,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var body by remember(initial) { mutableStateOf(initial?.body.orEmpty()) }
    val bodyScroll = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = if (initial == null) "New script" else "Edit script",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !saving,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "BODY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 200.dp)
                        .background(MaterialTheme.colorScheme.background, PanelShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, PanelShape)
                        .padding(8.dp),
                ) {
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(bodyScroll),
                        enabled = !saving,
                        textStyle = TextStyle(
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box {
                                if (body.isEmpty()) {
                                    MonoText(
                                        text = "#!/system/bin/sh\n",
                                        size = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onRequestDelete != null) {
                        IconButton(onClick = onRequestDelete, enabled = !saving) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete script",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { onSave(name, body) },
                        enabled = !saving && name.isNotBlank(),
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun exitColor(exit: Int) =
    if (exit == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
