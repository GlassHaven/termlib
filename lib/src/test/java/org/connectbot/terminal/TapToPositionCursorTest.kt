/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapToPositionCursorTest {

    private fun line(
        text: String,
        cols: Int,
        segments: List<SemanticSegment> = emptyList(),
    ): TerminalLine {
        val cells = (0 until cols).map { col ->
            TerminalLine.Cell(
                char = text.getOrElse(col) { ' ' },
                fgColor = Color.White,
                bgColor = Color.Black,
            )
        }
        return TerminalLine(row = 0, cells = cells, semanticSegments = segments)
    }

    private fun snapshot(
        screenLines: List<TerminalLine>,
        cursorRow: Int,
        cursorCol: Int,
        scrollback: List<TerminalLine> = emptyList(),
        cols: Int = 80,
    ): TerminalSnapshot = TerminalSnapshot(
        lines = screenLines,
        scrollback = scrollback,
        cursorRow = cursorRow,
        cursorCol = cursorCol,
        cursorVisible = true,
        cursorBlink = true,
        cursorShape = CursorShape.BLOCK,
        terminalTitle = "",
        rows = screenLines.size,
        cols = cols,
        timestamp = 0L,
        sequenceNumber = 0L,
    )

    private fun commandInputAt(col: Int, promptId: Int = 1): SemanticSegment = SemanticSegment(
        startCol = col,
        endCol = col,
        semanticType = SemanticType.COMMAND_INPUT,
        promptId = promptId,
    )

    private fun commandFinishedAt(col: Int, promptId: Int = 1): SemanticSegment = SemanticSegment(
        startCol = col,
        endCol = col,
        semanticType = SemanticType.COMMAND_FINISHED,
        promptId = promptId,
    )

    @Test
    fun `dispatches right-arrows when tap is past cursor on prompt line`() {
        // Prompt "$ " at cols 0..1, COMMAND_INPUT marker at col 2 (where
        // user's input begins). Cursor sits at col 2 (start of input).
        // Tap at col 7 → 5 right-arrows.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ ", cols = 80, segments = listOf(commandInputAt(2))),
            ),
            cursorRow = 0,
            cursorCol = 2,
        )

        val handled = dispatchTapToPositionCursor(
            emulator = emulator,
            snapshot = snap,
            tapRow = 0,
            tapCol = 7,
        )

        assertTrue(handled)
        verify(exactly = 5) { emulator.dispatchKey(0, VTermKey.RIGHT) }
        verify(exactly = 0) { emulator.dispatchKey(0, VTermKey.LEFT) }
    }

    @Test
    fun `dispatches left-arrows when tap is before cursor inside input region`() {
        // Cursor at col 10 (user typed 8 chars); tap at col 5 → 5 lefts.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ echo hi", cols = 80, segments = listOf(commandInputAt(2))),
            ),
            cursorRow = 0,
            cursorCol = 10,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 5)

        assertTrue(handled)
        verify(exactly = 5) { emulator.dispatchKey(0, VTermKey.LEFT) }
    }

    @Test
    fun `tap on prompt prefix snaps to start of input`() {
        // Tap at col 0 (on the "$" prompt char) — clamp to inputSegment.startCol=2.
        // Cursor at col 10 → 8 lefts (10 - 2).
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ echo hi", cols = 80, segments = listOf(commandInputAt(2))),
            ),
            cursorRow = 0,
            cursorCol = 10,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 0)

        assertTrue(handled)
        verify(exactly = 8) { emulator.dispatchKey(0, VTermKey.LEFT) }
    }

    @Test
    fun `zero-delta tap still claims the gesture but dispatches nothing`() {
        // User taps exactly where cursor already is — suppress the default
        // tap callback (so a quick double-tap-reposition doesn't bubble up
        // as a fullscreen toggle), but no key dispatches.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ ", cols = 80, segments = listOf(commandInputAt(2))),
            ),
            cursorRow = 0,
            cursorCol = 2,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 2)

        assertTrue(handled)
        verify(exactly = 0) { emulator.dispatchKey(any(), any()) }
    }

    @Test
    fun `does not fire when tap is on a different row from cursor`() {
        // Cursor on row 0, tap on row 1 — tap-to-position only repositions
        // along a single row; cross-row repositioning isn't safe because
        // most readlines stop at the line boundary.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ echo hi", cols = 80, segments = listOf(commandInputAt(2))),
                line("more text", cols = 80),
            ),
            cursorRow = 0,
            cursorCol = 10,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 1, tapCol = 5)

        assertFalse(handled)
        verify(exactly = 0) { emulator.dispatchKey(any(), any()) }
    }

    @Test
    fun `does not fire when row has no COMMAND_INPUT segment`() {
        // Random row in command output (not a prompt) — no OSC 133 marker.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("output line", cols = 80),
            ),
            cursorRow = 0,
            cursorCol = 5,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 10)

        assertFalse(handled)
        verify(exactly = 0) { emulator.dispatchKey(any(), any()) }
    }

    @Test
    fun `does not fire when COMMAND_FINISHED already exists for this prompt`() {
        // 133;D fired (command done). The COMMAND_INPUT marker is still
        // on this row, but a matching COMMAND_FINISHED elsewhere closes
        // the prompt out — taps shouldn't repurpose this row's marker.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ echo hi", cols = 80, segments = listOf(commandInputAt(2, promptId = 7))),
                line("hi", cols = 80, segments = listOf(commandFinishedAt(2, promptId = 7))),
            ),
            cursorRow = 0,
            cursorCol = 10,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 5)

        assertFalse(handled)
        verify(exactly = 0) { emulator.dispatchKey(any(), any()) }
    }

    @Test
    fun `COMMAND_FINISHED for a different prompt does not block this prompt`() {
        // promptId=7 is the current prompt (no FINISHED yet); promptId=6's
        // FINISHED is in scrollback above. Should fire normally.
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("\$ echo hi", cols = 80, segments = listOf(commandInputAt(2, promptId = 7))),
            ),
            scrollback = listOf(
                line("\$ ls", cols = 80, segments = listOf(commandInputAt(2, promptId = 6))),
                line("a b c", cols = 80, segments = listOf(commandFinishedAt(0, promptId = 6))),
            ),
            cursorRow = 0,
            cursorCol = 10,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 5)

        assertTrue(handled)
        verify(exactly = 5) { emulator.dispatchKey(0, VTermKey.LEFT) }
    }

    @Test
    fun `boxed REPL prompt positions cursor on same row without OSC 133`() {
        val rule = "─".repeat(54)
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line(rule, cols = 54),
                line("> hello world", cols = 54),
                line(rule, cols = 54),
            ),
            cursorRow = 1,
            cursorCol = 13,
            cols = 54,
        )

        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 1, tapCol = 8)

        assertTrue(handled)
        verify(exactly = 5) { emulator.dispatchKey(0, VTermKey.LEFT) }
    }

    @Test
    fun `boxed REPL prompt clamps tap in trailing whitespace to end of typed text`() {
        val rule = "─".repeat(54)
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line(rule, cols = 54),
                line("> hello world", cols = 54),
                line(rule, cols = 54),
            ),
            cursorRow = 1,
            cursorCol = 8,
            cols = 54,
        )

        // Text "> hello world" ends at col 13; tapping at col 40 clamps to 13 (delta = +5).
        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 1, tapCol = 40)

        assertTrue(handled)
        verify(exactly = 5) { emulator.dispatchKey(0, VTermKey.RIGHT) }
    }

    @Test
    fun `boxed REPL prompt positions cursor across wrapped lines`() {
        val rule = "─".repeat(54)
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line(rule, cols = 54),
                line("> Can you see why the mouse click cursor position", cols = 54),
                line("  doesnt work in agy in tmux", cols = 54),
                line(rule, cols = 54),
            ),
            cursorRow = 2,
            cursorCol = 12,
            cols = 54,
        )

        // Tap on row 1, col 10 from cursor on row 2, col 12:
        // 10 LEFTs to (row 2, col 2), 1 UP to (row 1, col 2), 8 RIGHTs to (row 1, col 10).
        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 1, tapCol = 10)

        assertTrue(handled)
        verify(exactly = 10) { emulator.dispatchKey(0, VTermKey.LEFT) }
        verify(exactly = 1) { emulator.dispatchKey(0, VTermKey.UP) }
        verify(exactly = 8) { emulator.dispatchKey(0, VTermKey.RIGHT) }
    }

    @Test
    fun `boxed REPL prompt ignores taps outside the rule box`() {
        val rule = "─".repeat(54)
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("conversation history line", cols = 54),
                line(rule, cols = 54),
                line("> hello world", cols = 54),
                line(rule, cols = 54),
                line("esc to cancel                  Gemini 4 Argon", cols = 54),
            ),
            cursorRow = 2,
            cursorCol = 13,
            cols = 54,
        )

        assertFalse(dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 5))
        assertFalse(dispatchTapToPositionCursor(emulator, snap, tapRow = 4, tapCol = 5))
        verify(exactly = 0) { emulator.dispatchKey(any(), any()) }
    }

    @Test
    fun `fallback shell prompt inside tmux positions cursor without OSC 133`() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("ian@msi-z790:~/Code/Haven\$ git status --short", cols = 80),
            ),
            cursorRow = 0,
            cursorCol = 45,
        )

        // Prompt prefix "ian@msi-z790:~/Code/Haven$ " is 27 chars (cols 0..26, startCol = 27).
        // Tap at col 31 ("status") -> 14 LEFTs.
        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 31)

        assertTrue(handled)
        verify(exactly = 14) { emulator.dispatchKey(0, VTermKey.LEFT) }
    }

    @Test
    fun `does not cross vertical tmux split divider`() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val snap = snapshot(
            screenLines = listOf(
                line("ian@host:~\$ echo hi      │ other pane text", cols = 80),
            ),
            cursorRow = 0,
            cursorCol = 19,
        )

        // Tap at col 35 is on the right-hand tmux pane past '│' at col 25.
        val handled = dispatchTapToPositionCursor(emulator, snap, tapRow = 0, tapCol = 35)

        assertFalse(handled)
        verify(exactly = 0) { emulator.dispatchKey(any(), any()) }
    }
}
