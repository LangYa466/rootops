package dev.rootops.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = ConsoleAccent,
    onPrimary = ConsoleBackground,
    primaryContainer = ConsoleBorder,
    onPrimaryContainer = ConsoleAccent,
    secondary = ConsoleSecondary,
    onSecondary = ConsoleBackground,
    secondaryContainer = ConsoleBorder,
    onSecondaryContainer = ConsoleSecondary,
    tertiary = ConsoleWarning,
    onTertiary = ConsoleBackground,
    tertiaryContainer = ConsoleBorder,
    onTertiaryContainer = ConsoleWarning,
    background = ConsoleBackground,
    onBackground = ConsoleOnSurface,
    surface = ConsoleSurface,
    onSurface = ConsoleOnSurface,
    surfaceVariant = ConsoleBorder,
    onSurfaceVariant = ConsoleMuted,
    surfaceContainer = ConsoleSurface,
    surfaceContainerHigh = ConsoleBorder,
    surfaceContainerHighest = ConsoleBorder,
    outline = ConsoleBorder,
    outlineVariant = ConsoleBorder,
    error = ConsoleError,
    onError = ConsoleBackground,
    errorContainer = ConsoleBorder,
    onErrorContainer = ConsoleError,
    scrim = ConsoleBackground,
    inverseSurface = ConsoleOnSurface,
    inverseOnSurface = ConsoleBackground,
    inversePrimary = ConsoleAccent,
)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightSurface,
    primaryContainer = LightBorder,
    onPrimaryContainer = LightAccent,
    secondary = LightSecondary,
    onSecondary = LightSurface,
    secondaryContainer = LightBorder,
    onSecondaryContainer = LightSecondary,
    tertiary = LightWarning,
    onTertiary = LightSurface,
    tertiaryContainer = LightBorder,
    onTertiaryContainer = LightWarning,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightBorder,
    onSurfaceVariant = LightMuted,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightBorder,
    surfaceContainerHighest = LightBorder,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = LightError,
    onError = LightSurface,
    errorContainer = LightBorder,
    onErrorContainer = LightError,
    scrim = LightOnSurface,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightSurface,
    inversePrimary = LightAccent,
)

/**
 * Applies the RootOps "operator console" look.
 * [darkTheme] defaults to true; the operator console is designed dark-first.
 */
@Composable
fun RootOpsTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RootOpsTypography,
        content = content,
    )
}
