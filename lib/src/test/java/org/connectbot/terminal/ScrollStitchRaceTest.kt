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
}
