package dev.rootops.ui.screens.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.util.formatBytes
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.nav.LocalNavigator
import dev.rootops.ui.nav.Route
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hard cap on the number of bytes this editor will load. */
private const val MAX_EDIT_BYTES = 2 * 1024 * 1024

/** Upper bound on highlighted find matches; keeps the span list bounded. */
private const val MAX_MATCHES = 5_000

/** Above these limits the document is shown as plain text (highlighting is O(n)). */
private const val HIGHLIGHT_MAX_CHARS = 200_000
private const val HIGHLIGHT_MAX_LINES = 20_000

private val SHELL_KEYWORDS: Set<String> = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "in", "function", "return", "local", "export", "readonly",
    "declare", "typeset", "unset", "source", "alias", "set", "eval", "exec",
    "trap", "shift", "break", "continue", "select", "time", "coproc",
    "true", "false", "null", "yes", "no", "on", "off",
)

/**
 * Plain-text configuration editor.
 *
 * Reads [initialPath] through [AppContainer.files] on the IO dispatcher, keeps the pristine copy so
 * unsaved changes can be detected and reverted, and writes the buffer back with a real save. Files
 * above [MAX_EDIT_BYTES] and binary payloads are rejected with an error banner instead of being
 * loaded, and a failed write surfaces the shell's error (typically a read-only mount or missing
 * write permission).
 *
 * The layout is a single scrolling row: a monospace line-number gutter and the text field share one
 * [androidx.compose.foundation.ScrollState], so the numbers always move with the text.
 */
@Composable
fun EditorScreen(container: AppContainer, initialPath: String? = null) {
    val path = initialPath.orEmpty()
    val navigate = LocalNavigator.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val fontSizeSp = settings.terminalFontSize.coerceIn(9, 30).sp
    val lineHeightSp = (fontSizeSp.value * 1.4f).sp
    val lineHeightPx = with(density) { lineHeightSp.toPx() }

    var text by remember(path) { mutableStateOf("") }
    var original by remember(path) { mutableStateOf("") }
    var fieldValue by remember(path) { mutableStateOf(TextFieldValue("")) }
    var loading by remember(path) { mutableStateOf(path.isNotBlank()) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var saveError by remember(path) { mutableStateOf<String?>(null) }
    var status by remember(path) { mutableStateOf<String?>(null) }
    var statusError by remember(path) { mutableStateOf(false) }
    var saving by remember(path) { mutableStateOf(false) }
    var writable by remember(path) { mutableStateOf(true) }
    var fileSize by remember(path) { mutableStateOf(0L) }
    var reloadKey by remember(path) { mutableIntStateOf(0) }

    var wrap by rememberSaveable(path) { mutableStateOf(true) }
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var matchCursor by remember { mutableIntStateOf(-1) }
    var showGoto by remember { mutableStateOf(false) }
    var gotoInput by remember { mutableStateOf("") }
    var showLeave by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val findFocus = remember { FocusRequester() }
    val gotoFocus = remember { FocusRequester() }

    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val dirty = !loading && loadError == null && path.isNotBlank() && text != original
    val matches = remember(text, findQuery) { findMatches(text, findQuery) }
    val cursor = fieldValue.selection.end.coerceIn(0, text.length)
    val cursorPosition = remember(text, cursor) { cursorPositionOf(text, cursor) }
    val fileName = path.substringAfterLast('/').ifBlank { path }

    LaunchedEffect(path, reloadKey) {
        if (path.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        saveError = null
        status = null
        statusError = false
        matchCursor = -1
        try {
            val entry = withContext(Dispatchers.IO) {
                runCatching { container.files.stat(path) }.getOrNull()
            }
            if (entry != null && entry.isDir) {
                loadError = "$path is a directory, not a file."
                loading = false
                return@LaunchedEffect
            }
            if (entry != null && entry.size > MAX_EDIT_BYTES) {
                loadError = "File is too large to edit: ${formatBytes(entry.size)} " +
                    "(limit ${formatBytes(MAX_EDIT_BYTES.toLong())})."
                loading = false
                return@LaunchedEffect
            }
            // Ask for one byte past the cap so a truncated read can never masquerade as a full file.
            val bytes = withContext(Dispatchers.IO) {
                container.files.readBytes(path, MAX_EDIT_BYTES + 1)
            }
            if (bytes.size > MAX_EDIT_BYTES) {
                loadError = "File is too large to edit (limit ${formatBytes(MAX_EDIT_BYTES.toLong())})."
                loading = false
                return@LaunchedEffect
            }
            if (bytes.isEmpty()) {
                val exists = withContext(Dispatchers.IO) { container.files.exists(path) }
                if (!exists) {
                    loadError = "File not found: $path"
                    loading = false
                    return@LaunchedEffect
                }
                if ((entry?.size ?: 0L) > 0L) {
                    loadError = "Could not read $path (permission denied?). Root access may be required."
                    loading = false
                    return@LaunchedEffect
                }
            }
            if (looksBinary(bytes)) {
                loadError = "Binary file — this editor only handles text files."
                loading = false
                return@LaunchedEffect
            }
            val raw = String(bytes, Charsets.UTF_8)
            text = raw
            original = raw
            fieldValue = TextFieldValue(raw, selection = TextRange(0))
            writable = entry?.writable ?: true
            fileSize = entry?.size ?: bytes.size.toLong()
            status = "Loaded ${formatBytes(fileSize)}"
            statusError = false
            vScroll.scrollTo(0)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            loadError = t.message?.takeIf { it.isNotBlank() } ?: "Failed to read $path"
        } finally {
            loading = false
        }
    }

    fun scrollToCharOffset(offset: Int) {
        val line = lineIndexAt(text, offset)
        val target = (line * lineHeightPx).toInt()
        scope.launch { vScroll.animateScrollTo(target) }
    }

    fun findNext() {
        if (findQuery.isEmpty()) return
        if (matches.isEmpty()) {
            status = "No matches for \"$findQuery\""
            statusError = false
            return
        }
        matchCursor = (matchCursor + 1) % matches.size
        val range = matches[matchCursor]
        fieldValue = fieldValue.copy(selection = TextRange(range.first, range.last + 1))
        scrollToCharOffset(range.first)
        val suffix = if (matches.size >= MAX_MATCHES) "+" else ""
        status = "Match ${matchCursor + 1} of ${matches.size}$suffix"
        statusError = false
    }

    fun goToLine(raw: String) {
        val number = raw.trim().toIntOrNull() ?: return
        val target = number.coerceIn(1, lineCount)
        val offset = lineStartOffset(text, target)
        fieldValue = fieldValue.copy(selection = TextRange(offset, offset))
        scrollToCharOffset(offset)
        status = "Line $target of $lineCount"
        statusError = false
    }

    fun revert() {
        if (text == original) return
        text = original
        fieldValue = TextFieldValue(original, selection = TextRange(0))
        saveError = null
        matchCursor = -1
        status = "Reverted to last saved version"
        statusError = false
        scope.launch { vScroll.scrollTo(0) }
    }

    fun copyAll() {
        clipboard.setText(AnnotatedString(text))
        status = "Copied ${text.length} characters"
        statusError = false
    }

    fun performSave() {
        if (saving || text == original || path.isBlank()) return
        val snapshot = text
        saving = true
        saveError = null
        status = "Saving…"
        statusError = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { container.files.writeText(path, snapshot) }.getOrDefault(false)
            }
            if (ok) {
                original = snapshot
                status = "Saved"
                statusError = false
            } else {
                val probe = withContext(Dispatchers.IO) {
                    runCatching {
                        container.shell.run("ls -ld -- ${shellQuote(path)}", timeoutMs = 5_000)
                    }.getOrNull()
                }
                val shellError = probe?.let { result ->
                    if (result.ok) "" else result.err.ifBlank { result.out }.trim()
                }.orEmpty()
                saveError = if (shellError.isNotBlank()) {
                    "Save failed: $shellError"
                } else {
                    "Save failed: $path is not writable (permission denied). " +
                        "Root access or a read-write mount is required."
                }
                status = "Save failed"
                statusError = true
            }
            saving = false
        }
    }

    BackHandler(enabled = dirty && !loading) { showLeave = true }

    if (showFind) {
        val matchCount = matches.size
        val countLabel = when {
            findQuery.isEmpty() -> "Type to search"
            matchCount == 0 -> "No matches"
            matchCount >= MAX_MATCHES -> "$MAX_MATCHES+ matches"
            matchCount == 1 -> "1 match"
            else -> "$matchCount matches"
        }
        LaunchedEffect(Unit) {
            delay(120)
            runCatching { findFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = {
                showFind = false
                focusManager.clearFocus()
            },
            title = { Text("Find in file", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            matchCursor = -1
                        },
                        singleLine = true,
                        label = { Text("Search text") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(findFocus),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = countLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { findNext() },
                    enabled = matchCount > 0,
                ) { Text("Find next") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFind = false
                        focusManager.clearFocus()
                    },
                ) { Text("Close") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showGoto) {
        LaunchedEffect(Unit) {
            delay(120)
            runCatching { gotoFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = {
                showGoto = false
                focusManager.clearFocus()
            },
            title = { Text("Go to line", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    OutlinedTextField(
                        value = gotoInput,
                        onValueChange = { value ->
                            gotoInput = value.filter { it.isDigit() }.take(7)
                        },
                        singleLine = true,
                        label = { Text("Line number") },
                        supportingText = { Text("1 – $lineCount") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(gotoFocus),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        goToLine(gotoInput)
                        showGoto = false
                        focusManager.clearFocus()
                    },
                    enabled = gotoInput.isNotBlank(),
                ) { Text("Go") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showGoto = false
                        focusManager.clearFocus()
                    },
                ) { Text("Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showLeave) {
        ConfirmDialog(
            title = "Unsaved changes",
            message = "Discard changes to ${fileName.ifBlank { "this file" }}?",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = {
                showLeave = false
                navigate(Route.Files)
            },
            onDismiss = { showLeave = false },
        )
    }

    AppScaffold(
        title = if (fileName.isBlank()) "Editor" else fileName + if (dirty) " *" else "",
        subtitle = when {
            path.isBlank() -> null
            !writable -> "$path · read-only"
            else -> path
        },
        onBack = {
            if (dirty) {
                showLeave = true
            } else {
                navigate(Route.Files)
            }
        },
        actions = {
            IconButton(
                onClick = { performSave() },
                enabled = dirty && !saving,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(Icons.Filled.Save, contentDescription = "Save")
                }
            }
            IconButton(
                onClick = { showFind = true },
                enabled = !loading && loadError == null && path.isNotBlank(),
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Find")
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !loading && loadError == null && path.isNotBlank(),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Revert to saved") },
                        onClick = {
                            menuOpen = false
                            revert()
                        },
                        enabled = dirty,
                        leadingIcon = { Icon(Icons.Filled.Undo, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy all") },
                        onClick = {
                            menuOpen = false
                            copyAll()
                        },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Go to line…") },
                        onClick = {
                            menuOpen = false
                            gotoInput = ""
                            showGoto = true
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.FormatListNumbered, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (wrap) "Disable soft wrap" else "Enable soft wrap") },
                        onClick = {
                            menuOpen = false
                            wrap = !wrap
                            status = if (wrap) "Soft wrap enabled" else "Soft wrap disabled"
                            statusError = false
                        },
                        leadingIcon = { Icon(Icons.Filled.WrapText, contentDescription = null) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Reload from disk") },
                        onClick = {
                            menuOpen = false
                            reloadKey++
                        },
                        enabled = !dirty,
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    )
                }
            }
        },
        bottomBar = {
            if (path.isNotBlank() && !loading && loadError == null) {
                EditorStatusBar(
                    dirty = dirty,
                    status = status,
                    statusError = statusError,
                    line = cursorPosition.first,
                    column = cursorPosition.second,
                    lines = lineCount,
                    sizeLabel = formatBytes(fileSize),
                    wrap = wrap,
                    writable = writable,
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
        ) {
            when {
                path.isBlank() -> EmptyState(
                    icon = Icons.Filled.Description,
                    title = "No file selected",
                    message = "Open a text file from Files to edit it here.",
                )

                loading -> LoadingRow("Reading ${fileName.ifBlank { path }}…")

                loadError != null -> Column(modifier = Modifier.fillMaxWidth()) {
                    ErrorBanner(
                        message = loadError.orEmpty(),
                        onRetry = { reloadKey++ },
                    )
                }

                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val viewportWidthPx = with(density) { maxWidth.toPx() }
                    val viewportHeightPx = with(density) { maxHeight.toPx() }
                    val measurer = rememberTextMeasurer()
                    val charWidthPx = remember(fontSizeSp, measurer) {
                        val sample = measurer.measure(
                            text = AnnotatedString("0".repeat(64)),
                            style = TextStyle(fontFamily = MonoFont, fontSize = fontSizeSp),
                        )
                        (sample.size.width / 64f).coerceAtLeast(1f)
                    }
                    val digits = lineCount.toString().length
                    val gutterWidth = with(density) {
                        (digits * charWidthPx + 16.dp.toPx()).toDp()
                    }
                    val longestLineChars = remember(text, wrap) {
                        if (wrap) 0 else text.lineSequence().maxOfOrNull { it.length } ?: 0
                    }
                    val editorWidth = with(density) {
                        maxOf(
                            viewportWidthPx,
                            longestLineChars * charWidthPx + 34.dp.toPx(),
                        ).toDp()
                    }
                    val numbers = remember(lineCount, digits) {
                        buildString(lineCount * (digits + 1)) {
                            for (line in 1..lineCount) {
                                if (line > 1) append('\n')
                                append(line.toString().padStart(digits))
                            }
                        }
                    }

                    val editorStyle = TextStyle(
                        fontFamily = MonoFont,
                        fontSize = fontSizeSp,
                        lineHeight = lineHeightSp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val gutterStyle = editorStyle.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        textAlign = TextAlign.End,
                    )
                    val highlightEnabled =
                        text.length <= HIGHLIGHT_MAX_CHARS && lineCount <= HIGHLIGHT_MAX_LINES
                    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val stringColor = MaterialTheme.colorScheme.secondary
                    val numberColor = MaterialTheme.colorScheme.tertiary
                    val keywordColor = MaterialTheme.colorScheme.primary
                    val matchColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    val transformation = remember(
                        highlightEnabled,
                        matches,
                        commentColor,
                        stringColor,
                        numberColor,
                        keywordColor,
                        matchColor,
                    ) {
                        EditorHighlight(
                            tokenize = highlightEnabled,
                            matchRanges = matches,
                            commentColor = commentColor,
                            stringColor = stringColor,
                            numberColor = numberColor,
                            keywordColor = keywordColor,
                            matchBackground = matchColor,
                        )
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        saveError?.let { message ->
                            ErrorBanner(
                                message = message,
                                onRetry = { performSave() },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(vScroll)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = numbers,
                                style = gutterStyle,
                                softWrap = false,
                                modifier = Modifier
                                    .width(gutterWidth)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 6.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (wrap) Modifier else Modifier.horizontalScroll(hScroll)
                                    ),
                            ) {
                                BasicTextField(
                                    value = fieldValue,
                                    onValueChange = { newValue ->
                                        fieldValue = newValue
                                        text = newValue.text
                                        matchCursor = -1
                                        if (statusError) {
                                            statusError = false
                                            status = null
                                        }
                                        val caret = newValue.selection.end
                                        if (caret >= 0) {
                                            val line = lineIndexAt(newValue.text, caret)
                                            val top = line * lineHeightPx
                                            val viewTop = vScroll.value.toFloat()
                                            val viewBottom = viewTop + viewportHeightPx
                                            when {
                                                top < viewTop -> scope.launch {
                                                    vScroll.animateScrollTo(top.toInt())
                                                }

                                                top + lineHeightPx > viewBottom -> scope.launch {
                                                    vScroll.animateScrollTo(
                                                        (top + lineHeightPx - viewportHeightPx).toInt()
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .then(
                                            if (wrap) {
                                                Modifier.fillMaxWidth()
                                            } else {
                                                Modifier.width(editorWidth)
                                            }
                                        )
                                        .padding(start = 10.dp, end = 16.dp),
                                    textStyle = editorStyle,
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        autoCorrectEnabled = false,
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Default,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    visualTransformation = transformation,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact state line under the editor: dirty/save state, cursor position and file stats. */
@Composable
private fun EditorStatusBar(
    dirty: Boolean,
    status: String?,
    statusError: Boolean,
    line: Int,
    column: Int,
    lines: Int,
    sizeLabel: String,
    wrap: Boolean,
    writable: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            statusError -> MaterialTheme.colorScheme.error
                            dirty -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status ?: if (dirty) "Unsaved changes" else "Saved",
                style = MaterialTheme.typography.labelSmall,
                color = if (statusError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!writable) {
                Text(
                    text = "read-only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(10.dp))
            }
            if (!wrap) {
                Text(
                    text = "nowrap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = "Ln $line, Col $column",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$lines L · $sizeLabel",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Applies the lightweight tokenizer plus find-match backgrounds to the text field contents.
 * The text is never changed, so [OffsetMapping.Identity] is exact.
 */
private class EditorHighlight(
    private val tokenize: Boolean,
    private val matchRanges: List<IntRange>,
    private val commentColor: Color,
    private val stringColor: Color,
    private val numberColor: Color,
    private val keywordColor: Color,
    private val matchBackground: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        if (source.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        if (!tokenize && matchRanges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            buildHighlighted(source),
            OffsetMapping.Identity,
        )
    }

    private fun buildHighlighted(source: String): AnnotatedString {
        val builder = AnnotatedString.Builder(source)
        val length = source.length
        for (range in matchRanges) {
            val start = range.first.coerceIn(0, length)
            val end = (range.last + 1).coerceIn(start, length)
            if (start < end) {
                builder.addStyle(SpanStyle(background = matchBackground), start, end)
            }
        }
        if (!tokenize) return builder.toAnnotatedString()

        var i = 0
        while (i < length) {
            val c = source[i]
            when {
                c == '#' && isCommentStart(source, i) -> {
                    val end = lineEnd(source, i)
                    builder.addStyle(SpanStyle(color = commentColor), i, end)
                    i = end
                }

                c == '/' && i + 1 < length && source[i + 1] == '/' && isCommentStart(source, i) -> {
                    val end = lineEnd(source, i)
                    builder.addStyle(SpanStyle(color = commentColor), i, end)
                    i = end
                }

                c == '"' || c == '\'' -> {
                    val end = stringEnd(source, i, c)
                    builder.addStyle(SpanStyle(color = stringColor), i, end)
                    i = end
                }

                c.isDigit() && (i == 0 || !isWordChar(source[i - 1])) -> {
                    var j = i + 1
                    while (j < length && (source[j].isLetterOrDigit() || source[j] == '.' || source[j] == '_')) {
                        j++
                    }
                    builder.addStyle(SpanStyle(color = numberColor), i, j)
                    i = j
                }

                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < length && (source[j].isLetterOrDigit() || source[j] == '_' || source[j] == '-')) {
                        j++
                    }
                    if (SHELL_KEYWORDS.contains(source.substring(i, j))) {
                        builder.addStyle(SpanStyle(color = keywordColor), i, j)
                    }
                    i = j
                }

                else -> i++
            }
        }
        return builder.toAnnotatedString()
    }
}

private fun isCommentStart(source: String, index: Int): Boolean {
    if (index == 0) return true
    val previous = source[index - 1]
    return previous.isWhitespace() || previous == ';' || previous == '\n'
}

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun lineEnd(source: String, from: Int): Int {
    val newline = source.indexOf('\n', from)
    return if (newline < 0) source.length else newline
}

private fun stringEnd(source: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < source.length) {
        val c = source[i]
        when {
            c == '\\' && i + 1 < source.length -> i += 2
            c == '\\' -> return source.length
            c == quote -> return i + 1
            c == '\n' -> return i
            else -> i++
        }
    }
    return source.length
}

/** Case-insensitive match offsets, capped at [MAX_MATCHES]. */
private fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val result = ArrayList<IntRange>()
    var index = text.indexOf(query, 0, ignoreCase = true)
    while (index >= 0 && result.size < MAX_MATCHES) {
        result.add(index until index + query.length)
        index = text.indexOf(query, index + 1, ignoreCase = true)
    }
    return result
}

/** Zero-based index of the line containing [offset]. */
private fun lineIndexAt(text: String, offset: Int): Int {
    val end = offset.coerceIn(0, text.length)
    var line = 0
    var i = 0
    while (i < end) {
        if (text[i] == '\n') line++
        i++
    }
    return line
}

/** One-based line and column for [offset]. */
private fun cursorPositionOf(text: String, offset: Int): Pair<Int, Int> {
    val end = offset.coerceIn(0, text.length)
    var line = 1
    var lastNewline = -1
    var i = 0
    while (i < end) {
        if (text[i] == '\n') {
            line++
            lastNewline = i
        }
        i++
    }
    return line to (end - lastNewline)
}

/** Character offset of the first character of one-based [lineNumber]. */
private fun lineStartOffset(text: String, lineNumber: Int): Int {
    if (lineNumber <= 1) return 0
    var line = 1
    var i = 0
    while (i < text.length) {
        if (text[i] == '\n') {
            line++
            if (line == lineNumber) return i + 1
        }
        i++
    }
    return text.length
}

/** NUL bytes or a high ratio of control bytes mean the payload is not editable text. */
private fun looksBinary(bytes: ByteArray): Boolean {
    val sample = minOf(bytes.size, 8_192)
    if (sample == 0) return false
    var control = 0
    for (i in 0 until sample) {
        val value = bytes[i].toInt() and 0xFF
        if (value == 0) return true
        if (value < 0x20 && value != '\n'.code && value != '\r'.code && value != '\t'.code) {
            control++
        }
    }
    return control * 20 > sample
}

/** Single-quotes [value] for a POSIX shell command line. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
