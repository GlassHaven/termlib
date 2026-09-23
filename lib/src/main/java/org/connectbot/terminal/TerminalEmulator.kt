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

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * URL discovered in terminal output.
 *
 * @property url The target URL.
 * @property source How the URL was discovered.
 */
data class TerminalUrl(
    val url: String,
    val source: TerminalUrlSource,
)

sealed class TerminalUrlSource {
    data object Osc8 : TerminalUrlSource()

    data object AutoDetected : TerminalUrlSource()
}

/**
 * Line range to scan for URLs.
 *
 * With the current terminal state model, both scopes scan primary scrollback
 * plus the visible primary screen while the primary screen is active. While the
 * alternate screen is active, both scopes scan only the alternate screen because
 * the alternate screen does not have scrollback.
 */
sealed class UrlScanScope {
    /**
     * Scan what users currently see as terminal output: primary scrollback plus
     * visible primary screen, or only the active alternate screen.
     */
    data object CurrentView : UrlScanScope()

    /**
     * Scan the currently active screen plus its scrollback, when that screen
     * has scrollback.
     */
    data object ScreenAndScrollback : UrlScanScope()
}

/**
 * Terminal emulator interface. This has no dependency on any UI framework
 * so it may be run in a Service on Android. It handles the management of
 * the terminal emulation state.
 */
sealed interface TerminalEmulator : AutoCloseable {
    /**
     * Release the native terminal.
     *
     * Until this existed the C++ terminal was freed only by a finalizer, which
     * is both non-deterministic — the memory stays held until the collector
     * gets round to it — and the mechanism behind #509's use-after-free, since
     * the collector will happily run while a native call is on the stack.
     *
     * Idempotent, safe from any thread, and safe to race: once closed, every
     * method on this interface becomes a no-op rather than throwing, because
     * the threads still calling in are transports delivering their last bytes
     * as the UI tears the screen down, and killing one of those is a worse
     * outcome than dropping output nobody will see.
     */
    override fun close()

    /**
     * Write data to the terminal (from PTY/transport).
     */
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    fun writeInput(buffer: ByteBuffer, length: Int)

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newCols: Int)

    /**
     * Whether a rows-only grow backfills the top rows by popping scrollback
     * (history returns into view, live content shifts down).
     *
     * Default true — the shell behaviour: the prompt stays pinned to the
     * bottom and restored history is visible. Sessions whose app repaints
     * the whole frame from its own model on WINCH (the UML guest console
     * runs opencode under a launcher that re-signals it) should set this
     * false: an incremental line-diff renderer (Ink) skips writing lines its
     * model believes unchanged, so a backfilling grow — which reflows
     * content down under the app — strands the popped scrollback in every
     * line the diff skips. With the backfill off, a grow anchors the screen
     * at the top with blank rows at the bottom and the app's own repaint
     * fills them.
     */
    var backfillScrollbackOnGrow: Boolean

    /**
     * Dispatch a key event to the terminal.
     */
    fun dispatchKey(modifiers: Int, key: Int)

    /**
     * Dispatch a character to the terminal.
     */
    @Deprecated(
        message = "Use dispatchCharacter(modifiers, codepoint) for full Unicode code point support",
        replaceWith = ReplaceWith("dispatchCharacter(modifiers, ch.code)"),
    )
    fun dispatchCharacter(modifiers: Int, ch: Char) = dispatchCharacter(modifiers, ch.code)

    /**
     * Dispatch a character to the terminal.
     */
    fun dispatchCharacter(modifiers: Int, codepoint: Int)

    /**
     * Clears the terminal emulator screen.
     */
    fun clearScreen()

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with new colors.
     *
     * @param ansiColors IntArray of ARGB colors (size 16 for all ANSI colors)
     * @return Number of colors set, or -1 on error
     */
    fun setAnsiPalette(ansiColors: IntArray): Int

    /**
     * Set default terminal colors.
     *
     * These colors are used when terminal content explicitly requests
     * "default" foreground or background (different from ANSI color 7/0).
     * Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    fun setDefaultColors(foreground: Int, background: Int): Int

    /**
     * Apply a complete color scheme to the terminal.
     *
     * Convenience method that sets both ANSI palette and default colors
     * from a color scheme. This is the recommended way to apply themes.
     *
     * @param ansiColors IntArray of 16 ARGB colors for ANSI palette
     * @param defaultForeground ARGB color for default foreground
     * @param defaultBackground ARGB color for default background
     */
    fun applyColorScheme(
        ansiColors: IntArray,
        defaultForeground: Int,
        defaultBackground: Int,
    )

    val dimensions: TerminalDimensions

    /**
     * Whether plain-text URL auto-detection is enabled.
     *
     * When true, hyperlink hit-testing continuously scans visible line text for URLs
     * in addition to OSC 8 hyperlink segments. When false, hit-testing only uses OSC 8
     * segments. This does not affect [getUrls], which always performs an explicit
     * one-shot scan when called.
     */
    val autoDetectUrls: Boolean

    /**
     * Whether bold text using low-intensity ANSI colors (0–7) promotes to the
     * corresponding bright palette color (8–15), matching xterm's boldColors behavior.
     */
    val boldAsBright: Boolean

    /**
     * Get the text output of the last completed command.
     *
     * Uses OSC 133 semantic segments to find the boundaries of the most recent
     * completed command output. Requires shell integration (OSC 133) to be
     * enabled in the user's shell.
     *
     * @return The command output text, or null if no completed command is found
     */
    fun getLastCommandOutput(): String?

    /**
     * Extract URLs from terminal output.
     *
     * This always performs explicit OSC 8 and plain-text regex URL extraction,
     * independent of [autoDetectUrls]. Plain-text URL extraction includes URLs
     * split across wrapped adjacent rows.
     *
     * Primary-screen scans include scrollback before visible screen lines.
     * While the alternate screen is active, primary scrollback is not scanned.
     */
    fun getUrls(scope: UrlScanScope = UrlScanScope.CurrentView): List<TerminalUrl>

    /**
     * Plain-text lines of the current terminal snapshot. Public accessor
     * so consumers outside this module (notably Haven's SelectionToolbar
     * for smart copy and word expansion) can read line text without
     * reflecting into the internal `snapshot` StateFlow. Returns a fresh
     * list at call time.
     */
    fun getSnapshotLineTexts(): List<String>

    /**
     * True while the emulator is actually rendering the alternate screen
     * buffer. Distinct from byte-stream scanners (Haven's MouseModeTracker)
     * that see `ESC[?1049h` go past: when the emulator was built with
     * `enableAltScreen = false` (profile opt-out, `screen` session manager)
     * the sequence has no effect and this stays false. Gesture routing must
     * trust this, not the scanner, when deciding swipe-to-arrow-keys (#255).
     */
    fun isAltScreenActive(): Boolean

    /**
     * Reactive terminal window title (empty when the running program has
     * never set one). Fed by OSC 0 / OSC 2 — libvterm surfaces both as
     * VTERM_PROP_TITLE through [TerminalCallbacks.setTermProp]. OSC 1 is
     * the icon name and intentionally does not update this. Consumers
     * (e.g. Haven's tab label, #625) collect this instead of intercepting
     * the OSC sequences themselves — stripping them from the byte stream
     * would starve the emulator's own title tracking, which backs
     * [AgentSnapshot.terminalTitle].
     */
    val terminalTitle: StateFlow<String>

    /**
     * Build a primitive-typed snapshot of the current terminal state for
     * agent / automation transports. The internal `TerminalSnapshot`
     * (and its `TerminalLine` / `SemanticSegment` collaborators) stay
     * `internal` to termlib; this view is the public, stringly-typed
     * surface meant for crossing module boundaries.
     *
     * Callers pass [includeSemanticSegments] = true when they need OSC
     * 133 prompt markers (PROMPT / COMMAND_INPUT / COMMAND_OUTPUT /
     * COMMAND_FINISHED / ANNOTATION) to drive prompt-aware features
     * such as tap-to-position. [maxLines] caps how many top rows of
     * `lines` are returned; the cursor position and dimensions are
     * always present regardless of that cap.
     */
    fun buildAgentSnapshot(
        includeSemanticSegments: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
    ): AgentSnapshot

    /**
     * Public entry point for the OSC 133 tap-to-position-cursor logic
     * (the helper used by Terminal.kt's gesture handler when
     * `tapToPositionCursorOnPrompt = true`). Cross-module callers
     * (agent / MCP transport) use this to script the same code path
     * without going through a touch event. Returns true when the tap
     * landed on an OSC 133 input line and arrow-key dispatches were
     * issued (or zero-delta no-op); false when no prompt was detected
     * at the tap row.
     */
    fun tapToPositionCursorOnPrompt(tapRow: Int, tapCol: Int): Boolean
}

/**
 * Primitive-typed view of [TerminalEmulator]'s state for cross-module
 * consumers (agent / MCP transport, accessibility services, tests
 * outside termlib). Mirrors fields from the internal `TerminalSnapshot`
 * but uses only public types so callers don't need access to termlib's
 * internal API.
 */
data class AgentSnapshot(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val terminalTitle: String,
    val scrollbackSize: Int,
    val lines: List<AgentLine>,
)

data class AgentLine(
    val text: String,
    val softWrapped: Boolean,
    val semanticSegments: List<AgentSemanticSegment>,
)

data class AgentSemanticSegment(
    val startCol: Int,
    val endCol: Int,
    /** One of: PROMPT, COMMAND_INPUT, COMMAND_OUTPUT, COMMAND_FINISHED, ANNOTATION. */
    val type: String,
    val promptId: Int,
    val metadata: String?,
)

class TerminalEmulatorFactory {
    companion object {
        /**
         * Creates the default implementation of TerminalEmulator.
         *
         * @param looper The Looper to use for callback handling (typically main looper)
         * @param initialRows Initial number of rows
         * @param initialCols Initial number of columns
         * @param defaultForeground Default foreground color
         * @param defaultBackground Default background color
         * @param onKeyboardInput Callback for keyboard output (to write to PTY)
         * @param onBell Optional callback for terminal bell
         * @param onResize Optional callback for terminal resize
         * @param onClipboardCopy Optional callback for OSC 52 clipboard copy operations.
         *                        The callback receives the decoded text to copy.
         * @param onProgressChange Optional callback for OSC 9;4 progress reporting.
         *                         The callback receives the progress state and percentage (0-100).
         * @param autoDetectUrls Whether to continuously scan visible terminal line text for
         *                       plain-text URLs and expose them via hit-testing as a fallback
         *                       when no OSC 8 hyperlink covers the column. Defaults to false.
         *                       [TerminalEmulator.getUrls] always performs its own one-shot
         *                       regex URL scan regardless of this setting.
         * @param boldAsBright Whether bold text using low-intensity ANSI colors (0–7) promotes to
         *                     the corresponding bright palette color (8–15), matching xterm's
         *                     default boldColors behavior. Defaults to true.
         * @param maxScrollbackLines Maximum number of lines kept in the Kotlin-side scrollback
         *                           ring before the oldest entries are evicted. Defaults to 1000;
         *                           hosts that want a longer history (or want to expose the limit
         *                           to users) can override.
         */
        fun create(
            looper: Looper = Looper.getMainLooper(),
            initialRows: Int = 24,
            initialCols: Int = 80,
            defaultForeground: Color = Color.White,
            defaultBackground: Color = Color.Black,
            onKeyboardInput: (ByteArray) -> Unit = {},
            onBell: (() -> Unit)? = null,
            onResize: ((TerminalDimensions) -> Unit)? = null,
            onClipboardCopy: ((String) -> Unit)? = null,
            onProgressChange: ((ProgressState, Int) -> Unit)? = null,
            enableAltScreen: Boolean = true,
            autoDetectUrls: Boolean = false,
            boldAsBright: Boolean = true,
            maxScrollbackLines: Int = 1000,
        ): TerminalEmulator = TerminalEmulatorImpl(
            looper = looper,
            initialRows = initialRows,
            initialCols = initialCols,
            defaultForeground = defaultForeground,
            defaultBackground = defaultBackground,
            onKeyboardInput = onKeyboardInput,
            onBell = onBell,
            onResize = onResize,
            onClipboardCopy = onClipboardCopy,
            onProgressChange = onProgressChange,
            enableAltScreen = enableAltScreen,
            autoDetectUrls = autoDetectUrls,
            boldAsBright = boldAsBright,
            maxScrollbackLines = maxScrollbackLines,
        )
    }
}

/**
 * Service-compatible terminal state manager.
 *
 * This class manages terminal state independently of the UI layer, making it
 * suitable for running in a background Android Service. It wraps the native
 * Terminal implementation and exposes state changes via StateFlow.
 *
 * Key features:
 * - No Compose dependencies (can run in background Service)
 * - Thread-safe callback handling with proper synchronization
 * - Snapshot-based state emission via StateFlow
 * - Accumulates damage and escapes native mutex before processing
 *
 * Threading model:
 * - JNI callbacks run on native thread and accumulate damage
 * - Handler posts to specified Looper to escape native mutex
 * - Snapshot building happens on Handler thread
 * - StateFlow emission is thread-safe
 *
 * @param looper The Looper to use for callback handling (typically main looper)
 * @param initialRows Initial number of rows
 * @param initialCols Initial number of columns
 * @param defaultForeground Default foreground color
 * @param defaultBackground Default background color
 * @param onKeyboardInput Callback for keyboard output (to write to PTY)
 * @param onBell Optional callback for terminal bell
 * @param onResize Optional callback for terminal resize
 * @param onClipboardCopy Optional callback for OSC 52 clipboard copy operations
 * @param onProgressChange Optional callback for OSC 9;4 progress reporting
 */
internal class TerminalEmulatorImpl(
    private val looper: Looper = Looper.getMainLooper(),
    initialRows: Int = 24,
    initialCols: Int = 80,
    defaultForeground: Color = Color.White,
    defaultBackground: Color = Color.Black,
    private val onKeyboardInput: (ByteArray) -> Unit = {},
    private val onBell: (() -> Unit)? = null,
    private val onResize: ((TerminalDimensions) -> Unit)? = null,
    private val onClipboardCopy: ((String) -> Unit)? = null,
    private val onProgressChange: ((ProgressState, Int) -> Unit)? = null,
    private val enableAltScreen: Boolean = true,
    override val autoDetectUrls: Boolean = false,
    override val boldAsBright: Boolean = true,
    maxScrollbackLines: Int = 1000,
) : TerminalEmulator,
    TerminalCallbacks {

    // Handler for escaping native mutex
    private val handler = Handler(looper)

    override var backfillScrollbackOnGrow: Boolean = true

    // Default colors (can be updated via setDefaultColors)
    private var currentDefaultForeground: Color = defaultForeground
    private var currentDefaultBackground: Color = defaultBackground

    // Damage accumulation (thread-safe) - MUST be initialized before terminalNative
    private val damageLock = Object()
    private val pendingDamageRegions = mutableListOf<DamageRegion>()
    private var damagePosted = false
    private var cursorMoved = false
    private var propertyChanged = false

    // Pending semantic segments to apply during processPendingUpdates
    private val pendingSemanticSegments = mutableListOf<PendingSemanticSegment>()
    private val movedSegmentRows = mutableSetOf<Int>()
    private val semanticSegmentTexts = mutableMapOf<SemanticSegmentKey, String>()

    // StateFlow for reactive state propagation
    private val _snapshot = MutableStateFlow(
        TerminalSnapshot.empty(initialRows, initialCols, currentDefaultForeground, currentDefaultBackground),
    )
    internal val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    override fun getSnapshotLineTexts(): List<String> = _snapshot.value.lines.map { it.text }

    // Snapshot-derived (not the raw altScreenActive var) so it is safe to
    // read from the UI thread; at most one frame stale, which is fine for
    // gesture routing.
    override fun isAltScreenActive(): Boolean = _snapshot.value.altScreen

    // Sequence number for ordering snapshots
    private var sequenceNumber = 0L

    // Terminal window title (VTERM_PROP_TITLE). Emitted independently of the
    // snapshot so title-only changes (#625) reach consumers without a full
    // screen recomposition.
    private val _terminalTitle = MutableStateFlow("")
    override val terminalTitle: StateFlow<String> = _terminalTitle.asStateFlow()

    // Terminal dimensions
    override val dimensions: TerminalDimensions
        get() = TerminalDimensions(rows = rows, columns = cols)

    private var rows = initialRows
    private var cols = initialCols

    // Cursor state
    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var cursorShape = CursorShape.BLOCK
    private var cursorBlink = false

    // True while the alternate screen buffer is active (DECSET 1049 — set by
    // full-screen TUIs like tmux/vim/less). Surfaced on the snapshot so the
    // Compose layer can size the PTY to the live keyboard-shrunk viewport for
    // these apps (they reflow on SIGWINCH and have no scrollback), while the
    // primary buffer keeps the keyboard-independent #206 sizing.
    private var altScreenActive = false

    // Terminal properties
    private var terminalTitleText = ""

    // Scrollback buffer
    private val scrollback = mutableListOf<TerminalLine>()
    private val maxScrollbackLines: Int = maxScrollbackLines.coerceAtLeast(0)

    // Cached immutable copy of scrollback - only recreate when scrollback changes
    private var scrollbackSnapshot: List<TerminalLine> = emptyList()
    private var scrollbackDirty = false

    // Reusable CellRun for fetching cell data
    private val cellRun = CellRun()

    // Current screen lines cache
    private var currentLines = List(initialRows) { row ->
        TerminalLine.empty(row, initialCols, currentDefaultForeground, currentDefaultBackground)
    }

    // Native terminal instance - MUST be initialized AFTER damageLock and other state
    //
    // The delegate is held by name so [close] can ask whether it was ever
    // initialized. Touching `terminalNative` to close it would otherwise
    // *construct* a native terminal for the sole purpose of destroying it,
    // on an emulator that was created and dropped without being used.
    private val terminalNativeDelegate = lazy {
        TerminalNative(this, enableAltScreen).apply {
            resize(initialRows, initialCols)
            if (setBoldHighbright(boldAsBright) != 0) {
                Log.e(TAG, "Failed to set boldAsBright=$boldAsBright")
            }
        }
    }
    private val terminalNative by terminalNativeDelegate

    /**
     * Set by [close]. Volatile because the threads that observe it are the
     * transports feeding this emulator, not the one that closes it.
     */
    @Volatile
    private var closed = false

    /**
     * Run [block] unless the terminal has been closed.
     *
     * The `closed` check handles the ordinary case. The catch handles the race
     * the check cannot: [close] landing between the check and the native call.
     * `TerminalNative` throws exactly one `IllegalStateException`, "Terminal has
     * been closed", so this is narrow rather than a blanket swallow — and the
     * alternative is that exception unwinding an SSH reader thread and taking
     * the process with it, which is precisely the class of failure this whole
     * change exists to remove.
     */
    private inline fun whenOpen(what: String, block: () -> Unit) {
        if (closed) return
        try {
            block()
        } catch (closedUnderneathUs: IllegalStateException) {
            Log.d(TAG, "$what after close, ignoring: ${closedUnderneathUs.message}")
        }
    }

    override fun close() {
        // Ordered: stop new work being accepted, then drop what is queued, then
        // free. Freeing first would leave posted callbacks to find a dead
        // pointer, which is the bug this method exists to prevent.
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (terminalNativeDelegate.isInitialized()) {
            terminalNative.close()
        }
        Log.d(TAG, "Terminal emulator closed")
    }

    // Parser for OSC sequences
    private val oscParser = OscParser()

    // ================================================================================
    // Public API
    // ================================================================================

    /**
     * Write data to the terminal (from PTY/transport).
     */
    override fun writeInput(data: ByteArray, offset: Int, length: Int) =
        whenOpen("writeInput") { terminalNative.writeInput(data, offset, length) }

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    override fun writeInput(buffer: ByteBuffer, length: Int) {
        whenOpen("writeInput") { terminalNative.writeInput(buffer, length) }
    }

    /**
     * Resize the terminal.
     */
    override fun resize(newRows: Int, newCols: Int) {
        val oldCols = cols
        val oldRows = rows
        rows = newRows
        cols = newCols
        whenOpen("resize") { terminalNative.resize(newRows, newCols) }
        // #272 diagnostics: correlate intermittent blanks with resizes.
        Log.d(TAG, "resize ${oldRows}x$oldCols -> ${newRows}x$newCols")

        // Capture current default colors (thread-safe)
        val currentDefaultFg: Color
        val currentDefaultBg: Color
        synchronized(damageLock) {
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
        }

        // Resize currentLines to match new dimensions, preserving semantic segments
        synchronized(damageLock) {
            val oldLines = currentLines
            currentLines = List(newRows) { row ->
                if (row < oldLines.size) {
                    if (newCols == oldCols) {
                        // Width unchanged (rows-only resize — height/keyboard/split changes):
                        // the old cells are still valid for this width, so keep the REAL
                        // content until invalidateDisplay()'s damage re-pull refreshes it.
                        // Blanking here let buildSnapshot() emit an all-empty screen in the
                        // resize→re-pull window — a source of the intermittent "terminal
                        // goes blank" (#272/#264 family). No regression: identical width =
                        // identical cell layout, and the re-pull overwrites it momentarily.
                        oldLines[row]
                    } else {
                        // Width changed (rotation): a reflow is needed, so keep only the
                        // semantic segments and let the re-pull rebuild the cells, as before.
                        TerminalLine.empty(row, newCols, currentDefaultFg, currentDefaultBg)
                            .copy(semanticSegments = oldLines[row].semanticSegments)
                    }
                } else {
                    TerminalLine.empty(row, newCols, currentDefaultFg, currentDefaultBg)
                }
            }
            if (newRows < oldLines.size) {
                for (row in newRows until oldLines.size) {
                    removeStoredSegmentTexts(row)
                }
            }
        }

        // Rebuild all lines after resize
        invalidateDisplay()

        // Resize callback - post to handler to avoid blocking native thread
        handler.post {
            onResize?.invoke(TerminalDimensions(rows = rows, columns = cols))
        }
    }

    /**
     * Dispatch a key event to the terminal.
     */
    override fun dispatchKey(modifiers: Int, key: Int) {
        whenOpen("dispatchKey") { terminalNative.dispatchKey(modifiers, key) }
    }

    /**
     * Dispatch a character to the terminal.
     */
    override fun dispatchCharacter(modifiers: Int, codepoint: Int) {
        whenOpen("dispatchCharacter") { terminalNative.dispatchCharacter(modifiers, codepoint) }
    }

    /**
     * Clears the terminal emulator screen.
     */
    override fun clearScreen() = writeInput("\u001B[2J\u001B[H".toByteArray())

    /**
     * Get the text output of the last completed command.
     */
    override fun getLastCommandOutput(): String? {
        val currentSnapshot = _snapshot.value
        val allLines = currentSnapshot.scrollback + currentSnapshot.lines
        return getLastCommandOutput(allLines)
    }

    override fun tapToPositionCursorOnPrompt(tapRow: Int, tapCol: Int): Boolean = dispatchTapToPositionCursor(this, _snapshot.value, tapRow, tapCol)

    override fun buildAgentSnapshot(
        includeSemanticSegments: Boolean,
        maxLines: Int,
    ): AgentSnapshot {
        val snap = _snapshot.value
        val cap = maxLines.coerceAtLeast(0)
        val take = if (cap == Int.MAX_VALUE) snap.lines.size else minOf(cap, snap.lines.size)
        val agentLines = snap.lines.take(take).map { line ->
            val segs = if (includeSemanticSegments) {
                line.semanticSegments.map { seg ->
                    AgentSemanticSegment(
                        startCol = seg.startCol,
                        endCol = seg.endCol,
                        type = seg.semanticType.name,
                        promptId = seg.promptId,
                        metadata = seg.metadata,
                    )
                }
            } else {
                emptyList()
            }
            AgentLine(
                text = line.text,
                softWrapped = line.softWrapped,
                semanticSegments = segs,
            )
        }
        return AgentSnapshot(
            rows = snap.rows,
            cols = snap.cols,
            cursorRow = snap.cursorRow,
            cursorCol = snap.cursorCol,
            cursorVisible = snap.cursorVisible,
            terminalTitle = snap.terminalTitle,
            scrollbackSize = snap.scrollback.size,
            lines = agentLines,
        )
    }

    override fun getUrls(scope: UrlScanScope): List<TerminalUrl> {
        val currentSnapshot = _snapshot.value
        val altScreen = synchronized(damageLock) {
            altScreenActive
        }
        return extractUrls(currentSnapshot.linesForUrlScan(scope, altScreen))
    }

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with new colors.
     *
     * @param ansiColors IntArray of ARGB colors (size 16 for all ANSI colors)
     * @return Number of colors set, or -1 on error
     */
    override fun setAnsiPalette(ansiColors: IntArray): Int {
        require(ansiColors.size >= 16) {
            "ANSI palette must contain 16 colors"
        }
        // -1 is this API's existing "did not happen" value, so a closed
        // terminal reports the same thing a failed call always did.
        var result = -1
        whenOpen("setAnsiPalette") { result = terminalNative.setPaletteColors(ansiColors, 16) }
        invalidateDisplay()
        return result
    }

    /**
     * Set default terminal colors.
     *
     * These colors are used when terminal content explicitly requests
     * "default" foreground or background (different from ANSI color 7/0).
     * Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    override fun setDefaultColors(foreground: Int, background: Int): Int {
        synchronized(damageLock) {
            currentDefaultForeground = Color(foreground)
            currentDefaultBackground = Color(background)
        }
        var result = -1
        whenOpen("setDefaultColors") { result = terminalNative.setDefaultColors(foreground, background) }
        invalidateDisplay()
        return result
    }

    /**
     * Apply a complete color scheme to the terminal.
     *
     * Convenience method that sets both ANSI palette and default colors
     * from a color scheme. This is the recommended way to apply themes.
     *
     * @param ansiColors IntArray of 16 ARGB colors for ANSI palette
     * @param defaultForeground ARGB color for default foreground
     * @param defaultBackground ARGB color for default background
     */
    override fun applyColorScheme(
        ansiColors: IntArray,
        defaultForeground: Int,
        defaultBackground: Int,
    ) {
        require(ansiColors.size >= 16) {
            "Color scheme must provide 16 ANSI colors"
        }

        setAnsiPalette(ansiColors)
        setDefaultColors(defaultForeground, defaultBackground)
    }

    // ================================================================================
    // TerminalCallbacks implementation
    // ================================================================================

    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int {
        synchronized(damageLock) {
            addDamageRegion(startRow, endRow, startCol, endCol)
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    // Track the last moverect source region so pushScrollbackLine knows
    // whether it was a full-screen or partial scroll region scroll.
    private var lastMoveRectSrc: TermRect? = null

    override fun moverect(dest: TermRect, src: TermRect): Int {
        // Save source rect — pushScrollbackLine uses it to limit segment shifting
        // to lines within the scroll region (avoiding corruption of tmux status bars etc.)
        lastMoveRectSrc = src
        // Treat moverect as display damage on the destination. Semantic segments
        // are shifted alongside the moved text elsewhere, so preserve them here.
        synchronized(damageLock) {
            for (row in dest.startRow until dest.endRow) {
                movedSegmentRows.add(row)
            }
            addDamageRegion(dest.startRow, dest.endRow, dest.startCol, dest.endCol, preserveSegments = true)
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
        synchronized(damageLock) {
            cursorRow = pos.row
            cursorCol = pos.col
            cursorVisible = visible
            cursorMoved = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun setTermProp(prop: Int, value: TerminalProperty): Int {
        synchronized(damageLock) {
            when (value) {
                is TerminalProperty.StringValue -> {
                    // Property 4 is VTERM_PROP_TITLE (vterm.h:257; 7 is MOUSE)
                    if (prop == 4) {
                        terminalTitleText = value.value
                        _terminalTitle.value = value.value
                        propertyChanged = true
                    }
                }

                is TerminalProperty.BoolValue -> {
                    when (prop) {
                        // Property 1 is VTERM_PROP_CURSORVISIBLE (from vterm.h line 254)
                        1 -> {
                            cursorVisible = value.value
                            propertyChanged = true
                        }

                        // Property 2 is VTERM_PROP_CURSORBLINK (from vterm.h line 255)
                        2 -> {
                            cursorBlink = value.value
                            propertyChanged = true
                        }

                        // Property 3 is VTERM_PROP_ALTSCREEN. On the
                        // transition OFF (alt-screen → primary), force
                        // a full screen redamage so the renderer re-
                        // pulls every cell from libvterm — otherwise we
                        // sometimes inherit stale cached lines from
                        // before alt-screen activated, surfacing as
                        // "garbage in scrollback after nano exit"
                        // (#120). The transition ON path doesn't need
                        // it — alt-screen activation always paints a
                        // fresh buffer.
                        3 -> {
                            altScreenActive = value.value
                            propertyChanged = true
                            if (!value.value) {
                                pendingDamageRegions.clear()
                                addDamageRegion(0, rows, 0, cols)
                            }
                        }
                    }
                }

                is TerminalProperty.IntValue -> {
                    // Property 6 is VTERM_PROP_CURSORSHAPE (from vterm.h line 260)
                    if (prop == 6) {
                        cursorShape = when (value.value) {
                            1 -> CursorShape.BLOCK

                            // VTERM_PROP_CURSORSHAPE_BLOCK
                            2 -> CursorShape.UNDERLINE

                            // VTERM_PROP_CURSORSHAPE_UNDERLINE
                            3 -> CursorShape.BAR_LEFT

                            // VTERM_PROP_CURSORSHAPE_BAR_LEFT
                            else -> CursorShape.BLOCK
                        }
                        propertyChanged = true
                    }
                }

                else -> {
                    // Other properties not handled
                }
            }
            if (propertyChanged) {
                requestProcessPendingUpdatesLocked()
            }
        }
        return 0
    }

    override fun bell(): Int {
        // Bell callback - post to handler to avoid blocking native thread
        handler.post {
            onBell?.invoke()
        }
        return 0
    }

    override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>, softWrapped: Boolean): Int {
        // Convert ScreenCell array to TerminalLine
        val cellList = cells.take(cols).map { screenCell ->
            TerminalLine.Cell(
                char = screenCell.char,
                combiningChars = screenCell.combiningChars.filter { it != '\u0000' },
                fgColor = Color(screenCell.fgRed, screenCell.fgGreen, screenCell.fgBlue),
                bgColor = Color(screenCell.bgRed, screenCell.bgGreen, screenCell.bgBlue),
                bold = screenCell.bold,
                italic = screenCell.italic,
                underline = screenCell.underline,
                reverse = screenCell.reverse,
                strike = screenCell.strike,
                width = screenCell.width,
            )
        }

        synchronized(damageLock) {
            // FIRST: Preserve semantic segments from line 0 (the line being scrolled out)
            // This must happen BEFORE we shift segments, since moverect was already called
            val line0Segments = if (currentLines.isNotEmpty()) {
                currentLines[0].semanticSegments
            } else {
                emptyList()
            }

            val line = TerminalLine(row = -1, cells = cellList, softWrapped = softWrapped, semanticSegments = line0Segments)

            scrollback.add(line)
            if (scrollback.size > maxScrollbackLines) {
                scrollback.removeAt(0)
            }
            scrollbackDirty = true

            // Shift semantic segments up within the scroll region only.
            // Lines outside the region (e.g. tmux status bar) keep their segments.
            val moveRect = lastMoveRectSrc
            lastMoveRectSrc = null
            if (currentLines.size > 1) {
                val shiftEnd = if (moveRect != null) {
                    // Partial scroll region: only shift within the region
                    moveRect.endRow.coerceAtMost(currentLines.size)
                } else {
                    // Full-screen scroll
                    currentLines.size
                }
                val newLines = currentLines.toMutableList()
                for (row in 0 until shiftEnd - 1) {
                    shiftStoredSegmentTexts(fromRow = row + 1, toRow = row)
                    newLines[row] = currentLines[row + 1].copy(row = row)
                }
                // Clear segments for the last line in the scroll region
                if (shiftEnd > 0 && shiftEnd <= currentLines.size) {
                    removeStoredSegmentTexts(shiftEnd - 1)
                    newLines[shiftEnd - 1] = currentLines[shiftEnd - 1].copy(
                        semanticSegments = emptyList(),
                    )
                }
                currentLines = newLines
            }

            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun clearScrollback(): Int {
        synchronized(damageLock) {
            scrollback.clear()
            scrollbackDirty = true
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
        synchronized(damageLock) {
            if (scrollback.isEmpty()) return 0
            if (!backfillScrollbackOnGrow) return 0

            var line = scrollback.removeAt(scrollback.size - 1)
            scrollbackDirty = true

            // #478: a pop at a width narrower than the stored content used to
            // hand vterm the first `cols` cells and drop the line — rotating
            // landscape→portrait destroyed every wide line's tail as the row
            // growth popped 80-col lines onto a 40-col screen. vterm pops
            // bottom-up, so deliver the LAST row's worth of content and
            // re-store the head soft-wrapped: the next pop takes the head,
            // and the line renders wrapped instead of ceasing to exist.
            // (Content width ignores trailing blanks — every pushed line is
            // padded to the old grid width; a styled blank tail past the last
            // glyph is the one thing this trim can drop. A double-width char
            // straddling the split renders mangled at the seam, not lost.)
            val contentWidth = line.cells.indexOfLast {
                it.char != ' ' || it.combiningChars.isNotEmpty()
            } + 1
            if (contentWidth > cols) {
                val split = ((contentWidth - 1) / cols) * cols
                scrollback.add(
                    line.copy(cells = line.cells.take(split), softWrapped = true),
                )
                line = line.copy(cells = line.cells.subList(split, contentWidth).toList())
            }

            // Convert TerminalLine.Cell back to ScreenCell (reverse of pushScrollbackLine)
            for (i in 0 until minOf(cols, cells.size)) {
                val cell = line.cells.getOrNull(i)
                if (cell != null) {
                    cells[i] = ScreenCell(
                        char = cell.char,
                        combiningChars = cell.combiningChars,
                        fgRed = (cell.fgColor.red * 255).toInt(),
                        fgGreen = (cell.fgColor.green * 255).toInt(),
                        fgBlue = (cell.fgColor.blue * 255).toInt(),
                        bgRed = (cell.bgColor.red * 255).toInt(),
                        bgGreen = (cell.bgColor.green * 255).toInt(),
                        bgBlue = (cell.bgColor.blue * 255).toInt(),
                        bold = cell.bold,
                        italic = cell.italic,
                        underline = cell.underline,
                        reverse = cell.reverse,
                        strike = cell.strike,
                        width = cell.width,
                    )
                } else {
                    // Fill remaining columns with empty cells using current defaults
                    cells[i] = ScreenCell(
                        char = ' ',
                        fgRed = (currentDefaultForeground.red * 255).toInt(),
                        fgGreen = (currentDefaultForeground.green * 255).toInt(),
                        fgBlue = (currentDefaultForeground.blue * 255).toInt(),
                        bgRed = (currentDefaultBackground.red * 255).toInt(),
                        bgGreen = (currentDefaultBackground.green * 255).toInt(),
                        bgBlue = (currentDefaultBackground.blue * 255).toInt(),
                    )
                }
            }

            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 1
    }

    override fun onKeyboardInput(data: ByteArray): Int {
        // Keyboard output callback - post to handler
        handler.post {
            onKeyboardInput.invoke(data)
        }
        return 0
    }

    override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int): Int {
        // Use the native cursor position from libvterm for OSC sequence processing
        val actions = synchronized(damageLock) {
            oscParser.parse(command, payload, cursorRow, cursorCol, cols)
        }

        synchronized(damageLock) {
            for (action in actions) {
                when (action) {
                    is OscParser.Action.AddSegment -> {
                        addSemanticSegment(
                            action.row,
                            action.startCol,
                            action.endCol,
                            action.type,
                            action.metadata,
                            action.promptId,
                        )
                    }

                    is OscParser.Action.SetCursorShape -> {
                        cursorShape = action.shape
                        propertyChanged = true
                        requestProcessPendingUpdatesLocked()
                    }

                    is OscParser.Action.ClipboardCopy -> {
                        // Post clipboard copy to handler thread to avoid blocking native callback
                        handler.post {
                            onClipboardCopy?.invoke(action.data)
                        }
                    }

                    is OscParser.Action.SetProgress -> {
                        // Post progress change to handler thread to avoid blocking native callback
                        handler.post {
                            onProgressChange?.invoke(action.state, action.progress)
                        }
                    }
                }
            }
        }
        return 1
    }

    /**
     * Apply a semantic segment immediately to the current line.
     * Segments are applied immediately so they can be properly shifted during scroll.
     */
    private fun addSemanticSegment(
        row: Int,
        startCol: Int,
        endCol: Int,
        semanticType: SemanticType,
        metadata: String?,
        promptId: Int,
    ) {
        synchronized(damageLock) {
            // Apply immediately to currentLines so segments are shifted correctly during scroll
            if (row < 0 || row >= currentLines.size) {
                return
            }

            val line = currentLines[row]
            val newSegment = SemanticSegment(
                startCol = startCol,
                endCol = endCol,
                semanticType = semanticType,
                metadata = metadata,
                promptId = promptId,
            )

            val updatedSegments = (line.semanticSegments + newSegment).sortedBy { it.startCol }
            currentLines = currentLines.toMutableList().apply {
                this[row] = line.copy(semanticSegments = updatedSegments)
            }
            pendingSemanticSegments.add(
                PendingSemanticSegment(
                    row = row,
                    startCol = startCol,
                    endCol = endCol,
                    semanticType = semanticType,
                    metadata = metadata,
                    promptId = promptId,
                ),
            )

            // Mark for update so processPendingUpdates runs
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
    }

    // ================================================================================
    // Internal snapshot building
    // ================================================================================

    /**
     * Process pending updates and emit new snapshot.
     * This runs on the Handler thread, NOT in the JNI callback.
     */
    @VisibleForTesting
    fun processPendingUpdates() {
        // A frame callback posted before close() still fires: Choreographer
        // callbacks are not in the Handler queue that close() drains, and no
        // reference is kept to remove them with. The rebuild below reads cells
        // out of the native terminal, so this guard is what stops it reading a
        // terminal that has been freed.
        whenOpen("processPendingUpdates") { processPendingUpdatesInner() }
    }

    private fun processPendingUpdatesInner() {
        // Collect pending changes
        val damageRegions: List<DamageRegion>
        val needsUpdate: Boolean
        val movedRows: Set<Int>
        synchronized(damageLock) {
            damageRegions = pendingDamageRegions.toList()
            pendingDamageRegions.clear()
            movedRows = movedSegmentRows.toSet()
            movedSegmentRows.clear()
            damagePosted = false
            needsUpdate = damageRegions.isNotEmpty() || cursorMoved || propertyChanged
            cursorMoved = false
            propertyChanged = false
        }

        if (!needsUpdate) return

        // Update damaged lines (safe to call getCellRun now - not in callback)
        for (region in damageRegions) {
            // Ensure row is within bounds [0, rows)
            val startRow = region.startRow.coerceIn(0, rows - 1)
            val endRow = region.endRow.coerceIn(startRow, rows) // endRow is exclusive
            for (row in startRow until endRow) {
                updateLine(row, region, preserveMovedSegments = row in movedRows)
            }
        }

        // Apply pending semantic segments now that text content is available
        val segmentsToApply: List<PendingSemanticSegment>
        synchronized(damageLock) {
            segmentsToApply = pendingSemanticSegments.toList()
            pendingSemanticSegments.clear()
        }

        for (segment in segmentsToApply) {
            applySemanticSegment(segment)
        }

        // Build and emit new snapshot
        val newSnapshot = buildSnapshot()
        _snapshot.value = newSnapshot
    }

    /**
     * Apply a semantic segment to a line.
     * This is called during processPendingUpdates when the actual text is available.
     */
    private fun applySemanticSegment(segment: PendingSemanticSegment) = synchronized(damageLock) {
        val row = segment.row

        // Ensure row is valid
        if (row < 0 || row >= currentLines.size) {
            return@synchronized
        }

        val line = currentLines[row]
        if (segment.semanticType == SemanticType.HYPERLINK) {
            if (!isValidCellRange(segment.startCol, segment.endCol, line.cells.size)) return@synchronized
            val linkedText = cellText(line.cells, segment.startCol, segment.endCol)
            if (linkedText.all { it == '\u0000' || it.isWhitespace() }) return@synchronized
        }

        // Create new segment
        val newSegment = SemanticSegment(
            startCol = segment.startCol,
            endCol = segment.endCol,
            semanticType = segment.semanticType,
            metadata = segment.metadata,
            promptId = segment.promptId,
        )

        if (newSegment in line.semanticSegments) {
            storeSegmentText(row, newSegment, line)
            return@synchronized
        }

        // Add to existing segments (sorted by startCol)
        val updatedSegments = (line.semanticSegments + newSegment)
            .sortedBy { it.startCol }

        // Update the line with new segments
        currentLines = currentLines.toMutableList().apply {
            this[row] = line.copy(semanticSegments = updatedSegments)
        }
        storeSegmentText(row, newSegment, line)
    }

    /**
     * Update a single line by fetching cell data from the terminal.
     */
    private fun updateLine(row: Int, damageRegion: DamageRegion, preserveMovedSegments: Boolean) {
        // Safety check: ensure row is within bounds
        if (row !in 0..<rows) {
            return
        }

        // Capture current default colors (thread-safe)
        val currentDefaultFg: Color
        val currentDefaultBg: Color
        synchronized(damageLock) {
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
        }

        val cells = ArrayList<TerminalLine.Cell>(cols)
        var col = 0

        while (col < cols) {
            cellRun.reset()
            val runLength = terminalNative.getCellRun(row, col, cellRun)

            if (runLength <= 0) {
                // Fill remaining with empty cells
                while (col < cols) {
                    cells.add(
                        TerminalLine.Cell(
                            char = ' ',
                            fgColor = currentDefaultFg,
                            bgColor = currentDefaultBg,
                        ),
                    )
                    col++
                }
                break
            }

            // Convert CellRun colors to Compose Color
            val fgColor = Color(cellRun.fgRed, cellRun.fgGreen, cellRun.fgBlue)
            val bgColor = Color(cellRun.bgRed, cellRun.bgGreen, cellRun.bgBlue)

            // Process characters in the run
            var charIndex = 0
            var cellsInRun = 0

            while (charIndex < cellRun.chars.size && cellsInRun < runLength) {
                val char = cellRun.chars[charIndex]
                if (char == 0.toChar()) break

                var combiningChars: MutableList<Char>? = null
                charIndex++

                // Handle surrogate pairs (characters > U+FFFF like emoji)
                if (char.isHighSurrogate() && charIndex < cellRun.chars.size) {
                    val nextChar = cellRun.chars[charIndex]
                    if (nextChar.isLowSurrogate()) {
                        combiningChars = mutableListOf(nextChar)
                        charIndex++
                    }
                }

                // Collect combining characters
                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    if (combiningChars == null) {
                        combiningChars = mutableListOf()
                    }
                    combiningChars.add(cellRun.chars[charIndex])
                    charIndex++
                }

                // Determine cell width
                val extraChars = combiningChars ?: TerminalLine.EMPTY_COMBINING_CHARS
                val width = if (extraChars.isNotEmpty() && extraChars[0].isLowSurrogate()) {
                    val codepoint = Character.toCodePoint(char, extraChars[0])
                    if (isFullwidthCodepoint(codepoint)) 2 else 1
                } else {
                    if (isFullwidthCharacter(char)) 2 else 1
                }

                cells.add(
                    TerminalLine.Cell(
                        char = char,
                        combiningChars = extraChars,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        bold = cellRun.bold,
                        italic = cellRun.italic,
                        underline = cellRun.underline,
                        blink = cellRun.blink,
                        reverse = cellRun.reverse,
                        strike = cellRun.strike,
                        width = width,
                    ),
                )

                cellsInRun++
                if (width == 2) {
                    cellsInRun++
                }
            }

            col += cellsInRun
        }

        // Check if this line is soft-wrapped (the next line is a continuation).
        // A line is soft-wrapped if the next row has continuation=true.
        val softWrapped = if (row + 1 < rows) {
            terminalNative.getLineContinuation(row + 1)
        } else {
            // Last visible row - we can't know if it's wrapped until it scrolls
            false
        }

        // Update cached line, preserving existing semantic segments only when they
        // were not touched by terminal text damage. This prevents stale OSC 8
        // links from surviving line redraws while allowing display-only
        // invalidations, such as palette changes, to keep semantic metadata.
        // Must synchronize to ensure visibility of segments added by addSemanticSegment
        synchronized(damageLock) {
            currentLines = currentLines.toMutableList().apply {
                val previousLine = this[row]
                val existingSegments = previousLine.semanticSegments
                val preservedSegments = if (damageRegion.preserveSegments || preserveMovedSegments) {
                    existingSegments.filter { segment ->
                        segment.endCol <= cells.size &&
                            (!preserveMovedSegments || segmentTextStillMatches(row, segment, cells))
                    }
                } else {
                    existingSegments.filter { segment ->
                        segment.endCol <= cells.size &&
                            !segment.overlaps(damageRegion.startCol, damageRegion.endCol)
                    }
                }
                replaceStoredSegmentTexts(row, preservedSegments)
                this[row] = TerminalLine(row, cells, softWrapped = softWrapped, semanticSegments = preservedSegments)
            }
        }
    }

    /**
     * Build a complete snapshot of terminal state.
     */
    private fun buildSnapshot(): TerminalSnapshot {
        // Read all mutable state under damageLock to ensure cross-thread visibility.
        // addSemanticSegment writes currentLines on the JNI callback thread; without
        // the lock here, the snapshot-building thread might see a stale reference.
        val lines: List<TerminalLine>
        val scrollbackCopy: List<TerminalLine>
        synchronized(damageLock) {
            if (scrollbackDirty) {
                scrollbackSnapshot = scrollback.toList()
                scrollbackDirty = false
            }
            lines = currentLines.toList() // Immutable copy (24 references)
            scrollbackCopy = scrollbackSnapshot // Reuse cached immutable copy
        }

        return TerminalSnapshot(
            lines = lines,
            scrollback = scrollbackCopy,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            cursorShape = cursorShape,
            cursorBlink = cursorBlink,
            terminalTitle = terminalTitleText,
            rows = rows,
            cols = cols,
            altScreen = altScreenActive,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = sequenceNumber++,
        )
    }

    private fun TerminalSnapshot.linesForUrlScan(
        scope: UrlScanScope,
        altScreenActive: Boolean,
    ): List<TerminalLine> {
        if (altScreenActive) return lines
        return when (scope) {
            UrlScanScope.CurrentView -> scrollback + lines
            UrlScanScope.ScreenAndScrollback -> scrollback + lines
        }
    }

    private fun extractUrls(lines: List<TerminalLine>): List<TerminalUrl> {
        val seenUrls = linkedSetOf<String>()
        val urls = mutableListOf<TerminalUrl>()

        lines.forEachIndexed { row, line ->
            for (url in extractUrls(lines, row, line)) {
                if (seenUrls.add(url.url)) {
                    urls.add(url)
                }
            }
        }

        return urls
    }

    private fun extractUrls(lines: List<TerminalLine>, row: Int, line: TerminalLine): List<TerminalUrl> {
        val osc8Segments = line.getSegmentsOfType(SemanticType.HYPERLINK)
        val osc8Urls = osc8Segments
            .mapNotNull { segment ->
                val url = segment.metadata
                if (url.isNullOrEmpty() || segment.startCol < 0 || segment.endCol <= segment.startCol) {
                    null
                } else {
                    segment.startCol to TerminalUrl(
                        url = url,
                        source = TerminalUrlSource.Osc8,
                    )
                }
            }

        val autoDetectedUrls = line.autoDetectedUrls
            .mapNotNull { (startCol, endCol, url) ->
                val spans = buildWrappedUrlSpans(lines, row, startCol)
                val detectedUrl = readWrappedUrl(lines, spans) ?: url
                val firstSpan = spans[row] ?: (startCol until endCol)
                if (spansOverlapOsc8(lines, spans.ifEmpty { mapOf(row to firstSpan) })) {
                    null
                } else {
                    startCol to TerminalUrl(
                        url = detectedUrl,
                        source = TerminalUrlSource.AutoDetected,
                    )
                }
            }

        return (osc8Urls + autoDetectedUrls).sortedWith(
            compareBy<Pair<Int, TerminalUrl>> { it.first }
                .thenBy { it.second.source.priority },
        ).map { it.second }
    }

    private val TerminalUrlSource.priority: Int
        get() = when (this) {
            TerminalUrlSource.Osc8 -> 0
            TerminalUrlSource.AutoDetected -> 1
        }

    private fun buildWrappedUrlSpans(lines: List<TerminalLine>, anchorRow: Int, anchorCol: Int): Map<Int, IntRange> {
        val spans = linkedMapOf<Int, IntRange>()
        var row = anchorRow
        var startCol = anchorCol

        while (row < lines.size && (row - anchorRow) < MAX_URL_SCAN_CONTINUATION_ROWS) {
            val text = lines[row].columnText
            if (startCol !in text.indices || !text[startCol].isUrlSafe()) break

            var endCol = startCol
            while (endCol < text.length && text[endCol].isUrlSafe()) {
                endCol++
            }

            val nextStart = continuationStart(lines, row, endCol)
            if (nextStart != null) {
                spans[row] = startCol until endCol
                row++
                startCol = nextStart
            } else {
                val trimmed = text.substring(startCol, endCol).trimDetectedUrl()
                if (trimmed.isNotEmpty()) {
                    spans[row] = startCol until (startCol + trimmed.length)
                }
                break
            }
        }

        return spans
    }

    private fun continuationStart(lines: List<TerminalLine>, previousRow: Int, previousEndCol: Int): Int? {
        if (previousRow + 1 >= lines.size) return null

        val previousLine = lines[previousRow]
        val previousText = previousLine.columnText
        val previousTrimmedEnd = previousText.indexOfLast { it != '\u0000' && !it.isWhitespace() } + 1
        if (previousTrimmedEnd <= 0 || previousEndCol < previousTrimmedEnd) return null

        val rowFilled = previousEndCol >= previousLine.cells.size || previousLine.softWrapped
        val nextText = lines[previousRow + 1].columnText
        val start = firstUrlSafeAfterPrefix(nextText) ?: return null

        var end = start
        while (end < nextText.length && nextText[end].isUrlSafe()) {
            end++
        }
        val run = nextText.substring(start, end)
        if (run.trimDetectedUrl().isEmpty()) return null

        val prevEndsWithDelimiter = previousEndCol > 0 && previousText[previousEndCol - 1] in "/?&=#"
        val nextStartsWithQueryOrFragment = run.isNotEmpty() && run[0] in "?&#"
        val previousRun = previousText.substring(0, previousEndCol)
        val previousWouldTrim = previousRun.trimDetectedUrl().length < previousRun.length
        val rowFilledContinuation = rowFilled &&
            (!previousWouldTrim || prevEndsWithDelimiter || nextStartsWithQueryOrFragment)
        val continues = rowFilledContinuation || prevEndsWithDelimiter || nextStartsWithQueryOrFragment

        return if (continues) start else null
    }

    private fun firstUrlSafeAfterPrefix(text: String): Int? {
        for (index in text.indices) {
            val ch = text[index]
            if (ch == '\u0000' || ch.isWhitespace()) continue
            if (ch.isUrlPrefixDecoration()) continue
            return if (ch.isUrlSafe()) index else null
        }
        return null
    }

    private fun readWrappedUrl(lines: List<TerminalLine>, spans: Map<Int, IntRange>): String? {
        if (spans.isEmpty()) return null
        val joined = buildString {
            for ((spanRow, span) in spans.toSortedMap()) {
                val text = lines[spanRow].columnText
                val end = (span.last + 1).coerceAtMost(text.length)
                if (span.first < end) append(text.substring(span.first, end))
            }
        }
        return TerminalLine.URL_REGEX.findAll(joined).firstOrNull()?.value?.trimDetectedUrl()?.takeIf { it.isNotEmpty() }
    }

    private fun spansOverlapOsc8(lines: List<TerminalLine>, spans: Map<Int, IntRange>): Boolean = spans.any { (row, span) ->
        val endCol = span.last + 1
        lines.getOrNull(row)?.getSegmentsOfType(SemanticType.HYPERLINK)?.any {
            it.startCol < endCol && span.first < it.endCol
        } == true
    }

    // ================================================================================
    // Helper methods
    // ================================================================================

    /**
     * Trigger a full display redraw.
     * Used when global display properties change (colors, etc.).
     */
    private fun invalidateDisplay() {
        synchronized(damageLock) {
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, rows, 0, cols, preserveSegments = true))
            requestProcessPendingUpdatesLocked()
        }
    }

    /**
     * Schedule snapshot work at display-frame cadence.
     *
     * libvterm can report many small damage/cursor callbacks while a single PTY read is
     * processed. Running updateLine/buildSnapshot for every callback burst can outpace
     * vsync and make Compose redraw the terminal multiple times for one displayed frame.
     *
     * MUST be called with damageLock held.
     */
    private fun requestProcessPendingUpdatesLocked() {
        if (damagePosted) return
        damagePosted = true
        if (looper == Looper.getMainLooper()) {
            handler.post {
                Choreographer.getInstance().postFrameCallback {
                    processPendingUpdates()
                }
            }
        } else {
            handler.post {
                processPendingUpdates()
            }
        }
    }

    /**
     * Add a damage region, coalescing with existing regions where possible.
     *
     * This prevents unbounded growth of damage regions during rapid updates
     * (like cacafire). Regions are coalesced if they overlap or touch on row boundaries.
     *
     * MUST be called with damageLock held.
     */
    private fun addDamageRegion(
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int,
        preserveSegments: Boolean = false,
    ) {
        // If list is getting large, coalesce more aggressively
        if (pendingDamageRegions.size > 100) {
            // Just mark entire screen as damaged to avoid O(n²) coalescing
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, rows, 0, cols, preserveSegments = preserveSegments))
            return
        }

        // Try to merge with existing regions
        var merged = false
        for (i in pendingDamageRegions.indices) {
            val existing = pendingDamageRegions[i]

            // Check if regions overlap or touch on row boundaries
            val rowsOverlap = !(endRow < existing.startRow || startRow > existing.endRow)

            if (rowsOverlap && existing.preserveSegments == preserveSegments) {
                // Merge the regions
                val newStartRow = minOf(startRow, existing.startRow)
                val newEndRow = maxOf(endRow, existing.endRow)
                val newStartCol = minOf(startCol, existing.startCol)
                val newEndCol = maxOf(endCol, existing.endCol)

                pendingDamageRegions[i] = DamageRegion(
                    newStartRow,
                    newEndRow,
                    newStartCol,
                    newEndCol,
                    preserveSegments = preserveSegments,
                )
                merged = true
                break
            }
        }

        if (!merged) {
            pendingDamageRegions.add(DamageRegion(startRow, endRow, startCol, endCol, preserveSegments = preserveSegments))
        }
    }

    private fun isCombiningCharacter(char: Char): Boolean = UCharacter.hasBinaryProperty(char.code, UProperty.GRAPHEME_EXTEND)

    private fun isFullwidthCharacter(char: Char): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(char.code, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
            eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    private fun isFullwidthCodepoint(codepoint: Int): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(codepoint, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
            eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    private fun storeSegmentText(row: Int, segment: SemanticSegment, line: TerminalLine) {
        synchronized(damageLock) {
            if (!isValidCellRange(segment.startCol, segment.endCol, line.cells.size)) return@synchronized
            semanticSegmentTexts[SemanticSegmentKey(row, segment)] = cellText(line.cells, segment.startCol, segment.endCol)
        }
    }

    private fun segmentTextStillMatches(row: Int, segment: SemanticSegment, cells: List<TerminalLine.Cell>): Boolean {
        return synchronized(damageLock) {
            val expected = semanticSegmentTexts[SemanticSegmentKey(row, segment)] ?: return@synchronized true
            if (!isValidCellRange(segment.startCol, segment.endCol, cells.size)) return@synchronized false
            val actual = cellText(cells, segment.startCol, segment.endCol)
            actual == expected
        }
    }

    private fun isValidCellRange(startCol: Int, endCol: Int, cellCount: Int): Boolean = startCol >= 0 && endCol >= startCol && endCol <= cellCount

    private fun cellText(cells: List<TerminalLine.Cell>, startCol: Int, endCol: Int): String = buildString {
        for (col in startCol until endCol) {
            append(cells[col].char)
            cells[col].combiningChars.forEach { append(it) }
        }
    }

    private fun replaceStoredSegmentTexts(row: Int, segments: List<SemanticSegment>) {
        synchronized(damageLock) {
            val keep = segments.mapTo(mutableSetOf()) { SemanticSegmentKey(row, it) }
            semanticSegmentTexts.keys.removeAll { it.row == row && it !in keep }
        }
    }

    private fun removeStoredSegmentTexts(row: Int) {
        synchronized(damageLock) {
            semanticSegmentTexts.keys.removeAll { it.row == row }
        }
    }

    private fun shiftStoredSegmentTexts(fromRow: Int, toRow: Int) {
        synchronized(damageLock) {
            removeStoredSegmentTexts(toRow)
            val moved = semanticSegmentTexts.entries
                .filter { it.key.row == fromRow }
                .map { (key, value) -> key.copy(row = toRow) to value }
            removeStoredSegmentTexts(fromRow)
            semanticSegmentTexts.putAll(moved)
        }
    }

    companion object {
        private const val TAG = "TerminalEmulatorImpl"
        private const val MAX_URL_SCAN_CONTINUATION_ROWS = 6
    }
}

/**
 * Represents a damaged region that needs updating.
 */
private data class DamageRegion(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int,
    val preserveSegments: Boolean = false,
)

/**
 * Represents a semantic segment waiting to be applied to a line.
 * Segments are queued during OSC processing and applied during processPendingUpdates
 * when the actual text content is available.
 */
private data class PendingSemanticSegment(
    val row: Int,
    val startCol: Int,
    val endCol: Int,
    val semanticType: SemanticType,
    val metadata: String?,
    val promptId: Int,
)

private data class SemanticSegmentKey(
    val row: Int,
    val startCol: Int,
    val endCol: Int,
    val semanticType: SemanticType,
    val metadata: String?,
    val promptId: Int,
) {
    constructor(row: Int, segment: SemanticSegment) : this(
        row = row,
        startCol = segment.startCol,
        endCol = segment.endCol,
        semanticType = segment.semanticType,
        metadata = segment.metadata,
        promptId = segment.promptId,
    )
}

/**
 * Represents the size of the terminal in characters.
 */
data class TerminalDimensions(
    val rows: Int,
    val columns: Int,
)
