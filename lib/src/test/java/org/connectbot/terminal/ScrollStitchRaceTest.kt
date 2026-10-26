package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #749: fast scroll up/down corrupts the model buffer permanently.
 *
 * Device evidence (5.89.52, tmux ground truth): rows carry intra-row
 * corruption - merged words ("isthe"), transposed chars ("orows"),
 * substituted chars ("tungel"), eaten leading spaces - while whole rows
 * stay in place. That signature is a STITCH: the row cache was rebuilt
 * from two different native states, because updateLine() used to call
 * getCellRun() once per style run and each call took and released the
 * native lock independently, while writeInput() mutated the model under
 * that same lock from the SSH reader thread.
 *
 * The seam (onCellRunFetchedForTesting) fires at the point where a
 * concurrent write used to land: between two per-run fetches of one row
 * rebuild - now, after the atomic getRowRuns() fix, entirely outside the
 * fetch. The property: a rebuilt row must equal ONE consistent native
 * state, never a splice. A write racing the rebuild is either fully before
 * it (its damage was drained with this batch) or fully after (its damage
 * re-damages the row and rebuilds it again) - so the cached row is the
 * post-write pre-scroll state immediately, and the final native state once
 * the racing write's damage drains.
 *
 * On the shipped per-run loop this failed: the cached row was "X" - the
 * post-write first run spliced onto post-scroll blanks - a state the
 * terminal never had.
 */
@RunWith(AndroidJUnit4::class)
class ScrollStitchRaceTest {

    private fun settle(e: TerminalEmulatorImpl) {
        e.processPendingUpdates()
    }

    @Test
    fun `row rebuild reflects one native state, not a splice`() {
        val e = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        // Row 0: two colour runs (A-run default attrs, B-run reverse) so the
        // rebuild spans multiple runs. Rows 1..23 blank.
        e.writeInput("\u001b[0mAAAAAAAA\u001b[7mBBBBBBBB\r\n".toByteArray())
        settle(e)

        // Park the cursor on row 0 (no damage), then arm the seam.
        e.writeInput("\u001b[H".toByteArray())
        settle(e)
        e.onCellRunFetchedForTesting = {
            // Lands at the exact point a concurrent SSH-reader write used to
            // interleave: after the first run of the rebuild was fetched.
            // Cursor to the bottom margin, two LFs -> scroll up two lines;
            // native row 0 becomes old row 2 - blank.
            e.writeInput("\u001b[24;1H\r\n\r\n".toByteArray())
            e.onCellRunFetchedForTesting = null
        }

        // Damage row 0 so processPendingUpdates rebuilds it through the seam.
        e.writeInput("X".toByteArray())
        settle(e)

        val texts = e.snapshot.value.lines.map { it.text.trimEnd() }
        // The rebuild must be the consistent post-write, pre-scroll state -
        // X replaced the first A (same default attrs, so one run), B run
        // intact. The shipped per-run loop produced "X" here: the post-write
        // first run spliced onto post-scroll blanks.
        assertEquals(
            "row 0 stitched from two native states",
            "XAAAAAAABBBBBBBB",
            texts[0],
        )

        // The racing scroll's damage was queued during the rebuild; once it
        // drains, the row converges on the final native state (blank).
        settle(e)
        val after = e.snapshot.value.lines.map { it.text.trimEnd() }
        assertEquals(
            "row 0 did not converge on the final native state",
            "",
            after[0].trim(),
        )
    }

    @Test
    fun `multi-row rebuild does not tear across concurrent thread writeInput`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl
        e.writeInput("ROW0_STATE1\r\nROW1_STATE1\r\nROW2_STATE1\r\nROW3_STATE1".toByteArray())

        val row0Fetched = java.util.concurrent.CountDownLatch(1)
        val writerEntered = java.util.concurrent.CountDownLatch(1)
        var writerThread: Thread? = null

        e.onCellRunFetchedForTesting = {
            e.onCellRunFetchedForTesting = null
            row0Fetched.countDown()
            // Give the background SSH reader thread a chance to call writeInput()
            // while processPendingUpdates() is between row 0 and row 1.
            writerEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(20)
        }

        writerThread = Thread {
            row0Fetched.await(2, java.util.concurrent.TimeUnit.SECONDS)
            writerEntered.countDown()
            // Scroll up 1 line on the SSH reader thread: native row 0 becomes ROW1_STATE1,
            // native row 1 becomes ROW2_STATE1, and scrollback gets ROW0_STATE1.
            e.writeInput("\r\nROW4_STATE2".toByteArray())
        }.apply { start() }

        settle(e)
        writerThread.join(2000)

        // The snapshot emitted by the first settle(e) must NOT be a torn hybrid where
        // lines[0] is ROW0_STATE1 (pre-scroll) while lines[1] is ROW2_STATE1 (post-scroll,
        // skipping ROW1_STATE1) and scrollback already contains ROW0_STATE1.
        val snap1 = e.snapshot.value
        val lines1 = snap1.lines.map { it.text.trimEnd() }
        assertEquals("ROW0_STATE1", lines1[0])
        assertEquals(
            "multi-row snapshot tore mid-frame across concurrent writeInput (ROW1_STATE1 skipped)",
            "ROW1_STATE1",
            lines1[1],
        )
        assertEquals(
            "scrollback in pre-scroll snapshot must not already contain post-scroll line",
            0,
            snap1.scrollback.size,
        )

        // Draining the second write converges to STATE2.
        settle(e)
        val snap2 = e.snapshot.value
        val lines2 = snap2.lines.map { it.text.trimEnd() }
        assertEquals("ROW1_STATE1", lines2[0])
        assertEquals("ROW2_STATE1", lines2[1])
        assertEquals("ROW3_STATE1", lines2[2])
        assertEquals("ROW4_STATE2", lines2[3])
        assertEquals(1, snap2.scrollback.size)
        assertEquals("ROW0_STATE1", snap2.scrollback[0].text.trimEnd())
    }

    @Test
    fun `concurrent osc8 hyperlink during rebuild is not dropped by premature segment drain`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 40) as TerminalEmulatorImpl
        e.writeInput("row0".toByteArray())

        e.onCellRunFetchedForTesting = {
            e.onCellRunFetchedForTesting = null
            // Simulate a write arriving on row 2 with an OSC 8 hyperlink while row 0 is rebuilding.
            e.writeInput("\u001b[3;1H\u001b]8;;https://example.com\u0007clickme\u001b]8;;\u0007".toByteArray())
        }

        // First settle rebuilds row 0 (the only row damaged before the seam).
        settle(e)
        // Second settle rebuilds row 2 (damaged by the seam write).
        settle(e)

        val row2 = e.snapshot.value.lines[2]
        assertEquals("clickme", row2.text.trimEnd())
        assertEquals(
            "OSC 8 hyperlink on row 2 was dropped by premature pendingSemanticSegments drain",
            "https://example.com",
            row2.getHyperlinkUrlAt(0, false),
        )
    }
}
