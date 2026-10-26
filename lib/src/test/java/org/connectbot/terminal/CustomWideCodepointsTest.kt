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
        rows: Int = 24,
        cols: Int = 80,
    ): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = rows,
        initialCols = cols,
        ambiwidthMode = mode,
        onKeyboardInput = {},
    ) as TerminalEmulatorImpl

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

    @Test
    fun testAutoWideWithEmptyProfiledCodepointsKeepsTuiGlyphsNarrowAndPuaWide() {
        val terminal = createEmulator(AmbiwidthMode.AUTO_WIDE)
        // Monospace font where 0 ambiguous glyphs in AMBIGUOUS_INTERVALS exceed 1.35x 'M'
        terminal.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE, IntArray(0))

        // ─ (U+2500), │ (U+2502), ▀ (U+2580), ▄ (U+2584), ○ (U+25CB), ● (U+25CF), · (U+00B7), • (U+2022)
        // are all in profiled_ambiguous -> 1 cell each (8 cells).
        // \uE000 is unprofiled PUA -> falls back to 2 cells in AUTO_WIDE.
        terminal.write("─│▀▄○●·•\uE000")
        val s = terminal.snapshot.value
        assertEquals(10, s.cursorCol)
    }

    @Test
    fun testSetAmbiwidthModeWithoutCodepointsAndSecondSessionDoNotClobberProfile() {
        val terminal1 = createEmulator(AmbiwidthMode.AUTO_WIDE, rows = 10, cols = 63)
        // UI profiles font and applies custom wide table (only U+2605 ★ is wide; TUI box/spinners are narrow)
        terminal1.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE, intArrayOf(0x2605))

        // ViewModel / SshTerminalEmulatorOwner re-polices mode without passing codepoints
        terminal1.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE)

        // A second SSH session connects in AUTO_WIDE mode and initializes its native terminal
        val terminal2 = createEmulator(AmbiwidthMode.AUTO_WIDE, rows = 10, cols = 63)
        terminal2.write("init")

        // Write tmux + agy differential animation sequences to terminal1:
        // 1. 60-col box-drawing separator on row 0 (must not wrap on a 63-col pane)
        // 2. agy spinner update using CUF (\u001b[C) on row 1 (must not eat first letter after space)
        // 3. 63-col tmux status bar with middle dot '·' on row 9 (must not wrap or scroll row 0 off)
        val sep = "─".repeat(60)
        val statusBar = "[mega-3] 0:bash* \"● Thinking · msi-z790\"      18:23 26-Oct-26"
        assertEquals(61, statusBar.length)
        terminal1.write(
            "\u001b[?1049h\u001b[2J\u001b[1;1H$sep" +
                "\u001b[2;1H○ Thinking" +
                "\u001b[2;1H●\u001b[CThought\u001b[K" +
                "\u001b[10;1H$statusBar",
        )

        val s1 = terminal1.snapshot.value
        assertEquals(sep, s1.lines[0].text.trimEnd())
        assertEquals("● Thought", s1.lines[1].text.trimEnd())
        assertEquals(statusBar, s1.lines[9].text.trimEnd())
    }

    @Test
    fun testAutoWideColdStartBeforeExplicitProfileKeepsTuiGlyphsNarrowAndPuaWide() {
        FontWidthProfiler.clearCache()
        // Simulate SshTerminalEmulatorOwner creating an emulator in AUTO_WIDE mode on cold start
        // and receiving initial PTY output before Terminal.kt's LaunchedEffect profiles the font.
        val terminal = createEmulator(AmbiwidthMode.AUTO_WIDE, rows = 10, cols = 63)
        terminal.write("─│▀▄○●·•\uE000")

        val s = terminal.snapshot.value
        assertEquals(10, s.cursorCol)
    }

    @Test
    fun testTmuxScrollRegionAndCufDifferentialRedrawDoNotSpliceRows() {
        FontWidthProfiler.clearCache()
        val terminal = createEmulator(AmbiwidthMode.AUTO_WIDE, rows = 6, cols = 63)
        terminal.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE, IntArray(0))
        // Simulate re-policing without codepoints
        terminal.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE)

        val sep = "─".repeat(60)
        // Set tmux scroll region to rows 1..5 (row 6 is status bar), populate row 3 with old text
        // and row 4 with blanks, then draw a 60-col '─' separator on row 5.
        // Previously, '─' was width 2, wrapping row 5 onto a 2nd line and scrolling row 3's text
        // into the row that tmux subsequently updated via CUF (\u001b[C) space-skips, producing:
        // "  comments) dpffers.fNowelet.meoreadtthe full verifiedbfiles"
        terminal.write(
            "\u001b[?1049h\u001b[2J\u001b[1;5r" +
                "\u001b[3;1H               p       f   e   .  o    t                 b     " +
                "\u001b[4;1H                                                               " +
                "\u001b[5;1H$sep" +
                "\u001b[4;1H  comments)\u001b[Cdiffers.\u001b[CNow\u001b[Clet\u001b[Cme\u001b[Cread\u001b[Cthe\u001b[Cfull\u001b[Cverified\u001b[Cfiles",
        )

        val s = terminal.snapshot.value
        assertEquals(
            "  comments) differs. Now let me read the full verified files",
            s.lines[3].text.trimEnd(),
        )
        assertEquals(sep, s.lines[4].text.trimEnd())
    }
}
