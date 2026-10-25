package org.connectbot.terminal

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DifferentialRepaintCorruptionTest {

    private fun createEmulator(
        rows: Int = 23,
        cols: Int = 51,
        mode: AmbiwidthMode = AmbiwidthMode.NARROW,
    ): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = rows,
        initialCols = cols,
        ambiwidthMode = mode,
        onKeyboardInput = {},
    ) as TerminalEmulatorImpl

    private fun settle(e: TerminalEmulatorImpl) {
        shadowOf(Looper.getMainLooper()).idle()
        e.processPendingUpdates()
    }

    /**
     * Build a differential-repaint sequence that updates [row1Based] starting at
     * 1-based column [startCol1Based], emitting `ESC[C` whenever a cell matches [prev]
     * (the exact cell-differ pattern emitted by TUI status/history redraws).
     */
    private fun buildDifferentialRowRewrite(
        row1Based: Int,
        prev: String,
        next: String,
        startCol1Based: Int = 1,
        sgrPrefix: String = "\u001b[38;5;246m",
        sgrSuffix: String = "\u001b(B\u001b[m",
    ): String = buildString {
        append("\u001b[$row1Based;${startCol1Based}H")
        append(sgrPrefix)
        var pendingSkips = 0
        val totalCols = maxOf(prev.length, next.length)
        for (col in (startCol1Based - 1) until totalCols) {
            val prevCh = if (col < prev.length) prev[col] else ' '
            val nextCh = if (col < next.length) next[col] else ' '
            if (nextCh == prevCh) {
                pendingSkips++
            } else {
                if (pendingSkips == 1) {
                    append("\u001b[C")
                } else if (pendingSkips > 1) {
                    append("\u001b[${pendingSkips}C")
                }
                pendingSkips = 0
                append(nextCh)
            }
        }
        append(sgrSuffix)
    }

    @Test
    fun narrowDifferentialRepaintWithCufGapsAndSpinnerPreservesRows() {
        val targetRow2 = "● building (pages already green); tag waits on list"
        val prevRow2 = "● working  (tasks running  9999);2old6itemstonlits "
        val targetRow3 = "● Thinking for 1m 12s, calling haven 2 times"
        val prevRow3 = "● Thinking for 1m 11s,ecalling h ven 1 timeo,s"
        val targetRow4 = "  for the user's message"
        val prevRow4 = "● for the user's message"

        val frame1 = buildString {
            append("\u001b[2J\u001b[H")
            append("\u001b[38;5;246m\u001b[48;5;237m❯ \u001b[38;5;231magain, once\u001b(B\u001b[m")
            append("\u001b[2;1H$prevRow2")
            append("\u001b[3;1H$prevRow3")
            append("\u001b[4;1H$prevRow4")
            append("\u001b[17;1H\u001b[38;5;220m\u001b[1m✢\u001b(B\u001b[m")
            append("\u001b[17;13H\u001b[38;5;246m19  0s · ↓ 1.2k\u001b(B\u001b[m")
        }

        val frame2 = buildString {
            // Spinner tick + status line with CUF gaps + home input rewrite + per-row CUF differential repaint
            append("\u001b[20;3H\u001b(B\u001b[m\u001b[17;1H\u001b[38;5;220m\u001b[1m✽")
            append("\u001b[17;13H\u001b[38;5;246m20\u001b[2C0s · ↓ 1.3k\u001b(B\u001b[m")
            append("\u001b[H\u001b[38;5;246m\u001b[48;5;237m❯ \u001b[38;5;231magain, once more\u001b(B\u001b[m")
            append(buildDifferentialRowRewrite(2, prevRow2, targetRow2, startCol1Based = 3))
            append(buildDifferentialRowRewrite(3, prevRow3, targetRow3, startCol1Based = 3))
            append("\u001b[4;1H ")
            append(buildDifferentialRowRewrite(4, "  for the u er's m ssag ", targetRow4, startCol1Based = 3))
            append("\u001b[4;25H\u001b[K")
        }

        // 1) Byte-by-byte writes with processPendingUpdates() after every single byte.
        val byteStepped = createEmulator()
        for (b in (frame1 + frame2).toByteArray(Charsets.UTF_8)) {
            byteStepped.writeInput(byteArrayOf(b))
            byteStepped.processPendingUpdates()
        }
        settle(byteStepped)
        val steppedLines = byteStepped.snapshot.value.lines
        assertEquals(targetRow2, steppedLines[1].text.trimEnd())
        assertEquals(targetRow3, steppedLines[2].text.trimEnd())
        assertEquals(targetRow4, steppedLines[3].text.trimEnd())
        byteStepped.close()

        // 2) Concurrent writer + continuous reader rebuilds across prime-sized chunks.
        val concurrent = createEmulator()
        val error = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val writer = Thread {
            try {
                val spinners = charArrayOf('✢', '✳', '✶', '✻', '✽')
                val streamBytes1 = frame1.toByteArray(Charsets.UTF_8)
                val streamBytes2 = frame2.toByteArray(Charsets.UTF_8)
                for (iter in 0 until 80) {
                    val src = if (iter % 2 == 0) streamBytes1 else streamBytes2
                    var offset = 0
                    val step = 7 + (iter % 11)
                    while (offset < src.size) {
                        val n = minOf(step, src.size - offset)
                        concurrent.writeInput(src, offset, n)
                        offset += n
                    }
                    val tick = "\u001b[17;1H\u001b[38;5;220m${spinners[iter % spinners.size]}\u001b(B\u001b[m"
                        .toByteArray(Charsets.UTF_8)
                    concurrent.writeInput(tick)
                }
                // End on frame2 so final state is deterministic.
                concurrent.writeInput(streamBytes1)
                concurrent.writeInput(streamBytes2)
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }
        writer.start()
        while (!done.await(1, TimeUnit.MILLISECONDS)) {
            concurrent.processPendingUpdates()
        }
        writer.join()
        settle(concurrent)
        error.get()?.let { throw it }

        val concurrentLines = concurrent.snapshot.value.lines
        assertEquals(targetRow2, concurrentLines[1].text.trimEnd())
        assertEquals(targetRow3, concurrentLines[2].text.trimEnd())
        assertEquals(targetRow4, concurrentLines[3].text.trimEnd())
        concurrent.close()
    }

    @Test
    fun ambiwidthInstanceIsolationUnderDifferentialRepaintWithCufGaps() {
        // Session A is an active narrow SSH session (matching remote Unix / tmux wcwidth=1).
        val sessionA = createEmulator(rows = 23, cols = 51, mode = AmbiwidthMode.NARROW)
        sessionA.writeInput("\u001b[2J\u001b[H".toByteArray(Charsets.UTF_8))
        settle(sessionA)

        // Session B (e.g. a second tab or font-profiled view) configures custom wide ambiguous
        // glyphs including U+25CF ('●'). Process-global state in unicode.c must NOT bleed into sessionA.
        val sessionB = createEmulator(rows = 23, cols = 51, mode = AmbiwidthMode.AUTO_NARROW)
        sessionB.setAmbiwidthMode(AmbiwidthMode.AUTO_NARROW, intArrayOf(0x25CF, 0x273D))

        val frame1Row3 = "● Thought for 1 minute checking agent"
        val targetRow3 = "● Thinking for 1m 12s, calling haven 2 times"
        val frame1Row4 = "● Looking  since  e see"
        val targetRow4 = "  for the user's message"

        sessionA.writeInput(
            (
                "\u001b[3;1H$frame1Row3" +
                    "\u001b[4;1H$frame1Row4"
                ).toByteArray(Charsets.UTF_8),
        )
        settle(sessionA)

        // Frame 2 differential repaint with ESC[3;3H and ESC[C over unchanged cells:
        val diffRow3 = buildDifferentialRowRewrite(
            row1Based = 3,
            prev = frame1Row3,
            next = targetRow3,
            startCol1Based = 3,
        )
        val diffRow4 = buildDifferentialRowRewrite(
            row1Based = 4,
            prev = frame1Row4,
            next = targetRow4,
            startCol1Based = 1,
        )
        sessionA.writeInput((diffRow3 + diffRow4).toByteArray(Charsets.UTF_8))
        settle(sessionA)

        val lines = sessionA.snapshot.value.lines
        assertEquals(
            "sessionA row 3 corrupted by cross-instance ambiwidth bleed",
            targetRow3,
            lines[2].text.trimEnd(),
        )
        assertEquals(
            "sessionA row 4 corrupted by cross-instance ambiwidth bleed",
            targetRow4,
            lines[3].text.trimEnd(),
        )

        settle(sessionB)
        sessionB.close()
        sessionA.close()
    }

    @Test
    fun splitUtf8BeforeEscParenBDoesNotShiftColumnsDuringDifferentialRepaint() {
        val e = createEmulator(rows = 23, cols = 51, mode = AmbiwidthMode.NARROW)

        val prevRow3 = "  ● Thinking for 1m 11s, reading haven 1 time"
        val targetRow3 = "  ● Thinking for 1m 12s, calling haven 2 times"

        // Frame 1 arrives in two chunks BEFORE any ESC(B has switched GL[0] away from UTF-8:
        // Chunk 1 starts with ASCII spaces ("  ") and ends with the first byte (0xE2) of '●' (E2 97 8F).
        // Chunk 2 starts with the continuation bytes (0x97 0x8F) followed by the rest of the row.
        val fullFrame1 = "\u001b[3;1H$prevRow3".toByteArray(Charsets.UTF_8)
        val bulletLeadIndex = fullFrame1.indexOf(0xE2.toByte())
        require(bulletLeadIndex > 0)

        e.writeInput(fullFrame1, 0, bulletLeadIndex + 1)
        settle(e)
        e.writeInput(fullFrame1, bulletLeadIndex + 1, fullFrame1.size - (bulletLeadIndex + 1))
        settle(e)

        // Frame 2 differential repaint skips unchanged cells with ESC[C.
        val diffRow3 = buildDifferentialRowRewrite(
            row1Based = 3,
            prev = prevRow3,
            next = targetRow3,
            startCol1Based = 5,
        )
        e.writeInput(diffRow3.toByteArray(Charsets.UTF_8))
        settle(e)

        assertEquals(targetRow3, e.snapshot.value.lines[2].text.trimEnd())
        e.close()
    }

    @Test
    fun interruptedUtf8BeforeCsiDoesNotCorruptTargetRowDifferentialRepaint() {
        val e = createEmulator(rows = 23, cols = 51, mode = AmbiwidthMode.NARROW)

        val prevRow3 = "✢ Thinking for 1m 11s, reading haven 1 time"
        val targetRow3 = "● Thinking for 1m 12s, calling haven 2 times"

        e.writeInput("\u001b(B\u001b[m\u001b[3;1H$prevRow3".toByteArray(Charsets.UTF_8))
        settle(e)

        // Row 17 spinner write has an interrupted UTF-8 lead byte (0xE2) immediately followed by
        // ESC[3;1H differential repaint starting with UTF-8 '●' on row 3. The incomplete sequence
        // on row 17 must not leak decoder state across the CSI cursor jump onto row 3 and emit
        // U+FFFD + '●' (2 columns), which shifts row 3's differential repaint into ESC[C gaps.
        val diffRow3 = buildDifferentialRowRewrite(
            row1Based = 3,
            prev = prevRow3,
            next = targetRow3,
            startCol1Based = 1,
        ).toByteArray(Charsets.UTF_8)
        val prefix = "\u001b[17;1H".toByteArray(Charsets.UTF_8) + byteArrayOf(0xE2.toByte())
        e.writeInput(prefix + diffRow3)
        settle(e)

        assertEquals(targetRow3, e.snapshot.value.lines[2].text.trimEnd())

        // Also verify when the incomplete UTF-8 lead byte arrives at the end of chunk 1 and
        // ESC[3;1H differential repaint begins at byte 0 of chunk 2.
        val nextRow3 = "● Thinking for 1m 13s, calling haven 3 times"
        val diffRow3Next = buildDifferentialRowRewrite(
            row1Based = 3,
            prev = targetRow3,
            next = nextRow3,
            startCol1Based = 1,
        ).toByteArray(Charsets.UTF_8)
        e.writeInput(prefix)
        settle(e)
        e.writeInput(diffRow3Next)
        settle(e)

        assertEquals(nextRow3, e.snapshot.value.lines[2].text.trimEnd())
        e.close()
    }

    @Test
    fun byteBufferWriteInputHonorsPositionAndHeapBuffersDuringDifferentialRepaint() {
        val e = createEmulator(rows = 23, cols = 51, mode = AmbiwidthMode.NARROW)

        val prevRow2 = "● working  (pages already green); tag waits on list"
        val targetRow2 = "● building (pages already green); tag waits on list"

        val frame1Bytes = "\u001b[2;1H$prevRow2".toByteArray(Charsets.UTF_8)
        val frame2DiffBytes = buildDifferentialRowRewrite(
            row1Based = 2,
            prev = prevRow2,
            next = targetRow2,
            startCol1Based = 3,
        ).toByteArray(Charsets.UTF_8)

        // Direct ByteBuffer where frame1 occupies [0, frame1Bytes.size) and frame2Diff occupies
        // [frame1Bytes.size, total), passed with buffer.position() == frame1Bytes.size.
        val direct = ByteBuffer.allocateDirect(frame1Bytes.size + frame2DiffBytes.size)
        direct.put(frame1Bytes)
        direct.put(frame2DiffBytes)

        direct.position(0)
        e.writeInput(direct, frame1Bytes.size)
        settle(e)
        assertEquals(prevRow2, e.snapshot.value.lines[1].text.trimEnd())

        direct.position(frame1Bytes.size)
        e.writeInput(direct, frame2DiffBytes.size)
        settle(e)
        assertEquals(
            "Direct ByteBuffer with non-zero position failed to apply differential repaint",
            targetRow2,
            e.snapshot.value.lines[1].text.trimEnd(),
        )

        // Heap ByteBuffer with offset must also apply differential repaint instead of dropping bytes.
        val targetRow3 = "● Thinking for 1m 12s, calling haven 2 times"
        val heapBacking = ByteArray(16 + targetRow3.toByteArray(Charsets.UTF_8).size + 16)
        val row3Cmd = "\u001b[3;1H$targetRow3".toByteArray(Charsets.UTF_8)
        val heapBuf = ByteBuffer.wrap(heapBacking)
        heapBuf.position(8)
        val sliceView = heapBuf.slice()
        System.arraycopy(row3Cmd, 0, heapBacking, 8, row3Cmd.size)
        e.writeInput(sliceView, row3Cmd.size)
        settle(e)
        assertEquals(
            "Heap ByteBuffer failed to apply terminal write",
            targetRow3,
            e.snapshot.value.lines[2].text.trimEnd(),
        )

        e.close()
    }
}
