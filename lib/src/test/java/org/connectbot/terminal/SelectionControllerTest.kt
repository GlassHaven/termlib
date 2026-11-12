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

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectionControllerTest {
    private lateinit var selectionManager: SelectionManager
    private lateinit var selectionController: SelectionController
    private var copiedText: String = ""

    @Before
    fun setup() {
        selectionManager = SelectionManager()

        // Create a simple implementation of SelectionController for testing
        selectionController = object : SelectionController {
            override val isSelectionActive: Boolean
                get() = selectionManager.mode != SelectionMode.NONE

            override fun startSelection(mode: SelectionMode) {
                if (selectionManager.mode == SelectionMode.NONE) {
                    // Start at a default position for testing
                    selectionManager.startSelection(10, 20, cols = 80, mode = mode)
                }
            }

            override fun toggleSelection() {
                if (selectionManager.mode == SelectionMode.NONE) {
                    startSelection()
                } else {
                    clearSelection()
                }
            }

            override fun moveSelectionUp() {
                selectionManager.moveSelectionUp(25)
            }

            override fun moveSelectionDown() {
                selectionManager.moveSelectionDown(25)
            }

            override fun moveSelectionLeft() {
                selectionManager.moveSelectionLeft(80)
            }

            override fun moveSelectionRight() {
                selectionManager.moveSelectionRight(80)
            }

            override fun toggleSelectionMode() {
                selectionManager.toggleMode(80)
            }

            override fun setSelectionMode(mode: SelectionMode) {
                selectionManager.setMode(mode, 80)
            }

            override fun selectAll() {
                selectionManager.selectAll(25, 80, scrollbackSize = 10, scrollbackPosition = 0)
            }

            override fun selectAllVisible() {
                selectionManager.selectAllVisible(25, 80)
            }

            override fun finishSelection() {
                selectionManager.endSelection()
            }

            override fun copySelection(): String {
                val snapshot = createTestSnapshot()
                val text = selectionManager.getSelectedText(snapshot, 0)
                if (text.isNotEmpty()) {
                    copiedText = text
                    selectionManager.clearSelection()
                }
                return text
            }

            override fun clearSelection() {
                selectionManager.clearSelection()
            }

            override fun updateSelectionStart(row: Int, col: Int) {
                selectionManager.updateSelectionStart(row, col)
            }

            override fun updateSelectionEnd(row: Int, col: Int) {
                selectionManager.updateSelectionEnd(row, col)
            }

            override fun getSelectionRange(): SelectionRange? = selectionManager.selectionRange
        }
    }

    @Test
    fun testInitialState() {
        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testStartSelection() {
        selectionController.startSelection()

        assertTrue(selectionController.isSelectionActive)
        assertNotNull(selectionManager.selectionRange)
    }

    @Test
    fun testStartSelectionWithMode() {
        selectionController.startSelection(SelectionMode.LINE)

        assertTrue(selectionController.isSelectionActive)
        assertEquals(SelectionMode.LINE, selectionManager.mode)
    }

    @Test
    fun testToggleSelectionOn() {
        assertFalse(selectionController.isSelectionActive)

        selectionController.toggleSelection()

        assertTrue(selectionController.isSelectionActive)
    }

    @Test
    fun testToggleSelectionOff() {
        selectionController.startSelection()
        assertTrue(selectionController.isSelectionActive)

        selectionController.toggleSelection()

        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testMoveSelectionUp() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionUp()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endRow - 1, newRange.endRow)
    }

    @Test
    fun testMoveSelectionDown() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionDown()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endRow + 1, newRange.endRow)
    }

    @Test
    fun testMoveSelectionLeft() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionLeft()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endCol - 1, newRange.endCol)
    }

    @Test
    fun testMoveSelectionRight() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionRight()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endCol + 1, newRange.endCol)
    }

    @Test
    fun testToggleSelectionMode() {
        selectionController.startSelection(SelectionMode.CHARACTER)
        assertEquals(SelectionMode.CHARACTER, selectionManager.mode)

        selectionController.toggleSelectionMode()
        assertEquals(SelectionMode.WORD, selectionManager.mode)

        selectionController.toggleSelectionMode()
        assertEquals(SelectionMode.LINE, selectionManager.mode)

        selectionController.toggleSelectionMode()
        assertEquals(SelectionMode.CHARACTER, selectionManager.mode)
    }

    @Test
    fun testSetSelectionMode() {
        selectionController.startSelection(SelectionMode.CHARACTER)
        selectionController.setSelectionMode(SelectionMode.LINE)
        assertEquals(SelectionMode.LINE, selectionManager.mode)
    }

    @Test
    fun testSelectAll() {
        selectionController.selectAll()
        assertTrue(selectionController.isSelectionActive)
        val range = selectionManager.selectionRange!!
        assertEquals(-10, range.startRow)
        assertEquals(0, range.startCol)
        assertEquals(24, range.endRow)
        assertEquals(79, range.endCol)
    }

    @Test
    fun testSelectAllVisible() {
        selectionController.selectAllVisible()
        assertTrue(selectionController.isSelectionActive)
        val range = selectionManager.selectionRange!!
        assertEquals(0, range.startRow)
        assertEquals(0, range.startCol)
        assertEquals(24, range.endRow)
        assertEquals(79, range.endCol)
    }

    @Test
    fun testFinishSelection() {
        selectionController.startSelection()
        assertTrue(selectionManager.isSelecting)

        selectionController.finishSelection()

        assertFalse(selectionManager.isSelecting)
        assertTrue(selectionController.isSelectionActive) // Still active, just not extending
    }

    @Test
    fun testCopySelection() {
        selectionController.startSelection()
        selectionController.moveSelectionDown()
        selectionController.moveSelectionRight()
        selectionController.finishSelection()

        val text = selectionController.copySelection()

        assertFalse(text.isEmpty())
        assertFalse(selectionController.isSelectionActive) // Cleared after copy
        assertEquals(text, copiedText) // Verify text was "copied"
    }

    @Test
    fun testCopySelectionWhenNoSelection() {
        val text = selectionController.copySelection()

        assertTrue(text.isEmpty())
    }

    @Test
    fun testClearSelection() {
        selectionController.startSelection()
        assertTrue(selectionController.isSelectionActive)

        selectionController.clearSelection()

        assertFalse(selectionController.isSelectionActive)
        assertNull(selectionManager.selectionRange)
    }

    @Test
    fun testCompleteWorkflow() {
        // Start selection
        assertFalse(selectionController.isSelectionActive)
        selectionController.startSelection()
        assertTrue(selectionController.isSelectionActive)
        assertTrue(selectionManager.isSelecting)

        // Extend selection
        selectionController.moveSelectionDown()
        selectionController.moveSelectionDown()
        selectionController.moveSelectionRight()
        selectionController.moveSelectionRight()
        selectionController.moveSelectionRight()

        val range = selectionManager.selectionRange!!
        assertEquals(10, range.startRow)
        assertEquals(20, range.startCol)
        assertEquals(12, range.endRow)
        assertEquals(23, range.endCol)

        // Finish extending
        selectionController.finishSelection()
        assertFalse(selectionManager.isSelecting)
        assertTrue(selectionController.isSelectionActive)

        // Copy and clear
        val text = selectionController.copySelection()
        assertFalse(text.isEmpty())
        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testNavigateSelectionAfterFinished() {
        selectionController.startSelection()
        selectionController.finishSelection()

        // After finished, moving should move entire selection
        val originalRange = selectionManager.selectionRange!!
        selectionController.moveSelectionDown()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.startRow + 1, newRange.startRow)
        assertEquals(originalRange.endRow + 1, newRange.endRow)
    }

    @Test
    fun testStartSelectionWhenAlreadyActive() {
        selectionController.startSelection()
        val range1 = selectionManager.selectionRange

        // Starting again should not change the selection
        selectionController.startSelection()
        val range2 = selectionManager.selectionRange

        assertEquals(range1, range2)
    }

    @Test
    fun testMultipleToggleCycles() {
        // Toggle on
        selectionController.toggleSelection()
        assertTrue(selectionController.isSelectionActive)

        // Toggle off
        selectionController.toggleSelection()
        assertFalse(selectionController.isSelectionActive)

        // Toggle on again
        selectionController.toggleSelection()
        assertTrue(selectionController.isSelectionActive)

        // Toggle off again
        selectionController.toggleSelection()
        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testMovementsRespectBoundaries() {
        selectionController.startSelection()

        // Move up many times to hit boundary
        repeat(20) {
            selectionController.moveSelectionUp()
        }

        val range = selectionManager.selectionRange!!
        assertTrue(range.endRow >= 0) // Should not go negative
    }

    @Test
    fun testShiftSelectionStartByRowsExtendsAnchorPastViewport() {
        // Drag-select-into-scrollback simulation (#94). The gesture
        // handler scrolls the viewport up and calls
        // shiftSelectionStartByRows(+1) so the anchor keeps pointing at
        // the original line as everything shifts down in the viewport.
        // After enough shifts the anchor row legitimately exceeds the
        // viewport height — the resolver still finds the right line via
        // the snapshot scrollback ring.
        //
        // Uses LINE mode because the gesture-driven extension of column
        // bounds at the corner anchors is independent of the row-shift
        // mechanism this test is exercising.
        val snapshot = createSnapshotWithScrollback(
            screenLines = listOf("screen-0", "screen-1", "screen-2"),
            scrollbackLines = listOf("hist-0", "hist-1", "hist-2", "hist-3"),
            cols = 80,
            rows = 3,
        )
        selectionManager.startSelection(
            row = 1,
            col = 0,
            cols = 80,
            mode = SelectionMode.LINE,
            snapshot = snapshot,
            scrollbackPosition = 0,
        )

        // Simulate two scroll-up steps: each moves scrollbackPosition +1
        // and the anchor +1 in lockstep. After two shifts the anchor row
        // is 3 (off the bottom of the 3-row viewport), but the logical
        // line it points to is still "screen-1".
        selectionManager.shiftSelectionStartByRows(+1)
        selectionManager.shiftSelectionStartByRows(+1)
        // The drag pointer is at the top edge throughout, so the end
        // row stays at 0; updating it after each scroll mirrors what
        // the gesture handler does.
        selectionManager.updateSelection(row = 0, col = 0)

        val range = selectionManager.selectionRange!!
        assertEquals("anchor row tracks viewport shift", 3, range.startRow)
        assertEquals(0, range.endRow)

        // With scrollbackPosition=2, the resolved selection text spans
        // hist-2, hist-3, screen-0, screen-1 (top-down).
        val text = selectionManager.getSelectedText(snapshot, scrollbackPosition = 2)
        assertEquals("hist-2\nhist-3\nscreen-0\nscreen-1", text)
    }

    @Test
    fun testShiftSelectionStartByRowsTowardScreen() {
        // Symmetric case: user already scrolled back, selected something
        // in scrollback, then drags toward the bottom edge. Each scroll-
        // down step decrements scrollbackPosition and shifts the anchor
        // -1 so it stays on its logical line.
        val snapshot = createSnapshotWithScrollback(
            screenLines = listOf("screen-0", "screen-1", "screen-2"),
            scrollbackLines = listOf("hist-0", "hist-1", "hist-2", "hist-3"),
            cols = 80,
            rows = 3,
        )
        // Anchor on the topmost visible scrollback line (hist-1) when
        // scrollbackPosition=3: viewport row 0 → scrollback[size-3]
        // = scrollback[1] = "hist-1".
        selectionManager.startSelection(
            row = 0,
            col = 0,
            cols = 80,
            mode = SelectionMode.CHARACTER,
            snapshot = snapshot,
            scrollbackPosition = 3,
        )

        // Two scroll-down steps: anchor row goes from 0 to -2.
        selectionManager.shiftSelectionStartByRows(-1)
        selectionManager.shiftSelectionStartByRows(-1)
        // Drag pointer at bottom edge — end row = rows - 1 = 2.
        selectionManager.updateSelection(row = 2, col = "screen-1".length - 1)

        val range = selectionManager.selectionRange!!
        assertEquals(-2, range.startRow)
        assertEquals(2, range.endRow)

        // scrollbackPosition is now 1 (started at 3, scrolled down twice).
        // Selection should resolve to: hist-1 (at row -2 relative to
        // pos=1 → scrollback[size - 1 + (-2)] = scrollback[1]),
        // hist-2 (row -1), hist-3 (row 0), screen-0 (row 1), screen-1 (row 2).
        val text = selectionManager.getSelectedText(snapshot, scrollbackPosition = 1)
        assertEquals("hist-1\nhist-2\nhist-3\nscreen-0\nscreen-1", text)
    }

    private fun createSnapshotWithScrollback(
        screenLines: List<String>,
        scrollbackLines: List<String>,
        cols: Int,
        rows: Int,
    ): TerminalSnapshot {
        fun toLine(text: String, row: Int): TerminalLine {
            val cells = (0 until cols).map { col ->
                TerminalLine.Cell(
                    char = text.getOrElse(col) { ' ' },
                    fgColor = Color.White,
                    bgColor = Color.Black,
                )
            }
            return TerminalLine(row, cells)
        }
        return TerminalSnapshot(
            lines = screenLines.mapIndexed { i, t -> toLine(t, i) },
            scrollback = scrollbackLines.mapIndexed { i, t -> toLine(t, -1) },
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = rows,
            cols = cols,
            timestamp = 0L,
            sequenceNumber = 0L,
        )
    }

    // Helper function to create a test snapshot
    private fun createTestSnapshot(): TerminalSnapshot {
        val lines = mutableListOf<TerminalLine>()
        for (row in 0 until 25) {
            val cells = mutableListOf<TerminalLine.Cell>()
            for (col in 0 until 80) {
                cells.add(
                    TerminalLine.Cell(
                        char = if (col < 10) ' ' else ('A' + (col % 26)),
                        fgColor = Color.White,
                        bgColor = Color.Black,
                    ),
                )
            }
            lines.add(TerminalLine(row, cells))
        }

        return TerminalSnapshot(
            lines = lines,
            scrollback = emptyList(),
            cursorRow = 10,
            cursorCol = 20,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = 25,
            cols = 80,
            timestamp = 0L,
            sequenceNumber = 0L,
        )
    }
}
