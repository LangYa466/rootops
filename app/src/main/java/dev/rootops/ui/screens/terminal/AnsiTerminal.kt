package dev.rootops.ui.screens.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Marker for "no explicit colour" in a cell attribute. */
const val ANSI_DEFAULT: Int = -1

/** Colours for the 16 base ANSI slots (0-7 normal, 8-15 bright). */
val AnsiPalette: List<Color> = listOf(
    Color(0xFF1C2321), // 0  black
    Color(0xFFE06C75), // 1  red
    Color(0xFF7BD88F), // 2  green
    Color(0xFFE5C07B), // 3  yellow
    Color(0xFF6FB3D2), // 4  blue
    Color(0xFFC792EA), // 5  magenta
    Color(0xFF56B6C2), // 6  cyan
    Color(0xFFD8E3DA), // 7  white
    Color(0xFF4A5450), // 8  bright black
    Color(0xFFFF8A93), // 9  bright red
    Color(0xFF9BEFA9), // 10 bright green
    Color(0xFFF5D79B), // 11 bright yellow
    Color(0xFF8FCFEA), // 12 bright blue
    Color(0xFFDDB6F2), // 13 bright magenta
    Color(0xFF7FD6DF), // 14 bright cyan
    Color(0xFFF2F7F3), // 15 bright white
)

/** Foreground used while SGR has not selected a colour. */
val AnsiDefaultForeground: Color = Color(0xFFD8E3DA)

/** Background used while SGR has not selected a colour. */
val AnsiDefaultBackground: Color = Color(0xFF0B0F0D)

private const val TRUECOLOR_FLAG = 0x0100_0000
private const val SCROLLBACK_LIMIT = 2000
private const val TAB_WIDTH = 8
private const val MIN_DIM = 2
private const val MAX_COLS = 500
private const val MAX_ROWS = 500
private const val MAX_PARAMS = 96
private const val MAX_PENDING_BYTES = 8

/** One character cell of the screen grid with its rendition attributes. */
internal class TerminalCell {
    var char: Char = ' '
    var fg: Int = ANSI_DEFAULT
    var bg: Int = ANSI_DEFAULT
    var bold: Boolean = false
    var underline: Boolean = false
    var inverse: Boolean = false

    fun reset() {
        char = ' '
        fg = ANSI_DEFAULT
        bg = ANSI_DEFAULT
        bold = false
        underline = false
        inverse = false
    }

    fun copyFrom(other: TerminalCell) {
        char = other.char
        fg = other.fg
        bg = other.bg
        bold = other.bold
        underline = other.underline
        inverse = other.inverse
    }

    fun hasStyle(): Boolean = fg != ANSI_DEFAULT || bg != ANSI_DEFAULT || bold || underline || inverse

    fun sameStyle(other: TerminalCell): Boolean =
        fg == other.fg && bg == other.bg && bold == other.bold &&
            underline == other.underline && inverse == other.inverse
}

/**
 * A VT100/ANSI screen buffer.
 *
 * [feed] accepts raw bytes from a pty and maintains a [cols] x [rows] character grid plus a
 * [SCROLLBACK_LIMIT]-line scrollback ring. [visibleLines] turns the whole thing into styled
 * Compose text. All public members are thread safe: the pty reader may feed while the UI
 * renders.
 */
class TerminalBuffer(cols: Int = 80, rows: Int = 24) {

    var cols: Int = cols.coerceIn(MIN_DIM, MAX_COLS)
        private set

    var rows: Int = rows.coerceIn(MIN_DIM, MAX_ROWS)
        private set

    private var grid: Array<Array<TerminalCell>> = blankGrid(this.cols, this.rows)
    private val scrollback = ArrayDeque<Array<TerminalCell>>()
    private val scrollbackRendered = ArrayDeque<AnnotatedString>()

    var cursorX: Int = 0
        private set

    var cursorY: Int = 0
        private set

    private var wrapPending = false

    private var savedX = 0
    private var savedY = 0
    private var savedFg = ANSI_DEFAULT
    private var savedBg = ANSI_DEFAULT
    private var savedBold = false
    private var savedUnderline = false
    private var savedInverse = false

    private var penFg = ANSI_DEFAULT
    private var penBg = ANSI_DEFAULT
    private var penBold = false
    private var penUnderline = false
    private var penInverse = false

    private var state = ParserState.GROUND
    private val params = StringBuilder()
    private var pendingBytes = ByteArray(0)

    private enum class ParserState { GROUND, ESCAPE, CSI, OSC, OSC_ESC, CHARSET }

    // ------------------------------------------------------------------ input

    /** Decodes [bytes] as UTF-8 (incrementally, replacement on error) and consumes them. */
    @Synchronized
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val data = if (pendingBytes.isEmpty()) bytes else pendingBytes + bytes
        val usable = completePrefixLength(data)
        if (usable <= 0) {
            pendingBytes = if (data.size <= MAX_PENDING_BYTES) data else ByteArray(0)
            return
        }
        pendingBytes = data.copyOfRange(usable, data.size)
        val text = decodeUtf8(data, usable)
        for (ch in text) handle(ch)
    }

    /** Convenience overload for already decoded text (used by the shell fallback). */
    @Synchronized
    fun feed(text: String) {
        for (ch in text) handle(ch)
    }

    // ----------------------------------------------------------------- output

    /**
     * Renders scrollback + the visible grid as styled lines, with trailing blank cells trimmed.
     * The last grid row carries the cursor cell rendered in inverse video.
     */
    @Synchronized
    fun visibleLines(): List<AnnotatedString> {
        ensureScrollbackRendered()
        val out = ArrayList<AnnotatedString>(scrollback.size + rows)
        out.addAll(scrollbackRendered)
        for (y in 0 until rows) {
            out.add(renderRow(grid[y], cols, if (y == cursorY) cursorX else -1))
        }
        return out
    }

    /** Full buffer contents (scrollback + screen) as plain text, for clipboard export. */
    @Synchronized
    fun text(): String {
        val sb = StringBuilder()
        for (row in scrollback) sb.append(rowText(row, cols)).append('\n')
        for (y in 0 until rows) sb.append(rowText(grid[y], cols)).append('\n')
        return sb.toString()
    }

    /** Number of lines [visibleLines] will return. */
    @Synchronized
    fun lineCount(): Int = scrollback.size + rows

    // --------------------------------------------------------------- geometry

    /**
     * Applies a new grid size. Existing content is preserved: growing keeps rows at the top,
     * shrinking pushes the rows that fall off the top into the scrollback. The cursor is clamped.
     */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(MIN_DIM, MAX_COLS)
        val r = newRows.coerceIn(MIN_DIM, MAX_ROWS)
        if (c == cols && r == rows) return

        val oldRows = rows
        val dropped = if (r < oldRows) oldRows - r else 0
        for (y in 0 until dropped) pushScrollback(grid[y])

        val next = blankGrid(c, r)
        val keep = minOf(oldRows, r)
        val srcStart = dropped
        for (y in 0 until keep) {
            val src = grid[srcStart + y]
            val dst = next[y]
            val width = minOf(cols, c)
            for (x in 0 until width) dst[x].copyFrom(src[x])
        }

        grid = next
        cols = c
        rows = r
        scrollbackRendered.clear()
        cursorX = cursorX.coerceIn(0, c - 1)
        cursorY = (cursorY - dropped).coerceIn(0, r - 1)
        wrapPending = false
    }

    // ---------------------------------------------------------------- parsing

    private fun handle(ch: Char) {
        when (state) {
            ParserState.GROUND -> handleGround(ch)
            ParserState.ESCAPE -> handleEscape(ch)
            ParserState.CSI -> handleCsi(ch)
            ParserState.OSC -> handleOsc(ch)
            ParserState.OSC_ESC -> {
                state = ParserState.GROUND
                // ESC \ terminates the OSC; anything else restarts a normal escape sequence.
                if (ch != '\\') handleEscape(ch)
            }
            ParserState.CHARSET -> state = ParserState.GROUND
        }
    }

    private fun handleGround(ch: Char) {
        when (ch) {
            '\u001B' -> {
                params.setLength(0)
                state = ParserState.ESCAPE
            }
            '\r' -> {
                cursorX = 0
                wrapPending = false
            }
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\b' -> {
                if (cursorX > 0) cursorX--
                wrapPending = false
            }
            '\t' -> {
                wrapPending = false
                val next = ((cursorX / TAB_WIDTH) + 1) * TAB_WIDTH
                cursorX = next.coerceAtMost(cols - 1)
            }
            '\u0007' -> Unit // BEL
            else -> if (ch >= ' ' && ch != '\u007F') putChar(ch)
        }
    }

    private fun handleEscape(ch: Char) {
        state = ParserState.GROUND
        when (ch) {
            '[' -> {
                params.setLength(0)
                state = ParserState.CSI
            }
            ']' -> {
                params.setLength(0)
                state = ParserState.OSC
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                cursorX = 0
                lineFeed()
            }
            'M' -> reverseIndex()
            'c' -> resetAll()
            '(', ')', '*', '+', '-', '.', '/', '#', '%' -> state = ParserState.CHARSET
            else -> Unit // keypad modes, single shifts and anything unknown
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch == '\u001B') {
            params.setLength(0)
            state = ParserState.ESCAPE
            return
        }
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '<' ||
            ch == '=' || ch == ':' || ch == '!' || ch.code in 0x20..0x2F
        ) {
            if (params.length < MAX_PARAMS) params.append(ch)
            return
        }
        val raw = params.toString()
        params.setLength(0)
        state = ParserState.GROUND
        if (ch.code in 0x40..0x7E) dispatchCsi(ch, raw)
    }

    private fun handleOsc(ch: Char) {
        when (ch) {
            '\u0007' -> state = ParserState.GROUND // BEL terminator
            '\u001B' -> state = ParserState.OSC_ESC // maybe ST (ESC \)
            else -> Unit // title / hyperlink payload is not rendered
        }
    }

    private fun dispatchCsi(final: Char, raw: String) {
        val isPrivate = raw.isNotEmpty() &&
            (raw[0] == '?' || raw[0] == '>' || raw[0] == '<' || raw[0] == '=')
        val body = if (isPrivate) raw.substring(1) else raw
        val cleaned = body.filter { it.isDigit() || it == ';' }
        val values: List<Int> = if (cleaned.isEmpty()) {
            emptyList()
        } else {
            cleaned.split(';').map { it.toIntOrNull() ?: 0 }
        }
        // DEC private modes (cursor visibility, alternate screen, bracketed paste, ...) are
        // acknowledged but have no effect on this buffer.
        if (isPrivate) return

        val n = values.firstOrNull()?.takeIf { it > 0 } ?: 1
        when (final) {
            'A' -> moveCursor(0, -n)
            'B' -> moveCursor(0, n)
            'C' -> moveCursor(n, 0)
            'D' -> moveCursor(-n, 0)
            'E' -> {
                cursorX = 0
                moveCursor(0, n)
            }
            'F' -> {
                cursorX = 0
                moveCursor(0, -n)
            }
            'G' -> {
                cursorX = (n - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'd' -> {
                cursorY = (n - 1).coerceIn(0, rows - 1)
                wrapPending = false
            }
            'H', 'f' -> {
                val row = (values.getOrNull(0) ?: 1).let { if (it <= 0) 1 else it }
                val col = (values.getOrNull(1) ?: 1).let { if (it <= 0) 1 else it }
                cursorY = (row - 1).coerceIn(0, rows - 1)
                cursorX = (col - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'J' -> eraseDisplay(values.firstOrNull() ?: 0)
            'K' -> eraseLine(values.firstOrNull() ?: 0)
            'm' -> applySgr(values)
            // DECSTBM: scrolling regions are not implemented; park the cursor at home so the
            // following output lands somewhere sane instead of corrupting the grid.
            'r' -> {
                cursorX = 0
                cursorY = 0
                wrapPending = false
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'X' -> eraseCells(n)
            else -> Unit // h/l/n/c/q/t/g and unknown finals are ignored safely
        }
    }

    private fun applySgr(values: List<Int>) {
        if (values.isEmpty()) {
            resetPen()
            return
        }
        var i = 0
        while (i < values.size) {
            when (val p = values[i]) {
                0 -> resetPen()
                1 -> penBold = true
                2 -> penBold = false // dim is rendered as normal intensity
                3 -> Unit // italic unsupported
                4 -> penUnderline = true
                7 -> penInverse = true
                21, 22 -> penBold = false
                23, 24 -> penUnderline = false
                27 -> penInverse = false
                in 30..37 -> penFg = p - 30
                39 -> penFg = ANSI_DEFAULT
                in 40..47 -> penBg = p - 40
                49 -> penBg = ANSI_DEFAULT
                in 90..97 -> penFg = p - 90 + 8
                in 100..107 -> penBg = p - 100 + 8
                38, 48 -> {
                    val extended = values.getOrNull(i + 1)
                    when (extended) {
                        5 -> {
                            val index = (values.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                            if (p == 38) penFg = index else penBg = index
                            i += 2
                        }
                        2 -> {
                            val r = (values.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                            val g = (values.getOrNull(i + 3) ?: 0).coerceIn(0, 255)
                            val b = (values.getOrNull(i + 4) ?: 0).coerceIn(0, 255)
                            val packed = TRUECOLOR_FLAG or (r shl 16) or (g shl 8) or b
                            if (p == 38) penFg = packed else penBg = packed
                            i += 4
                        }
                        else -> i += 1
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    // --------------------------------------------------------------- mutation

    private fun putChar(c: Char) {
        if (wrapPending) {
            wrapPending = false
            cursorX = 0
            advanceLine()
        }
        val cell = grid[cursorY][cursorX]
        cell.char = c
        cell.fg = penFg
        cell.bg = penBg
        cell.bold = penBold
        cell.underline = penUnderline
        cell.inverse = penInverse
        if (cursorX >= cols - 1) {
            cursorX = cols - 1
            wrapPending = true
        } else {
            cursorX++
        }
    }

    private fun lineFeed() {
        wrapPending = false
        advanceLine()
    }

    private fun advanceLine() {
        if (cursorY >= rows - 1) scrollUp() else cursorY++
    }

    private fun scrollUp() {
        pushScrollback(grid[0])
        System.arraycopy(grid, 1, grid, 0, rows - 1)
        grid[rows - 1] = blankRow(cols)
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY > 0) {
            cursorY--
        } else {
            System.arraycopy(grid, 0, grid, 1, rows - 1)
            grid[0] = blankRow(cols)
        }
    }

    private fun pushScrollback(row: Array<TerminalCell>) {
        scrollback.addLast(row)
        scrollbackRendered.addLast(renderRow(row, cols, -1))
        while (scrollback.size > SCROLLBACK_LIMIT) {
            scrollback.removeFirst()
            scrollbackRendered.removeFirst()
        }
    }

    /** Rebuilds the rendered scrollback cache after a resize invalidated it. */
    private fun ensureScrollbackRendered() {
        if (scrollbackRendered.size == scrollback.size) return
        scrollbackRendered.clear()
        for (row in scrollback) scrollbackRendered.addLast(renderRow(row, cols, -1))
    }

    private fun moveCursor(dx: Int, dy: Int) {
        cursorX = (cursorX + dx).coerceIn(0, cols - 1)
        cursorY = (cursorY + dy).coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseRange(cursorY, cursorX, cols - 1)
                for (y in cursorY + 1 until rows) clearRow(y)
            }
            1 -> {
                for (y in 0 until cursorY) clearRow(y)
                eraseRange(cursorY, 0, cursorX)
            }
            else -> {
                for (y in 0 until rows) clearRow(y)
                if (mode == 3) {
                    scrollback.clear()
                    scrollbackRendered.clear()
                }
            }
        }
        wrapPending = false
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseRange(cursorY, cursorX, cols - 1)
            1 -> eraseRange(cursorY, 0, cursorX)
            else -> eraseRange(cursorY, 0, cols - 1)
        }
        wrapPending = false
    }

    private fun eraseCells(count: Int) {
        val end = (cursorX + count - 1).coerceAtMost(cols - 1)
        eraseRange(cursorY, cursorX, end)
    }

    private fun eraseRange(y: Int, from: Int, to: Int) {
        if (y !in 0 until rows) return
        val start = from.coerceIn(0, cols - 1)
        val end = to.coerceIn(0, cols - 1)
        if (start > end) return
        val row = grid[y]
        for (x in start..end) row[x].reset()
    }

    private fun clearRow(y: Int) {
        val row = grid[y]
        for (x in 0 until cols) row[x].reset()
    }

    private fun resetPen() {
        penFg = ANSI_DEFAULT
        penBg = ANSI_DEFAULT
        penBold = false
        penUnderline = false
        penInverse = false
    }

    private fun saveCursor() {
        savedX = cursorX
        savedY = cursorY
        savedFg = penFg
        savedBg = penBg
        savedBold = penBold
        savedUnderline = penUnderline
        savedInverse = penInverse
    }

    private fun restoreCursor() {
        cursorX = savedX.coerceIn(0, cols - 1)
        cursorY = savedY.coerceIn(0, rows - 1)
        penFg = savedFg
        penBg = savedBg
        penBold = savedBold
        penUnderline = savedUnderline
        penInverse = savedInverse
        wrapPending = false
    }

    private fun resetAll() {
        grid = blankGrid(cols, rows)
        scrollback.clear()
        scrollbackRendered.clear()
        cursorX = 0
        cursorY = 0
        wrapPending = false
        resetPen()
        params.setLength(0)
        state = ParserState.GROUND
    }

    // -------------------------------------------------------------- rendering

    private fun renderRow(row: Array<TerminalCell>, width: Int, cursorAt: Int): AnnotatedString {
        var end = minOf(width, row.size)
        while (end > 0 && row[end - 1].char == ' ' && row[end - 1].bg == ANSI_DEFAULT) end--
        if (cursorAt >= 0 && cursorAt + 1 > end) end = cursorAt + 1
        if (end <= 0) return AnnotatedString("")

        var plain = true
        for (i in 0 until end) {
            if (i == cursorAt || row[i].hasStyle()) {
                plain = false
                break
            }
        }
        val sb = StringBuilder(end)
        for (i in 0 until end) sb.append(row[i].char)
        if (plain) return AnnotatedString(sb.toString())

        return buildAnnotatedString {
            append(sb.toString())
            var i = 0
            while (i < end) {
                if (i == cursorAt) {
                    addStyle(CursorStyle, i, i + 1)
                    i++
                    continue
                }
                val cell = row[i]
                var j = i + 1
                while (j < end && j != cursorAt && row[j].sameStyle(cell)) j++
                if (cell.hasStyle()) addStyle(styleFor(cell), i, j)
                i = j
            }
        }
    }

    private fun rowText(row: Array<TerminalCell>, width: Int): String {
        var end = minOf(width, row.size)
        while (end > 0 && row[end - 1].char == ' ' && row[end - 1].bg == ANSI_DEFAULT) end--
        val sb = StringBuilder(end)
        for (i in 0 until end) sb.append(row[i].char)
        return sb.toString()
    }

    private fun styleFor(cell: TerminalCell): SpanStyle {
        val fgValue = if (cell.inverse) cell.bg else cell.fg
        val bgValue = if (cell.inverse) cell.fg else cell.bg
        var style = SpanStyle(color = colorFor(fgValue, AnsiDefaultForeground))
        if (bgValue != ANSI_DEFAULT || cell.inverse) {
            style = style.copy(background = colorFor(bgValue, AnsiDefaultBackground))
        }
        if (cell.bold) style = style.copy(fontWeight = FontWeight.Bold)
        if (cell.underline) style = style.copy(textDecoration = TextDecoration.Underline)
        return style
    }

    private companion object {
        val CursorStyle = SpanStyle(
            color = AnsiDefaultBackground,
            background = AnsiDefaultForeground,
        )
    }
}

// -------------------------------------------------------------------- helpers

private fun blankRow(width: Int): Array<TerminalCell> = Array(width) { TerminalCell() }

private fun blankGrid(width: Int, height: Int): Array<Array<TerminalCell>> =
    Array(height) { blankRow(width) }

/** Resolves an SGR colour value (default, 256-colour index or packed truecolour) to a Color. */
private fun colorFor(value: Int, fallback: Color): Color = when {
    value == ANSI_DEFAULT -> fallback
    value >= TRUECOLOR_FLAG -> Color(0xFF000000.toInt() or (value and 0xFFFFFF))
    value < 16 -> AnsiPalette[value.coerceIn(0, 15)]
    value < 232 -> {
        val n = value - 16
        val r = cubeLevel(n / 36)
        val g = cubeLevel((n / 6) % 6)
        val b = cubeLevel(n % 6)
        Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
    }
    else -> {
        val level = 8 + (value.coerceAtMost(255) - 232) * 10
        Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level)
    }
}

private fun cubeLevel(index: Int): Int = if (index == 0) 0 else 55 + index * 40

private fun decodeUtf8(data: ByteArray, length: Int): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return decoder.decode(ByteBuffer.wrap(data, 0, length)).toString()
}

/**
 * Length of the longest prefix of [data] that ends on a UTF-8 character boundary, so a
 * multi-byte character split across two [TerminalBuffer.feed] calls is not mangled.
 */
private fun completePrefixLength(data: ByteArray): Int {
    if (data.isEmpty()) return 0
    var i = data.size - 1
    var walked = 0
    while (i >= 0 && walked < 4) {
        val v = data[i].toInt() and 0xFF
        if (v and 0xC0 == 0x80) {
            i--
            walked++
            continue
        }
        val needed = when {
            v and 0x80 == 0x00 -> 1
            v and 0xE0 == 0xC0 -> 2
            v and 0xF0 == 0xE0 -> 3
            v and 0xF8 == 0xF0 -> 4
            else -> return data.size // stray continuation byte: let the decoder replace it
        }
        return if (data.size - i >= needed) data.size else i
    }
    return data.size
}
