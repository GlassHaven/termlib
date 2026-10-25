package org.connectbot.terminal

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Deterministic host tests for #749 intra-row corruption in the getRowRuns() /
 * rowRunPool / updateLine() cell-reconstruction pipeline and libvterm wide-cell
 * overwrite handling.
 */
@RunWith(AndroidJUnit4::class)
class RowRunPoolAndCellBoundaryTest {

    private fun settle(e: TerminalEmulatorImpl) {
        shadowOf(Looper.getMainLooper()).idle()
        e.processPendingUpdates()
    }

    @Test
    fun `pooled run array does not bleed stale combining mark onto shorter run`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl
        settle(e)

        // Damage row 0 ('A' + U+0301, 2 UTF-16 units in tls_runArrays[0]) and row 1 ('B',
        // 1 UTF-16 unit in tls_runArrays[0]) in the same batch so row 1 directly reuses
        // row 0's pooled char array slot.
        e.writeInput("\u001b[1;1H\u001b[31mA\u0301\u001b[2;1H\u001b[32mB\u001b[0m".toByteArray(Charsets.UTF_8))
        settle(e)

        assertEquals("A\u0301", e.snapshot.value.lines[0].text.trimEnd())
        val row1 = e.snapshot.value.lines[1]
        assertEquals("B", row1.text.trimEnd())
        assertTrue(
            "expected no combining chars on 'B', got ${row1.cells[0].combiningChars}",
            row1.cells[0].combiningChars.isEmpty(),
        )
    }

    @Test
    fun `overwriting left half of wide glyph clears right half and preserves spacing`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl

        // Prime tls_runArrays[0] with distinctive characters so any over-read is visible.
        e.writeInput("\u001b[31mZZZZZZZZZZ\u001b[0m\r\n".toByteArray(Charsets.UTF_8))
        settle(e)

        // Row 1: wide glyph '漢' at cols 0..1 followed by "is the" at cols 2..7.
        e.writeInput("\u001b[2;1H\u001b[32m漢is the\u001b[0m".toByteArray(Charsets.UTF_8))
        settle(e)

        // tmux differential redraw overwrites col 0 with ASCII 'A' (leaving col 1 untouched
        // because tmux cleared col 1 to space when col 0's wide glyph was broken).
        e.writeInput("\u001b[2;1H\u001b[32mA\u001b[0m".toByteArray(Charsets.UTF_8))
        settle(e)

        // Expected terminal grid on row 1: col 0 = 'A', col 1 = ' ', cols 2..7 = "is the".
        assertEquals("A is the", e.snapshot.value.lines[1].text.trimEnd())
    }

    @Test
    fun `overwriting right half of wide glyph clears left half and does not eat trailing space`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl

        // Write wide glyph '漢' at cols 0..1, then overwrite col 1 (1-based col 2) with "X "
        // in red followed by "word" in default attrs.
        e.writeInput("\u001b[31m漢\u001b[1;2HX \u001b[0mword".toByteArray(Charsets.UTF_8))
        settle(e)

        // Col 0's broken wide glyph becomes space, col 1 = 'X', col 2 = ' ', cols 3..6 = "word".
        assertEquals(" X word", e.snapshot.value.lines[0].text.trimEnd())
    }

    @Test
    fun `ech erasing left half of wide glyph does not eat space or splice pooled chars`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl

        // Prime tls_runArrays[0] with 'Q's.
        e.writeInput("QQQQQQQQQQ\r\n".toByteArray(Charsets.UTF_8))
        settle(e)

        // Row 1: wide glyph at cols 0..1 followed by "word".
        e.writeInput("\u001b[2;1H漢word".toByteArray(Charsets.UTF_8))
        settle(e)

        // Move to row 1 col 0 and ECH 1 (CSI 1 X) to erase 1 cell at col 0.
        e.writeInput("\u001b[2;1H\u001b[1X".toByteArray(Charsets.UTF_8))
        settle(e)

        assertEquals("  word", e.snapshot.value.lines[1].text.trimEnd())
    }

    @Test
    fun `zwj and supplementary combining marks do not shift columns or drop run tail`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl

        // U+200D (ZWJ) is combining in libvterm (0 columns, attached to 'a') but
        // GRAPHEME_EXTEND is false in ICU; trailing space in the red run must not be dropped.
        e.writeInput("\u001b[31ma\u200Db \u001b[0mword".toByteArray(Charsets.UTF_8))
        settle(e)
        assertEquals("a\u200Db word", e.snapshot.value.lines[0].text.trimEnd())

        // Kitty placeholder row/col diacritic U+10A0F (supplementary combining mark > U+FFFF):
        // libvterm attaches it to 'x' (width 1), while Char-based isCombiningCharacter saw a
        // high surrogate and split it into an extra cell, dropping the trailing space.
        val suppMark = String(Character.toChars(0x10A0F))
        e.writeInput("\u001b[2;1H\u001b[32mx${suppMark} \u001b[0mnext".toByteArray(Charsets.UTF_8))
        settle(e)
        assertEquals("x${suppMark} next", e.snapshot.value.lines[1].text.trimEnd())
    }

    @Test
    fun `post unicode 5 combining mark does not splice stale pooled char into row`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl
        settle(e)

        // Row 0 primes tls_runArrays[0] with "ZZZZZZZZ"; row 1 in the same batch has
        // U+1AB0 (Unicode 7.0), which is width 1 in libvterm's table but GRAPHEME_EXTEND
        // in ICU. updateLine must follow libvterm's cell boundaries rather than splicing 'Z'.
        e.writeInput(
            "\u001b[1;1H\u001b[31mZZZZZZZZ\u001b[2;1H\u001b[32ma\u1AB0 \u001b[0mword"
                .toByteArray(Charsets.UTF_8),
        )
        settle(e)
        assertEquals("a\u1AB0 word", e.snapshot.value.lines[1].text.trimEnd())
    }

    @Test
    fun `unicode 14 emoji width difference does not eat trailing space of run`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl

        // U+1FAE0 (melting face, Unicode 14.0) is width 1 in libvterm's fullwidth table
        // and width 2 in modern ICU; updateLine must not drop the space after it.
        val emoji = String(Character.toChars(0x1FAE0))
        e.writeInput("\u001b[31m$emoji \u001b[0mok".toByteArray(Charsets.UTF_8))
        settle(e)
        assertEquals("$emoji ok", e.snapshot.value.lines[0].text.trimEnd())
    }

    @Test
    fun `rowRunPool reuse across multi-run rows never aliases state between rows`() {
        val e = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 32) as TerminalEmulatorImpl

        // Row 0: 8 alternating colour runs with combining marks and wide glyphs.
        e.writeInput(
            (
                "\u001b[31mA\u0301\u001b[32m漢\u001b[33mB\u0302\u001b[34m語" +
                    "\u001b[35mC\u0308\u001b[36m字\u001b[31mD\u0303\u001b[0m end\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        // Row 1: 2 runs with plain ASCII words and leading spaces.
        e.writeInput("\u001b[32m  alpha\u001b[0m beta gamma\r\n".toByteArray(Charsets.UTF_8))
        // Row 2: supplementary Nerd Font icon + text.
        val icon = String(Character.toChars(0xF00BA))
        e.writeInput("\u001b[33m $icon \u001b[34mstatus\u001b[0m ok\r\n".toByteArray(Charsets.UTF_8))
        // Row 3: single default run with leading spaces.
        e.writeInput("    indented line".toByteArray(Charsets.UTF_8))
        settle(e)

        val lines = e.snapshot.value.lines
        assertEquals("A\u0301漢B\u0302語C\u0308字D\u0303 end", lines[0].text.trimEnd())
        assertEquals("  alpha beta gamma", lines[1].text.trimEnd())
        assertEquals(" $icon status ok", lines[2].text.trimEnd())
        assertEquals("    indented line", lines[3].text.trimEnd())
    }
}
