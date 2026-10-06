package org.connectbot.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upstream 6472614e: a horizontally-consumed swipe (Haven's session pager
 * consumes horizontal movement in the Initial pass) must not surface as a
 * terminal tap.
 *
 * Upstream fixed this by classifying on consumed movement (1a2323ca), which
 * would be a semantic conflict here: the fork classifies drags on raw
 * position deltas (#186/#524, the pagerSwipeOverride note) and suppresses the
 * tap through the `isHorizontalDrag` gate. This test locks that behaviour in
 * so a rewrite cannot regress it, and asserts the gesture pipeline recovers
 * for an ordinary tap afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ConsumedSwipeTapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun consumedSessionSwipeDoesNotTriggerTerminalTap() {
        var tapCount = 0
        var consumedMoves = 0
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.pointerInput(Unit) {
                    // Match Haven's session navigation: consume horizontal
                    // movement in Initial, before the terminal receives Main.
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var distance = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.first { it.id == down.id }
                            if (!change.pressed) break
                            distance += change.positionChange().x
                            if (kotlin.math.abs(distance) > viewConfiguration.touchSlop) {
                                change.consume()
                                consumedMoves++
                            }
                        }
                    }
                },
                onTerminalTap = { tapCount++ },
            )
        }
        composeTestRule.onRoot().performTouchInput {
            down(Offset(width * 0.8f, center.y))
            advanceEventTime(100)
            moveTo(Offset(width * 0.6f, center.y))
            moveTo(Offset(width * 0.3f, center.y))
            up()
        }
        composeTestRule.runOnIdle {
            assertTrue("Parent must consume the swipe", consumedMoves > 0)
            assertEquals("A consumed swipe must not reopen the keyboard through a tap", 0, tapCount)
        }
        // A consumed gesture must not prevent a subsequent ordinary tap.
        composeTestRule.onRoot().performTouchInput {
            advanceEventTime(400)
            click()
        }
        composeTestRule.runOnIdle { assertEquals(1, tapCount) }
    }
}