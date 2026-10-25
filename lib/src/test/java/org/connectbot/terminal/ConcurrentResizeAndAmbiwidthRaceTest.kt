package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * #749 audit areas #3 & #4:
 * - resize() racing an in-flight updateLine() across the atomic getRowRuns() seam
 * - concurrent processPendingUpdates() calls aliasing the shared rowRunPool
 * - setAmbiwidthMode() lock-order inversion (damageLock -> terminalNative vs
 *   terminalNative -> mLock -> damageLock), post-close safety, and native
 *   g_custom_wide_chars concurrent mutation during vterm_unicode_width lookups.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentResizeAndAmbiwidthRaceTest {

    private fun settle(e: TerminalEmulatorImpl) {
        e.processPendingUpdates()
    }

    @Test
    fun `resize shrinking rows during in-flight row rebuild does not throw or overwrite post-resize state`() {
        val e = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 20) as TerminalEmulatorImpl
        settle(e)

        e.writeInput("\u001b[1;1Hrow-zero\u001b[9;1Hrow-eight".toByteArray())
        settle(e)

        e.onCellRunFetchedForTesting = {
            e.onCellRunFetchedForTesting = null
            // Shrink terminal height from 10 to 4 while updateLine(8) is in flight.
            e.resize(4, 12)
        }

        // Damage only row 8 so updateLine(8) is in flight when resize(4, 12) lands.
        e.writeInput("\u001b[9;1HROW-EIGHT".toByteArray())
        settle(e)

        val immediate = e.snapshot.value
        assertEquals(4, immediate.rows)
        assertEquals(12, immediate.cols)
        assertEquals(4, immediate.lines.size)
        for ((idx, line) in immediate.lines.withIndex()) {
            assertEquals("row $idx must match post-resize width", 12, line.cells.size)
        }

        // Drain the post-resize invalidateDisplay() damage and verify convergence.
        settle(e)
        val converged = e.snapshot.value
        assertEquals(4, converged.lines.size)
        for ((idx, line) in converged.lines.withIndex()) {
            assertEquals("converged row $idx width", 12, line.cells.size)
        }
    }

    @Test
    fun `resize changing columns during in-flight row rebuild does not splice old-width row into snapshot`() {
        val e = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 20) as TerminalEmulatorImpl
        settle(e)

        e.writeInput("\u001b[1;1H0123456789ABCDEFGHIJ".toByteArray())
        settle(e)

        e.onCellRunFetchedForTesting = {
            e.onCellRunFetchedForTesting = null
            // Narrow columns 20 -> 10 right after updateLine(0) fetched 20 pre-resize cells.
            e.resize(6, 10)
        }

        e.writeInput("\u001b[1;1H!".toByteArray())
        settle(e)

        val midSnap = e.snapshot.value
        assertEquals(6, midSnap.rows)
        assertEquals(10, midSnap.cols)
        assertEquals(
            "stale 20-col pre-resize row must not overwrite post-resize 10-col currentLines[0]",
            10,
            midSnap.lines[0].cells.size,
        )

        // Drain queued resize invalidation and verify reflowed 10-column content.
        settle(e)
        val finalSnap = e.snapshot.value
        assertEquals("!123456789", finalSnap.lines[0].text)
        assertTrue("reflowed row 0 should be soft-wrapped", finalSnap.lines[0].softWrapped)
        assertEquals(10, finalSnap.lines[0].cells.size)
        assertEquals("ABCDEFGHIJ", finalSnap.lines[1].text)
        assertEquals(10, finalSnap.lines[1].cells.size)
    }

    @Test
    fun `concurrent processPendingUpdates calls do not alias rowRunPool across threads`() {
        val e = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 20) as TerminalEmulatorImpl
        settle(e)

        var competingThread: Thread? = null
        val competingFailure = AtomicReference<Throwable?>(null)

        e.onCellRunFetchedForTesting = {
            e.onCellRunFetchedForTesting = null
            // Damage row 1 with two distinct style runs, then trigger a concurrent
            // processPendingUpdates() on another thread before outer updateLine(0)
            // consumes rowRunPool[0..1].
            e.writeInput("\u001b[2;1H\u001b[38;2;0;0;255mBLU_OVER\u001b[38;2;255;255;0mYLW_OVER\u001b[0m".toByteArray())
            val t = thread(name = "competing-rebuild", isDaemon = true) {
                try {
                    e.processPendingUpdates()
                } catch (err: Throwable) {
                    competingFailure.set(err)
                }
            }
            competingThread = t
            val deadlineNs = System.nanoTime() + 2_000_000_000L
            while (t.isAlive && t.state != Thread.State.BLOCKED && System.nanoTime() < deadlineNs) {
                Thread.sleep(1)
            }
        }

        e.writeInput("\u001b[1;1H\u001b[38;2;255;0;0mRED_TEXT\u001b[38;2;0;255;0mGRN_TEXT\u001b[0m".toByteArray())
        settle(e)

        val t = checkNotNull(competingThread)
        t.join(5_000)
        assertFalse("competing rebuild thread should have finished", t.isAlive)
        assertEquals(null, competingFailure.get())

        val snap = e.snapshot.value
        assertEquals(
            "row 0 must retain its own runs rather than aliasing row 1's rowRunPool entries",
            "RED_TEXTGRN_TEXT",
            snap.lines[0].text.trimEnd(),
        )
        assertEquals(Color(255, 0, 0), snap.lines[0].cells[0].fgColor)
        assertEquals(Color(0, 255, 0), snap.lines[0].cells[8].fgColor)
        assertEquals("BLU_OVERYLW_OVER", snap.lines[1].text.trimEnd())
        assertEquals(Color(0, 0, 255), snap.lines[1].cells[0].fgColor)
        assertEquals(Color(255, 255, 0), snap.lines[1].cells[8].fgColor)
    }

    @Test
    fun `setAmbiwidthMode is safe after close and concurrent with writeInput without deadlock`() {
        val closedEmulator = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 20) as TerminalEmulatorImpl
        closedEmulator.writeInput("▼α가".toByteArray(Charsets.UTF_8))
        settle(closedEmulator)
        closedEmulator.close()

        // Must be silent no-ops after close(), matching writeInput/resize/setDefaultColors.
        closedEmulator.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE, intArrayOf(0x25BC, 0x25C6))
        closedEmulator.setAmbiwidth(true)
        closedEmulator.setCustomWideCodepoints(intArrayOf(0x25BC))

        // Stress concurrent writeInput (terminalNative -> mLock -> damageLock + vterm_unicode_width)
        // against setAmbiwidthMode (previously damageLock -> terminalNative + unsynchronized free()
        // of g_custom_wide_chars).
        val e = TerminalEmulatorFactory.create(initialRows = 12, initialCols = 40) as TerminalEmulatorImpl
        settle(e)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val payload = "▼◆α\uE000가 test row\r\n".toByteArray(Charsets.UTF_8)
        val tableA = intArrayOf(0x25B2, 0x25BC, 0x25C6, 0x2605)
        val tableB = intArrayOf(0x03B1, 0x25BC, 0x25CB, 0x25CF, 0x2713, 0x2717)

        val writer = thread(name = "pty-writer", isDaemon = true) {
            start.await()
            repeat(300) {
                try {
                    e.writeInput(payload)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    return@thread
                }
            }
        }
        val profiler = thread(name = "font-profiler", isDaemon = true) {
            start.await()
            repeat(300) { i ->
                try {
                    when (i % 3) {
                        0 -> e.setAmbiwidthMode(AmbiwidthMode.AUTO_NARROW, tableA)
                        1 -> e.setAmbiwidthMode(AmbiwidthMode.AUTO_WIDE, tableB)
                        else -> e.setAmbiwidthMode(AmbiwidthMode.NARROW, null)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    return@thread
                }
            }
        }

        start.countDown()
        writer.join(5_000)
        profiler.join(5_000)
        assertFalse("pty-writer deadlocked against setAmbiwidthMode", writer.isAlive)
        assertFalse("font-profiler deadlocked against writeInput", profiler.isAlive)
        assertEquals(null, failure.get())

        // Reset global native ambiwidth state to default narrow so later tests start clean.
        e.setAmbiwidthMode(AmbiwidthMode.NARROW, null)
        e.close()
    }
}
