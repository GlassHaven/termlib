package org.connectbot.terminal

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class ImmediateMouseDragTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class RecordingCallback(
        private val claimMouseDrag: Boolean = true,
    ) : TerminalGestureCallback {
        val drags = Collections.synchronizedList(mutableListOf<Triple<Int, Int, MouseDragPhase>>())
        val taps = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val scrolls = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Boolean>>())
        val events = Collections.synchronizedList(mutableListOf<String>())

        override fun onTap(col: Int, row: Int): Boolean {
            taps.add(col to row)
            return true
        }

        override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
            scrolls.add(Triple(col, row, scrollUp))
            events.add("scroll:${if (scrollUp) "up" else "down"}")
            return true
        }

        override fun onMouseDrag(col: Int, row: Int, phase: MouseDragPhase): Boolean {
            drags.add(Triple(col, row, phase))
            events.add("drag:$phase")
            return phase != MouseDragPhase.Start || claimMouseDrag
        }

        fun phases() = drags.map { it.third }
    }

    private fun selectionLines() = ShadowLog.getLogsForTag("HavenGesture").map { it.msg }.filter { "selection-started" in it }

    private fun render(
        immediateMouseDrag: Boolean,
        callback: TerminalGestureCallback? = null,
        immediateMouseDragOverride: (() -> Boolean)? = null,
        populateScrollback: Boolean = false,
        onFontSizeChanged: ((TextUnit) -> Unit)? = null,
        onScrollControllerAvailable: ((ScrollController) -> Unit)? = null,
    ): TerminalEmulator {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        runBlocking {
            if (populateScrollback) {
                repeat(80) { emulator.writeInput("line $it\r\n".toByteArray()) }
            } else {
                emulator.writeInput("ready\r\n".toByteArray())
            }
        }
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                keyboardEnabled = true,
                modifier = Modifier.size(400.dp, 600.dp),
                gestureCallback = callback,
                mouseModeActive = true,
                immediateMouseDrag = immediateMouseDragOverride?.invoke() ?: immediateMouseDrag,
                onFontSizeChanged = onFontSizeChanged,
                onScrollControllerAvailable = onScrollControllerAvailable,
            )
        }
        composeTestRule.waitForIdle()
        return emulator
    }

    @Test
    fun `OFF long press still starts a local selection`() {
        ShadowLog.clear()
        render(immediateMouseDrag = false)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        assertTrue(
            "OFF mode must keep Haven local selection: ${ShadowLog.getLogsForTag("HavenGesture").map { it.msg }}",
            selectionLines().isNotEmpty(),
        )
    }

    @Test
    fun `OFF drag is scroll not mouse-drag`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = false, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(0f, 180f)) }
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertTrue("OFF swipe must not emit MouseDrag: ${cb.drags}", cb.drags.isEmpty())
        assertTrue("OFF swipe should still be able to scroll: ${cb.scrolls}", cb.scrolls.isNotEmpty())
    }

    @Test
    fun `ON drag emits Start then Moves then one End`() {
        ShadowLog.clear()
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.waitForIdle()
        assertTrue("holding without movement must not start a drag", cb.drags.isEmpty())

        composeTestRule.onRoot().performTouchInput { moveBy(Offset(180f, 0f)) }
        composeTestRule.waitForIdle()
        assertEquals(MouseDragPhase.Start, cb.phases().first())
        val start = cb.drags.first()
        assertTrue("expected cell-quantized Moves after Start: ${cb.drags}", cb.drags.size >= 2)
        assertTrue(cb.drags.drop(1).all { it.third == MouseDragPhase.Move })
        val moves = cb.drags.filter { it.third == MouseDragPhase.Move }
        assertEquals("Moves must stay ordered", moves, moves.sortedBy { it.first })

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(MouseDragPhase.Start, cb.phases().first())
        assertEquals(MouseDragPhase.End, cb.phases().last())
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
        assertEquals("Start cell is the original down cell", start.first, cb.drags.first().first)
        assertTrue("ON drag must not start local selection", selectionLines().isEmpty())
        assertTrue("ON drag must not be classified as Scroll", cb.scrolls.isEmpty())
        assertTrue("Start/End must not also emit onTap", cb.taps.isEmpty())
    }

    @Test
    fun `ON realistic tap is onTap without MouseDrag`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(200)
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertTrue("tap must stay onTap: taps=${cb.taps} drags=${cb.drags}", cb.taps.isNotEmpty())
        assertTrue("tap must not emit Start/End", cb.drags.isEmpty())
    }

    @Test
    fun `ON movement inside second-pointer probe still starts drag`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(180f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(MouseDragPhase.Start, cb.phases().first())
        assertTrue(cb.phases().contains(MouseDragPhase.Move))
        assertEquals(MouseDragPhase.End, cb.phases().last())
    }

    @Test
    fun `ON stationary edge press does not emit drag or wheel`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(Offset(center.x, 4f)) }
        composeTestRule.mainClock.advanceTimeBy(240)
        composeTestRule.waitForIdle()
        assertTrue("stationary press must not start a drag", cb.drags.isEmpty())
        assertTrue("stationary edge press must not emit wheel events", cb.scrolls.isEmpty())
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.taps.size)
    }

    @Test
    fun `ON bottom edge keeps drag held without interleaved wheel`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput {
            moveBy(Offset(80f, 0f))
            moveTo(Offset(center.x + 80f, bottomCenter.y + 40f))
        }
        composeTestRule.waitForIdle()

        assertEquals(MouseDragPhase.Start, cb.phases().first())
        assertTrue("active mouse drag must not interleave wheel", cb.scrolls.isEmpty())
        assertEquals("drag:Move", cb.events.last())
        assertEquals(0, cb.phases().count { it == MouseDragPhase.End })

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
    }

    @Test
    fun `ON top edge keeps drag held without selection-resetting wheel`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput {
            moveBy(Offset(80f, 0f))
            moveTo(Offset(center.x + 80f, -40f))
        }
        composeTestRule.waitForIdle()

        assertEquals(MouseDragPhase.Start, cb.phases().first())
        assertTrue("active mouse drag must not interleave wheel", cb.scrolls.isEmpty())
        assertEquals("drag:Move", cb.events.last())
        assertEquals(0, cb.phases().count { it == MouseDragPhase.End })

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
    }

    @Test
    fun `ON stationary top edge repeats held Move without wheel`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput {
            moveBy(Offset(80f, 0f))
            moveTo(Offset(center.x + 80f, -40f))
        }
        composeTestRule.waitForIdle()
        cb.events.clear()
        cb.scrolls.clear()

        composeTestRule.waitUntil(timeoutMillis = 1_000) { cb.events.isNotEmpty() }
        assertTrue(cb.scrolls.isEmpty())
        assertTrue(cb.events.all { it == "drag:Move" })
        assertEquals(0, cb.phases().count { it == MouseDragPhase.End })

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
    }

    @Test
    fun `ON top and bottom edge handling are symmetric held motion`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        fun edgeEvents(y: Float): Pair<List<MouseDragPhase>, Int> {
            cb.drags.clear()
            cb.scrolls.clear()
            cb.events.clear()
            composeTestRule.onRoot().performTouchInput { down(center) }
            composeTestRule.mainClock.advanceTimeBy(80)
            composeTestRule.onRoot().performTouchInput {
                moveBy(Offset(80f, 0f))
                moveTo(Offset(center.x + 80f, y))
            }
            composeTestRule.waitForIdle()
            composeTestRule.onRoot().performTouchInput { up() }
            composeTestRule.waitForIdle()
            return cb.phases() to cb.scrolls.size
        }

        val top = edgeEvents(-40f)
        val bottom = edgeEvents(640f)
        assertEquals(top.first, bottom.first)
        assertEquals(0, top.second)
        assertEquals(0, bottom.second)
    }

    @Test
    fun `ON outside edge coordinates stay clamped to valid terminal rows`() {
        val cb = RecordingCallback()
        val emulator = render(immediateMouseDrag = true, callback = cb)
        fun rowsAt(top: Boolean): List<Int> {
            cb.drags.clear()
            cb.scrolls.clear()
            cb.events.clear()
            composeTestRule.onRoot().performTouchInput { down(center) }
            composeTestRule.mainClock.advanceTimeBy(80)
            composeTestRule.onRoot().performTouchInput {
                moveBy(Offset(80f, 0f))
                val outsideY = if (top) -200f else bottomCenter.y + 200f
                moveTo(Offset(center.x + 80f, outsideY))
                up()
            }
            composeTestRule.waitForIdle()
            return cb.drags.map { it.second } + cb.scrolls.map { it.second }
        }

        val topRows = rowsAt(top = true)
        val bottomRows = rowsAt(top = false)
        val rows = emulator.dimensions.rows
        assertTrue(topRows.isNotEmpty() && topRows.all { it in 0 until rows })
        assertTrue(bottomRows.isNotEmpty() && bottomRows.all { it in 0 until rows })
        assertTrue(topRows.contains(0))
        assertTrue(bottomRows.contains(rows - 1))
    }

    @Test
    fun `ON release outside viewport emits exactly one End`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput {
            moveBy(Offset(80f, 0f))
            moveTo(Offset(center.x + 80f, -100f))
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.Start })
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
    }

    @Test
    fun `ON cancel in edge zone emits exactly one End`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput {
            moveBy(Offset(80f, 0f))
            moveTo(Offset(center.x + 80f, -100f))
            cancel()
        }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
    }

    @Test
    fun `ON reentering viewport continues same drag`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput {
            moveBy(Offset(80f, 0f))
            moveTo(Offset(center.x + 80f, -100f))
            moveTo(Offset(center.x + 80f, center.y))
        }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.Start })
        assertEquals(0, cb.phases().count { it == MouseDragPhase.End })
        assertTrue(cb.drags.count { it.third == MouseDragPhase.Move } >= 3)

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
    }

    @Test
    fun `ON cancel after Start emits exactly one End`() {
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(180f, 0f)) }
        composeTestRule.waitForIdle()
        assertEquals(MouseDragPhase.Start, cb.phases().first())
        composeTestRule.onRoot().performTouchInput { cancel() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
        assertEquals(MouseDragPhase.End, cb.phases().last())
    }

    @Test
    fun `ON late second pointer ends remote drag before local two-finger pan`() {
        ShadowLog.clear()
        val cb = RecordingCallback()
        var scrollController: ScrollController? = null
        render(
            immediateMouseDrag = true,
            callback = cb,
            populateScrollback = true,
            onScrollControllerAvailable = { scrollController = it },
        )
        assertTrue((scrollController?.maxScrollback ?: 0) > 0)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(180f, 0f)) }
        composeTestRule.waitForIdle()
        assertEquals(MouseDragPhase.Start, cb.phases().first())

        var first = Offset.Unspecified
        var second = Offset.Unspecified
        composeTestRule.onRoot().performTouchInput {
            first = center + Offset(180f, 0f)
            second = center + Offset(60f, 0f)
            down(1, second)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })

        composeTestRule.onRoot().performTouchInput {
            updatePointerTo(0, first + Offset(0f, 80f))
            updatePointerTo(1, second + Offset(0f, 80f))
            move()
            updatePointerTo(0, first + Offset(0f, 160f))
            updatePointerTo(1, second + Offset(0f, 160f))
            move()
            up(1)
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
        assertFalse("second pointer must not start local selection", selectionLines().isNotEmpty())
        assertTrue("two-finger pan must move local scrollback", scrollController!!.scrollbackPosition > 0)
        assertTrue("two-finger pan must not emit remote wheel", cb.scrolls.isEmpty())
    }

    @Test
    fun `ON early second pointer pans local scrollback without MouseDrag`() {
        val cb = RecordingCallback()
        var scrollController: ScrollController? = null
        render(
            immediateMouseDrag = true,
            callback = cb,
            populateScrollback = true,
            onScrollControllerAvailable = { scrollController = it },
        )
        assertTrue((scrollController?.maxScrollback ?: 0) > 0)
        composeTestRule.onRoot().performTouchInput {
            val first = center + Offset(-60f, 0f)
            val second = center + Offset(60f, 0f)
            down(0, first)
            down(1, second)
            updatePointerTo(0, first + Offset(0f, 80f))
            updatePointerTo(1, second + Offset(0f, 80f))
            move()
            updatePointerTo(0, first + Offset(0f, 160f))
            updatePointerTo(1, second + Offset(0f, 160f))
            move()
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue(cb.drags.isEmpty())
        assertTrue("two-finger pan must move local scrollback", scrollController!!.scrollbackPosition > 0)
        assertTrue("two-finger pan must not emit remote wheel", cb.scrolls.isEmpty())
        assertTrue(cb.taps.isEmpty())
    }

    @Test
    fun `ON two-finger upward pan moves toward local live bottom`() {
        val cb = RecordingCallback()
        var scrollController: ScrollController? = null
        render(
            immediateMouseDrag = true,
            callback = cb,
            populateScrollback = true,
            onScrollControllerAvailable = { scrollController = it },
        )
        scrollController!!.scrollBy(20)
        composeTestRule.waitForIdle()
        val startingPosition = scrollController!!.scrollbackPosition
        assertTrue(startingPosition > 0)
        composeTestRule.onRoot().performTouchInput {
            val first = center + Offset(-60f, 0f)
            val second = center + Offset(60f, 0f)
            down(0, first)
            down(1, second)
            updatePointerTo(0, first + Offset(0f, -80f))
            updatePointerTo(1, second + Offset(0f, -80f))
            move()
            updatePointerTo(0, first + Offset(0f, -160f))
            updatePointerTo(1, second + Offset(0f, -160f))
            move()
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue(scrollController!!.scrollbackPosition < startingPosition)
        assertTrue("two-finger pan must not emit remote wheel", cb.scrolls.isEmpty())
        assertTrue(cb.drags.isEmpty())
    }

    @Test
    fun `ON pinch zooms without wheel or held mouse button`() {
        val cb = RecordingCallback()
        val fontSizes = mutableListOf<TextUnit>()
        render(
            immediateMouseDrag = true,
            callback = cb,
            onFontSizeChanged = { fontSizes.add(it) },
        )
        composeTestRule.onRoot().performTouchInput {
            val first = center + Offset(-30f, 0f)
            val second = center + Offset(30f, 0f)
            down(0, first)
            down(1, second)
            updatePointerTo(0, first + Offset(-80f, 0f))
            updatePointerTo(1, second + Offset(80f, 0f))
            move()
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue("pinch must persist a changed font size", fontSizes.isNotEmpty())
        assertTrue("pinch must not emit wheel", cb.scrolls.isEmpty())
        assertTrue("pinch must not start or hold remote button", cb.drags.isEmpty())
    }

    @Test
    fun `OFF two-finger pan keeps Haven local scrollback`() {
        val cb = RecordingCallback()
        var scrollController: ScrollController? = null
        render(
            immediateMouseDrag = false,
            callback = cb,
            populateScrollback = true,
            onScrollControllerAvailable = { scrollController = it },
        )
        assertTrue((scrollController?.maxScrollback ?: 0) > 0)
        composeTestRule.onRoot().performTouchInput {
            val first = center + Offset(-60f, 0f)
            val second = center + Offset(60f, 0f)
            down(0, first)
            down(1, second)
            updatePointerTo(0, first + Offset(0f, 80f))
            updatePointerTo(1, second + Offset(0f, 80f))
            move()
            updatePointerTo(0, first + Offset(0f, 160f))
            updatePointerTo(1, second + Offset(0f, 160f))
            move()
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()
        assertTrue("OFF two-finger pan must move local scrollback", scrollController!!.scrollbackPosition > 0)
        assertTrue("OFF two-finger pan must not emit remote wheel", cb.scrolls.isEmpty())
        assertTrue(cb.drags.isEmpty())
    }

    @Test
    fun `ON rejected Start falls back to scroll without End`() {
        val cb = RecordingCallback(claimMouseDrag = false)
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(0f, 180f)) }
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(MouseDragPhase.Start), cb.phases())
        assertTrue("declined drag must retain normal scroll fallback", cb.scrolls.isNotEmpty())
    }

    @Test
    fun `ON mode downgrade ends drag before more motion`() {
        val enabled = mutableStateOf(true)
        val cb = RecordingCallback()
        render(
            immediateMouseDrag = true,
            callback = cb,
            immediateMouseDragOverride = { enabled.value },
        )
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(80)
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(180f, 0f)) }
        composeTestRule.waitForIdle()
        assertEquals(MouseDragPhase.Start, cb.phases().first())
        val movesBeforeDowngrade = cb.phases().count { it == MouseDragPhase.Move }

        composeTestRule.runOnIdle { enabled.value = false }
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(80f, 0f)) }
        composeTestRule.onRoot().performTouchInput { moveBy(Offset(0f, 180f)) }
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertEquals(1, cb.phases().count { it == MouseDragPhase.End })
        assertEquals(movesBeforeDowngrade, cb.phases().count { it == MouseDragPhase.Move })
        assertTrue("invalidated drag must not turn into wheel scrolling", cb.scrolls.isEmpty())
    }

    @Test
    fun `ON long hold without movement stays a tap`() {
        ShadowLog.clear()
        val cb = RecordingCallback()
        render(immediateMouseDrag = true, callback = cb)
        composeTestRule.onRoot().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        assertTrue(selectionLines().isEmpty())
        assertTrue(cb.drags.isEmpty())
        assertTrue(cb.scrolls.isEmpty())
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, cb.taps.size)
    }
}
