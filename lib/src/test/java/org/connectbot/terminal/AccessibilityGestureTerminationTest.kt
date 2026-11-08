package org.connectbot.terminal

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * #435: the reporter's taps become text selections, their finger taps fail
 * while a scrcpy mouse click works, and they have confirmed that accessibility
 * services (Tasker, a password manager) are enabled.
 *
 * That last detail is the only structural difference anyone has found: when ANY
 * accessibility service is enabled — `AccessibilityManager.isEnabled`, not just
 * TalkBack — Haven composes two full-screen overlays ABOVE the terminal
 * (`AccessibilityOverlay`, a `LazyColumn`, and `LiveOutputRegion`). A layer over
 * the terminal is exactly the sort of thing that can swallow a finger-up while
 * letting the finger-down through, and a gesture that never sees its release is
 * what leaves the long-press timer to fire into an absent finger.
 *
 * So this asks the narrow question directly: does a plain tap still TERMINATE
 * its gesture when those overlays are present?
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityGestureTerminationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun gestureLines() = ShadowLog.getLogsForTag("HavenGesture").map { it.msg }

    private fun tapAndCollect(accessibility: Boolean): List<String> {
        ShadowLog.clear()
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        runBlocking { emulator.writeInput("ready\r\n".toByteArray()) }

        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                keyboardEnabled = true,
                forceAccessibilityEnabled = accessibility,
                modifier = Modifier.size(400.dp, 600.dp),
            )
        }
        composeTestRule.waitForIdle()

        // A deliberate, complete tap: press and release, well under any
        // long-press threshold.
        composeTestRule.onRoot().performTouchInput {
            down(center)
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()
        return gestureLines()
    }

    /**
     * Control. Without the overlays a completed tap must end its gesture, and
     * must not leave a selection behind.
     */
    @Test
    fun `without accessibility a tap ends its gesture`() {
        val lines = tapAndCollect(accessibility = false)
        assertTrue(
            "a completed tap logged no gesture end; harness cannot see terminations: $lines",
            lines.any { it.contains("gesture=ended-") },
        )
        assertTrue(
            "a short tap started a selection with no accessibility involved: $lines",
            lines.none { it.contains("selection-started") },
        )
    }

    /**
     * The question. If this fails while the control passes, the overlays are
     * eating the release and the fix belongs there rather than in the timer.
     */
    @Test
    fun `with accessibility enabled a tap still ends its gesture`() {
        val lines = tapAndCollect(accessibility = true)
        assertTrue(
            "with the accessibility overlays composed, a completed tap never ended " +
                "its gesture — the release is being swallowed above the terminal, " +
                "which is what leaves the long-press timer to fire into a finger " +
                "that has already gone (#435). Lines: $lines",
            lines.any { it.contains("gesture=ended-") },
        )
        assertTrue(
            "a short tap started a selection with accessibility enabled: $lines",
            lines.none { it.contains("selection-started") },
        )
    }

    @Test
    fun `tap on boxed prompt in mouse mode positions cursor instead of sending mouse click`() {
        val dispatchedBytes = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> dispatchedBytes.add(data.copyOf()) },
        )

        val tappedCells = mutableListOf<Pair<Int, Int>>()
        val callback = object : TerminalGestureCallback {
            override fun onTap(col: Int, row: Int): Boolean {
                tappedCells.add(col to row)
                return true
            }
        }

        // Verify that even when tapToPositionCursorOnPrompt starts false (e.g. DataStore
        // initial value) and flips to true before the tap, pointerInput sees the updated state.
        val tapPrefState = androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                keyboardEnabled = true,
                tapToPositionCursorOnPrompt = tapPrefState.value,
                gestureCallback = callback,
                modifier = Modifier.size(400.dp, 600.dp),
            )
        }
        composeTestRule.waitForIdle()
        tapPrefState.value = true
        composeTestRule.waitForIdle()

        val rule = "─".repeat(30)
        runBlocking {
            // Render boxed prompt at row 1 (top rule at row 0, bottom rule at row 2),
            // then move cursor back to row 1, col 13 (1-based: 2;14H) at end of "> hello world".
            emulator.writeInput("\u001b[H$rule\r\n> hello world\r\n$rule\u001b[2;14H".toByteArray())
            (emulator as TerminalEmulatorImpl).processPendingUpdates()
        }
        composeTestRule.waitForIdle()

        val snap = (emulator as TerminalEmulatorImpl).snapshot.value
        org.junit.Assert.assertEquals(1, snap.cursorRow)
        org.junit.Assert.assertEquals(13, snap.cursorCol)

        // Tap near the start of row 1 (x = 10f -> col <= 2, while cursorCol = 13 -> delta < 0):
        composeTestRule.onRoot().performTouchInput {
            val target = androidx.compose.ui.geometry.Offset(10f, 22f)
            down(target)
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        org.junit.Assert.assertEquals(
            "mouse callback should not swallow a tap on the active boxed prompt (tappedCells=$tappedCells, cursor=${snap.cursorRow},${snap.cursorCol})",
            0,
            tappedCells.size,
        )
        assertTrue(
            "expected LEFT arrow key dispatches to position cursor on boxed prompt",
            dispatchedBytes.isNotEmpty(),
        )
    }
}
