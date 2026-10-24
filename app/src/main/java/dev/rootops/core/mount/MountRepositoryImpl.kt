package dev.rootops.core.mount

import dev.rootops.core.api.MountEntry
import dev.rootops.core.api.MountRepository
import dev.rootops.core.api.ShellResult
import dev.rootops.core.api.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MountRepository] backed by a root shell.
 *
 * The mount table is read from `/proc/mounts`, falling back to the `mount`
 * command when the proc file cannot be read. Both formats are accepted and
 * octal escapes (`\040` space, `\011` tab, `\012` newline, `\134` backslash)
 * are decoded for the device and mount point fields.
 *
 * Every operation validates its arguments before building a command line, so
 * no caller-supplied text can introduce additional shell words. No method
 * throws: failures surface as `false` (mutations) or an empty list ([list]).
 */
class MountRepositoryImpl(private val shell: ShellRunner) : MountRepository {

    override suspend fun list(): List<MountEntry> = safeIo(emptyList()) {
        val table = readProcMounts() ?: readMountCommand()
        if (table == null) emptyList() else parseTable(table)
    }

    override suspend fun remount(path: String, readOnly: Boolean): Boolean = safeIo(false) {
        if (!isValidMountPoint(path)) return@safeIo false
        val mode = if (readOnly) "remount,ro" else "remount,rw"
        runCommand("mount -o $mode $path")?.ok == true
    }

    override suspend fun mount(
        device: String,
        mountPoint: String,
        fsType: String,
        options: String,
    ): Boolean = safeIo(false) {
        if (!isValidMountPoint(mountPoint)) return@safeIo false
        if (!isValidDevice(device)) return@safeIo false
        val type = fsType.trim()
        if (type.isNotEmpty() && !isValidFsType(type)) return@safeIo false
        val opts = options.trim()
        if (opts.isNotEmpty() && !isValidOptions(opts)) return@safeIo false

        val command = buildString {
            append("mount")
            if (type.isNotEmpty()) append(" -t ").append(type)
            if (opts.isNotEmpty()) append(" -o ").append(opts)
            append(' ').append(device)
            append(' ').append(mountPoint)
        }
        runCommand(command)?.ok == true
    }

    override suspend fun umount(path: String): Boolean = safeIo(false) {
        if (!isValidMountPoint(path)) return@safeIo false
        if (runCommand("umount $path")?.ok == true) return@safeIo true
        runCommand("umount -l $path")?.ok == true
    }

    // region table reading

    private suspend fun readProcMounts(): String? {
        val result = runCommand("cat /proc/mounts") ?: return null
        return result.out.takeIf { result.ok && it.isNotBlank() }
    }

    private suspend fun readMountCommand(): String? {
        val result = runCommand("mount") ?: return null
        return result.out.takeIf { result.ok && it.isNotBlank() }
    }

    private fun parseTable(table: String): List<MountEntry> =
        table.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseLine)
            .sortedBy { it.mountPoint }
            .toList()

    /**
     * Parses one mount table line, accepting both the `/proc/mounts` layout
     * (`device mountPoint fsType options dump pass`) and the `mount` layout
     * (`device on mountPoint type fsType (options)`).
     */
    private fun parseLine(line: String): MountEntry? {
        MOUNT_LINE.matchEntire(line)?.let { match ->
            val device = unescapeOctal(match.groupValues[1])
            val mountPoint = unescapeOctal(match.groupValues[2])
            if (!mountPoint.startsWith("/")) return null
            return MountEntry(
                device = device,
                mountPoint = mountPoint,
                fsType = match.groupValues[3],
                options = splitOptions(match.groupValues[4]),
            )
        }

        val fields = line.split(WHITESPACE)
        if (fields.size < 4) return null
        val mountPoint = unescapeOctal(fields[1])
        if (!mountPoint.startsWith("/")) return null
        return MountEntry(
            device = unescapeOctal(fields[0]),
            mountPoint = mountPoint,
            fsType = fields[2],
            options = splitOptions(fields[3]),
        )
    }

    private fun splitOptions(raw: String): List<String> =
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Decodes `\NNN` octal escapes used by the kernel for spaces and friends. */
    private fun unescapeOctal(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 3 < value.length) {
                val d1 = value[i + 1]
                val d2 = value[i + 2]
                val d3 = value[i + 3]
                if (isOctal(d1) && isOctal(d2) && isOctal(d3)) {
                    out.append(((d1 - '0') * 64 + (d2 - '0') * 8 + (d3 - '0')).toChar())
                    i += 4
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isOctal(c: Char): Boolean = c in '0'..'7'

    // endregion

    // region validation

    /** Absolute path with no shell metacharacters and no whitespace. */
    private fun isValidMountPoint(path: String): Boolean = SAFE_PATH.matches(path)

    private fun isValidDevice(device: String): Boolean = SAFE_TOKEN.matches(device)

    private fun isValidFsType(fsType: String): Boolean = SAFE_FS_TYPE.matches(fsType)

    private fun isValidOptions(options: String): Boolean = SAFE_TOKEN.matches(options)

    // endregion

    private suspend fun runCommand(command: String): ShellResult? =
        try {
            shell.run(command, root = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            null
        }

    private suspend fun <T> safeIo(fallback: T, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                fallback
            }
        }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** `device on mountPoint type fsType (options)` */
        val MOUNT_LINE = Regex("^(.+) on (.+) type (\\S+) \\((.*)\\)$")

        /** Mount points: absolute, no whitespace, no shell metacharacters. */
        val SAFE_PATH = Regex("^/[A-Za-z0-9_./,:=+@%-]*$")

        /** Devices and option lists: `[A-Za-z0-9_./,:=+-]`, non-empty. */
        val SAFE_TOKEN = Regex("^[A-Za-z0-9_./,:=+%-]+$")

        /** Filesystem types, e.g. `ext4`, `fuse.sshfs`, `ntfs-3g`. */
        val SAFE_FS_TYPE = Regex("^[A-Za-z0-9_.,:+-]+$")
    }
}
