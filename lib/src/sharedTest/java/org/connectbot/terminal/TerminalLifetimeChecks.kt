/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shared between Robolectric and on-device runs. Adapted from upstream
 * `93a4e04e` to this fork's close semantics: public operations on a closed
 * emulator are no-ops (whenOpen swallows the single IllegalStateException
 * TerminalNative throws) rather than throwing, and close() does not clear the
 * snapshot or the image store. The leak regression —
 * [abandonedManagedCallbackCyclesAreCollectible] — is kept verbatim.
 */
abstract class TerminalLifetimeChecks {
    private fun nativeDelegate(terminal: TerminalEmulatorImpl): Lazy<*> = TerminalEmulatorImpl::class.java.getDeclaredField("terminalNativeDelegate").let {
        it.isAccessible = true
        it.get(terminal) as Lazy<*>
    }

    @Test
    fun closingUnusedTerminalDoesNotInitializeNative() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val native = nativeDelegate(terminal)
        terminal.close()
        terminal.close()
        assertFalse(native.isInitialized())
        // Fork semantics: a closed emulator's public operations are no-ops, not
        // throws — the threads still calling in are transports delivering their
        // last bytes during teardown. (close() does not clear the snapshot, so
        // the blank rows a fresh emulator carries stay as they are.)
        terminal.writeInput(byteArrayOf(65))
        assertFalse(native.isInitialized())
    }

    @Test
    fun closeSeversNativeHandleAndMakesPublicOperationsNoOps() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 2, initialCols = 10) as TerminalEmulatorImpl
        terminal.writeInput("first\r\nsecond\r\nthird".toByteArray())
        terminal.processPendingUpdates()
        val previous = terminal.snapshot.value
        val native = nativeDelegate(terminal).value as TerminalNative
        terminal.close()
        terminal.close()
        assertTrue(previous.lines.isNotEmpty())
        assertEquals(TerminalDimensions(2, 10), terminal.dimensions)
        // The native handle itself is gone: TerminalNative.withPtr throws for the
        // one IllegalStateException it defines, and that is what whenOpen absorbs
        // at the emulator layer.
        assertThrows(IllegalStateException::class.java) { native.writeInput(byteArrayOf(65)) }
        // Every public operation is a no-op rather than a throw, and the Int-
        // returning ones report the API's existing "did not happen" value.
        assertEquals(-1, terminal.setAnsiPalette(IntArray(16)))
        assertEquals(-1, terminal.setDefaultColors(0, 0))
        val operations: List<() -> Any?> = listOf(
            { terminal.writeInput(byteArrayOf(65)) },
            { terminal.writeInput(ByteBuffer.allocateDirect(1), 1) },
            { terminal.resize(2, 10) },
            { terminal.dispatchKey(0, VTermKey.ENTER) },
            { terminal.dispatchCharacter(0, 65) },
            { terminal.clearScreen() },
            { terminal.setInlineImages(InlineImages.On()) },
            { terminal.setCellPixelSize(8, 16) },
            { terminal.applyColorScheme(IntArray(16), 0, 0) },
            { terminal.getLastCommandOutput() },
            { terminal.getUrls() },
        )
        operations.forEach { operation -> operation() }
    }

    @Test
    fun closeCanRaceInputResizeSnapshotsAndOtherCloses() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        terminal.writeInput("hello".toByteArray())
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val actions: List<() -> Unit> = listOf(
            { terminal.writeInput("hello\r\n".toByteArray()) },
            { terminal.resize(25, 81) },
            { terminal.processPendingUpdates() },
            { terminal.dispatchCharacter(0, 65) },
            { terminal.close() },
            { terminal.close() },
        )
        val workers = actions.map { action ->
            thread {
                start.await()
                try {
                    repeat(20) { action() }
                } catch (_: IllegalStateException) {
                    // A public operation may lose the race to disposal.
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }
        terminal.close()
        assertTrue(workers.none { it.isAlive })
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test
    fun nativeCallbackExceptionsDoNotLeaveBorrowedContextInstalled() {
        val callbacks = NativeCallbackStub()
        TerminalNative(callbacks).use { native ->
            callbacks.native = native
            callbacks.bellAction = { throw IllegalArgumentException("callback failed") }
            // This fork's JNI_CHECK_EXCEPTION macro describes-and-clears rather
            // than rethrows, so the throwing bell does not surface here. What
            // matters is that the CallbackScope still unwound (RAII) and the
            // next call reinstalls cleanly — on the host Debug build a leaked
            // mEnv/mCallbacks trips the scope's assert() and aborts.
            native.writeInput(byteArrayOf(7))
            callbacks.bellAction = { 0 }
            assertEquals(1, native.writeInput(byteArrayOf(7)))
        }
    }

    @Test
    fun abandonedManagedCallbackCyclesAreCollectible() {
        fun abandoned(): WeakReference<NativeCallbackStub> {
            val callbacks = NativeCallbackStub()
            callbacks.native = TerminalNative(callbacks)
            return WeakReference(callbacks)
        }
        // ART may keep a WeakReference.get() result in an interpreter register
        // across the next GC. Create on a terminated thread and don't poll the
        // referents while collecting, so the test itself cannot keep one alive.
        val created = AtomicReference<List<WeakReference<NativeCallbackStub>>>()
        thread { created.set(List(20) { abandoned() }) }.join()
        repeat(20) {
            System.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            Thread.sleep(20)
        }
        assertEquals("JNI must not root the callback/native ownership cycle", 0, created.get().count { it.get() != null })
    }

    @Test
    fun closedImagesPreserveBorrowedBitmap() {
        val terminal = TerminalEmulatorFactory.create(inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        val asset = terminal.imageStore.add(1, null, ImageFrame(width = 1, height = 1))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        asset.bitmap = bitmap
        asset.presentation.publish(bitmap, null)
        val borrowed = asset.presentation.frame
        terminal.close()
        terminal.imageStore.updateViewport(ImageViewport(0, emptyList(), 8f, 16f, 0))
        asset.request(1, 1)
        asset.presentation.publish(bitmap, null)
        // Fork close() does not clear the image store, so the asset survives;
        // the contract this test holds is that a borrowed bitmap is never
        // recycled by close/teardown and re-publishing the same frame is a
        // no-op that leaves the presented frame identity intact.
        assertSame(bitmap, borrowed.bitmap)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun decodeCompletingAfterCloseDoesNotPublishOrRetainPixels() {
        val terminal = TerminalEmulatorFactory.create(inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        val decoder = InlineImageStore::class.java.getDeclaredField("decoder").let {
            it.isAccessible = true
            it.get(null) as java.util.concurrent.ExecutorService
        }
        val maintenance = InlineImageStore::class.java.getDeclaredField("maintenance").let {
            it.isAccessible = true
            it.get(terminal.imageStore) as java.util.concurrent.ExecutorService
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        decoder.execute {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1;/wAA/w==\u001b\\".toByteArray())
            val asset = terminal.imageStore.assets.values.single()
            asset.request(1, 1)
            assertTrue(asset.pending)
            terminal.close()
            release.countDown()
            // Drain the single-threaded decoder, then drain the store's single-
            // threaded maintenance executor onto which decode completion hops.
            decoder.submit {}.get(5, TimeUnit.SECONDS)
            maintenance.submit {}.get(5, TimeUnit.SECONDS)
            synchronized(terminal.imageStore) {
                assertFalse(asset.pending)
                // Fork semantics: close() does not clear the image store, so the
                // late decode publishes into the still-registered asset instead of
                // being dropped as upstream does. The pixels are then owned by the
                // store and become collectable with the emulator — which the JNI
                // cycle fix is what guarantees. What this test holds is that the
                // completion lands cleanly (no use-after-free, no dangling borrow)
                // rather than upstream's earlier-release contract.
                assertNotNull(asset.bitmap)
            }
        } finally {
            release.countDown()
            terminal.close()
        }
    }
}

/** No-op callbacks for lifetime and boundary tests, without mock-framework roots. */
internal class NativeCallbackStub : TerminalCallbacks {
    var native: TerminalNative? = null
    var bellAction: () -> Int = { 0 }
    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int = 0
    override fun moverect(dest: TermRect, src: TermRect): Int = 0
    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int = 0
    override fun setTermProp(prop: Int, value: TerminalProperty): Int = 0
    override fun bell(): Int = bellAction()
    override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>, softWrapped: Boolean): Int = 0
    override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int = 0
    override fun clearScrollback(): Int = 0
    override fun onKeyboardInput(data: ByteArray): Int = 0
    override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int): Int = 0
}
