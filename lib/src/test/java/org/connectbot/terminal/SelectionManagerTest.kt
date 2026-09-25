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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectionManagerTest {
    private lateinit var selectionManager: SelectionManager

    @Before
    fun setup() {
        selectionManager = SelectionManager()
    }

    @Test
    fun testInitialState() {
        assertEquals(SelectionMode.NONE, selectionManager.mode)
        assertNull(selectionManager.selectionRange)
        assertFalse(selectionManager.isSelecting)
    }

    @Test
    fun testStartSelection() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)

        assertEquals(SelectionMode.CHARACTER, selectionManager.mode)
        assertTrue(selectionManager.isSelecting)
        assertNotNull(selectionManager.selectionRange)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow)
        assertEquals(10, range.startCol)
        assertEquals(5, range.endRow)
        assertEquals(10, range.endCol)
    }

    @Test
    fun testMoveSelectionUpWhileSelecting() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionUp(20)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow) // Start unchanged
        assertEquals(10, range.startCol)
        assertEquals(4, range.endRow) // End moved up
        assertEquals(10, range.endCol)
    }

    @Test
    fun testMoveSelectionUpAtBoundary() {
        selectionManager.startSelection(0, 10, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionUp(20)

        val range = selectionManager.selectionRange!!
        assertEquals(0, range.endRow) // Clamped at 0
    }

    @Test
    fun testMoveSelectionDownWhileSelecting() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionDown(20)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow) // Start unchanged
        assertEquals(10, range.startCol)
        assertEquals(6, range.endRow) // End moved down
        assertEquals(10, range.endCol)
    }

    @Test
    fun testMoveSelectionDownAtBoundary() {
        selectionManager.startSelection(19, 10, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionDown(20)

        val range = selectionManager.selectionRange!!
        assertEquals(19, range.endRow) // Clamped at maxRow - 1
    }

    @Test
    fun testMoveSelectionLeftWhileSelecting() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionLeft(80)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow)
        assertEquals(10, range.startCol) // Start unchanged
        assertEquals(5, range.endRow)
        assertEquals(9, range.endCol) // End moved left
    }

    @Test
    fun testMoveSelectionLeftAtBoundary() {
        selectionManager.startSelection(5, 0, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionLeft(80)

        val range = selectionManager.selectionRange!!
        assertEquals(0, range.endCol) // Clamped at 0
    }

    @Test
    fun testMoveSelectionRightWhileSelecting() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow)
        assertEquals(10, range.startCol) // Start unchanged
        assertEquals(5, range.endRow)
        assertEquals(11, range.endCol) // End moved right
    }

    @Test
    fun testMoveSelectionRightAtBoundary() {
        selectionManager.startSelection(5, 79, cols = 80, mode = SelectionMode.CHARACTER)

        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(79, range.endCol) // Clamped at maxCol - 1
    }

    @Test
    fun testMoveSelectionAfterFinished() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        assertFalse(selectionManager.isSelecting)

        // Move down after finishing - both start and end should move
        selectionManager.moveSelectionDown(20)

        val range = selectionManager.selectionRange!!
        assertEquals(6, range.startRow) // Both moved down
        assertEquals(6, range.endRow)
    }

    @Test
    fun testMoveSelectionUpAfterFinished() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        selectionManager.moveSelectionUp(20)

        val range = selectionManager.selectionRange!!
        assertEquals(4, range.startRow) // Both moved up
        assertEquals(4, range.endRow)
    }

    @Test
    fun testMoveSelectionLeftAfterFinished() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        selectionManager.moveSelectionLeft(80)

        val range = selectionManager.selectionRange!!
        assertEquals(9, range.startCol) // Both moved left
        assertEquals(9, range.endCol)
    }

    @Test
    fun testMoveSelectionRightAfterFinished() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(11, range.startCol) // Both moved right
        assertEquals(11, range.endCol)
    }

    @Test
    fun testMultipleMovesWhileSelecting() {
        selectionManager.startSelection(10, 40, cols = 80, mode = SelectionMode.CHARACTER)

        // Move to create a rectangular selection
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionRight(80)
        selectionManager.moveSelectionRight(80)
        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(10, range.startRow)
        assertEquals(40, range.startCol)
        assertEquals(12, range.endRow) // Moved down 2
        assertEquals(43, range.endCol) // Moved right 3
    }

    @Test
    fun testClearSelectionResetsState() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.moveSelectionDown(20)
        selectionManager.moveSelectionRight(80)

        selectionManager.clearSelection()

        assertEquals(SelectionMode.NONE, selectionManager.mode)
        assertNull(selectionManager.selectionRange)
        assertFalse(selectionManager.isSelecting)
    }

    @Test
    fun testUpdateSelectionStart() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        selectionManager.updateSelectionStart(3, 8)

        val range = selectionManager.selectionRange!!
        assertEquals(3, range.startRow)
        assertEquals(8, range.startCol)
        assertEquals(5, range.endRow) // End unchanged
        assertEquals(10, range.endCol)
    }

    @Test
    fun testUpdateSelectionEnd() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        selectionManager.updateSelectionEnd(7, 12)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow) // Start unchanged
        assertEquals(10, range.startCol)
        assertEquals(7, range.endRow)
        assertEquals(12, range.endCol)
    }

    @Test
    fun testToggleModeCycling() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.endSelection()

        // CHARACTER -> WORD
        selectionManager.toggleMode(80)
        assertEquals(SelectionMode.WORD, selectionManager.mode)

        // WORD -> LINE
        selectionManager.toggleMode(80)
        assertEquals(SelectionMode.LINE, selectionManager.mode)
        val range = selectionManager.selectionRange!!
        assertEquals(0, range.startCol) // Line mode uses full width
        assertEquals(79, range.endCol)

        // LINE -> CHARACTER
        selectionManager.toggleMode(80)
        assertEquals(SelectionMode.CHARACTER, selectionManager.mode)
    }

    @Test
    fun testSetMode() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.setMode(SelectionMode.LINE, 80)
        assertEquals(SelectionMode.LINE, selectionManager.mode)
    }

    @Test
    fun testSelectAll() {
        selectionManager.selectAll(25, 80)
        assertEquals(SelectionMode.CHARACTER, selectionManager.mode)
        assertFalse(selectionManager.isSelecting)
        val range = selectionManager.selectionRange!!
        assertEquals(0, range.startRow)
        assertEquals(0, range.startCol)
        assertEquals(24, range.endRow)
        assertEquals(79, range.endCol)
    }

    @Test
    fun testIsCellSelectedInCharacterMode() {
        selectionManager.startSelection(5, 10, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(7, 15)
        selectionManager.endSelection()

        // Inside selection
        // Row 5: from col 10 onwards
        assertTrue(selectionManager.isCellSelected(5, 10))
        assertTrue(selectionManager.isCellSelected(5, 15))
        assertTrue(selectionManager.isCellSelected(5, 20))

        // Row 6: entire row (middle row)
        assertTrue(selectionManager.isCellSelected(6, 0))
        assertTrue(selectionManager.isCellSelected(6, 9))
        assertTrue(selectionManager.isCellSelected(6, 12))
        assertTrue(selectionManager.isCellSelected(6, 16))

        // Row 7: up to col 15
        assertTrue(selectionManager.isCellSelected(7, 0))
        assertTrue(selectionManager.isCellSelected(7, 10))
        assertTrue(selectionManager.isCellSelected(7, 15))

        // Outside selection
        assertFalse(selectionManager.isCellSelected(4, 10)) // Row before
        assertFalse(selectionManager.isCellSelected(8, 10)) // Row after
        assertFalse(selectionManager.isCellSelected(5, 9)) // Before startCol on first row
        assertFalse(selectionManager.isCellSelected(7, 16)) // After endCol on last row
    }

    @Test
    fun testIsCellSelectedInLineMode() {
        selectionManager.startSelection(5, 0, cols = 80, mode = SelectionMode.LINE)
        selectionManager.updateSelection(7, 79)
        selectionManager.endSelection()

        // Entire rows 5, 6, 7 should be selected
        assertTrue(selectionManager.isCellSelected(5, 0))
        assertTrue(selectionManager.isCellSelected(5, 40))
        assertTrue(selectionManager.isCellSelected(6, 20))
        assertTrue(selectionManager.isCellSelected(7, 79))

        // Row 4 and 8 should not be selected
        assertFalse(selectionManager.isCellSelected(4, 0))
        assertFalse(selectionManager.isCellSelected(8, 0))
    }

    @Test
    fun testAdjustSelectionForModeWord() {
        // Create a fake snapshot with one line: "Hello world"
        val line = TerminalLine(
            row = 0,
            cells = "Hello world".map { c ->
                TerminalLine.Cell(
                    char = c,
                    fgColor = androidx.compose.ui.graphics.Color.White,
                    bgColor = androidx.compose.ui.graphics.Color.Black,
                )
            },
        )
        val snapshot = TerminalSnapshot(
            lines = listOf(line),
            scrollback = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = 1,
            cols = 11,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = 1L,
        )

        // Select 'e' in "Hello" (row 0, col 1)
        selectionManager.startSelection(0, 1, cols = 11, mode = SelectionMode.WORD, snapshot = snapshot)

        val range = selectionManager.selectionRange!!
        // Should snap to "Hello" (cols 0 to 4)
        assertEquals(0, range.startCol)
        assertEquals(4, range.endCol)

        // Now update end to 'r' in "world" (col 8)
        selectionManager.updateSelectionEnd(0, 8)
        selectionManager.adjustSelectionForMode(11, snapshot)

        val updatedRange = selectionManager.selectionRange!!
        // End should snap to end of "world" (col 10)
        // Start remains at 0 because it was already snapped and hasn't moved
        assertEquals(0, updatedRange.startCol)
        assertEquals(10, updatedRange.endCol)
    }

    @Test
    fun testMoveWithoutStartingSelectionDoesNothing() {
        // Should not crash when moving without selection
        selectionManager.moveSelectionUp(20)
        selectionManager.moveSelectionDown(20)
        selectionManager.moveSelectionLeft(80)
        selectionManager.moveSelectionRight(80)

        assertNull(selectionManager.selectionRange)
    }

    private fun makeSnapshot(text: String, cols: Int = text.length): TerminalSnapshot {
        val cells = text.map { c ->
            TerminalLine.Cell(
                char = c,
                fgColor = androidx.compose.ui.graphics.Color.White,
                bgColor = androidx.compose.ui.graphics.Color.Black,
            )
        }
        val line = TerminalLine(row = 0, cells = cells)
        return TerminalSnapshot(
            lines = listOf(line),
            scrollback = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = 1,
            cols = cols,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = 1L,
        )
    }

    @Test
    fun testWordModeSnapsToLastWordWhenInTrailingWhitespace() {
        // "hello   " — touch in trailing spaces past the last word
        val snapshot = makeSnapshot("hello   ", cols = 80)
        selectionManager.startSelection(0, 6, cols = 80, mode = SelectionMode.WORD, snapshot = snapshot)

        val range = selectionManager.selectionRange!!
        // Should snap to "hello" (cols 0-4), not stay in whitespace
        assertEquals(0, range.startCol)
        assertEquals(4, range.endCol)
    }

    @Test
    fun testWordModeDoesNotSnapWhenWordExistsToRight() {
        // "foo bar" — touch in space between words; should keep non-word region
        val snapshot = makeSnapshot("foo bar")
        selectionManager.startSelection(0, 3, cols = 7, mode = SelectionMode.WORD, snapshot = snapshot)

        val range = selectionManager.selectionRange!!
        // col 3 is ' ' and there is a word to the right, so it groups the space
        assertEquals(3, range.startCol)
        assertEquals(3, range.endCol)
    }

    private fun cell(c: Char) = TerminalLine.Cell(
        char = c,
        fgColor = androidx.compose.ui.graphics.Color.White,
        bgColor = androidx.compose.ui.graphics.Color.Black,
    )

    @Test
    fun testIsCellSelectedSkipsTrailingSpaceCells() {
        // Line: "hi  " (content in cols 0-1, spaces in 2-3)
        val line = TerminalLine(row = 0, cells = listOf(cell('h'), cell('i'), cell(' '), cell(' ')))

        selectionManager.startSelection(0, 0, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(0, 3)
        selectionManager.endSelection()

        // Content cells should be selected
        assertTrue(selectionManager.isCellSelected(0, 0, line))
        assertTrue(selectionManager.isCellSelected(0, 1, line))
        // Trailing space cells should NOT be selected when line is provided
        assertFalse(selectionManager.isCellSelected(0, 2, line))
        assertFalse(selectionManager.isCellSelected(0, 3, line))
    }

    @Test
    fun testIsCellSelectedSkipsTrailingNullCells() {
        val line = TerminalLine(row = 0, cells = listOf(cell('x'), cell('\u0000'), cell('\u0000')))

        selectionManager.startSelection(0, 0, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(0, 2)
        selectionManager.endSelection()

        assertTrue(selectionManager.isCellSelected(0, 0, line))
        assertFalse(selectionManager.isCellSelected(0, 1, line))
        assertFalse(selectionManager.isCellSelected(0, 2, line))
    }

    @Test
    fun testIsCellSelectedWithoutLineIncludesTrailingSpace() {
        // Without providing a line, trailing spaces ARE included (backward compat)
        selectionManager.startSelection(0, 0, cols = 80, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(0, 5)
        selectionManager.endSelection()

        assertTrue(selectionManager.isCellSelected(0, 5))
        assertTrue(selectionManager.isCellSelected(0, 3))
    }

    @Test
    fun testIsCellSelectedLineModeIncludesTrailingSpace() {
        // LINE mode selects entire rows including trailing whitespace so the handle
        // position and the highlight are consistent with the full-width selection.
        val line = TerminalLine(row = 0, cells = listOf(cell('a'), cell(' '), cell(' ')))

        selectionManager.startSelection(0, 0, cols = 80, mode = SelectionMode.LINE)
        selectionManager.endSelection()

        assertTrue(selectionManager.isCellSelected(0, 0, line))
        assertTrue(selectionManager.isCellSelected(0, 1, line))
        assertTrue(selectionManager.isCellSelected(0, 2, line))
    }

    @Test
    fun testWordModeAdjustsDuringDrag() {
        // "hello world   " — start on 'h', drag end to trailing space
        val snapshot = makeSnapshot("hello world   ", cols = 80)
        selectionManager.startSelection(0, 0, cols = 80, mode = SelectionMode.WORD, snapshot = snapshot)

        // Simulate drag into trailing whitespace (col 12, past "world" ending at col 10)
        selectionManager.updateSelection(0, 12)
        selectionManager.adjustSelectionForMode(80, snapshot)

        val range = selectionManager.selectionRange!!
        // Start should snap to start of "hello" (col 0)
        assertEquals(0, range.startCol)
        // End should snap to last word "world" end (col 10), not trailing space
        assertEquals(10, range.endCol)
    }

    @Test
    fun testComplexNavigationScenario() {
        // Start selection
        selectionManager.startSelection(10, 20, cols = 80, mode = SelectionMode.CHARACTER)
        assertTrue(selectionManager.isSelecting)

        // Extend selection by moving
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionRight(80)
        selectionManager.moveSelectionRight(80)

        var range = selectionManager.selectionRange!!
        assertEquals(10, range.startRow)
        assertEquals(20, range.startCol)
        assertEquals(12, range.endRow)
        assertEquals(22, range.endCol)

        // Finish selection
        selectionManager.endSelection()
        assertFalse(selectionManager.isSelecting)

        // Now move the entire selection
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionRight(80)

        range = selectionManager.selectionRange!!
        assertEquals(11, range.startRow)
        assertEquals(21, range.startCol)
        assertEquals(13, range.endRow)
        assertEquals(23, range.endCol)

        // Clear it
        selectionManager.clearSelection()
        assertEquals(SelectionMode.NONE, selectionManager.mode)
    }

    /**
     * Build a snapshot whose `scrollback` carries the first N-rows of
     * [content] and whose active screen ([lines]) carries the rest.
     * Helpful for issue-#94 follow-up tests where a selection spans both
     * scrollback and the live screen.
     */
    private fun makeSnapshotWithScrollback(
        scrollbackText: List<String>,
        screenText: List<String>,
        cols: Int,
    ): TerminalSnapshot {
        fun lineOf(text: String, rowIndex: Int): TerminalLine {
            val padded = text.padEnd(cols, ' ')
            return TerminalLine(
                row = rowIndex,
                cells = padded.map { cell(it) },
            )
        }
        val scrollback = scrollbackText.mapIndexed { i, t -> lineOf(t, i) }
        val screen = screenText.mapIndexed { i, t -> lineOf(t, scrollbackText.size + i) }
        return TerminalSnapshot(
            lines = screen,
            scrollback = scrollback,
            cursorRow = screen.lastIndex.coerceAtLeast(0),
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = screen.size,
            cols = cols,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = 1L,
        )
    }

    /**
     * Issue #94 follow-up — when the user drags-to-select and edge-scroll
     * auto-scroll pushes the anchor off the top of the viewport,
     * [SelectionManager.shiftSelectionStartByRows] sets `startRow` negative
     * while live-tailing (`scrollbackPosition == 0`). Pre-fix,
     * `getSnapshotLine` short-circuited to `snapshot.lines.getOrNull(row)`
     * for the live-tail case, returning null on negative rows and
     * `getSelectedText` silently dropped those rows. The clipboard ended up
     * with only the on-screen portion of the selection.
     */
    @Test
    fun testGetSelectedTextSpansOffViewportTopWhileLiveTailing() {
        // 3 lines of scrollback above, 3 lines of screen below.
        val snapshot = makeSnapshotWithScrollback(
            scrollbackText = listOf("old-3", "old-2", "old-1"),
            screenText = listOf("scr-0", "scr-1", "scr-2"),
            cols = 5,
        )

        // Selection: anchor was at screen row 0, user dragged finger off the
        // top edge; shiftSelectionStartByRows pulled startRow to -2 (i.e. two
        // rows into scrollback).
        selectionManager.startSelection(0, 0, cols = 5, mode = SelectionMode.CHARACTER)
        selectionManager.shiftSelectionStartByRows(-2)
        selectionManager.updateSelection(0, 4)
        selectionManager.endSelection()

        val text = selectionManager.getSelectedText(snapshot, scrollbackPosition = 0)
        // Three rows should be present: "old-2", "old-1", "scr-0".
        assertEquals("old-2\nold-1\nscr-0", text)
    }

    /**
     * Same shape as the live-tailing case but with the user already
     * scrolled back into history. `scrollbackPosition > 0`; the anchor
     * has drifted further into older scrollback via repeated edge-scroll.
     */
    @Test
    fun testGetSelectedTextSpansOffViewportTopWhileScrolledBack() {
        val snapshot = makeSnapshotWithScrollback(
            scrollbackText = listOf("old-4", "old-3", "old-2", "old-1"),
            screenText = listOf("scr-0", "scr-1", "scr-2"),
            cols = 5,
        )

        // Viewport is scrolled back by 2 rows: visible rows are
        //   row 0 -> scrollback[size-2] = "old-2"
        //   row 1 -> scrollback[size-1] = "old-1"
        //   row 2 -> lines[0]           = "scr-0"
        // Anchor started at row 0, then shifted -2 (further into history).
        selectionManager.startSelection(0, 0, cols = 5, mode = SelectionMode.CHARACTER)
        selectionManager.shiftSelectionStartByRows(-2)
        selectionManager.updateSelection(1, 4)
        selectionManager.endSelection()

        val text = selectionManager.getSelectedText(snapshot, scrollbackPosition = 2)
        // Should produce "old-4\nold-3\nold-2\nold-1".
        assertEquals("old-4\nold-3\nold-2\nold-1", text)
    }

    /**
     * Sanity check: a wholly on-screen selection is unaffected by the
     * #94 fix (was the dominant code path before; must not regress).
     */
    @Test
    fun testGetSelectedTextOnScreenOnlyUnchanged() {
        val snapshot = makeSnapshotWithScrollback(
            scrollbackText = listOf("old-1"),
            screenText = listOf("scr-0", "scr-1", "scr-2"),
            cols = 5,
        )

        selectionManager.startSelection(0, 0, cols = 5, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(1, 4)
        selectionManager.endSelection()

        val text = selectionManager.getSelectedText(snapshot, scrollbackPosition = 0)
        assertEquals("scr-0\nscr-1", text)
    }

    /**
     * Past-the-end rows (row >= rows after a downward shift) must resolve
     * to null cleanly — extraction skips them rather than crashing. This
     * locks the contract for the symmetric case to the bug above.
     */
    @Test
    fun testGetSelectedTextSkipsRowsPastScreenBottom() {
        val snapshot = makeSnapshotWithScrollback(
            scrollbackText = emptyList(),
            screenText = listOf("scr-0", "scr-1"),
            cols = 5,
        )

        selectionManager.startSelection(0, 0, cols = 5, mode = SelectionMode.CHARACTER)
        // endRow > snapshot.rows; only existing rows should be emitted.
        selectionManager.updateSelection(5, 4)
        selectionManager.endSelection()

        val text = selectionManager.getSelectedText(snapshot, scrollbackPosition = 0)
        assertEquals("scr-0\nscr-1", text)
    }

    @Test
    fun testClampToDimensions() {
        // Start with a large selection (e.g. 24 rows)
        selectionManager.selectAll(24, 80)
        var range = selectionManager.selectionRange!!
        assertEquals(23, range.endRow)

        // Shrink to 12 rows
        selectionManager.clampToDimensions(12, 80)
        range = selectionManager.selectionRange!!
        assertEquals(0, range.startRow)
        assertEquals(11, range.endRow) // Clamped to new maxRow - 1

        // Expand back to 24 rows - should NOT expand back automatically
        selectionManager.clampToDimensions(24, 80)
        range = selectionManager.selectionRange!!
        assertEquals(11, range.endRow) // Still at 11
    }

    @Test
    fun `backward drag selection is normalized to reading order on end`() {
        // Drag from (5,10) up-left to (2,3): gesture order has start after end.
        selectionManager.startSelection(5, 10, 80)
        selectionManager.updateSelection(2, 3)
        selectionManager.endSelection()

        val range = selectionManager.selectionRange!!
        assertEquals(2, range.startRow)
        assertEquals(3, range.startCol)
        assertEquals(5, range.endRow)
        assertEquals(10, range.endCol)
    }

    @Test
    fun `backward same-row selection is normalized on end`() {
        selectionManager.startSelection(4, 20, 80)
        selectionManager.updateSelection(4, 7)
        selectionManager.endSelection()

        val range = selectionManager.selectionRange!!
        assertEquals(7, range.startCol)
        assertEquals(20, range.endCol)
    }

    @Test
    fun `forward selection is unchanged by normalization`() {
        selectionManager.startSelection(1, 2, 80)
        selectionManager.updateSelection(3, 4)
        selectionManager.endSelection()

        val range = selectionManager.selectionRange!!
        assertEquals(1, range.startRow)
        assertEquals(2, range.startCol)
        assertEquals(3, range.endRow)
        assertEquals(4, range.endCol)
    }

    /**
     * The highlight and the clipboard must agree on which cells are selected.
     *
     * [SelectionRange.contains] — what gets drawn — is anchor-aware: on the
     * first row it takes everything from the START anchor's column, and on the
     * last row everything up to the END anchor's column. `getSelectedText` used
     * `minOf`/`maxOf` on the two columns instead, which is the same answer only
     * when the drag happens to go down-and-right.
     *
     * Drag DOWN and LEFT and they disagree: here the user selects from row 0
     * col 8 to row 1 col 2, so the highlight covers "IJ" then "abc", but the
     * extraction took min(8,2)=2 on the first row and max(8,2)=8 on the last,
     * copying "CDEFGHIJ" then "abcdefghi" — text the user never highlighted.
     */
    @Test
    fun testSelectedTextMatchesHighlightWhenDraggedDownAndLeft() {
        val snapshot = makeSnapshotWithScrollback(
            scrollbackText = emptyList(),
            screenText = listOf("ABCDEFGHIJ", "abcdefghij"),
            cols = 10,
        )

        selectionManager.startSelection(0, 8, cols = 10, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(1, 2)
        selectionManager.endSelection()

        val range = selectionManager.selectionRange!!

        // What the user sees highlighted, read straight off the drawing predicate.
        val highlighted = (0..1).joinToString("\n") { row ->
            (0..9).filter { col -> range.contains(row, col) }
                .joinToString("") { col -> "ABCDEFGHIJabcdefghij"[row * 10 + col].toString() }
        }
        assertEquals("IJ\nabc", highlighted)

        // What actually reaches the clipboard must be the same text.
        assertEquals(highlighted, selectionManager.getSelectedText(snapshot, scrollbackPosition = 0))
    }

    /** The down-and-right drag that already worked must keep working. */
    @Test
    fun testSelectedTextMatchesHighlightWhenDraggedDownAndRight() {
        val snapshot = makeSnapshotWithScrollback(
            scrollbackText = emptyList(),
            screenText = listOf("ABCDEFGHIJ", "abcdefghij"),
            cols = 10,
        )

        selectionManager.startSelection(0, 2, cols = 10, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(1, 8)
        selectionManager.endSelection()

        assertEquals(
            "CDEFGHIJ\nabcdefghi",
            selectionManager.getSelectedText(snapshot, scrollbackPosition = 0),
        )
    }

    @Test
    fun testSelectedTextCrossesFromScrollbackIntoCurrentScreen() {
        val snapshot = makeTwoLineSnapshot("live", "next").copy(
            scrollback = listOf(TerminalLine(row = 0, cells = "old!".map { cell(it) })),
        )

        // With one row of scrollback visible, viewport row 0 is "old!" and
        // viewport row 1 is the first current-screen row, "live".
        selectionManager.startSelection(0, 1, cols = 4, mode = SelectionMode.CHARACTER)
        selectionManager.updateSelection(1, 2)
        selectionManager.endSelection()

        assertEquals("ld!\nliv", selectionManager.getSelectedText(snapshot, scrollbackPosition = 1))
    }

    private fun makeTwoLineSnapshot(first: String, second: String): TerminalSnapshot {
        val cols = maxOf(first.length, second.length)
        val lines = listOf(first, second).mapIndexed { i, t ->
            TerminalLine(row = i, cells = t.padEnd(cols, ' ').map { cell(it) })
        }
        return TerminalSnapshot(
            lines = lines,
            scrollback = emptyList(),
            cursorRow = 1,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = 2,
            cols = cols,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = 1L,
        )
    }
}
