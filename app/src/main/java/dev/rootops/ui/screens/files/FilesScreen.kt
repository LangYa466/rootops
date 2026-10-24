package dev.rootops.ui.screens.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.FileEntry
import dev.rootops.core.api.SortKey
import dev.rootops.core.api.SortOrder
import dev.rootops.core.util.JsonStore
import dev.rootops.core.util.formatBytes
import dev.rootops.core.util.formatTimestamp
import dev.rootops.core.util.modeToRwx
import dev.rootops.core.util.truncateMiddle
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.nav.LocalNavigator
import dev.rootops.ui.nav.Route
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

private const val BOOKMARK_ITEMS_KEY = "items"
private const val MAX_TEXT_BYTES = 2L * 1024L * 1024L
private const val SEARCH_DEBOUNCE_MS = 300L

private val DEFAULT_BOOKMARKS = listOf("/", "/sdcard", "/data", "/system", "/data/local/tmp", "/proc")

private val TEXT_EXTENSIONS = setOf(
    "txt", "conf", "cfg", "ini", "json", "xml", "yaml", "yml", "sh", "rc", "properties",
    "log", "md", "env", "service", "toml", "py", "js", "ts", "kt", "java", "gradle", "prop",
)

private val MONO_NAME_EXTENSIONS = setOf(
    "conf", "cfg", "ini", "sh", "rc", "properties", "prop", "env", "service", "gradle",
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "ico")
private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk", "zst")
private val SCRIPT_EXTENSIONS = setOf("sh", "bash", "py", "js", "ts", "rb", "pl", "lua", "ps1")
private val CONFIG_EXTENSIONS = setOf(
    "conf", "cfg", "ini", "rc", "properties", "prop", "env", "service", "toml", "yaml", "yml", "xml", "json",
)

private enum class CreateKind { FILE, FOLDER }

private data class DestinationRequest(val paths: List<String>, val move: Boolean)

/**
 * Root file browser: listing, sorting, search, bookmarks, selection and file operations.
 * All repository work runs off the main thread and every call is guarded so the screen never crashes.
 */
@Composable
fun FilesScreen(container: AppContainer) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val rootAvailable by container.shell.rootAvailable.collectAsStateWithLifecycle()
    val progress by container.fileOps.progress.collectAsStateWithLifecycle()

    val bookmarkStore = remember { JsonStore(File(context.filesDir, "bookmarks.json")) }

    var bookmarks by remember { mutableStateOf(DEFAULT_BOOKMARKS) }
    var path by remember { mutableStateOf(normalizePath(settings.defaultPath)) }
    var pathInput by remember { mutableStateOf(path) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var sortKey by remember { mutableStateOf(SortKey.NAME) }
    var sortOrder by remember { mutableStateOf(SortOrder.ASC) }
    var dirsFirst by remember { mutableStateOf(true) }

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedPath by remember { mutableStateOf<String?>(null) }

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchWholeDevice by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<FileEntry>?>(null) }

    var opError by remember { mutableStateOf<String?>(null) }
    var clipboard by remember { mutableStateOf<List<String>>(emptyList()) }
    var clipboardCut by remember { mutableStateOf(false) }

    var destRequest by remember { mutableStateOf<DestinationRequest?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    var chmodOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    var sortMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bookmarks = withContext(Dispatchers.IO) { loadBookmarks(bookmarkStore) }
    }

    LaunchedEffect(path, reloadKey) {
        loading = true
        loadError = null
        try {
            entries = withContext(Dispatchers.IO) { container.files.list(path) }
        } catch (t: Throwable) {
            entries = emptyList()
            loadError = t.message?.takeIf { it.isNotBlank() } ?: "Cannot read $path"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(path) {
        pathInput = path
        selected = emptySet()
        expandedPath = null
    }

    LaunchedEffect(searchOpen, query, searchWholeDevice, path) {
        if (!searchOpen || query.isBlank()) {
            searchResults = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        val root = if (searchWholeDevice) "/" else path
        try {
            searchResults = withContext(Dispatchers.IO) {
                container.files.search(root, query.trim())
            }
        } catch (t: Throwable) {
            searchResults = emptyList()
            opError = t.message?.takeIf { it.isNotBlank() } ?: "Search failed"
        } finally {
            searching = false
        }
    }

    val comparator = remember(sortKey, sortOrder) { entryComparator(sortKey, sortOrder) }
    val visible = remember(entries, settings.showHidden, comparator, dirsFirst) {
        val filtered = if (settings.showHidden) {
            entries
        } else {
            entries.filter { !it.name.startsWith(".") }
        }
        if (dirsFirst) {
            filtered.sortedWith(compareByDescending<FileEntry> { it.isDir }.then(comparator))
        } else {
            filtered.sortedWith(comparator)
        }
    }

    val searchActive = searchOpen && query.isNotBlank()
    val activeResults = if (searchActive) searchResults else null
    val displayedEntries = activeResults ?: visible
    val listingError = loadError
    val operationError = opError

    fun reportFailure(t: Throwable, label: String) {
        opError = t.message?.takeIf { it.isNotBlank() }
            ?: "$label failed: ${t.javaClass.simpleName}"
    }

    fun toggleHidden() {
        try {
            container.settings.update { it.copy(showHidden = !it.showHidden) }
        } catch (t: Throwable) {
            reportFailure(t, "Update settings")
        }
    }

    fun runOp(label: String, onSuccess: () -> Unit = {}, op: suspend () -> Boolean) {
        scope.launch {
            try {
                if (op()) {
                    opError = null
                    onSuccess()
                    reloadKey++
                    snackbarHostState.showSnackbar("$label complete")
                } else {
                    opError = "$label failed"
                }
            } catch (t: Throwable) {
                reportFailure(t, label)
            }
        }
    }

    fun toggleSelection(target: String) {
        selected = if (selected.contains(target)) selected - target else selected + target
    }

    fun navigateToDir(target: String) {
        val normalized = normalizePath(target)
        path = normalized
        pathInput = normalized
        selected = emptySet()
        expandedPath = null
        if (searchOpen) {
            searchOpen = false
            query = ""
            searchResults = null
        }
    }

    fun openFile(entry: FileEntry) {
        if (isTextLike(entry)) {
            navigator(Route.Editor(entry.path))
        } else {
            navigator(Route.FileProperties(entry.path))
        }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isLink) {
            val link = entry.linkTarget
            if (!link.isNullOrBlank()) {
                val target = if (link.startsWith("/")) link else joinPath(parentOf(entry.path), link)
                scope.launch {
                    val resolved = try {
                        withContext(Dispatchers.IO) { container.files.stat(target) }
                    } catch (t: Throwable) {
                        reportFailure(t, "Follow link")
                        null
                    }
                    when {
                        resolved == null -> opError = "Cannot follow ${entry.name} → $target"
                        resolved.isDir -> navigateToDir(target)
                        else -> openFile(resolved)
                    }
                }
                return
            }
        }
        if (entry.isDir) navigateToDir(entry.path) else openFile(entry)
    }

    fun goParent() {
        val parent = parentOf(path)
        if (parent == path) navigator(Route.Home) else navigateToDir(parent)
    }

    fun deleteSelected() {
        val targets = selected.toList()
        if (targets.isEmpty()) return
        runOp("Delete", onSuccess = { selected = emptySet() }) {
            container.fileOps.delete(targets)
        }
    }

    fun requestDelete() {
        if (selected.isEmpty()) return
        if (settings.confirmDelete) deleteConfirm = true else deleteSelected()
    }

    fun addBookmark() {
        val current = normalizePath(path)
        if (bookmarks.contains(current)) {
            scope.launch { snackbarHostState.showSnackbar("Already bookmarked") }
            return
        }
        val next = bookmarks + current
        bookmarks = next
        scope.launch(Dispatchers.IO) { saveBookmarks(bookmarkStore, next) }
    }

    fun removeBookmark(target: String) {
        val next = bookmarks - target
        bookmarks = next
        scope.launch(Dispatchers.IO) { saveBookmarks(bookmarkStore, next) }
    }

    fun pasteClipboard() {
        val items = clipboard
        if (items.isEmpty()) return
        val cut = clipboardCut
        runOp(
            label = if (cut) "Move" else "Copy",
            onSuccess = {
                clipboard = emptyList()
                clipboardCut = false
            },
        ) {
            if (cut) container.fileOps.move(items, path) else container.fileOps.copy(items, path)
        }
    }

    AppScaffold(
        title = if (path == "/") "Files" else path.substringAfterLast('/'),
        subtitle = path,
        onBack = { goParent() },
        actions = {
            IconButton(
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) {
                        query = ""
                        searchResults = null
                    }
                },
            ) {
                Icon(
                    imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search",
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    val keys = listOf(SortKey.NAME, SortKey.SIZE, SortKey.MODIFIED, SortKey.TYPE)
                    keys.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(key)) },
                            trailingIcon = {
                                if (sortKey == key) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = {
                                sortKey = key
                                sortMenu = false
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text("Ascending") },
                        trailingIcon = {
                            if (sortOrder == SortOrder.ASC) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            sortOrder = SortOrder.ASC
                            sortMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Descending") },
                        trailingIcon = {
                            if (sortOrder == SortOrder.DESC) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            sortOrder = SortOrder.DESC
                            sortMenu = false
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text("Directories first") },
                        trailingIcon = {
                            if (dirsFirst) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = { dirsFirst = !dirsFirst },
                    )
                }
            }
            Box {
                IconButton(onClick = { bookmarkMenu = true }) {
                    Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks")
                }
                DropdownMenu(expanded = bookmarkMenu, onDismissRequest = { bookmarkMenu = false }) {
                    if (bookmarks.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No bookmarks") },
                            onClick = { bookmarkMenu = false },
                        )
                    }
                    bookmarks.forEach { bookmark ->
                        DropdownMenuItem(
                            text = { MonoText(truncateMiddle(bookmark, 36), size = 12.sp) },
                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { removeBookmark(bookmark) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove bookmark",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                            onClick = {
                                bookmarkMenu = false
                                navigateToDir(bookmark)
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text("Add current folder") },
                        leadingIcon = { Icon(Icons.Filled.BookmarkBorder, contentDescription = null) },
                        onClick = {
                            bookmarkMenu = false
                            addBookmark()
                        },
                    )
                }
            }
            Box {
                IconButton(onClick = { overflowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = {
                            overflowMenu = false
                            reloadKey++
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (settings.showHidden) "Hide hidden files" else "Show hidden files") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (settings.showHidden) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            overflowMenu = false
                            toggleHidden()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add current folder to bookmarks") },
                        leadingIcon = { Icon(Icons.Filled.BookmarkBorder, contentDescription = null) },
                        onClick = {
                            overflowMenu = false
                            addBookmark()
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionActionBar(
                    count = selected.size,
                    single = selected.size == 1,
                    onCopy = { destRequest = DestinationRequest(selected.toList(), move = false) },
                    onMove = { destRequest = DestinationRequest(selected.toList(), move = true) },
                    onCut = {
                        val items = selected.toList()
                        clipboard = items
                        clipboardCut = true
                        selected = emptySet()
                        scope.launch { snackbarHostState.showSnackbar("Cut ${items.size} item(s)") }
                    },
                    onDelete = { requestDelete() },
                    onRename = {
                        val only = selected.firstOrNull()
                        val target = displayedEntries.firstOrNull { it.path == only }
                        if (target != null) renameTarget = target
                    },
                    onChmod = { chmodOpen = true },
                    onProperties = {
                        selected.firstOrNull()?.let { navigator(Route.FileProperties(it)) }
                    },
                    onClear = { selected = emptySet() },
                )
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New")
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New file") },
                        leadingIcon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
                        onClick = {
                            fabMenu = false
                            createKind = CreateKind.FILE
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            fabMenu = false
                            createKind = CreateKind.FOLDER
                        },
                    )
                    if (clipboard.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (clipboardCut) {
                                        "Move ${clipboard.size} item(s) here"
                                    } else {
                                        "Paste ${clipboard.size} item(s) here"
                                    },
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                            onClick = {
                                fabMenu = false
                                pasteClipboard()
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                BreadcrumbsRow(path = path, onNavigate = { navigateToDir(it) })

                if (searchOpen) {
                    SearchRow(
                        query = query,
                        path = path,
                        wholeDevice = searchWholeDevice,
                        onQueryChange = { query = it },
                        onToggleWholeDevice = { searchWholeDevice = !searchWholeDevice },
                        onClose = {
                            searchOpen = false
                            query = ""
                            searchResults = null
                        },
                    )
                } else {
                    PathBar(
                        value = pathInput,
                        currentPath = path,
                        onValueChange = { pathInput = it },
                        onGo = { navigateToDir(pathInput) },
                        onUp = { goParent() },
                        onRefresh = { reloadKey++ },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        text = "${displayedEntries.size} items",
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    TagChip(
                        text = if (rootAvailable) "root" else "no root",
                        color = if (rootAvailable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        selected = rootAvailable,
                    )
                    if (selected.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        TagChip(
                            text = "${selected.size} selected",
                            color = MaterialTheme.colorScheme.secondary,
                            selected = true,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (displayedEntries.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selected = if (selected.size == displayedEntries.size) {
                                    emptySet()
                                } else {
                                    displayedEntries.map { it.path }.toSet()
                                }
                            },
                        ) {
                            Text(
                                text = if (selected.size == displayedEntries.size) "Clear" else "Select all",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (progress.running) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        val detail = if (progress.total > 0) {
                            "${progress.label} ${progress.done}/${progress.total}"
                        } else {
                            progress.label
                        }
                        MonoText(
                            text = detail.ifBlank { "Working…" },
                            size = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (operationError != null) {
                    val message = operationError
                    ErrorBanner(message = message, onRetry = { opError = null })
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        searchActive && searching && activeResults == null -> LoadingRow("Searching…")

                        searchActive && activeResults != null -> {
                            val results = activeResults
                            if (results.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Filled.Search,
                                    title = "No matches",
                                    message = "Nothing matched \"$query\" under ${
                                        if (searchWholeDevice) "/" else path
                                    }.",
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(results, key = { it.path }) { entry ->
                                        EntryRow(
                                            entry = entry,
                                            selectionMode = selected.isNotEmpty(),
                                            isSelected = selected.contains(entry.path),
                                            expanded = expandedPath == entry.path,
                                            showFullPath = true,
                                            onOpen = { openEntry(entry) },
                                            onToggleSelection = { toggleSelection(entry.path) },
                                            onLongPress = {
                                                selected = selected + entry.path
                                                expandedPath = entry.path
                                            },
                                            onProperties = { navigator(Route.FileProperties(entry.path)) },
                                            onRename = { renameTarget = entry },
                                            onChmod = {
                                                selected = setOf(entry.path)
                                                chmodOpen = true
                                            },
                                            onDelete = {
                                                selected = setOf(entry.path)
                                                requestDelete()
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        loading -> LoadingRow("Reading $path…")

                        listingError != null -> {
                            val message = listingError
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ErrorBanner(message = message, onRetry = { reloadKey++ })
                                EmptyState(
                                    icon = Icons.Filled.FolderOpen,
                                    title = "Cannot open folder",
                                    message = message,
                                    actionLabel = "Retry",
                                    onAction = { reloadKey++ },
                                )
                            }
                        }

                        visible.isEmpty() -> EmptyState(
                            icon = Icons.Filled.FolderOpen,
                            title = "Empty folder",
                            message = if (settings.showHidden) {
                                "Nothing in $path."
                            } else {
                                "Nothing visible in $path; hidden entries are filtered out."
                            },
                            actionLabel = if (settings.showHidden) {
                                "Hide hidden files"
                            } else {
                                "Show hidden files"
                            },
                            onAction = {
                                toggleHidden()
                            },
                        )

                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.path }) { entry ->
                                EntryRow(
                                    entry = entry,
                                    selectionMode = selected.isNotEmpty(),
                                    isSelected = selected.contains(entry.path),
                                    expanded = expandedPath == entry.path,
                                    showFullPath = false,
                                    onOpen = { openEntry(entry) },
                                    onToggleSelection = { toggleSelection(entry.path) },
                                    onLongPress = {
                                        selected = selected + entry.path
                                        expandedPath = entry.path
                                    },
                                    onProperties = { navigator(Route.FileProperties(entry.path)) },
                                    onRename = { renameTarget = entry },
                                    onChmod = {
                                        selected = setOf(entry.path)
                                        chmodOpen = true
                                    },
                                    onDelete = {
                                        selected = setOf(entry.path)
                                        requestDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    val destination = destRequest
    if (destination != null) {
        DestinationPickerDialog(
            title = if (destination.move) {
                "Move ${destination.paths.size} item(s)"
            } else {
                "Copy ${destination.paths.size} item(s)"
            },
            initialPath = path,
            listDir = { dir -> container.files.list(dir) },
            onDismiss = { destRequest = null },
            onConfirm = { target ->
                destRequest = null
                runOp(
                    label = if (destination.move) "Move" else "Copy",
                    onSuccess = { selected = emptySet() },
                ) {
                    if (destination.move) {
                        container.fileOps.move(destination.paths, target)
                    } else {
                        container.fileOps.copy(destination.paths, target)
                    }
                }
            },
        )
    }

    val renaming = renameTarget
    if (renaming != null) {
        NameInputDialog(
            title = "Rename",
            label = "New name",
            initial = renaming.name,
            confirmLabel = "Rename",
            requireChange = true,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                runOp("Rename") { container.fileOps.rename(renaming.path, newName) }
            },
        )
    }

    val creating = createKind
    if (creating != null) {
        NameInputDialog(
            title = if (creating == CreateKind.FOLDER) "New folder" else "New file",
            label = "Name",
            initial = "",
            confirmLabel = "Create",
            requireChange = false,
            onDismiss = { createKind = null },
            onConfirm = { name ->
                createKind = null
                runOp("Create") {
                    if (creating == CreateKind.FOLDER) {
                        container.fileOps.mkdir(path, name)
                    } else {
                        container.fileOps.createFile(path, name)
                    }
                }
            },
        )
    }

    if (chmodOpen) {
        val targets = selected.toList()
        ChmodDialog(
            count = targets.size,
            onDismiss = { chmodOpen = false },
            onConfirm = { mode, recursive ->
                chmodOpen = false
                runOp("Permissions") {
                    var ok = true
                    for (target in targets) {
                        if (!container.fileOps.chmod(target, mode, recursive)) ok = false
                    }
                    ok
                }
            },
        )
    }

    if (deleteConfirm) {
        val names = selected.take(6).joinToString("\n")
        val extra = if (selected.size > 6) "\n…and ${selected.size - 6} more" else ""
        ConfirmDialog(
            title = "Delete ${selected.size} item(s)?",
            message = names + extra,
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleteConfirm = false
                deleteSelected()
            },
            onDismiss = { deleteConfirm = false },
        )
    }
}

@Composable
private fun BreadcrumbsRow(path: String, onNavigate: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val segments = pathSegments(path)
    LaunchedEffect(path) { scrollState.animateScrollTo(scrollState.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            val label = segment.first
            val target = segment.second
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = if (index == 0) "root" else label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFont,
                color = if (index == segments.lastIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onNavigate(target) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PathBar(
    value: String,
    currentPath: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Parent folder")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
            placeholder = { Text("/path/to/folder", style = MaterialTheme.typography.bodySmall) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            trailingIcon = {
                if (value.trim().isNotEmpty() && normalizePath(value) != currentPath) {
                    TextButton(onClick = onGo) { Text("Go") }
                }
            },
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    path: String,
    wholeDevice: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleWholeDevice: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(if (wholeDevice) "Search whole device" else "Search in $path") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
            trailingIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        TagChip(
            text = "whole device",
            color = MaterialTheme.colorScheme.secondary,
            selected = wholeDevice,
            onClick = onToggleWholeDevice,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FileEntry,
    selectionMode: Boolean,
    isSelected: Boolean,
    expanded: Boolean,
    showFullPath: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onProperties: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val rowBackground = if (isSelected) accent.copy(alpha = 0.10f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onOpen() },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                imageVector = iconFor(entry),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when {
                    entry.isDir -> MaterialTheme.colorScheme.primary
                    entry.isLink -> MaterialTheme.colorScheme.tertiary
                    entry.executable -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (monospaceName(entry)) {
                    MonoText(entry.name, size = 13.sp)
                } else {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showFullPath) {
                    MonoText(
                        text = entry.path,
                        size = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val link = entry.linkTarget
                if (entry.isLink && !link.isNullOrBlank()) {
                    MonoText(
                        text = "→ $link",
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(2.dp))
                    MonoText(
                        text = buildString {
                            append(modeToRwx(entry.mode))
                            if (entry.owner.isNotBlank()) {
                                append("  ").append(entry.owner)
                                if (entry.group.isNotBlank()) append(":").append(entry.group)
                            }
                            append("  ").append(formatBytes(entry.size))
                        },
                        size = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                MonoText(
                    text = if (entry.isDir) "—" else formatBytes(entry.size),
                    size = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.modified > 0) {
                    Text(
                        text = formatTimestamp(entry.modified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (!selectionMode) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Actions",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Properties") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onProperties()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Permissions") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onChmod()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@Composable
private fun SelectionActionBar(
    count: Int,
    single: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onProperties: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(
                    text = "$count",
                    size = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                BarAction(Icons.Filled.ContentCopy, "Copy", onCopy)
                BarAction(Icons.Filled.ContentCut, "Cut", onCut)
                BarAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move", onMove)
                BarAction(Icons.Filled.Edit, "Rename", onRename, enabled = single)
                BarAction(Icons.Filled.Lock, "Chmod", onChmod)
                BarAction(Icons.Filled.Delete, "Delete", onDelete)
                BarAction(Icons.Filled.Info, "Properties", onProperties, enabled = single)
                BarAction(Icons.Filled.Close, "Clear", onClear)
            }
        }
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DestinationPickerDialog(
    title: String,
    initialPath: String,
    listDir: suspend (String) -> List<FileEntry>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var dir by remember { mutableStateOf(normalizePath(initialPath)) }
    var folders by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(dir, reload) {
        loading = true
        error = null
        try {
            val listed = withContext(Dispatchers.IO) { listDir(dir) }
            folders = listed.filter { it.isDir }.sortedBy { it.name.lowercase() }
        } catch (t: Throwable) {
            folders = emptyList()
            error = t.message?.takeIf { it.isNotBlank() } ?: "Cannot read $dir"
        } finally {
            loading = false
        }
    }

    val message = error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonoText(dir, size = 12.sp, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { dir = parentOf(dir) }, enabled = dir != "/") {
                        Text("Up")
                    }
                    Spacer(Modifier.weight(1f))
                    MonoText(
                        text = "${folders.size} folders",
                        size = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading) LoadingRow("Reading…")
                if (message != null) ErrorBanner(message = message, onRetry = { reload++ })
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(folders, key = { it.path }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dir = folder.path }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dir) }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    requireChange: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val invalid = text.isBlank() || text.contains('/') || (requireChange && text == initial)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(label) },
                isError = invalid && text.isNotBlank(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (!invalid) onConfirm(text.trim()) },
                enabled = !invalid,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun ChmodDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var mode by remember { mutableStateOf("755") }
    var recursive by remember { mutableStateOf(false) }
    val sanitized = mode.filter { it in '0'..'7' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change permissions", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonoText(
                    text = "$count item(s) selected",
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mode,
                    onValueChange = { input -> mode = input.filter { it in '0'..'7' }.take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Octal mode") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                )
                Spacer(Modifier.height(6.dp))
                MonoText(
                    text = modeToRwx(sanitized.ifEmpty { "000" }),
                    size = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recursive", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = recursive, onCheckedChange = { recursive = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sanitized, recursive) },
                enabled = sanitized.isNotEmpty(),
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

private fun sortLabel(key: SortKey): String = when (key) {
    SortKey.NAME -> "Name"
    SortKey.SIZE -> "Size"
    SortKey.MODIFIED -> "Modified"
    SortKey.TYPE -> "Type"
}

private fun entryComparator(key: SortKey, order: SortOrder): Comparator<FileEntry> {
    val base: Comparator<FileEntry> = when (key) {
        SortKey.NAME -> compareBy<FileEntry> { it.name.lowercase() }
        SortKey.SIZE -> compareBy<FileEntry> { it.size }
        SortKey.MODIFIED -> compareBy<FileEntry> { it.modified }
        SortKey.TYPE -> compareBy<FileEntry> { it.extension.lowercase() }
            .thenBy { it.name.lowercase() }
    }
    return if (order == SortOrder.ASC) base else base.reversed()
}

private fun iconFor(entry: FileEntry): ImageVector {
    val ext = entry.extension.lowercase()
    return when {
        entry.isLink -> Icons.Filled.Link
        entry.isDir -> Icons.Filled.Folder
        ext in IMAGE_EXTENSIONS -> Icons.Filled.Image
        ext in ARCHIVE_EXTENSIONS -> Icons.Filled.Archive
        ext in SCRIPT_EXTENSIONS -> Icons.Filled.Code
        ext in CONFIG_EXTENSIONS -> Icons.Filled.Settings
        else -> Icons.Filled.Description
    }
}

private fun monospaceName(entry: FileEntry): Boolean =
    !entry.isDir && entry.extension.lowercase() in MONO_NAME_EXTENSIONS

private fun isTextLike(entry: FileEntry): Boolean =
    !entry.isDir && entry.size < MAX_TEXT_BYTES && entry.extension.lowercase() in TEXT_EXTENSIONS

private fun normalizePath(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "/"
    val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    val stack = ArrayList<String>()
    for (part in absolute.split('/')) {
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(part)
        }
    }
    return if (stack.isEmpty()) "/" else stack.joinToString("/", prefix = "/")
}

private fun joinPath(base: String, name: String): String = when {
    name.startsWith("/") -> normalizePath(name)
    base == "/" -> "/$name"
    else -> "$base/$name"
}

private fun parentOf(path: String): String {
    val normalized = normalizePath(path)
    if (normalized == "/") return "/"
    val index = normalized.lastIndexOf('/')
    return if (index <= 0) "/" else normalized.substring(0, index)
}

private fun pathSegments(path: String): List<Pair<String, String>> {
    val normalized = normalizePath(path)
    val out = ArrayList<Pair<String, String>>()
    out.add("/" to "/")
    var accumulated = ""
    for (segment in normalized.split('/')) {
        if (segment.isEmpty()) continue
        accumulated = "$accumulated/$segment"
        out.add(segment to accumulated)
    }
    return out
}

/** Bookmarks live in `bookmarks.json` as `{"items": ["/", "/sdcard", …]}`; a missing file seeds the defaults. */
private fun loadBookmarks(store: JsonStore): List<String> {
    val array = (try {
        store.read().optJSONArray(BOOKMARK_ITEMS_KEY)
    } catch (t: Throwable) {
        null
    }) ?: return DEFAULT_BOOKMARKS
    val stored = ArrayList<String>()
    for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotEmpty() && !stored.contains(value)) stored.add(value)
    }
    return stored
}

private fun saveBookmarks(store: JsonStore, paths: List<String>) {
    try {
        val array = JSONArray()
        for (path in paths) array.put(path)
        store.update { it.put(BOOKMARK_ITEMS_KEY, array) }
    } catch (t: Throwable) {
        // Bookmark persistence is best-effort; the in-memory list stays authoritative.
    }
}
