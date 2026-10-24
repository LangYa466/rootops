package dev.rootops.ui.screens.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rootops.AppContainer
import dev.rootops.core.api.PtySession
import dev.rootops.core.api.ShellHandle
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TABS = 5
private const val POLL_INTERVAL_MS = 50L
private const val RESIZE_DEBOUNCE_MS = 200L
private const val MIN_COLS = 20
private const val MAX_COLS = 400
private const val MIN_ROWS = 4
private const val MAX_ROWS = 200
private const val INITIAL_COLS = 80
private const val INITIAL_ROWS = 24
private const val MIN_FONT = 8
private const val MAX_FONT = 28
private const val SENTINEL = " "
private const val CHAR_WIDTH_RATIO = 0.6f
private const val LINE_HEIGHT_RATIO = 1.35f

/**
 * Multi-session terminal: real pty when the native backend is available, plain shell streaming
 * as a fallback. Output is decoded by [TerminalBuffer] and rendered as styled monospace rows.
 */
@Composable
fun TerminalScreen(container: AppContainer) {
    val settings by container.settings.settings.collectAsState()
    val rootAvailable by container.shell.rootAvailable.collectAsState()
    val clipboard = LocalClipboardManager.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var activeId by remember { mutableIntStateOf(-1) }
    var nextId by remember { mutableIntStateOf(0) }

    fun openTab() {
        if (sessions.size >= MAX_TABS) return
        val root = settings.useRoot && rootAvailable
        val session = TerminalSession(
            id = nextId,
            title = if (root) "root" else "sh",
            container = container,
            rootMode = root,
            shell = if (root) "su" else settings.terminalShell,
            cwd = settings.defaultPath,
            buffer = TerminalBuffer(INITIAL_COLS, INITIAL_ROWS),
        )
        nextId += 1
        sessions.add(session)
        activeId = session.id
        session.start()
    }

    fun closeTab(id: Int) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].close()
        sessions.removeAt(index)
        activeId = when {
            sessions.isEmpty() -> -1
            activeId == id -> sessions[(index - 1).coerceAtLeast(0)].id
            else -> activeId
        }
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) openTab()
    }

    DisposableEffect(Unit) {
        onDispose {
            sessions.forEach { it.close() }
            sessions.clear()
        }
    }

    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    AppScaffold(
        title = "Terminal",
        subtitle = if (active == null) {
            "no session"
        } else {
            "${active.title} · ${active.mode.label()} · ${settings.terminalFontSize}sp"
        },
        actions = {
            TagChip(
                text = if (settings.useRoot && rootAvailable) "root" else "sh",
                color = MaterialTheme.colorScheme.primary,
                selected = settings.useRoot && rootAvailable,
                onClick = {
                    if (rootAvailable) {
                        container.settings.update { it.copy(useRoot = !it.useRoot) }
                    }
                },
            )
            TextButton(onClick = {
                container.settings.update {
                    it.copy(terminalFontSize = (it.terminalFontSize - 1).coerceIn(MIN_FONT, MAX_FONT))
                }
            }) {
                Text("A-", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = {
                container.settings.update {
                    it.copy(terminalFontSize = (it.terminalFontSize + 1).coerceIn(MIN_FONT, MAX_FONT))
                }
            }) {
                Text("A+", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(active?.buffer?.text() ?: "")) },
                enabled = active != null,
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy all output",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { openTab() }, enabled = sessions.size < MAX_TABS) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New session",
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (sessions.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "No terminal session",
                    message = "Open a shell to start running commands.",
                    actionLabel = "New session",
                    onAction = { openTab() },
                )
            } else {
                TabStrip(
                    sessions = sessions,
                    activeId = activeId,
                    canAdd = sessions.size < MAX_TABS,
                    onSelect = { activeId = it },
                    onClose = { closeTab(it) },
                    onNew = { openTab() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (active != null) {
                    // Fully qualified to avoid clashing with the KeyEvent.key extension import.
                    androidx.compose.runtime.key(active.id) {
                        TerminalPane(
                            session = active,
                            fontSize = settings.terminalFontSize,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- tabs

@Composable
private fun TabStrip(
    sessions: List<TerminalSession>,
    activeId: Int,
    canAdd: Boolean,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sessions.forEach { session ->
            TabPill(
                label = "${session.title} ${session.id + 1}",
                selected = session.id == activeId,
                onSelect = { onSelect(session.id) },
                onClose = { onClose(session.id) },
            )
        }
        if (canAdd) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onNew() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onSelect() },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close session",
                    modifier = Modifier.size(12.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

// -------------------------------------------------------------------- pane

@Composable
private fun TerminalPane(
    session: TerminalSession,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val buffer = session.buffer
    val revision = session.revision
    val lines = remember(buffer, revision) { buffer.visibleLines() }
    val notice = session.notice
    val mode = session.mode

    var ctrlSticky by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(SENTINEL) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }

    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val charWidth = with(density) { (fontSize * CHAR_WIDTH_RATIO).sp.toDp() }
    val lineHeight = with(density) { (fontSize * LINE_HEIGHT_RATIO).sp.toDp() }
    val charWidthPx = with(density) { charWidth.toPx() }
    val lineHeightPx = with(density) { lineHeight.toPx() }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val cols = if (viewport.width > 0 && charWidthPx > 0f) {
        (viewport.width / charWidthPx).toInt().coerceIn(MIN_COLS, MAX_COLS)
    } else {
        buffer.cols
    }
    val rows = if (viewport.height > 0 && lineHeightPx > 0f) {
        (viewport.height / lineHeightPx).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
    } else {
        buffer.rows
    }

    // Debounced geometry sync: the pty and the emulator always agree on the grid size.
    LaunchedEffect(cols, rows) {
        delay(RESIZE_DEBOUNCE_MS)
        if (cols != buffer.cols || rows != buffer.rows) {
            buffer.resize(cols, rows)
            session.resize(cols, rows)
        }
    }

    LaunchedEffect(session.id) {
        runCatching { focusRequester.requestFocus() }
    }

    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling) stickToBottom = !canForward
            }
    }
    LaunchedEffect(revision, lines.size) {
        if (stickToBottom && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    val sendTyped: (String) -> Unit = { raw ->
        var text = raw
        if (ctrlSticky) {
            ctrlSticky = false
            val mapped = StringBuilder(text.length)
            for (c in text) {
                mapped.append(
                    when {
                        c in 'a'..'z' -> (c - 'a' + 1).toChar()
                        c in 'A'..'Z' -> (c - 'A' + 1).toChar()
                        else -> c
                    },
                )
            }
            text = mapped.toString()
        }
        if (text.isNotEmpty()) session.send(text.replace('\n', '\r').toByteArray())
    }

    val keyHandler: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val sequence = when (event.key) {
                Key.Enter -> "\r"
                Key.Backspace -> "\u007F"
                Key.Tab -> "\t"
                Key.Escape -> "\u001B"
                Key.DirectionUp -> "\u001B[A"
                Key.DirectionDown -> "\u001B[B"
                Key.DirectionRight -> "\u001B[C"
                Key.DirectionLeft -> "\u001B[D"
                Key.MoveHome -> "\u001B[H"
                Key.MoveEnd -> "\u001B[F"
                Key.Delete -> "\u001B[3~"
                Key.PageUp -> "\u001B[5~"
                Key.PageDown -> "\u001B[6~"
                else -> null
            }
            if (sequence != null) {
                session.send(sequence.toByteArray())
                true
            } else if (event.isCtrlPressed) {
                val code = event.utf16CodePoint
                when (code) {
                    in 'a'.code..'z'.code -> {
                        session.send(byteArrayOf((code - 'a'.code + 1).toByte()))
                        true
                    }
                    in 'A'.code..'Z'.code -> {
                        session.send(byteArrayOf((code - 'A'.code + 1).toByte()))
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }

    fun submitCommand() {
        if (command.isEmpty()) return
        session.send((command + "\r").toByteArray())
        command = ""
    }

    Column(modifier = modifier.fillMaxSize()) {
        ExtraKeysBar(
            ctrlSticky = ctrlSticky,
            onToggleCtrl = { ctrlSticky = !ctrlSticky },
            onSequence = { session.send(it.toByteArray()) },
            onPaste = {
                val text = clipboard.getText()?.text
                if (!text.isNullOrEmpty()) {
                    session.send(text.replace("\r\n", "\n").replace('\n', '\r').toByteArray())
                }
            },
            onClear = { session.send("clear\r".toByteArray()) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { viewport = it.size }
                .onPreviewKeyEvent(keyHandler)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { runCatching { focusRequester.requestFocus() } },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(charWidth * cols + 8.dp)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    items(count = lines.size) { index ->
                        Text(
                            text = lines[index],
                            fontFamily = MonoFont,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * LINE_HEIGHT_RATIO).sp,
                            softWrap = false,
                            maxLines = 1,
                            color = AnsiDefaultForeground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            BasicTextField(
                value = hidden,
                onValueChange = { value ->
                    val typed = when {
                        value.isEmpty() -> null
                        value.startsWith(SENTINEL) -> value.substring(SENTINEL.length)
                        value.endsWith(SENTINEL) -> value.dropLast(SENTINEL.length)
                        else -> value
                    }
                    if (typed == null) {
                        session.send(byteArrayOf(0x7F))
                    } else if (typed.isNotEmpty()) {
                        sendTyped(typed)
                    }
                    hidden = SENTINEL
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = Color.Transparent,
                    fontFamily = MonoFont,
                    fontSize = 1.sp,
                ),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.None,
                ),
                keyboardActions = KeyboardActions(),
                singleLine = false,
            )
        }

        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = MonoFont,
                    fontSize = fontSize.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                placeholder = {
                    Text(
                        text = if (mode == SessionMode.FALLBACK) {
                            "Send a command (plain shell)"
                        } else {
                            "Send a command"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submitCommand() }),
                trailingIcon = {
                    IconButton(onClick = { submitCommand() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send command",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------- extra keys

@Composable
private fun ExtraKeysBar(
    ctrlSticky: Boolean,
    onToggleCtrl: () -> Unit,
    onSequence: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap("ESC") { onSequence("\u001B") }
        KeyCap("TAB") { onSequence("\t") }
        KeyCap("CTRL", active = ctrlSticky, onClick = onToggleCtrl)
        KeyCap("\u2191") { onSequence("\u001B[A") }
        KeyCap("\u2193") { onSequence("\u001B[B") }
        KeyCap("\u2190") { onSequence("\u001B[D") }
        KeyCap("\u2192") { onSequence("\u001B[C") }
        KeyCap("/") { onSequence("/") }
        KeyCap("-") { onSequence("-") }
        KeyCap("|") { onSequence("|") }
        KeyCap("~") { onSequence("~") }
        KeyCap("HOME") { onSequence("\u001B[H") }
        KeyCap("END") { onSequence("\u001B[F") }
        KeyCap("^C") { onSequence("\u0003") }
        KeyCap("^D") { onSequence("\u0004") }
        KeyCap("PASTE", onClick = onPaste)
        KeyCap("CLEAR", onClick = onClear)
    }
}

@Composable
private fun KeyCap(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        } else {
            MaterialTheme.colorScheme.background
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFont,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

// ----------------------------------------------------------------- sessions

private enum class SessionMode {
    CONNECTING,
    PTY,
    FALLBACK,
    DEAD;

    fun label(): String = when (this) {
        CONNECTING -> "connecting"
        PTY -> "pty"
        FALLBACK -> "plain"
        DEAD -> "closed"
    }
}

/**
 * One terminal session: owns the pty (or the shell fallback), feeds [buffer] and publishes a
 * coalesced repaint revision at most once per [POLL_INTERVAL_MS].
 */
private class TerminalSession(
    val id: Int,
    val title: String,
    private val container: AppContainer,
    private val rootMode: Boolean,
    private val shell: String,
    private val cwd: String?,
    val buffer: TerminalBuffer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<ByteArray>()
    private val dirtyLock = Any()
    private var dirty = false
    private var started = false
    private var pty: PtySession? = null
    private var fallback: ShellHandle? = null

    var mode by mutableStateOf(SessionMode.CONNECTING)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun start() {
        if (started) return
        started = true
        scope.launch { repaintLoop() }
        // Parsing runs off the main thread; the repaint ticker publishes the revision on Main.
        scope.launch(Dispatchers.Default) { connect() }
    }

    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val session = pty
        if (session != null) {
            // PtySession.write copies and dispatches internally, so ordering is preserved here.
            runCatching { session.write(bytes) }
            return
        }
        val handle = fallback
        if (handle != null) {
            val text = sanitizeForShell(bytes)
            if (text.isNotEmpty()) {
                scope.launch { runCatching { handle.write(text) } }
            }
            return
        }
        if (mode == SessionMode.CONNECTING && pending.size < 32) {
            pending.addLast(bytes)
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        val session = pty
        if (session != null) runCatching { session.resize(newCols, newRows) }
    }

    fun close() {
        val session = pty
        pty = null
        runCatching { session?.close() }
        val handle = fallback
        fallback = null
        if (handle != null) {
            // The scope below is cancelled immediately, so the suspend close runs detached.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { handle.close() }
            }
        }
        scope.cancel()
    }

    private fun markDirty() {
        synchronized(dirtyLock) { dirty = true }
    }

    private fun consumeDirty(): Boolean = synchronized(dirtyLock) {
        val value = dirty
        dirty = false
        value
    }

    private suspend fun repaintLoop() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (consumeDirty()) revision += 1
        }
    }

    private suspend fun connect() {
        val session = openPty()
        if (session != null) {
            runPty(session)
        } else {
            runFallback()
        }
    }

    private suspend fun openPty(): PtySession? = try {
        withContext(Dispatchers.IO) {
            container.terminal.open(
                shell,
                emptyList(),
                cwd,
                environment(),
                buffer.cols,
                buffer.rows,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null // UnsatisfiedLinkError / IllegalStateException -> plain shell fallback
    }

    private suspend fun runPty(session: PtySession) {
        pty = session
        mode = SessionMode.PTY
        flushPending()
        markDirty()

        val watcher = scope.launch {
            session.exited.collect { exited ->
                if (exited) {
                    notice = "process exited"
                    markDirty()
                }
            }
        }
        try {
            session.output.collect { bytes ->
                if (bytes.isNotEmpty()) {
                    buffer.feed(bytes)
                    markDirty()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // reader failure: the session is reported as closed below
        } finally {
            watcher.cancel()
        }
        if (notice == null) notice = "session closed"
        mode = SessionMode.DEAD
        markDirty()
    }

    private suspend fun runFallback() {
        val handle = try {
            withContext(Dispatchers.IO) { container.shell.open("sh", rootMode) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (handle == null) {
            mode = SessionMode.DEAD
            notice = "terminal backend unavailable"
            markDirty()
            return
        }

        fallback = handle
        mode = SessionMode.FALLBACK
        notice = "pty unavailable — plain shell mode"
        flushPending()
        markDirty()

        val watcher = scope.launch {
            handle.exited.collect { exited ->
                if (exited) {
                    notice = "shell exited"
                    markDirty()
                }
            }
        }
        try {
            handle.output.collect { text ->
                if (text.isNotEmpty()) {
                    buffer.feed(text)
                    markDirty()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // stream failure: reported as closed below
        } finally {
            watcher.cancel()
        }
        notice = "shell exited"
        mode = SessionMode.DEAD
        markDirty()
    }

    private fun flushPending() {
        val session = pty
        val handle = fallback
        while (pending.isNotEmpty()) {
            val bytes = pending.removeFirst()
            if (session != null) {
                runCatching { session.write(bytes) }
            } else if (handle != null) {
                val text = sanitizeForShell(bytes)
                if (text.isNotEmpty()) scope.launch { runCatching { handle.write(text) } }
            }
        }
    }

    private fun environment(): Map<String, String> = mapOf(
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "LANG" to "en_US.UTF-8",
        "HOME" to "/",
        "SHELL" to shell,
        "PATH" to "/sbin:/system/sbin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin",
    )

    /** Keeps only the input a pipe-backed shell can use: no escape sequences, CR becomes LF. */
    private fun sanitizeForShell(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u001B' -> {
                    i++
                    if (i < text.length && text[i] == '[') {
                        i++
                        while (i < text.length && text[i].code in 0x20..0x3F) i++
                        if (i < text.length) i++
                    } else if (i < text.length) {
                        i++
                    }
                }
                c == '\u007F' -> {
                    out.append('\b')
                    i++
                }
                c == '\r' -> {
                    out.append('\n')
                    i++
                    if (i < text.length && text[i] == '\n') i++
                }
                c == '\n' || c == '\t' || c.code >= 0x20 -> {
                    out.append(c)
                    i++
                }
                else -> i++
            }
        }
        return out.toString()
    }
}
