package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReflowResizeIntegrityTest {

    private fun settle(e: TerminalEmulatorImpl) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
    }

    /** Reconstruct logical lines (joining softWrapped chains) across scrollback + live screen. */
    private fun logicalLines(e: TerminalEmulatorImpl, stripTrailingEmpty: Boolean = true): List<String> {
        settle(e)
        val snap = e.snapshot.value
        val raw = snap.scrollback + snap.lines
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (line in raw) {
            if (line.softWrapped) {
                current.append(line.text)
            } else {
                current.append(line.text.trimEnd())
                out.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            out.add(current.toString().trimEnd())
        }
        return if (stripTrailingEmpty) {
            out.dropLastWhile { it.isEmpty() }
        } else {
            out
        }
    }

    private fun contentDisplayWidth(line: TerminalLine): Int {
        val lastIdx = line.cells.indexOfLast { it.char != ' ' || it.combiningChars.isNotEmpty() }
        if (lastIdx < 0) return 0
        return line.cells.subList(0, lastIdx + 1).sumOf { it.width.coerceAtLeast(1) }
    }

    @Test
    fun `shrinking screen when a soft-wrapped paragraph overflows the top preserves every character`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 20)
            as TerminalEmulatorImpl
        val paragraph = (0 until 5).joinToString("") { block ->
            ('a'..'t').map { ch -> "$ch" }.joinToString("") {
                if (it == "a") "$block" else it
            }
        }
        assertEquals(100, paragraph.length)

        // 100 chars wrap into 5 rows on 5x20; x and y scroll 3 rows (60 chars) into scrollback
        // leaving 2 continuation rows (40 chars) at the top of the live screen.
        emulator.writeInput("$paragraph\r\nx\r\ny\r\n".toByteArray())
        delay(150)
        assertEquals(listOf(paragraph, "x", "y"), logicalLines(emulator))

        // Shrink to 5x12: the 40-char screen chain expands to 4 rows, overflowing the top.
        emulator.resize(5, 12)
        delay(150)
        settle(emulator)
        assertEquals(
            "shrinking 5x20 -> 5x12 dropped part of the top-straddling chain",
            listOf(paragraph, "x", "y"),
            logicalLines(emulator),
        )
        for ((idx, sbLine) in emulator.snapshot.value.scrollback.withIndex()) {
            assertTrue(
                "scrollback[$idx] display width ${contentDisplayWidth(sbLine)} exceeds 12 cols: '${sbLine.text}'",
                contentDisplayWidth(sbLine) <= 12,
            )
        }

        // Shrink further to 5x7, then widen to 5x80.
        emulator.resize(5, 7)
        delay(150)
        settle(emulator)
        assertEquals(
            "shrinking 5x12 -> 5x7 corrupted or dropped content",
            listOf(paragraph, "x", "y"),
            logicalLines(emulator),
        )
        for ((idx, sbLine) in emulator.snapshot.value.scrollback.withIndex()) {
            assertTrue(
                "scrollback[$idx] display width ${contentDisplayWidth(sbLine)} exceeds 7 cols: '${sbLine.text}'",
                contentDisplayWidth(sbLine) <= 7,
            )
        }

        emulator.resize(5, 80)
        delay(150)
        assertEquals(
            "widening 5x7 -> 5x80 failed to reconstruct original paragraph",
            listOf(paragraph, "x", "y"),
            logicalLines(emulator),
        )
        emulator.close()
    }

    @Test
    fun `scrollback lines rewrap on narrower width and rejoin on wider width without merging hard newlines`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 40)
            as TerminalEmulatorImpl
        val para70 = (1..7).joinToString("") { "012345678$it" }
        val line35a = "alpha-bravo-charlie-delta-echo-1234"
        val line35b = "foxtrot-golf-hotel-india-juliet-567"
        assertEquals(70, para70.length)
        assertEquals(35, line35a.length)
        assertEquals(35, line35b.length)

        emulator.writeInput("$para70\r\n$line35a\r\n\r\n$line35b\r\n".toByteArray())
        for (i in 1..8) emulator.writeInput("tail-$i\r\n".toByteArray())
        delay(150)
        val expected = listOf(para70, line35a, "", line35b) + (1..8).map { "tail-$it" }
        assertEquals(expected, logicalLines(emulator))

        // Shrink to 6x20: 35-char single lines in scrollback must wrap to <=20 cols instead of clipping.
        emulator.resize(6, 20)
        delay(150)
        settle(emulator)
        for ((idx, sbLine) in emulator.snapshot.value.scrollback.withIndex()) {
            assertTrue(
                "scrollback[$idx] display width ${contentDisplayWidth(sbLine)} exceeds 20 cols",
                contentDisplayWidth(sbLine) <= 20,
            )
        }
        assertEquals(expected, logicalLines(emulator))

        // Widen to 6x80: para70 must rejoin into a single 70-char scrollback line, and hard newlines stay separate.
        emulator.resize(6, 80)
        delay(150)
        settle(emulator)
        assertEquals(expected, logicalLines(emulator))
        val sbTexts = emulator.snapshot.value.scrollback.map { it.text.trimEnd() }
        assertTrue(
            "expected para70 rejoined as a single scrollback line at 80 cols, got: $sbTexts",
            sbTexts.contains(para70),
        )
        assertTrue(
            "expected line35a rejoined as a single scrollback line at 80 cols, got: $sbTexts",
            sbTexts.contains(line35a),
        )
        emulator.close()
    }

    @Test
    fun `wide CJK glyphs reflow across narrow and wide resizes without tearing or phantom spaces`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 6)
            as TerminalEmulatorImpl
        // On 6 cols:
        // "ABCD中文EF" -> row 0: "ABCD中" (4+2=6), row 1: "文EF" (2+2=4)
        // "ABC中文D"   -> row 2: "ABC中" (3+2=5, 1 pad col), row 3: "文D" (2+1=3)
        emulator.writeInput("ABCD中文EF\r\nABC中文D\r\n".toByteArray(Charsets.UTF_8))
        for (i in 1..6) emulator.writeInput("z$i\r\n".toByteArray())
        delay(150)
        val expected = listOf("ABCD中文EF", "ABC中文D") + (1..6).map { "z$it" }

        // Shrink to 5 cols, then 4 cols, then widen to 12 cols.
        emulator.resize(6, 5)
        delay(150)
        settle(emulator)
        for ((idx, line) in (emulator.snapshot.value.scrollback + emulator.snapshot.value.lines).withIndex()) {
            assertTrue(
                "line[$idx] display width ${contentDisplayWidth(line)} exceeds 5 cols: '${line.text}'",
                contentDisplayWidth(line) <= 5,
            )
        }

        emulator.resize(6, 4)
        delay(150)
        emulator.resize(6, 12)
        delay(150)
        settle(emulator)
        assertEquals(expected, logicalLines(emulator))
        val allTrimmed = (emulator.snapshot.value.scrollback + emulator.snapshot.value.lines).map { it.text.trimEnd() }
        assertTrue("expected rejoined 'ABCD中文EF' at 12 cols, got $allTrimmed", allTrimmed.contains("ABCD中文EF"))
        assertTrue("expected rejoined 'ABC中文D' at 12 cols without phantom space, got $allTrimmed", allTrimmed.contains("ABC中文D"))
        emulator.close()
    }

    @Test
    fun `OSC 8 hyperlinks in scrollback survive narrower and wider reflow`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 30)
            as TerminalEmulatorImpl
        val url = "https://haven.example/docs"
        emulator.writeInput("prefix-12 \u001B]8;;$url\u0007click-here-link\u001B]8;;\u0007 end\r\n".toByteArray())
        for (i in 1..6) emulator.writeInput("row-$i\r\n".toByteArray())
        delay(150)

        // Shrink to 16 cols (splitting "click-here-link" across two scrollback rows), then widen to 40 cols.
        emulator.resize(5, 16)
        delay(150)
        settle(emulator)
        for ((idx, sbLine) in emulator.snapshot.value.scrollback.withIndex()) {
            assertTrue(
                "scrollback[$idx] display width ${contentDisplayWidth(sbLine)} exceeds 16 cols",
                contentDisplayWidth(sbLine) <= 16,
            )
        }
        val splitLinkRows = emulator.snapshot.value.scrollback.filter { line ->
            line.cells.indices.any { col -> line.getHyperlinkUrlAt(col) == url }
        }
        assertEquals("expected OSC 8 link split across 2 scrollback rows at 16 cols", 2, splitLinkRows.size)
        assertTrue(
            "OSC 8 URL should remain discoverable after narrowing scrollback to 16 cols",
            emulator.getUrls().any { it.url == url && it.source == TerminalUrlSource.Osc8 },
        )

        emulator.resize(5, 40)
        delay(150)
        settle(emulator)
        val rejoined = emulator.snapshot.value.scrollback.first { it.text.contains("click-here-link") }
        val linkStart = rejoined.text.indexOf("click-here-link")
        assertEquals(url, rejoined.getHyperlinkUrlAt(linkStart))
        assertEquals(url, rejoined.getHyperlinkUrlAt(linkStart + "click-here-link".length - 1))
        assertEquals(null, rejoined.getHyperlinkUrlAt(linkStart - 1))
        emulator.close()
    }

    @Test
    fun `live screen wide CJK glyphs reflow without tearing across narrow and wide resizes`() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 6)
            as TerminalEmulatorImpl
        // Stay entirely on the live screen (0 scrollback) so screen.c's wide-glyph reflow is exercised directly.
        emulator.writeInput("ABC中D\r\nABCD中".toByteArray(Charsets.UTF_8))
        delay(150)

        // Resize to 4 cols: '中' at col 3 on 6 cols would land on col 3 (new_cols-1) on 4 cols if torn.
        emulator.resize(6, 4)
        delay(150)
        settle(emulator)
        for ((idx, line) in emulator.snapshot.value.lines.withIndex()) {
            assertTrue(
                "live row[$idx] display width ${contentDisplayWidth(line)} exceeds 4 cols: '${line.text}'",
                contentDisplayWidth(line) <= 4,
            )
        }

        // Widen to 10 cols: both lines must rejoin cleanly with width-2 '中' and no phantom spaces.
        emulator.resize(6, 10)
        delay(150)
        settle(emulator)
        assertEquals(listOf("ABC中D", "ABCD中"), logicalLines(emulator))
        val row0 = emulator.snapshot.value.lines[0]
        val zhongCell = row0.cells.first { it.char == '中' }
        assertEquals(2, zhongCell.width)
        emulator.close()
    }
}
