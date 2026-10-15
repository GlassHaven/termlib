package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-user keyboard hide/show on a full-screen TUI living in the primary
 * buffer (opencode over the UML console): showing the keyboard shrinks the
 * grid (rows overflow into scrollback), hiding it grows the grid again — and
 * the app's repaint then lands in the wrong place, leaving stale rows
 * stranded mid-screen ("keyboard hide/show has no effect").
 *
 * A cursor-tracking TUI repaints relative to the cursor position its own
 * model holds, which is where its last write left the cursor. After a grow
 * the physical (native) cursor must therefore still be at that cell for the
 * app's repaint to land where the app thinks it is writing.
 *
 * The Kotlin snapshot cursor is not the right probe for this: resize emits
 * no movecursor callback, so it never moves on a grow and cannot catch the
 * desync. The probe is DSR (CSI 6n) — the emulator answers from the native
 * state cursor over the keyboard path, the same position the app's escape
 * semantics resolve against.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardGrowScrollbackTest {

    /** DSR responses arrive here as if typed; native parse is synchronous. */
    private val keyboardOut = mutableListOf<ByteArray>()

    private fun createEmulator(initialRows: Int, initialCols: Int): TerminalEmulatorImpl =
        TerminalEmulatorFactory.create(
            initialRows = initialRows,
            initialCols = initialCols,
            onKeyboardInput = { data -> synchronized(keyboardOut) { keyboardOut.add(data) } },
        ) as TerminalEmulatorImpl

    private fun visibleText(e: TerminalEmulatorImpl): String {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
        return e.snapshot.value.lines.joinToString("\n") { it.text.trimEnd() }
    }

    /** CSI 6n → native answers "ESC[row;colR" (1-based) on the keyboard path. */
    private fun nativeCursor(e: TerminalEmulatorImpl): Pair<Int, Int> {
        synchronized(keyboardOut) { keyboardOut.clear() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // onKeyboardInput posts to the main handler — trigger on the main
        // thread and let the looper drain before reading the collector.
        instrumentation.runOnMainSync {
            e.writeInput("\u001b[6n".toByteArray())
        }
        instrumentation.waitForIdleSync()
        val text = synchronized(keyboardOut) { keyboardOut.joinToString("") { String(it) } }
        val m = Regex("\u001b\\[(\\d+);(\\d+)R").find(text)
            ?: throw AssertionError("no DSR response in keyboard output: ${text.escapeRepr()}")
        // CPR is 1-based; convert to 0-based to match the snapshot cursor.
        return (m.groupValues[1].toInt() - 1) to (m.groupValues[2].toInt() - 1)
    }

    private fun String.escapeRepr() =
        map { if (it.code < 32) "\\u%04x".format(it.code) else it }.joinToString("")

    @Test
    fun `rows-only grow with scrollback keeps the cursor at its pre-grow cell`() = runBlocking {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        // 43 labelled lines: 20 flow into scrollback, 20..42 sit on the
        // 23-row screen, cursor ends just below the last line.
        for (i in 0..42) {
            emulator.writeInput("L%02d".format(i).toByteArray())
            if (i < 42) emulator.writeInput("\r\n".toByteArray())
        }
        delay(120)

        val beforeText = visibleText(emulator)
        org.junit.Assert.assertEquals(
            "precondition: screen should hold L20..L42",
            (20..42).joinToString("\n") { "L%02d".format(it) }.lines(),
            beforeText.lines(),
        )
        // Read the snapshot AFTER processing so scrollback/cursor are fresh.
        val before = emulator.snapshot.value
        val beforeScrollback = before.scrollback.size
        val beforeNative = nativeCursor(emulator)
        org.junit.Assert.assertEquals(
            "precondition: native cursor must sit at the pre-grow cell (22,3)",
            22 to 3,
            beforeNative,
        )

        // Keyboard hides: the grid grows back to 40 rows with scrollback
        // available to backfill from.
        emulator.resize(40, 51)
        delay(120)

        val afterNative = nativeCursor(emulator)
        val after = emulator.snapshot.value
        println("GROW-TEST scrollback $beforeScrollback -> ${after.scrollback.size}")
        println("GROW-TEST native $beforeNative -> $afterNative")
        println("GROW-TEST display (${before.cursorRow},${before.cursorCol}) -> (${after.cursorRow},${after.cursorCol})")
        val lines = visibleText(emulator).lines()
        lines.forEachIndexed { i, l -> println("GROW-TEST row %02d: %s".format(i, l)) }

        // When scrollback backfills into the top 17 rows, the pre-grow bottom
        // line (L42) moves down to row 39, so the cursor on L42 must move with
        // its line to (39, 3). Keeping it at (22, 3) strands it mid-screen on
        // the shifted L25 line (#687, #689, #712).
        assertEquals(
            "rows-only grow with scrollback backfill must keep cursor on its line (L42 at row 39)",
            39 to 3,
            afterNative,
        )
        assertEquals(
            "display cursor must agree with the native cursor after the grow",
            afterNative,
            after.cursorRow to after.cursorCol,
        )
    }

    /**
     * Exact reproduction of #689 ("root@GreenCloud:~# lied with ESM Apps.") and #687:
     * 1. A 23-row screen is filled with Ubuntu MOTD + prompt at bottom row 22
     *    (with earlier MOTD lines in scrollback).
     * 2. Screen grows to 40 rows (e.g. rotation or keyboard hide) with backfill on.
     * 3. Remote bash/readline receives SIGWINCH and redraws its prompt via CR + prompt.
     * 4. Screen shrinks back to 23 rows.
     *
     * Before #712 fix: growing to 40 rows shifted the old prompt to row 39, but
     * `new_cursor.row--` moved the cursor back to row 22 onto the MOTD line
     * `"0 updates can be applied with ESM Apps."`. Readline's `\rroot@GreenCloud:~# `
     * then overwrote the first 19 chars of row 22 to produce
     * `"root@GreenCloud:~# lied with ESM Apps."`, and shrinking back to 23 rows
     * pushed rows 0..16 to scrollback, stranding the corrupted prompt near the top!
     */
    @Test
    fun `shell prompt after MOTD stays on bottom row across grow SIGWINCH redraw and shrink`() = runBlocking {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        // Write 20 lines that scroll into scrollback, followed by 22 MOTD lines
        // including "0 updates can be applied with ESM Apps." at what will become
        // row 22 after a 17-row backfill, and the shell prompt on the last row.
        for (i in 0..19) {
            emulator.writeInput("scrollback-$i\r\n".toByteArray())
        }
        for (i in 0..21) {
            val line = if (i == 5) {
                "0 updates can be applied with ESM Apps."
            } else {
                "motd-line-$i"
            }
            emulator.writeInput("$line\r\n".toByteArray())
        }
        val prompt = "root@GreenCloud:~# "
        emulator.writeInput(prompt.toByteArray())
        delay(120)

        val beforeGrow = visibleText(emulator).lines()
        assertEquals("precondition: prompt on bottom row 22", prompt.trimEnd(), beforeGrow[22])
        assertEquals("precondition: cursor at (22, 19)", 22 to prompt.length, nativeCursor(emulator))

        // Grow to 40 rows (e.g. portrait rotation or keyboard hide)
        emulator.resize(40, 51)
        delay(120)
        // Simulate bash/readline SIGWINCH prompt redraw: CR + prompt + clear-to-EOL
        emulator.writeInput("\r$prompt\u001b[K".toByteArray())
        delay(120)

        val grownLines = visibleText(emulator).lines()
        assertEquals(
            "MOTD line at row 22 must not be overwritten by readline SIGWINCH redraw (#689)",
            "0 updates can be applied with ESM Apps.",
            grownLines[22],
        )
        assertEquals(
            "prompt must remain at bottom row 39 after grow + SIGWINCH redraw",
            prompt.trimEnd(),
            grownLines[39],
        )
        assertEquals(
            "cursor must be at bottom row 39 after grow + SIGWINCH redraw",
            39 to prompt.length,
            nativeCursor(emulator),
        )

        // Shrink back to 23 rows (e.g. rotate back or keyboard show)
        emulator.resize(23, 51)
        delay(120)
        emulator.writeInput("\r$prompt\u001b[K".toByteArray())
        delay(120)

        val shrunkLines = visibleText(emulator).lines()
        val afterShrink = emulator.snapshot.value
        assertEquals(
            "prompt must be on bottom row 22 after shrinking back to 23 rows",
            prompt.trimEnd(),
            shrunkLines[22],
        )
        assertEquals(
            "cursor must be on bottom row 22 (not stranded at top row, #687/#689)",
            22 to prompt.length,
            afterShrink.cursorRow to afterShrink.cursorCol,
        )
    }

    /**
     * #717: Once the scrollback ring buffer reaches `maxScrollbackLines`,
     * streaming output evicts the oldest line (`scrollback.removeAt(0)`) on
     * every new line pushed so `scrollback.size` stays constant. A user
     * scrolled up reading history must still have `scrollbackPosition`
     * incremented by the number of evicted lines so the visible viewport stays
     * pinned to the same historical line until that line itself is evicted.
     */
    @Test
    fun `scrolled up viewport does not drift when scrollback ring buffer is full`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 3,
            initialCols = 20,
            maxScrollbackLines = 4,
        ) as TerminalEmulatorImpl

        // Write 7 lines into a 3-row terminal with maxScrollbackLines = 4:
        // Scrollback (4 lines, full): line-1, line-2, line-3, line-4
        // Screen (3 lines):           line-5, line-6, line-7
        val initial = (1..7).joinToString("\r\n") { "line-$it" }
        emulator.writeInput(initial.toByteArray())
        delay(120)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        emulator.processPendingUpdates()

        val state = TerminalScreenState(emulator.snapshot.value)
        assertEquals(4, state.snapshot.scrollback.size)

        // Scroll up 1 line: viewport should show line-4, line-5, line-6
        state.scrollBy(1)
        assertEquals(1, state.scrollbackPosition)
        assertEquals("line-4", state.getVisibleLine(0).text.trimEnd())
        assertEquals("line-5", state.getVisibleLine(1).text.trimEnd())
        assertEquals("line-6", state.getVisibleLine(2).text.trimEnd())

        // Stream 2 more lines ("line-8", "line-9") while scrollback is full at 4 lines
        emulator.writeInput("\r\nline-8\r\nline-9".toByteArray())
        delay(120)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        emulator.processPendingUpdates()

        state.updateSnapshot(emulator.snapshot.value)

        // Scrollback size is still 4 (line-1 and line-2 evicted; line-5 and line-6 pushed),
        // so scrollbackPosition must advance from 1 to 3 to keep line-4, line-5, line-6 visible.
        assertEquals(3, state.scrollbackPosition)
        assertEquals("line-4", state.getVisibleLine(0).text.trimEnd())
        assertEquals("line-5", state.getVisibleLine(1).text.trimEnd())
        assertEquals("line-6", state.getVisibleLine(2).text.trimEnd())
    }
}