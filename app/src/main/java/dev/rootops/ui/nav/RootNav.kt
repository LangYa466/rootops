package dev.rootops.ui.nav

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.rootops.AppContainer
import dev.rootops.core.api.ThemeMode
import dev.rootops.ui.screens.analyzer.AnalyzerScreen
import dev.rootops.ui.screens.editor.EditorScreen
import dev.rootops.ui.screens.files.FilesScreen
import dev.rootops.ui.screens.files.PropertiesScreen
import dev.rootops.ui.screens.home.HomeScreen
import dev.rootops.ui.screens.home.ToolsHubScreen
import dev.rootops.ui.screens.logcat.LogcatScreen
import dev.rootops.ui.screens.mounts.MountsScreen
import dev.rootops.ui.screens.network.NetworkScreen
import dev.rootops.ui.screens.packages.PackagesScreen
import dev.rootops.ui.screens.processes.ProcessScreen
import dev.rootops.ui.screens.schedule.ScheduleScreen
import dev.rootops.ui.screens.scripts.ScriptsScreen
import dev.rootops.ui.screens.settings.SettingsScreen
import dev.rootops.ui.screens.sftp.SftpScreen
import dev.rootops.ui.screens.ssh.SshScreen
import dev.rootops.ui.screens.terminal.TerminalScreen
import dev.rootops.ui.theme.RootOpsTheme

private object Paths {
    const val HOME = "home"
    const val FILES = "files"
    const val TERMINAL = "terminal"
    const val PROCESSES = "processes"
    const val LOGCAT = "logcat"
    const val NETWORK = "network"
    const val SCRIPTS = "scripts"
    const val SCHEDULE = "schedule"
    const val MOUNTS = "mounts"
    const val PACKAGES = "packages"
    const val SSH = "ssh"
    const val SFTP = "sftp"
    const val SETTINGS = "settings"
    const val ANALYZER = "analyzer"
    const val TOOLS = "tools"
    const val EDITOR = "editor?path={path}"
    const val PROPERTIES = "properties?path={path}"
}

private class Tab(val label: String, val icon: ImageVector, val path: String)

private val TABS = listOf(
    Tab("Home", Icons.Filled.Home, Paths.HOME),
    Tab("Files", Icons.Filled.Folder, Paths.FILES),
    Tab("Terminal", Icons.Filled.Terminal, Paths.TERMINAL),
    Tab("Tools", Icons.Filled.Build, Paths.TOOLS),
)

/** Application root: theme, navigator, bottom bar and the single NavHost. */
@Composable
fun RootApp(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    RootOpsTheme(darkTheme = darkTheme) {
        val navController = rememberNavController()
        CompositionLocalProvider(
            LocalNavigator provides { route -> navController.go(route) },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { RootBottomBar(navController) },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = Paths.HOME,
                    modifier = Modifier.padding(padding),
                ) {
                    composable(Paths.HOME) { HomeScreen(container) }
                    composable(Paths.FILES) { FilesScreen(container) }
                    composable(Paths.TERMINAL) { TerminalScreen(container) }
                    composable(Paths.PROCESSES) { ProcessScreen(container) }
                    composable(Paths.LOGCAT) { LogcatScreen(container) }
                    composable(Paths.NETWORK) { NetworkScreen(container) }
                    composable(Paths.SCRIPTS) { ScriptsScreen(container) }
                    composable(Paths.SCHEDULE) { ScheduleScreen(container) }
                    composable(Paths.MOUNTS) { MountsScreen(container) }
                    composable(Paths.PACKAGES) { PackagesScreen(container) }
                    composable(Paths.SSH) { SshScreen(container) }
                    composable(Paths.SFTP) { SftpScreen(container) }
                    composable(Paths.SETTINGS) { SettingsScreen(container) }
                    composable(Paths.ANALYZER) { AnalyzerScreen(container) }
                    composable(Paths.TOOLS) { ToolsHubScreen(container) }
                    composable(
                        route = Paths.EDITOR,
                        arguments = listOf(
                            navArgument("path") {
                                type = NavType.StringType
                                defaultValue = null
                                nullable = true
                            },
                        ),
                    ) { entry ->
                        EditorScreen(
                            container = container,
                            initialPath = entry.arguments?.getString("path"),
                        )
                    }
                    composable(
                        route = Paths.PROPERTIES,
                        arguments = listOf(
                            navArgument("path") {
                                type = NavType.StringType
                                defaultValue = null
                                nullable = true
                            },
                        ),
                    ) { entry ->
                        PropertiesScreen(
                            container = container,
                            path = entry.arguments?.getString("path"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RootBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.path,
                onClick = {
                    if (currentRoute != tab.path) {
                        navController.navigate(tab.path) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun NavHostController.go(route: Route) {
    val target = when (route) {
        Route.Home -> Paths.HOME
        Route.Files -> Paths.FILES
        Route.Terminal -> Paths.TERMINAL
        Route.Processes -> Paths.PROCESSES
        Route.Logcat -> Paths.LOGCAT
        Route.Network -> Paths.NETWORK
        Route.Scripts -> Paths.SCRIPTS
        Route.Schedule -> Paths.SCHEDULE
        Route.Mounts -> Paths.MOUNTS
        Route.Packages -> Paths.PACKAGES
        Route.Ssh -> Paths.SSH
        Route.Sftp -> Paths.SFTP
        Route.Settings -> Paths.SETTINGS
        Route.Analyzer -> Paths.ANALYZER
        Route.Tools -> Paths.TOOLS
        is Route.Editor -> "editor?path=" + Uri.encode(route.path)
        is Route.FileProperties -> "properties?path=" + Uri.encode(route.path)
    }
    navigate(target) { launchSingleTop = true }
}
