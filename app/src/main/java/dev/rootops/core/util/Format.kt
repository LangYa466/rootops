package dev.rootops.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BYTE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

/** Human readable binary size, e.g. `1.4 MiB`, `512 B`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-" + formatBytes(-bytes)
    if (bytes < 1024L) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, BYTE_UNITS[unit])
}

/** Local wall-clock timestamp for an epoch-seconds value, e.g. `2026-10-24 15:03`. */
fun formatTimestamp(epochSeconds: Long): String =
    TIMESTAMP_FORMAT.format(
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    )

/** Compact elapsed duration, e.g. `310ms`, `42s`, `3m 07s`, `1h 04m`. */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0ms"
    if (ms < 1_000L) return "${ms}ms"
    val totalSeconds = ms / 1_000L
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val totalMinutes = totalSeconds / 60L
    if (totalMinutes < 60L) {
        return String.format(Locale.US, "%dm %02ds", totalMinutes, totalSeconds % 60L)
    }
    val hours = totalMinutes / 60L
    return String.format(Locale.US, "%dh %02dm", hours, totalMinutes % 60L)
}

/** Shortens [s] to at most [max] chars by eliding the middle with `…`. */
fun truncateMiddle(s: String, max: Int): String {
    if (max <= 0) return ""
    if (s.length <= max) return s
    if (max <= 3) return s.take(max)
    val keep = max - 1
    val head = keep / 2
    val tail = keep - head
    return s.take(head) + "…" + s.takeLast(tail)
}

private fun octalTriple(digit: Char): String = when (digit) {
    '0' -> "---"
    '1' -> "--x"
    '2' -> "-w-"
    '3' -> "-wx"
    '4' -> "r--"
    '5' -> "r-x"
    '6' -> "rw-"
    '7' -> "rwx"
    else -> "---"
}

/**
 * Renders a permission mode as an `rwxr-xr-x` string.
 * Values that already use the rwx form (e.g. from `ls -l`) pass through unchanged.
 */
fun modeToRwx(mode: String): String {
    val trimmed = mode.trim()
    if (trimmed.isEmpty()) return "---------"
    if (trimmed.any { it == 'r' || it == 'w' || it == 'x' || it == '-' }) return trimmed
    val digits = trimmed.filter { it.isDigit() }.takeLast(3)
    if (digits.isEmpty()) return "---------"
    val octal = digits.padStart(3, '0')
    return buildString(9) {
        for (digit in octal) append(octalTriple(digit))
    }
}
