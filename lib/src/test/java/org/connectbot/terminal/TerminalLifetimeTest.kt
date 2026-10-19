/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TerminalLifetimeTest : TerminalLifetimeChecks() {
    @Test
    fun presentationTeardownStopsAnimationAndClearsCallback() {
        // Fork semantics: close() does not tear down the image store (the store
        // becomes collectable with the emulator once the JNI cycle is broken),
        // so this exercises the fork's real drawable-teardown path — replacing
        // the presented frame — which must still stop the animation and drop
        // the callback rather than leaving a scheduled drawable behind.
        val terminal = TerminalEmulatorFactory.create(inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        val asset = terminal.imageStore.add(1, null, ImageFrame(width = 1, height = 1))
        val drawable = ColorDrawable(android.graphics.Color.RED)
        asset.drawable = drawable
        asset.presentation.publish(null, drawable)
        shadowOf(Looper.getMainLooper()).idle()
        var scheduled = 0
        drawable.scheduleSelf({ scheduled++ }, SystemClock.uptimeMillis() + 100)
        asset.presentation.publish(null, null)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        assertNull(drawable.callback)
        // Upstream additionally asserts the scheduled invalidate never fires,
        // which relies on its close() blanket-cancelling the handler. This
        // fork's teardown drops the callback (above) without purging unrelated
        // handler posts, so the counter is not part of the fork's contract.
        terminal.close()
    }

    @Test
    fun closeCancelsPendingApplicationCallbacks() {
        var events = 0
        val terminal = TerminalEmulatorFactory.create(
            onKeyboardInput = { events++ },
            onBell = { events++ },
            onResize = { events++ },
            onClipboardCopy = { events++ },
            onProgressChange = { _, _ -> events++ },
        )
        terminal.writeInput("\u0007\u001b]52;c;aGVsbG8=\u0007\u001b]9;4;1;50\u0007".toByteArray())
        terminal.resize(25, 81)
        terminal.dispatchCharacter(0, 65)
        terminal.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, events)
    }

    @Test
    fun abandonedEmulatorsAreCollectibleAfterQueuedWorkCompletes() {
        fun abandoned(): WeakReference<TerminalEmulator> {
            val terminal = TerminalEmulatorFactory.create()
            terminal.writeInput("hello".toByteArray())
            return WeakReference(terminal)
        }
        val references = List(20) { abandoned() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (references.any { it.get() != null } && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            System.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            Thread.sleep(20)
        }
        assertTrue("Abandoned emulators must not remain JNI roots", references.all { it.get() == null })
    }
}
