package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the keyboard-toggle artifact (the cursor half is pinned
 * by KeyboardGrowScrollbackTest): a full-screen TUI with an incremental
 * line-diff renderer — opencode runs on Ink, whose log-update render skips
 * writing any line whose new text equals the previous frame's text at that
 * index. A rows-only grow that backfills scrollback reflows the live content
 * DOWN under the app; the app's diff has no way to know, so every line it
 * skips leaves the popped scrollback stranded in the grid where the app's
 * model wants blank (or its own) content.
 *
 * The UML guest console sets backfillScrollbackOnGrow=false: the grow then
 * anchors the screen at the top with blank rows at the bottom — the layout
 * the diff renderer's model already assumes — and the app's WINCH repaint
 * fills the new rows without stale remnants.
 *
 * The Ink repaint is simulated with its actual move vocabulary: relative
 * cursorUp to the frame top, then per row cursorNextLine on skip, or
 * CR + text + EL + LF on write (no LF on the last row).
 */
@RunWith(AndroidJUnit4::class)
class GrowBackfillDiffRenderTest {

    private val keyboardOut = mutableListOf<ByteArray>()

    private fun createEmulator(initialRows: Int, initialCols: Int): TerminalEmulatorImpl =
        TerminalEmulatorFactory.create(
            initialRows = initialRows,
            initialCols = initialCols,
            onKeyboardInput = { data -> synchronized(keyboardOut) { keyboardOut.add(data) } },
        ) as TerminalEmulatorImpl

    private fun visibleText(e: TerminalEmulatorImpl): List<String> {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
        return e.snapshot.value.lines.map { it.text.trimEnd() }
    }

    /**
     * delay() in runBlocking does not pump Robolectric's main looper, so the
     * snapshot (scrollback included) stays stale until something forces a
     * rebuild — drain the looper and rebuild before asserting on it.
     */
    private fun refresh(e: TerminalEmulatorImpl) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
    }

    private fun nativeCursor(e: TerminalEmulatorImpl): Pair<Int, Int> {
        synchronized(keyboardOut) { keyboardOut.clear() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            e.writeInput("\u001b[6n".toByteArray())
        }
        instrumentation.waitForIdleSync()
        val text = synchronized(keyboardOut) { keyboardOut.joinToString("") { String(it) } }
        val m = Regex("\u001b\\[(\\d+);(\\d+)R").find(text)
            ?: throw AssertionError("no DSR response in keyboard output: $text")
        return (m.groupValues[1].toInt() - 1) to (m.groupValues[2].toInt() - 1)
    }

    /** 43 labelled lines flow 20 into scrollback; blank two live rows; park the cursor. */
    private suspend fun writeFramedScreen(e: TerminalEmulatorImpl): List<String> {
        for (i in 0..42) {
            e.writeInput("L%02d".format(i).toByteArray())
            if (i < 42) e.writeInput("\r\n".toByteArray())
        }
        // Rows 5 and 10 of the 23-row screen become blank frame rows, the way
        // an Ink layout's spacing rows sit between message blocks.
        e.writeInput("\u001b[6;1H\u001b[2K".toByteArray())
        e.writeInput("\u001b[11;1H\u001b[2K".toByteArray())
        e.writeInput("\u001b[23;1H".toByteArray())
        delay(120)
        val frame = visibleText(e)
        assertEquals(23, frame.size)
        assertEquals("precondition: row 5 blanked", "", frame[5])
        assertEquals("precondition: row 10 blanked", "", frame[10])
        return frame
    }

    /**
     * The 40-row frame the app would render after the keyboard hides: new
     * message rows on top (blank spacing rows kept at the same indices) and
     * the previous frame pushed down 17 rows below them.
     */
    private fun expectedGrowFrame(prev: List<String>): List<String> {
        val top = (0..16).map { if (it == 5 || it == 10) "" else "N%02d".format(it) }
        return top + prev
    }

    private fun inkRepaint(e: TerminalEmulatorImpl, prevFrame: List<String>, newFrame: List<String>) {
        // Ink's render(): return to the bottom of the previous block (the
        // cursor is already parked on its last row), walk to the top, then
        // diff row by row.
        e.writeInput("\u001b[${prevFrame.size - 1}A".toByteArray())
        for (i in newFrame.indices) {
            val isLast = i == newFrame.size - 1
            if (i < prevFrame.size && newFrame[i] == prevFrame[i]) {
                e.writeInput("\u001b[E".toByteArray())
            } else {
                e.writeInput(("\r" + newFrame[i] + "\u001b[K" + if (isLast) "" else "\n").toByteArray())
            }
        }
    }

    /**
     * Characterization: with the default backfill on, the grow pops scrollback
     * into the top rows and an Ink-style diff repaint strands it in every
     * skipped line — the mechanism behind the user-visible artifact.
     */
    @Test
    fun `backfilled grow strands popped rows under a diff-render repaint`() = runBlocking {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        val prev = writeFramedScreen(emulator)
        assertEquals("precondition: 20 lines in scrollback", 20, emulator.snapshot.value.scrollback.size)
        assertEquals("precondition: cursor parked at (22,0)", 22 to 0, nativeCursor(emulator))

        emulator.resize(40, 51)
        refresh(emulator)
        assertEquals("backfilled grow pops 17 rows", 3, emulator.snapshot.value.scrollback.size)
        assertEquals("cursor stays at its pre-grow cell", 22 to 0, nativeCursor(emulator))

        val newFrame = expectedGrowFrame(prev)
        inkRepaint(emulator, prev, newFrame)

        val grid = visibleText(emulator)
        val stale = grid.indices.filter { grid[it] != newFrame.getOrElse(it) { "" } }
        org.junit.Assert.assertTrue(
            "expected the backfill artifact (stale rows under a diff-render repaint), got stale=$stale " +
                "grid=${grid.joinToString("|")} expected=${newFrame.joinToString("|")}",
            stale.contains(5) && stale.contains(10),
        )
    }

    /**
     * With the backfill off, the grow must not reflow: history stays in
     * scrollback, the screen keeps its content at the top, blank rows open at
     * the bottom, and the cursor stays at its pre-grow cell.
     */
    @Test
    fun `no-backfill grow keeps history in scrollback and blanks the bottom`() = runBlocking {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        val prev = writeFramedScreen(emulator)
        emulator.backfillScrollbackOnGrow = false

        emulator.resize(40, 51)
        refresh(emulator)

        assertEquals("history must stay in scrollback", 20, emulator.snapshot.value.scrollback.size)
        assertEquals("cursor stays at its pre-grow cell", 22 to 0, nativeCursor(emulator))
        val grid = visibleText(emulator)
        assertEquals("screen content stays anchored at the top", prev, grid.subList(0, 23))
        assertEquals(
            "rows below the old screen must be blank",
            List(17) { "" },
            grid.subList(23, 40),
        )
    }

    /**
     * The regression: after a no-backfill grow, the app's incremental diff
     * repaint produces exactly its intended frame — no stale cells anywhere.
     */
    @Test
    fun `diff-render repaint after a no-backfill grow leaves no stale rows`() = runBlocking {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        val prev = writeFramedScreen(emulator)
        emulator.backfillScrollbackOnGrow = false

        emulator.resize(40, 51)
        delay(120)

        val newFrame = expectedGrowFrame(prev)
        inkRepaint(emulator, prev, newFrame)

        assertEquals(
            "grid must equal the app's intended frame after its repaint",
            newFrame,
            visibleText(emulator),
        )
    }
}