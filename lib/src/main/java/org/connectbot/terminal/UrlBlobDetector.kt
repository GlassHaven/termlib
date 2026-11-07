/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

/**
 * Resolves the URL (if any) that covers a tapped cell by treating the terminal
 * screen as a 2D grid and the URL as a **convex, closed** blob of URL-safe
 * cells that is *anchored* on an explicit scheme match (`https://`, `http://`,
 * `ftp://`). Without an anchor the detector refuses to return anything —
 * protecting against false positives where a tap on generic prose would
 * otherwise get turned into a URL by concatenation-through-whitespace.
 *
 * Algorithm:
 *
 * 1. Locate the anchor scheme `https://`/`http://`/`ftp://` inside the same
 *    logical wrapped region as the tap (current row + adjacent rows that
 *    pass the continuation test).
 *
 * 2. Mark structural columns over the visible snapshot: non-URL-safe,
 *    non-whitespace chars that appear at the same column on ≥ 2 adjacent
 *    rows. `│`, `├`, `|`, `+` frames get flagged naturally.
 *
 * 3. Starting at the anchor, walk forward row by row. On each row collect
 *    the URL-safe run. Cross a row boundary only when the next row starts,
 *    after stripping leading whitespace + single-row formatting chars
 *    (non-URL-safe-non-structural) + structural cells, with a URL-safe run
 *    containing `/`.
 *
 * 4. For each row the blob touches, check convex-closedness: the span from
 *    leftmost to rightmost URL-safe cell must contain only URL-safe,
 *    structural, or narrow bridged-whitespace cells — no terminator chars
 *    inside the span.
 *
 * 5. Reconstruct the URL text by reading blob cells in row-major order,
 *    skipping structural cells and collapsing bridged whitespace runs. Match
 *    against [TerminalLine.URL_REGEX]; the match covering the tap's offset
 *    is the result.
 *
 * Intentional non-goals:
 * - No mid-row horizontal whitespace bridging (tool-padding on the same row
 *   terminates the URL — less ambitious than the spec doc's optional rule).
 * - No anchor-less detection; plain-text tokens that happen to be URL-shaped
 *   fall back to the caller's single-line autoDetectedUrls path.
 */
internal class UrlBlobDetector(private val state: TerminalScreenState) {

    private val snapshot get() = state.snapshot
    private val rows = snapshot.rows
    private val cols = snapshot.cols

    // Sources cells from getVisibleLine() rather than snapshot.lines[] so
    // the row indices we work with match the tap coordinates passed in by
    // the gesture handler — which are viewport-relative. When the user has
    // scrolled back, `tapRow = 5` means "the 6th row currently on screen",
    // which corresponds to a different underlying row depending on
    // scrollbackPosition; TerminalScreenState.getVisibleLine does that
    // translation for us.
    private val cellChar = Array(rows) { r ->
        val line = state.getVisibleLine(r)
        CharArray(cols) { c -> line.cells.getOrNull(c)?.char ?: ' ' }
    }

    private val structural: Array<BooleanArray> by lazy { computeStructural() }

    /** See [isSaturatedUrlRow] — a row the wrap filled edge to edge. */
    private fun isSaturatedRow(r: Int): Boolean =
        r in 0 until rows && isSaturatedUrlRow(cols) { cellChar[r][it] }

    /** Entry point. Returns the full URL covering the tap, or null. */
    fun detect(tapRow: Int, tapCol: Int): String? {
        if (tapRow !in 0 until rows || tapCol !in 0 until cols) return null
        if (!cellChar[tapRow][tapCol].isBlobUrlSafe()) return null

        // Find the anchor row: walk upward from tapRow looking for a row
        // whose stripped text contains "https://" etc. At most a few rows
        // back — URL wraps rarely span more than that.
        var anchorRow = -1
        var anchorStartCol = -1
        // How far up to look. MAX_UPWARD_ROWS covers the ambiguous cases
        // (decorated or short continuation rows); beyond that, keep climbing
        // while the row ABOVE is URL-safe in every column, because such a row
        // was run through by the wrap and cannot be prose. Tapping the 7th row
        // of a 9-row wrap used to find no anchor at all and open nothing.
        var searchFloor = tapRow
        while (searchFloor > 0 && isSaturatedRow(searchFloor - 1)) searchFloor--
        searchFloor = minOf(searchFloor, tapRow - MAX_UPWARD_ROWS).coerceAtLeast(0)
        for (r in tapRow downTo searchFloor) {
            val (found, startCol) = findAnchorInRow(r)
            if (found) {
                anchorRow = r
                anchorStartCol = startCol
                break
            }
            // Upward walk stops once we've searched enough rows back.
        }
        if (anchorRow < 0) return null

        // Walk forward from anchorRow collecting rows that continue the blob.
        // Each continuation returns its start column — either the
        // same-column-as-anchor content (frame wrap) or the first URL-safe
        // col after prefix strip.  We accumulate (row, startCol) pairs so
        // the rowSpans computation below doesn't have to re-derive start.
        val rowStarts = mutableMapOf(anchorRow to anchorStartCol)
        val touchedRows = mutableListOf(anchorRow)
        var lastRow = anchorRow
        while (lastRow < rows - 1) {
            val nextStart = continuationStartCol(
                anchorRow = anchorRow,
                anchorStartCol = anchorStartCol,
                prevRow = lastRow,
                curRow = lastRow + 1,
            ) ?: break
            lastRow++
            touchedRows.add(lastRow)
            rowStarts[lastRow] = nextStart
        }
        if (tapRow !in touchedRows) return null

        // Compute per-row spans from the collected starts.
        val rowSpans = mutableMapOf<Int, IntRange>()
        for (r in touchedRows) {
            val start = rowStarts[r] ?: continue
            val end = findUrlSafeRunEnd(r, start) ?: continue
            rowSpans[r] = start..end
        }
        if (tapRow !in rowSpans) return null

        // Convex-closed: each row's span must contain only URL-safe,
        // structural, or narrow bridged-whitespace cells.
        val validRows = rowSpans.filter { (r, span) -> isSpanConvexClosed(r, span) }
        if (tapRow !in validRows) return null
        // Tap must lie inside its row's span — guards against taps on prose
        // before or after the URL on the same row.
        if (tapCol !in validRows[tapRow]!!) return null

        // Keep only rows that are contiguous with the anchor row.
        val ordered = validRows.keys.sorted()
        val contiguous = ordered.filter { r -> r == anchorRow || r in anchorRow..(anchorRow + ordered.size) }
            .takeWhile { it - (validRows.keys.min()) <= touchedRows.size }
        // Simpler: just keep rows [anchorRow..lastContiguousValid].
        val kept = buildList {
            add(anchorRow)
            var prev = anchorRow
            for (r in ordered) {
                if (r <= anchorRow) continue
                if (r - prev > 1) break
                if (r !in validRows) break
                add(r)
                prev = r
            }
        }
        if (tapRow !in kept) return null

        // Reconstruct
        val sb = StringBuilder()
        var tapOffset = 0
        for (r in kept) {
            val span = validRows[r]!!
            if (r == tapRow) {
                tapOffset = sb.length + offsetWithinRow(r, span, tapCol)
            }
            sb.append(readRowSpan(r, span))
        }

        val full = sb.toString()
        val match = TerminalLine.URL_REGEX.findAll(full).firstOrNull { m ->
            tapOffset in m.range
        }
        return match?.value
    }

    // ------------------------------------------------------------------
    // Structural column detection
    // ------------------------------------------------------------------

    private fun computeStructural(): Array<BooleanArray> {
        val mask = Array(rows) { BooleanArray(cols) }
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                val ch = cellChar[r][c]
                if (ch.isBlobUrlSafe() || ch.isWhitespace()) {
                    r++
                    continue
                }
                var runEnd = r
                while (runEnd + 1 < rows && cellChar[runEnd + 1][c] == ch) runEnd++
                if (runEnd - r >= 1) {
                    for (rr in r..runEnd) mask[rr][c] = true
                }
                r = runEnd + 1
            }
        }
        return mask
    }

    // ------------------------------------------------------------------
    // Row-level helpers
    // ------------------------------------------------------------------

    private fun findAnchorInRow(r: Int): Pair<Boolean, Int> {
        val rowText = rowAsString(r)
        for (scheme in ANCHORS) {
            val idx = rowText.indexOf(scheme)
            if (idx >= 0) return true to idx
        }
        return false to -1
    }

    private fun rowAsString(r: Int): String = String(cellChar[r])

    /**
     * Row r+1 is a continuation of row r iff after stripping row r+1's
     * leading run of whitespace / structural / single-row non-URL-safe
     * formatting chars, the remaining content starts with a URL-safe
     * character and the first URL-safe run contains `/`.
     *
     * Row r must end with a URL-safe trailing token (before terminal-pad
     * trailing whitespace).
     */
    /**
     * Whether `curRow` logically continues a URL that starts at
     * (`anchorRow`, `anchorStartCol`) and passes through `prevRow`.
     *
     * Continuation accepted when the previous row's URL-safe run (starting
     * from the anchor col if prevRow == anchorRow, else the first URL-safe
     * col after prefix stripping) ends in one of three geometries:
     *
     *  - **Margin wrap** — URL-safe run reaches the last column of the row
     *    (`cols - 1`).  The terminal hard-wrapped the URL.  The
     *    continuation row's URL-safe content is accepted unconditionally.
     *
     *  - **Frame wrap** — URL-safe run ends before a structural column.
     *    The URL is inside a markdown table / box-drawn panel that frames
     *    it; the next row continues within the same frame.  The
     *    continuation row's first URL-safe run must contain `/`.
     *
     *  - **Short-row wrap** — URL-safe run ends in trailing whitespace
     *    (no structural frame).  The continuation row's first URL-safe
     *    run must contain `/` (strong URL signal to accept the join).
     */

    /**
     * Returns the column on `curRow` at which the URL continues (if it does),
     * or null if `curRow` is not a continuation of the URL starting at
     * (`anchorRow`, `anchorStartCol`) that passes through `prevRow`.
     */
    private fun continuationStartCol(
        anchorRow: Int,
        anchorStartCol: Int,
        prevRow: Int,
        curRow: Int,
    ): Int? {
        if (prevRow < 0 || curRow >= rows) return null

        val prevUrlStart = if (prevRow == anchorRow) {
            anchorStartCol
        } else {
            firstUrlSafeColAfterPrefix(prevRow) ?: return null
        }
        val prevUrlEnd = findUrlSafeRunEnd(prevRow, prevUrlStart) ?: return null
        if (!cellChar[prevRow][prevUrlEnd].isBlobUrlSafe()) return null

        // Frame bounds on prevRow: leftmost structural col strictly < anchor,
        // rightmost structural col strictly > prevUrlEnd (reached via whitespace).
        val leftFrame = (0 until prevUrlStart).lastOrNull { structural[prevRow][it] } ?: -1
        var scan = prevUrlEnd + 1
        while (scan < cols && cellChar[prevRow][scan].isWhitespace()) scan++
        val rightFrame = if (scan < cols && structural[prevRow][scan]) scan else cols

        // Where to look for the continuation's start col.
        val curStart = if (leftFrame >= 0 || rightFrame < cols) {
            firstUrlSafeColInRange(curRow, leftFrame + 1, rightFrame) ?: return null
        } else {
            firstUrlSafeColAfterPrefix(curRow) ?: return null
        }

        // A row that carries its own URL scheme is a NEW anchor, never a
        // continuation — without this, a screen listing several URLs on
        // adjacent rows glues them into one mega-URL (the regex bridges
        // the rows because `=`/`#` are URL-safe characters). Checked before
        // the margin-wrap acceptance so a full-width URL row doesn't glue
        // to an unrelated URL printed directly below it.
        val curText = rowAsString(curRow)
        if (curText.contains("://")) return null

        // Margin wrap — continuation accepted with no structural-char
        // requirement.
        if (prevUrlEnd == cols - 1) return curStart

        // Non-margin wrap: if the URL on prevRow did not reach the right
        // edge (or right frame boundary) because other text followed it on
        // the same row, it ended mid-line and cannot wrap onto curRow.
        val prevCutOff = (prevUrlEnd + 1 until rightFrame).all { cellChar[prevRow][it].isWhitespace() }
        if (!prevCutOff) return null

        // URL-structural signal required for the non-margin cases: the
        // continuation's first URL-safe run must contain any of `/#?&=` —
        // i.e. a path separator, fragment marker, or query structure. Any
        // of those is strong evidence the row continues a URL rather than
        // natural prose (which essentially never contains these runs of
        // URL-safe glyphs). Required because wrap targets like
        // `.../issues/106#issuecomment-1234` land their fragment on the
        // continuation row with no `/` of its own. The run must also carry
        // at least one alphanumeric: a run of pure punctuation (`==`, `--`
        // markdown underlines, separator rows) is decoration, not a URL
        // fragment, and accepting it chains unrelated rows together.
        var sawStructural = false
        var sawAlnum = false
        var runEnd = curStart
        while (runEnd < cols && cellChar[curRow][runEnd].isBlobUrlSafe()) {
            if (cellChar[curRow][runEnd] in "/#?&=") sawStructural = true
            if (cellChar[curRow][runEnd].isLetterOrDigit()) sawAlnum = true
            runEnd++
        }
        if (sawStructural && !sawAlnum) sawStructural = false

        val runLen = runEnd - curStart
        val roomOnPrevRow = cols - (prevUrlEnd + 1)
        val inFrame = leftFrame >= 0 || rightFrame < cols

        // Haven #791: when curRow starts at column 0 with no leading
        // indentation, prefix decoration, or structural frame, and the URL
        // run on curRow would have fitted in the remaining columns of
        // prevRow, nothing forced a line wrap — the line break was an
        // explicit newline (`https://example.com\n/1`). Only accept such a
        // short unindented row at col 0 when prevRow ended with an
        // incomplete URL delimiter (`/?&=#`, excluding `//` across the
        // newline) or curRow starts with a query/fragment marker (`?&#`).
        if (!inFrame && curStart == 0 && runLen <= roomOnPrevRow) {
            val prevLastChar = cellChar[prevRow][prevUrlEnd]
            val curFirstChar = cellChar[curRow][curStart]
            val prevEndsWithDelimiter = prevLastChar in "/?&=#" &&
                !(prevLastChar == '/' && curFirstChar == '/')
            val nextStartsWithQueryOrFragment = curFirstChar in "?&#"
            if (!prevEndsWithDelimiter && !nextStartsWithQueryOrFragment) {
                return null
            }
        }

        if (!sawStructural) {
            // Fallback for slash-less wrap tails: a short URL-safe run alone on
            // an otherwise-blank line is the shape of a hanging-indent wrap
            // (e.g. the trailing "t" of a ".git" URL that Claude Code broke
            // across lines, where the fragment carries no /#?&= of its own).
            // Indented prose is excluded — it carries more words, so the rest
            // of the line is not blank.
            val restBlank = (runEnd until cols).all { cellChar[curRow][it].isWhitespace() }
            val hangingIndentTail = curStart > 0 && restBlank && runLen in 1..(cols / 2)
            // Second fallback, for a break inside a filename with prose
            // following it on the same row — the shape above requires the tail
            // to sit alone on its line, which a wrapped paragraph never does.
            //
            // Two gates keep it off ordinary prose. The URL must run to the
            // previous row's last printed column, so a URL with text after it
            // on its own row can never reach here. And the tail must not have
            // *fitted* on that row: a wrap is a line that ran out of room, so
            // if the tail would have gone on the end of the previous row, the
            // line ended for some other reason and the next row is its own
            // thought. Without that second gate this glued real output
            // together — `…/foo/bar` above `README.md and 3 other files`
            // became `…/foo/barREADME.md`.
            val fileTail = runLen > roomOnPrevRow &&
                looksLikeWrappedFileTail(String(cellChar[curRow], curStart, runEnd - curStart))
            if (!hangingIndentTail && !fileTail) return null
        }

        return curStart
    }

    /** Like [firstUrlSafeColAfterPrefix] but restricted to the half-open column range [start, end). */
    private fun firstUrlSafeColInRange(r: Int, start: Int, end: Int): Int? {
        val lo = start.coerceAtLeast(0)
        val hi = end.coerceAtMost(cols)
        for (c in lo until hi) {
            val ch = cellChar[r][c]
            if (ch.isWhitespace()) continue
            if (structural[r][c]) continue
            if (!ch.isBlobUrlSafe()) continue // single-row formatting
            return c
        }
        return null
    }

    /**
     * Skip leading whitespace + structural + single-row non-URL-safe
     * formatting chars on row `r`, returning the first URL-safe column,
     * or null if nothing URL-safe follows.
     */
    private fun firstUrlSafeColAfterPrefix(r: Int): Int? {
        for (c in 0 until cols) {
            val ch = cellChar[r][c]
            if (ch.isWhitespace()) continue
            if (structural[r][c]) continue
            if (!ch.isBlobUrlSafe()) continue // single-row formatting (⎿, ●, etc.)
            return c
        }
        return null
    }

    private fun findUrlSafeRunEnd(r: Int, start: Int): Int? {
        var c = start
        var last = -1
        while (c < cols) {
            val ch = cellChar[r][c]
            if (ch.isBlobUrlSafe()) {
                last = c
                c++
                continue
            }
            // Structural columns count as pass-through for span computation;
            // the URL-safe run can resume after them.
            if (structural[r][c]) {
                c++
                continue
            }
            break
        }
        return if (last >= start) last else null
    }

    private fun isSpanConvexClosed(r: Int, span: IntRange): Boolean {
        var c = span.first
        while (c <= span.last) {
            val ch = cellChar[r][c]
            if (ch.isBlobUrlSafe() || structural[r][c]) {
                c++
                continue
            }
            return false // terminator / mid-row whitespace inside the hull
        }
        return true
    }

    private fun readRowSpan(r: Int, span: IntRange): String {
        val sb = StringBuilder()
        for (c in span) {
            if (structural[r][c]) continue
            val ch = cellChar[r][c]
            if (ch.isBlobUrlSafe()) sb.append(ch)
        }
        return sb.toString()
    }

    private fun offsetWithinRow(r: Int, span: IntRange, tapCol: Int): Int {
        if (tapCol < span.first) return 0
        var offset = 0
        var c = span.first
        while (c < tapCol && c <= span.last) {
            if (!structural[r][c] && cellChar[r][c].isBlobUrlSafe()) offset++
            c++
        }
        return offset
    }

    /**
     * Blob-local URL-safeness: like [Char.isUrlSafe] but excludes balanced-
     * punctuation characters that tool output wraps around URLs — `(` `)`
     * `[` `]` `{` `}`. Within the blob detector we prefer to treat these
     * as terminators even though [TerminalLine.URL_REGEX] allows them
     * through, because log output like "Bash(curl URL)" is much more
     * common than Wikipedia-style URLs containing balanced parens.
     */
    private fun Char.isBlobUrlSafe(): Boolean = isLetterOrDigit() || this in "/:@!$&'*+,;=-._~%?#"

    companion object {
        private val ANCHORS = listOf("https://", "http://", "ftp://")
        private const val MAX_UPWARD_ROWS = 4
    }
}
