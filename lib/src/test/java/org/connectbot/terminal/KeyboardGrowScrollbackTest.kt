package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keyboard hide/show on the primary buffer: showing the keyboard shrinks the
 * grid (rows overflow into scrollback), hiding it grows the grid again and
 * libvterm backfills the new rows from scrollback, shifting the screen down.
 *
 * The cursor has to move with its line. Programs that redraw in place on
 * SIGWINCH (RouterOS's console, shell line editors) write "\r<prompt>\e[K"
 * at the cursor; if the cursor stayed at its pre-grow cell while the content
 * moved, that redraw — and every line of output after it — lands on top of
 * restored history instead of on the prompt's own line.
 *
 * The probe is DSR (CSI 6n): the emulator answers from the native state
 * cursor over the keyboard path, the same position the remote's escape
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

    /** 43 labelled lines on a 23x51 grid: L00..L19 in scrollback, L20..L42 on screen. */
    private fun filledEmulator(): TerminalEmulatorImpl {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        for (i in 0..42) {
            emulator.writeInput("L%02d".format(i).toByteArray())
            if (i < 42) emulator.writeInput("\r\n".toByteArray())
        }
        assertEquals(
            "precondition: screen should hold L20..L42",
            (20..42).map { "L%02d".format(it) },
            visibleText(emulator).lines(),
        )
        assertEquals("precondition: native cursor after L42", 22 to 3, nativeCursor(emulator))
        return emulator
    }

    @Test
    fun `rows-only grow with scrollback moves the cursor with its line`() = runBlocking {
        val emulator = filledEmulator()

        // Keyboard hides: 23 -> 40 rows, 17 rows backfilled from scrollback.
        emulator.resize(40, 51)
        delay(120)

        val lines = visibleText(emulator).lines()
        assertEquals("backfill restores L03..L42", (3..42).map { "L%02d".format(it) }, lines)
        val (row, col) = nativeCursor(emulator)
        assertEquals("cursor stays at the end of L42", 3, col)
        assertEquals("cursor row holds L42", "L42", lines[row])
        val snapshot = emulator.snapshot.value
        assertEquals(
            "display cursor must agree with the native cursor after the grow",
            row to col,
            snapshot.cursorRow to snapshot.cursorCol,
        )
    }

    @Test
    fun `line editor redraw after grow lands on its own line`() = runBlocking {
        val emulator = filledEmulator()
        emulator.resize(40, 51)
        delay(120)

        // RouterOS on SIGWINCH: redraw the prompt line in place, then the
        // command runs and prints below it.
        emulator.writeInput("\r> \u001b[Kcmd\r\nout1\r\nout2\r\n> ".toByteArray())
        delay(120)

        val lines = visibleText(emulator).lines()
        assertEquals(
            "history intact, prompt redrawn over L42, output below",
            (6..41).map { "L%02d".format(it) } + listOf("> cmd", "out1", "out2", ">"),
            lines,
        )
    }
}
