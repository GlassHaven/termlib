/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Interface for controlling text selection in the terminal.
 * This allows external components (UI chrome, keyboard handlers, accessibility) to control selection.
 */
interface SelectionController {
    /**
     * Check if selection mode is currently active.
     */
    val isSelectionActive: Boolean

    /**
     * Current scrollback view offset: how many lines the viewport is scrolled
     * up from the live bottom (0 = showing the newest output). Consumers that
     * correlate selection rows with screen-only line lists (Haven's smart
     * copy) must treat this as the coordinate-space mismatch signal: when it
     * is non-zero, selection rows resolve against scrollback, not the visible
     * screen.
     */
    val scrollbackPosition: Int get() = 0

    /**
     * Start selection mode at the current cursor position or center of screen.
     * @param mode The selection mode to use (CHARACTER, WORD, or LINE)
     */
    fun startSelection(mode: SelectionMode = SelectionMode.CHARACTER)

    /**
     * Toggle selection mode on/off. If off, turns it on. If on, turns it off.
     */
    fun toggleSelection()

    /**
     * Move the selection cursor up by one row.
     */
    fun moveSelectionUp()

    /**
     * Move the selection cursor down by one row.
     */
    fun moveSelectionDown()

    /**
     * Move the selection cursor left by one column.
     */
    fun moveSelectionLeft()

    /**
     * Move the selection cursor right by one column.
     */
    fun moveSelectionRight()

    /**
     * Toggle between CHARACTER, WORD, and LINE selection modes.
     */
    fun toggleSelectionMode()

    /**
     * Set the selection mode directly.
     */
    fun setSelectionMode(mode: SelectionMode)

    /**
     * Select all retained scrollback and current screen text.
     */
    fun selectAll()

    /** Select only the rows currently displayed in the terminal viewport. */
    fun selectAllVisible() = selectAll()

    /**
     * Finish the selection (stop extending it, but keep it active for copying).
     */
    fun finishSelection()

    /**
     * Copy the selected text to clipboard and clear the selection.
     * @return The selected text, or empty string if no selection
     */
    fun copySelection(): String

    /**
     * Clear the selection without copying.
     */
    fun clearSelection()

    /**
     * Update the start anchor of the selection to the given cell.
     */
    fun updateSelectionStart(row: Int, col: Int)

    /**
     * Update the end anchor of the selection to the given cell.
     */
    fun updateSelectionEnd(row: Int, col: Int)

    /**
     * Get the current selection range as (startRow, startCol, endRow, endCol),
     * or null if no selection is active.
     */
    fun getSelectionRange(): SelectionRange?

    /**
     * Returns the currently selected text, with soft-wrapped lines rejoined
     * using libvterm's authoritative `softWrapped` flag (no clipboard write,
     * no selection clear). Returns "" when nothing is selected.
     *
     * Default implementation returns "" so existing test doubles compile;
     * the real implementation in [Terminal] forwards to
     * [SelectionManager.getSelectedText] with the live snapshot and
     * scrollback position. Callers that need the selection's logical text
     * (smartCopy, copy-button handlers, OSC 52 emitters) should prefer this
     * over re-extracting from the snapshot themselves — the column-length
     * heuristic for "did this line wrap?" is unreliable when row text is
     * NUL-padded or lines exactly fill the viewport.
     */
    fun getSelectedText(): String = ""

    /**
     * Returns the text of each currently visible viewport row (`0 until rows`),
     * accounting for [scrollbackPosition]. Default returns an empty list so
     * existing test doubles compile.
     */
    fun getVisibleLineTexts(): List<String> = emptyList()

    /**
     * Shift the selection's start anchor's row by [delta]. Used by the
     * Compose gesture handler during drag-extend with edge-zone
     * auto-scroll (see Terminal.kt GestureType.Selection): each scroll
     * step is paired with a `+1` (top edge) or `-1` (bottom edge) shift
     * so the anchor tracks its logical content line while the viewport
     * scrolls under the finger. Also reachable from the MCP transport
     * via `drag_selection_to`, which composes one step of this with
     * [ScrollController.scrollBy] per requested logical-row of scroll.
     *
     * No clamping — `range.startRow` may legitimately go outside
     * `[0, rows-1]` while the anchor's content has scrolled off-viewport
     * on either side. [SelectionManager.getSelectedText] resolves rows
     * outside the viewport via `getSnapshotLine`, so text extraction
     * stays correct.
     *
     * Default implementation is a no-op so existing test doubles compile.
     */
    fun shiftSelectionStartByRows(delta: Int) {}
}

sealed class SelectionMode {
    data object NONE : SelectionMode()
    data object CHARACTER : SelectionMode()
    data object WORD : SelectionMode()
    data object LINE : SelectionMode()
}

data class SelectionRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
    /**
     * The multiplexer pane the selection is confined to, or null for the
     * whole terminal width. Rows then span [PaneRect.left]..[PaneRect.right]
     * rather than the full line. See [detectPane].
     */
    val pane: PaneRect? = null,
) {
    fun contains(row: Int, col: Int): Boolean {
        val minRow = minOf(startRow, endRow)
        val maxRow = maxOf(startRow, endRow)

        if (row !in minRow..maxRow) return false
        if (pane != null && col !in pane.left..pane.right) return false

        if (startRow == endRow) {
            val minCol = minOf(startCol, endCol)
            val maxCol = maxOf(startCol, endCol)
            return col in minCol..maxCol
        }

        return when (row) {
            minRow -> col >= if (startRow < endRow) startCol else endCol
            maxRow -> col <= if (startRow < endRow) endCol else startCol
            else -> true
        }
    }

    fun getStartPosition(): Pair<Int, Int> {
        if (startRow == endRow) return Pair(startRow, minOf(startCol, endCol))
        if (startRow < endRow) return Pair(startRow, startCol)
        return Pair(endRow, endCol)
    }

    fun getEndPosition(): Pair<Int, Int> {
        if (startRow == endRow) return Pair(startRow, maxOf(startCol, endCol))
        if (startRow < endRow) return Pair(endRow, endCol)
        return Pair(startRow, startCol)
    }
}

internal class SelectionManager {
    var mode by mutableStateOf<SelectionMode>(SelectionMode.NONE)
        private set

    var selectionRange by mutableStateOf<SelectionRange?>(null)
        private set

    var isSelecting by mutableStateOf(false)
        private set

    /**
     * The pane found around the point the current selection started from,
     * kept while the user widens the selection to the whole screen so it can
     * be narrowed back. Null when the press was not inside a bordered pane.
     */
    var detectedPane by mutableStateOf<PaneRect?>(null)
        private set

    /** Restores a prior range — the touch wobble filter rolls back to this at lift. */
    internal fun restoreSelectionRange(range: SelectionRange) {
        selectionRange = range
    }

    fun startSelection(
        row: Int,
        col: Int,
        cols: Int,
        mode: SelectionMode = SelectionMode.CHARACTER,
        snapshot: TerminalSnapshot? = null,
        scrollbackPosition: Int = 0,
    ) {
        this.mode = mode
        isSelecting = true
        detectedPane = snapshot?.let { detectPaneAt(it, row, col, scrollbackPosition) }
        selectionRange = SelectionRange(row, col, row, col, pane = detectedPane)
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    /**
     * Switch between the pane the selection started in and the whole screen.
     * Narrowing pulls both anchors back inside the pane. A no-op when the
     * selection did not start inside a pane.
     */
    fun togglePaneConfinement(cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        val range = selectionRange ?: return
        val pane = detectedPane ?: return
        selectionRange = if (range.pane != null) {
            range.copy(pane = null)
        } else {
            range.copy(pane = pane).clampedToPane()
        }
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    /** Keep both anchors inside [SelectionRange.pane], when there is one. */
    private fun SelectionRange.clampedToPane(): SelectionRange = clampedStartToPane().clampedEndToPane()

    // Each update path clamps only the anchor it moves. Re-clamping the other
    // one would undo [shiftSelectionStartByRows]: during a drag with edge
    // auto-scroll the start anchor legitimately goes above the viewport.

    private fun SelectionRange.clampedStartToPane(): SelectionRange {
        val p = pane ?: return this
        return copy(startRow = p.clampRow(startRow), startCol = startCol.coerceIn(p.left, p.right))
    }

    private fun SelectionRange.clampedEndToPane(): SelectionRange {
        val p = pane ?: return this
        return copy(endRow = p.clampRow(endRow), endCol = endCol.coerceIn(p.left, p.right))
    }

    /**
     * Rows above the viewport are scrollback. When the pane starts at the top
     * of the screen, those lines scrolled out of it, so they stay selectable;
     * otherwise rows stop at the pane's top and bottom.
     */
    private fun PaneRect.clampRow(row: Int): Int = if (top == 0 && row < 0) row else row.coerceIn(top, bottom)

    private fun detectPaneAt(snapshot: TerminalSnapshot, row: Int, col: Int, scrollbackPosition: Int): PaneRect? = detectPane(snapshot.rows, snapshot.cols, row, col) { r, c ->
        getSnapshotLine(snapshot, r, scrollbackPosition)?.cellAtColumn(c)?.char
    }

    fun updateSelection(row: Int, col: Int) {
        if (!isSelecting) return

        val range = selectionRange ?: return
        selectionRange = range.copy(endRow = row, endCol = col).clampedEndToPane()
    }

    fun updateSelectionStart(row: Int, col: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(startRow = row, startCol = col).clampedStartToPane()
    }

    fun updateSelectionEnd(row: Int, col: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(endRow = row, endCol = col).clampedEndToPane()
    }

    fun moveSelectionUp(maxRow: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point up
            val newRow = (range.endRow - 1).coerceAtLeast(0)
            selectionRange = range.copy(endRow = newRow).clampedEndToPane()
        } else {
            // After selection is finished, move both start and end up
            val newStartRow = (range.startRow - 1).coerceAtLeast(0)
            val newEndRow = (range.endRow - 1).coerceAtLeast(0)
            selectionRange = range.copy(startRow = newStartRow, endRow = newEndRow).clampedToPane()
        }
    }

    fun moveSelectionDown(maxRow: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point down
            val newRow = (range.endRow + 1).coerceAtMost(maxRow - 1)
            selectionRange = range.copy(endRow = newRow).clampedEndToPane()
        } else {
            // After selection is finished, move both start and end down
            val newStartRow = (range.startRow + 1).coerceAtMost(maxRow - 1)
            val newEndRow = (range.endRow + 1).coerceAtMost(maxRow - 1)
            selectionRange = range.copy(startRow = newStartRow, endRow = newEndRow).clampedToPane()
        }
    }

    fun moveSelectionLeft(maxCol: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point left
            val newCol = (range.endCol - 1).coerceAtLeast(0)
            selectionRange = range.copy(endCol = newCol).clampedEndToPane()
        } else {
            // After selection is finished, move both start and end left
            val newStartCol = (range.startCol - 1).coerceAtLeast(0)
            val newEndCol = (range.endCol - 1).coerceAtLeast(0)
            selectionRange = range.copy(startCol = newStartCol, endCol = newEndCol).clampedToPane()
        }
    }

    fun moveSelectionRight(maxCol: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point right
            val newCol = (range.endCol + 1).coerceAtMost(maxCol - 1)
            selectionRange = range.copy(endCol = newCol).clampedEndToPane()
        } else {
            // After selection is finished, move both start and end right
            val newStartCol = (range.startCol + 1).coerceAtMost(maxCol - 1)
            val newEndCol = (range.endCol + 1).coerceAtMost(maxCol - 1)
            selectionRange = range.copy(startCol = newStartCol, endCol = newEndCol).clampedToPane()
        }
    }

    fun endSelection() {
        isSelecting = false
        normalizeToReadingOrder()
    }

    /**
     * Ensure `start` is the earlier endpoint in reading order (row-major:
     * top-left-most character). During a drag the range is kept in gesture
     * order — start = where the finger went down — because the update/drag
     * logic addresses the moving end by name. Once the gesture commits,
     * though, the user-facing meaning of the handles is positional: a
     * left-to-right reader expects the "start" handle on the earlier
     * character. Called from [endSelection]; harmless when already ordered.
     */
    internal fun normalizeToReadingOrder() {
        val r = selectionRange ?: return
        if (r.startRow > r.endRow || (r.startRow == r.endRow && r.startCol > r.endCol)) {
            selectionRange = SelectionRange(r.endRow, r.endCol, r.startRow, r.startCol, pane = r.pane)
        }
    }

    fun clearSelection() {
        mode = SelectionMode.NONE
        selectionRange = null
        isSelecting = false
        detectedPane = null
    }

    fun toggleMode(cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        mode = when (mode) {
            SelectionMode.CHARACTER -> SelectionMode.WORD
            SelectionMode.WORD -> SelectionMode.LINE
            SelectionMode.LINE -> SelectionMode.CHARACTER
            SelectionMode.NONE -> SelectionMode.CHARACTER
        }

        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun setMode(newMode: SelectionMode, cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        mode = newMode
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun selectAll(
        rows: Int,
        cols: Int,
        scrollbackSize: Int = 0,
        scrollbackPosition: Int = 0,
    ) {
        val sbSize = scrollbackSize.coerceAtLeast(0)
        val sbPos = scrollbackPosition.coerceAtLeast(0)
        mode = SelectionMode.CHARACTER
        isSelecting = false
        detectedPane = null
        selectionRange = SelectionRange(
            startRow = sbPos - sbSize,
            startCol = 0,
            endRow = sbPos + (rows - 1).coerceAtLeast(0),
            endCol = (cols - 1).coerceAtLeast(0),
        )
    }

    fun selectAllVisible(rows: Int, cols: Int) {
        mode = SelectionMode.CHARACTER
        isSelecting = false
        detectedPane = null
        selectionRange = SelectionRange(
            startRow = 0,
            startCol = 0,
            endRow = (rows - 1).coerceAtLeast(0),
            endCol = (cols - 1).coerceAtLeast(0),
        )
    }

    /**
     * Shift the start anchor's row by [delta] (positive = downward,
     * negative = upward in the viewport). Used by the gesture handler
     * during drag-select when auto-scrolling near the edge: as the
     * viewport scrolls, the anchor's row index needs to track its
     * logical content line so the selection stays anchored on the
     * line the user originally pressed (issue #94).
     *
     * No clamping — callers are expected to keep the column in range,
     * but row may legitimately go outside `[0, rows-1]` while the
     * anchor's content has scrolled off-viewport on either side.
     * Selection extraction still resolves correctly because
     * [SelectionManager.getSelectedText] passes the row through
     * [getSnapshotLine], which handles rows beyond the viewport.
     */
    fun shiftSelectionStartByRows(delta: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(startRow = range.startRow + delta)
    }

    /**
     * Shift BOTH anchors by [delta]. Used by the Compose-side scroll
     * observer to keep an existing (non-actively-extending) selection
     * anchored on its logical lines as the viewport scrolls under it —
     * the desktop-terminal behaviour where scrolling preserves the
     * selection. During an active drag-extend ([isSelecting] == true)
     * the gesture handler manages the start anchor explicitly via
     * [shiftSelectionStartByRows], so this should NOT be called in
     * that case (it would double-shift the start anchor).
     */
    fun shiftSelectionByRows(delta: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(
            startRow = range.startRow + delta,
            endRow = range.endRow + delta,
        )
    }

    /**
     * Clamps the selection range to the given dimensions.
     * Useful when the terminal is resized.
     */
    fun clampToDimensions(rows: Int, cols: Int, scrollbackPosition: Int = 0) {
        val range = selectionRange ?: return
        val maxRow = scrollbackPosition.coerceAtLeast(0) + (rows - 1).coerceAtLeast(0)
        val maxCol = (cols - 1).coerceAtLeast(0)
        val newStartRow = range.startRow.coerceAtMost(maxRow)
        val newEndRow = range.endRow.coerceAtMost(maxRow)
        val newStartCol = range.startCol.coerceAtMost(maxCol)
        val newEndCol = range.endCol.coerceAtMost(maxCol)

        if (newStartRow != range.startRow || newEndRow != range.endRow ||
            newStartCol != range.startCol || newEndCol != range.endCol
        ) {
            selectionRange = SelectionRange(newStartRow, newStartCol, newEndRow, newEndCol, pane = range.pane)
        }
    }

    internal fun adjustSelectionForMode(cols: Int, snapshot: TerminalSnapshot?, scrollbackPosition: Int = 0) {
        val range = selectionRange ?: return

        when (mode) {
            SelectionMode.LINE -> {
                selectionRange = range.copy(
                    startCol = range.pane?.left ?: 0,
                    endCol = range.pane?.right ?: (cols - 1),
                )
            }

            SelectionMode.WORD -> {
                if (snapshot != null) {
                    val startLine = getSnapshotLine(snapshot, range.startRow, scrollbackPosition)
                    val endLine = getSnapshotLine(snapshot, range.endRow, scrollbackPosition)

                    val pane = range.pane
                    if (startLine != null && endLine != null && pane != null) {
                        // Pane anchors are screen columns: see findWordBoundariesInPane.
                        selectionRange = range.copy(
                            startCol = findWordBoundariesInPane(startLine, range.startCol, pane).first,
                            endCol = findWordBoundariesInPane(endLine, range.endCol, pane).second,
                        )
                    } else if (startLine != null && endLine != null) {
                        val (newStartCol, _) = findWordBoundaries(startLine, range.startCol)
                        val (_, newEndCol) = findWordBoundaries(endLine, range.endCol)

                        selectionRange = range.copy(
                            startCol = newStartCol,
                            endCol = newEndCol,
                        )
                    }
                }
            }

            SelectionMode.CHARACTER, SelectionMode.NONE -> {
                // No adjustment needed
            }
        }
    }

    /**
     * Resolves a viewport-relative `row` (i.e., the row coordinate emitted by
     * the gesture handler at tap-time) to the underlying [TerminalLine].
     *
     * Visible rows in `[0, scrollbackPosition)` show scrollback, and visible
     * rows in `[scrollbackPosition, rows)` show the top portion of the current
     * screen. `row` may legitimately fall outside `[0, rows)` when
     * [shiftSelectionStartByRows] has tracked an anchor whose content has
     * scrolled off the viewport during a drag-extend with edge auto-scroll
     * (issue #94). In that case the row still maps to a valid scrollback or
     * screen line via the unified index formula below, so we use it for every
     * scrollbackPosition (including 0 — the live-tail case where a negative
     * `row` points into the most recent scrollback).
     */
    private fun getSnapshotLine(snapshot: TerminalSnapshot, row: Int, scrollbackPosition: Int = 0): TerminalLine? {
        val sbPos = scrollbackPosition.coerceAtLeast(0)
        val actualIndex = snapshot.scrollback.size - sbPos + row
        return if (actualIndex < snapshot.scrollback.size) {
            snapshot.scrollback.getOrNull(actualIndex)
        } else {
            snapshot.lines.getOrNull(actualIndex - snapshot.scrollback.size)
        }
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private fun findWordBoundaries(line: TerminalLine, col: Int): Pair<Int, Int> = findWordBoundaries(line.cells, col)

    /**
     * [findWordBoundaries] for a selection confined to [pane]: [column] and the
     * result are screen columns, not cell indices (a wide glyph is one cell
     * over two columns), and only the cells starting inside the pane are
     * searched, so the trailing-whitespace fallback cannot reach a word in the
     * neighbouring pane or sidebar. The end is the start column of the last
     * cell, which is how pane ranges address a cell.
     */
    private fun findWordBoundariesInPane(line: TerminalLine, column: Int, pane: PaneRect): Pair<Int, Int> {
        val starts = ArrayList<Int>()
        val cells = ArrayList<TerminalLine.Cell>()
        var at = 0
        for (cell in line.cells) {
            if (at > pane.right) break
            if (at >= pane.left) {
                starts += at
                cells += cell
            }
            at += cell.width
        }
        if (cells.isEmpty()) return Pair(column, column)
        val index = starts.indexOfLast { it <= column }.coerceAtLeast(0)
        val (start, end) = findWordBoundaries(cells, index)
        return Pair(starts[start], starts[end])
    }

    private fun findWordBoundaries(cells: List<TerminalLine.Cell>, col: Int): Pair<Int, Int> {
        if (cells.isEmpty()) return Pair(0, 0)
        val safeCol = col.coerceIn(0, cells.lastIndex)

        // If the touch is in trailing whitespace with no word to the right, snap to the last word.
        if (!isWordChar(cells[safeCol].char)) {
            val lastWordEnd = cells.indices.lastOrNull { isWordChar(cells[it].char) }
            if (lastWordEnd != null && lastWordEnd < safeCol) {
                var start = lastWordEnd
                while (start > 0 && isWordChar(cells[start - 1].char)) start--
                return Pair(start, lastWordEnd)
            }
        }

        val startChar = cells[safeCol].char
        val targetingWord = isWordChar(startChar)

        var start = safeCol
        while (start > 0 && isWordChar(cells[start - 1].char) == targetingWord) {
            start--
        }

        var end = safeCol
        while (end < cells.size - 1 && isWordChar(cells[end + 1].char) == targetingWord) {
            end++
        }

        return Pair(start, end)
    }

    private fun isBlankCell(cell: TerminalLine.Cell): Boolean = !cell.isImagePlaceholder && cell.renderableText() == null

    private fun lastContentCol(line: TerminalLine): Int {
        var last = line.cells.lastIndex
        while (last > 0 && isBlankCell(line.cells[last])) last--
        return last
    }

    // Pane selections work in screen columns, not cell indices: a wide (CJK)
    // glyph is one cell spanning two columns, so after one the index falls
    // behind the column. A herdr sidebar full of CJK names would otherwise
    // put the pane's left edge at a different index on every row.

    /** Last column of the last non-blank cell starting inside [pane]'s columns, or -1. */
    private fun lastContentColumnInPane(line: TerminalLine, pane: PaneRect): Int {
        var column = 0
        var lastEnd = -1
        for (cell in line.cells) {
            if (column > pane.right) break
            if (column >= pane.left && !isBlankCell(cell)) lastEnd = column + cell.width - 1
            column += cell.width
        }
        return lastEnd
    }

    /** Text of the cells starting in columns [from]..[to], trailing blanks trimmed. */
    private fun columnText(line: TerminalLine, from: Int, to: Int): String = buildString {
        var column = 0
        for (cell in line.cells) {
            if (column > to) break
            if (column >= from) appendCopyableCell(cell)
            column += cell.width
        }
    }.trimEnd()

    /**
     * The last column a program in [pane] writes to: [PaneRect.right], or the
     * column before it when no row reaches the last one but some reach the
     * one before — herdr keeps its pane scrollbar column blank until there is
     * something to scroll, so its text wraps one column short of the edge.
     */
    private fun wrapColumn(snapshot: TerminalSnapshot, pane: PaneRect, scrollbackPosition: Int): Int {
        var widest = -1
        for (r in pane.top..pane.bottom) {
            val line = getSnapshotLine(snapshot, r, scrollbackPosition) ?: continue
            widest = maxOf(widest, lastContentColumnInPane(line, pane))
        }
        return if (widest == pane.right - 1) pane.right - 1 else pane.right
    }

    /**
     * Whether [line]'s text runs on into [next] inside [pane]: the pane
     * wrapped it, not the program. A multiplexer wraps a pane's long line
     * itself, so libvterm sees two hard-broken rows and `softWrapped` is
     * false; joining them is what makes a copied paragraph come out as one
     * line. A row counts as wrapped when its text reaches [edge] (see
     * [wrapColumn]), or stops one column short where a wide (CJK) character
     * did not fit and opens the next row instead.
     */
    private fun continuesInPane(line: TerminalLine, next: TerminalLine?, pane: PaneRect, edge: Int): Boolean {
        val lastEnd = lastContentColumnInPane(line, pane)
        if (lastEnd >= edge) return true
        if (lastEnd == edge - 1) {
            val opener = next?.cellAtColumn(pane.left) ?: return false
            return opener.width == 2 && !isBlankCell(opener)
        }
        return false
    }

    fun getSelectedText(snapshot: TerminalSnapshot, scrollbackPosition: Int = 0): String {
        val range = selectionRange ?: return ""

        val minRow = minOf(range.startRow, range.endRow)
        val maxRow = maxOf(range.startRow, range.endRow)

        // Anchor-aware, so the clipboard matches what SelectionRange.contains
        // drew: the first row starts at the anchor the selection began from and
        // the last row ends at the one it finished on. Using minOf/maxOf on the
        // two columns agreed with the highlight only for a down-and-right drag
        // — dragging down and LEFT copied text the user never highlighted (a
        // selection from row 0 col 8 to row 1 col 2 highlighted "IJ"/"abc" but
        // copied "CDEFGHIJ"/"abcdefghi").
        val (_, selStartCol) = range.getStartPosition()
        val (_, selEndCol) = range.getEndPosition()

        return buildString {
            for (row in minRow..maxRow) {
                // Viewport-relative `row` — use the same scrollback-aware
                // resolver as [adjustSelectionForMode]. Visible rows above
                // `scrollbackPosition` come from scrollback; the rest from
                // the current screen.
                val line = getSnapshotLine(snapshot, row, scrollbackPosition)

                if (line == null) continue

                when (mode) {
                    SelectionMode.LINE -> {
                        // Build line text and trim trailing whitespace.
                        val pane = range.pane
                        val lineText = if (pane != null) {
                            columnText(line, pane.left, pane.right)
                        } else {
                            buildString {
                                line.cells.forEach { cell ->
                                    appendCopyableCell(cell)
                                }
                            }.trimEnd()
                        }
                        append(lineText)
                        if (row < maxRow && breaksAfter(line, row, range, snapshot, scrollbackPosition)) append('\n')
                    }

                    SelectionMode.CHARACTER, SelectionMode.WORD -> {
                        val startCol = when (row) {
                            minRow -> selStartCol
                            else -> range.pane?.left ?: 0
                        }
                        val endCol = when (row) {
                            maxRow -> selEndCol
                            else -> range.pane?.right ?: (line.cells.size - 1)
                        }

                        // Build line text and trim trailing whitespace
                        val lineText = if (range.pane != null) {
                            columnText(line, startCol, endCol)
                        } else {
                            buildString {
                                for (col in startCol..minOf(endCol, line.cells.lastIndex)) {
                                    appendCopyableCell(line.cells[col])
                                }
                            }.trimEnd()
                        }
                        append(lineText)
                        if (row < maxRow && breaksAfter(line, row, range, snapshot, scrollbackPosition)) append('\n')
                    }

                    SelectionMode.NONE -> {}
                }
            }
        }.trim()
    }

    /** Whether copying puts a newline after [line] (viewport [row]) — false where it wrapped. */
    private fun breaksAfter(
        line: TerminalLine,
        row: Int,
        range: SelectionRange,
        snapshot: TerminalSnapshot,
        scrollbackPosition: Int,
    ): Boolean {
        val pane = range.pane ?: return !line.softWrapped
        val edge = wrapColumn(snapshot, pane, scrollbackPosition)
        return !continuesInPane(line, getSnapshotLine(snapshot, row + 1, scrollbackPosition), pane, edge)
    }

    fun isCellSelected(row: Int, col: Int, line: TerminalLine? = null): Boolean {
        val range = selectionRange ?: return false
        return when (mode) {
            SelectionMode.LINE -> {
                val minRow = minOf(range.startRow, range.endRow)
                val maxRow = maxOf(range.startRow, range.endRow)
                val pane = range.pane
                val column = if (pane != null && line != null) line.columnOf(col) else col
                row in minRow..maxRow && (pane == null || column in pane.left..pane.right)
            }

            SelectionMode.CHARACTER, SelectionMode.WORD -> {
                val pane = range.pane
                if (pane != null && line != null) {
                    // [col] is the renderer's cell index; the pane is in columns.
                    // Trailing blanks stop at the pane's own text, not the next pane's.
                    val column = line.columnOf(col)
                    if (column > lastContentColumnInPane(line, pane)) return false
                    return range.contains(row, column)
                }
                if (line != null && col > lastContentCol(line)) return false
                range.contains(row, col)
            }

            SelectionMode.NONE -> false
        }
    }
}

/** Screen column where the cell at [index] starts: the widths of the cells before it. */
internal fun TerminalLine.columnOf(index: Int): Int {
    var column = 0
    for (i in 0 until minOf(index, cells.size)) column += cells[i].width
    return column
}

/** The cell covering screen [column], or null past the end of the line. */
internal fun TerminalLine.cellAtColumn(column: Int): TerminalLine.Cell? {
    var start = 0
    for (cell in cells) {
        if (column < start + cell.width) return if (column >= start) cell else null
        start += cell.width
    }
    return null
}
