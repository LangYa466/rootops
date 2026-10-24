package dev.rootops.core.net

import android.content.Context
import dev.rootops.core.api.NetInterface
import dev.rootops.core.api.NetworkTools
import dev.rootops.core.api.PortEntry
import dev.rootops.core.api.ShellRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

private val WHITESPACE = Regex("""\s+""")
private val ADDR_LINE = Regex("""^\d+:\s+(\S+)\s+(inet6?)\s+(\S+)""")
private val LINK_LINE = Regex("""^\d+:\s+(\S+?):\s+<([^>]*)>(.*)$""")
private val STATE_UP = Regex("""\bstate\s+UP\b""")
private val FD_SOCKET = Regex("""/proc/(\d+)/fd/\d+\s*->\s*socket:\[(\d+)\]""")
private val SS_PID = Regex("""pid=(\d+)""")
private val SS_NAME = Regex("""\("([^"]+)""")
private val HOST_PATTERN = Regex("""^[A-Za-z0-9._:\-]+$""")
private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private val IPV6_LITERAL = Regex("""[0-9A-Fa-f:.]{2,45}""")

private val PROC_TCP_FILES = listOf("tcp", "tcp6")
private val PUBLIC_IP_ENDPOINTS = listOf("https://ifconfig.me", "https://api.ipify.org")

private data class IfaceAcc(
    val name: String,
    var up: Boolean = true,
    val addresses: MutableList<String> = ArrayList(),
)

private data class RawPort(
    val proto: String,
    val local: String,
    val remote: String,
    val state: String,
    val inode: Long,
)

private data class ProbeResult(val code: Int, val connectMs: Long, val totalMs: Long)

/**
 * Read-only network inspection backed by the platform shell plus `java.net` fallbacks.
 *
 * Every public entry point runs on [Dispatchers.IO] and never throws: failures degrade to
 * empty lists, `null` or a single explanatory flow line.
 */
class NetworkToolsImpl(
    private val context: Context,
    private val shell: ShellRunner,
) : NetworkTools {

    private val userAgent: String =
        "RootOps/" + runCatching { context.packageName }.getOrDefault("app")

    /** Positive binary lookups only; a negative result is re-checked on the next call. */
    private val binaryCache = HashMap<String, Boolean>()

    // ------------------------------------------------------------------ interfaces

    override suspend fun interfaces(): List<NetInterface> = withContext(Dispatchers.IO) {
        attempt(emptyList<NetInterface>()) {
            val counters = readCounters()
            val parsed = ipInterfaces()
            val resolved = if (parsed.isEmpty() || parsed.all { it.addresses.isEmpty() }) {
                fallbackInterfaces().ifEmpty { parsed }
            } else {
                parsed
            }
            resolved
                .map { iface ->
                    val counter = counters[iface.name]
                    if (counter == null) iface
                    else iface.copy(rxBytes = counter.first, txBytes = counter.second)
                }
                .sortedBy { it.name }
        }
    }

    private suspend fun ipInterfaces(): List<NetInterface> {
        val addrOut = shellOutput("ip -o addr show")
        val linkOut = shellOutput("ip -o link show")
        if (addrOut.isBlank() && linkOut.isBlank()) return emptyList()

        val interfaces = LinkedHashMap<String, IfaceAcc>()

        for (raw in addrOut.lineSequence()) {
            val match = ADDR_LINE.find(raw.trim()) ?: continue
            val name = match.groupValues[1]
            val family = match.groupValues[2]
            val address = match.groupValues[3].substringBefore('/').substringBefore('%')
            if (name.isEmpty() || address.isEmpty()) continue
            if (family != "inet" && family != "inet6") continue
            val entry = interfaces.getOrPut(name) { IfaceAcc(name) }
            if (address !in entry.addresses) entry.addresses.add(address)
        }

        for (raw in linkOut.lineSequence()) {
            val match = LINK_LINE.find(raw.trim()) ?: continue
            val name = match.groupValues[1]
            if (name.isEmpty()) continue
            val flags = match.groupValues[2]
            val rest = match.groupValues[3]
            val up = flags.split(',').any { it.trim().equals("UP", ignoreCase = true) } ||
                STATE_UP.containsMatchIn(rest)
            val entry = interfaces.getOrPut(name) { IfaceAcc(name) }
            entry.up = up
        }

        return interfaces.values.map { acc ->
            NetInterface(
                name = acc.name,
                up = acc.up,
                addresses = acc.addresses.toList(),
                rxBytes = 0,
                txBytes = 0,
            )
        }
    }

    private fun fallbackInterfaces(): List<NetInterface> {
        val enumeration = (try {
            NetworkInterface.getNetworkInterfaces()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }) ?: return emptyList()

        val result = ArrayList<NetInterface>()
        while (enumeration.hasMoreElements()) {
            val networkInterface = enumeration.nextElement() ?: continue
            val name = networkInterface.name ?: continue
            val up = try {
                networkInterface.isUp
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                false
            }
            val addresses = try {
                networkInterface.interfaceAddresses
                    .mapNotNull { it.address?.hostAddress?.substringBefore('%') }
                    .distinct()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                emptyList()
            }
            result.add(NetInterface(name, up, addresses, 0, 0))
        }
        return result
    }

    private suspend fun readCounters(): Map<String, Pair<Long, Long>> {
        val text = readProcFile("/proc/net/dev", shell.rootAvailable.value)
        if (text.isBlank()) return emptyMap()

        val counters = HashMap<String, Pair<Long, Long>>()
        for (raw in text.lineSequence()) {
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val name = raw.substring(0, colon).trim()
            if (name.isEmpty() || name == "face") continue
            val numbers = raw.substring(colon + 1).trim()
                .split(WHITESPACE)
                .mapNotNull { it.toLongOrNull() }
            if (numbers.size < 9) continue
            counters[name] = numbers[0] to numbers[8]
        }
        return counters
    }

    // ----------------------------------------------------------------------- ports

    override suspend fun ports(): List<PortEntry> = withContext(Dispatchers.IO) {
        attempt(emptyList<PortEntry>()) { loadPorts() }
    }

    private suspend fun loadPorts(): List<PortEntry> {
        val root = shell.rootAvailable.value
        val owners: Map<Long, Pair<Int, String?>> = if (root) scanSocketOwners() else emptyMap()

        val entries = ArrayList<PortEntry>()
        val listenIndex = HashMap<String, Int>()

        for (proto in PROC_TCP_FILES) {
            val text = readProcFile("/proc/net/$proto", root)
            if (text.isBlank()) continue
            for (raw in parseProcNet(text, proto)) {
                val owner = if (raw.inode > 0) owners[raw.inode] else null
                entries.add(
                    PortEntry(
                        proto = raw.proto,
                        local = raw.local,
                        remote = raw.remote,
                        state = raw.state,
                        pid = owner?.first,
                        process = owner?.second,
                    )
                )
                if (raw.state == "LISTEN") {
                    listenIndex["${raw.proto}|${raw.local}"] = entries.lastIndex
                }
            }
        }

        val ss = try {
            shell.run("ss -ltnp", root = root, timeoutMs = 10_000)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }

        if (ss != null && ss.code == 0 && ss.out.isNotBlank()) {
            for (entry in parseSs(ss.out)) {
                val key = "tcp|${entry.local}"
                val index = listenIndex[key]
                if (index != null) {
                    val existing = entries[index]
                    entries[index] = existing.copy(
                        pid = existing.pid ?: entry.pid,
                        process = existing.process ?: entry.process,
                    )
                } else {
                    listenIndex[key] = entries.size
                    entries.add(entry)
                }
            }
        }

        return entries.sortedWith(compareBy({ it.proto }, { it.local }, { it.remote }))
    }

    /** Parses `/proc/net/tcp` or `/proc/net/tcp6` into decoded endpoints. */
    private fun parseProcNet(text: String, proto: String): List<RawPort> {
        val result = ArrayList<RawPort>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("sl")) continue
            val fields = line.split(WHITESPACE)
            if (fields.size < 10) continue
            val local = formatEndpoint(fields[1], proto) ?: continue
            val remote = formatEndpoint(fields[2], proto) ?: continue
            val state = stateName(fields[3].uppercase(Locale.US))
            val inode = fields[9].toLongOrNull() ?: 0L
            result.add(RawPort(proto, local, remote, state, inode))
        }
        return result
    }

    /** Parses the listener table printed by `ss -ltnp`; rows without a local port are skipped. */
    private fun parseSs(text: String): List<PortEntry> {
        val result = ArrayList<PortEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("State")) continue
            val fields = line.split(WHITESPACE)
            if (fields.size < 5) continue
            val state = fields[0].uppercase(Locale.US)
            if (state != "LISTEN") continue
            val local = normalizeSsEndpoint(fields[3])
            if (local.isEmpty()) continue
            val remote = normalizeSsEndpoint(fields[4])
            val tail = fields.drop(5).joinToString(" ")
            val pid = SS_PID.find(tail)?.groupValues?.get(1)?.toIntOrNull()
            val name = SS_NAME.find(tail)?.groupValues?.get(1)
            result.add(PortEntry("tcp", local, remote, state, pid, name))
        }
        return result
    }

    private fun normalizeSsEndpoint(value: String): String {
        val endpoint = value.trim()
        if (endpoint.startsWith("*:")) return "0.0.0.0:" + endpoint.substring(2)
        if (!endpoint.startsWith("[")) return endpoint
        val close = endpoint.indexOf(']')
        if (close <= 0) return endpoint
        val host = endpoint.substring(1, close)
        val port = endpoint.substring(close + 1).removePrefix(":")
        if (host.startsWith("::ffff:", ignoreCase = true) && host.length > 7) {
            return host.substring(7) + ":" + port
        }
        return "[$host]:$port"
    }

    /**
     * Maps socket inodes to owning processes by scanning `/proc/<pid>/fd` symlinks as root.
     * The scan is capped at 4000 descriptors to keep it bounded on busy devices.
     */
    private suspend fun scanSocketOwners(): Map<Long, Pair<Int, String?>> {
        val result = try {
            shell.run(
                "ls -l /proc/[0-9]*/fd/* 2>/dev/null | head -n 4000",
                root = true,
                timeoutMs = 20_000,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return emptyMap()
        }
        if (result.out.isBlank()) return emptyMap()

        val owners = HashMap<Long, Int>()
        for (line in result.out.lineSequence()) {
            val match = FD_SOCKET.find(line) ?: continue
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            val inode = match.groupValues[2].toLongOrNull() ?: continue
            if (inode > 0) owners[inode] = pid
        }
        if (owners.isEmpty()) return emptyMap()

        val names = processNames(owners.values.toSet())
        return owners.mapValues { (_, pid) -> pid to names[pid] }
    }

    private suspend fun processNames(pids: Collection<Int>): Map<Int, String> {
        if (pids.isEmpty()) return emptyMap()
        val command = buildString {
            append("for p in ")
            append(pids.joinToString(" "))
            append("; do echo \"\$p \$(cat /proc/\$p/comm 2>/dev/null)\"; done")
        }
        val result = try {
            shell.run(command, root = true, timeoutMs = 15_000)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return emptyMap()
        }
        val names = HashMap<Int, String>()
        for (line in result.out.lineSequence()) {
            val space = line.indexOf(' ')
            if (space <= 0) continue
            val pid = line.substring(0, space).toIntOrNull() ?: continue
            val name = line.substring(space + 1).trim()
            if (name.isNotEmpty()) names[pid] = name
        }
        return names
    }

    private fun formatEndpoint(hex: String, proto: String): String? {
        val colon = hex.lastIndexOf(':')
        if (colon <= 0) return null
        val addressHex = hex.substring(0, colon)
        val port = hex.substring(colon + 1).toIntOrNull(16) ?: return null
        return if (proto == "tcp6") {
            val address = decodeIpv6(addressHex) ?: return null
            when {
                address.startsWith("::ffff:") -> address.removePrefix("::ffff:") + ":$port"
                address.contains(':') -> "[$address]:$port"
                else -> "$address:$port"
            }
        } else {
            val address = decodeIpv4(addressHex) ?: return null
            "$address:$port"
        }
    }

    /** `/proc/net/tcp` stores IPv4 as a little-endian 32-bit integer. */
    private fun decodeIpv4(hex: String): String? {
        if (hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        val a = (value and 0xFF).toInt()
        val b = ((value shr 8) and 0xFF).toInt()
        val c = ((value shr 16) and 0xFF).toInt()
        val d = ((value shr 24) and 0xFF).toInt()
        return "$a.$b.$c.$d"
    }

    /** `/proc/net/tcp6` stores four 32-bit words, each in host (little-endian) order. */
    private fun decodeIpv6(hex: String): String? {
        if (hex.length != 32) return null
        val bytes = ByteArray(16)
        for (word in 0 until 4) {
            val value = hex.substring(word * 8, word * 8 + 8).toLongOrNull(16) ?: return null
            for (index in 0 until 4) {
                bytes[word * 4 + index] = ((value shr (8 * index)) and 0xFF).toByte()
            }
        }
        return formatIpv6(bytes)
    }

    private fun formatIpv6(bytes: ByteArray): String {
        val mapped = (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        if (mapped) {
            return "::ffff:" + ipv4From(bytes, 12)
        }

        val groups = IntArray(8) { index ->
            ((bytes[index * 2].toInt() and 0xFF) shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)
        }

        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < groups.size) {
            if (groups[index] == 0) {
                var end = index
                while (end < groups.size && groups[end] == 0) end++
                val length = end - index
                if (length > bestLength) {
                    bestLength = length
                    bestStart = index
                }
                index = end
            } else {
                index++
            }
        }
        if (bestLength < 2) bestStart = -1

        val builder = StringBuilder()
        index = 0
        while (index < groups.size) {
            if (index == bestStart) {
                builder.append("::")
                index += bestLength
                continue
            }
            if (builder.isNotEmpty() && !builder.endsWith(":")) builder.append(':')
            builder.append(Integer.toHexString(groups[index]))
            index++
        }
        return builder.toString()
    }

    private fun ipv4From(bytes: ByteArray, offset: Int): String =
        "${bytes[offset].toInt() and 0xFF}." +
            "${bytes[offset + 1].toInt() and 0xFF}." +
            "${bytes[offset + 2].toInt() and 0xFF}." +
            "${bytes[offset + 3].toInt() and 0xFF}"

    private fun stateName(hex: String): String = when (hex) {
        "01" -> "ESTABLISHED"
        "02" -> "SYN_SENT"
        "03" -> "SYN_RECV"
        "04" -> "FIN_WAIT1"
        "05" -> "FIN_WAIT2"
        "06" -> "TIME_WAIT"
        "07" -> "CLOSE"
        "08" -> "CLOSE_WAIT"
        "09" -> "LAST_ACK"
        "0A" -> "LISTEN"
        "0B" -> "CLOSING"
        "0C" -> "NEW_SYN_RECV"
        else -> "STATE_$hex"
    }

    // ------------------------------------------------------------------ diagnostics

    override suspend fun ping(host: String, count: Int): Flow<String> = flow {
        val target = sanitizeHost(host)
        if (target == null) {
            emit("invalid host: ${host.take(64)}")
            return@flow
        }
        if (!binaryExists("ping")) {
            emit("ping: binary not available on this device")
            return@flow
        }
        val attempts = count.coerceIn(1, 20)
        emitAll(shell.stream("ping -c $attempts -W 2 ${shellQuote(target)}", root = false))
    }.guarded().flowOn(Dispatchers.IO)

    override suspend fun dnsLookup(host: String): Flow<String> = flow {
        val target = sanitizeHost(host)
        if (target == null) {
            emit("invalid host: ${host.take(64)}")
            return@flow
        }
        if (binaryExists("nslookup")) {
            emitAll(shell.stream("nslookup ${shellQuote(target)}", root = false))
            return@flow
        }
        val addresses = try {
            InetAddress.getAllByName(target)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        if (addresses == null || addresses.isEmpty()) {
            emit("lookup failed for $target")
            return@flow
        }
        for (address in addresses) {
            val text = address.hostAddress ?: continue
            emit(text)
        }
    }.guarded().flowOn(Dispatchers.IO)

    override suspend fun httpProbe(url: String): Flow<String> = flow {
        val target = sanitizeUrl(url)
        if (target == null) {
            emit("invalid url: ${url.take(120)}")
            return@flow
        }
        if (!binaryExists("curl")) {
            emit(probeWithUrlConnection(target))
            return@flow
        }
        val format = "HTTP %{http_code}  connect=%{time_connect}s  total=%{time_total}s\\n"
        val command = "curl -sS --max-time 10 -o /dev/null -w ${shellQuote(format)} " +
            shellQuote(target)
        var emitted = false
        emitAll(shell.stream(command, root = false).onEach { emitted = true })
        if (!emitted) emit("probe failed: no response")
    }.guarded().flowOn(Dispatchers.IO)

    override suspend fun publicIp(): String? = withContext(Dispatchers.IO) {
        attempt<String?>(null) {
            for (endpoint in PUBLIC_IP_ENDPOINTS) {
                val body = fetchText(endpoint, 8_000)
                val candidate = body?.trim().orEmpty()
                if (isIpLiteral(candidate)) return@attempt candidate
            }
            null
        }
    }

    override suspend fun traceroute(host: String): Flow<String> = flow {
        val target = sanitizeHost(host)
        if (target == null) {
            emit("invalid host: ${host.take(64)}")
            return@flow
        }
        if (!binaryExists("traceroute")) {
            emit("traceroute: binary not available on this device")
            return@flow
        }
        emitAll(shell.stream("traceroute -n -m 15 ${shellQuote(target)}", root = false))
    }.guarded().flowOn(Dispatchers.IO)

    // ----------------------------------------------------------------------- helpers

    private suspend fun shellOutput(command: String): String {
        val result = try {
            shell.run(command, root = false, timeoutMs = 10_000)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        return if (result != null && result.code == 0) result.out else ""
    }

    private suspend fun readProcFile(path: String, root: Boolean): String {
        val direct = try {
            val file = File(path)
            if (file.canRead()) file.readText() else ""
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ""
        }
        if (direct.isNotBlank()) return direct

        val result = try {
            // [path] is always an internal /proc constant, so it needs no shell quoting.
            shell.run("cat $path 2>/dev/null", root = root, timeoutMs = 8_000)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        return if (result != null && result.code == 0) result.out else ""
    }

    private suspend fun binaryExists(name: String): Boolean {
        synchronized(binaryCache) { binaryCache[name] }?.let { return it }
        val result = try {
            shell.run("command -v ${shellQuote(name)}", root = false, timeoutMs = 5_000)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val exists = result != null && result.code == 0 && result.out.isNotBlank()
        if (exists) synchronized(binaryCache) { binaryCache[name] = true }
        return exists
    }

    private fun probeWithUrlConnection(url: String): String {
        val head = attemptHttp(url, "HEAD")
        val chosen: ProbeResult? = if (head != null && head.code in 200..399) {
            head
        } else {
            attemptHttp(url, "GET") ?: head
        }
        val result = chosen ?: return "probe failed: unable to connect"
        return String.format(
            Locale.US,
            "HTTP %d  connect=%.3fs  total=%.3fs",
            result.code,
            result.connectMs / 1000.0,
            result.totalMs / 1000.0,
        )
    }

    private fun attemptHttp(url: String, method: String): ProbeResult? {
        var connection: HttpURLConnection? = null
        return try {
            val start = System.nanoTime()
            val current = URL(url).openConnection() as HttpURLConnection
            connection = current
            current.requestMethod = method
            current.connectTimeout = 10_000
            current.readTimeout = 10_000
            current.instanceFollowRedirects = true
            current.setRequestProperty("User-Agent", userAgent)
            current.connect()
            val connectMs = (System.nanoTime() - start) / 1_000_000
            val code = current.responseCode
            val totalMs = (System.nanoTime() - start) / 1_000_000
            ProbeResult(code, connectMs, totalMs)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // Ignore: the connection is already unusable.
            }
        }
    }

    private suspend fun fetchText(url: String, timeoutMs: Int): String? {
        if (binaryExists("curl")) {
            val seconds = (timeoutMs / 1000).coerceAtLeast(1)
            val result = try {
                shell.run(
                    "curl -sS --max-time $seconds ${shellQuote(url)}",
                    root = false,
                    timeoutMs = timeoutMs + 3_000L,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
            if (result != null && result.code == 0 && result.out.isNotBlank()) return result.out
        }
        return httpGetText(url, timeoutMs)
    }

    private fun httpGetText(url: String, timeoutMs: Int): String? {
        var connection: HttpURLConnection? = null
        return try {
            val current = URL(url).openConnection() as HttpURLConnection
            connection = current
            current.requestMethod = "GET"
            current.connectTimeout = timeoutMs
            current.readTimeout = timeoutMs
            current.instanceFollowRedirects = true
            current.setRequestProperty("User-Agent", userAgent)
            if (current.responseCode !in 200..299) {
                null
            } else {
                current.inputStream.bufferedReader().use { it.readText().take(4096) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // Ignore: the connection is already unusable.
            }
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        if (value.isEmpty() || value.length > 45) return false
        if (IPV4_LITERAL.matches(value)) {
            return value.split('.').all { part -> (part.toIntOrNull() ?: 256) in 0..255 }
        }
        return value.contains(':') && IPV6_LITERAL.matches(value)
    }

    private fun sanitizeHost(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isEmpty() || trimmed.length > 255) return null
        if (!HOST_PATTERN.matches(trimmed)) return null
        return trimmed
    }

    private fun sanitizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        if (!hasScheme) return null
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null
        return try {
            URL(trimmed)
            trimmed
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun Flow<String>.guarded(): Flow<String> = this.catch { error ->
        if (error is CancellationException) throw error
        emit("error: ${error.message ?: error.javaClass.simpleName}")
    }

    private suspend fun <T> attempt(default: T, block: suspend () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        default
    }
}
