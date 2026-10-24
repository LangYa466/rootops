package dev.rootops.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.rootops.core.api.AppSettings
import dev.rootops.core.api.SettingsStore
import dev.rootops.core.api.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed [SettingsStore].
 *
 * Every [AppSettings] field is stored under its own key. Values that cannot be
 * read back (wrong type, unknown enum name, out-of-range number) silently fall
 * back to the corresponding default from [AppSettings], so a corrupted or
 * partially written preference file can never prevent the app from starting.
 */
class SettingsStoreImpl(private val context: Context) : SettingsStore {

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val defaults = AppSettings()

    private val _settings = MutableStateFlow(defaults)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        _settings.value = try {
            load()
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load settings, using defaults", t)
            defaults
        }
    }

    @Synchronized
    override fun update(block: (AppSettings) -> AppSettings) {
        val next = try {
            block(_settings.value)
        } catch (t: Throwable) {
            Log.w(TAG, "settings update rejected", t)
            return
        }
        persist(next)
        _settings.value = next
    }

    /** Restores every preference to its default and clears the backing file. */
    @Synchronized
    fun reset() {
        try {
            prefs.edit().clear().commit()
        } catch (t: Throwable) {
            Log.w(TAG, "failed to clear settings", t)
        }
        _settings.value = defaults
    }

    /** Current snapshot without collecting the flow. */
    fun current(): AppSettings = _settings.value

    // ---------------------------------------------------------------- load

    private fun load(): AppSettings = AppSettings(
        themeMode = ThemeMode.entries.firstOrNull { it.name == readString(Keys.THEME_MODE) }
            ?: defaults.themeMode,
        accent = readLong(Keys.ACCENT, defaults.accent),
        terminalFontSize = readPositiveInt(Keys.TERMINAL_FONT_SIZE, defaults.terminalFontSize),
        terminalShell = readNonBlankString(Keys.TERMINAL_SHELL, defaults.terminalShell),
        useRoot = readBoolean(Keys.USE_ROOT, defaults.useRoot),
        showHidden = readBoolean(Keys.SHOW_HIDDEN, defaults.showHidden),
        defaultPath = readNonBlankString(Keys.DEFAULT_PATH, defaults.defaultPath),
        logcatLimit = readPositiveInt(Keys.LOGCAT_LIMIT, defaults.logcatLimit),
        confirmDelete = readBoolean(Keys.CONFIRM_DELETE, defaults.confirmDelete),
    )

    private fun readString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (t: Throwable) {
        null
    }

    private fun readNonBlankString(key: String, fallback: String): String {
        val value = readString(key)
        return if (value.isNullOrBlank()) fallback else value
    }

    private fun readLong(key: String, fallback: Long): Long = try {
        if (prefs.contains(key)) prefs.getLong(key, fallback) else fallback
    } catch (t: Throwable) {
        fallback
    }

    private fun readPositiveInt(key: String, fallback: Int): Int {
        val value = try {
            if (prefs.contains(key)) prefs.getInt(key, fallback) else fallback
        } catch (t: Throwable) {
            fallback
        }
        return if (value > 0) value else fallback
    }

    private fun readBoolean(key: String, fallback: Boolean): Boolean = try {
        if (prefs.contains(key)) prefs.getBoolean(key, fallback) else fallback
    } catch (t: Throwable) {
        fallback
    }

    // ------------------------------------------------------------- persist

    private fun persist(value: AppSettings) {
        try {
            prefs.edit()
                .putString(Keys.THEME_MODE, value.themeMode.name)
                .putLong(Keys.ACCENT, value.accent)
                .putInt(Keys.TERMINAL_FONT_SIZE, value.terminalFontSize)
                .putString(Keys.TERMINAL_SHELL, value.terminalShell)
                .putBoolean(Keys.USE_ROOT, value.useRoot)
                .putBoolean(Keys.SHOW_HIDDEN, value.showHidden)
                .putString(Keys.DEFAULT_PATH, value.defaultPath)
                .putInt(Keys.LOGCAT_LIMIT, value.logcatLimit)
                .putBoolean(Keys.CONFIRM_DELETE, value.confirmDelete)
                .commit()
        } catch (t: Throwable) {
            Log.w(TAG, "failed to persist settings", t)
        }
    }

    private object Keys {
        const val THEME_MODE = "themeMode"
        const val ACCENT = "accent"
        const val TERMINAL_FONT_SIZE = "terminalFontSize"
        const val TERMINAL_SHELL = "terminalShell"
        const val USE_ROOT = "useRoot"
        const val SHOW_HIDDEN = "showHidden"
        const val DEFAULT_PATH = "defaultPath"
        const val LOGCAT_LIMIT = "logcatLimit"
        const val CONFIRM_DELETE = "confirmDelete"
    }

    private companion object {
        const val TAG = "SettingsStore"
        const val PREFS_NAME = "rootops_settings"
    }
}
