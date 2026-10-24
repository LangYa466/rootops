package dev.rootops.core.pkg

import android.content.Context
import dev.rootops.core.api.PackageEntry
import dev.rootops.core.api.PackageRepository
import dev.rootops.core.api.ShellResult
import dev.rootops.core.api.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Installed-application inventory backed by the platform `pm` and `dumpsys` tooling.
 *
 * All commands are executed through [ShellRunner] with root enabled, so the same code path
 * works on rooted devices and falls back to the shell user elsewhere. No method throws:
 * every failure is reported as an empty list, `false` or `null`.
 *
 * [list] is intentionally cheap: it only runs the four `pm list packages` queries and reads
 * labels through `PackageManager`. Version metadata and the uid are resolved on demand by
 * [details] for the packages that are actually on screen.
 */
class PackageRepositoryImpl(
    private val context: Context,
    private val shell: ShellRunner,
) : PackageRepository {

    private val cacheMutex = Mutex()

    /** APK paths from the most recent `pm list packages -f` run, keyed by package name. */
    @Volatile
    private var apkPaths: Map<String, String> = emptyMap()

    @Volatile
    private var cachedListing: Listing? = null

    @Volatile
    private var cachedAt: Long = 0L

    // ---------------------------------------------------------------- PackageRepository

    override suspend fun list(includeSystem: Boolean): List<PackageEntry> =
        withContext(Dispatchers.IO) {
            try {
                val listing = loadListing(force = true)
                val entries = ArrayList<PackageEntry>(listing.apkPaths.size)
                for ((pkg, path) in listing.apkPaths) {
                    val system = isSystemPackage(pkg, path, listing)
                    if (system && !includeSystem) continue
                    entries += PackageEntry(
                        pkg = pkg,
                        label = applicationLabel(pkg),
                        versionName = null,
                        versionCode = 0L,
                        system = system,
                        enabled = pkg !in listing.disabled,
                        uid = 0,
                        apkPath = path,
                    )
                }
                entries.sortBy { it.pkg }
                entries
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                emptyList()
            }
        }

    override suspend fun setEnabled(pkg: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidName(pkg)) return@withContext false
            try {
                val command = if (enabled) {
                    "pm enable $pkg"
                } else {
                    "pm disable-user --user 0 $pkg"
                }
                val ok = succeeded(shell.run(command, root = true))
                if (ok) invalidateListing()
                ok
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                false
            }
        }

    override suspend fun clearData(pkg: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidName(pkg)) return@withContext false
            try {
                succeeded(shell.run("pm clear $pkg", root = true))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                false
            }
        }

    override suspend fun uninstall(pkg: String, keepData: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidName(pkg)) return@withContext false
            try {
                val command = if (keepData) "pm uninstall -k $pkg" else "pm uninstall $pkg"
                val ok = succeeded(shell.run(command, root = true))
                if (ok) invalidateListing()
                ok
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                false
            }
        }

    override suspend fun launch(pkg: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidName(pkg)) return@withContext false
            try {
                val monkey = shell.run(
                    "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
                    root = true,
                )
                if (launchSucceeded(monkey)) return@withContext true
                val start = shell.run(
                    "am start -a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER -p $pkg",
                    root = true,
                )
                launchSucceeded(start)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                false
            }
        }

    override suspend fun apkPath(pkg: String): String? =
        withContext(Dispatchers.IO) {
            if (!isValidName(pkg)) return@withContext null
            try {
                apkPaths[pkg] ?: loadListing(force = true).apkPaths[pkg]
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
        }

    // ------------------------------------------------------------------- Extended API

    /**
     * Resolves the full metadata of a single package.
     *
     * `dumpsys package <pkg>` supplies `versionName`, `versionCode`, `userId` and `codePath`;
     * the enabled flag comes from the cached `pm list packages -d` set. Returns `null` when
     * the name is invalid or the package is not installed.
     */
    suspend fun details(pkg: String): PackageEntry? = withContext(Dispatchers.IO) {
        if (!isValidName(pkg)) return@withContext null
        try {
            val listing = loadListing(force = false)
            val dump = shell.run("dumpsys package $pkg", root = true)
            val text = dump.out
            if (text.isBlank() && !dump.ok) return@withContext null
            if (text.contains("Unable to find package", ignoreCase = true)) return@withContext null

            val codePath = CODE_PATH.find(text)?.groupValues?.get(1)?.trimEnd('/')
            val versionName = VERSION_NAME.find(text)?.groupValues?.get(1)
            val versionCode = VERSION_CODE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uid = USER_ID.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val known = listing.apkPaths.containsKey(pkg) || codePath != null
            if (!known && versionName == null && versionCode == 0L && uid == 0) {
                return@withContext null
            }

            val apk = listing.apkPaths[pkg] ?: codePath?.let { "$it/base.apk" }
            val system = isSystemPackage(pkg, apk, listing)
            PackageEntry(
                pkg = pkg,
                label = applicationLabel(pkg),
                versionName = versionName,
                versionCode = versionCode,
                system = system,
                enabled = pkg !in listing.disabled,
                uid = uid,
                apkPath = apk,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ Listing cache

    /** Snapshot of the four `pm list packages` queries used to build entries. */
    private data class Listing(
        val apkPaths: Map<String, String>,
        val disabled: Set<String>,
        val system: Set<String>,
        val thirdParty: Set<String>,
    )

    private suspend fun loadListing(force: Boolean): Listing = cacheMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedListing
        if (!force && existing != null && now - cachedAt < LISTING_TTL_MS) {
            return existing
        }
        val fresh = runListing()
        cachedListing = fresh
        cachedAt = now
        apkPaths = fresh.apkPaths
        fresh
    }

    private fun invalidateListing() {
        cachedListing = null
        cachedAt = 0L
        apkPaths = emptyMap()
    }

    private suspend fun runListing(): Listing = Listing(
        // No `-u`: uninstalled packages whose data was retained still appear in `pm list
        // packages -f -u` with a stale APK path, and this is an inventory of installed apps.
        apkPaths = parseApkPaths(runShell("pm list packages -f")),
        // Disabled packages are installed, so `-d` needs no `-u` either.
        disabled = parseNames(runShell("pm list packages -d")),
        system = parseNames(runShell("pm list packages -s")),
        thirdParty = parseNames(runShell("pm list packages -3")),
    )

    private suspend fun runShell(command: String): String = try {
        shell.run(command, root = true).out
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        ""
    }

    // ------------------------------------------------------------------------ Parsing

    private fun parseApkPaths(output: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith(PACKAGE_PREFIX)) continue
            val body = line.substring(PACKAGE_PREFIX.length)
            // The path may itself contain '=' (base64 directory hashes), the package name never
            // does, so the last separator is the reliable one.
            val separator = body.lastIndexOf('=')
            if (separator <= 0 || separator == body.lastIndex) continue
            val path = body.substring(0, separator)
            val pkg = body.substring(separator + 1)
            if (path.isEmpty() || !isValidName(pkg)) continue
            val previous = result[pkg]
            if (previous == null || isBetterApk(path, previous)) {
                result[pkg] = path
            }
        }
        return result
    }

    private fun parseNames(output: String): Set<String> {
        val result = HashSet<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith(PACKAGE_PREFIX)) continue
            val name = line.substring(PACKAGE_PREFIX.length).trim()
            if (isValidName(name)) result.add(name)
        }
        return result
    }

    /** Prefers the base APK over split APKs of the same package. */
    private fun isBetterApk(candidate: String, existing: String): Boolean {
        val candidateIsBase = candidate.endsWith(BASE_APK_SUFFIX)
        val existingIsBase = existing.endsWith(BASE_APK_SUFFIX)
        return candidateIsBase && !existingIsBase
    }

    // ----------------------------------------------------------------------- Helpers

    private fun isSystemPackage(pkg: String, apkPath: String?, listing: Listing): Boolean = when {
        pkg in listing.thirdParty -> false
        pkg in listing.system -> true
        else -> isSystemPath(apkPath)
    }

    private fun isSystemPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return SYSTEM_PATH_PREFIXES.any { path.startsWith(it) }
    }

    @Suppress("DEPRECATION")
    private fun applicationLabel(pkg: String): String? = try {
        val manager = context.packageManager
        val info = manager.getApplicationInfo(pkg, 0)
        manager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    private fun succeeded(result: ShellResult): Boolean {
        if (result.code != 0) return false
        val text = result.out + '\n' + result.err
        return !(text.contains("Failure", ignoreCase = true) ||
            text.contains("Error:", ignoreCase = true) ||
            text.contains("Exception", ignoreCase = true) ||
            text.contains("Unable to", ignoreCase = true))
    }

    private fun launchSucceeded(result: ShellResult): Boolean {
        if (result.code != 0) return false
        val text = result.out + '\n' + result.err
        return !(text.contains("aborted", ignoreCase = true) ||
            text.contains("No activities found", ignoreCase = true) ||
            text.contains("Error", ignoreCase = true) ||
            text.contains("Exception", ignoreCase = true))
    }

    private fun isValidName(pkg: String): Boolean = pkg.isNotEmpty() && NAME_PATTERN.matches(pkg)

    private companion object {
        const val PACKAGE_PREFIX = "package:"
        const val BASE_APK_SUFFIX = "/base.apk"
        const val LISTING_TTL_MS = 60_000L

        val NAME_PATTERN = Regex("[A-Za-z0-9._]+")

        val SYSTEM_PATH_PREFIXES = listOf(
            "/system/",
            "/system_ext/",
            "/product/",
            "/vendor/",
            "/apex/",
        )

        val VERSION_NAME = Regex("""versionName=(\S+)""")
        val VERSION_CODE = Regex("""versionCode=(\d+)""")
        val USER_ID = Regex("""userId=(\d+)""")
        val CODE_PATH = Regex("""codePath=(\S+)""")
    }
}
