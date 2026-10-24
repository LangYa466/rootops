package dev.rootops.ui.screens.ssh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.SshAuth
import dev.rootops.core.api.SshProfile
import dev.rootops.core.api.SshSessionInfo
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.launch

private const val MAX_HISTORY = 20
private const val MAX_OUTPUT_CHARS = 120_000

private val QUICK_COMMANDS = listOf(
    "uname -a",
    "uptime",
    "df -h",
    "free -m",
    "docker ps",
    "systemctl status",
)

private val CardShape = RoundedCornerShape(10.dp)

/**
 * SSH profile manager and remote command console.
 *
 * Profiles live in the shared [SshProfileStore] file so the SFTP screen can
 * connect with the same credentials.
 */
@Composable
fun SshScreen(container: AppContainer) {
    val context = LocalContext.current
    val store = remember(context) { SshProfileStore(context) }
    val scope = rememberCoroutineScope()

    val sessions by container.ssh.sessions.collectAsStateWithLifecycle()
    val confirmDelete = container.settings.settings.collectAsStateWithLifecycle().value.confirmDelete

    var profiles by remember { mutableStateOf<List<SshProfile>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectingId by remember { mutableStateOf<Long?>(null) }
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorProfile by remember { mutableStateOf<SshProfile?>(null) }
    var pendingDelete by remember { mutableStateOf<SshProfile?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        profiles = store.list()
    }

    // Prefer the explicitly selected session; fall back to any live connection so a
    // connect() that lands before the sessions flow refreshes still opens the console.
    val activeSession: SshSessionInfo? =
        sessions.firstOrNull { it.id == activeSessionId && it.connected }
            ?: sessions.firstOrNull { it.connected }

    LaunchedEffect(activeSession?.id) {
        output = ""
        command = ""
    }

    val connect: (SshProfile) -> Unit = { profile ->
        if (connectingId != null) {
            Unit
        } else {
            error = null
            connectingId = profile.id
            scope.launch {
                try {
                    val info = container.ssh.connect(profile)
                    if (info.connected) {
                        activeSessionId = info.id
                    } else {
                        error = info.lastError
                            ?: "Could not connect to ${profile.name.ifBlank { profile.host }}."
                    }
                } catch (t: Throwable) {
                    error = t.message ?: "Could not connect to ${profile.host}."
                } finally {
                    connectingId = null
                }
            }
        }
    }

    val disconnect: (Long) -> Unit = { id ->
        scope.launch {
            runCatching { container.ssh.disconnect(id) }
                .onFailure { error = it.message ?: "Disconnect failed." }
            if (activeSessionId == id) activeSessionId = null
        }
    }

    val runCommand: (String) -> Unit = { raw ->
        val cmd = raw.trim()
        val sessionId = activeSession?.id
        when {
            cmd.isEmpty() -> Unit
            sessionId == null -> error = "Connect to a profile before running commands."
            running -> Unit
            else -> {
                history.remove(cmd)
                history.add(0, cmd)
                while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
                command = ""
                output = appendOutput(output, "$ $cmd")
                error = null
                running = true
                scope.launch {
                    try {
                        container.ssh.exec(sessionId, cmd).collect { chunk ->
                            val text = chunk.trimEnd('\n')
                            if (text.isNotEmpty()) output = appendOutput(output, text)
                        }
                    } catch (t: Throwable) {
                        output = appendOutput(output, "! ${t.message ?: "command failed"}")
                    } finally {
                        running = false
                    }
                }
            }
        }
    }

    AppScaffold(
        title = "SSH",
        subtitle = "${profiles.size} profiles · ${sessions.count { it.connected }} connected",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editorProfile = null
                    editorOpen = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New profile") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (error != null) {
                ErrorBanner(message = error.orEmpty())
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                item(key = "profiles-header") {
                    SectionHeader("Profiles")
                }
                if (profiles.isEmpty()) {
                    item(key = "profiles-empty") {
                        EmptyState(
                            icon = Icons.Filled.Terminal,
                            title = "No SSH profiles",
                            message = "Add a profile to connect to a remote host and run commands.",
                            actionLabel = "New profile",
                            onAction = {
                                editorProfile = null
                                editorOpen = true
                            },
                        )
                    }
                } else {
                    items(profiles, key = { "profile-${it.id}" }) { profile ->
                        ProfileRow(
                            profile = profile,
                            session = sessions.firstOrNull {
                                it.host == profile.host && it.profileName == profile.name
                            },
                            busy = connectingId == profile.id,
                            onConnect = { connect(profile) },
                            onEdit = {
                                editorProfile = profile
                                editorOpen = true
                            },
                        )
                    }
                }

                if (sessions.isNotEmpty()) {
                    item(key = "sessions-header") {
                        SectionHeader("Sessions")
                    }
                    items(sessions, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            session = session,
                            active = session.id == activeSession?.id,
                            onSelect = {
                                if (session.connected) {
                                    activeSessionId = session.id
                                }
                            },
                            onDisconnect = { disconnect(session.id) },
                        )
                    }
                }

                item(key = "sftp-hint") {
                    Text(
                        text = "The SFTP screen reads these same profiles — " +
                            "saving one here makes it available for file transfers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            ConsolePanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (activeSession != null) 1.25f else 0.5f)
                    // Keep the extended FAB clear of the console surface.
                    .padding(bottom = 72.dp),
                session = activeSession,
                command = command,
                onCommandChange = { command = it },
                running = running,
                output = output,
                history = history,
                onRun = runCommand,
                onClear = { output = "" },
            )
        }
    }

    if (editorOpen) {
        val deleteAction: (() -> Unit)? = editorProfile?.takeIf { it.id > 0L }?.let { existing ->
            {
                editorOpen = false
                editorProfile = null
                if (confirmDelete) {
                    pendingDelete = existing
                } else {
                    store.delete(existing.id)
                    profiles = store.list()
                }
            }
        }
        ProfileEditorDialog(
            initial = editorProfile,
            onDismiss = {
                editorOpen = false
                editorProfile = null
            },
            onSave = { profile ->
                store.save(profile)
                profiles = store.list()
                editorOpen = false
                editorProfile = null
            },
            onDelete = deleteAction,
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        ConfirmDialog(
            title = "Delete profile",
            message = "Remove \"${deleteTarget.name.ifBlank { deleteTarget.host }}\" from saved SSH profiles?",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                store.delete(deleteTarget.id)
                profiles = store.list()
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: SshProfile,
    session: SshSessionInfo?,
    busy: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
) {
    val connected = session?.connected == true
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val lastError = session?.lastError
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name.ifBlank { "Unnamed" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    TagChip(
                        text = if (profile.auth == SshAuth.KEY) "key" else "password",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(3.dp))
                MonoText(
                    text = "${profile.user}@${profile.host}:${profile.port}",
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (connected) {
                    Spacer(Modifier.height(6.dp))
                    TagChip(
                        text = "connected",
                        color = MaterialTheme.colorScheme.primary,
                        selected = true,
                    )
                } else if (lastError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onConnect, enabled = !connected) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = "Connect",
                        tint = if (connected) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SshSessionInfo,
    active: Boolean,
    onSelect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(enabled = session.connected, onClick = onSelect),
        shape = CardShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val lastError = session.lastError
                Text(
                    text = session.profileName.ifBlank { "Session ${session.id}" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                MonoText(
                    text = session.host,
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!session.connected && lastError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TagChip(
                text = if (session.connected) "connected" else "closed",
                color = if (session.connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                selected = active,
            )
            IconButton(onClick = onDisconnect, enabled = session.connected) {
                Icon(
                    imageVector = Icons.Filled.LinkOff,
                    contentDescription = "Disconnect",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConsolePanel(
    modifier: Modifier,
    session: SshSessionInfo?,
    command: String,
    onCommandChange: (String) -> Unit,
    running: Boolean,
    output: String,
    history: List<String>,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(
            text = "Console",
            trailing = {
                if (session != null) {
                    TagChip(
                        text = session.profileName.ifBlank { session.host },
                        color = MaterialTheme.colorScheme.primary,
                        selected = true,
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            },
        )

        if (session == null) {
            Text(
                text = "Connect a profile to open a remote command console.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_COMMANDS.forEach { quick ->
                TagChip(text = quick, onClick = { onCommandChange(quick) })
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RECENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                history.forEach { past ->
                    TagChip(
                        text = past,
                        color = MaterialTheme.colorScheme.secondary,
                        onClick = { onCommandChange(past) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Command") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { onRun(command) }),
                enabled = !running,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onRun(command) },
                enabled = command.isNotBlank() && !running,
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Run")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        val outputScroll = rememberScrollState()
        LaunchedEffect(output) {
            snapshotFlow { outputScroll.maxValue }.collect { max ->
                if (max > 0) outputScroll.scrollTo(max)
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .weight(1f),
            shape = CardShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(outputScroll)
                    .padding(10.dp),
            ) {
                MonoText(
                    text = output.ifEmpty { "No output yet." },
                    size = 11.sp,
                    color = if (output.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: SshProfile?,
    onDismiss: () -> Unit,
    onSave: (SshProfile) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) {
        mutableStateOf((initial?.port ?: SshProfileStore.DEFAULT_PORT).toString())
    }
    var user by remember(initial) { mutableStateOf(initial?.user.orEmpty()) }
    var auth by remember(initial) { mutableStateOf(initial?.auth ?: SshAuth.PASSWORD) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var keyPath by remember(initial) { mutableStateOf(initial?.keyPath.orEmpty()) }
    var passphrase by remember(initial) { mutableStateOf(initial?.passphrase.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null || initial.id <= 0L) "New SSH profile" else "Edit SSH profile",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Host") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        modifier = Modifier.weight(1.8f),
                        singleLine = true,
                        label = { Text("User") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AUTH",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    FilterChip(
                        selected = auth == SshAuth.PASSWORD,
                        onClick = { auth = SshAuth.PASSWORD },
                        label = { Text("Password") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = auth == SshAuth.KEY,
                        onClick = { auth = SshAuth.KEY },
                        label = { Text("Key") },
                    )
                }
                if (auth == SshAuth.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = if (reveal) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    imageVector = if (reveal) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (reveal) "Hide password" else "Show password",
                                )
                            }
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = keyPath,
                        onValueChange = { keyPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Key path") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Passphrase (optional)") },
                        visualTransformation = if (reveal) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    imageVector = if (reveal) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (reveal) "Hide passphrase" else "Show passphrase",
                                )
                            }
                        },
                    )
                }
                if (validation != null) {
                    Text(
                        text = validation.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val portValue = port.toIntOrNull() ?: SshProfileStore.DEFAULT_PORT
                    val trimmedName = name.trim()
                    val trimmedHost = host.trim()
                    val trimmedUser = user.trim()
                    val trimmedKeyPath = keyPath.trim()
                    val message = when {
                        trimmedName.isEmpty() -> "Name is required."
                        trimmedHost.isEmpty() -> "Host is required."
                        trimmedUser.isEmpty() -> "User is required."
                        portValue !in 1..65535 -> "Port must be between 1 and 65535."
                        auth == SshAuth.KEY && trimmedKeyPath.isEmpty() -> "Key path is required."
                        else -> null
                    }
                    validation = message
                    if (message == null) {
                        onSave(
                            SshProfile(
                                id = initial?.id ?: 0L,
                                name = trimmedName,
                                host = trimmedHost,
                                port = portValue,
                                user = trimmedUser,
                                auth = auth,
                                password = if (auth == SshAuth.PASSWORD) {
                                    password.ifBlank { null }
                                } else {
                                    null
                                },
                                keyPath = if (auth == SshAuth.KEY) {
                                    trimmedKeyPath.ifBlank { null }
                                } else {
                                    null
                                },
                                passphrase = if (auth == SshAuth.KEY) {
                                    passphrase.ifBlank { null }
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun appendOutput(current: String, chunk: String): String {
    if (chunk.isEmpty()) return current
    val next = if (current.isEmpty()) chunk else current + "\n" + chunk
    return if (next.length <= MAX_OUTPUT_CHARS) next else next.takeLast(MAX_OUTPUT_CHARS)
}
