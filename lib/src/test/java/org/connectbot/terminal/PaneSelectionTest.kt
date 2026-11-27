package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A selection started inside a multiplexer pane stays inside it: detection
 * from the drawn borders, clamping, highlight, copied text (including lines
 * the pane wrapped), wide (CJK) glyphs, and widening back to the whole screen.
 */
class PaneSelectionTest {

    /** CJK glyphs take two columns and one cell, as TerminalEmulator builds them. */
    private fun isWide(c: Char) = c.code in 0x2E80..0x9FFF || c.code in 0xFF00..0xFF60

    private fun line(row: Int, text: String, cols: Int): TerminalLine {
        val cells = mutableListOf<TerminalLine.Cell>()
        var column = 0
        for (c in text) {
            val w = if (isWide(c)) 2 else 1
            if (column + w > cols) break
            cells += TerminalLine.Cell(c, fgColor = Color.White, bgColor = Color.Black, width = w)
            column += w
        }
        while (column < cols) {
            cells += TerminalLine.Cell(' ', fgColor = Color.White, bgColor = Color.Black)
            column++
        }
        return TerminalLine(row = row, cells = cells)
    }

    private fun snapshot(vararg rows: String, cols: Int = 30): TerminalSnapshot = TerminalSnapshot(
        lines = rows.mapIndexed { i, text -> line(i, text, cols) },
        scrollback = emptyList(),
        cursorRow = 0,
        cursorCol = 0,
        cursorVisible = true,
        cursorBlink = false,
        cursorShape = CursorShape.BLOCK,
        terminalTitle = "",
        rows = rows.size,
        cols = cols,
        timestamp = 0L,
        sequenceNumber = 1L,
    )

    private fun detect(snap: TerminalSnapshot, row: Int, col: Int): PaneRect? = detectPane(snap.rows, snap.cols, row, col) { r, c -> snap.lines.getOrNull(r)?.cellAtColumn(c)?.char }

    /** herdr's layout: a 10-column sidebar, a │ border at column 10, the pane to the right. */
    private val herdr = snapshot(
        " spaces   │ 1   +",
        " · Haven  │hello world",
        "   main   │second line",
        "          │third line",
        " agents   │",
        "          │",
    )

    @Test
    fun aSidebarBorderBoundsThePane() {
        assertEquals(PaneRect(top = 0, bottom = 5, left = 11, right = 29), detect(herdr, 1, 15))
        assertEquals(PaneRect(top = 0, bottom = 5, left = 0, right = 9), detect(herdr, 2, 3))
    }

    @Test
    fun noBorderMeansNoPane() {
        val plain = snapshot("hello", "world", "again")
        assertNull(detect(plain, 1, 2))
    }

    @Test
    fun aShortTableIsNotAPaneBorder() {
        val rows = Array(12) { "" }
        rows[4] = "a │ b"
        rows[5] = "c │ d"
        rows[6] = "e │ f"
        // A 3-row run on a 12-row screen is under a third of it.
        assertNull(detect(snapshot(*rows), 5, 0))
    }

    @Test
    fun onlyAJoinedRuleIsAHorizontalSplit() {
        val snap = snapshot(
            "side│top pane",
            "side│top pane",
            "side├" + "─".repeat(25),
            "side│bottom",
            "side│bottom",
            "side│" + "─".repeat(25),
            "side│input",
        )
        // ├ joins the rule to the border: a split, so rows stop there. The rule
        // on row 5 meets a plain │ — a line drawn inside the pane — and doesn't.
        assertEquals(PaneRect(top = 3, bottom = 6, left = 5, right = 29), detect(snap, 4, 7))
    }

    @Test
    fun selectionIsClampedHighlightedAndCopiedWithinThePane() {
        val sm = SelectionManager()
        sm.startSelection(1, 11, herdr.cols, SelectionMode.CHARACTER, herdr)
        // Drag down and left, into the sidebar.
        sm.updateSelection(3, 2)
        sm.endSelection()

        val range = sm.selectionRange!!
        assertNotNull(range.pane)
        assertEquals(11, range.endCol) // pulled back to the pane's left edge

        assertFalse("sidebar text not highlighted", sm.isCellSelected(2, 4, herdr.lines[2]))
        assertTrue(sm.isCellSelected(2, 12, herdr.lines[2]))
        assertEquals("hello world\nsecond line\nt", sm.getSelectedText(herdr))
    }

    @Test
    fun wideSidebarTextDoesNotShiftThePane() {
        // "慧苗" is two cells over four columns: by index the border would sit
        // two cells earlier on that row than on the others.
        val snap = snapshot(
            " spaces   │ 1   +",
            " ○ 慧苗   │题目 abc",
            "   main   │second",
            "          │",
        )
        assertEquals(PaneRect(top = 0, bottom = 3, left = 11, right = 29), detect(snap, 2, 12))

        val sm = SelectionManager()
        sm.startSelection(1, 11, snap.cols, SelectionMode.CHARACTER, snap)
        sm.updateSelection(2, 16)
        sm.endSelection()
        assertEquals("题目 abc\nsecond", sm.getSelectedText(snap))
        // On row 1 the border is cell 8 (column 10) and 题 is cell 9 (column 11).
        assertFalse(sm.isCellSelected(1, 8, snap.lines[1]))
        assertTrue(sm.isCellSelected(1, 9, snap.lines[1]))
    }

    @Test
    fun linesThePaneWrappedAreJoined() {
        val snap = snapshot(
            "side│abcdefghij",
            "side│klm",
            "side│short",
            "side│next",
            cols = 15,
        )
        val sm = SelectionManager()
        sm.startSelection(0, 5, snap.cols, SelectionMode.CHARACTER, snap)
        sm.updateSelection(3, 8)
        sm.endSelection()
        // Row 0 fills the pane to its edge: wrapped. Row 2 stops short: a real break.
        assertEquals("abcdefghijklm\nshort\nnext", sm.getSelectedText(snap))
    }

    @Test
    fun aWideGlyphThatDidNotFitStillJoins() {
        val snap = snapshot(
            "side│一二三四x",
            "side│五六",
            "side│",
            cols = 15,
        )
        // Row 0 ends one column short of the edge because 五 needed two.
        val sm = SelectionManager()
        sm.startSelection(0, 5, snap.cols, SelectionMode.CHARACTER, snap)
        sm.updateSelection(1, 8)
        sm.endSelection()
        assertEquals("一二三四x五六", sm.getSelectedText(snap))
    }

    @Test
    fun widenToTheWholeScreenAndBack() {
        val sm = SelectionManager()
        sm.startSelection(1, 11, herdr.cols, SelectionMode.CHARACTER, herdr)
        sm.updateSelection(2, 21)
        sm.endSelection()

        sm.togglePaneConfinement(herdr.cols, herdr)
        assertNull(sm.selectionRange!!.pane)
        assertNotNull("can narrow back", sm.detectedPane)
        assertTrue(sm.isCellSelected(2, 3, herdr.lines[2]))

        sm.togglePaneConfinement(herdr.cols, herdr)
        assertNotNull(sm.selectionRange!!.pane)
        assertFalse(sm.isCellSelected(2, 3, herdr.lines[2]))
    }

    @Test
    fun lineModeStaysInThePane() {
        val sm = SelectionManager()
        sm.startSelection(1, 14, herdr.cols, SelectionMode.LINE, herdr)
        sm.updateSelection(2, 14)
        sm.endSelection()
        assertEquals("hello world\nsecond line", sm.getSelectedText(herdr))
        assertFalse(sm.isCellSelected(1, 2, herdr.lines[1]))
    }

    @Test
    fun clearAndSelectAllForgetThePane() {
        val sm = SelectionManager()
        sm.startSelection(1, 14, herdr.cols, SelectionMode.CHARACTER, herdr)
        sm.selectAllVisible(herdr.rows, herdr.cols)
        assertNull(sm.detectedPane)
        assertNull(sm.selectionRange!!.pane)
    }

    @Test
    fun herdrsScrollbarColumnBoundsThePane() {
        // herdr draws a scrolling pane's scrollbar in its last column: ▕ track, ▐ thumb.
        val snap = snapshot(
            "side│0123456789▕",
            "side│abc       ▐",
            "side│def       ▐",
            "side│ghi       ▕",
            cols = 16,
        )
        assertEquals(PaneRect(top = 0, bottom = 3, left = 5, right = 14), detect(snap, 1, 6))
    }

    @Test
    fun aReservedBlankLastColumnStillJoinsWrappedLines() {
        // Not scrolling, herdr leaves its scrollbar column blank: text wraps at
        // column 13 though the pane runs to 14. Real captured layout, shortened.
        val snap = snapshot(
            "side│先做数学x",
            "side│识点、难x",
            "side│多家",
            "side│end",
            cols = 15,
        )
        val sm = SelectionManager()
        sm.startSelection(0, 5, snap.cols, SelectionMode.CHARACTER, snap)
        sm.updateSelection(3, 7)
        sm.endSelection()
        // Rows 0 and 1 both end at column 13, one short of the pane's last column.
        assertEquals("先做数学x识点、难x多家\nend", sm.getSelectedText(snap))
    }

    @Test
    fun wordModeWorksInColumnsBesideACjkSidebar() {
        // On row 1 the sidebar's 慧苗 makes cell indices lag columns by two.
        val snap = snapshot(
            " spaces   │ 1   +",
            " ○ 慧苗   │alpha beta gamma",
            "          │",
            "          │",
        )
        val sm = SelectionManager()
        // Press on "beta" (columns 17..20), then cycle to Mode: Word.
        sm.startSelection(1, 18, snap.cols, SelectionMode.CHARACTER, snap)
        sm.endSelection()
        sm.setMode(SelectionMode.WORD, snap.cols, snap)

        val range = sm.selectionRange!!
        assertEquals(17, range.startCol)
        assertEquals(20, range.endCol)
        assertEquals("beta", sm.getSelectedText(snap))
    }

    @Test
    fun wordModeOnABlankPaneRowStaysInThePane() {
        // Nothing to snap to inside the pane: the trailing-whitespace fallback
        // must not reach "main" in the sidebar.
        val snap = snapshot(
            " spaces   │ 1   +",
            "   main   │",
            "          │text",
            "          │",
        )
        val sm = SelectionManager()
        sm.startSelection(1, 15, snap.cols, SelectionMode.CHARACTER, snap)
        sm.endSelection()
        sm.setMode(SelectionMode.WORD, snap.cols, snap)

        val range = sm.selectionRange!!
        assertTrue(range.startCol >= 11)
        assertTrue(range.endCol >= 11)
        assertEquals("", sm.getSelectedText(snap))
    }

    @Test
    fun edgeAutoScrollKeepsAStartAnchorAboveTheScreen() {
        val sm = SelectionManager()
        sm.startSelection(1, 12, herdr.cols, SelectionMode.CHARACTER, herdr)
        // Two lines scroll into scrollback under the finger, then the drag continues.
        sm.shiftSelectionStartByRows(-2)
        sm.updateSelection(3, 15)

        val range = sm.selectionRange!!
        assertEquals("start anchor kept in scrollback", -1, range.startRow)
        assertEquals(3, range.endRow)
    }

    @Test
    fun aDragBelowALowerPaneStillStopsAtItsTop() {
        // A pane that does not reach the screen top keeps its rows: no
        // scrollback above it belongs to it.
        val snap = snapshot(
            "side│top pane",
            "side├" + "─".repeat(25),
            "side│bottom a",
            "side│bottom b",
            "side│bottom c",
            "side│bottom d",
        )
        val sm = SelectionManager()
        sm.startSelection(3, 6, snap.cols, SelectionMode.CHARACTER, snap)
        sm.updateSelection(0, 6)
        assertEquals(2, sm.selectionRange!!.endRow)
    }
}
