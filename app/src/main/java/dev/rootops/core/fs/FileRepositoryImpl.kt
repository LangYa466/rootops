package dev.rootops.core.fs

import dev.rootops.core.api.FileEntry
import dev.rootops.core.api.FileRepository
import dev.rootops.core.api.ShellResult
import dev.rootops.core.api.ShellRunner
import dev.rootops.core.util.modeToRwx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Root-aware [FileRepository].
 *
 * Every read path first tries the ordinary Java file APIs, which is fast and covers everything
 * the app sandbox may touch. Paths the sandbox cannot reach (`/data/data`, `/root`, `/system`, …)
 * are resolved through [shell] with root: `ls -la` for listings, `stat -c` for metadata,
 * `base64` for contents and a staged temp file under `/data/local/tmp` for writes.
 *
 * No method throws: failures degrade to an empty list, `false`, `0` or an empty string.
 */
class FileRepositoryImpl(private val shell: ShellRunner) : FileRepository {

    // ---------------------------------------------------------------- listing

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext emptyList()
            val target = normalizePath(path)
            val direct = readDirect(target)
            if (direct != null) return@withContext direct.sortedWith(ENTRY_ORDER)
            listViaShell(target)
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            emptyList()
        }
    }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext null
            statInternal(normalizePath(path))
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            null
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext false
            val target = normalizePath(path)
            val file = File(target)
            if (file.exists()) return@withContext true
            if (safeIsSymlink(file)) return@withContext true
            val q = quote(target)
            val res = sh("if [ -e $q ] || [ -L $q ]; then echo Y; else echo N; fi")
            res.ok && res.out.trim() == "Y"
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
    }

    override suspend fun canonical(path: String): String = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext path
            val target = normalizePath(path)
            val q = quote(target)
            val res = sh("readlink -f $q 2>/dev/null")
            val resolved = res.out.lineSequence().firstOrNull()?.trim().orEmpty()
            if (res.ok && resolved.isNotEmpty()) return@withContext resolved
            try {
                File(target).canonicalPath
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                target
            }
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            path
        }
    }

    // ------------------------------------------------------------------ reads

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext ""
            val limit = if (maxBytes <= 0) UNLIMITED else maxBytes
            String(readAll(normalizePath(path), limit), Charsets.UTF_8)
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            ""
        }
    }

    override suspend fun readBytes(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext EMPTY_BYTES
            val limit = if (maxBytes <= 0) UNLIMITED else maxBytes
            readAll(normalizePath(path), limit)
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            EMPTY_BYTES
        }
    }

    // ----------------------------------------------------------------- writes

    override suspend fun writeText(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            writeInternal(path, text.toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            writeInternal(path, bytes)
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
    }

    // ------------------------------------------------------------ aggregation

    override suspend fun search(root: String, query: String, limit: Int): List<FileEntry> =
        withContext(Dispatchers.IO) {
            try {
                if (root.isBlank() || query.isBlank()) return@withContext emptyList()
                val base = normalizePath(root)
                val max = if (limit <= 0) DEFAULT_SEARCH_LIMIT else limit
                val qRoot = quote(base)
                val qPattern = quote("*$query*")
                val cmd = "if command -v find >/dev/null 2>&1; then " +
                    "find $qRoot -maxdepth $SEARCH_DEPTH -iname $qPattern 2>/dev/null | head -n $max; " +
                    "else echo $NO_FIND; fi"
                val res = sh(cmd, timeoutMs = 120_000L)
                if (res.out.contains(NO_FIND)) return@withContext searchDirect(base, query, max)
                val paths = res.out.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(max)
                    .toList()
                if (paths.isEmpty()) return@withContext emptyList()
                val found = LinkedHashMap<String, FileEntry>()
                for (chunk in paths.chunked(STAT_BATCH)) {
                    for (entry in statBatch(chunk)) found[entry.path] = entry
                }
                paths.mapNotNull { found[it] }
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                emptyList()
            }
        }

    override suspend fun diskUsage(path: String): Long = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext 0L
            val q = quote(normalizePath(path))
            parseDu(sh("du -sb $q 2>/dev/null", timeoutMs = 180_000L).out)?.let {
                return@withContext it
            }
            parseDu(sh("du -sk $q 2>/dev/null", timeoutMs = 180_000L).out)?.let {
                return@withContext it * 1024L
            }
            val file = File(path)
            if (file.exists() && file.canRead()) return@withContext walkSize(file)
            0L
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            0L
        }
    }

    override suspend fun childrenCount(path: String): Int = withContext(Dispatchers.IO) {
        try {
            if (path.isBlank()) return@withContext 0
            val target = normalizePath(path)
            val q = quote(target)
            val res = sh("ls -1 $q 2>/dev/null | wc -l")
            val count = res.out.trim().split(WHITESPACE).lastOrNull()?.toIntOrNull()
            if (res.ok && count != null) return@withContext count.coerceAtLeast(0)
            val direct = try {
                File(target).list()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                null
            }
            direct?.size ?: 0
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            0
        }
    }

    // ------------------------------------------------------- direct file APIs

    /** Directory listing through the sandbox, or `null` when the app cannot list [dirPath]. */
    private fun readDirect(dirPath: String): List<FileEntry>? {
        val dir = File(dirPath)
        val isDir = try {
            dir.isDirectory
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
        if (!isDir) return null
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            null
        } ?: return null
        return children.map { entryOf(it) }
    }

    /** Metadata for one path using NIO attributes, with a legacy `java.io.File` fallback. */
    private fun entryOf(file: File): FileEntry {
        val path = file.absolutePath
        val nio = file.toPath()
        var isLink = false
        var isDir = false
        var special = false
        var size = 0L
        var modified = 0L
        var owner = ""
        var group = ""
        var mode = ""
        var readable = true
        var writable = false
        var executable = false
        var fromPosix = false

        try {
            isLink = Files.isSymbolicLink(nio)
            isDir = Files.isDirectory(nio)
            val attrs = Files.readAttributes(
                nio,
                PosixFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            size = attrs.size()
            modified = attrs.lastModifiedTime().toMillis() / 1000L
            owner = attrs.owner().name
            group = attrs.group().name
            val permissions = attrs.permissions()
            val bits = bitsOf(permissions)
            readable = bits.first
            writable = bits.second
            executable = bits.third
            special = !attrs.isDirectory && !attrs.isSymbolicLink && !attrs.isRegularFile
            val type = when {
                isLink -> 'l'
                attrs.isDirectory -> 'd'
                special -> '?'
                else -> '-'
            }
            mode = type + rwxOf(permissions)
            fromPosix = true
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
        }

        if (!fromPosix) {
            isLink = isLink || safeIsSymlink(file)
            isDir = try {
                file.isDirectory
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                false
            }
            size = try {
                file.length()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                0L
            }
            modified = try {
                file.lastModified() / 1000L
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                0L
            }
            readable = try {
                file.canRead()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                false
            }
            writable = try {
                file.canWrite()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                false
            }
            executable = try {
                file.canExecute()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                false
            }
            val type = when {
                isLink -> 'l'
                isDir -> 'd'
                else -> '-'
            }
            mode = type.toString() + buildString(9) {
                append(if (readable) 'r' else '-')
                append(if (writable) 'w' else '-')
                append(if (executable) 'x' else '-')
                append(if (readable) 'r' else '-')
                append(if (writable) 'w' else '-')
                append(if (executable) 'x' else '-')
                append(if (readable) 'r' else '-')
                append(if (writable) 'w' else '-')
                append(if (executable) 'x' else '-')
            }
        }

        val linkTarget = if (isLink) {
            try {
                Files.readSymbolicLink(nio).toString()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                null
            }
        } else {
            null
        }

        return FileEntry(
            name = file.name.ifEmpty { path },
            path = path,
            isDir = isDir,
            isLink = isLink,
            linkTarget = linkTarget,
            size = size,
            mode = mode,
            owner = owner,
            group = group,
            modified = modified,
            readable = readable,
            writable = writable,
            executable = executable,
            special = special,
        )
    }

    // ------------------------------------------------------ shell-based reads

    private suspend fun listViaShell(dirPath: String): List<FileEntry> {
        val q = quote(dirPath)
        val epochCommand = "if [ -d $q ]; then ls -la --time-style=+%s $q 2>/dev/null; " +
            "else echo $NOT_DIR; fi"
        val epochResult = sh(epochCommand)
        if (epochResult.ok && !epochResult.out.contains(NOT_DIR)) {
            val parsed = parseLsListing(epochResult.out, dirPath, epochStyle = true)
            if (parsed.isNotEmpty()) return parsed.sortedWith(ENTRY_ORDER)
        }
        val plainCommand = "if [ -d $q ]; then ls -la $q 2>/dev/null; else echo $NOT_DIR; fi"
        val plainResult = sh(plainCommand)
        if (plainResult.ok && !plainResult.out.contains(NOT_DIR)) {
            return parseLsListing(plainResult.out, dirPath, epochStyle = false)
                .sortedWith(ENTRY_ORDER)
        }
        return emptyList()
    }

    private fun parseLsListing(out: String, dirPath: String, epochStyle: Boolean): List<FileEntry> {
        val base = normalizePath(dirPath)
        val entries = ArrayList<FileEntry>()
        for (raw in out.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            if (line.startsWith("total ")) continue
            if (line == NOT_DIR) continue
            val entry = parseLsLine(line, base, epochStyle) ?: continue
            entries.add(entry)
        }
        return entries
    }

    private fun parseLsLine(line: String, base: String, epochStyle: Boolean): FileEntry? {
        val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < 6) return null

        val modeRaw = tokens[0]
        val typeChar = modeRaw.firstOrNull() ?: return null
        if (typeChar !in MODE_TYPES) return null
        val permissions = modeRaw.drop(1).take(9).padEnd(9, '-')

        val owner = tokens[2]
        val group = tokens[3]

        var size = 0L
        var index = 5
        val sizeToken = tokens[4]
        if (sizeToken.endsWith(",") && tokens.size > 5) {
            // Device node: "1,  3" is split across two tokens.
            size = sizeToken.dropLast(1).filter { it.isDigit() }.toLongOrNull() ?: 0L
            index = 6
        } else {
            size = sizeToken.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
        if (tokens.size <= index) return null

        val dateToken = tokens[index]
        val dateTokens: Int
        val modified: Long
        if (dateToken.all { it.isDigit() } && (epochStyle || dateToken.length >= 9)) {
            dateTokens = 1
            modified = dateToken.toLongOrNull() ?: 0L
        } else if (ISO_DATE_TOKEN.matches(dateToken)) {
            dateTokens = 2
            val timeToken = tokens.getOrElse(index + 1) { "" }
            modified = parseDateText("$dateToken $timeToken")
        } else if (MONTH_TOKEN.matches(dateToken)) {
            dateTokens = 3
            modified = parseDateText(tokens.drop(index).take(3).joinToString(" "))
        } else {
            return null
        }

        val nameIndex = index + dateTokens
        if (tokens.size <= nameIndex) return null
        var name = tokens.drop(nameIndex).joinToString(" ")
        if (name.isEmpty()) return null
        if (name == "." || name == "..") return null

        var linkTarget: String? = null
        if (typeChar == 'l') {
            val arrow = name.indexOf(LINK_ARROW)
            if (arrow >= 0) {
                linkTarget = name.substring(arrow + LINK_ARROW.length)
                name = name.substring(0, arrow)
            }
            if (name.isEmpty()) return null
        }

        return FileEntry(
            name = name,
            path = childPath(base, name),
            isDir = typeChar == 'd',
            isLink = typeChar == 'l',
            linkTarget = linkTarget,
            size = size,
            mode = typeChar.toString() + permissions,
            owner = owner,
            group = group,
            modified = modified,
            readable = permissionReadable(permissions),
            writable = permissionWritable(permissions),
            executable = permissionExecutable(permissions),
            special = typeChar in SPECIAL_TYPES,
        )
    }

    private suspend fun statInternal(target: String): FileEntry? {
        val q = quote(target)
        val command = "stat -c '$STAT_FORMAT' $q 2>/dev/null; " +
            "if [ -d $q ]; then echo $DIR_MARK; fi; " +
            "if [ -L $q ]; then echo $LINK_MARK; readlink $q 2>/dev/null; fi"
        val res = sh(command)

        var baseEntry: FileEntry? = null
        var dirMarker = false
        var linkMarker = false
        var linkTarget: String? = null
        var expectLink = false
        for (line in res.out.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == DIR_MARK) {
                dirMarker = true
                continue
            }
            if (trimmed == LINK_MARK) {
                linkMarker = true
                expectLink = true
                continue
            }
            if (expectLink) {
                linkTarget = trimmed
                expectLink = false
                continue
            }
            if (baseEntry == null) baseEntry = parseStatLine(trimmed)
        }

        val entry = baseEntry
        if (entry != null) {
            return entry.copy(
                isDir = entry.isDir || dirMarker,
                isLink = entry.isLink || linkMarker,
                linkTarget = linkTarget ?: entry.linkTarget,
            )
        }

        val file = File(target)
        val known = try {
            file.exists()
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
        if (!known && !safeIsSymlink(file)) return null
        return entryOf(file)
    }

    /** Parses one `stat -c '%n|%s|%a|%U|%G|%Y|%F'` line. */
    private fun parseStatLine(raw: String): FileEntry? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        val parts = line.split('|')
        if (parts.size < 7) return null
        val tail = parts.subList(parts.size - 6, parts.size)
        val fullPath = parts.subList(0, parts.size - 6).joinToString("|")
        if (fullPath.isEmpty()) return null

        val size = tail[0].trim().toLongOrNull() ?: 0L
        val octal = tail[1].trim()
        val owner = tail[2].trim()
        val group = tail[3].trim()
        val modified = tail[4].trim().toLongOrNull() ?: 0L
        val type = tail[5].trim()
        val typeChar = typeCharOf(type)
        val bits = octalBits(octal)

        return FileEntry(
            name = fileNameOf(fullPath),
            path = fullPath,
            isDir = typeChar == 'd',
            isLink = typeChar == 'l',
            size = size,
            mode = typeChar.toString() + modeToRwx(octal),
            owner = owner,
            group = group,
            modified = modified,
            readable = bits.first,
            writable = bits.second,
            executable = bits.third,
            special = typeChar in SPECIAL_TYPES,
        )
    }

    private suspend fun statBatch(paths: List<String>): List<FileEntry> {
        if (paths.isEmpty()) return emptyList()
        val args = paths.joinToString(" ") { quote(it) }
        val res = sh("stat -c '$STAT_FORMAT' $args 2>/dev/null", timeoutMs = 120_000L)
        if (res.out.isBlank()) return emptyList()
        val byPath = HashMap<String, FileEntry>(paths.size * 2)
        for (line in res.out.lineSequence()) {
            val entry = parseStatLine(line) ?: continue
            byPath[entry.path] = entry
        }
        return paths.mapNotNull { byPath[it] }
    }

    private suspend fun readAll(path: String, limit: Int): ByteArray {
        val file = File(path)
        val direct = try {
            file.isFile && file.canRead()
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
        if (direct) {
            try {
                FileInputStream(file).use { stream -> return readLimited(stream, limit) }
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
            }
        }
        return readViaShell(path, limit)
    }

    private suspend fun readViaShell(path: String, limit: Int): ByteArray {
        val q = quote(path)
        val cap = limit.coerceAtMost(MAX_SHELL_READ).toLong()
        val res = sh("head -c $cap $q 2>/dev/null | base64", timeoutMs = 120_000L)
        if (!res.ok || res.out.isBlank()) return EMPTY_BYTES
        return try {
            Base64.getMimeDecoder().decode(res.out)
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            EMPTY_BYTES
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(READ_BUFFER)
        val sink = ByteArrayOutputStream(minOf(limit, READ_BUFFER).coerceAtLeast(32))
        var total = 0
        while (total < limit) {
            val want = minOf(buffer.size, limit - total)
            val read = try {
                input.read(buffer, 0, want)
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                -1
            }
            if (read <= 0) break
            sink.write(buffer, 0, read)
            total += read
        }
        return sink.toByteArray()
    }

    // ----------------------------------------------------------- shell writes

    private suspend fun writeInternal(path: String, bytes: ByteArray): Boolean {
        if (path.isBlank()) return false
        val target = normalizePath(path)
        if (writeDirect(target, bytes)) return true
        return writeViaShell(target, bytes)
    }

    private fun writeDirect(path: String, bytes: ByteArray): Boolean {
        val target = File(path)
        val parent = target.parentFile ?: return false
        val writableTarget = try {
            target.exists() && target.canWrite()
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
        val writableParent = try {
            parent.isDirectory && parent.canWrite()
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
        if (!writableTarget && !writableParent) return false
        return try {
            FileOutputStream(target, false).use { out ->
                out.write(bytes)
                out.flush()
                try {
                    out.fd.sync()
                } catch (t: Throwable) {
                    rethrowIfCancelled(t)
                }
            }
            true
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
    }

    private suspend fun writeViaShell(path: String, bytes: ByteArray): Boolean {
        val tmpPath = "$TMP_DIR/rootops-$STAGING_PREFIX${UUID.randomUUID().toString().take(12)}.tmp"
        val qTmp = quote(tmpPath)
        val qTarget = quote(path)
        var staged = false
        try {
            staged = stageFile(tmpPath, bytes)
            if (!staged) return false

            val meta = targetMeta(path)
            val mode = if (meta.first.length in 3..4 && meta.first.all { it.isDigit() }) {
                meta.first
            } else {
                DEFAULT_FILE_MODE
            }
            var owner = meta.second
            var group = meta.third
            if (owner == null || group == null) {
                val parent = File(path).parent ?: "/"
                val parentMeta = targetMeta(parent)
                if (owner == null) owner = parentMeta.second
                if (group == null) group = parentMeta.third
            }

            val copy = sh("cp -f $qTmp $qTarget", timeoutMs = 120_000L)
            if (!copy.ok) return false

            val post = StringBuilder("chmod ")
                .append(mode)
                .append(' ')
                .append(qTarget)
            if (owner != null && group != null) {
                post.append(" && chown ")
                    .append(quote(owner))
                    .append(':')
                    .append(quote(group))
                    .append(' ')
                    .append(qTarget)
            }
            sh(post.toString(), timeoutMs = 30_000L)
            return true
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            return false
        } finally {
            if (staged) {
                try {
                    sh("rm -f $qTmp")
                } catch (t: Throwable) {
                    // Best effort cleanup only.
                }
            }
        }
    }

    /**
     * Writes [bytes] to [tmpPath] through root, chunked so no single command grows past the
     * shell argument limit. Chunks are 3-byte aligned so each base64 block decodes on its own.
     */
    private suspend fun stageFile(tmpPath: String, bytes: ByteArray): Boolean {
        val q = quote(tmpPath)
        val reset = sh(": > $q")
        if (!reset.ok) return false
        if (bytes.isEmpty()) return true
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + STAGE_CHUNK, bytes.size)
            val part = if (offset == 0 && end == bytes.size) bytes else bytes.copyOfRange(offset, end)
            val encoded = Base64.getEncoder().encodeToString(part)
            val res = sh("printf '%s' '$encoded' | base64 -d >> $q", timeoutMs = 120_000L)
            if (!res.ok) return false
            offset = end
        }
        return true
    }

    /** `mode|owner|group` of [path], following symlinks; empty values when unknown. */
    private suspend fun targetMeta(path: String): Triple<String, String?, String?> {
        val q = quote(path)
        var res = sh("stat -L -c '%a|%U|%G' $q 2>/dev/null")
        if (!res.ok || res.out.isBlank()) res = sh("stat -c '%a|%U|%G' $q 2>/dev/null")
        if (!res.ok) return Triple("", null, null)
        val line = res.out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return Triple("", null, null)
        val parts = line.split('|')
        if (parts.size < 3) return Triple("", null, null)
        return Triple(
            parts[0].trim(),
            parts[1].trim().ifEmpty { null },
            parts[2].trim().ifEmpty { null },
        )
    }

    // --------------------------------------------------------------- fallbacks

    private fun searchDirect(root: String, query: String, limit: Int): List<FileEntry> {
        val needle = query.lowercase(Locale.US)
        if (needle.isEmpty()) return emptyList()
        val start = File(root)
        val isDir = try {
            start.isDirectory
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            false
        }
        if (!isDir) return emptyList()

        val results = ArrayList<FileEntry>()
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.addLast(start to 0)
        var visited = 0
        while (pending.isNotEmpty() && results.size < limit && visited < DIRECT_WALK_LIMIT) {
            val (dir, depth) = pending.removeLast()
            visited++
            val children = try {
                dir.listFiles()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                null
            } ?: continue
            for (child in children) {
                if (child.name.lowercase(Locale.US).contains(needle)) {
                    results.add(entryOf(child))
                    if (results.size >= limit) break
                }
                val childIsDir = try {
                    child.isDirectory
                } catch (t: Throwable) {
                    rethrowIfCancelled(t)
                    false
                }
                val descend = depth + 1 < SEARCH_DEPTH &&
                    !child.name.startsWith(".") &&
                    !safeIsSymlink(child) &&
                    childIsDir
                if (descend) pending.addLast(child to (depth + 1))
            }
        }
        return results
    }

    private fun walkSize(root: File): Long {
        var total = 0L
        var visited = 0
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty() && visited < DIRECT_WALK_LIMIT) {
            val current = pending.removeLast()
            visited++
            val children = try {
                current.listFiles()
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                null
            }
            if (children == null) {
                if (current.isFile) total += current.length()
                continue
            }
            for (child in children) {
                val isDir = try {
                    child.isDirectory
                } catch (t: Throwable) {
                    rethrowIfCancelled(t)
                    false
                }
                if (isDir) pending.addLast(child) else total += child.length()
            }
        }
        return total
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun sh(
        cmd: String,
        root: Boolean = true,
        timeoutMs: Long = 30_000L,
    ): ShellResult = try {
        shell.run(cmd, root, timeoutMs)
    } catch (t: Throwable) {
        rethrowIfCancelled(t)
        ShellResult(code = -1, out = "", err = t.message ?: t.javaClass.simpleName)
    }

    private fun rethrowIfCancelled(t: Throwable) {
        if (t is CancellationException) throw t
    }

    private fun safeIsSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (t: Throwable) {
        rethrowIfCancelled(t)
        false
    }

    private fun normalizePath(path: String): String {
        var value = path.trim()
        if (value.isEmpty()) return "/"
        while (value.length > 1 && value.endsWith('/')) value = value.dropLast(1)
        return value
    }

    private fun childPath(base: String, name: String): String {
        val parent = normalizePath(base)
        return if (parent == "/") "/$name" else "$parent/$name"
    }

    private fun fileNameOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index < 0) trimmed else trimmed.substring(index + 1).ifEmpty { "/" }
    }

    private fun typeCharOf(type: String): Char {
        val value = type.lowercase(Locale.US)
        return when {
            value.contains("symbolic") || value == "link" -> 'l'
            value.contains("directory") -> 'd'
            value.contains("character") -> 'c'
            value.contains("block") -> 'b'
            value.contains("socket") -> 's'
            value.contains("fifo") || value.contains("pipe") -> 'p'
            else -> '-'
        }
    }

    private fun bitsOf(permissions: Set<PosixFilePermission>): Triple<Boolean, Boolean, Boolean> {
        val readable = PosixFilePermission.OWNER_READ in permissions ||
            PosixFilePermission.GROUP_READ in permissions ||
            PosixFilePermission.OTHERS_READ in permissions
        val writable = PosixFilePermission.OWNER_WRITE in permissions ||
            PosixFilePermission.GROUP_WRITE in permissions ||
            PosixFilePermission.OTHERS_WRITE in permissions
        val executable = PosixFilePermission.OWNER_EXECUTE in permissions ||
            PosixFilePermission.GROUP_EXECUTE in permissions ||
            PosixFilePermission.OTHERS_EXECUTE in permissions
        return Triple(readable, writable, executable)
    }

    private fun rwxOf(permissions: Set<PosixFilePermission>): String = buildString(9) {
        append(if (PosixFilePermission.OWNER_READ in permissions) 'r' else '-')
        append(if (PosixFilePermission.OWNER_WRITE in permissions) 'w' else '-')
        append(if (PosixFilePermission.OWNER_EXECUTE in permissions) 'x' else '-')
        append(if (PosixFilePermission.GROUP_READ in permissions) 'r' else '-')
        append(if (PosixFilePermission.GROUP_WRITE in permissions) 'w' else '-')
        append(if (PosixFilePermission.GROUP_EXECUTE in permissions) 'x' else '-')
        append(if (PosixFilePermission.OTHERS_READ in permissions) 'r' else '-')
        append(if (PosixFilePermission.OTHERS_WRITE in permissions) 'w' else '-')
        append(if (PosixFilePermission.OTHERS_EXECUTE in permissions) 'x' else '-')
    }

    private fun octalBits(octal: String): Triple<Boolean, Boolean, Boolean> {
        val digits = octal.filter { it.isDigit() }.takeLast(3).padStart(3, '0')
        var readable = false
        var writable = false
        var executable = false
        for (char in digits) {
            val value = char - '0'
            if (value and 4 != 0) readable = true
            if (value and 2 != 0) writable = true
            if (value and 1 != 0) executable = true
        }
        return Triple(readable, writable, executable)
    }

    private fun permissionReadable(permissions: String): Boolean =
        permissions.getOrNull(0) == 'r' ||
            permissions.getOrNull(3) == 'r' ||
            permissions.getOrNull(6) == 'r'

    private fun permissionWritable(permissions: String): Boolean =
        permissions.getOrNull(1) == 'w' ||
            permissions.getOrNull(4) == 'w' ||
            permissions.getOrNull(7) == 'w'

    private fun permissionExecutable(permissions: String): Boolean =
        isExecChar(permissions.getOrNull(2)) ||
            isExecChar(permissions.getOrNull(5)) ||
            isExecChar(permissions.getOrNull(8))

    private fun isExecChar(char: Char?): Boolean = char == 'x' || char == 's' || char == 't'

    private fun parseDu(out: String): Long? = out.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.split(WHITESPACE)
        ?.firstOrNull()
        ?.toLongOrNull()

    private fun parseDateText(text: String): Long {
        val value = text.trim()
        if (value.isEmpty()) return 0L
        for (pattern in DATE_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.isLenient = false
            val parsed = try {
                format.parse(value)
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                null
            } ?: continue
            if (pattern.startsWith("MMM")) {
                val calendar = Calendar.getInstance()
                calendar.time = parsed
                if (!YEAR_TOKEN.containsMatchIn(value)) {
                    calendar.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                    if (calendar.timeInMillis > System.currentTimeMillis() + 86_400_000L) {
                        calendar.add(Calendar.YEAR, -1)
                    }
                }
                return calendar.timeInMillis / 1000L
            }
            return parsed.time / 1000L
        }
        return 0L
    }

    companion object {
        private const val STAT_FORMAT = "%n|%s|%a|%U|%G|%Y|%F"
        private const val NOT_DIR = "__ROOTOPS_NOT_DIR__"
        private const val DIR_MARK = "__ROOTOPS_DIR__"
        private const val LINK_MARK = "__ROOTOPS_LINK__"
        private const val NO_FIND = "__ROOTOPS_NO_FIND__"
        private const val LINK_ARROW = " -> "
        private const val TMP_DIR = "/data/local/tmp"
        private const val STAGING_PREFIX = "w-"
        private const val DEFAULT_FILE_MODE = "0644"
        private const val SEARCH_DEPTH = 8
        private const val DEFAULT_SEARCH_LIMIT = 300
        private const val STAT_BATCH = 48
        private const val DIRECT_WALK_LIMIT = 50_000
        private const val MAX_SHELL_READ = 64 * 1024 * 1024
        private const val UNLIMITED = Int.MAX_VALUE
        private const val READ_BUFFER = 16 * 1024
        private const val STAGE_CHUNK = 24 * 1024
        private const val MODE_TYPES = "-dlbcps"
        private const val SPECIAL_TYPES = "bcps"
        private val EMPTY_BYTES = ByteArray(0)
        private val WHITESPACE = Regex("\\s+")
        private val ISO_DATE_TOKEN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val MONTH_TOKEN = Regex("^[A-Za-z]{3}$")
        private val YEAR_TOKEN = Regex("\\d{4}")
        private val DATE_PATTERNS = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "MMM d HH:mm:ss",
            "MMM d HH:mm",
            "MMM d yyyy",
        )
        private val ENTRY_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDir }
                .thenBy { it.name.lowercase(Locale.US) }

        /** POSIX single-quote escaping; the returned token is safe inside `sh -c`. */
        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
