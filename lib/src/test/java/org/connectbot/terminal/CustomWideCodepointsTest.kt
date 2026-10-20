/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CustomWideCodepointsTest {

    private fun createEmulator(
        mode: AmbiwidthMode = AmbiwidthMode.NARROW,
    ): TerminalEmulatorImpl {
        return TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            ambiwidthMode = mode,
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
        val terminal = createEmulator(AmbiwidthMode.NARROW)
        // Default: ambiguous characters like ▼ (U+25BC) and α (U+03B1) are 1 cell
        terminal.write("▼α가")
        val s = terminal.snapshot.value
        // ▼ (1) + α (1) + 가 (2) = 4
        assertEquals(4, s.cursorCol)
    }

    @Test
    fun testGlobalAmbiwidth() {
        val terminal = createEmulator(AmbiwidthMode.WIDE)
        // Global ambiwidth=true: both ▼ and α are forced to 2 cells
        terminal.write("▼α가")
        val s = terminal.snapshot.value
        // ▼ (2) + α (2) + 가 (2) = 6
        assertEquals(6, s.cursorCol)
    }

    @Test
    fun testAmbiwidthModeAutoNarrow() {
        val terminal = createEmulator(AmbiwidthMode.AUTO_NARROW)
        // Auto Narrow: profiled ▼ is 2 cells; profiled α is 1 cell; unprofiled PUA (0xE000) falls back to 1 cell
        terminal.setAmbiwidthMode(AmbiwidthMode.AUTO_NARROW, intArrayOf(0x25BC))

        terminal.write("▼α\uE000가")
        val s = terminal.snapshot.value
        // ▼ (2) + α (1) + PUA (1) + 가 (2) = 6
        assertEquals(6, s.cursorCol)
    }

    @Test
    fun testAmbiwidthModeAutoWide() {
        val terminal = createEmulator(AmbiwidthMode.AUTO_WIDE)
        // Auto Wide: profiled ▼ is 2 cells; profiled α is 1 cell; unprofiled PUA (0xE000) falls back to 2 cells
        terminal.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE, intArrayOf(0x25BC))

        terminal.write("▼α\uE000가")
        val s = terminal.snapshot.value
        // ▼ (2) + α (1) + PUA (2) + 가 (2) = 7
        assertEquals(7, s.cursorCol)
    }

    @Test
    fun testDynamicCustomWideCodepoints() {
        val terminal = createEmulator(AmbiwidthMode.NARROW)
        terminal.setCustomWideCodepoints(intArrayOf(0x25BC))

        terminal.write("▼α가")
        val s = terminal.snapshot.value
        // ▼ (2) + α (1) + 가 (2) = 5
        assertEquals(5, s.cursorCol)
    }

    @Test
    fun testCustomWideOverridesGlobalAmbiwidth() {
        val terminal = createEmulator(AmbiwidthMode.WIDE)
        terminal.setCustomWideCodepoints(intArrayOf(0x25BC, 0x25C6)) // ▼ and ◆

        terminal.write("▼◆α가")
        val s = terminal.snapshot.value
        // ▼ (2) + ◆ (2) + α (1) + 가 (2) = 7
        assertEquals(7, s.cursorCol)
    }
}
