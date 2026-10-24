package dev.rootops.core.fs

import dev.rootops.core.api.FileEntry
import dev.rootops.core.api.FileOps
import dev.rootops.core.api.FileRepository
import dev.rootops.core.api.OpProgress
import dev.rootops.core.api.ShellResult
import dev.rootops.core.api.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Filesystem mutations executed through the privileged shell, so they work for paths the app
 * process cannot touch directly (`/system`, `/data`, `/sdcard`).
 *
 * Every operation runs on [Dispatchers.IO], publishes its state through [progress] and never
 * throws: failures are reported as `false` plus an error message on the progress flow. Operations
 * are serialised through a [Mutex] so concurrent callers cannot interleave destructive work or
 * progress updates.
 */
class FileOpsImpl(
    private val shell: ShellRunner,
    private val files: FileRepository,
) : FileOps {

    private val mutex = Mutex()

    private val _progress = MutableStateFlow(OpProgress())

    override val progress: StateFlow<OpProgress> = _progress.asStateFlow()

    // ---------------------------------------------------------------- operations

    override suspend fun copy(sources: List<String>, destDir: String): Boolean = runOp(LABEL_COPY) {
        val plans = resolveTargets(LABEL_COPY, sources, destDir, "copy") ?: return@runOp false
        val counted = plans.map { it.copy(entries = countEntries(it.source).coerceAtLeast(1L)) }
        val total = counted.sumOf { it.entries }
        var done = 0L
        publish(LABEL_COPY, done, total)

        for (plan in counted) {
            val source = quote(plan.source)
            val dest = quote(plan.dest)
            val result = shell.run(
                "cp -a -- $source $dest || cp -r -- $source $dest",
                root = true,
                timeoutMs = LONG_TIMEOUT_MS,
            )
            if (!result.ok) {
                return@runOp fail(
                    LABEL_COPY,
                    result.errorText("Failed to copy ${plan.source}"),
                    done,
                    total,
                )
            }
            done += plan.entries
            publish(LABEL_COPY, done, total)
        }

        publish(LABEL_COPY, total, total, running = false)
        true
    }

    override suspend fun move(sources: List<String>, destDir: String): Boolean = runOp(LABEL_MOVE) {
        val plans = resolveTargets(LABEL_MOVE, sources, destDir, "move") ?: return@runOp false
        val counted = plans.map { it.copy(entries = countEntries(it.source).coerceAtLeast(1L)) }
        val total = counted.sumOf { it.entries }
        var done = 0L
        publish(LABEL_MOVE, done, total)

        for (plan in counted) {
            val source = quote(plan.source)
            val dest = quote(plan.dest)
            val result = shell.run("mv -- $source $dest", root = true, timeoutMs = LONG_TIMEOUT_MS)
            if (!result.ok) {
                if (!isCrossDevice(result)) {
                    return@runOp fail(
                        LABEL_MOVE,
                        result.errorText("Failed to move ${plan.source}"),
                        done,
                        total,
                    )
                }
                // Cross-filesystem move: copy the tree, then drop the original.
                val copied = shell.run(
                    "cp -a -- $source $dest || cp -r -- $source $dest",
                    root = true,
                    timeoutMs = LONG_TIMEOUT_MS,
                )
                if (!copied.ok) {
                    return@runOp fail(
                        LABEL_MOVE,
                        copied.errorText("Failed to move ${plan.source}"),
                        done,
                        total,
                    )
                }
                val removed = shell.run("rm -rf -- $source", root = true, timeoutMs = LONG_TIMEOUT_MS)
                if (!removed.ok) {
                    return@runOp fail(
                        LABEL_MOVE,
                        removed.errorText("Copied ${plan.source} but could not remove the original"),
                        done,
                        total,
                    )
                }
            }
            done += plan.entries
            publish(LABEL_MOVE, done, total)
        }

        publish(LABEL_MOVE, total, total, running = false)
        true
    }

    override suspend fun delete(paths: List<String>): Boolean = runOp(LABEL_DELETE) {
        if (paths.isEmpty()) return@runOp fail(LABEL_DELETE, "No paths selected")

        val targets = ArrayList<String>(paths.size)
        for (raw in paths) {
            val guard = deletionGuard(raw)
            if (guard != null) return@runOp fail(LABEL_DELETE, guard)
            targets += normalize(raw)
        }

        val counted = targets.map { it to countEntries(it).coerceAtLeast(1L) }
        val total = counted.sumOf { it.second }
        var done = 0L
        publish(LABEL_DELETE, done, total)

        for ((path, entries) in counted) {
            val result = shell.run("rm -rf -- ${quote(path)}", root = true, timeoutMs = LONG_TIMEOUT_MS)
            if (!result.ok) {
                return@runOp fail(LABEL_DELETE, result.errorText("Failed to delete $path"), done, total)
            }
            done += entries
            publish(LABEL_DELETE, done, total)
        }

        publish(LABEL_DELETE, total, total, running = false)
        true
    }

    override suspend fun rename(path: String, newName: String): Boolean = runOp(LABEL_RENAME) {
        val source = normalize(path)
        if (source.isEmpty()) return@runOp fail(LABEL_RENAME, "Path is empty")
        if (isRootPath(source)) return@runOp fail(LABEL_RENAME, "Refusing to rename the filesystem root")

        val nameError = validateName(newName)
        if (nameError != null) return@runOp fail(LABEL_RENAME, nameError)

        val dest = joinPath(parentOf(source), newName)
        if (dest == source) return@runOp finish(LABEL_RENAME)
        if (!safeExists(source)) return@runOp fail(LABEL_RENAME, "Not found: $source")
        if (safeExists(dest)) return@runOp fail(LABEL_RENAME, "Already exists: $dest")

        publish(LABEL_RENAME, 0L, 1L)
        val result = shell.run("mv -- ${quote(source)} ${quote(dest)}", root = true, timeoutMs = LONG_TIMEOUT_MS)
        if (!result.ok) {
            return@runOp fail(LABEL_RENAME, result.errorText("Failed to rename $source"), 0L, 1L)
        }
        finish(LABEL_RENAME)
    }

    override suspend fun mkdir(parent: String, name: String): Boolean = runOp(LABEL_MKDIR) {
        val dir = normalize(parent)
        if (dir.isEmpty()) return@runOp fail(LABEL_MKDIR, "Parent directory is empty")

        val nameError = validateName(name)
        if (nameError != null) return@runOp fail(LABEL_MKDIR, nameError)

        val dest = joinPath(dir, name)
        if (safeExists(dest)) return@runOp fail(LABEL_MKDIR, "Already exists: $dest")

        publish(LABEL_MKDIR, 0L, 1L)
        val result = shell.run("mkdir -p -- ${quote(dest)}", root = true)
        if (!result.ok) {
            return@runOp fail(LABEL_MKDIR, result.errorText("Failed to create $dest"), 0L, 1L)
        }
        finish(LABEL_MKDIR)
    }

    override suspend fun createFile(parent: String, name: String): Boolean = runOp(LABEL_TOUCH) {
        val dir = normalize(parent)
        if (dir.isEmpty()) return@runOp fail(LABEL_TOUCH, "Parent directory is empty")

        val nameError = validateName(name)
        if (nameError != null) return@runOp fail(LABEL_TOUCH, nameError)

        val dest = joinPath(dir, name)
        if (safeExists(dest)) return@runOp fail(LABEL_TOUCH, "Already exists: $dest")

        publish(LABEL_TOUCH, 0L, 1L)
        val result = shell.run("touch -- ${quote(dest)}", root = true)
        if (!result.ok) {
            return@runOp fail(LABEL_TOUCH, result.errorText("Failed to create $dest"), 0L, 1L)
        }
        finish(LABEL_TOUCH)
    }

    override suspend fun chmod(path: String, mode: String, recursive: Boolean): Boolean = runOp(LABEL_CHMOD) {
        val target = normalize(path)
        if (target.isEmpty()) return@runOp fail(LABEL_CHMOD, "Path is empty")
        if (recursive && isRootPath(target)) {
            return@runOp fail(LABEL_CHMOD, "Refusing to change permissions recursively on the filesystem root")
        }

        val octal = mode.trim()
        if (!MODE_PATTERN.matches(octal)) {
            return@runOp fail(LABEL_CHMOD, "Invalid mode: $mode (expected 3 or 4 octal digits)")
        }

        val flags = if (recursive) "-R " else ""
        publish(LABEL_CHMOD, 0L, 1L)
        val result = shell.run(
            "chmod $flags$octal -- ${quote(target)}",
            root = true,
            timeoutMs = if (recursive) LONG_TIMEOUT_MS else DEFAULT_TIMEOUT_MS,
        )
        if (!result.ok) {
            return@runOp fail(LABEL_CHMOD, result.errorText("Failed to change mode of $target"), 0L, 1L)
        }
        finish(LABEL_CHMOD)
    }

    override suspend fun chown(
        path: String,
        owner: String,
        group: String,
        recursive: Boolean,
    ): Boolean = runOp(LABEL_CHOWN) {
        val target = normalize(path)
        if (target.isEmpty()) return@runOp fail(LABEL_CHOWN, "Path is empty")
        if (recursive && isRootPath(target)) {
            return@runOp fail(LABEL_CHOWN, "Refusing to change ownership recursively on the filesystem root")
        }

        val ownerName = owner.trim()
        val groupName = group.trim()
        if (!isValidAccount(ownerName)) return@runOp fail(LABEL_CHOWN, "Invalid owner: $owner")
        if (!isValidAccount(groupName)) return@runOp fail(LABEL_CHOWN, "Invalid group: $group")

        val flags = if (recursive) "-R " else ""
        publish(LABEL_CHOWN, 0L, 1L)
        val result = shell.run(
            "chown $flags${quote("$ownerName:$groupName")} -- ${quote(target)}",
            root = true,
            timeoutMs = if (recursive) LONG_TIMEOUT_MS else DEFAULT_TIMEOUT_MS,
        )
        if (!result.ok) {
            return@runOp fail(LABEL_CHOWN, result.errorText("Failed to change owner of $target"), 0L, 1L)
        }
        finish(LABEL_CHOWN)
    }

    override suspend fun symlink(target: String, linkPath: String): Boolean = runOp(LABEL_SYMLINK) {
        if (target.isBlank()) return@runOp fail(LABEL_SYMLINK, "Link target is empty")

        val link = normalize(linkPath)
        if (link.isEmpty()) return@runOp fail(LABEL_SYMLINK, "Link path is empty")
        if (isRootPath(link)) return@runOp fail(LABEL_SYMLINK, "Refusing to replace the filesystem root")
        if (safeExists(link)) return@runOp fail(LABEL_SYMLINK, "Already exists: $link")

        publish(LABEL_SYMLINK, 0L, 1L)
        val result = shell.run("ln -s -- ${quote(target)} ${quote(link)}", root = true)
        if (!result.ok) {
            return@runOp fail(LABEL_SYMLINK, result.errorText("Failed to create $link"), 0L, 1L)
        }
        finish(LABEL_SYMLINK)
    }

    // ---------------------------------------------------------------- planning

    /** A validated source/destination pair plus the number of entries it contains. */
    private data class Plan(val source: String, val dest: String, val entries: Long = 0L)

    /**
     * Validates a multi-source operation and maps every source onto `destDir/<basename>`.
     * Returns `null` after publishing an error when any guard fails.
     */
    private suspend fun resolveTargets(
        label: String,
        sources: List<String>,
        destDir: String,
        verb: String,
    ): List<Plan>? {
        if (sources.isEmpty()) {
            fail(label, "No sources selected")
            return null
        }
        val dir = normalize(destDir)
        if (dir.isEmpty()) {
            fail(label, "Destination directory is empty")
            return null
        }

        val plans = ArrayList<Plan>(sources.size)
        for (raw in sources) {
            val source = normalize(raw)
            if (source.isEmpty()) {
                fail(label, "Source path is empty")
                return null
            }
            if (isRootPath(source)) {
                fail(label, "Refusing to $verb the filesystem root")
                return null
            }
            val base = baseName(source)
            if (base.isEmpty()) {
                fail(label, "Invalid source: $raw")
                return null
            }
            val dest = joinPath(dir, base)
            if (isSamePath(source, dest)) {
                fail(label, "Source and destination are the same: $source")
                return null
            }
            val entry = safeStat(source)
            if (entry == null) {
                fail(label, "Source not found: $source")
                return null
            }
            if (entry.isDir && isInside(dest, source)) {
                fail(label, "Cannot $verb a directory into itself: $source")
                return null
            }
            // `cp`/`mv` would nest the source inside an existing directory at `dest` instead of
            // replacing it, which is never what the caller asked for.
            val existing = safeStat(dest)
            if (existing != null && existing.isDir) {
                fail(label, "Destination already exists: $dest")
                return null
            }
            if (plans.any { it.dest == dest }) {
                fail(label, "Two sources map to the same destination: $dest")
                return null
            }
            plans += Plan(source, dest)
        }
        return plans
    }

    // ---------------------------------------------------------------- shell helpers

    /** Counts the entries below [path] (`find` includes the path itself). */
    private suspend fun countEntries(path: String): Long {
        val target = if (path.startsWith("/")) path else "./$path"
        val result = shell.run(
            "find ${quote(target)} 2>/dev/null | wc -l",
            root = true,
            timeoutMs = COUNT_TIMEOUT_MS,
        )
        return result.out.trim().toLongOrNull() ?: 0L
    }

    private suspend fun safeStat(path: String): FileEntry? = try {
        files.stat(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun safeExists(path: String): Boolean = try {
        files.exists(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun safeCanonical(path: String): String? = try {
        files.canonical(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** True when both paths resolve to the same file, falling back to a textual comparison. */
    private suspend fun isSamePath(first: String, second: String): Boolean {
        if (first == second) return true
        val a = safeCanonical(first) ?: return false
        val b = safeCanonical(second) ?: return false
        return a == b
    }

    /** True when [path] is [dir] itself or lives below it. */
    private fun isInside(path: String, dir: String): Boolean {
        if (path == dir) return true
        val prefix = if (dir.endsWith("/")) dir else "$dir/"
        return path.startsWith(prefix)
    }

    private fun isCrossDevice(result: ShellResult): Boolean {
        val text = (result.err + "\n" + result.out).lowercase()
        return text.contains("exdev") ||
            text.contains("cross-device") ||
            text.contains("cross device")
    }

    private fun ShellResult.errorText(fallback: String): String {
        val text = err.trim().ifEmpty { out.trim() }
        if (text.isEmpty()) return fallback
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: fallback
    }

    // ---------------------------------------------------------------- validation

    /** Validates a single path segment; returns an error message or `null` when acceptable. */
    private fun validateName(name: String): String? = when {
        name.isEmpty() -> "Name must not be empty"
        name.isBlank() -> "Name must not be blank"
        name == "." || name == ".." -> "Invalid name: $name"
        name.contains('/') -> "Name must not contain '/'"
        name.any { it.code < 0x20 || it.code == 0x7f } -> "Name contains a control character"
        else -> null
    }

    /** Returns an error message when [raw] must not be deleted, `null` otherwise. */
    private fun deletionGuard(raw: String): String? {
        if (raw.isBlank()) return "Refusing to delete an empty path"
        if (isRootPath(raw)) return "Refusing to delete the filesystem root"

        val path = normalize(raw)
        val leaf = path.substringAfterLast('/')
        val unsafe = leaf == "." || leaf == ".." || path == "." || path == ".."
        return if (unsafe) "Refusing to delete a relative path: $raw" else null
    }

    private fun isValidAccount(value: String): Boolean =
        value.isNotEmpty() && value.none { it.isWhitespace() || it == ':' || it == '/' }

    // ---------------------------------------------------------------- path helpers

    /** Quotes [value] for POSIX shells, escaping embedded single quotes. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Strips trailing slashes (except for the root itself) so commands see one canonical form. */
    private fun normalize(path: String): String {
        if (path.isEmpty()) return path
        var end = path.length
        while (end > 1 && path[end - 1] == '/') end--
        return path.substring(0, end)
    }

    private fun isRootPath(path: String): Boolean =
        path.isNotEmpty() && path.all { it == '/' }

    private fun baseName(path: String): String {
        val trimmed = normalize(path)
        if (trimmed == "/") return ""
        return trimmed.substringAfterLast('/')
    }

    private fun parentOf(path: String): String {
        val trimmed = normalize(path)
        val slash = trimmed.lastIndexOf('/')
        return when {
            slash < 0 -> "."
            slash == 0 -> "/"
            else -> trimmed.substring(0, slash)
        }
    }

    private fun joinPath(dir: String, name: String): String = when {
        dir.isEmpty() || dir == "." -> name
        dir == "/" -> "/$name"
        dir.endsWith("/") -> dir + name
        else -> "$dir/$name"
    }

    // ---------------------------------------------------------------- progress

    private fun publish(label: String, done: Long, total: Long, running: Boolean = true) {
        _progress.value = OpProgress(label = label, done = done, total = total, running = running)
    }

    private fun finish(label: String): Boolean {
        _progress.value = OpProgress(label = label, done = 1L, total = 1L, running = false)
        return true
    }

    private fun fail(label: String, message: String, done: Long = 0L, total: Long = 0L): Boolean {
        _progress.value = OpProgress(
            label = label,
            done = done,
            total = total,
            running = false,
            error = message,
        )
        return false
    }

    // ---------------------------------------------------------------- execution

    /** Runs [block] on [Dispatchers.IO] under the operation lock, mapping any failure to `false`. */
    private suspend fun runOp(label: String, block: suspend () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    _progress.value = OpProgress(label = label, running = false)
                    throw e
                } catch (e: Exception) {
                    fail(
                        label,
                        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                    )
                }
            }
        }

    private companion object {
        const val LABEL_COPY = "Copying"
        const val LABEL_MOVE = "Moving"
        const val LABEL_DELETE = "Deleting"
        const val LABEL_RENAME = "Renaming"
        const val LABEL_MKDIR = "Creating folder"
        const val LABEL_TOUCH = "Creating file"
        const val LABEL_CHMOD = "Changing permissions"
        const val LABEL_CHOWN = "Changing ownership"
        const val LABEL_SYMLINK = "Creating symlink"

        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val LONG_TIMEOUT_MS = 30 * 60 * 1000L
        const val COUNT_TIMEOUT_MS = 5 * 60 * 1000L

        val MODE_PATTERN = Regex("^[0-7]{3,4}$")
    }
}
