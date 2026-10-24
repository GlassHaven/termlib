package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #749: fast scroll up/down corrupts the model buffer (permanent row
 * misalignment, not a torn read). Suspect: the VTERM_DAMAGE_SCROLL coalescing
 * path in screen.c — scrollrect() accumulates into pending_scrollrect and
 * adjusts the live damage region; a mis-adjustment leaves the damage rect
 * pointing at rows the model no longer holds, so updateLine() rebuilds the
 * wrong row and a neighbour char bleeds to col 0.
 *
 * These tests drive the coalescing path deterministically: scroll batches and
 * redraws interleaved inside single writeInput() calls (one parse batch = one
 * coalescing window), then compare the snapshot against hand-computed ground
 * truth. A clean run here means the native coalescing is sound and the
 * corruption lives elsewhere (app-side scroll forwarding / tmux mouse path).
 */
@RunWith(AndroidJUnit4::class)
class ScrollDamageCoalescingTest {

    private fun settle(e: TerminalEmulatorImpl) {
        e.processPendingUpdates()
    }

    private fun screenTexts(e: TerminalEmulatorImpl): List<String> {
        settle(e)
        return e.snapshot.value.lines.map { it.text.trimEnd() }
    }

    /** Fill a 24x80 screen with line-001..line-024, cursor parked at the bottom. */
    private fun fillScreen(e: TerminalEmulatorImpl) {
        val sb = StringBuilder()
        for (i in 1..24) {
            sb.append("line-%03d".format(i))
            if (i < 24) sb.append("\r\n")
        }
        e.writeInput(sb.toString().toByteArray())
        settle(e)
    }

    /** No visible row may carry a neighbour char shifted into col 0. */
    private fun assertNoCol0Shift(texts: List<String>) {
        for ((row, t) in texts.withIndex()) {
            if (t.isNotEmpty()) {
                assertEquals("row $row starts with a shifted neighbour char: '$t'", t.trimStart(), t)
            }
        }
    }

    @Test
    fun `coalesced scroll up shifts content exactly and blanks the bottom`() {
        val e = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        fillScreen(e)

        // 10 LFs at the bottom margin in one batch: coalesced into
        // pending_scroll_downward=10. A redraw mid-batch interleaves damage
        // with the pending scroll — the window the reporter's fast scroll hits
        // under a busy redraw.
        val up = StringBuilder()
        repeat(10) { up.append("\r\n") }
        up.append("REDRAW")
        e.writeInput(up.toString().toByteArray())
        settle(e)

        val texts = screenTexts(e)
        // Top 10 lines pushed to scrollback; line-011..line-024 now at rows
        // 0..13; rows 14..22 blank; row 23 carries the mid-batch redraw.
        assertEquals("line-011", texts[0])
        assertEquals("line-024", texts[13])
        assertEquals("REDRAW", texts[23])
        for (r in 14..22) assertEquals("row $r should be blank", "", texts[r])
        assertNoCol0Shift(texts)
    }

    @Test
    fun `coalesced reverse-index down preserves row content`() {
        val e = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        fillScreen(e)

        // Scroll up 10, then reverse-index (ESC M) 10 at the top margin in the
        // same batch — the up/down pair the reporter performs fast. Reverse
        // index shifts content down and blanks the top; scrollback lines do not
        // return, so ground truth is the shifted remainder, not the original.
        val batch = StringBuilder()
        repeat(10) { batch.append("\r\n") }
        batch.append("\u001b[1;1H")
        repeat(10) { batch.append("\u001bM") }
        batch.append("TOP-REDRAW")
        e.writeInput(batch.toString().toByteArray())
        settle(e)

        val texts = screenTexts(e)
        // After up-10 then down-10: line-011..line-024 land at rows 10..23,
        // rows 0..9 blank except the redraw written at row 0.
        assertEquals("TOP-REDRAW", texts[0])
        assertEquals("line-011", texts[10])
        assertEquals("line-024", texts[23])
        for (r in 1..9) assertEquals("row $r should be blank", "", texts[r])
        assertNoCol0Shift(texts)
    }

    @Test
    fun `damage region after coalesced scroll rebuilds the right rows`() {
        val e = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        fillScreen(e)

        // One batch: 3 scrolls (coalesced into pending_scroll_downward=3) then
        // a putglyph damage on the bottom row. Flush must damage both the
        // scrolled region and the redrawn row, and every rebuilt row must match
        // the native model.
        e.writeInput("\r\n\r\n\r\nBOTTOM-REDRAW".toByteArray())
        settle(e)

        val texts = screenTexts(e)
        // After 3 scrolls up: line-004..line-024 shifted to rows 0..20,
        // rows 21-22 blank, row 23 = BOTTOM-REDRAW.
        assertEquals("line-004", texts[0])
        assertEquals("BOTTOM-REDRAW", texts[23])
        assertNoCol0Shift(texts)
    }
}
