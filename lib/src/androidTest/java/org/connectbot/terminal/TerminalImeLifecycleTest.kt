/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Requires an unlocked device with an enabled software IME. Uses no Espresso hidden APIs. */
@RunWith(AndroidJUnit4::class)
class TerminalImeLifecycleTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var activity: ComponentActivity
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        activityRule.scenario.onActivity { activity = it }
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    private fun settleLayout() {
        // Allow real popup/IME animations to complete (not Compose's virtual clock).
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun setContent(content: @Composable () -> Unit) {
        activity.setContentView(ComposeView(activity).apply { setContent(content) })
    }

    /**
     * The fork's IME contract, device-verified: BACK dismisses the keyboard at
     * the platform level, but onImeVisibilityChanged reports host intent
     * (keyboardEnabled && showSoftKeyboard), not platform truth — so the report
     * stays true across a BACK dismissal, and only the host's
     * showSoftKeyboard=false opt-out reports false. Reopening is the tap's
     * job: requestFocus() alone is a no-op after a BACK dismissal because the
     * input view never lost focus, so the tap handler must call showIme()
     * explicitly while the host still wants the keyboard (the fold merge kept
     * the fork's tap implementation and dropped upstream's showIme() call —
     * this test is what caught it). A tap after opt-out must not reopen.
     */
    @Test
    fun backDismissesTapReopensAndHostOptOutHidesIt() {
        val terminal = TerminalEmulatorFactory.create()
        val enabled = mutableStateOf(true)
        var reportedVisibility: Boolean? = null
        instrumentation.runOnMainSync {
            setContent {
                Box(Modifier.fillMaxSize().imePadding()) {
                    Terminal(
                        terminal,
                        Modifier.fillMaxSize(),
                        keyboardEnabled = true,
                        showSoftKeyboard = enabled.value,
                        onImeVisibilityChanged = { reportedVisibility = it },
                    )
                }
            }
        }
        waitUntil("Keyboard did not open on entry") { imeVisible() && reportedVisibility == true }
        settleLayout()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("Back did not dismiss the keyboard") { !imeVisible() }
        instrumentation.runOnMainSync {
            assertTrue("A platform dismissal must not change the host-intent report", reportedVisibility == true)
        }
        terminal.writeInput("still connected\r\n".toByteArray())
        settleLayout()
        instrumentation.runOnMainSync { assertFalse(imeVisible()) }
        tapTerminal()
        waitUntil("Tapping the focused terminal did not reopen the keyboard") { imeVisible() }
        instrumentation.runOnMainSync { assertTrue(reportedVisibility == true) }
        instrumentation.runOnMainSync { enabled.value = false }
        waitUntil("Host opt-out did not hide the keyboard") { !imeVisible() && reportedVisibility == false }
        settleLayout()
        tapTerminal()
        settleLayout()
        instrumentation.runOnMainSync { assertFalse("A tap must honor showSoftKeyboard=false", imeVisible()) }
    }

    private fun tapTerminal() {
        val now = SystemClock.uptimeMillis()
        val location = IntArray(2)
        instrumentation.runOnMainSync { activity.window.decorView.getLocationOnScreen(location) }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, location[0] + 100f, location[1] + 200f, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    /**
     * Upstream added this test alongside a `resizeSuspended` parameter that
     * let the host pin the terminal's dimensions across a transient popup
     * layout. This fork has no such parameter: a transient layout is handled
     * by holding the pending PTY resize until window focus returns (#554),
     * so the round-trip is asserted against that mechanism instead — the
     * popup's own layout must never reach the emulator, and the settle below
     * is longer than the commit debounce plus the IME-restore grace, so any
     * resize the hold failed to suppress would have been applied by now.
     */
    @Test
    fun menuRoundTripRetainsTerminalDimensions() {
        val menu = mutableStateOf(false)
        val sizes = CopyOnWriteArrayList<TerminalDimensions>()
        val terminal = TerminalEmulatorFactory.create(onResize = { sizes.add(it) }) as TerminalEmulatorImpl
        instrumentation.runOnMainSync {
            setContent {
                Box(Modifier.fillMaxSize().imePadding()) {
                    Terminal(terminal, Modifier.fillMaxSize(), keyboardEnabled = true)
                    DropdownMenu(expanded = menu.value, onDismissRequest = { menu.value = false }) {
                        Text("Settings")
                    }
                }
            }
        }
        waitUntil("Keyboard did not open on entry") { imeVisible() }
        settleLayout()
        val original = terminal.dimensions
        sizes.clear()
        instrumentation.runOnMainSync { menu.value = true }
        settleLayout()
        instrumentation.runOnMainSync { menu.value = false }
        waitUntil("Menu did not restore window focus and keyboard") { activity.hasWindowFocus() && imeVisible() }
        // Longer than RESIZE_DEBOUNCE_MS + IME_RESTORE_GRACE_MS so a resize
        // the pending-resize hold did not suppress is committed before we look.
        settleLayout()
        settleLayout()
        assertTrue("Temporary menu layouts must not reach the remote PTY (original=$original): $sizes", sizes.isEmpty())
    }
}
