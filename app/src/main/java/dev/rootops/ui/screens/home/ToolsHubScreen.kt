package dev.rootops.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rootops.AppContainer
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.nav.LocalNavigator
import dev.rootops.ui.nav.Route

private data class ToolEntry(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: Route,
)

private val TOOLS = listOf(
    ToolEntry(
        title = "Processes",
        description = "Inspect CPU and memory per process",
        icon = Icons.Outlined.Memory,
        route = Route.Processes,
    ),
    ToolEntry(
        title = "Logcat",
        description = "Live system log stream",
        icon = Icons.Outlined.Article,
        route = Route.Logcat,
    ),
    ToolEntry(
        title = "Network",
        description = "Interfaces, ports and probes",
        icon = Icons.Outlined.Wifi,
        route = Route.Network,
    ),
    ToolEntry(
        title = "Scripts",
        description = "Saved shell scripts with one-tap runs",
        icon = Icons.Outlined.Code,
        route = Route.Scripts,
    ),
    ToolEntry(
        title = "Schedule",
        description = "Recurring script execution",
        icon = Icons.Outlined.Schedule,
        route = Route.Schedule,
    ),
    ToolEntry(
        title = "Mounts",
        description = "Mount points and remount control",
        icon = Icons.Outlined.Storage,
        route = Route.Mounts,
    ),
    ToolEntry(
        title = "Packages",
        description = "Installed apps, enable and uninstall",
        icon = Icons.Outlined.Apps,
        route = Route.Packages,
    ),
    ToolEntry(
        title = "SSH",
        description = "Remote shell sessions",
        icon = Icons.Outlined.Cloud,
        route = Route.Ssh,
    ),
    ToolEntry(
        title = "SFTP",
        description = "File transfers over SSH",
        icon = Icons.Outlined.CloudUpload,
        route = Route.Sftp,
    ),
    ToolEntry(
        title = "Analyzer",
        description = "Storage and disk usage breakdown",
        icon = Icons.Outlined.Analytics,
        route = Route.Analyzer,
    ),
    ToolEntry(
        title = "Settings",
        description = "Preferences, theme and shell defaults",
        icon = Icons.Outlined.Settings,
        route = Route.Settings,
    ),
)

/** Grid of every operator tool; each card navigates straight to its screen. */
@Composable
fun ToolsHubScreen(container: AppContainer) {
    val navigate = LocalNavigator.current

    AppScaffold(
        title = "DevOps tools",
        subtitle = "System, network and remote operations",
        onBack = { navigate(Route.Home) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(TOOLS, key = { it.title }) { tool ->
                ToolCard(tool = tool, onClick = { navigate(tool.route) })
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = tool.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
