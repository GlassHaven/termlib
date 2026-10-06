/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import java.io.File
import java.nio.ByteBuffer

/**
 * Terminal emulator using libvterm via JNI.
 *
 * This class provides terminal emulation without PTY management.
 * The caller is responsible for:
 * - Creating and managing the PTY
 * - Reading data from PTY and feeding to writeInput()
 * - Handling onKeyboardInput() callback and writing to PTY
 *
 * Thread Safety:
 * - All native calls are protected by a non-reentrant mutex
 * - Callbacks MUST NOT call back into Terminal methods (will deadlock)
 * - Safe to call from multiple threads (serialized by native mutex)
 *
 * That native mutex serialises calls against each other, but it cannot make
 * destruction safe: it is a member of the `Terminal` that `nativeDestroy`
 * deletes, so it dies with the object it would have to outlive. Lifetime is
 * therefore this class's problem, and [withPtr] is where it is solved — see
 * the note there before adding a method that touches [nativePtr].
 */
internal class TerminalNative(
    callbacks: TerminalCallbacks,
    enableAltScreen: Boolean = true,
    xtversion: String? = null,
) : AutoCloseable {
    private var nativePtr: Long = 0

    init {
        nativePtr = nativeInit(callbacks, enableAltScreen, xtversion)
        if (nativePtr == 0L) {
            throw RuntimeException("Failed to initialize native terminal")
        }
    }

    /**
     * Run [block] with the native pointer, or throw if the terminal is closed.
     *
     * **Every** native call goes through here. The old shape —
     *
     * ```
     * checkNotClosed()
     * return nativeWriteInputArray(nativePtr, …)
     * ```
     *
     * — reads the pointer twice with a window in between, and frees the object
     * from a place that window cannot see. Two ways that crashes, both of which
     * land as a native SIGSEGV on whichever thread was running:
     *
     * 1. Nothing here calls [close]. The only thing that frees the native
     *    terminal is [finalize], on the GC's finalizer thread. Once `nativePtr`
     *    has been loaded into a register, `this` is never touched again, so the
     *    collector may decide the object is unreachable and finalize it *while
     *    the native call is still executing* — `delete term` underneath a live
     *    `vterm_input_write`. This is the hazard `reachabilityFence` exists for.
     * 2. An explicit `close()` on another thread, should one ever be added,
     *    lands between the check and the use for the ordinary reason.
     *
     * `synchronized(this)` answers both with one mechanism: it makes the check,
     * the read and the call atomic against [close], and a held monitor is a GC
     * root, so the receiver cannot be finalised for as long as the native call
     * is running. Reading into a local means the pointer passed to native is
     * provably the one that was checked.
     *
     * Lock order is always JVM monitor → native mutex, so this cannot invert
     * against the native lock. The existing rule still stands: a callback must
     * not synchronously re-enter these methods — the JVM monitor is reentrant
     * and would let it through, and the non-recursive native mutex would then
     * deadlock exactly as it does today.
     */
    private inline fun <T> withPtr(block: (Long) -> T): T = synchronized(this) {
        val ptr = nativePtr
        if (ptr == 0L) {
            throw IllegalStateException("Terminal has been closed")
        }
        block(ptr)
    }

    /**
     * Feed input data from PTY to the terminal emulator.
     * This processes the byte stream and updates the terminal state.
     *
     * @param buffer Direct ByteBuffer containing data
     * @param length Number of bytes to read
     * @return Number of bytes consumed
     */
    fun writeInput(buffer: ByteBuffer, length: Int): Int {
        return withPtr { nativeWriteInputBuffer(it, buffer, length) }
    }

    /**
     * Feed input data from PTY to the terminal emulator.
     * This processes the byte stream and updates the terminal state.
     *
     * @param data Byte array containing data
     * @param offset Starting offset in array
     * @param length Number of bytes to read
     * @return Number of bytes consumed
     */
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        return withPtr { nativeWriteInputArray(it, data, offset, length) }
    }

    /**
     * Resize the terminal.
     *
     * @param rows Number of rows
     * @param cols Number of columns
     * @return 0 on success
     */
    fun resize(rows: Int, cols: Int): Int {
        return withPtr { nativeResize(it, rows, cols) }
    }

    /**
     * Dispatch a keyboard key event to the terminal.
     * This generates appropriate escape sequences via onKeyboardInput() callback.
     *
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @param key VTermKey value
     * @return true if handled
     */
    fun dispatchKey(modifiers: Int, key: Int): Boolean {
        return withPtr { nativeDispatchKey(it, modifiers, key) }
    }

    /**
     * Dispatch a character input to the terminal.
     * This generates appropriate escape sequences via onKeyboardInput() callback.
     *
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @param character Unicode codepoint
     * @return true if handled
     */
    fun dispatchCharacter(modifiers: Int, character: Int): Boolean {
        return withPtr { nativeDispatchCharacter(it, modifiers, character) }
    }

    /**
     * Get a run of cells with identical formatting starting at the given position.
     * This is the primary method for retrieving terminal content for rendering.
     *
     * @param row Row index (0-based)
     * @param col Column index (0-based)
     * @param run CellRun object to fill (reusable, call reset() first)
     * @return Number of cells in the run
     */
    fun getCellRun(row: Int, col: Int, run: CellRun): Int {
        return withPtr { nativeGetCellRun(it, row, col, run) }
    }

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with the new colors.
     *
     * @param colors IntArray of ARGB colors (must have at least 'count' elements)
     * @param count Number of colors to set (max 16, default: min(colors.size, 16))
     * @return Number of colors set, or -1 on error
     */
    fun setPaletteColors(colors: IntArray, count: Int = colors.size.coerceAtMost(16)): Int {
        require(count <= 16) { "Can only set up to 16 ANSI palette colors" }
        require(colors.size >= count) { "Color array too small for requested count" }
        return withPtr { nativeSetPaletteColors(it, colors, count) }
    }

    /**
     * Set default foreground and background colors.
     *
     * These colors are used when terminal content explicitly requests "default" color
     * (different from ANSI color 7/0). Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    fun setDefaultColors(foreground: Int, background: Int): Int {
        return withPtr { nativeSetDefaultColors(it, foreground, background) }
    }

    /**
     * Get the continuation (soft wrap) status for a visible screen line.
     *
     * A line is a "continuation" if it continues from the previous line due to
     * text wrapping, rather than starting after a hard newline.
     *
     * @param row Row index (0-based)
     * @return true if this line is a continuation of the previous line
     */
    fun getLineContinuation(row: Int): Boolean {
        return withPtr { nativeGetLineContinuation(it, row) }
    }

    /**
     * Enable or disable bold-as-bright color promotion.
     *
     * When enabled, bold text using low-intensity ANSI colors (0–7) promotes
     * to the corresponding bright palette color (8–15), matching xterm behavior.
     *
     * @param enabled true to enable bold-as-bright, false to disable
     * @return 0 on success, -1 on error
     */
    fun setBoldHighbright(enabled: Boolean): Int {
        return withPtr { nativeSetBoldHighbright(it, enabled) }
    }

    /**
     * Close the terminal and release native resources.
     * After calling this, the Terminal instance cannot be used.
     */
    @Synchronized
    override fun close() {
        val ptr = nativePtr
        if (ptr != 0L) {
            // Cleared FIRST, so that even if nativeDestroy throws, no later
            // call can be handed a pointer whose object is being torn down.
            nativePtr = 0
            nativeDestroy(ptr)
        }
    }

    /**
     * Failsafe only — and, today, the *sole* thing that ever frees the native
     * terminal: nothing in this library calls [close]. That makes the finalizer
     * race in [withPtr] the live one rather than a theoretical one, and it also
     * means the native terminal lives until the collector gets round to it.
     * Giving `TerminalEmulator` a real close is worth doing separately; this
     * class being safe when it happens is the part that belongs here.
     */
    @Suppress("unused")
    protected fun finalize() {
        close()
    }

    // Native method declarations
    private external fun nativeInit(callbacks: TerminalCallbacks, enableAltScreen: Boolean, xtversion: String?): Long
    private external fun nativeDestroy(ptr: Long): Int
    private external fun nativeWriteInputBuffer(ptr: Long, buffer: ByteBuffer, length: Int): Int
    private external fun nativeWriteInputArray(ptr: Long, data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeResize(ptr: Long, rows: Int, cols: Int): Int
    private external fun nativeDispatchKey(ptr: Long, modifiers: Int, key: Int): Boolean
    private external fun nativeDispatchCharacter(ptr: Long, modifiers: Int, character: Int): Boolean
    private external fun nativeGetCellRun(ptr: Long, row: Int, col: Int, run: CellRun): Int
    private external fun nativeSetPaletteColors(ptr: Long, colors: IntArray, count: Int): Int
    private external fun nativeSetDefaultColors(ptr: Long, fgColor: Int, bgColor: Int): Int
    private external fun nativeGetLineContinuation(ptr: Long, row: Int): Boolean
    private external fun nativeSetBoldHighbright(ptr: Long, enabled: Boolean): Int

    companion object {
        private const val LIBRARY_NAME = "jni_cb_term"

        init {
            loadNativeLibrary()
        }

        private fun loadNativeLibrary() {
            try {
                System.loadLibrary(LIBRARY_NAME)
            } catch (e: UnsatisfiedLinkError) {
                if (!e.message.orEmpty().contains("already loaded in another classloader")) {
                    throw e
                }

                loadCopiedNativeLibrary(e)
            }
        }

        private fun loadCopiedNativeLibrary(cause: UnsatisfiedLinkError) {
            val mappedName = System.mapLibraryName(LIBRARY_NAME)
            val source = System.getProperty("java.library.path")
                .orEmpty()
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, mappedName) }
                .firstOrNull { it.isFile }
                ?: throw cause

            val target = File.createTempFile("${LIBRARY_NAME}-", "-$mappedName")
            target.deleteOnExit()
            source.copyTo(target, overwrite = true)
            System.load(target.absolutePath)
        }
    }
}
