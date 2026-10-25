package org.connectbot.terminal

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Deterministic host tests for DECSTBM partial-scroll-region cache integrity
 * and wide/supplementary glyph preservation across scrollback pop.
 */
@RunWith(AndroidJUnit4::class)
class ScrollbackAndRegionIntegrityTest {

    private fun settle(e: TerminalEmulatorImpl) {
        e.processPendingUpdates()
        shadowOf(Looper.getMainLooper()).idle()
        e.processPendingUpdates()
    }

    @Test
    fun `top-anchored DECSTBM scroll region does not shift or corrupt pinned footer rows`() {
        val e = TerminalEmulatorFactory.create(initialRows = 8, initialCols = 24) as TerminalEmulatorImpl
        settle(e)

        // Populate body rows 0..5 and two pinned status/footer rows 6..7 (row 7 carries an OSC 8 link).
        val init = buildString {
            for (r in 0..5) {
                append("body-$r\r\n")
            }
            append("STATUS-BAR-ONE\r\n")
            append("\u001b]8;;https://example.com\u0007STATUS-BAR-TWO\u001b]8;;\u0007")
        }
        e.writeInput(init.toByteArray(Charsets.UTF_8))
        settle(e)

        assertEquals("STATUS-BAR-ONE", e.snapshot.value.lines[6].text.trimEnd())
        assertEquals("STATUS-BAR-TWO", e.snapshot.value.lines[7].text.trimEnd())
        assertEquals(1, e.snapshot.value.lines[7].getSegmentsOfType(SemanticType.HYPERLINK).size)

        // Set DECSTBM to rows 1..6 (0-based 0..5), park at row 6 col 1, and scroll the top region.
        // Footer rows 6..7 are outside the scroll region and receive no damage.
        e.writeInput("\u001b[1;6r\u001b[6;1H\r\nbody-6".toByteArray(Charsets.UTF_8))
        settle(e)

        val snap = e.snapshot.value
        assertEquals("body-0", snap.scrollback.single().text.trimEnd())
        for (r in 0..5) {
            assertEquals("row $r", "body-${r + 1}", snap.lines[r].text.trimEnd())
        }
        assertEquals(
            "pinned row 6 below DECSTBM was overwritten by pushScrollbackLine",
            "STATUS-BAR-ONE",
            snap.lines[6].text.trimEnd(),
        )
        assertEquals(
            "pinned row 7 below DECSTBM was corrupted",
            "STATUS-BAR-TWO",
            snap.lines[7].text.trimEnd(),
        )
        assertEquals(
            "pinned row 7 hyperlink segment was wiped by pushScrollbackLine",
            1,
            snap.lines[7].getSegmentsOfType(SemanticType.HYPERLINK).size,
        )
    }

    @Test
    fun `popping scrollback line with wide and supplementary glyphs preserves spaces and text`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 26) as TerminalEmulatorImpl
        settle(e)

        val rocket = String(Character.toChars(0x1F680))
        val payload = listOf(
            "\u2705 Passed all checks",
            "漢字 テスト complete",
            "$rocket launch sequence",
            "row-3",
            "row-4",
            "row-5",
            "row-6",
        ).joinToString("\r\n")
        e.writeInput(payload.toByteArray(Charsets.UTF_8))
        settle(e)

        assertEquals(3, e.snapshot.value.scrollback.size)

        // Grow height from 4 to 7 rows: pops the 3 scrollback lines back onto live rows 0..2.
        e.resize(7, 26)
        settle(e)

        val lines = e.snapshot.value.lines.map { it.text.trimEnd() }
        assertEquals("\u2705 Passed all checks", lines[0])
        assertEquals("漢字 テスト complete", lines[1])
        assertEquals("$rocket launch sequence", lines[2])
        assertEquals("row-3", lines[3])
        assertEquals("row-4", lines[4])
        assertEquals("row-5", lines[5])
        assertEquals("row-6", lines[6])
    }

    @Test
    fun `compound grow-rows shrink-cols split preserves wide glyph scrollback lines without truncation`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 20) as TerminalEmulatorImpl
        settle(e)

        // 8 CJK glyphs = 8 cells, 16 display columns.
        e.writeInput("一二三四五六七八\r\nr1\r\nr2\r\nr3\r\nr4".toByteArray(Charsets.UTF_8))
        settle(e)
        assertEquals("一二三四五六七八", e.snapshot.value.scrollback.single().text.trimEnd())

        // Grow rows (4 -> 8) while shrinking cols (20 -> 10): 16 display columns must split into
        // "一二三四五" (10 cols) + "六七八" (6 cols) rather than skipping odd slots or dropping tail.
        e.resize(8, 10)
        settle(e)

        val visible = e.snapshot.value.lines.map { it.text.trimEnd() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("一二三四五", "六七八", "r1", "r2", "r3", "r4"),
            visible,
        )
    }
}
