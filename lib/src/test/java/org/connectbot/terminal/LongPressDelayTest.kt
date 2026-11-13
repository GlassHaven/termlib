package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #435: tapping a zellij tab opened Haven's copy interface instead of clicking.
 *
 * Long-press starts a Haven-local selection in every session so tmux/zellij
 * users keep the visible select-and-Copy workflow. That made a *click* in a
 * mouse-mode app race the system long-press timeout — aiming carefully at a
 * small target (a zellij tab, a tmux pane border) beats it, so the press became
 * a selection and the app never saw the click. Only quick taps got through,
 * which is why the feature looked like it already worked.
 */
class LongPressDelayTest {

    /** Android's default is ~400-500ms; use a representative value. */
    private val systemTimeout = 400L

    @Test
    fun `mouse mode gets more grace than the system timeout`() {
        val mouse = longPressDelayMs(mouseMode = true, systemLongPressMs = systemTimeout)
        assertTrue(
            "a careful tap in a mouse-mode app must not be stolen by the selection " +
                "long-press ($mouse must exceed $systemTimeout)",
            mouse > systemTimeout,
        )
    }

    /**
     * Outside mouse mode nothing changes — selection must stay as responsive as
     * the platform expects, since there is no app competing for the click.
     */
    @Test
    fun `non-mouse mode uses the platform timeout unchanged`() {
        assertEquals(
            systemTimeout,
            longPressDelayMs(mouseMode = false, systemLongPressMs = systemTimeout),
        )
    }

    /**
     * The grace must stay in "deliberate hold" territory: long enough to clear a
     * careful tap, short enough that press-and-hold to select still feels
     * immediate rather than broken.
     */
    @Test
    fun `mouse-mode grace stays within a usable hold range`() {
        val mouse = longPressDelayMs(mouseMode = true, systemLongPressMs = systemTimeout)
        assertTrue("grace should clear a slow tap", mouse >= 750L)
        assertTrue("a deliberate hold should not feel unresponsive", mouse <= 1200L)
    }

    /** A larger platform timeout must still be honoured verbatim when not in mouse mode. */
    @Test
    fun `platform timeout is passed through, not clamped`() {
        assertEquals(1500L, longPressDelayMs(mouseMode = false, systemLongPressMs = 1500L))
    }

    @Test
    fun `swipe mode increases selection hold delay above system and mouse-mode timeouts`() {
        val mouse = longPressDelayMs(mouseMode = true, systemLongPressMs = systemTimeout, swipeMode = false)
        val swipePlain = longPressDelayMs(mouseMode = false, systemLongPressMs = systemTimeout, swipeMode = true)
        val swipeMouse = longPressDelayMs(mouseMode = true, systemLongPressMs = systemTimeout, swipeMode = true)
        assertTrue(
            "swipe mode selection delay ($swipePlain) must exceed mouse-mode grace ($mouse)",
            swipePlain > mouse,
        )
        assertEquals(swipePlain, swipeMouse)
        assertEquals(1800L, longPressDelayMs(mouseMode = false, systemLongPressMs = 1800L, swipeMode = true))
    }

    /**
     * Measured on-device: with a selection showing, two identical taps produced
     * only one SGR click on the wire — the first was spent dismissing the
     * selection. In a mouse-mode app that reads as "tapping does nothing",
     * which is the state a stolen press leaves you in.
     */
    @Test
    fun `a tap in mouse mode is delivered even when a selection is showing`() {
        assertFalse(
            "the app owns the click — dismissing Haven's selection must not consume it",
            tapOnlyDismissesSelection(selectionActive = true, mouseMode = true),
        )
    }

    /** Outside mouse mode nothing competes for the click, so dismissing is the gesture. */
    @Test
    fun `a tap outside mouse mode just dismisses the selection`() {
        assertTrue(tapOnlyDismissesSelection(selectionActive = true, mouseMode = false))
    }

    /** With no selection showing there is nothing to dismiss, in either mode. */
    @Test
    fun `with no selection a tap is never spent dismissing`() {
        assertFalse(tapOnlyDismissesSelection(selectionActive = false, mouseMode = false))
        assertFalse(tapOnlyDismissesSelection(selectionActive = false, mouseMode = true))
    }
}
