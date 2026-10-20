/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CustomWideCodepointsTest {

    private fun createEmulator(
        ambiwidth: Boolean = false,
    ): TerminalEmulatorImpl {
        return TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            ambiwidth = ambiwidth,
            onKeyboardInput = {},
        ) as TerminalEmulatorImpl
    }

    private fun TerminalEmulatorImpl.write(text: String) {
        writeInput(text.toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()
        processPendingUpdates()
    }

    @Test
    fun testDefaultNarrowAmbiwidth() {
        val terminal = createEmulator(ambiwidth = false)
        // Default: ambiguous characters like ▼ (U+25BC) and α (U+03B1) are 1 cell
        terminal.write("▼α가")
        val s = terminal.snapshot.value
        // ▼ (1) + α (1) + 가 (2) = 4
        assertEquals(4, s.cursorCol)
    }

    @Test
    fun testGlobalAmbiwidth() {
        val terminal = createEmulator(ambiwidth = true)
        // Global ambiwidth=true: both ▼ and α are forced to 2 cells
        terminal.write("▼α가")
        val s = terminal.snapshot.value
        // ▼ (2) + α (2) + 가 (2) = 6
        assertEquals(6, s.cursorCol)
    }

    @Test
    fun testDynamicCustomWideCodepoints() {
        val terminal = createEmulator(ambiwidth = false)
        // Configure dynamic font profile: only geometric symbol ▼ (0x25BC) is 2 cells,
        // while Greek α (0x03B1) remains 1 cell
        terminal.setCustomWideCodepoints(intArrayOf(0x25BC))

        terminal.write("▼α가")
        val s = terminal.snapshot.value
        // ▼ (2) + α (1) + 가 (2) = 5
        assertEquals(5, s.cursorCol)
    }

    @Test
    fun testCustomWideOverridesGlobalAmbiwidth() {
        val terminal = createEmulator(ambiwidth = true)
        // Even if global ambiwidth was true, setting custom wide codepoints
        // restricts 2-cell expansion ONLY to the profiled codepoints
        terminal.setCustomWideCodepoints(intArrayOf(0x25BC, 0x25C6)) // ▼ and ◆

        terminal.write("▼◆α가")
        val s = terminal.snapshot.value
        // ▼ (2) + ◆ (2) + α (1) + 가 (2) = 7
        assertEquals(7, s.cursorCol)
    }
}
