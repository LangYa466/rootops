@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.rootops.ui.screens.mounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.MountEntry
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.KeyValueRow
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.theme.ConsoleAccent
import dev.rootops.ui.theme.ConsoleWarning
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RowShape = RoundedCornerShape(10.dp)
private val BannerShape = RoundedCornerShape(10.dp)

/** Read-only view of the kernel mount table with root remount/umount/mount actions. */
@Composable
fun MountsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val rootAvailable by container.shell.rootAvailable.collectAsStateWithLifecycle()

    var entries by remember { mutableStateOf<List<MountEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(QuickFilter.ALL) }
    var selected by remember { mutableStateOf<MountEntry?>(null) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showMountDialog by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { container.mounts.list() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            entries = emptyList()
            error = failure.message ?: "Could not read the mount table"
        } finally {
            loading = false
        }
    }

    fun runAction(entry: MountEntry, action: PendingAction) {
        scope.launch {
            busy = true
            val ok = try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        PendingAction.REMOUNT_RW ->
                            container.mounts.remount(entry.mountPoint, readOnly = false)

                        PendingAction.REMOUNT_RO ->
                            container.mounts.remount(entry.mountPoint, readOnly = true)

                        PendingAction.UMOUNT -> container.mounts.umount(entry.mountPoint)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                false
            }
            busy = false
            pending = null
            selected = null
            if (ok) {
                reload()
            } else {
                error = "${action.confirmLabel} failed for ${entry.mountPoint}"
            }
        }
    }

    fun runMount(device: String, mountPoint: String, fsType: String, options: String) {
        scope.launch {
            busy = true
            val ok = try {
                withContext(Dispatchers.IO) {
                    container.mounts.mount(device, mountPoint, fsType, options)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                false
            }
            busy = false
            showMountDialog = false
            if (ok) {
                reload()
            } else {
                error = "Mount failed for $mountPoint"
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val visible = remember(entries, query, filter) {
        val needle = query.trim()
        entries.filter { entry ->
            val matchesQuery = needle.isEmpty() ||
                entry.device.contains(needle, ignoreCase = true) ||
                entry.mountPoint.contains(needle, ignoreCase = true) ||
                entry.fsType.contains(needle, ignoreCase = true)
            matchesQuery && filter.matches(entry)
        }
    }
    val reloadAction: () -> Unit = { scope.launch { reload() } }

    AppScaffold(
        title = "Mounts",
        subtitle = if (loading) {
            "Reading mount table…"
        } else {
            "${visible.size} of ${entries.size} mounts"
        },
        actions = {
            IconButton(
                onClick = { scope.launch { reload() } },
                enabled = !busy,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh mounts")
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showMountDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Mount filesystem")
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
                filter = filter,
                onFilterChange = { filter = it },
            )

            if (!rootAvailable) RootWarning()

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            error?.let { message ->
                ErrorBanner(message = message, onRetry = { scope.launch { reload() } })
            }

            when {
                loading && entries.isEmpty() -> LoadingRow("Reading /proc/mounts…")

                visible.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Storage,
                    title = if (entries.isEmpty()) "No mounts found" else "No matching mounts",
                    message = if (entries.isEmpty()) {
                        "The mount table could not be read or is empty."
                    } else {
                        "Adjust the filter to see more entries."
                    },
                    actionLabel = if (entries.isEmpty()) "Reload" else null,
                    onAction = if (entries.isEmpty()) reloadAction else null,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                ) {
                    itemsIndexed(visible) { index, entry ->
                        MountRow(entry = entry, onClick = { selected = entry })
                        if (index < visible.lastIndex) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        MountSheet(
            entry = entry,
            rootAvailable = rootAvailable,
            busy = busy,
            onDismiss = { selected = null },
            onAction = { pending = it },
        )
    }

    pending?.let { action ->
        val entry = selected
        if (entry == null) {
            pending = null
        } else {
            ConfirmDialog(
                title = action.title,
                message = action.message(entry),
                confirmLabel = action.confirmLabel,
                destructive = action.destructive,
                onConfirm = { runAction(entry, action) },
                onDismiss = { pending = null },
            )
        }
    }

    if (showMountDialog) {
        MountDialog(
            busy = busy,
            onDismiss = { showMountDialog = false },
            onSubmit = { device, mountPoint, fsType, options ->
                runMount(device, mountPoint, fsType, options)
            },
        )
    }
}

@Composable
private fun FilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: QuickFilter,
    onFilterChange: (QuickFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Filter device, mount point or type") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear filter")
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(QuickFilter.entries) { option ->
                TagChip(
                    text = option.label,
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                )
            }
        }
    }
}

@Composable
private fun RootWarning() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = BannerShape,
        color = ConsoleWarning.copy(alpha = 0.12f),
        contentColor = ConsoleWarning,
        border = BorderStroke(1.dp, ConsoleWarning.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Root unavailable — mount operations need root.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MountRow(entry: MountEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RowShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MonoText(
                    text = entry.mountPoint,
                    size = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.device,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagChip(
                        text = entry.fsType,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    TagChip(
                        text = if (entry.readOnly) "ro" else "rw",
                        color = if (entry.readOnly) ConsoleWarning else ConsoleAccent,
                        selected = true,
                    )
                    Text(
                        text = "${entry.options.size} opts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MountSheet(
    entry: MountEntry,
    rootAvailable: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAction: (PendingAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = entry.mountPoint,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = MonoFont,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Device", entry.device)
            KeyValueRow("Type", entry.fsType)
            KeyValueRow("Access", if (entry.readOnly) "read-only" else "read-write")
            KeyValueRow("Options", entry.options.size.toString())
            Spacer(Modifier.height(8.dp))
            SectionHeader("Mount options")
            Text(
                text = entry.options.joinToString(", ").ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (!rootAvailable) {
                Text(
                    text = "Root unavailable — these actions need root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ConsoleWarning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(PendingAction.REMOUNT_RW) },
                    enabled = !busy && rootAvailable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Remount rw")
                }
                OutlinedButton(
                    onClick = { onAction(PendingAction.REMOUNT_RO) },
                    enabled = !busy && rootAvailable,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Remount ro")
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onAction(PendingAction.UMOUNT) },
                enabled = !busy && rootAvailable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("Umount")
            }
        }
    }
}

@Composable
private fun MountDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (device: String, mountPoint: String, fsType: String, options: String) -> Unit,
) {
    var device by remember { mutableStateOf("") }
    var mountPoint by remember { mutableStateOf("") }
    var fsType by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Mount filesystem", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = { Text("Device") },
                    placeholder = { Text("/dev/block/sda1") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mountPoint,
                    onValueChange = { mountPoint = it },
                    label = { Text("Mount point") },
                    placeholder = { Text("/mnt/usb") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fsType,
                    onValueChange = { fsType = it },
                    label = { Text("Filesystem type") },
                    placeholder = { Text("ext4, vfat, tmpfs…") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = options,
                    onValueChange = { options = it },
                    label = { Text("Options") },
                    placeholder = { Text("rw,noatime") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                localError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val trimmedDevice = device.trim()
                    val trimmedPoint = mountPoint.trim()
                    when {
                        trimmedDevice.isEmpty() -> localError = "Device is required"
                        !trimmedPoint.startsWith("/") ->
                            localError = "Mount point must be an absolute path"

                        else -> {
                            localError = null
                            onSubmit(
                                trimmedDevice,
                                trimmedPoint,
                                fsType.trim(),
                                options.trim(),
                            )
                        }
                    }
                },
            ) {
                Text(if (busy) "Mounting…" else "Mount")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Quick access filters offered above the mount list. */
private enum class QuickFilter(val label: String) {
    ALL("all"),
    RO("ro"),
    RW("rw"),
    TMPFS("tmpfs"),
    FUSE("fuse"),
    EXT4("ext4"),
    ;

    fun matches(entry: MountEntry): Boolean = when (this) {
        ALL -> true
        RO -> entry.readOnly
        RW -> !entry.readOnly
        TMPFS -> entry.fsType == "tmpfs"
        FUSE -> entry.fsType.startsWith("fuse")
        EXT4 -> entry.fsType == "ext4"
    }
}

/** Destructive/privileged action awaiting confirmation. */
private enum class PendingAction(
    val title: String,
    val confirmLabel: String,
    val destructive: Boolean,
) {
    REMOUNT_RW(
        title = "Remount read-write",
        confirmLabel = "Remount rw",
        destructive = false,
    ),
    REMOUNT_RO(
        title = "Remount read-only",
        confirmLabel = "Remount ro",
        destructive = false,
    ),
    UMOUNT(
        title = "Unmount filesystem",
        confirmLabel = "Umount",
        destructive = true,
    ),
    ;

    fun message(entry: MountEntry): String = when (this) {
        REMOUNT_RW ->
            "Remount ${entry.mountPoint} read-write?\n\nDevice: ${entry.device}"

        REMOUNT_RO ->
            "Remount ${entry.mountPoint} read-only?\n\nDevice: ${entry.device}"

        UMOUNT ->
            "Unmount ${entry.mountPoint}?\n\nOpen files on this filesystem may cause the " +
                "unmount to fail; the lazy fallback is used when the first attempt fails."
    }
}
