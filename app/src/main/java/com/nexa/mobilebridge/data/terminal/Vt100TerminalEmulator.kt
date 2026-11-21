package com.nexa.mobilebridge.data.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

data class TerminalCell(
    val ch: Char = ' ',
    val fgArgb: Int = DEFAULT_FG,
    val bgArgb: Int = DEFAULT_BG,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
) {
    companion object {
        const val DEFAULT_FG: Int = 0xFFE2E8F0.toInt()
        const val DEFAULT_BG: Int = 0x00000000 // Transparent (uses canvas background)
        val BLANK = TerminalCell()
    }
}

data class TerminalScreenSnapshot(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val scrollbackLines: Int,
    val isAlternateScreen: Boolean,
    val grid: List<List<TerminalCell>>
)

/**
 * Pure-Kotlin VT100 / xterm-256color ANSI Terminal Emulator (FR-TERM-02, FR-TERM-03, FR-TERM-09):
 * - Zero external native .so libraries -> 100% 16 KB Android 16 page-size compliant
 * - Supports ANSI 16, 256-color palette, 24-bit TrueColor, bold/italic/underline/reverse
 * - Supports cursor positioning, erase display/line, scroll regions, alternate screen (?1049h/?1049l)
 * - Strictly caps scrollback history at <= 1,000 lines (FR-TERM-09)
 */
class Vt100TerminalEmulator(
    initialRows: Int = 24,
    initialCols: Int = 80,
    val maxScrollback: Int = 1000
) {
    private enum class ParseState {
        GROUND,
        ESC,
        CSI,
        OSC,
        OSC_ESC,
        DCS,
        DCS_ESC,
        CHARSET
    }

    private var rows: Int = initialRows.coerceIn(4, 200)
    private var cols: Int = initialCols.coerceIn(10, 300)

    private var primaryGrid: Array<Array<TerminalCell>> = allocateGrid(rows, cols)
    private var altGrid: Array<Array<TerminalCell>> = allocateGrid(rows, cols)
    private var isAltScreen: Boolean = false

    private val scrollback = ArrayDeque<Array<TerminalCell>>(maxScrollback + 1)

    private var cursorRow: Int = 0
    private var cursorCol: Int = 0
    private var savedCursorRow: Int = 0
    private var savedCursorCol: Int = 0
    private var altSavedCursorRow: Int = 0
    private var altSavedCursorCol: Int = 0
    private var cursorVisible: Boolean = true
    private var pendingWrap: Boolean = false
    private var lastPrintedChar: Char = ' '

    private var scrollTop: Int = 0
    private var scrollBottom: Int = rows - 1

    // Current SGR pen attributes
    private var fgArgb: Int = TerminalCell.DEFAULT_FG
    private var bgArgb: Int = TerminalCell.DEFAULT_BG
    private var bold: Boolean = false
    private var italic: Boolean = false
    private var underline: Boolean = false
    private var inverse: Boolean = false

    // Incremental parser state
    private var parseState = ParseState.GROUND
    private val csiBuf = StringBuilder(32)
    private val utf8Buf = ByteArrayOutputStream(4)
    private var utf8Expected = 0

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun activeGrid(): Array<Array<TerminalCell>> = if (isAltScreen) altGrid else primaryGrid

    @Synchronized
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        for (rawByte in bytes) {
            val b = rawByte.toInt() and 0xFF
            if (utf8Expected > 0) {
                if ((b and 0xC0) == 0x80) {
                    utf8Buf.write(b)
                    if (utf8Buf.size() == utf8Expected) {
                        val decoded = utf8Buf.toString(Charsets.UTF_8.name())
                        utf8Buf.reset()
                        utf8Expected = 0
                        for (c in decoded) processChar(c)
                    }
                    continue
                } else {
                    utf8Buf.reset()
                    utf8Expected = 0
                }
            }

            when {
                b < 0x80 -> processChar(b.toChar())
                (b and 0xE0) == 0xC0 -> {
                    utf8Buf.reset(); utf8Buf.write(b); utf8Expected = 2
                }
                (b and 0xF0) == 0xE0 -> {
                    utf8Buf.reset(); utf8Buf.write(b); utf8Expected = 3
                }
                (b and 0xF8) == 0xF0 -> {
                    utf8Buf.reset(); utf8Buf.write(b); utf8Expected = 4
                }
                else -> processChar('\uFFFD')
            }
        }
        _version.value++
    }

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        val targetRows = newRows.coerceIn(4, 200)
        val targetCols = newCols.coerceIn(10, 300)
        if (targetRows == rows && targetCols == cols) return

        // A-H9: When primary screen shrinks vertically (e.g. IME keyboard opens), push top lines into
        // scrollback instead of clipping the active prompt at the bottom of the screen.
        if (!isAltScreen && targetRows < rows && cursorRow >= targetRows) {
            val overflow = (cursorRow - targetRows + 1).coerceIn(0, rows - targetRows)
            for (i in 0 until overflow) {
                if (scrollback.size >= maxScrollback) {
                    scrollback.removeFirst()
                }
                scrollback.addLast(primaryGrid[i].copyOf())
            }
            val shifted = allocateGrid(targetRows, targetCols)
            for (r in 0 until targetRows) {
                val srcRow = primaryGrid[r + overflow]
                val copyCols = min(srcRow.size, targetCols)
                for (c in 0 until copyCols) {
                    shifted[r][c] = srcRow[c]
                }
            }
            primaryGrid = shifted
            cursorRow = (cursorRow - overflow).coerceIn(0, targetRows - 1)
        } else {
            primaryGrid = resizeGrid(primaryGrid, targetRows, targetCols)
            cursorRow = cursorRow.coerceIn(0, targetRows - 1)
        }

        altGrid = resizeGrid(altGrid, targetRows, targetCols)
        rows = targetRows
        cols = targetCols
        scrollTop = 0
        scrollBottom = rows - 1
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
        _version.value++
    }

    @Synchronized
    fun reset() {
        primaryGrid = allocateGrid(rows, cols)
        altGrid = allocateGrid(rows, cols)
        scrollback.clear()
        isAltScreen = false
        cursorRow = 0
        cursorCol = 0
        savedCursorRow = 0
        savedCursorCol = 0
        altSavedCursorRow = 0
        altSavedCursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisible = true
        pendingWrap = false
        lastPrintedChar = ' '
        resetSgr()
        parseState = ParseState.GROUND
        csiBuf.setLength(0)
        utf8Buf.reset()
        utf8Expected = 0
        _version.value++
    }

    @Synchronized
    fun snapshot(scrollOffsetLines: Int = 0): TerminalScreenSnapshot {
        val grid = activeGrid()
        val effectiveOffset = if (isAltScreen) 0 else scrollOffsetLines.coerceIn(0, scrollback.size)
        val out = ArrayList<List<TerminalCell>>(rows)

        if (effectiveOffset == 0) {
            for (r in 0 until rows) {
                out.add(grid[r].toList())
            }
        } else {
            val sbStart = scrollback.size - effectiveOffset
            for (r in 0 until rows) {
                val sbIdx = sbStart + r
                val line = if (sbIdx < scrollback.size) {
                    padOrTrimLine(scrollback[sbIdx], cols)
                } else {
                    grid[sbIdx - scrollback.size].toList()
                }
                out.add(line)
            }
        }

        return TerminalScreenSnapshot(
            rows = rows,
            cols = cols,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible && effectiveOffset == 0,
            scrollbackLines = scrollback.size,
            isAlternateScreen = isAltScreen,
            grid = out
        )
    }

    private fun processChar(c: Char) {
        when (parseState) {
            ParseState.GROUND -> handleGround(c)
            ParseState.ESC -> handleEsc(c)
            ParseState.CSI -> handleCsi(c)
            ParseState.OSC -> {
                when (c) {
                    '\u0007' -> parseState = ParseState.GROUND
                    '\u001B' -> parseState = ParseState.OSC_ESC
                }
            }
            ParseState.OSC_ESC -> {
                parseState = ParseState.GROUND
            }
            ParseState.DCS -> {
                when (c) {
                    '\u0007' -> parseState = ParseState.GROUND
                    '\u001B' -> parseState = ParseState.DCS_ESC
                }
            }
            ParseState.DCS_ESC -> {
                parseState = ParseState.GROUND
            }
            ParseState.CHARSET -> {
                parseState = ParseState.GROUND
            }
        }
    }

    private fun handleGround(c: Char) {
        when (c) {
            '\u001B' -> parseState = ParseState.ESC
            '\r' -> {
                cursorCol = 0
                pendingWrap = false
            }
            '\n', '\u000B', '\u000C' -> {
                lineFeed()
            }
            '\b' -> {
                pendingWrap = false
                if (cursorCol > 0) cursorCol--
            }
            '\t' -> {
                pendingWrap = false
                val nextStop = ((cursorCol / 8) + 1) * 8
                cursorCol = min(nextStop, cols - 1)
            }
            '\u0007', '\u0000' -> {
                // Bell / NUL ignored visually
            }
            else -> {
                if (c.code >= 0x20) {
                    putPrintableChar(c)
                }
            }
        }
    }

    private fun handleEsc(c: Char) {
        when (c) {
            '[' -> {
                csiBuf.setLength(0)
                parseState = ParseState.CSI
            }
            ']' -> {
                parseState = ParseState.OSC
            }
            'P' -> {
                parseState = ParseState.DCS
            }
            '(', ')', '*', '+' -> {
                parseState = ParseState.CHARSET
            }
            '7' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                parseState = ParseState.GROUND
            }
            '8' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                pendingWrap = false
                parseState = ParseState.GROUND
            }
            'M' -> {
                // Reverse index
                if (cursorRow == scrollTop) {
                    scrollDown(scrollTop, scrollBottom, 1)
                } else if (cursorRow > 0) {
                    cursorRow--
                }
                pendingWrap = false
                parseState = ParseState.GROUND
            }
            'c' -> {
                reset()
                parseState = ParseState.GROUND
            }
            else -> {
                parseState = ParseState.GROUND
            }
        }
    }

    private fun handleCsi(c: Char) {
        // A-H9: ESC inside CSI aborts current CSI cleanly and begins a new ESC sequence
        if (c == '\u001B') {
            csiBuf.setLength(0)
            parseState = ParseState.ESC
            return
        }
        if (c.code in 0x40..0x7E) {
            executeCsi(csiBuf.toString(), c)
            parseState = ParseState.GROUND
        } else {
            if (csiBuf.length < 64) {
                csiBuf.append(c)
            }
        }
    }

    private fun executeCsi(rawParams: String, finalChar: Char) {
        // A-H9: Detect private prefix bytes (?, >, <, =, !). Ignore non-DEC private prefixes so
        // sequences like CSI >4;2m (vim modifyOtherKeys) never get parsed as SGR 0!
        val firstChar = rawParams.firstOrNull()
        if (firstChar == '>' || firstChar == '<' || firstChar == '=' || firstChar == '!') {
            return
        }
        val isPrivate = firstChar == '?'
        val cleanParams = if (isPrivate) rawParams.substring(1) else rawParams
        val params = if (cleanParams.isEmpty()) {
            emptyList()
        } else {
            cleanParams.split(';').map { it.toIntOrNull() ?: 0 }
        }

        fun p(idx: Int, defaultVal: Int): Int {
            val v = params.getOrNull(idx) ?: 0
            return if (v <= 0) defaultVal else v
        }

        if (isPrivate) {
            when (finalChar) {
                'h' -> params.forEach { mode ->
                    when (mode) {
                        25 -> cursorVisible = true
                        1049, 1047, 47 -> switchToAltScreen(true)
                    }
                }
                'l' -> params.forEach { mode ->
                    when (mode) {
                        25 -> cursorVisible = false
                        1049, 1047, 47 -> switchToAltScreen(false)
                    }
                }
            }
            return
        }

        when (finalChar) {
            'A' -> { // CUU - Cursor Up
                cursorRow = max(scrollTop, cursorRow - p(0, 1))
                pendingWrap = false
            }
            'B' -> { // CUD - Cursor Down
                cursorRow = min(scrollBottom, cursorRow + p(0, 1))
                pendingWrap = false
            }
            'C' -> { // CUF - Cursor Forward
                cursorCol = min(cols - 1, cursorCol + p(0, 1))
                pendingWrap = false
            }
            'D' -> { // CUB - Cursor Backward
                cursorCol = max(0, cursorCol - p(0, 1))
                pendingWrap = false
            }
            'E' -> { // CNL - Cursor Next Line
                cursorRow = min(scrollBottom, cursorRow + p(0, 1))
                cursorCol = 0
                pendingWrap = false
            }
            'F' -> { // CPL - Cursor Previous Line
                cursorRow = max(scrollTop, cursorRow - p(0, 1))
                cursorCol = 0
                pendingWrap = false
            }
            'G' -> { // CHA - Cursor Horizontal Absolute
                cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'd' -> { // VPA - Line Position Absolute
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                pendingWrap = false
            }
            'H', 'f' -> { // CUP - Cursor Position
                val r = p(0, 1) - 1
                val c = p(1, 1) - 1
                cursorRow = r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> { // ED - Erase in Display
                val mode = params.getOrNull(0) ?: 0
                eraseDisplay(mode)
            }
            'K' -> { // EL - Erase in Line
                val mode = params.getOrNull(0) ?: 0
                eraseLine(mode)
            }
            'L' -> { // IL - Insert Lines (A-C6: ignore if cursor outside scroll region)
                if (cursorRow in scrollTop..scrollBottom) {
                    scrollDown(cursorRow, scrollBottom, p(0, 1))
                }
            }
            'M' -> { // DL - Delete Lines (A-C6: ignore if cursor outside scroll region)
                if (cursorRow in scrollTop..scrollBottom) {
                    scrollUp(cursorRow, scrollBottom, p(0, 1), recordScrollback = false)
                }
            }
            'S' -> { // SU - Scroll Up (A-H9)
                scrollUp(scrollTop, scrollBottom, p(0, 1), recordScrollback = !isAltScreen && scrollTop == 0)
            }
            'T' -> { // SD - Scroll Down (A-H9)
                scrollDown(scrollTop, scrollBottom, p(0, 1))
            }
            'X' -> { // ECH - Erase Characters without moving cursor (A-H9)
                val n = p(0, 1)
                val line = activeGrid()[cursorRow]
                val endCol = min(cols, cursorCol + n)
                for (c in cursorCol until endCol) {
                    line[c] = blankCellWithBg()
                }
            }
            'b' -> { // REP - Repeat preceding graphic character (A-H9)
                val n = p(0, 1).coerceAtMost(cols * 2)
                repeat(n) { putPrintableChar(lastPrintedChar) }
            }
            'P' -> { // DCH - Delete Characters
                val n = p(0, 1)
                val line = activeGrid()[cursorRow]
                for (c in cursorCol until cols) {
                    line[c] = if (c + n < cols) line[c + n] else blankCellWithBg()
                }
            }
            '@' -> { // ICH - Insert Blank Characters
                val n = p(0, 1)
                val line = activeGrid()[cursorRow]
                for (c in cols - 1 downTo cursorCol) {
                    line[c] = if (c - n >= cursorCol) line[c - n] else blankCellWithBg()
                }
            }
            'r' -> { // DECSTBM - Set Top & Bottom Margins
                val top = (p(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (if (params.size >= 2 && params[1] > 0) params[1] - 1 else rows - 1).coerceIn(top, rows - 1)
                scrollTop = top
                scrollBottom = bottom
                cursorRow = 0
                cursorCol = 0
                pendingWrap = false
            }
            's' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }
            'u' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'm' -> { // SGR - Select Graphic Rendition
                applySgr(if (params.isEmpty()) listOf(0) else params)
            }
        }
    }

    private fun switchToAltScreen(enable: Boolean) {
        if (enable == isAltScreen) return
        if (enable) {
            altSavedCursorRow = cursorRow
            altSavedCursorCol = cursorCol
            altGrid = allocateGrid(rows, cols)
            isAltScreen = true
            cursorRow = 0
            cursorCol = 0
            scrollTop = 0
            scrollBottom = rows - 1
        } else {
            isAltScreen = false
            cursorRow = altSavedCursorRow.coerceIn(0, rows - 1)
            cursorCol = altSavedCursorCol.coerceIn(0, cols - 1)
            scrollTop = 0
            scrollBottom = rows - 1
        }
        pendingWrap = false
    }

    private fun putPrintableChar(c: Char) {
        lastPrintedChar = c
        if (pendingWrap) {
            cursorCol = 0
            lineFeed()
            pendingWrap = false
        }
        val effectiveFg = if (inverse) {
            if (bgArgb == TerminalCell.DEFAULT_BG) 0xFF06090F.toInt() else bgArgb
        } else {
            fgArgb
        }
        val effectiveBg = if (inverse) fgArgb else bgArgb

        activeGrid()[cursorRow][cursorCol] = TerminalCell(
            ch = c,
            fgArgb = effectiveFg,
            bgArgb = effectiveBg,
            bold = bold,
            italic = italic,
            underline = underline
        )
        if (cursorCol == cols - 1) {
            pendingWrap = true
        } else {
            cursorCol++
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            scrollUp(scrollTop, scrollBottom, 1, recordScrollback = !isAltScreen && scrollTop == 0)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollUp(top: Int, bottom: Int, count: Int, recordScrollback: Boolean) {
        // A-C6: Guard invalid scroll region bounds so coerceIn never throws IllegalArgumentException
        if (top < 0 || bottom >= rows || top > bottom) return
        val grid = activeGrid()
        val n = count.coerceIn(1, bottom - top + 1)
        if (recordScrollback) {
            for (i in 0 until n) {
                if (scrollback.size >= maxScrollback) {
                    scrollback.removeFirst()
                }
                scrollback.addLast(grid[top + i].copyOf())
            }
        }
        for (r in top..bottom - n) {
            grid[r] = grid[r + n]
        }
        for (r in bottom - n + 1..bottom) {
            grid[r] = Array(cols) { blankCellWithBg() }
        }
    }

    private fun scrollDown(top: Int, bottom: Int, count: Int) {
        // A-C6: Guard invalid scroll region bounds so coerceIn never throws IllegalArgumentException
        if (top < 0 || bottom >= rows || top > bottom) return
        val grid = activeGrid()
        val n = count.coerceIn(1, bottom - top + 1)
        for (r in bottom downTo top + n) {
            grid[r] = grid[r - n]
        }
        for (r in top until top + n) {
            grid[r] = Array(cols) { blankCellWithBg() }
        }
    }

    private fun eraseDisplay(mode: Int) {
        val grid = activeGrid()
        when (mode) {
            0 -> { // Cursor to end of display
                eraseLine(0)
                for (r in cursorRow + 1 until rows) {
                    grid[r] = Array(cols) { blankCellWithBg() }
                }
            }
            1 -> { // Start of display to cursor
                for (r in 0 until cursorRow) {
                    grid[r] = Array(cols) { blankCellWithBg() }
                }
                eraseLine(1)
            }
            2, 3 -> { // Entire display
                for (r in 0 until rows) {
                    grid[r] = Array(cols) { blankCellWithBg() }
                }
                if (mode == 3 && !isAltScreen) {
                    scrollback.clear()
                }
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val line = activeGrid()[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) line[c] = blankCellWithBg()
            1 -> for (c in 0..cursorCol) line[c] = blankCellWithBg()
            2 -> for (c in 0 until cols) line[c] = blankCellWithBg()
        }
    }

    private fun blankCellWithBg(): TerminalCell = TerminalCell(bgArgb = bgArgb)

    private fun resetSgr() {
        fgArgb = TerminalCell.DEFAULT_FG
        bgArgb = TerminalCell.DEFAULT_BG
        bold = false
        italic = false
        underline = false
        inverse = false
    }

    private fun applySgr(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> resetSgr()
                1 -> bold = true
                3 -> italic = true
                4 -> underline = true
                7 -> inverse = true
                22 -> bold = false
                23 -> italic = false
                24 -> underline = false
                27 -> inverse = false
                in 30..37 -> fgArgb = ANSI_16_PALETTE[code - 30]
                39 -> fgArgb = TerminalCell.DEFAULT_FG
                in 40..47 -> bgArgb = ANSI_16_PALETTE[code - 40]
                49 -> bgArgb = TerminalCell.DEFAULT_BG
                in 90..97 -> fgArgb = ANSI_16_PALETTE[code - 90 + 8]
                in 100..107 -> bgArgb = ANSI_16_PALETTE[code - 100 + 8]
                38, 48 -> {
                    val isFg = code == 38
                    val mode = codes.getOrNull(i + 1)
                    if (mode == 5 && i + 2 < codes.size) {
                        val color = xterm256Color(codes[i + 2])
                        if (isFg) fgArgb = color else bgArgb = color
                        i += 2
                    } else if (mode == 2 && i + 4 < codes.size) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        if (isFg) fgArgb = color else bgArgb = color
                        i += 4
                    }
                }
            }
            i++
        }
    }

    companion object {
        private val ANSI_16_PALETTE = intArrayOf(
            0xFF0A0E14.toInt(), // 0 Black
            0xFFFF5252.toInt(), // 1 Red
            0xFF00E676.toInt(), // 2 Green (NEXA Emerald)
            0xFFFFAB40.toInt(), // 3 Yellow (NEXA Amber)
            0xFF38BDF8.toInt(), // 4 Blue
            0xFFCE93D8.toInt(), // 5 Magenta (NEXA Violet)
            0xFF00E5FF.toInt(), // 6 Cyan (NEXA Cyan)
            0xFFE2E8F0.toInt(), // 7 White
            0xFF64748B.toInt(), // 8 Bright Black (Muted Slate)
            0xFFFF6B6B.toInt(), // 9 Bright Red
            0xFF69F0AE.toInt(), // 10 Bright Green
            0xFFFFD180.toInt(), // 11 Bright Yellow
            0xFF7DD3FC.toInt(), // 12 Bright Blue
            0xFFE1BEE7.toInt(), // 13 Bright Magenta
            0xFF64FFDA.toInt(), // 14 Bright Cyan
            0xFFFFFFFF.toInt()  // 15 Bright White
        )

        fun xterm256Color(index: Int): Int {
            val idx = index.coerceIn(0, 255)
            if (idx < 16) return ANSI_16_PALETTE[idx]
            if (idx >= 232) {
                val gray = 8 + (idx - 232) * 10
                return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
            val cube = idx - 16
            val rIdx = cube / 36
            val gIdx = (cube % 36) / 6
            val bIdx = cube % 6
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            return (0xFF shl 24) or (levels[rIdx] shl 16) or (levels[gIdx] shl 8) or levels[bIdx]
        }

        private fun allocateGrid(rows: Int, cols: Int): Array<Array<TerminalCell>> {
            return Array(rows) { Array(cols) { TerminalCell.BLANK } }
        }

        private fun resizeGrid(
            old: Array<Array<TerminalCell>>,
            newRows: Int,
            newCols: Int
        ): Array<Array<TerminalCell>> {
            val next = allocateGrid(newRows, newCols)
            val copyRows = min(old.size, newRows)
            for (r in 0 until copyRows) {
                val copyCols = min(old[r].size, newCols)
                for (c in 0 until copyCols) {
                    next[r][c] = old[r][c]
                }
            }
            return next
        }

        private fun padOrTrimLine(line: Array<TerminalCell>, targetCols: Int): List<TerminalCell> {
            return if (line.size == targetCols) {
                line.toList()
            } else {
                List(targetCols) { idx -> if (idx < line.size) line[idx] else TerminalCell.BLANK }
            }
        }
    }
}
