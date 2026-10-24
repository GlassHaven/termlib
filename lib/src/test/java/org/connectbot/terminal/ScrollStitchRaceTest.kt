package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #749: fast scroll up/down corrupts the model buffer permanently.
 *
 * Device evidence (5.89.52, CPH2655, tmux ground truth): rows carry
 * intra-row corruption - merged words ("isthe"), transposed chars
 * ("orows"), substituted chars ("tungel"), eaten leading spaces - while
 * whole rows stay in place. That signature is a STITCH: the row cache is
 * rebuilt from two different native states, because updateLine() calls
 * getCellRun() once per run and each call takes/releases the native lock
 * independently, while writeInput() mutates the model under that same
 * lock from the SSH reader thread.
 *
 * This test injects a scroll at the seam between two getCellRun() calls
 * of one row rebuild (onCellRunFetchedForTesting) and asserts the cached
 * row equals the native model's FINAL state. On the shipped per-run fetch
 * it stitches; it passes only when a row is fetched atomically w.r.t.
 * input.
 */
@RunWith(AndroidJUnit4::class)
class ScrollStitchRaceTest {

    private fun settle(e: TerminalEmulatorImpl) {
        e.processPendingUpdates()
    }

    @Test
    @Ignore(
        "Red until updateLine() fetches a row atomically w.r.t. input (#749). " +
            "Verified failing on the shipped per-run getCellRun loop: the cached " +
            "row keeps a stale pre-scroll run while the native model scrolled. " +
            "Un-ignore with the atomic row-fetch fix.",
    )
    fun `row rebuild is atomic against a concurrent scroll`() {
        val e = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        // Row 0: two colour runs (A-run default attrs, B-run reverse) so the
        // rebuild makes >=2 getCellRun calls. Rows 1..23 blank.
        e.writeInput("\u001b[0mAAAAAAAA\u001b[7mBBBBBBBB\r\n".toByteArray())
        settle(e)

        // Park the cursor on row 0 (no damage), then arm the seam.
        e.writeInput("\u001b[H".toByteArray())
        settle(e)
        e.onCellRunFetchedForTesting = {
            // Lands exactly between run 1 and run 2 of row 0's rebuild:
            // cursor to the bottom margin, two LFs -> scroll up two lines.
            // Native row 0 becomes old row 2 - blank. The already-fetched
            // run 1 is stale A-run content.
            e.writeInput("\u001b[24;1H\r\n\r\n".toByteArray())
            e.onCellRunFetchedForTesting = null
        }

        // Damage row 0 so processPendingUpdates rebuilds it through the seam.
        e.writeInput("X".toByteArray())
        settle(e)

        val texts = e.snapshot.value.lines.map { it.text.trimEnd() }
        // Ground truth: after the injected scroll, native row 0 is blank
        // (old row 2). A cached row still holding A/B content is a stitch of
        // the pre-scroll state - the #749 corruption.
        assertEquals(
            "row 0 stitched from a stale native state: '${texts[0]}'",
            "",
            texts[0].trim(),
        )
    }
}
