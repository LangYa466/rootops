package dev.rootops.ui.screens.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rootops.AppContainer
import dev.rootops.core.api.FileEntry
import dev.rootops.core.util.formatBytes
import dev.rootops.core.util.formatTimestamp
import dev.rootops.core.util.modeToRwx
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
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
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(10.dp)

/** Outcome of the last chmod/chown/rename/delete, shown inline above the details. */
private data class OpResult(val ok: Boolean, val message: String)

/**
 * Detailed metadata for a single filesystem entry plus inline editors for the
 * permission bits and the owner/group pair.
 */
@Composable
fun PropertiesScreen(container: AppContainer, path: String? = null) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var currentPath by remember(path) { mutableStateOf(path) }
    var entry by remember(path) { mutableStateOf<FileEntry?>(null) }
    var loading by remember(path) { mutableStateOf(path != null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var childCount by remember(path) { mutableStateOf<Int?>(null) }
    var usageBytes by remember(path) { mutableStateOf<Long?>(null) }
    var reloadToken by remember(path) { mutableStateOf(0) }

    var modeInput by remember(path) { mutableStateOf("") }
    var recursive by remember(path) { mutableStateOf(false) }
    var ownerInput by remember(path) { mutableStateOf("") }
    var groupInput by remember(path) { mutableStateOf("") }
    var busy by remember(path) { mutableStateOf(false) }
    var result by remember(path) { mutableStateOf<OpResult?>(null) }
    var showRename by remember(path) { mutableStateOf(false) }
    var showDelete by remember(path) { mutableStateOf(false) }

    LaunchedEffect(currentPath, reloadToken) {
        val target = currentPath
        if (target == null) {
            loading = false
            entry = null
            loadError = null
            childCount = null
            usageBytes = null
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        try {
            val stat = withContext(Dispatchers.IO) { container.files.stat(target) }
            entry = stat
            if (stat != null) {
                modeInput = rwxToOctal(stat.mode)
                ownerInput = stat.owner
                groupInput = stat.group
                if (stat.isDir) {
                    childCount = runCatching {
                        withContext(Dispatchers.IO) { container.files.childrenCount(target) }
                    }.getOrNull()
                    usageBytes = runCatching {
                        withContext(Dispatchers.IO) { container.files.diskUsage(target) }
                    }.getOrNull()
                } else {
                    childCount = null
                    usageBytes = null
                }
            } else {
                childCount = null
                usageBytes = null
            }
        } catch (t: Throwable) {
            entry = null
            childCount = null
            usageBytes = null
            loadError = t.message ?: t.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    fun applyChmod() {
        val item = entry ?: return
        val mode = modeInput.trim()
        if (busy || !isOctalMode(mode)) return
        val target = item.path
        val isRecursive = item.isDir && recursive
        scope.launch {
            busy = true
            result = null
            val ok = try {
                withContext(Dispatchers.IO) { container.fileOps.chmod(target, mode, isRecursive) }
            } catch (t: Throwable) {
                false
            }
            busy = false
            result = if (ok) {
                OpResult(true, "Mode changed to $mode")
            } else {
                OpResult(false, "chmod $mode failed")
            }
            if (ok) reloadToken++
        }
    }

    fun applyChown() {
        val item = entry ?: return
        val owner = ownerInput.trim()
        val group = groupInput.trim()
        if (busy || owner.isEmpty() || group.isEmpty()) return
        val target = item.path
        val isRecursive = item.isDir && recursive
        scope.launch {
            busy = true
            result = null
            val ok = try {
                withContext(Dispatchers.IO) { container.fileOps.chown(target, owner, group, isRecursive) }
            } catch (t: Throwable) {
                false
            }
            busy = false
            result = if (ok) {
                OpResult(true, "Owner changed to $owner:$group")
            } else {
                OpResult(false, "chown $owner:$group failed")
            }
            if (ok) reloadToken++
        }
    }

    val displayName = when {
        currentPath == null -> "Properties"
        currentPath == "/" -> "/"
        else -> currentPath.orEmpty().trimEnd('/').substringAfterLast('/').ifEmpty { currentPath.orEmpty() }
    }

    AppScaffold(
        title = "Properties",
        subtitle = entry?.name ?: displayName,
        onBack = { nav(Route.Files) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (loading) LoadingRow("Reading metadata…")
            if (loadError != null) {
                ErrorBanner(message = loadError.orEmpty(), onRetry = { reloadToken++ })
            }
            val outcome = result
            if (outcome != null) {
                if (outcome.ok) {
                    SuccessBanner(outcome.message)
                } else {
                    ErrorBanner(message = outcome.message)
                }
            }

            val item = entry
            if (!loading && item == null && loadError == null) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "File not found",
                    message = currentPath ?: "No path was provided.",
                )
            }

            if (item != null) {
                SectionHeader("Location")
                SelectionContainer {
                    MonoText(
                        text = item.path,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "Size",
                        value = formatBytes(item.size),
                        caption = if (item.isDir) "directory entry" else null,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Modified",
                        value = modifiedText(item),
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionHeader("Metadata")
                KeyValueRow("Type", typeLabel(item))
                if (item.isLink && !item.linkTarget.isNullOrBlank()) {
                    KeyValueRow("Link target", item.linkTarget.orEmpty())
                }
                KeyValueRow("Permissions", permissionText(item))
                KeyValueRow("Owner:group", "${item.owner}:${item.group}")
                KeyValueRow("Size", formatBytes(item.size))
                KeyValueRow("Modified", modifiedText(item))
                if (item.isDir) {
                    KeyValueRow("Children", childCount?.toString() ?: "—")
                    KeyValueRow(
                        "Disk usage (du -sb)",
                        usageBytes?.let { formatBytes(it) } ?: "—",
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TagChip(
                        text = "readable",
                        color = MaterialTheme.colorScheme.secondary,
                        selected = item.readable,
                    )
                    TagChip(
                        text = "writable",
                        color = MaterialTheme.colorScheme.secondary,
                        selected = item.writable,
                    )
                    TagChip(
                        text = "executable",
                        color = MaterialTheme.colorScheme.secondary,
                        selected = item.executable,
                    )
                    if (item.isLink) {
                        TagChip(
                            text = "symlink",
                            color = MaterialTheme.colorScheme.tertiary,
                            selected = true,
                        )
                    }
                    if (item.special) {
                        TagChip(
                            text = "special",
                            color = MaterialTheme.colorScheme.tertiary,
                            selected = true,
                        )
                    }
                }

                SectionHeader("Actions")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(item.path)) },
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Copy path", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!item.isDir) {
                        OutlinedButton(
                            onClick = { nav(Route.Editor(item.path)) },
                            modifier = Modifier.weight(1f),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Open", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showRename = true },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DriveFileRenameOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Rename", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { showDelete = true },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                SectionHeader("Permissions")
                PermissionCard(
                    isDir = item.isDir,
                    mode = modeInput,
                    onModeChange = { modeInput = it },
                    recursive = recursive,
                    onRecursiveChange = { recursive = it },
                    busy = busy,
                    onApply = { applyChmod() },
                )

                SectionHeader("Ownership")
                OwnershipCard(
                    owner = ownerInput,
                    group = groupInput,
                    onOwnerChange = { ownerInput = it },
                    onGroupChange = { groupInput = it },
                    isDir = item.isDir,
                    recursive = recursive,
                    busy = busy,
                    onApply = { applyChown() },
                )

                if (showRename) {
                    RenameDialog(
                        initialName = item.name,
                        onDismiss = { showRename = false },
                        onConfirm = { newName ->
                            showRename = false
                            if (!busy) {
                                val target = item.path
                                scope.launch {
                                    busy = true
                                    result = null
                                    val ok = try {
                                        withContext(Dispatchers.IO) {
                                            container.fileOps.rename(target, newName)
                                        }
                                    } catch (t: Throwable) {
                                        false
                                    }
                                    busy = false
                                    if (ok) {
                                        result = OpResult(true, "Renamed to $newName")
                                        currentPath = siblingPath(target, newName)
                                    } else {
                                        result = OpResult(false, "Rename failed")
                                    }
                                }
                            }
                        },
                    )
                }

                if (showDelete) {
                    ConfirmDialog(
                        title = "Delete",
                        message = "Delete \"${item.name}\"? This cannot be undone.",
                        confirmLabel = "Delete",
                        destructive = true,
                        onConfirm = {
                            showDelete = false
                            val target = item.path
                            scope.launch {
                                busy = true
                                result = null
                                val ok = try {
                                    withContext(Dispatchers.IO) {
                                        container.fileOps.delete(listOf(target))
                                    }
                                } catch (t: Throwable) {
                                    false
                                }
                                busy = false
                                if (ok) {
                                    nav(Route.Files)
                                } else {
                                    result = OpResult(false, "Delete failed")
                                }
                            }
                        },
                        onDismiss = { showDelete = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    isDir: Boolean,
    mode: String,
    onModeChange: (String) -> Unit,
    recursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    busy: Boolean,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mode,
                    onValueChange = { input ->
                        onModeChange(input.filter { it in '0'..'7' }.take(4))
                    },
                    label = { Text("Octal mode") },
                    singleLine = true,
                    isError = !isOctalMode(mode),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                )
                Spacer(Modifier.width(12.dp))
                MonoText(
                    text = modeToRwx(mode),
                    size = MaterialTheme.typography.bodyMedium.fontSize,
                )
            }
            if (!isOctalMode(mode)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Use 3 digits (755) or 4 with the special bits (4755).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (isDir) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Recursive", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Apply to every child entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = recursive,
                        onCheckedChange = onRecursiveChange,
                        enabled = !busy,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onApply,
                    enabled = !busy && isOctalMode(mode),
                ) {
                    Text("Apply mode")
                }
            }
        }
    }
}

@Composable
private fun OwnershipCard(
    owner: String,
    group: String,
    onOwnerChange: (String) -> Unit,
    onGroupChange: (String) -> Unit,
    isDir: Boolean,
    recursive: Boolean,
    busy: Boolean,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = owner,
                    onValueChange = onOwnerChange,
                    label = { Text("Owner") },
                    singleLine = true,
                    isError = owner.isBlank(),
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = onGroupChange,
                    label = { Text("Group") },
                    singleLine = true,
                    isError = group.isBlank(),
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                )
            }
            if (isDir) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (recursive) {
                        "Recursive is on: children are updated too."
                    } else {
                        "Recursive is off: only this directory changes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onApply,
                    enabled = !busy && owner.isNotBlank() && group.isNotBlank(),
                ) {
                    Text("Apply owner")
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && !trimmed.contains('/')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New name") },
                    singleLine = true,
                    isError = !valid,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                )
                if (!valid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Name must not be empty or contain '/'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(trimmed) },
                enabled = valid,
            ) {
                Text("Rename")
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

@Composable
private fun SuccessBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = CardShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** `rwxr-xr-x` → `755`; symbolic special bits are kept as a leading digit (`4755`). */
private fun rwxToOctal(mode: String): String {
    val trimmed = mode.trim()
    if (trimmed.isEmpty()) return "000"
    if (trimmed.all { it in '0'..'7' }) {
        val digits = trimmed.takeLast(4)
        return if (digits.length == 4 && digits[0] == '0') {
            digits.substring(1)
        } else {
            digits.padStart(3, '0')
        }
    }
    val symbolic = stripTypeChar(trimmed).padEnd(9, '-').take(9)
    fun digit(base: Int): Int {
        var value = 0
        if (symbolic[base] == 'r') value += 4
        if (symbolic[base + 1] == 'w') value += 2
        val exec = symbolic[base + 2]
        if (exec == 'x' || exec == 's' || exec == 't') value += 1
        return value
    }
    val setUid = if (symbolic[2] == 's' || symbolic[2] == 'S') 4 else 0
    val setGid = if (symbolic[5] == 's' || symbolic[5] == 'S') 2 else 0
    val sticky = if (symbolic[8] == 't' || symbolic[8] == 'T') 1 else 0
    val special = setUid + setGid + sticky
    val tail = "${digit(0)}${digit(3)}${digit(6)}"
    return if (special == 0) tail else "$special$tail"
}

private fun stripTypeChar(mode: String): String {
    val trimmed = mode.trim()
    return if (trimmed.length == 10 && trimmed[0] in "dlbcps-") trimmed.substring(1) else trimmed
}

private fun isOctalMode(value: String): Boolean =
    value.length in 3..4 && value.all { it in '0'..'7' }

private fun typeLabel(entry: FileEntry): String = when {
    entry.isLink -> buildString {
        append("symlink")
        val target = entry.linkTarget
        if (!target.isNullOrBlank()) append(" → ").append(target)
        if (entry.isDir) append(" (directory)")
    }
    entry.isDir -> "directory"
    else -> "file"
}

private fun permissionText(entry: FileEntry): String {
    val rwx = modeToRwx(entry.mode)
    val raw = entry.mode.trim()
    return if (raw.isEmpty() || raw == rwx) rwx else "$rwx  $raw"
}

private fun modifiedText(entry: FileEntry): String =
    if (entry.modified > 0) formatTimestamp(entry.modified) else "unknown"

private fun siblingPath(path: String, newName: String): String {
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    return when (parent) {
        "" -> newName
        "/" -> "/$newName"
        else -> "$parent/$newName"
    }
}
