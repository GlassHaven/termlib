package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A run's length is a count of screen columns, not UTF-16 units. A
 * supplementary-plane glyph is two units but one column, so reporting the unit
 * count made updateLine read one cell past the run out of the reused native
 * buffer.
 */
@RunWith(AndroidJUnit4::class)
class SurrogateRunCellCountTest {

    @Test
    fun `supplementary glyphs do not over-read a cell`() {
        val e = TerminalEmulatorFactory.create(
            initialRows = 2,
            initialCols = 16,
            onKeyboardInput = { _ -> },
        ) as TerminalEmulatorImpl

        for (cp in listOf(0xF00BA, 0xF0189, 0xF0410, 0xF02CB, 0xF01BC)) {
            val icon = String(Character.toChars(cp))
            // The first run primes the shared buffer; the icon run must not
            // pull its 5th char ('E') into an extra cell.
            e.writeInput(
                (
                    "\u001b[2J\u001b[H\u001b[31mABCDEFGH\u001b[0m" +
                        "\u001b[32mX${icon}Y\u001b[0m"
                    ).toByteArray(),
            )
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            e.processPendingUpdates()
            assertEquals(
                "U+%05X".format(cp),
                "ABCDEFGHX${icon}Y",
                e.snapshot.value.lines[0].text.trimEnd(),
            )
        }
    }
}
