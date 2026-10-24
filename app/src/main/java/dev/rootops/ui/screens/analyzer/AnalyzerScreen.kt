package dev.rootops.ui.screens.analyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rootops.AppContainer
import dev.rootops.core.api.FileRepository
import dev.rootops.core.api.ShellRunner
import dev.rootops.core.util.formatBytes
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.StatTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_PATH = "/data"
private const val MAX_ENTRIES = 60
private const val BAR_HEIGHT_DP = 28
private const val DU_TIMEOUT_MS = 60_000L

/** One analyzed child of the current path. */
private data class UsageEntry(
    val path: String,
    val name: String,
    val bytes: Long,
)

private val DU_LINE = Regex("^(\\d+)\\s+(.+)$")

/**
 * Disk-usage analyzer: runs `du` over the children of a path and renders the
 * largest consumers as horizontal bars. Tapping a bar drills into that entry.
 */
@Composable
fun AnalyzerScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()

    var pathInput by remember { mutableStateOf(DEFAULT_PATH) }
    var currentPath by remember { mutableStateOf(DEFAULT_PATH) }
    var entries by remember { mutableStateOf<List<UsageEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun analyze(target: String) {
        busy = true
        error = null
        val outcome = runCatching {
            withContext(Dispatchers.IO) { loadUsage(container.shell, container.files, target) }
        }
        outcome.fold(
            onSuccess = { result ->
                entries = result
                currentPath = target
                pathInput = target
            },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                entries = emptyList()
                error = failure.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to analyze $target"
            },
        )
        busy = false
    }

    fun drill(entry: UsageEntry) {
        scope.launch {
            val isDirectory = withContext(Dispatchers.IO) {
                runCatching { container.files.stat(entry.path) }.getOrNull()?.isDir ?: true
            }
            if (isDirectory) analyze(entry.path)
        }
    }

    LaunchedEffect(Unit) { analyze(currentPath) }

    val parent = parentOf(currentPath)
    val total = entries.sumOf { it.bytes }
    val maxBytes = entries.maxOfOrNull { it.bytes } ?: 0L

    AppScaffold(
        title = "Storage analyzer",
        subtitle = currentPath,
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
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("Path") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { scope.launch { analyze(normalizePath(pathInput)) } },
                    enabled = !busy,
                ) {
                    Text("Analyze")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { parent?.let { up -> scope.launch { analyze(up) } } },
                    enabled = parent != null && !busy,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = "Up one level",
                    )
                }
                MonoText(
                    text = currentPath,
                    modifier = Modifier.weight(1f),
                    size = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (busy) "Scanning…" else "${entries.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val currentError = error
            if (currentError != null) {
                ErrorBanner(
                    message = currentError,
                    onRetry = { scope.launch { analyze(currentPath) } },
                )
            }

            if (busy) {
                LoadingRow(text = "Analyzing $currentPath…")
            } else {
                StatTile(
                    label = "Total",
                    value = formatBytes(total),
                    caption = "across ${entries.size} entries in $currentPath",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (entries.isEmpty()) {
                    if (currentError == null) {
                        EmptyState(
                            icon = Icons.Filled.Folder,
                            title = "Nothing to analyze",
                            message = "No readable entries under $currentPath.",
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(entries) { entry ->
                            UsageBar(
                                entry = entry,
                                maxBytes = maxBytes,
                                onClick = { drill(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageBar(entry: UsageEntry, maxBytes: Long, onClick: () -> Unit) {
    val fraction = if (maxBytes <= 0L) {
        0f
    } else {
        (entry.bytes.toFloat() / maxBytes.toFloat()).coerceIn(0.01f, 1f)
    }
    val barShape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            MonoText(
                text = formatBytes(entry.bytes),
                size = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT_DP.dp)
                .clip(barShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(barShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
            )
        }
    }
}

private suspend fun loadUsage(
    shell: ShellRunner,
    files: FileRepository,
    path: String,
): List<UsageEntry> {
    val pattern = if (path == "/") "/*" else "${shellQuote(path)}/*"
    val command = "du -sk $pattern 2>/dev/null | sort -n -r | head -n $MAX_ENTRIES"
    val result = runCatching {
        shell.run(command, root = true, timeoutMs = DU_TIMEOUT_MS)
    }.getOrNull()
    val parsed = result?.let { parseDu(it.out) }.orEmpty()
    if (parsed.isNotEmpty()) return parsed
    return listOneLevel(files, path)
}

/** Parses `du -sk` output lines of the form `sizeKb<TAB>path`. */
private fun parseDu(output: String): List<UsageEntry> =
    output.lineSequence()
        .mapNotNull { line ->
            val match = DU_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val kb = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val entryPath = match.groupValues[2].trim()
            if (entryPath.isEmpty()) return@mapNotNull null
            UsageEntry(
                path = entryPath,
                name = displayName(entryPath),
                bytes = kb * 1024L,
            )
        }
        .sortedByDescending { it.bytes }
        .take(MAX_ENTRIES)
        .toList()

/** Fallback when `du` is unavailable: one directory level, recursing only for sizes. */
private suspend fun listOneLevel(
    files: FileRepository,
    path: String,
): List<UsageEntry> {
    val children = files.list(path)
    val result = ArrayList<UsageEntry>(children.size)
    for (child in children) {
        val bytes = if (child.isDir) {
            runCatching { files.diskUsage(child.path) }.getOrDefault(child.size)
        } else {
            child.size
        }
        result += UsageEntry(
            path = child.path,
            name = child.name.ifEmpty { displayName(child.path) },
            bytes = bytes.coerceAtLeast(0L),
        )
    }
    return result.sortedByDescending { it.bytes }.take(MAX_ENTRIES)
}

private fun displayName(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return path
    return trimmed.substringAfterLast('/').ifEmpty { trimmed }
}

private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

private fun normalizePath(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "/"
    val collapsed = trimmed.replace(Regex("/{2,}"), "/")
    return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
}

private fun parentOf(path: String): String? {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return null
    val index = trimmed.lastIndexOf('/')
    return if (index <= 0) "/" else trimmed.substring(0, index)
}
