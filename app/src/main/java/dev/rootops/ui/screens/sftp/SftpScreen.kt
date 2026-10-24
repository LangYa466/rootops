package dev.rootops.ui.screens.sftp

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.FileEntry
import dev.rootops.core.api.SshProfile
import dev.rootops.core.api.TransferItem
import dev.rootops.core.api.TransferState
import dev.rootops.core.util.formatBytes
import dev.rootops.core.util.formatTimestamp
import dev.rootops.core.util.truncateMiddle
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.KeyValueRow
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.screens.ssh.SshProfileStore
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

private const val DOWNLOAD_DIR = "/sdcard/Download"
private const val ROOT_PATH = "/"
private const val TRANSFER_PANEL_MAX_HEIGHT_DP = 190

private val SLASHES = Regex("/+")

private val ENTRY_ORDER: Comparator<FileEntry> =
    compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) }

/**
 * SFTP browser: pick a saved SSH profile, connect, walk the remote tree, move files both ways
 * and watch the transfer queue. All remote work goes through [AppContainer.sftp].
 */
@Composable
fun SftpScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cleanupScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val transfers by container.sftp.transfers.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var profiles by remember { mutableStateOf<List<SshProfile>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var profileMenuOpen by remember { mutableStateOf(false) }

    var handle by remember { mutableStateOf<Long?>(null) }
    var sessionLabel by remember { mutableStateOf("") }
    var remotePath by rememberSaveable { mutableStateOf(ROOT_PATH) }
    var pathField by rememberSaveable { mutableStateOf(ROOT_PATH) }

    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var connecting by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var uploadPickerOpen by remember { mutableStateOf(false) }
    var localFiles by remember { mutableStateOf<List<FileEntry>?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val selectedProfile = profiles.firstOrNull { it.id == selectedId }

    fun refreshProfiles() {
        val loaded = try {
            SshProfileStore.load(context)
        } catch (t: Exception) {
            error = messageOf(t)
            emptyList()
        }
        profiles = loaded
        if (loaded.none { it.id == selectedId }) {
            selectedId = loaded.firstOrNull()?.id
        }
    }

    suspend fun loadDirectory(handleId: Long, target: String) {
        listing = true
        try {
            entries = container.sftp.list(handleId, target).sortedWith(ENTRY_ORDER)
            remotePath = target
            pathField = target
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            error = messageOf(t)
        } finally {
            listing = false
        }
    }

    fun connect() {
        val profile = selectedProfile ?: return
        scope.launch {
            connecting = true
            error = null
            entries = emptyList()
            try {
                val id = container.sftp.connect(profile)
                handle = id
                sessionLabel = "${profile.user}@${profile.host}"
                remotePath = ROOT_PATH
                pathField = ROOT_PATH
                loadDirectory(id, ROOT_PATH)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                handle = null
                sessionLabel = ""
                error = messageOf(t)
            } finally {
                connecting = false
            }
        }
    }

    fun disconnect() {
        val id = handle ?: return
        scope.launch {
            handle = null
            sessionLabel = ""
            entries = emptyList()
            remotePath = ROOT_PATH
            pathField = ROOT_PATH
            error = null
            try {
                container.sftp.disconnect(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                error = messageOf(t)
            }
        }
    }

    fun reload() {
        val id = handle ?: return
        scope.launch { loadDirectory(id, remotePath) }
    }

    fun retry() {
        if (handle != null) reload() else connect()
    }

    fun navigateTo(target: String) {
        val id = handle ?: return
        scope.launch { loadDirectory(id, normalizePath(target)) }
    }

    fun downloadEntry(entry: FileEntry) {
        val id = handle ?: return
        scope.launch {
            error = null
            try {
                container.sftp.download(id, entry.path, "$DOWNLOAD_DIR/${entry.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                error = messageOf(t)
            }
        }
    }

    fun uploadFile(file: FileEntry) {
        val id = handle ?: return
        scope.launch {
            error = null
            try {
                container.sftp.upload(id, file.path, remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                error = messageOf(t)
            }
        }
    }

    fun createFolder(name: String) {
        val id = handle ?: return
        val target = joinPath(remotePath, name.trim())
        scope.launch {
            error = null
            try {
                container.sftp.mkdir(id, target)
                loadDirectory(id, remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                error = messageOf(t)
            }
        }
    }

    fun deleteEntry(entry: FileEntry) {
        val id = handle ?: return
        scope.launch {
            error = null
            try {
                container.sftp.remove(id, entry.path, entry.isDir)
                loadDirectory(id, remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                error = messageOf(t)
            }
        }
    }

    fun openUploadPicker() {
        uploadPickerOpen = true
        localFiles = null
        localError = null
        scope.launch {
            try {
                localFiles = container.files
                    .list(DOWNLOAD_DIR)
                    .filterNot { it.isDir }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                localError = messageOf(t)
                localFiles = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { refreshProfiles() }

    val liveHandle = rememberUpdatedState(handle)
    DisposableEffect(Unit) {
        onDispose {
            val id = liveHandle.value
            if (id != null) {
                cleanupScope.launch {
                    try {
                        container.sftp.disconnect(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Exception) {
                        // Session teardown is best effort once the screen is gone.
                    }
                }
            }
        }
    }

    val sortedTransfers = remember(transfers) { transfers.sortedByDescending { it.id } }

    AppScaffold(
        title = "SFTP",
        subtitle = when {
            handle != null -> "$sessionLabel · $remotePath"
            connecting -> "Connecting…"
            else -> "Not connected"
        },
        actions = {
            if (handle != null) {
                IconButton(
                    onClick = { reload() },
                    enabled = !listing,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh directory")
                }
                IconButton(onClick = { newFolderOpen = true }, enabled = !listing) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
                IconButton(onClick = { openUploadPicker() }, enabled = !listing) {
                    Icon(Icons.Filled.UploadFile, contentDescription = "Upload file")
                }
            }
        },
        floatingActionButton = {
            if (handle != null) {
                FloatingActionButton(
                    onClick = { openUploadPicker() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = "Upload file")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ProfileBar(
                profiles = profiles,
                selected = selectedProfile,
                menuOpen = profileMenuOpen,
                connected = handle != null,
                connecting = connecting,
                onMenuOpenChange = { open ->
                    profileMenuOpen = open
                    if (open) refreshProfiles()
                },
                onSelect = { profile -> selectedId = profile.id },
                onConnect = { connect() },
                onDisconnect = { disconnect() },
            )

            if (error != null) {
                ErrorBanner(
                    message = error ?: "",
                    onRetry = { retry() },
                )
            }

            if (handle != null) {
                PathBar(
                    value = pathField,
                    enabled = !listing,
                    onValueChange = { pathField = it },
                    onGo = { navigateTo(pathField) },
                    onUp = { navigateTo(parentPath(remotePath)) },
                )
                Breadcrumbs(path = remotePath, onNavigate = { navigateTo(it) })
            }

            if (sortedTransfers.isNotEmpty()) {
                TransfersPanel(
                    transfers = sortedTransfers,
                    onCancel = { transferId -> container.sftp.cancel(transferId) },
                )
            }

            SectionHeader(
                text = if (handle != null) "Remote · ${entries.size} items" else "Remote",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    handle == null && profiles.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Cloud,
                        title = "No SSH profiles",
                        message = "Create a connection profile on the SSH screen, then come back to browse it over SFTP.",
                    )

                    handle == null -> EmptyState(
                        icon = Icons.Filled.Cloud,
                        title = "Not connected",
                        message = "Pick a profile above and press Connect.",
                    )

                    listing -> LoadingRow("Listing $remotePath…")

                    entries.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Folder,
                        title = "Empty directory",
                        message = remotePath,
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = entries, key = { it.path }) { entry ->
                            RemoteEntryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDir) {
                                        navigateTo(entry.path)
                                    } else {
                                        actionEntry = entry
                                    }
                                },
                                onLongClick = { actionEntry = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    val pendingAction = actionEntry
    if (pendingAction != null) {
        EntryActionsDialog(
            entry = pendingAction,
            onDownload = {
                actionEntry = null
                downloadEntry(pendingAction)
            },
            onDelete = {
                actionEntry = null
                if (settings.confirmDelete) {
                    deleteTarget = pendingAction
                } else {
                    deleteEntry(pendingAction)
                }
            },
            onDismiss = { actionEntry = null },
        )
    }

    val pendingDelete = deleteTarget
    if (pendingDelete != null) {
        ConfirmDialog(
            title = if (pendingDelete.isDir) "Delete remote folder?" else "Delete remote file?",
            message = pendingDelete.path,
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                deleteEntry(pendingDelete)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (newFolderOpen) {
        NewFolderDialog(
            currentPath = remotePath,
            onDismiss = { newFolderOpen = false },
            onCreate = { name ->
                newFolderOpen = false
                createFolder(name)
            },
        )
    }

    if (uploadPickerOpen) {
        UploadPickerDialog(
            files = localFiles,
            error = localError,
            remoteDir = remotePath,
            onDismiss = { uploadPickerOpen = false },
            onPick = { file ->
                uploadPickerOpen = false
                uploadFile(file)
            },
        )
    }
}

@Composable
private fun ProfileBar(
    profiles: List<SshProfile>,
    selected: SshProfile?,
    menuOpen: Boolean,
    connected: Boolean,
    connecting: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onSelect: (SshProfile) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { onMenuOpenChange(true) },
                enabled = !connected && profiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selected?.let { "${it.name} · ${it.user}@${it.host}:${it.port}" }
                        ?: if (profiles.isEmpty()) "No profiles" else "Select profile",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = menuOpen && profiles.isNotEmpty(),
                onDismissRequest = { onMenuOpenChange(false) },
            ) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                MonoText(
                                    text = "${profile.user}@${profile.host}:${profile.port}",
                                    size = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(profile)
                            onMenuOpenChange(false)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        if (connected) {
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        } else {
            Button(
                onClick = onConnect,
                enabled = selected != null && !connecting,
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (connecting) "Connecting" else "Connect")
            }
        }
    }
}

@Composable
private fun PathBar(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    onUp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = enabled) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Parent directory")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
            placeholder = {
                Text(
                    text = ROOT_PATH,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFont,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
        )
        IconButton(onClick = onGo, enabled = enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open path",
            )
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val crumbs = remember(path) {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var accumulated = ""
        parts.map { segment ->
            accumulated += "/$segment"
            segment to accumulated
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ROOT_PATH,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { onNavigate(ROOT_PATH) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        crumbs.forEach { (segment, target) ->
            Text(
                text = ROOT_PATH,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.outline,
            )
            TagChip(text = segment, onClick = { onNavigate(target) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteEntryRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (entry.isDir) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.isDir) "directory" else formatBytes(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.modified > 0) {
                    Text(
                        text = " · ${formatTimestamp(entry.modified)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (entry.isLink) {
            Spacer(Modifier.width(6.dp))
            TagChip(text = "link", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun TransfersPanel(transfers: List<TransferItem>, onCancel: (Long) -> Unit) {
    val active = transfers.count { it.state == TransferState.RUNNING || it.state == TransferState.QUEUED }
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            text = "Transfers",
            trailing = {
                Text(
                    text = if (active > 0) "$active active" else "${transfers.size} total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = TRANSFER_PANEL_MAX_HEIGHT_DP.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            transfers.forEach { item ->
                TransferRow(item = item, onCancel = { onCancel(item.id) })
            }
        }
    }
}

@Composable
private fun TransferRow(item: TransferItem, onCancel: () -> Unit) {
    val fraction = if (item.total > 0L) {
        (item.done.toFloat() / item.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val accent = when (item.state) {
        TransferState.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        TransferState.RUNNING -> MaterialTheme.colorScheme.primary
        TransferState.DONE -> MaterialTheme.colorScheme.primary
        TransferState.FAILED -> MaterialTheme.colorScheme.error
        TransferState.CANCELLED -> MaterialTheme.colorScheme.outline
    }
    val cancellable = item.state == TransferState.QUEUED || item.state == TransferState.RUNNING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagChip(
                text = if (item.upload) "Upload" else "Download",
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TagChip(text = item.state.name, color = accent, selected = true)
            if (cancellable) {
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel transfer",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = accent,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(
                text = "${formatBytes(item.done)} / ${formatBytes(item.total)}",
                modifier = Modifier.weight(1f),
                size = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val failure = item.error
            if (failure != null) {
                MonoText(
                    text = truncateMiddle(failure, 48),
                    size = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EntryActionsDialog(
    entry: FileEntry,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonoText(
                    text = entry.path,
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                KeyValueRow(
                    label = "Size",
                    value = if (entry.isDir) "directory" else formatBytes(entry.size),
                )
                if (entry.modified > 0) {
                    KeyValueRow(label = "Modified", value = formatTimestamp(entry.modified))
                }
                Spacer(Modifier.height(10.dp))
                if (!entry.isDir) {
                    TextButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Download to $DOWNLOAD_DIR")
                    }
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NewFolderDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonoText(
                    text = "in $currentPath",
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
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
private fun UploadPickerDialog(
    files: List<FileEntry>?,
    error: String?,
    remoteDir: String,
    onDismiss: () -> Unit,
    onPick: (FileEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Upload file", style = MaterialTheme.typography.titleSmall)
                MonoText(
                    text = "from $DOWNLOAD_DIR to $remoteDir",
                    size = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                when {
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    files == null -> LoadingRow("Reading local files…")

                    files.isEmpty() -> Text(
                        text = "No files in $DOWNLOAD_DIR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = files, key = { it.path }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(file) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                MonoText(
                                    text = formatBytes(file.size),
                                    size = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun messageOf(t: Throwable): String =
    t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "Operation failed"

private fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

private fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return ROOT_PATH
    val index = trimmed.lastIndexOf('/')
    return if (index <= 0) ROOT_PATH else trimmed.substring(0, index)
}

private fun normalizePath(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ROOT_PATH
    val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    val collapsed = absolute.replace(SLASHES, "/")
    val stripped = collapsed.trimEnd('/')
    return if (stripped.isEmpty()) ROOT_PATH else stripped
}
