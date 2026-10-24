package dev.rootops.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

sealed interface Route {
    data object Home : Route
    data object Files : Route
    data object Terminal : Route
    data object Processes : Route
    data object Logcat : Route
    data object Network : Route
    data object Scripts : Route
    data object Schedule : Route
    data object Mounts : Route
    data object Packages : Route
    data object Ssh : Route
    data object Sftp : Route
    data object Settings : Route
    data object Analyzer : Route
    data object Tools : Route
    data class Editor(val path: String) : Route
    data class FileProperties(val path: String) : Route
}

val LocalNavigator = staticCompositionLocalOf<(Route) -> Unit> { {} }
