package dev.rootops.core.fs

import dev.rootops.core.api.ArchiveEntry
import dev.rootops.core.api.ArchiveManager
import dev.rootops.core.api.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * [ArchiveManager] backed by the platform `zip`, `unzip`, `tar` and `gzip` binaries.
 *
 * All shell work goes through [shell], so archives living in root-only locations are
 * still usable. Whenever a binary is missing (common on Android, where toybox ships
 * neither `zip` nor `unzip`) or the archive is directly readable by the app process,
 * read-only work falls back to `java.util.zip`, and [createZip] falls back to
 * [ZipOutputStream].
 *
 * Every method is main-safe, never throws, and reports failure as `emptyList()` /
 * `false`.
 */
class ArchiveManagerImpl(private val shell: ShellRunner) : ArchiveManager {

    /** Binaries already proven to exist; negatives are not cached so a later root grant is picked up. */
    private val binaryCache = ConcurrentHashMap<String, Boolean>()

    // ---------------------------------------------------------------- list

    override suspend fun list(archivePath: String): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        runCatching { listInternal(archivePath) }.getOrDefault(emptyList<ArchiveEntry>())
    }

    private suspend fun listInternal(archivePath: String): List<ArchiveEntry> {
        val path = archivePath.trim()
        if (path.isEmpty()) return emptyList()
        return when (kindOf(path)) {
            Kind.ZIP -> listZip(path)
            Kind.TAR_GZ ->
                if (hasBinary("tar")) parseTarList(runListing("tar -tzvf ${quote(path)}")) else emptyList()
            Kind.TAR ->
                if (hasBinary("tar")) parseTarList(runListing("tar -tvf ${quote(path)}")) else emptyList()
            Kind.GZ -> listGz(path)
            Kind.UNKNOWN -> emptyList()
        }
    }

    private suspend fun listZip(path: String): List<ArchiveEntry> {
        val fromShell =
            if (hasBinary("unzip")) parseUnzipList(runListing("unzip -l -qq ${quote(path)}")) else emptyList()
        if (fromShell.isNotEmpty()) return fromShell
        val file = File(path)
        if (!file.canRead()) return fromShell
        return listZipJava(file).ifEmpty { fromShell }
    }

    private suspend fun listGz(path: String): List<ArchiveEntry> {
        if (hasBinary("gzip")) {
            val entry = parseGzipList(runListing("gzip -l ${quote(path)}"), path)
            if (entry != null) return listOf(entry)
        }
        val file = File(path)
        val name = file.name.removeSuffix(".gz").ifEmpty { file.name + ".out" }
        val compressed = file.length()
        val size = gzipTrailerSize(file) ?: compressed
        return listOf(ArchiveEntry(path = name, size = size, isDir = false, compressedSize = compressed))
    }

    // ------------------------------------------------------------- extract

    override suspend fun extract(archivePath: String, destDir: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val path = archivePath.trim()
            if (path.isEmpty()) return@runCatching false
            val dest = destDir.trimEnd('/').ifEmpty { "/" }
            if (!ensureDir(dest)) return@runCatching false
            when (kindOf(path)) {
                Kind.ZIP -> extractZip(path, dest)
                Kind.TAR_GZ -> extractTarGz(path, dest)
                Kind.TAR -> extractTar(path, dest)
                Kind.GZ -> extractGz(path, dest)
                Kind.UNKNOWN -> false
            }
        }.getOrDefault(false)
    }

    private suspend fun extractZip(path: String, dest: String): Boolean {
        if (hasBinary("unzip")) {
            val result = shell.run(
                "unzip -o -q ${quote(path)} -d ${quote(dest)} 2>/dev/null",
                timeoutMs = WRITE_TIMEOUT_MS,
            )
            if (result.ok) return true
        }
        val file = File(path)
        if (!file.canRead()) return false
        return extractZipJava(file, File(dest))
    }

    private suspend fun extractTarGz(path: String, dest: String): Boolean {
        if (!hasBinary("tar")) return false
        val direct = shell.run(
            "tar -xzf ${quote(path)} -C ${quote(dest)}",
            timeoutMs = WRITE_TIMEOUT_MS,
        )
        if (direct.ok) return true
        if (!hasBinary("gzip")) return false
        return shell.run(
            "gzip -dc ${quote(path)} | tar -xf - -C ${quote(dest)}",
            timeoutMs = WRITE_TIMEOUT_MS,
        ).ok
    }

    private suspend fun extractTar(path: String, dest: String): Boolean {
        if (!hasBinary("tar")) return false
        return shell.run("tar -xf ${quote(path)} -C ${quote(dest)}", timeoutMs = WRITE_TIMEOUT_MS).ok
    }

    private suspend fun extractGz(path: String, dest: String): Boolean {
        val file = File(path)
        val baseName = file.name.removeSuffix(".gz").ifEmpty { file.name + ".out" }
        // A `.gz` may wrap a tar stream (common for mislabeled `.tar.gz`); try that first.
        if (hasBinary("gzip") && hasBinary("tar")) {
            val piped = shell.run(
                "gzip -dc ${quote(path)} | tar -xf - -C ${quote(dest)}",
                timeoutMs = WRITE_TIMEOUT_MS,
            )
            if (piped.ok) return true
        }
        if (hasBinary("gzip")) {
            val target = joinPath(dest, baseName)
            val single = shell.run(
                "gzip -dc ${quote(path)} > ${quote(target)}",
                timeoutMs = WRITE_TIMEOUT_MS,
            )
            if (single.ok) return true
        }
        if (!file.canRead()) return false
        return extractGzJava(file, File(dest, baseName))
    }

    // ----------------------------------------------------------- createZip

    override suspend fun createZip(sources: List<String>, destZip: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val layout = archiveLayout(sources) ?: return@runCatching false
            if (destZip.isBlank()) return@runCatching false
            val destAbs = File(destZip.trim()).absolutePath
            if (!ensureDir(File(destAbs).parent ?: "/")) return@runCatching false
            if (hasBinary("zip")) {
                val names = layout.relativeNames.joinToString(" ") { quote(it) }
                val exclude = exclusionFor(destAbs, layout)
                val cmd = buildString {
                    append("cd ").append(quote(layout.parent)).append(" && zip -r -q ")
                    append(quote(destAbs)).append(' ').append(names)
                    if (exclude != null) append(" -x ").append(quote(exclude))
                }
                if (shell.run(cmd, timeoutMs = WRITE_TIMEOUT_MS).ok) return@runCatching true
            }
            createZipJava(layout, destAbs)
        }.getOrDefault(false)
    }

    // --------------------------------------------------------- createTarGz

    override suspend fun createTarGz(sources: List<String>, destTarGz: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val layout = archiveLayout(sources) ?: return@runCatching false
                if (!hasBinary("tar")) return@runCatching false
                if (destTarGz.isBlank()) return@runCatching false
                val destAbs = File(destTarGz.trim()).absolutePath
                if (!ensureDir(File(destAbs).parent ?: "/")) return@runCatching false
                val names = layout.relativeNames.joinToString(" ") { quote(it) }
                val exclude = exclusionFor(destAbs, layout)
                val excludeArg = exclude?.let { " --exclude=${quote(it)}" }.orEmpty()
                val parent = quote(layout.parent)

                val direct = shell.run(
                    "tar -czf ${quote(destAbs)} -C $parent$excludeArg $names",
                    timeoutMs = WRITE_TIMEOUT_MS,
                )
                if (direct.ok) return@runCatching true
                // tar exits 1 for warning-level problems (e.g. "file changed as we read it"
                // when the archive lives inside the tree being archived); the archive is
                // still complete, so validate it instead of trusting the exit code.
                if (tarLists(destAbs)) return@runCatching true
                if (excludeArg.isNotEmpty()) {
                    // The build may not know `--exclude`; retry without it rather than fail outright.
                    if (shell.run("tar -czf ${quote(destAbs)} -C $parent $names", timeoutMs = WRITE_TIMEOUT_MS).ok) {
                        return@runCatching true
                    }
                    if (tarLists(destAbs)) return@runCatching true
                }
                if (!hasBinary("gzip")) return@runCatching false
                val piped = shell.run(
                    "tar -cf - -C $parent $names | gzip -c > ${quote(destAbs)}",
                    timeoutMs = WRITE_TIMEOUT_MS,
                )
                piped.ok && tarLists(destAbs)
            }.getOrDefault(false)
        }

    // ------------------------------------------------------------ shell IO

    private suspend fun runListing(cmd: String): String {
        val result = runCatching { shell.run("$cmd 2>/dev/null", timeoutMs = LIST_TIMEOUT_MS) }
            .getOrNull() ?: return ""
        return if (result.out.isNotBlank()) result.out else ""
    }

    /** True when [path] is a readable gzip-compressed tar, used to validate `tar` creations. */
    private suspend fun tarLists(path: String): Boolean = runCatching {
        shell.run("tar -tzf ${quote(path)} >/dev/null 2>&1", timeoutMs = LIST_TIMEOUT_MS).ok
    }.getOrDefault(false)

    private suspend fun hasBinary(name: String): Boolean {
        if (binaryCache[name] == true) return true
        val found = runCatching {
            shell.run("command -v ${quote(name)} >/dev/null 2>&1", timeoutMs = 15_000).ok
        }.getOrDefault(false)
        if (found) binaryCache[name] = true
        return found
    }

    private suspend fun ensureDir(path: String): Boolean {
        if (File(path).isDirectory) return true
        val result = runCatching { shell.run("mkdir -p ${quote(path)} 2>/dev/null") }.getOrNull()
        if (result?.ok == true) return true
        val dir = File(path)
        return dir.isDirectory || dir.mkdirs()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"

    // ------------------------------------------------------- zip fallbacks

    private fun listZipJava(file: File): List<ArchiveEntry> = runCatching {
        ZipFile(file).use { zip ->
            val entries = ArrayList<ArchiveEntry>()
            for (entry in zip.entries()) {
                val dir = entry.isDirectory
                entries.add(
                    ArchiveEntry(
                        path = entry.name,
                        size = if (dir) 0L else entry.size.coerceAtLeast(0L),
                        isDir = dir,
                        compressedSize = if (dir) 0L else entry.compressedSize.coerceAtLeast(0L),
                    )
                )
            }
            entries
        }
    }.getOrDefault(emptyList())

    private fun extractZipJava(archive: File, destDir: File): Boolean = runCatching {
        if (!destDir.isDirectory && !destDir.mkdirs()) return@runCatching false
        val root = destDir.canonicalFile
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val target = File(root, entry.name).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    return@runCatching false // zip-slip guard
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
            }
            true
        }
    }.getOrDefault(false)

    private fun createZipJava(layout: ArchiveLayout, destAbs: String): Boolean = runCatching {
        val dest = File(destAbs)
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            for (relative in layout.relativeNames) {
                addToZip(zip, File(layout.parent, relative), relative)
            }
        }
        true
    }.getOrDefault(false)

    private fun addToZip(zip: ZipOutputStream, file: File, entryName: String) {
        if (file.isDirectory) {
            val dirName = if (entryName.endsWith("/")) entryName else "$entryName/"
            zip.putNextEntry(ZipEntry(dirName))
            zip.closeEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addToZip(zip, child, dirName + child.name)
            }
        } else if (file.isFile) {
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().buffered().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun extractGzJava(archive: File, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        true
    }.getOrDefault(false)

    /** Uncompressed size stored in the gzip trailer (ISIZE, little-endian, modulo 2^32). */
    private fun gzipTrailerSize(file: File): Long? = runCatching {
        if (!file.isFile || file.length() < 4L) return@runCatching null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 4L)
            var value = 0L
            for (shift in 0 until 4) {
                val byte = raf.read()
                if (byte < 0) return@runCatching null
                value = value or (byte.toLong() shl (shift * 8))
            }
            value and 0xFFFF_FFFFL
        }
    }.getOrNull()

    // ----------------------------------------------------------- layout

    /**
     * Common parent directory plus the per-source paths relative to it, which is exactly
     * what `cd <parent> && zip …` / `tar -C <parent>` need.
     */
    private class ArchiveLayout(val parent: String, val relativeNames: List<String>)

    private fun archiveLayout(sources: List<String>): ArchiveLayout? {
        val normalized = sources.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("/")) it else File(it).absolutePath }
            .map { if (it.length > 1) it.trimEnd('/') else it }
            .distinct()
            .toList()
        if (normalized.isEmpty()) return null

        val components = normalized.map { path -> path.split('/').filter { it.isNotEmpty() } }
        if (components.any { it.isEmpty() }) return null // "/" cannot be archived

        val parents = components.map { it.dropLast(1) }
        var common = parents.first()
        for (parent in parents.drop(1)) {
            var index = 0
            while (index < common.size && index < parent.size && common[index] == parent[index]) index++
            common = common.take(index)
        }

        val parentPath = if (common.isEmpty()) "/" else "/" + common.joinToString("/")
        val relatives = components.map { path -> path.drop(common.size).joinToString("/") }
        return ArchiveLayout(parentPath, relatives)
    }

    /** Path of the destination relative to the archive root, so an archive is never added to itself. */
    private fun exclusionFor(destAbs: String, layout: ArchiveLayout): String? {
        val parent = layout.parent.trimEnd('/')
        val prefix = if (parent.isEmpty()) "/" else "$parent/"
        if (!destAbs.startsWith(prefix)) return null
        val relative = destAbs.substring(prefix.length)
        return relative.ifEmpty { null }
    }

    // ---------------------------------------------------------- parsing

    private fun parseUnzipList(out: String): List<ArchiveEntry> {
        val entries = ArrayList<ArchiveEntry>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(WHITESPACE, limit = 4)
            if (parts.size < 4) continue
            val size = parts[0].toLongOrNull() ?: continue
            if (!UNZIP_DATE.matches(parts[1]) || !UNZIP_TIME.matches(parts[2])) continue
            val name = parts[3]
            if (name.isEmpty()) continue
            val isDir = name.endsWith("/")
            entries.add(
                ArchiveEntry(
                    path = name,
                    size = if (isDir) 0L else size,
                    isDir = isDir,
                    compressedSize = 0L,
                )
            )
        }
        return entries
    }

    /**
     * Parses `tar -tvf` lines such as
     * `-rw-r--r-- root/root  1234 2026-10-24 12:00 path/with spaces.txt`.
     * The name is taken as the remainder of the line so spaces survive.
     */
    private fun parseTarList(out: String): List<ArchiveEntry> {
        val entries = ArrayList<ArchiveEntry>()
        for (raw in out.lineSequence()) {
            if (raw.isBlank()) continue
            val tokens = TAR_TOKEN.findAll(raw).toList()
            if (tokens.isEmpty()) continue
            val mode = tokens[0].value
            if (mode.length < 2 || mode[0] !in "-hdlbcps") continue

            var dateIndex = -1
            var timeIndex = -1
            for (index in 1 until tokens.size) {
                val token = tokens[index].value
                val dateLike = TAR_DATE_ISO.matches(token) || TAR_DATE_MONTH.matches(token)
                if (!dateLike) continue
                val next = index + 1
                if (next < tokens.size && TAR_TIME.matches(tokens[next].value)) {
                    dateIndex = index
                    timeIndex = next
                    break
                }
                // Legacy `Mon DD HH:MM` form inserts a day number between month and time.
                if (next + 1 < tokens.size &&
                    tokens[next].value.toIntOrNull() != null &&
                    TAR_TIME.matches(tokens[next + 1].value)
                ) {
                    dateIndex = index
                    timeIndex = next + 1
                    break
                }
            }
            if (dateIndex < 1 || timeIndex < 0) continue

            val size = tokens[dateIndex - 1].value.toLongOrNull() ?: continue
            val name = raw.substring(tokens[timeIndex].range.last + 1).trim()
            if (name.isEmpty()) continue
            val isDir = mode.startsWith("d") || name.endsWith("/")
            entries.add(
                ArchiveEntry(
                    path = name,
                    size = if (isDir) 0L else size,
                    isDir = isDir,
                    compressedSize = 0L,
                )
            )
        }
        return entries
    }

    private fun parseGzipList(out: String, archivePath: String): ArchiveEntry? {
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("compressed")) continue
            val parts = line.split(WHITESPACE, limit = 4)
            if (parts.size < 4) continue
            val compressed = parts[0].toLongOrNull() ?: continue
            val size = parts[1].toLongOrNull() ?: continue
            // gzip echoes the path it was given; the stored member name is always a basename.
            val name = parts[3].trim().substringAfterLast('/')
            if (name.isEmpty() || name == "-") continue
            return ArchiveEntry(path = name, size = size, isDir = false, compressedSize = compressed)
        }
        val file = File(archivePath)
        if (!file.isFile) return null
        val fallbackName = file.name.removeSuffix(".gz").ifEmpty { file.name }
        return ArchiveEntry(path = fallbackName, size = file.length(), isDir = false, compressedSize = file.length())
    }

    private fun kindOf(path: String): Kind {
        val lower = path.lowercase(Locale.US)
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> Kind.TAR_GZ
            lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") -> Kind.ZIP
            lower.endsWith(".tar") -> Kind.TAR
            lower.endsWith(".gz") -> Kind.GZ
            else -> Kind.UNKNOWN
        }
    }

    private enum class Kind { ZIP, TAR_GZ, TAR, GZ, UNKNOWN }

    private companion object {
        const val LIST_TIMEOUT_MS = 120_000L
        const val WRITE_TIMEOUT_MS = 900_000L

        val WHITESPACE = Regex("\\s+")
        val UNZIP_DATE = Regex("""\d{2,4}[-/.]\d{1,2}[-/.]\d{2,4}""")
        val UNZIP_TIME = Regex("""\d{1,2}:\d{2}(:\d{2})?""")
        val TAR_DATE_ISO = Regex("""\d{4}-\d{2}-\d{2}""")
        val TAR_DATE_MONTH = Regex("""(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)""")
        val TAR_TIME = Regex("""\d{1,2}:\d{2}(:\d{2})?""")
        val TAR_TOKEN = Regex("""\S+""")
    }
}
