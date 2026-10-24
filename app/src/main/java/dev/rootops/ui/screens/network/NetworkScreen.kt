package dev.rootops.ui.screens.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rootops.core.api.NetInterface
import dev.rootops.core.api.PortEntry
import dev.rootops.core.util.formatBytes
import dev.rootops.AppContainer
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.EmptyState
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.KeyValueRow
import dev.rootops.ui.components.LoadingRow
import dev.rootops.ui.components.MonoText
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.StatTile
import dev.rootops.ui.components.TagChip
import dev.rootops.ui.theme.MonoFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(10.dp)
private const val MAX_CONSOLE_LINES = 5_000

private enum class NetworkTab(val label: String) {
    INTERFACES("Interfaces"),
    PORTS("Ports"),
    TOOLS("Tools"),
}

@Composable
fun NetworkScreen(container: AppContainer) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    AppScaffold(
        title = "Network",
        subtitle = "Interfaces · ports · diagnostics",
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                NetworkTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label) },
                    )
                }
            }
            when (NetworkTab.entries.getOrElse(selectedTab) { NetworkTab.INTERFACES }) {
                NetworkTab.INTERFACES -> InterfacesTab(container)
                NetworkTab.PORTS -> PortsTab(container)
                NetworkTab.TOOLS -> ToolsTab(container)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Interfaces                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun InterfacesTab(container: AppContainer) {
    var interfaces by remember { mutableStateOf<List<NetInterface>>(emptyList()) }
    var publicIp by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        loadIo { container.network.interfaces() }
            .onSuccess { interfaces = it }
            .onFailure { error = it.readable() }
        loadIo { container.network.publicIp() }
            .onSuccess { publicIp = it }
            .onFailure { publicIp = null }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader(
            text = "Public IP",
            trailing = {
                IconButton(onClick = { reloadKey++ }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(8.dp))
            MonoText(
                text = publicIp ?: if (loading) "Resolving…" else "Unavailable",
                modifier = Modifier.weight(1f),
                size = 13.sp,
                color = if (publicIp != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (error != null) {
            ErrorBanner(message = error!!, onRetry = { reloadKey++ })
        }

        SectionHeader(
            text = "Interfaces",
            trailing = {
                Text(
                    text = interfaces.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        when {
            loading && interfaces.isEmpty() -> LoadingRow("Reading interfaces…")
            interfaces.isEmpty() && error == null -> EmptyState(
                icon = Icons.Filled.Wifi,
                title = "No interfaces",
                message = "The device did not report any network interface.",
                actionLabel = "Refresh",
                onAction = { reloadKey++ },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(interfaces, key = { it.name }) { iface ->
                    InterfaceCard(iface)
                }
            }
        }
    }
}

@Composable
private fun InterfaceCard(iface: NetInterface) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = iface.name,
                    modifier = Modifier.weight(1f),
                    size = 13.sp,
                )
                TagChip(
                    text = if (iface.up) "UP" else "DOWN",
                    color = if (iface.up) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    selected = true,
                )
            }
            if (iface.addresses.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                MonoText(
                    text = "no addresses",
                    size = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                iface.addresses.forEach { address ->
                    Spacer(Modifier.height(2.dp))
                    MonoText(
                        text = address,
                        size = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "RX",
                    value = formatBytes(iface.rxBytes),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "TX",
                    value = formatBytes(iface.txBytes),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Ports                                                               */
/* ------------------------------------------------------------------ */

@Composable
private fun PortsTab(container: AppContainer) {
    var ports by remember { mutableStateOf<List<PortEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var onlyListening by rememberSaveable { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        loadIo { container.network.ports() }
            .onSuccess { ports = it }
            .onFailure { error = it.readable() }
        loading = false
    }

    val query = filter.trim()
    val visible = remember(ports, query, onlyListening) {
        ports.filter { entry ->
            val matchesQuery = query.isEmpty() || entry.matches(query)
            val matchesListening = !onlyListening || entry.listening
            matchesQuery && matchesListening
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            label = { Text("Filter") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (filter.isNotEmpty()) {
                    IconButton(onClick = { filter = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear filter",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = onlyListening, onCheckedChange = { onlyListening = it })
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Only listening",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reloadKey++ }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (error != null) {
            ErrorBanner(message = error!!, onRetry = { reloadKey++ })
        }

        SectionHeader(
            text = "Sockets",
            trailing = {
                Text(
                    text = "${visible.size}/${ports.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        when {
            loading && ports.isEmpty() -> LoadingRow("Reading sockets…")
            visible.isEmpty() && error == null -> EmptyState(
                icon = Icons.Filled.Search,
                title = if (ports.isEmpty()) "No open sockets" else "No matching sockets",
                message = if (ports.isEmpty()) {
                    "No ports were reported by the kernel."
                } else {
                    "Adjust the filter or turn off “Only listening”."
                },
                actionLabel = if (ports.isEmpty()) "Refresh" else "Clear filter",
                onAction = {
                    if (ports.isEmpty()) {
                        reloadKey++
                    } else {
                        filter = ""
                        onlyListening = false
                    }
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible) { entry -> PortRow(entry) }
            }
        }
    }
}

@Composable
private fun PortRow(entry: PortEntry) {
    val listening = entry.listening
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagChip(
                    text = entry.proto.uppercase(),
                    color = MaterialTheme.colorScheme.secondary,
                    selected = true,
                )
                Spacer(Modifier.width(8.dp))
                MonoText(
                    text = entry.local,
                    modifier = Modifier.weight(1f),
                    size = 12.sp,
                )
                Text(
                    text = entry.state.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFont,
                    color = if (listening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            MonoText(
                text = "→ ${entry.remote.ifBlank { "*" }}",
                size = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            KeyValueRow(label = "pid", value = entry.pid?.toString() ?: "—")
            KeyValueRow(label = "process", value = entry.process ?: "unknown")
        }
    }
}

private val PortEntry.listening: Boolean
    get() = state.contains("LISTEN", ignoreCase = true)

private fun PortEntry.matches(query: String): Boolean =
    proto.contains(query, ignoreCase = true) ||
        local.contains(query, ignoreCase = true) ||
        remote.contains(query, ignoreCase = true) ||
        state.contains(query, ignoreCase = true) ||
        (pid != null && pid.toString().contains(query)) ||
        process?.contains(query, ignoreCase = true) == true

/* ------------------------------------------------------------------ */
/* Tools                                                               */
/* ------------------------------------------------------------------ */

@Composable
private fun ToolsTab(container: AppContainer) {
    var input by rememberSaveable { mutableStateOf("") }
    val output = remember { mutableStateListOf<String>() }
    var activeTool by remember { mutableStateOf<String?>(null) }
    var toolError by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var runToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun start(label: String, call: suspend (String) -> Flow<String>) {
        val target = input.trim()
        if (target.isEmpty()) {
            toolError = "Enter a host or URL first."
            return
        }
        job?.cancel()
        val token = ++runToken
        output.clear()
        toolError = null
        activeTool = label
        job = scope.launch {
            try {
                call(target).collect { line ->
                    output.add(line)
                    while (output.size > MAX_CONSOLE_LINES) {
                        output.removeAt(0)
                    }
                }
                if (token == runToken) activeTool = null
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (token == runToken) {
                    toolError = t.readable()
                    activeTool = null
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        activeTool = null
    }

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            listState.scrollToItem(output.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                toolError = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Host or URL") },
            placeholder = { Text("example.com") },
            singleLine = true,
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { input = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear input",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { start("Ping") { host -> container.network.ping(host, count = 4) } },
                modifier = Modifier.weight(1f),
            ) { Text("Ping") }
            Button(
                onClick = { start("DNS") { host -> container.network.dnsLookup(host) } },
                modifier = Modifier.weight(1f),
            ) { Text("DNS") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { start("HTTP probe") { url -> container.network.httpProbe(url) } },
                modifier = Modifier.weight(1f),
            ) { Text("HTTP probe") }
            Button(
                onClick = { start("Traceroute") { host -> container.network.traceroute(host) } },
                modifier = Modifier.weight(1f),
            ) { Text("Traceroute") }
        }

        if (activeTool != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Running ${activeTool}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { cancel() }) { Text("Cancel") }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        if (toolError != null) {
            ErrorBanner(message = toolError!!)
        }

        SectionHeader(
            text = "Output",
            trailing = {
                TextButton(onClick = {
                    output.clear()
                    toolError = null
                }) { Text("Clear") }
            },
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = CardShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            if (output.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                ) {
                    MonoText(
                        text = "No output yet — run a tool above.",
                        size = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                ) {
                    itemsIndexed(output) { _, line ->
                        MonoText(
                            text = line,
                            size = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Runs an I/O-bound load off the main thread while letting cancellation propagate. */
private suspend fun <T> loadIo(block: suspend () -> T): Result<T> =
    try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
