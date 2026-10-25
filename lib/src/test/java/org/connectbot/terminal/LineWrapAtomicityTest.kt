package org.connectbot.terminal

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Deterministic host tests for soft-wrap / line-continuation atomicity and
 * damage invalidation across getRowRuns(), cross-chunk autowrap, and scrollback.
 */
@RunWith(AndroidJUnit4::class)
class LineWrapAtomicityTest {

    private fun settle(e: TerminalEmulatorImpl) {
        e.processPendingUpdates()
    }

    @Test
    fun `row rebuild fetches softWrapped atomically with row cells across seam`() {
        val e = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 10) as TerminalEmulatorImpl
        settle(e)

        // Arm the seam: right after row 0's cells ("X         ") are fetched, a racing
        // write overwrites row 0..1 with a soft-wrapped 15-char line so native
        // lineinfo[1].continuation flips to true.
        e.onCellRunFetchedForTesting = {
            e.writeInput("\u001b[1;1H0123456789abcde".toByteArray(Charsets.UTF_8))
            e.onCellRunFetchedForTesting = null
        }

        e.writeInput("X".toByteArray(Charsets.UTF_8))
        settle(e)

        // State 1 ("X") was NOT soft-wrapped. Reading getLineContinuation(1) in a
        // separate lock hold after the seam spliced State-1 cells ("X") with State-2
        // softWrapped=true.
        val firstPassRow0 = e.snapshot.value.lines[0]
        assertEquals("X", firstPassRow0.text.trimEnd())
        assertFalse(
            "row 0 spliced pre-wrap cells with post-wrap softWrapped=true",
            firstPassRow0.softWrapped,
        )

        // Draining the racing write's queued damage converges on State 2.
        settle(e)
        shadowOf(Looper.getMainLooper()).idle()
        val secondPass = e.snapshot.value.lines
        assertEquals("0123456789", secondPass[0].text.trimEnd())
        assertTrue(secondPass[0].softWrapped)
        assertEquals("abcde", secondPass[1].text.trimEnd())
        assertFalse(secondPass[1].softWrapped)
    }

    @Test
    fun `autowrap across two writeInput chunks updates previous row softWrapped`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 10) as TerminalEmulatorImpl
        settle(e)

        // Chunk 1 fills row 0 to column 9; libvterm defers wrap (at_phantom=1), so row 0
        // is not yet wrapped.
        e.writeInput("0123456789".toByteArray(Charsets.UTF_8))
        settle(e)
        assertEquals("0123456789", e.snapshot.value.lines[0].text.trimEnd())
        assertFalse(e.snapshot.value.lines[0].softWrapped)

        // Chunk 2 writes the continuation onto row 1. libvterm sets lineinfo[1].continuation=1
        // and damages only row 1; cached row 0 must still learn softWrapped=true.
        e.writeInput("abcdef".toByteArray(Charsets.UTF_8))
        settle(e)
        assertEquals("0123456789", e.snapshot.value.lines[0].text.trimEnd())
        assertEquals("abcdef", e.snapshot.value.lines[1].text.trimEnd())
        assertTrue(
            "row 0 softWrapped remained stale false after chunk 2 wrapped onto row 1",
            e.snapshot.value.lines[0].softWrapped,
        )

        // Deleting row 1 (CSI M on 1-based row 2) clears lineinfo[1].continuation without
        // damaging row 0; cached row 0 must update back to softWrapped=false.
        e.writeInput("\u001b[2;1H\u001b[M".toByteArray(Charsets.UTF_8))
        settle(e)
        assertFalse(
            "row 0 softWrapped remained stale true after row 1 was deleted",
            e.snapshot.value.lines[0].softWrapped,
        )
    }

    @Test
    fun `two-row soft-wrapped line scrolled into scrollback preserves softWrapped`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 10) as TerminalEmulatorImpl
        settle(e)

        // Rows 0..1 hold a 15-char soft-wrapped line; rows 2..3 hold hard lines; one more
        // newline scrolls row 0 ("0123456789") into scrollback[0].
        e.writeInput("0123456789abcde\r\nrow2\r\nrow3\r\nrow4".toByteArray(Charsets.UTF_8))
        settle(e)

        val sb = e.snapshot.value.scrollback
        assertEquals(1, sb.size)
        assertEquals("0123456789", sb[0].text.trimEnd())
        assertTrue(
            "scrollback[0] lost softWrapped=true on single-line scroll",
            sb[0].softWrapped,
        )
    }

    @Test
    fun `multi-line scroll and resize shrink preserve per-row softWrapped in scrollback`() {
        val e = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 10) as TerminalEmulatorImpl
        settle(e)

        // Row 0..1: soft-wrapped "0123456789" + "abcde"; Row 2: "hard2"; Row 3..4: soft-wrapped "ABCDEFGHIJ" + "klmno".
        e.writeInput("0123456789abcde\r\nhard2\r\nABCDEFGHIJklmno".toByteArray(Charsets.UTF_8))
        settle(e)

        // Scroll up 2 lines at once via CSI 2 S: pushes row 0 (softWrapped=true) and row 1 (softWrapped=false).
        e.writeInput("\u001b[2S".toByteArray(Charsets.UTF_8))
        settle(e)

        val sbAfterScroll = e.snapshot.value.scrollback
        assertEquals(2, sbAfterScroll.size)
        assertEquals("0123456789", sbAfterScroll[0].text.trimEnd())
        assertTrue("expected scrollback[0] softWrapped=true", sbAfterScroll[0].softWrapped)
        assertEquals("abcde", sbAfterScroll[1].text.trimEnd())
        assertFalse("expected scrollback[1] softWrapped=false", sbAfterScroll[1].softWrapped)

        // Now shrink height from 5 to 2 rows, pushing remaining top lines into scrollback.
        e.resize(2, 10)
        settle(e)

        val sbAfterResize = e.snapshot.value.scrollback
        // "hard2" (softWrapped=false) was pushed before the remaining wrapped pair.
        assertEquals("hard2", sbAfterResize[2].text.trimEnd())
        assertFalse("expected 'hard2' softWrapped=false", sbAfterResize[2].softWrapped)
    }
}
