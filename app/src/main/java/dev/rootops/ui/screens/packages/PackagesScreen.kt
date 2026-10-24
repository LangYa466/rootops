package dev.rootops.ui.screens.packages

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.PackageEntry
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.TagChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Installed-application browser backed by [AppContainer.packages].
 *
 * The whole list is fetched once per filter change and every row is rendered from the
 * already-parsed [PackageEntry], so a few hundred packages scroll without any per-frame
 * `pm` work. Each row is keyed by package name so recomposition stays limited to the rows
 * that actually change.
 *
 * Every repository call is defensive: the package layer reports failures as `false`/`null`,
 * and anything that does throw is turned into an [ErrorBanner] instead of crashing.
 */
@Composable
fun PackagesScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootAvailable by container.shell.rootAvailable.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var includeSystem by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    var entries by remember { mutableStateOf<List<PackageEntry>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var loadToken by remember { mutableIntStateOf(0) }

    var sheetTarget by remember { mutableStateOf<PackageEntry?>(null) }
    var clearTarget by remember { mutableStateOf<PackageEntry?>(null) }
    var uninstallTarget by remember { mutableStateOf<PackageEntry?>(null) }
    var apkRequest by remember { mutableStateOf<ApkPathRequest?>(null) }

    // Keep the root chip honest: the shell layer caches its last probe.
    LaunchedEffect(Unit) {
        runCatching { container.shell.refreshRoot() }
    }

    LaunchedEffect(includeSystem, reloadToken) {
        // A newer load may supersede this one while `pm` is still running; only the most
        // recent request is allowed to publish its result.
        val token = ++loadToken
        loading = true
        error = null
        try {
            val listing = container.packages.list(includeSystem)
            if (token == loadToken) entries = listing
        } catch (t: Throwable) {
            if (token == loadToken) {
                error = t.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to read installed packages"
            }
        } finally {
            if (token == loadToken) loading = false
        }
    }

    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        delay(4_000)
        if (notice == current) notice = null
    }

    fun startOp(
        action: String,
        success: String,
        failure: String,
        refresh: Boolean = false,
        block: suspend () -> Boolean,
    ) {
        if (busy != null) return
        busy = action
        error = null
        scope.launch {
            var ok = false
            try {
                ok = block()
            } catch (t: Throwable) {
                error = t.message?.takeIf { it.isNotBlank() } ?: failure
            }
            busy = null
            if (ok) {
                notice = success
                if (refresh) reloadToken++
            } else if (error == null) {
                error = failure
            }
        }
    }

    fun copyText(label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            error = "Clipboard is unavailable on this device"
            return
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            notice = "Copied $label"
        } catch (t: Throwable) {
            error = t.message?.takeIf { it.isNotBlank() } ?: "Couldn't copy to the clipboard"
        }
    }

    fun launchApp(entry: PackageEntry) {
        startOp(
            action = "Launching ${entry.pkg}",
            success = "Launched ${entry.pkg}",
            failure = "Couldn't launch ${entry.pkg}",
        ) { container.packages.launch(entry.pkg) }
    }

    fun toggleEnabled(entry: PackageEntry) {
        val enable = !entry.enabled
        startOp(
            action = if (enable) "Enabling ${entry.pkg}" else "Disabling ${entry.pkg}",
            success = if (enable) "Enabled ${entry.pkg}" else "Disabled ${entry.pkg}",
            failure = if (enable) "Couldn't enable ${entry.pkg}" else "Couldn't disable ${entry.pkg}",
            refresh = true,
        ) { container.packages.setEnabled(entry.pkg, enable) }
    }

    fun clearData(entry: PackageEntry) {
        startOp(
            action = "Clearing data for ${entry.pkg}",
            success = "Cleared data for ${entry.pkg}",
            failure = "Couldn't clear data for ${entry.pkg}",
        ) { container.packages.clearData(entry.pkg) }
    }

    fun uninstall(entry: PackageEntry, keepData: Boolean) {
        startOp(
            action = "Uninstalling ${entry.pkg}",
            success = "Uninstalled ${entry.pkg}",
            failure = "Couldn't uninstall ${entry.pkg}",
            refresh = true,
        ) { container.packages.uninstall(entry.pkg, keepData) }
    }

    fun resolveApkPath(entry: PackageEntry) {
        apkRequest = ApkPathRequest(entry = entry, loading = true)
        scope.launch {
            val resolved = try {
                val path = container.packages.apkPath(entry.pkg)
                ApkPathRequest(
                    entry = entry,
                    loading = false,
                    path = path,
                    message = if (path.isNullOrBlank()) {
                        "No APK path was reported for ${entry.pkg}"
                    } else {
                        null
                    },
                )
            } catch (t: Throwable) {
                ApkPathRequest(
                    entry = entry,
                    loading = false,
                    message = t.message?.takeIf { it.isNotBlank() }
                        ?: "Couldn't resolve the APK path",
                )
            }
            // Only publish when the dialog for this package is still the one on screen.
            if (apkRequest?.entry?.pkg == entry.pkg) apkRequest = resolved
        }
    }

    val loaded = entries
    val errorText = error
    val noticeText = notice
    val busyText = busy

    val rows = remember(loaded) {
        loaded.orEmpty().map { entry ->
            PackageRow(
                entry = entry,
                title = entry.label?.takeIf { it.isNotBlank() } ?: entry.pkg,
                version = entry.versionName?.takeIf { it.isNotBlank() }
                    ?: if (entry.versionCode > 0L) entry.versionCode.toString() else null,
            )
        }
    }
    val needle = query.trim().lowercase()
    val visible = remember(rows, needle) {
        if (needle.isEmpty()) {
            rows
        } else {
            rows.filter { it.searchKey.contains(needle) }
        }
    }

    val subtitle = busyText ?: when {
        loaded == null -> null
        needle.isNotEmpty() -> "${visible.size} of ${rows.size} shown"
        rows.size == 1 -> "1 package"
        else -> "${rows.size} packages"
    }

    AppScaffold(
        title = "Packages",
        subtitle = subtitle,
        actions = {
            if (loading || busyText != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(
                onClick = { reloadToken++ },
                enabled = !loading && busyText == null,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh packages")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            FilterBar(
                query = query,
                onQueryChange = { query = it },
                includeSystem = includeSystem,
                onIncludeSystemChange = { includeSystem = it },
                rootAvailable = rootAvailable,
                packageCount = loaded?.size,
            )
            if (errorText != null) {
                ErrorBanner(message = errorText, onRetry = { reloadToken++ })
            }
            if (noticeText != null) {
                NoticeRow(text = noticeText)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    loaded == null && loading -> LoadingRow("Reading installed packages…")

                    loaded == null -> EmptyState(
                        icon = Icons.Filled.Warning,
                        title = "Couldn't load packages",
                        message = "No package data is available.",
                    )

                    visible.isEmpty() && needle.isNotEmpty() -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No matches",
                        message = "Nothing matches \"${query.trim()}\".",
                        actionLabel = "Clear search",
                        onAction = { query = "" },
                    )

                    visible.isEmpty() && includeSystem -> EmptyState(
                        icon = Icons.Filled.Apps,
                        title = "No packages",
                        message = "The system did not report any installed packages.",
                    )

                    visible.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Apps,
                        title = "No user apps",
                        message = "No user apps are installed. Turn on system apps to see the full list.",
                        actionLabel = "Show system apps",
                        onAction = { includeSystem = true },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(
                            items = visible,
                            key = { it.entry.pkg },
                            contentType = { "package" },
                        ) { row ->
                            PackageRowItem(
                                row = row,
                                onClick = { sheetTarget = row.entry },
                            )
                        }
                    }
                }
            }
        }
    }

    val sheetEntry = sheetTarget
    if (sheetEntry != null) {
        PackageActionSheet(
            entry = sheetEntry,
            busy = busyText != null,
            onDismiss = { sheetTarget = null },
            onLaunch = {
                sheetTarget = null
                launchApp(sheetEntry)
            },
            onToggleEnabled = {
                sheetTarget = null
                toggleEnabled(sheetEntry)
            },
            onClearData = {
                sheetTarget = null
                if (settings.confirmDelete) clearTarget = sheetEntry else clearData(sheetEntry)
            },
            onUninstall = {
                sheetTarget = null
                uninstallTarget = sheetEntry
            },
            onCopyName = {
                sheetTarget = null
                copyText("package name", sheetEntry.pkg)
            },
            onShowApkPath = {
                sheetTarget = null
                resolveApkPath(sheetEntry)
            },
        )
    }

    val clearEntry = clearTarget
    if (clearEntry != null) {
        ConfirmDialog(
            title = "Clear app data",
            message = "Delete everything stored by ${clearEntry.label ?: clearEntry.pkg}? " +
                "The app stays installed.",
            confirmLabel = "Clear data",
            destructive = true,
            onConfirm = {
                clearTarget = null
                clearData(clearEntry)
            },
            onDismiss = { clearTarget = null },
        )
    }

    val uninstallEntry = uninstallTarget
    if (uninstallEntry != null) {
        UninstallDialog(
            entry = uninstallEntry,
            onDismiss = { uninstallTarget = null },
            onConfirm = { keepData ->
                uninstallTarget = null
                uninstall(uninstallEntry, keepData)
            },
        )
    }

    val request = apkRequest
    if (request != null) {
        ApkPathDialog(
            request = request,
            onCopy = { path -> copyText("APK path", path) },
            onDismiss = { apkRequest = null },
        )
    }
}

/** Row model with the label and version resolved once per listing, plus a lowercase search key. */
private class PackageRow(
    val entry: PackageEntry,
    val title: String,
    val version: String?,
) {
    /** Lowercase haystack so typing never re-normalises every package name. */
    val searchKey: String = (entry.pkg + " " + title).lowercase()
}

/** One-shot lookup of the APK backing a package. */
private class ApkPathRequest(
    val entry: PackageEntry,
    val loading: Boolean,
    val path: String? = null,
    val message: String? = null,
)

@Composable
private fun FilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    includeSystem: Boolean,
    onIncludeSystemChange: (Boolean) -> Unit,
    rootAvailable: Boolean,
    packageCount: Int?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text(
                    text = "Search package or label",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagChip(
                text = if (rootAvailable) "root" else "no root",
                color = if (rootAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                selected = true,
            )
            if (packageCount != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (packageCount == 1) "1 package" else "$packageCount packages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "System apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = includeSystem,
                onCheckedChange = onIncludeSystemChange,
            )
        }
        if (!rootAvailable) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Package operations require root access.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun PackageRowItem(row: PackageRow, onClick: () -> Unit) {
    val entry = row.entry
    val titleColor = if (entry.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.version != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(
                text = entry.pkg,
                modifier = Modifier.weight(1f),
                size = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip(
                    text = if (entry.system) "system" else "user",
                    color = if (entry.system) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    selected = true,
                )
                if (!entry.enabled) {
                    TagChip(
                        text = "disabled",
                        color = MaterialTheme.colorScheme.tertiary,
                        selected = true,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageActionSheet(
    entry: PackageEntry,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onToggleEnabled: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
    onCopyName: () -> Unit,
    onShowApkPath: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = entry.label?.takeIf { it.isNotBlank() } ?: entry.pkg,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                MonoText(
                    text = entry.pkg,
                    size = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(
                        text = if (entry.system) "system" else "user",
                        color = if (entry.system) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        selected = true,
                    )
                    TagChip(
                        text = if (entry.enabled) "enabled" else "disabled",
                        color = if (entry.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        selected = true,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ActionRow(
                icon = Icons.Filled.PlayArrow,
                label = "Launch",
                enabled = !busy,
                onClick = onLaunch,
            )
            ActionRow(
                icon = if (entry.enabled) Icons.Filled.Block else Icons.Filled.CheckCircle,
                label = if (entry.enabled) "Disable" else "Enable",
                enabled = !busy,
                onClick = onToggleEnabled,
            )
            ActionRow(
                icon = Icons.Filled.DeleteSweep,
                label = "Clear data",
                enabled = !busy,
                onClick = onClearData,
            )
            ActionRow(
                icon = Icons.Filled.ContentCopy,
                label = "Copy package name",
                enabled = !busy,
                onClick = onCopyName,
            )
            ActionRow(
                icon = Icons.Filled.Folder,
                label = "Show APK path",
                enabled = !busy,
                onClick = onShowApkPath,
            )
            ActionRow(
                icon = Icons.Filled.Delete,
                label = "Uninstall",
                enabled = !busy,
                tint = MaterialTheme.colorScheme.error,
                onClick = onUninstall,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) tint else tint.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun NoticeRow(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UninstallDialog(
    entry: PackageEntry,
    onDismiss: () -> Unit,
    onConfirm: (keepData: Boolean) -> Unit,
) {
    var keepData by remember(entry.pkg) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uninstall app", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = "Remove ${entry.label?.takeIf { it.isNotBlank() } ?: entry.pkg} " +
                        "for the current user?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = keepData,
                            role = Role.Checkbox,
                            onValueChange = { keepData = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keepData, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Keep app data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(keepData) }) {
                Text("Uninstall", color = MaterialTheme.colorScheme.error)
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
private fun ApkPathDialog(
    request: ApkPathRequest,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val path = request.path
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("APK path", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                MonoText(
                    text = request.entry.pkg,
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                when {
                    request.loading -> Text(
                        text = "Resolving…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    path != null -> MonoText(
                        text = path,
                        size = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    else -> Text(
                        text = request.message ?: "No APK path found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (path != null) {
                TextButton(onClick = { onCopy(path) }) { Text("Copy") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
