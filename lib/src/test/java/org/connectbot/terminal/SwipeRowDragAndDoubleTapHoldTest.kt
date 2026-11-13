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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
