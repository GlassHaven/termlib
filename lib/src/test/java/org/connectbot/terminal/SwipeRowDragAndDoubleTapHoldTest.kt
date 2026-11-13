package org.connectbot.terminal

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import kotlin.math.ceil

/**
 * Verifies row-height continuous drag on single-touch swipes and double-touch
 * activation of SwipeHold when [TerminalGestureCallback.prefersRowHeightScrollQuantum]
 * is enabled.
 */
@RunWith(AndroidJUnit4::class)
class SwipeRowDragAndDoubleTapHoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun singleTouchVerticalDragStepsOnePerCharHeightWithoutHold() {
        val scrollEvents = mutableListOf<Boolean>()
        var holdStarts = 0
        var charHeightPx = 20f
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        val callback = object : TerminalGestureCallback {
            override fun prefersRowHeightScrollQuantum(): Boolean = true

            override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
                scrollEvents += scrollUp
                return true
            }

            override fun onSwipeHoldStart(
                col: Int,
                row: Int,
                direction: SwipeHoldDirection,
            ): Boolean {
                holdStarts++
                return true
            }
        }

        composeTestRule.setContent {
            val density = LocalDensity.current
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = with(density) { 16.sp.toPx() }
                isAntiAlias = true
            }
            val measured = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            charHeightPx = if (measured <= 0f) 20f else measured

            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 600.dp),
                initialFontSize = 16.sp,
                typeface = Typeface.MONOSPACE,
                gestureCallback = callback,
            )
        }

        composeTestRule.waitForIdle()

        // Single touch: drag UP by 3.25 * charHeightPx, hold stationary 350ms, lift.
        composeTestRule.onRoot().performTouchInput {
            val start = Offset(center.x, center.y + 120f)
            down(start)
            advanceEventTime(60)
            moveTo(Offset(start.x, start.y - 1.1f * charHeightPx))
            advanceEventTime(20)
            moveTo(Offset(start.x, start.y - 2.2f * charHeightPx))
            advanceEventTime(20)
            moveTo(Offset(start.x, start.y - 3.25f * charHeightPx))
            // Stationary hold must emit nothing and must not arm SwipeHold.
            advanceEventTime(350)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals("Single-touch swipe must not arm SwipeHold", 0, holdStarts)
            // Dragging UP yields scrollUp = false (3 row quanta).
            assertEquals(listOf(false, false, false), scrollEvents)
        }
    }

    @Test
    fun singleTouchHorizontalDragEmitsHorizontalScrollWithoutHoldOrTap() {
        val horizEvents = mutableListOf<Boolean>()
        var holdStarts = 0
        var tapCount = 0
        var charHeightPx = 20f
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        val callback = object : TerminalGestureCallback {
            override fun prefersRowHeightScrollQuantum(): Boolean = true

            override fun onHorizontalScroll(col: Int, row: Int, scrollLeft: Boolean): Boolean {
                horizEvents += scrollLeft
                return true
            }

            override fun onSwipeHoldStart(
                col: Int,
                row: Int,
                direction: SwipeHoldDirection,
            ): Boolean {
                holdStarts++
                return true
            }
        }

        composeTestRule.setContent {
            val density = LocalDensity.current
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = with(density) { 16.sp.toPx() }
                isAntiAlias = true
            }
            val measured = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            charHeightPx = if (measured <= 0f) 20f else measured

            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 600.dp),
                initialFontSize = 16.sp,
                typeface = Typeface.MONOSPACE,
                gestureCallback = callback,
                onTerminalTap = { tapCount++ },
            )
        }

        composeTestRule.waitForIdle()

        // Drag LEFT by 2.3 * charHeightPx -> scrollLeft = false (2 quanta).
        composeTestRule.onRoot().performTouchInput {
            val start = Offset(center.x + 80f, center.y)
            down(start)
            advanceEventTime(60)
            moveTo(Offset(start.x - 1.15f * charHeightPx, start.y))
            advanceEventTime(20)
            moveTo(Offset(start.x - 2.3f * charHeightPx, start.y))
            advanceEventTime(20)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals(0, holdStarts)
            assertEquals(0, tapCount)
            assertEquals(listOf(false, false), horizEvents)
        }
    }

    @Test
    fun doubleTouchWithinTimeoutArmsSwipeHoldAndResetsForSubsequentSingleDrag() {
        val scrollEvents = mutableListOf<Boolean>()
        val holdDirections = mutableListOf<SwipeHoldDirection>()
        var holdEnds = 0
        var charHeightPx = 20f
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        val callback = object : TerminalGestureCallback {
            override fun prefersRowHeightScrollQuantum(): Boolean = true

            override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
                scrollEvents += scrollUp
                return true
            }

            override fun onSwipeHoldStart(
                col: Int,
                row: Int,
                direction: SwipeHoldDirection,
            ): Boolean {
                holdDirections += direction
                return true
            }

            override fun onSwipeHoldEnd() {
                holdEnds++
            }
        }

        composeTestRule.setContent {
            val density = LocalDensity.current
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = with(density) { 16.sp.toPx() }
                isAntiAlias = true
            }
            val measured = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            charHeightPx = if (measured <= 0f) 20f else measured

            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 600.dp),
                initialFontSize = 16.sp,
                typeface = Typeface.MONOSPACE,
                gestureCallback = callback,
            )
        }

        composeTestRule.waitForIdle()

        // First touch (tap) then quick second touch + drag -> SwipeHold.
        composeTestRule.onRoot().performTouchInput {
            val pos = center
            down(pos)
            advanceEventTime(50)
            up()
            advanceEventTime(100)
            down(pos)
            advanceEventTime(60)
            moveTo(Offset(pos.x, pos.y - 2.5f * charHeightPx))
            advanceEventTime(200)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals(listOf(SwipeHoldDirection.Up), holdDirections)
            assertEquals(1, holdEnds)
            assertEquals("SwipeHold must suppress quantized onScroll", emptyList<Boolean>(), scrollEvents)
        }

        // Immediately following single-touch drag must NOT stay armed as SwipeHold.
        composeTestRule.onRoot().performTouchInput {
            advanceEventTime(100)
            val start = center
            down(start)
            advanceEventTime(60)
            moveTo(Offset(start.x, start.y - 2.2f * charHeightPx))
            advanceEventTime(20)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals("Consumed double-touch must reset before next gesture", 1, holdDirections.size)
            assertEquals(listOf(false, false), scrollEvents)
        }
    }

    @Test
    fun singleStepVerticalSwipeEmitsAtMostOnePerDirectionAndReversesBeforeRelease() {
        val scrollEvents = mutableListOf<Boolean>()
        var holdStarts = 0
        var charHeightPx = 20f
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        val callback = object : TerminalGestureCallback {
            override fun verticalSingleTouchSwipeMode(): SingleTouchSwipeMode = SingleTouchSwipeMode.SingleStepWithReversal

            override fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean {
                scrollEvents += scrollUp
                return true
            }

            override fun onSwipeHoldStart(
                col: Int,
                row: Int,
                direction: SwipeHoldDirection,
            ): Boolean {
                holdStarts++
                return true
            }
        }

        composeTestRule.setContent {
            val density = LocalDensity.current
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = with(density) { 16.sp.toPx() }
                isAntiAlias = true
            }
            val measured = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            charHeightPx = if (measured <= 0f) 20f else measured

            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 600.dp),
                initialFontSize = 16.sp,
                typeface = Typeface.MONOSPACE,
                gestureCallback = callback,
            )
        }

        composeTestRule.waitForIdle()

        // Drag UP 3.5 rows (max 1 Up step -> scrollUp=false), reverse DOWN 2.5 rows before release
        // (1 Down step -> scrollUp=true), reverse UP 2.0 rows again (1 Up step -> scrollUp=false), lift.
        composeTestRule.onRoot().performTouchInput {
            val start = Offset(center.x, center.y + 80f)
            down(start)
            advanceEventTime(40)
            moveTo(Offset(start.x, start.y - 1.2f * charHeightPx))
            advanceEventTime(20)
            moveTo(Offset(start.x, start.y - 3.5f * charHeightPx))
            advanceEventTime(20)
            // Reverse downward past touch slop and keep going -> max 1 opposite step.
            moveTo(Offset(start.x, start.y - 2.2f * charHeightPx))
            advanceEventTime(20)
            moveTo(Offset(start.x, start.y - 1.0f * charHeightPx))
            advanceEventTime(20)
            // Reverse upward again before release -> 1 step up.
            moveTo(Offset(start.x, start.y - 3.0f * charHeightPx))
            advanceEventTime(20)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals(0, holdStarts)
            assertEquals(listOf(false, true, false), scrollEvents)
        }

        // Quick second single-step swipe 150ms later must NOT trigger SwipeHold (previous gesture was a swipe, not a tap).
        composeTestRule.onRoot().performTouchInput {
            advanceEventTime(150)
            val start = center
            down(start)
            advanceEventTime(40)
            moveTo(Offset(start.x, start.y - 2.5f * charHeightPx))
            advanceEventTime(20)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals("Two consecutive swipes must not arm SwipeHold", 0, holdStarts)
            assertEquals(listOf(false, true, false, false), scrollEvents)
        }
    }

    @Test
    fun singleStepHorizontalSwipeEmitsAtMostOnePerDirectionAndReversesBeforeRelease() {
        val horizEvents = mutableListOf<Boolean>()
        var holdStarts = 0
        var tapCount = 0
        var charHeightPx = 20f
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        val callback = object : TerminalGestureCallback {
            override fun horizontalSingleTouchSwipeMode(): SingleTouchSwipeMode = SingleTouchSwipeMode.SingleStepWithReversal

            override fun onHorizontalScroll(col: Int, row: Int, scrollLeft: Boolean): Boolean {
                horizEvents += scrollLeft
                return true
            }

            override fun onSwipeHoldStart(
                col: Int,
                row: Int,
                direction: SwipeHoldDirection,
            ): Boolean {
                holdStarts++
                return true
            }
        }

        composeTestRule.setContent {
            val density = LocalDensity.current
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = with(density) { 16.sp.toPx() }
                isAntiAlias = true
            }
            val measured = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            charHeightPx = if (measured <= 0f) 20f else measured

            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 600.dp),
                initialFontSize = 16.sp,
                typeface = Typeface.MONOSPACE,
                gestureCallback = callback,
                onTerminalTap = { tapCount++ },
            )
        }

        composeTestRule.waitForIdle()

        // Drag LEFT 3.5 * charHeightPx (max 1 step: scrollLeft=false), then reverse RIGHT 2.5 * charHeightPx
        // before release (1 opposite step: scrollLeft=true), then lift.
        composeTestRule.onRoot().performTouchInput {
            val start = Offset(center.x + 80f, center.y)
            down(start)
            advanceEventTime(40)
            moveTo(Offset(start.x - 1.5f * charHeightPx, start.y))
            advanceEventTime(20)
            moveTo(Offset(start.x - 3.5f * charHeightPx, start.y))
            advanceEventTime(20)
            moveTo(Offset(start.x - 1.0f * charHeightPx, start.y))
            advanceEventTime(20)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals(0, holdStarts)
            assertEquals(0, tapCount)
            assertEquals(listOf(false, true), horizEvents)
        }
    }

    @Test
    fun doubleTouchHoldPauseDoesNotTriggerEarlySelectionAndSingleHoldUsesIncreasedDelay() {
        ShadowLog.clear()
        val holdDirections = mutableListOf<SwipeHoldDirection>()
        var holdEnds = 0
        var charHeightPx = 20f
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        val callback = object : TerminalGestureCallback {
            override fun verticalSingleTouchSwipeMode(): SingleTouchSwipeMode = SingleTouchSwipeMode.TrackFingerByRowHeight
            override fun horizontalSingleTouchSwipeMode(): SingleTouchSwipeMode = SingleTouchSwipeMode.SingleStepWithReversal

            override fun onSwipeHoldStart(
                col: Int,
                row: Int,
                direction: SwipeHoldDirection,
            ): Boolean {
                holdDirections += direction
                return true
            }

            override fun onSwipeHoldEnd() {
                holdEnds++
            }
        }

        composeTestRule.setContent {
            val density = LocalDensity.current
            val paint = TextPaint().apply {
                typeface = Typeface.MONOSPACE
                textSize = with(density) { 16.sp.toPx() }
                isAntiAlias = true
            }
            val measured = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            charHeightPx = if (measured <= 0f) 20f else measured

            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 600.dp),
                initialFontSize = 16.sp,
                typeface = Typeface.MONOSPACE,
                gestureCallback = callback,
            )
        }

        composeTestRule.waitForIdle()
        fun selectionLogs() = ShadowLog.getLogsForTag("HavenGesture").map { it.msg }.filter { "selection-started" in it }

        // 1. Double-tap with a 350ms inter-tap gap (> 300ms platform default) and a 650ms hold
        // before moving (> 400ms system long-press timeout) must NOT enter text selection and
        // MUST arm SwipeHold once dragged.
        composeTestRule.onRoot().performTouchInput {
            down(center)
            advanceEventTime(60)
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(350)
        composeTestRule.onRoot().performTouchInput {
            down(center)
        }
        composeTestRule.mainClock.advanceTimeBy(650)
        composeTestRule.onRoot().performTouchInput {
            moveTo(Offset(center.x, center.y - 2.0f * charHeightPx))
            advanceEventTime(100)
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "Double-touch hold must not trigger early text selection: ${selectionLogs()}",
            selectionLogs().isEmpty(),
        )
        assertEquals(listOf(SwipeHoldDirection.Up), holdDirections)
        assertEquals(1, holdEnds)

        // 2. Single touch + hold: at 650ms (> 400ms system timeout) selection must NOT have started yet,
        // and after the increased hold delay (1350ms total >= 1200ms) selection MUST start.
        ShadowLog.clear()
        composeTestRule.mainClock.advanceTimeBy(700)
        composeTestRule.onRoot().performTouchInput {
            down(center)
        }
        composeTestRule.mainClock.advanceTimeBy(650)
        composeTestRule.waitForIdle()
        assertTrue(
            "Single touch at 650ms must not trigger selection early in swipe mode: ${selectionLogs()}",
            selectionLogs().isEmpty(),
        )

        composeTestRule.mainClock.advanceTimeBy(700)
        composeTestRule.waitForIdle()
        assertTrue(
            "Single touch held past increased delay (1350ms) must trigger text selection",
            selectionLogs().isNotEmpty(),
        )
        composeTestRule.onRoot().performTouchInput {
            up()
        }
    }
}
