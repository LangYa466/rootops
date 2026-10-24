package dev.rootops.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rootops.AppContainer
import dev.rootops.core.api.AppSettings
import dev.rootops.core.api.ThemeMode
import dev.rootops.ui.components.AppScaffold
import dev.rootops.ui.components.ConfirmDialog
import dev.rootops.ui.components.ErrorBanner
import dev.rootops.ui.components.KeyValueRow
import dev.rootops.ui.components.SectionHeader
import dev.rootops.ui.components.TagChip
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Accent presets offered in the appearance section, as packed ARGB values. */
private val AccentPresets: List<Long> = listOf(
    0xFF7BD88F, // green (default)
    0xFF6FB3D2, // blue
    0xFFE5C07B, // amber
    0xFFE06C75, // red
    0xFFC792EA, // violet
    0xFF56B6C2, // cyan
)

private const val MIN_FONT_SIZE = 9
private const val MAX_FONT_SIZE = 22
private const val MIN_LOGCAT_LIMIT = 500
private const val MAX_LOGCAT_LIMIT = 10_000
private const val LOGCAT_STEP = 500
private const val FALLBACK_VERSION = "1.0"

/**
 * Global preferences: appearance, terminal, file browser, logcat, root status,
 * all-files storage access and about/reset.
 *
 * Every control writes straight through [AppContainer.settings] so a change is
 * persisted the moment it is made; there is no save button and no local draft of
 * the whole settings object. Text fields keep a per-field editing buffer so the
 * cursor does not jump while typing.
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rootAvailable by container.shell.rootAvailable.collectAsStateWithLifecycle()
    var checkingRoot by remember { mutableStateOf(false) }
    var rootError by remember { mutableStateOf<String?>(null) }

    var showResetDialog by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }

    val supportsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    var storageError by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, supportsAllFiles) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesGranted = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppScaffold(title = "Settings") { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ------------------------------------------------------ appearance
            SectionHeader("Appearance")
            ThemeModeRow(
                current = settings.themeMode,
                onSelect = { mode -> container.settings.update { it.copy(themeMode = mode) } },
            )
            AccentRow(
                current = settings.accent,
                onSelect = { accent -> container.settings.update { it.copy(accent = accent) } },
            )

            // -------------------------------------------------------- terminal
            SectionHeader("Terminal")
            SettingSlider(
                title = "Font size",
                valueLabel = "${settings.terminalFontSize} sp",
                value = settings.terminalFontSize.toFloat().coerceIn(
                    MIN_FONT_SIZE.toFloat(),
                    MAX_FONT_SIZE.toFloat(),
                ),
                range = MIN_FONT_SIZE.toFloat()..MAX_FONT_SIZE.toFloat(),
                steps = MAX_FONT_SIZE - MIN_FONT_SIZE - 1,
                onValueChange = { raw ->
                    val size = raw.roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                    if (size != settings.terminalFontSize) {
                        container.settings.update { it.copy(terminalFontSize = size) }
                    }
                },
            )
            SettingTextField(
                label = "Default shell",
                value = settings.terminalShell,
                supporting = "Absolute path to the shell binary used for new sessions.",
                onCommit = { text -> container.settings.update { it.copy(terminalShell = text) } },
            )
            SettingSwitch(
                title = "Use root by default",
                subtitle = "Run terminal and file commands through su when available.",
                checked = settings.useRoot,
                onCheckedChange = { enabled -> container.settings.update { it.copy(useRoot = enabled) } },
            )

            // ----------------------------------------------------------- files
            SectionHeader("Files")
            SettingSwitch(
                title = "Show hidden files",
                subtitle = "Include dotfiles and other entries starting with a dot.",
                checked = settings.showHidden,
                onCheckedChange = { enabled -> container.settings.update { it.copy(showHidden = enabled) } },
            )
            SettingTextField(
                label = "Default path",
                value = settings.defaultPath,
                supporting = "Directory opened by the file browser on launch.",
                onCommit = { text -> container.settings.update { it.copy(defaultPath = text) } },
            )
            SettingSwitch(
                title = "Confirm before delete",
                subtitle = "Ask for confirmation before removing files or directories.",
                checked = settings.confirmDelete,
                onCheckedChange = { enabled -> container.settings.update { it.copy(confirmDelete = enabled) } },
            )

            // ------------------------------------------------------------ logs
            SectionHeader("Logs")
            SettingSlider(
                title = "Logcat buffer",
                valueLabel = "${settings.logcatLimit} lines",
                value = settings.logcatLimit.coerceIn(MIN_LOGCAT_LIMIT, MAX_LOGCAT_LIMIT).toFloat(),
                range = MIN_LOGCAT_LIMIT.toFloat()..MAX_LOGCAT_LIMIT.toFloat(),
                steps = (MAX_LOGCAT_LIMIT - MIN_LOGCAT_LIMIT) / LOGCAT_STEP - 1,
                onValueChange = { raw ->
                    val stepped = ((raw / LOGCAT_STEP).roundToInt() * LOGCAT_STEP)
                        .coerceIn(MIN_LOGCAT_LIMIT, MAX_LOGCAT_LIMIT)
                    if (stepped != settings.logcatLimit) {
                        container.settings.update { it.copy(logcatLimit = stepped) }
                    }
                },
            )

            // ------------------------------------------------------------ root
            SectionHeader("Root")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root access",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (rootAvailable) "su responded to a probe command." else "No usable su shell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                TagChip(
                    text = if (rootAvailable) "AVAILABLE" else "UNAVAILABLE",
                    color = if (rootAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    selected = true,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (checkingRoot) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Probing su…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (!checkingRoot) {
                            checkingRoot = true
                            rootError = null
                            scope.launch {
                                try {
                                    container.shell.refreshRoot()
                                } catch (t: Throwable) {
                                    rootError = t.message ?: "Root check failed."
                                } finally {
                                    checkingRoot = false
                                }
                            }
                        }
                    },
                    enabled = !checkingRoot,
                ) {
                    Text("Re-check")
                }
            }
            if (rootError != null) {
                ErrorBanner(message = rootError ?: "Root check failed.")
            }
            if (!rootAvailable) {
                Text(
                    text = "Root operations are unavailable: this device has no su binary, or the " +
                        "superuser request was denied. Commands that need root will fail; everything " +
                        "else keeps working with normal app permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (settings.useRoot) {
                    Text(
                        text = "Root mode is currently enabled in Terminal settings, so those commands " +
                            "will fail until root is granted or the switch is turned off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }

            // --------------------------------------------------------- storage
            SectionHeader("Storage access")
            if (!supportsAllFiles) {
                Text(
                    text = "All-files access (MANAGE_EXTERNAL_STORAGE) only exists on Android 11 and " +
                        "newer. On this device the app uses its standard external storage access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            } else {
                KeyValueRow(
                    label = "All-files access",
                    value = if (allFilesGranted) "granted" else "not granted",
                    mono = false,
                )
                if (!allFilesGranted) {
                    Text(
                        text = "Granting this lets root operations read and write /sdcard outside the " +
                            "app sandbox. Android opens a system screen where the permission is enabled manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Button(
                        onClick = {
                            storageError = null
                            val opened = openAllFilesSettings(context)
                            if (!opened) {
                                storageError = "No system settings screen could be opened on this device."
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Grant all-files access")
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TagChip(
                            text = "MANAGE_EXTERNAL_STORAGE",
                            color = MaterialTheme.colorScheme.primary,
                            selected = true,
                        )
                    }
                }
                if (storageError != null) {
                    ErrorBanner(message = storageError ?: "Could not open settings.")
                }
            }

            // ----------------------------------------------------------- about
            SectionHeader("About")
            KeyValueRow(label = "Application", value = appLabel(context), mono = false)
            KeyValueRow(label = "Version", value = appVersion(context))
            KeyValueRow(label = "Device", value = deviceModel())
            KeyValueRow(
                label = "Android",
                value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("Reset all settings", color = MaterialTheme.colorScheme.error)
            }
            if (resetError != null) {
                ErrorBanner(message = resetError ?: "Could not reset settings.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showResetDialog) {
        ConfirmDialog(
            title = "Reset settings",
            message = "Every preference returns to its default value. Stored files, scripts and " +
                "connections are not affected.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = {
                showResetDialog = false
                resetError = null
                try {
                    container.settings.update { AppSettings() }
                } catch (t: Throwable) {
                    resetError = t.message ?: "Could not reset settings."
                }
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

// ------------------------------------------------------------------ sections

@Composable
private fun ThemeModeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeMode.entries.forEach { mode ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TagChip(
                    text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun AccentRow(current: Long, onSelect: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentPresets.forEach { preset ->
            val swatch = Color(preset)
            val selected = preset == current
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(preset) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected accent",
                        tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0),
        )
    }
}

/**
 * Text field whose persisted value lives in [AppContainer.settings].
 *
 * The field keeps a local buffer so every keystroke is visible immediately, and
 * commits each non-blank edit; blank input is never persisted (the store would
 * fall back to the default on the next launch anyway, which would silently
 * rewrite what the user sees).
 */
@Composable
private fun SettingTextField(
    label: String,
    value: String,
    supporting: String,
    onCommit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != text.trim()) text = value
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { updated ->
                text = updated
                val trimmed = updated.trim()
                if (trimmed.isNotEmpty()) onCommit(trimmed)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------- helpers

private fun hasAllFilesAccess(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
    return try {
        Environment.isExternalStorageManager()
    } catch (t: Throwable) {
        false
    }
}

/**
 * Opens the system screen that toggles all-files access, degrading through the
 * generic all-files screen and finally the app's detail page. Returns `false`
 * only when no settings activity could be started at all.
 */
private fun openAllFilesSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val packageUri = Uri.parse("package:${context.packageName}")
    val candidates = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    for (intent in candidates) {
        try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (t: Throwable) {
            // Try the next, less specific settings screen.
        }
    }
    return false
}

private fun appLabel(context: Context): String = try {
    context.applicationInfo.loadLabel(context.packageManager).toString()
} catch (t: Throwable) {
    "RootOps"
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?.takeIf { it.isNotBlank() }
        ?: FALLBACK_VERSION
} catch (t: Throwable) {
    FALLBACK_VERSION
}

private fun deviceModel(): String = try {
    "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Unknown device" }
} catch (t: Throwable) {
    "Unknown device"
}
