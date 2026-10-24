package dev.rootops.ui.screens.logcat

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.LogLine
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.theme.MonoFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Levels offered by the filter chips, ordered from least to most severe. */
private val LEVELS = listOf('V', 'D', 'I', 'W', 'E', 'F')

private val FatalMagenta = Color(0xFFD98CE8)
private val FieldShape = RoundedCornerShape(8.dp)
private val NoticeShape = RoundedCornerShape(8.dp)

/** How often buffered lines are pushed into the visible list. */
private const val FLUSH_INTERVAL_MS = 80L

/** Upper bound for the tail-follow scroll, so a user gesture cannot stall the flusher. */
private const val SCROLL_TIMEOUT_MS = 300L

/** Delay before a typed tag/pid filter is applied, so typing does not respawn logcat. */
private const val FILTER_DEBOUNCE_MS = 300L

private const val MIN_CAP = 200
private const val MAX_CAP = 20_000

private const val EXPORT_DIR = "/sdcard/Download"

private enum class StreamState { RUNNING, STOPPED, ERROR }

/**
 * Live `logcat` viewer.
 *
 * The device log is streamed through [dev.rootops.core.api.LogRepository.stream] into a bounded
 * in-memory ring (size taken from `settings.logcatLimit`, oldest lines dropped first). Every
 * filter change restarts the stream; a client-side `grep` narrows the rows that are already
 * captured without touching the running logcat process.
 */
@Composable
fun LogcatScreen(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val cap by remember {
        derivedStateOf { settings.logcatLimit.coerceIn(MIN_CAP, MAX_CAP) }
    }

    val lines = remember { mutableStateListOf<LogLine>() }
    var levelName by rememberSaveable { mutableStateOf('V'.toString()) }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var pidInput by rememberSaveable { mutableStateOf("") }
    var grepInput by rememberSaveable { mutableStateOf("") }
    var paused by rememberSaveable { mutableStateOf(false) }
    var restartKey by remember { mutableIntStateOf(0) }
    var streamState by remember { mutableStateOf(StreamState.RUNNING) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var expandedSeqs by remember { mutableStateOf(emptySet<Long>()) }

    // Seeded from the (possibly restored) inputs so a process/config restore does not stream
    // unfiltered for one debounce window and then restart.
    var tagQuery by remember { mutableStateOf(tagInput.trim().takeIf { it.isNotEmpty() }) }
    var pidQuery by remember { mutableStateOf(pidInput.trim().toIntOrNull()?.takeIf { it > 0 }) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val minLevel = levelName.firstOrNull()?.takeIf { it in LEVELS } ?: 'V'

    // Typing restarts the stream, so wait for a pause in input first.
    LaunchedEffect(tagInput, pidInput) {
        delay(FILTER_DEBOUNCE_MS)
        tagQuery = tagInput.trim().takeIf { it.isNotEmpty() }
        pidQuery = pidInput.trim().toIntOrNull()?.takeIf { it > 0 }
    }

    // Client-side narrowing of the captured buffer: grep runs over message, tag and level.
    val displayed by remember {
        derivedStateOf {
            val needle = grepInput.trim()
            if (needle.isEmpty()) {
                lines.toList()
            } else {
                lines.filter { line ->
                    line.message.contains(needle, ignoreCase = true) ||
                        line.tag.contains(needle, ignoreCase = true) ||
                        line.levelName.contains(needle, ignoreCase = true) ||
                        line.pid.toString() == needle
                }
            }
        }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last != null && last.index >= info.totalItemsCount - 1)
        }
    }

    // A different filter means a different dataset.
    LaunchedEffect(minLevel, tagQuery, pidQuery) {
        lines.clear()
        expandedSeqs = emptySet()
    }

    // Keep the ring bounded when the limit is lowered from settings.
    LaunchedEffect(cap) {
        while (lines.size > cap) lines.removeAt(0)
    }

    LaunchedEffect(minLevel, tagQuery, pidQuery, restartKey) {
        streamState = StreamState.RUNNING
        errorText = null
        val pending = ArrayDeque<LogLine>()

        suspend fun drain() {
            if (pending.isEmpty()) return
            val stick = listState.layoutInfo.let { info ->
                val last = info.visibleItemsInfo.lastOrNull()
                info.totalItemsCount == 0 ||
                    (last != null && last.index >= info.totalItemsCount - 1)
            }
            while (pending.isNotEmpty()) {
                lines.add(pending.removeFirst())
                if (lines.size > cap) lines.removeAt(0)
            }
            if (stick && displayed.isNotEmpty()) {
                // A running fling holds the scroll mutex; never let the flusher stall on it.
                withTimeoutOrNull(SCROLL_TIMEOUT_MS) {
                    listState.scrollToItem(displayed.lastIndex)
                }
            }
        }

        try {
            coroutineScope {
                val flusher = launch {
                    while (isActive) {
                        delay(FLUSH_INTERVAL_MS)
                        drain()
                    }
                }
                try {
                    container.logs.stream(minLevel, tagQuery, pidQuery).collect { line ->
                        // Pausing freezes the view but keeps the process alive, so resuming
                        // never replays the buffer (which would duplicate every row).
                        if (!paused) pending.addLast(line)
                    }
                    drain()
                    streamState = StreamState.STOPPED
                } finally {
                    flusher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            streamState = StreamState.ERROR
            errorText = t.message?.takeIf { it.isNotBlank() } ?: "logcat stream failed"
        }
    }

    // A fresh logcat re-dumps the whole buffer, so drop the captured rows on restart instead
    // of appending a second copy of them.
    val restartStream: () -> Unit = {
        lines.clear()
        expandedSeqs = emptySet()
        paused = false
        restartKey++
    }

    val resumeStream: () -> Unit = { paused = false }

    val streaming = !paused && streamState == StreamState.RUNNING

    val emptyAction: (() -> Unit)? = when {
        paused -> resumeStream
        streamState != StreamState.RUNNING -> restartStream
        else -> null
    }

    val emptyActionLabel: String? = when {
        paused -> "Resume"
        streamState != StreamState.RUNNING -> "Restart"
        else -> null
    }

    val clearBuffer: () -> Unit = {
        scope.launch {
            val ok = runCatching { container.logs.clear() }.isSuccess
            lines.clear()
            expandedSeqs = emptySet()
            statusText = if (ok) "Log buffer cleared" else "Could not clear the log buffer"
        }
    }

    val exportLines: () -> Unit = {
        if (!exporting) {
            exporting = true
            scope.launch {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val path = "$EXPORT_DIR/logcat-$stamp.txt"
                val payload = displayed
                val ok = runCatching { container.logs.export(path, payload) }.getOrDefault(false)
                statusText = if (ok) {
                    "Exported ${payload.size} lines to $path"
                } else {
                    "Export failed, nothing written to $path"
                }
                exporting = false
            }
        }
    }

    val subtitle = buildString {
        append(displayed.size)
        append(" lines · min ")
        append(minLevel)
        if (paused) append(" · paused")
    }

    AppScaffold(
        title = "Logcat",
        subtitle = subtitle,
        actions = {
            IconButton(
                onClick = {
                    if (paused) {
                        resumeStream()
                    } else if (streamState == StreamState.RUNNING) {
                        paused = true
                    } else {
                        restartStream()
                    }
                },
            ) {
                Icon(
                    imageVector = if (streaming) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (streaming) "Pause stream" else "Start stream",
                )
            }
            IconButton(
                onClick = {
                    if (settings.confirmDelete) showClearDialog = true else clearBuffer()
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "Clear log buffer",
                )
            }
            IconButton(onClick = exportLines, enabled = !exporting) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.SaveAlt,
                        contentDescription = "Export log to Download",
                    )
                }
            }
        },
        floatingActionButton = {
            if (!atBottom && displayed.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.scrollToItem((displayed.size - 1).coerceAtLeast(0))
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Jump to latest line",
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LogcatFilterBar(
                minLevel = minLevel,
                onLevel = { levelName = it.toString() },
                tag = tagInput,
                onTag = { tagInput = it },
                pid = pidInput,
                onPid = { input -> pidInput = input.filter { it.isDigit() } },
                grep = grepInput,
                onGrep = { grepInput = it },
            )

            statusText?.let { text ->
                NoticeStrip(text = text, onDismiss = { statusText = null })
            }

            if (paused && streamState == StreamState.RUNNING) {
                NoticeStrip(
                    text = "stream paused",
                    actionLabel = "Resume",
                    onAction = resumeStream,
                )
            }
            when (streamState) {
                StreamState.STOPPED -> NoticeStrip(
                    text = "stream stopped",
                    actionLabel = "Restart",
                    onAction = restartStream,
                )
                StreamState.ERROR -> ErrorBanner(
                    message = errorText ?: "logcat stream failed",
                    onRetry = restartStream,
                )
                StreamState.RUNNING -> Unit
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (displayed.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Subject,
                        title = when {
                            paused -> "Stream paused"
                            streamState == StreamState.STOPPED -> "Stream stopped"
                            streamState == StreamState.ERROR -> "Stream failed"
                            else -> "Waiting for log output"
                        },
                        message = when {
                            paused ->
                                "Resume to keep reading the device log."
                            streamState == StreamState.STOPPED ->
                                "The logcat stream ended. Restart it to continue."
                            streamState == StreamState.ERROR ->
                                errorText ?: "The logcat stream failed."
                            grepInput.isNotBlank() ->
                                "No captured line matches \"${grepInput.trim()}\"."
                            else ->
                                "Nothing has arrived yet. Loosen the level, tag or pid filters " +
                                    "if the buffer stays quiet."
                        },
                        actionLabel = emptyActionLabel,
                        onAction = emptyAction,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = false,
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(items = displayed, key = { it.seq }) { line ->
                            LogLineRow(
                                line = line,
                                expanded = line.seq in expandedSeqs,
                                onToggle = {
                                    expandedSeqs = if (line.seq in expandedSeqs) {
                                        expandedSeqs - line.seq
                                    } else {
                                        expandedSeqs + line.seq
                                    }
                                },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(line.toClipboardText()))
                                    Toast.makeText(context, "Line copied", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "Clear log buffer",
            message = "This clears the device logcat buffer and every line shown here. " +
                "It cannot be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                showClearDialog = false
                clearBuffer()
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

// ---------------------------------------------------------------------------- filter bar

@Composable
private fun LogcatFilterBar(
    minLevel: Char,
    onLevel: (Char) -> Unit,
    tag: String,
    onTag: (String) -> Unit,
    pid: String,
    onPid: (String) -> Unit,
    grep: String,
    onGrep: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "MIN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (level in LEVELS) {
                    TagChip(
                        text = level.toString(),
                        color = levelColor(level),
                        selected = minLevel == level,
                        onClick = { onLevel(level) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField(
                value = tag,
                onValueChange = onTag,
                label = "tag",
                modifier = Modifier.weight(1f),
            )
            CompactField(
                value = pid,
                onValueChange = onPid,
                label = "pid",
                modifier = Modifier.width(96.dp),
                numeric = true,
            )
        }
        Spacer(Modifier.height(6.dp))
        CompactField(
            value = grep,
            onValueChange = onGrep,
            label = "grep",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    Surface(
        modifier = modifier.height(34.dp),
        shape = FieldShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFont,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = if (numeric) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------- rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogLineRow(
    line: LogLine,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val color = levelColor(line.level)
    val tint = when (line.level.uppercaseChar()) {
        'E', 'F', 'A' -> color.copy(alpha = 0.07f)
        'W' -> color.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .combinedClickable(onClick = onToggle, onLongClick = onCopy)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MonoText(
            text = line.timestamp,
            modifier = Modifier.widthIn(max = 130.dp),
            size = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = line.level.uppercaseChar().toString(),
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = line.tag,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = line.message.ifEmpty { " " },
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------- helpers

@Composable
private fun levelColor(level: Char): Color = when (level.uppercaseChar()) {
    'V' -> MaterialTheme.colorScheme.onSurfaceVariant
    'D' -> MaterialTheme.colorScheme.secondary
    'I' -> MaterialTheme.colorScheme.primary
    'W' -> MaterialTheme.colorScheme.tertiary
    'E' -> MaterialTheme.colorScheme.error
    'F', 'A' -> FatalMagenta
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun NoticeStrip(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = NoticeShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** Plain-text form of a line, used for clipboard copies. */
private fun LogLine.toClipboardText(): String =
    "$timestamp ${pid.toString().padStart(5)} ${tid.toString().padStart(5)} $level $tag: $message"
