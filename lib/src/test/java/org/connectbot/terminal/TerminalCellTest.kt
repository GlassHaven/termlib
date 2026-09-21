package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCellTest {

    @Test
    fun `maps pixels to 0-based cells`() {
        assertEquals(3 to 2, terminalCell(35f, 24f, 10f, 12f, 0f, 80, 24))
    }

    @Test
    fun `adds keyboardCoveredPx to the row`() {
        assertEquals(0 to 2, terminalCell(5f, 10f, 10f, 10f, 10f, 80, 24))
    }

    @Test
    fun `clamps to the visible grid`() {
        assertEquals(79 to 23, terminalCell(10_000f, 10_000f, 10f, 10f, 0f, 80, 24))
        assertEquals(0 to 0, terminalCell(-20f, -20f, 10f, 10f, 0f, 80, 24))
    }

    @Test
    fun `degenerate metrics stay at origin`() {
        assertEquals(0 to 0, terminalCell(10f, 10f, 0f, 10f, 0f, 80, 24))
        assertEquals(0 to 0, terminalCell(10f, 10f, 10f, 10f, 0f, 0, 24))
    }
}
