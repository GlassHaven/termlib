package org.connectbot.terminal

import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Regression tests for invisible Unicode format controls (Category Cf), specifically
 * U+FFF9..U+FFFB (Interlinear Annotation Anchor/Separator/Terminator) emitted by TUI
 * soft-wrap formatters right after leading indentation spaces.
 *
 * libvterm classifies U+FFF9..U+FFFB as zero-width combining codepoints and attaches
 * them to the preceding cell (e.g. column 3's space before column 4's 'R'). Because
 * Unicode does not mark U+FFF9..U+FFFB as Default_Ignorable_Code_Point, passing
 * " \uFFF9" to Canvas.drawText at cellLeft[3] advances one cell for ' ' and then draws
 * a missing-glyph .notdef box at cellLeft[4] directly around column 4's character.
 */
@RunWith(AndroidJUnit4::class)
class NonRenderingFormatCharsTest {

    private fun settle(e: TerminalEmulatorImpl) {
        shadowOf(Looper.getMainLooper()).idle()
        e.processPendingUpdates()
    }

    @Test
    fun `meteor soft-wrap interlinear annotation anchor on indent space does not draw over next cell`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 54) as TerminalEmulatorImpl
        settle(e)

        // Exact ANSI/UTF-8 sequence observed on the live 'meteor' tmux session:
        // 4 indent spaces, U+FFF9 (EF BF B9), SGR reset/grey, then "RTSP/GStreamer".
        val stream = "\u001b[39m  \u001b[38;5;254m  \uFFF9\u001b(B\u001b[m\u001b[38;5;247mRTSP/GStreamer"
        e.writeInput(stream.toByteArray(Charsets.UTF_8))
        settle(e)

        val line = e.snapshot.value.lines[0]
        // Column 3 holds the 4th space with U+FFF9 attached by libvterm; it must not invoke drawText.
        assertEquals(' ', line.cells[3].char)
        assertEquals(listOf('\uFFF9'), line.cells[3].combiningChars)
        assertNull(line.cells[3].renderableText())

        // Column 4 holds 'R' and renders cleanly as "R".
        assertEquals("R", line.cells[4].renderableText())
    }

    @Test
    fun `renderableText strips non-shaping format controls while preserving marks zwj and tags`() {
        fun cell(char: Char, vararg extra: Char) = TerminalLine.Cell(
            char = char,
            combiningChars = extra.toList(),
            fgColor = Color.White,
            bgColor = Color.Black,
        )

        // Standalone or attached interlinear annotation controls (U+FFF9..U+FFFB).
        assertNull(cell('\uFFF9').renderableText())
        assertNull(cell(' ', '\uFFF9').renderableText())
        assertNull(cell(' ', '\uFFFA', '\uFFFB').renderableText())
        assertEquals("R", cell('R', '\uFFF9').renderableText())

        // Other non-rendering format controls (ZWSP, BOM, bidi controls, word joiner, musical beam U+1D173, language tag U+E0001).
        assertNull(cell(' ', '\u200B').renderableText())
        assertNull(cell(' ', '\uFEFF').renderableText())
        assertNull(cell(' ', '\u200E', '\u2060').renderableText())
        val beam = Character.toChars(0x1D173)
        assertNull(cell(' ', beam[0], beam[1]).renderableText())
        assertEquals("A", cell('A', beam[0], beam[1]).renderableText())
        val langTag = Character.toChars(0xE0001)
        assertNull(cell(' ', langTag[0], langTag[1]).renderableText())

        // Real combining diacritics on space or letters MUST still render.
        assertEquals(" \u0301", cell(' ', '\u0301').renderableText())
        assertEquals("e\u0301", cell('e', '\u0301').renderableText())

        // ZWJ (U+200D), ZWNJ (U+200C), VS16 (U+FE0F), and regional flag tag sequences (U+E0067..U+E007F) MUST be preserved.
        assertEquals("a\u200Db", cell('a', '\u200D', 'b').renderableText())
        assertEquals("a\u200Cb", cell('a', '\u200C', 'b').renderableText())
        assertEquals("\u2764\uFE0F", cell('\u2764', '\uFE0F').renderableText())
        val flagBlack = Character.toChars(0x1F3F4)
        val tagG = Character.toChars(0xE0067)
        val tagEnd = Character.toChars(0xE007F)
        val flagCell = cell(flagBlack[0], flagBlack[1], tagG[0], tagG[1], tagEnd[0], tagEnd[1])
        assertEquals(
            String(flagBlack) + String(tagG) + String(tagEnd),
            flagCell.renderableText(),
        )
    }

    @Test
    fun `selection copy omits interlinear annotation anchors and image placeholders`() {
        val e = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 30) as TerminalEmulatorImpl
        settle(e)

        e.writeInput("    \uFFF9RTSP/GStreamer\r\n    \uFFF9drifted by 40 ms".toByteArray(Charsets.UTF_8))
        settle(e)

        val sm = SelectionManager()
        sm.startSelection(0, 0, cols = 30, mode = SelectionMode.LINE)
        sm.updateSelection(1, 20)

        val copied = sm.getSelectedText(e.snapshot.value)
        assertEquals("RTSP/GStreamer\n    drifted by 40 ms", copied)
    }
}
