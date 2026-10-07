package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rows a grow backfills from scrollback must come back in the terminal's
 * default colours, not as solid rectangles.
 *
 * Scrollback stores resolved RGB, so a default-coloured blank line went out as
 * whatever RGB the default was at push time and came back as an explicit
 * colour. The renderer paints any background that differs from the current
 * default, so the restored rows showed as a block whenever that RGB no longer
 * matched: always for output that arrived before the first setDefaultColors
 * (native started on libvterm's stock black), and after any theme change.
 * Seen on RouterOS: its login scrolls blank lines off the 80x24 connect-time
 * grid, and the tab-mount grow restored them as an 80-column black block.
 */
@RunWith(AndroidJUnit4::class)
class BackfillDefaultColorTest {

    private val schemeFg = Color(0xFFC0C0C0.toInt())
    private val schemeBg = Color(0xFF1A1B2E.toInt())

    private fun settledLines(e: TerminalEmulatorImpl): List<TerminalLine> {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
        return e.snapshot.value.lines
    }

    /** 23x51 grid in the scheme colours; 20 blank lines go to scrollback, then a prompt. */
    private fun emulatorWithBlankScrollback(): TerminalEmulatorImpl {
        val e = TerminalEmulatorFactory.create(
            initialRows = 23,
            initialCols = 51,
            defaultForeground = schemeFg,
            defaultBackground = schemeBg,
        ) as TerminalEmulatorImpl
        e.writeInput(("\r\n".repeat(42) + "> ").toByteArray())
        settledLines(e)
        assertEquals("precondition: 20 lines in scrollback", 20, e.snapshot.value.scrollback.size)
        return e
    }

    private fun assertNoPaintedRows(lines: List<TerminalLine>, background: Color) {
        val painted = lines.indices.filter { row -> lines[row].cells.any { it.bgColor != background } }
        assertEquals("rows with a non-default background", emptyList<Int>(), painted)
    }

    @Test
    fun `backfilled rows keep the default background from construction`() {
        val e = emulatorWithBlankScrollback()

        e.resize(40, 51)

        assertNoPaintedRows(settledLines(e), schemeBg)
    }

    @Test
    fun `backfilled rows follow a theme applied after they were pushed`() {
        val e = emulatorWithBlankScrollback()
        val themedBg = Color(0xFF002B36.toInt())
        e.setDefaultColors(schemeFg.toArgb(), themedBg.toArgb())

        e.resize(40, 51)

        assertNoPaintedRows(settledLines(e), themedBg)
    }
}
