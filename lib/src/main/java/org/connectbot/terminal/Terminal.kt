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

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Gesture type for unified gesture handling state machine.
 */
private enum class GestureType {
    Undetermined,
    Scroll,
    Selection,
    Zoom,
    HandleDrag,
    /** A claimed gesture whose host capability disappeared before release. */
    Ignored,

    /**
     * Mouse drag forwarded to the remote (tmux mouse mode). Press/motion/release
     * dispatched via [TerminalGestureCallback.onMouseDrag]. Selected when
     * a gestureCallback is present, a long-press has armed the gesture, the
     * user then drags, and the callback claims it (returns true on
     * [MouseDragPhase.Start]). (#94, #186)
     */
    MouseDrag,

    /**
     * A held one-finger swipe claimed by the host via
     * [TerminalGestureCallback.onSwipeHoldStart] (#524): the host runs its
     * own key repeat from press-and-hold; the terminal only reports axis
     * reversals ([TerminalGestureCallback.onSwipeHold]) and the release
     * ([TerminalGestureCallback.onSwipeHoldEnd]). No [onScroll] quanta.
     */
    SwipeHold,
}

/**
 * The rate at which the cursor blinks in milliseconds when enabled.
 */
private const val CURSOR_BLINK_RATE_MS = 500L

/**
 * Amount of time to wait for second touch to detect multitouch gesture in milliseconds.
 */
private const val WAIT_FOR_SECOND_TOUCH_MS = 40L

/**
 * Long-press threshold for the gesture callback (ms).
 * When a gestureCallback is present, fires onLongPress at this time — short
 * enough to feel responsive as a right-click, and the point at which a
 * copy-mode selection arms so a following drag forwards to the remote. (#186)
 */
private const val CALLBACK_LONG_PRESS_MS = 400L

/**
 * Extra grace before a press becomes a Haven text selection while the remote
 * app has mouse mode on (#435).
 *
 * Long-press starts a local selection in every session, so tmux/zellij users
 * keep the visible select-and-Copy workflow. The cost was that a *click* in a
 * mouse-mode app had to beat the system long-press timeout: aiming carefully at
 * a small target — a zellij tab, a tmux pane border — routinely takes longer
 * than that, and the press was hijacked into a selection while the app never
 * saw the click. Only quick taps got through, which is why this looked like it
 * worked. Holding for this long instead still opens the copy workflow; a
 * deliberate press-and-hold is unmistakably longer than a careful tap.
 */
private const val MOUSE_MODE_LONG_PRESS_MS = 900L

/**
 * How long a press must be held before it becomes a Haven text selection.
 * [mouseMode] true means the remote app asked for the mouse, so clicks belong
 * to it and the press gets [MOUSE_MODE_LONG_PRESS_MS] of grace; otherwise the
 * platform's own timeout applies. Pure so the policy is testable (#435).
 */
internal fun longPressDelayMs(mouseMode: Boolean, systemLongPressMs: Long): Long = if (mouseMode) MOUSE_MODE_LONG_PRESS_MS else systemLongPressMs

/**
 * Whether a tap landing while a selection is showing is spent dismissing that
 * selection, instead of being delivered as a click as well (#435).
 *
 * True outside mouse mode: no remote app wants the click, so dismissing is the
 * whole gesture. False while the app has mouse tracking on — there the click is
 * the app's, and consuming it to close Haven's own selection UI makes taps look
 * dead until the user taps a second time. Pure so the policy is testable.
 */
internal fun tapOnlyDismissesSelection(selectionActive: Boolean, mouseMode: Boolean): Boolean = selectionActive && !mouseMode

/**
 * Map a pointer position to a 0-based terminal cell.
 * [keyboardCoveredPx] is added to Y so the cell matches the shifted canvas.
 */
internal fun terminalCell(
    x: Float,
    y: Float,
    charWidth: Float,
    charHeight: Float,
    keyboardCoveredPx: Float,
    cols: Int,
    rows: Int,
): Pair<Int, Int> {
    if (cols <= 0 || rows <= 0 || charWidth <= 0f || charHeight <= 0f) {
        return 0 to 0
    }
    val col = (x / charWidth).toInt().coerceIn(0, cols - 1)
    val row = ((y + keyboardCoveredPx) / charHeight).toInt().coerceIn(0, rows - 1)
    return col to row
}

/**
 * One line per terminal gesture, tagged so a bug reporter can filter for it
 * with `logcat -s HavenGesture` (#435).
 *
 * Exists because that report could not be reproduced from a description: a
 * tap the user calls "as short as I can make it" starts a text selection on
 * their device, while a synthetic click of the same length does not on mine.
 * The disagreement is about what the touch stream actually contains, so this
 * prints the measurements rather than asking anyone to estimate them.
 *
 * ★[sinceDownMs] is time since this gesture's FIRST finger-down — not how long
 * the user pressed. The distinction cost a reporter three wasted rounds: the
 * field used to be called `pressMs`, and on a `selection-started` line it is
 * ~[thresholdMs] BY CONSTRUCTION, because that line is emitted the instant the
 * long-press timer fires. Reading `pressMs=901 thresholdMs=900` as "they held
 * it for 901ms" is therefore circular; every such line says that. The real
 * finger-down duration is [sinceDownMs] on the matching `ended-*` line.
 *
 * ★[gestureId] is what makes gesture merging visible: several physical taps
 * sharing one id are one gesture as far as the pointer stream is concerned,
 * which is the actual #435 defect. Without it, a 6794ms "press" that moved
 * 6.9px looks like an implausible user rather than a missed finger-up.
 */
internal fun gestureLogLine(
    outcome: String,
    gestureId: Long,
    sinceDownMs: Long,
    thresholdMs: Long,
    mouseMode: Boolean,
    movedPx: Float,
    touchSlopPx: Float,
    pointerType: String,
): String = "gesture=$outcome id=$gestureId sinceDownMs=$sinceDownMs thresholdMs=$thresholdMs " +
    "mouseMode=$mouseMode movedPx=${"%.1f".format(movedPx)} " +
    "slopPx=${"%.1f".format(touchSlopPx)} pointer=$pointerType"

/**
 * Millis after last multi-touch event to suppress tap from stale
 * finger lift-off after a pinch-to-zoom. Only blocks taps, not
 * long-press or drag gestures.
 */
private const val MULTITOUCH_LIFTOFF_MS = 200L

/**
 * Vertical drag accumulated before emitting one scroll callback event, in
 * DENSITY-INDEPENDENT pixels. Lower values feel more responsive in TUI apps but
 * send more escape sequences.
 *
 * This was a raw pixel count, which made scroll sensitivity depend on screen
 * density. Measured across real displays, the old 24px worked out to 12dp on
 * a 2x screen, 9.1dp at 2.625x, 8dp at 3x and 6dp at 4x — so the denser the
 * display, the shorter the swipe needed per wheel event, and the more escape
 * sequences the same physical gesture sent (#421).
 *
 * No single value can leave every device unchanged; that inconsistency IS the
 * bug. 9dp sits at the top of that observed range: near-identical on a 2.625x
 * phone, slightly less twitchy on the 3x and 4x displays where the complaint
 * came from, and slightly more responsive on 2x screens that previously needed
 * an unusually long drag.
 */
private const val SCROLL_THRESHOLD_DP = 9f

/**
 * [SCROLL_THRESHOLD_DP] in pixels at the given display [density]. Pure so the
 * density-scaling is testable — the bug it replaces was invisible precisely
 * because a bare constant looks correct on whatever device you happen to hold.
 */
internal fun scrollThresholdPx(density: Float): Float = SCROLL_THRESHOLD_DP * density

/**
 * The per-event travel for one-finger scroll gestures routed to a
 * [TerminalGestureCallback], scaled by the host's [multiplier]. Content
 * scrolling wants the fine [SCROLL_THRESHOLD_DP] quantum; a swipe that steps
 * a discrete cursor (swipe-arrows mode, #524) wants a far coarser one so a
 * deliberate swipe walks history entry by entry instead of flinging through
 * it. Clamped so a host value below 1 can't make scrolling twitchier than
 * the content default. Pure for the same reason as [scrollThresholdPx].
 */
internal fun callbackScrollThresholdPx(density: Float, multiplier: Float): Float =
    scrollThresholdPx(density) * multiplier.coerceAtLeast(1f)

/**
 * Fraction of terminal height at top/bottom that triggers edge-scroll
 * when dragging selection near the boundary.
 */
private const val EDGE_SCROLL_ZONE = 0.12f

/**
 * Interval between auto-repeat edge-scroll ticks while a drag is held still
 * inside the edge zone. The pointer-event loop only fires while the finger
 * moves; this tick covers a finger held stationary at the edge.
 */
private const val EDGE_SCROLL_TICK_MS = 60L

/**
 * Cap on rows scrolled per [EDGE_SCROLL_TICK_MS] tick. The finger position
 * within the edge zone scales the actual rows-per-tick from 1 (just inside
 * the zone boundary) up to this cap (against the screen edge), so dragging
 * deeper into the edge accelerates the scroll. At the cap, ≈130 rows/sec.
 * Issue #94 — ehoeve786 wanted scrolling to accelerate by finger position.
 */
private const val MAX_EDGE_SCROLL_ROWS = 8

/**
 * Text selection magnifier loupe size in dp.
 */
private const val MAGNIFIER_SIZE_DP = 100

/**
 * How much to scale up the text in the magnifier loupe.
 */
private const val MAGNIFIER_SCALE = 2.5f

/**
 * Delay in milliseconds before showing the IME (Input Method Editor).
 */
private const val IME_SHOW_DELAY_MS = 100L

/**
 * Delay in milliseconds to allow UI to settle before requesting focus.
 */
private const val UI_SETTLE_DELAY_MS = 100L

/**
 * Delay in milliseconds before showing the soft keyboard.
 */
private const val KEYBOARD_SHOW_DELAY_MS = 50L

/**
 * Border width for the terminal display in dp.
 */
private val TERMINAL_BORDER_WIDTH = 2.dp

/**
 * Minimum font size for pinch-to-zoom gesture (sp).
 */
private const val MIN_PINCH_FONT_SP = 6f

/**
 * Maximum font size for pinch-to-zoom gesture (sp).
 */
private const val MAX_PINCH_FONT_SP = 32f

/**
 * Size of the copy button when selection is active in dp.
 */
private val COPY_BUTTON_SIZE = 48.dp

/**
 * Vertical offset for the copy button above the selection in dp.
 */
private val COPY_BUTTON_OFFSET = 48.dp

/**
 * Touch radius in pixels for detecting selection handle touches.
 */
private const val HANDLE_HIT_RADIUS = 80f

/**
 * Vertical offset in dp to position the magnifier above (or below) the finger.
 */
private val MAGNIFIER_VERTICAL_OFFSET = 40.dp

/**
 * Estimated finger contact height in dp, used to avoid positioning the magnifier under the finger.
 */
private val FINGER_HEIGHT_DP = 50.dp

/**
 * Center offset multiplier for magnifier positioning.
 */
private const val MAGNIFIER_CENTER_OFFSET_MULTIPLIER = 1.2f

/**
 * Border width for the magnifier loupe in dp.
 */
private val MAGNIFIER_BORDER_WIDTH = 2.dp

/**
 * Background alpha for the magnifier loupe (0.0 = transparent, 1.0 = opaque).
 */
private const val MAGNIFIER_BACKGROUND_ALPHA = 0.9f

/**
 * Number of rows to display on each side of the touch point in the magnifier.
 */
private const val MAGNIFIER_ROW_RANGE = 3

/**
 * Width of selection handles (teardrop shape) in dp.
 */
private val SELECTION_HANDLE_WIDTH = 24.dp

/**
 * Alpha value for the block cursor.
 */
private const val CURSOR_BLOCK_ALPHA = 0.7f

/**
 * Alpha value for the underline and bar cursors.
 */
private const val CURSOR_LINE_ALPHA = 0.9f

/**
 * Percentage of cell height for underline cursor (0.0 to 1.0).
 */
private const val CURSOR_UNDERLINE_HEIGHT_RATIO = 0.15f

/**
 * Percentage of cell width for bar cursor (0.0 to 1.0).
 */
private const val CURSOR_BAR_WIDTH_RATIO = 0.15f

/**
 * Convergence threshold for binary search when finding optimal font size.
 */
private const val FONT_SIZE_SEARCH_EPSILON = 0.1f

/**
 * Number of wavelengths per character width for the curly underline pattern.
 */
private const val CURLY_UNDERLINE_CYCLES_PER_CHAR = 2f

/**
 * Amplitude (height) of the curly underline pattern in pixels.
 */
private const val CURLY_UNDERLINE_AMPLITUDE = 1.5f

/**
 * Spacing between the two lines in a double underline in pixels.
 */
private const val DOUBLE_UNDERLINE_SPACING = 2f

/**
 * Terminal - A Jetpack Compose terminal screen component.
 *
 * This component:
 * - Renders terminal output using Canvas
 * - Handles terminal resize based on available space
 * - Displays cursor
 * - Supports colors, bold, italic, underline, etc.
 *
 * @param terminalEmulator The terminal emulator containing terminal state
 * @param modifier Modifier for the composable
 * @param typeface Typeface for terminal text (default: Typeface.MONOSPACE)
 * @param initialFontSize Initial font size for terminal text (can be changed with pinch-to-zoom)
 * @param minFontSize Minimum font size for pinch-to-zoom
 * @param maxFontSize Maximum font size for pinch-to-zoom
 * @param backgroundColor Default background color
 * @param foregroundColor Default foreground color
 * @param keyboardEnabled Enable keyboard input handling (default: false for display-only mode).
 *                        When false, no keyboard input (hardware or soft) is accepted.
 * @param showSoftKeyboard Whether to show the soft keyboard/IME (default: true when keyboardEnabled=true).
 *                         Only applies when keyboardEnabled=true. Hardware keyboard always works when keyboardEnabled=true.
 * @param focusRequester Focus requester for keyboard input (if enabled)
 * @param onTerminalTap Callback for a simple tap event on the terminal (when no selection is active)
 * @param onImeVisibilityChanged Callback invoked when IME visibility changes (true = shown, false = hidden)
 * @param forcedSize Force terminal to specific dimensions (rows, cols). When set, font size is calculated to fit.
 * @param onSelectionControllerAvailable Optional callback providing access to the SelectionController for controlling selection mode
 * @param onHyperlinkClick Callback when user taps on an OSC8 hyperlink. Receives the URL as parameter.
 * @param selectionBackgroundColor Background color for selected text (default: 0xFFB3D7FF)
 * @param selectionForegroundColor Foreground color for selected text (default: Black)
 */
@Composable
fun Terminal(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    /**
     * Alpha applied to the screen-fill background only (0.0 = fully
     * transparent, 1.0 = opaque). Cell backgrounds keep their own colour;
     * default/empty cells skip painting and so reveal whatever sits behind
     * the Canvas (the host can make its window translucent for a wallpaper
     * see-through effect). Default 1.0 = unchanged opaque fill.
     */
    backgroundOpacity: Float = 1f,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    /**
     * Bump to re-run the IME show even though [showSoftKeyboard] hasn't
     * changed — the warm-return restore (#515). The show must be this
     * terminal's own [ImeInputView.showIme]: an InsetsController show from
     * the host layer dies in the IMM ("view not served") because
     * ImeInputView drops focusability while its window is invisible.
     */
    imeRestoreTick: Int = 0,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onTerminalTap: () -> Unit = {},
    onTerminalDoubleTap: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null,
    onSelectionControllerAvailable: ((SelectionController) -> Unit)? = null,
    onHyperlinkClick: (String) -> Unit = {},
    onComposeControllerAvailable: ((ComposeController) -> Unit)? = null,
    onFontSizeChanged: ((TextUnit) -> Unit)? = null,
    gestureCallback: TerminalGestureCallback? = null,
    /**
     * Multiplies the travel needed per [TerminalGestureCallback.onScroll]
     * event for one-finger swipes. Hosts pass >1 when the callback maps
     * swipes to discrete key steps (swipe-arrows mode, #524) so each arrow
     * takes a deliberate fingertip of travel; 1 = the content-scroll feel.
     * Values below 1 are clamped to 1. Content scrollback, edge-scroll and
     * touchpad paths are unaffected.
     */
    scrollQuantumMultiplier: Float = 1f,
    allowStandardKeyboard: Boolean = false,
    rawKeyboardMode: Boolean = false,
    /**
     * When non-null, ImeInputView ignores the Secure/Standard
     * inferential logic and assembles EditorInfo from these explicit
     * toggles. Lets a power user tune around their IME's quirks
     * (#115 follow-up). Default null = use the preset logic.
     */
    customImeFlags: ImeFlagBundle? = null,
    onPasteShortcut: (() -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    rightAltMode: RightAltMode = RightAltMode.CharacterModifier,
    delKeyMode: DelKeyMode = DelKeyMode.Delete,
    /**
     * When true, tapping inside the active OSC 133 prompt's input line
     * synthesises arrow-key dispatches to move the shell's input cursor
     * to the tapped column. Only fires when the cursor row has a
     * `COMMAND_INPUT` segment with no matching `COMMAND_FINISHED`, and
     * the viewport is at the bottom (not scrolled back). Has no effect
     * on shells that don't emit OSC 133 (`133;A`/`B`/`D`).
     */
    tapToPositionCursorOnPrompt: Boolean = false,
    onScrollControllerAvailable: ((ScrollController) -> Unit)? = null,
    /**
     * Optional callback handed a [GestureInjector] once the terminal is
     * composed. Lets a test harness drive synthetic touch gestures
     * through the real pointer pipeline. Production callers leave this
     * null.
     */
    onGestureInjectorReady: ((GestureInjector) -> Unit)? = null,
    /**
     * True while the remote app has mouse tracking on (DECSET ?1000/?1002/
     * ?1003) and Haven is forwarding taps to it as clicks. Clicks belong to the
     * app, so a press gets [MOUSE_MODE_LONG_PRESS_MS] before Haven converts it
     * into a local text selection (#435).
     *
     * Must be the real mouse-mode signal, not "a gestureCallback exists": a
     * callback is also installed for alt-screen-only apps (vim, less, plain
     * tmux) that forward *scroll* but not taps, and those have no click to
     * protect — giving them the longer grace only makes selecting text feel
     * broken.
     */
    mouseModeActive: Boolean = false,
    /**
     * When true, a one-finger drag that crosses touch slop is forwarded as
     * left-button press/motion/release via [onMouseDrag] instead of becoming
     * local selection or scroll. Taps still follow the normal tap path.
     * Default false keeps current Haven behaviour (long-press selects, swipe
     * scrolls).
     */
    immediateMouseDrag: Boolean = false,
    /**
     * Host hint: reflow (resize the PTY to the keyboard-shrunk height,
     * SIGWINCH) on a soft-keyboard toggle, instead of keeping the row count
     * and render-shifting (#206). The alternate screen always reflows; this
     * lets the host opt a PRIMARY-buffer full-screen TUI in too (e.g. a tmux/
     * screen session, or any app with mouse tracking active) so its top
     * status row isn't shifted off the top. Default false = plain line-mode
     * shell: keep the no-resize render-shift.
     */
    reflowOnKeyboard: Boolean = false,
) {
    if (LocalInspectionMode.current) {
        TerminalPreview(modifier, backgroundColor, foregroundColor)
        return
    }

    TerminalWithAccessibility(
        terminalEmulator = terminalEmulator,
        modifier = modifier,
        typeface = typeface,
        initialFontSize = initialFontSize,
        minFontSize = minFontSize,
        maxFontSize = maxFontSize,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
        backgroundOpacity = backgroundOpacity,
        keyboardEnabled = keyboardEnabled,
        showSoftKeyboard = showSoftKeyboard,
        imeRestoreTick = imeRestoreTick,
        focusRequester = focusRequester,
        onTerminalTap = onTerminalTap,
        onTerminalDoubleTap = onTerminalDoubleTap,
        onImeVisibilityChanged = onImeVisibilityChanged,
        forcedSize = forcedSize,
        modifierManager = modifierManager,
        onSelectionControllerAvailable = onSelectionControllerAvailable,
        onHyperlinkClick = onHyperlinkClick,
        onComposeControllerAvailable = onComposeControllerAvailable,
        onFontSizeChanged = onFontSizeChanged,
        gestureCallback = gestureCallback,
        scrollQuantumMultiplier = scrollQuantumMultiplier,
        allowStandardKeyboard = allowStandardKeyboard,
        rawKeyboardMode = rawKeyboardMode,
        customImeFlags = customImeFlags,
        onPasteShortcut = onPasteShortcut,
        onScrollControllerAvailable = onScrollControllerAvailable,
        onPasteRequest = onPasteRequest,
        rightAltMode = rightAltMode,
        selectionBackgroundColor = selectionBackgroundColor,
        selectionForegroundColor = selectionForegroundColor,
        delKeyMode = delKeyMode,
        tapToPositionCursorOnPrompt = tapToPositionCursorOnPrompt,
        onGestureInjectorReady = onGestureInjectorReady,
        reflowOnKeyboard = reflowOnKeyboard,
        mouseModeActive = mouseModeActive,
        immediateMouseDrag = immediateMouseDrag,
    )
}

/**
 * Used for testing accessibility.
 *
 * @see Terminal
 */
@VisibleForTesting
@Composable
internal fun TerminalWithAccessibility(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    backgroundOpacity: Float = 1f,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    imeRestoreTick: Int = 0,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onTerminalTap: () -> Unit = {},
    onTerminalDoubleTap: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null,
    forceAccessibilityEnabled: Boolean? = null,
    onSelectionControllerAvailable: ((SelectionController) -> Unit)? = null,
    onHyperlinkClick: (String) -> Unit = {},
    onComposeControllerAvailable: ((ComposeController) -> Unit)? = null,
    onFontSizeChanged: ((TextUnit) -> Unit)? = null,
    gestureCallback: TerminalGestureCallback? = null,
    /** See [Terminal]'s scrollQuantumMultiplier (#524). */
    scrollQuantumMultiplier: Float = 1f,
    allowStandardKeyboard: Boolean = false,
    rawKeyboardMode: Boolean = false,
    customImeFlags: ImeFlagBundle? = null,
    onPasteShortcut: (() -> Unit)? = null,
    onScrollControllerAvailable: ((ScrollController) -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    rightAltMode: RightAltMode = RightAltMode.CharacterModifier,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
    delKeyMode: DelKeyMode = DelKeyMode.Delete,
    onGestureInjectorReady: ((GestureInjector) -> Unit)? = null,
    tapToPositionCursorOnPrompt: Boolean = false,
    reflowOnKeyboard: Boolean = false,
    mouseModeActive: Boolean = false,
    immediateMouseDrag: Boolean = false,
) {
    if (terminalEmulator !is TerminalEmulatorImpl) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text("Unknown TerminalEmulator type")
        }
        return
    }

    val terminalEmulator: TerminalEmulatorImpl = terminalEmulator

    // Remember updated callbacks to avoid stale lambdas inside pointerInput
    val currentOnTerminalTap by rememberUpdatedState(onTerminalTap)
    val currentOnHyperlinkClick by rememberUpdatedState(onHyperlinkClick)

    // Read through a State so the gesture handler sees mode flips without
    // being keyed on them: pointerInput only restarts on terminalEmulator /
    // gestureCallback, so a captured Boolean would go stale. (#435)
    val currentMouseModeActive by rememberUpdatedState(mouseModeActive)
    val currentImmediateMouseDrag by rememberUpdatedState(immediateMouseDrag)

    val density = LocalDensity.current
    // Density-scaled once here; every scroll site below uses this, never a raw
    // pixel count (#421).
    val scrollThreshold = scrollThresholdPx(density.density)
    // Read through a State like mouseModeActive (#435): the pointerInput
    // restarts when gestureCallback changes identity, but the multiplier can
    // recompose independently and a captured Float would go stale.
    val callbackScrollThreshold by rememberUpdatedState(
        callbackScrollThresholdPx(density.density, scrollQuantumMultiplier),
    )
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Track accessibility state - only enable accessibility features when needed
    val systemAccessibilityEnabled by rememberAccessibilityState()
    val accessibilityEnabled = forceAccessibilityEnabled ?: systemAccessibilityEnabled

    // Observe terminal state via StateFlow
    val screenState = rememberTerminalScreenState(terminalEmulator)

    // Keyboard handler (will be updated with selectionController after it's created)
    val keyboardHandler = remember(terminalEmulator) {
        KeyboardHandler(terminalEmulator, modifierManager)
    }
    keyboardHandler.rightAltMode = rightAltMode
    keyboardHandler.delKeyMode = delKeyMode

    // Re-bind the paste shortcut callback whenever the host swaps it
    // (e.g. when the user toggles the preference). Reading null here
    // releases the binding so V keys flow through unchanged.
    SideEffect {
        keyboardHandler.onPasteShortcut = onPasteShortcut
    }

    // Font size state (pinch-to-zoom adjusts this directly)
    var calculatedFontSize by remember(terminalEmulator) { mutableStateOf(initialFontSize) }
    // Track whether a pinch gesture set the font size, to avoid the
    // initialFontSize LaunchedEffect overwriting it before the preference
    // round-trips back.
    var fontSetByPinch by remember(terminalEmulator) { mutableStateOf(false) }

    // Magnifying glass state
    var showMagnifier by remember(terminalEmulator) { mutableStateOf(false) }
    var magnifierPosition by remember(terminalEmulator) { mutableStateOf(Offset.Zero) }

    // Cursor blink state
    var cursorBlinkVisible by remember(terminalEmulator) { mutableStateOf(true) }

    // IME text field state (hidden BasicTextField for capturing IME input)
    val imeFocusRequester = remember { FocusRequester() }

    // Review Mode state for accessibility
    var isReviewMode by remember(terminalEmulator) { mutableStateOf(false) }
    val reviewFocusRequester = remember { FocusRequester() }

    // Manage focus and IME visibility
    // Determine if IME should be shown:
    // 1. keyboardEnabled is true (master switch)
    // 2. showSoftKeyboard is true (user wants IME visible)
    val shouldShowIme = keyboardEnabled && showSoftKeyboard

    // Keep reference to ImeInputView for controlling IME
    var imeInputView by remember { mutableStateOf<ImeInputView?>(null) }

    // Live IME composition text (romaji mid-conversion, pinyin candidates).
    // Rendered as a floating overlay near the terminal cursor so the partial
    // text never has to be projected into the terminal itself — avoids the
    // backspace-and-rewrite flicker that inline projection produced during
    // CJK conversion, and avoids paying per-keystroke round-trips over mosh.
    val imeComposerText by (
        imeInputView?.composingText
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow("") }
        ).collectAsState()

    // Cleanup IME when component is disposed. Leave the focus chain first: a
    // focused interop child at removeAllViewsInLayout() time makes ViewGroup
    // re-enter the disposing Compose hierarchy via rootViewRequestFocus()
    // (crashes on HyperOS/Android 16). This used to drop the FOCUSABLE flag
    // here, which is not safe during a disposal — View.setFlags gives focus up
    // by calling the public clearFocus(), and that calls rootViewRequestFocus()
    // itself. ImeInputView.onInteropReset() is the version that doesn't.
    DisposableEffect(imeInputView) {
        // Capture the key's value: onDispose reading the `imeInputView` state
        // directly would act on the NEW view when the key changes null→view,
        // tearing down its focus at creation.
        val disposingView = imeInputView
        onDispose {
            Log.d("Terminal", "Disposing Terminal - hiding IME")
            disposingView?.onInteropReset()
            disposingView?.hideIme()
        }
    }

    // React to IME state changes
    LaunchedEffect(shouldShowIme, imeInputView) {
        Log.d("Terminal", "IME state changed: shouldShowIme=$shouldShowIme (imeInputView=$imeInputView)")

        imeInputView?.let { view ->
            if (shouldShowIme) {
                Log.d("Terminal", "Showing IME via InputMethodManager")
                delay(IME_SHOW_DELAY_MS)
                view.showIme()
                Log.d("Terminal", "IME show completed")
                onImeVisibilityChanged(true)
            } else {
                Log.d("Terminal", "Hiding IME via InputMethodManager")
                view.hideIme()
                Log.d("Terminal", "IME hide completed")
                onImeVisibilityChanged(false)
            }
        }
    }

    // Warm-return keyboard restore (#515). A background round trip leaves
    // shouldShowIme unchanged, so the effect above never re-fires, yet the
    // platform has torn the IME down. The host bumps imeRestoreTick when its
    // pause-time record says a keyboard should come back; the show must be
    // showIme() because this view dropped focus and focusability while the
    // window was invisible, so nothing else in the window can be served.
    LaunchedEffect(imeRestoreTick) {
        if (imeRestoreTick > 0 && shouldShowIme) {
            imeInputView?.let { view ->
                delay(IME_SHOW_DELAY_MS)
                Log.i("TerminalIme", "ime restore tick=$imeRestoreTick -> showIme()")
                view.showIme()
                onImeVisibilityChanged(true)
            }
        }
    }

    // Manage focus based on Review Mode
    LaunchedEffect(isReviewMode) {
        if (isReviewMode) {
            // Entering Review Mode: hide keyboard, focus on accessibility overlay
            keyboardController?.hide()
            delay(UI_SETTLE_DELAY_MS)
            try {
                reviewFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Focus requester not attached yet, ignore
            }
        } else {
            // Exiting Review Mode: return focus to input field if keyboard enabled
            if (keyboardEnabled && shouldShowIme) {
                delay(UI_SETTLE_DELAY_MS)
                imeFocusRequester.requestFocus()
                delay(KEYBOARD_SHOW_DELAY_MS)
                keyboardController?.show()
            }
        }
    }

    // Cursor blink animation
    LaunchedEffect(
        screenState.snapshot.cursorVisible,
        screenState.snapshot.cursorBlink,
        screenState.snapshot.cursorRow,
        screenState.snapshot.cursorCol,
    ) {
        if (screenState.snapshot.cursorVisible) {
            cursorBlinkVisible = true
            if (screenState.snapshot.cursorBlink) {
                // Show cursor immediately when it moves or becomes visible
                while (true) {
                    delay(CURSOR_BLINK_RATE_MS)
                    cursorBlinkVisible = !cursorBlinkVisible
                }
            }
        } else {
            cursorBlinkVisible = false
        }
    }

    // Create TextPaint for measuring and drawing (base size)
    val textPaint = remember(typeface, calculatedFontSize) {
        TextPaint().apply {
            this.typeface = typeface
            textSize = with(density) { calculatedFontSize.toPx() }
            isAntiAlias = true
        }
    }

    // Base character dimensions (unzoomed)
    val baseCharWidth = remember(textPaint) {
        textPaint.measureText("M")
    }

    // Snap to integer pixels so adjacent rows share a pixel boundary; otherwise
    // fractional row pitch leaves a gap between background rects (issue #2069).
    val baseCharHeight = remember(textPaint) {
        val metrics = textPaint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent)
        if (height <= 0f) 20f else height // Fallback for tests
    }

    val baseCharBaseline = remember(textPaint) {
        ceil(-textPaint.fontMetrics.ascent)
    }

    // The gesture handler below is pointerInput(terminalEmulator, gestureCallback),
    // which never restarts on a font-size change — a pinch-zoom would leave its
    // long-lived coroutine holding the pre-zoom metrics, so every later touch
    // maps to the wrong cell. Route the metrics through State (same pattern as
    // the currentOn* callbacks) so reads inside the handler are always current.
    val currentCharWidth = rememberUpdatedState(baseCharWidth)
    val currentCharHeight = rememberUpdatedState(baseCharHeight)

    // Scroll animation state
    val scrollOffset = remember(terminalEmulator) { Animatable(0f) }
    val maxScroll = remember(screenState.snapshot.scrollback.size, baseCharHeight) {
        screenState.snapshot.scrollback.size * baseCharHeight
    }

    // #478: same trap as the char metrics above. The gesture handler is
    // pointerInput(terminalEmulator, gestureCallback), which does NOT restart on
    // a font-size change, so a plain capture of maxScroll stays at its pre-zoom
    // value for the life of that coroutine. Every scroll then clamps to the old
    // limit, and the scrollback a zoom just created cannot be reached —
    // intermittently, because anything that changes gestureCallback (entering
    // mouse mode, the alt screen) restarts the handler and silently fixes it
    // until the next zoom.
    val currentMaxScroll = rememberUpdatedState(maxScroll)
    // Mutable holder avoids recomposition for every indirect touchpad MOVE.
    val indirectSwipeAccumY = remember(terminalEmulator) { floatArrayOf(0f) }
    val interopTwoFingerLastY = remember(terminalEmulator) { floatArrayOf(Float.NaN) }
    LaunchedEffect(maxScroll) {
        scrollOffset.updateBounds(0f, maxScroll)
    }

    // Selection manager
    val selectionManager = remember(terminalEmulator) {
        SelectionManager()
    }

    // Selection controller - expose API for external control
    val selectionController = remember(terminalEmulator, selectionManager) {
        object : SelectionController {
            override val isSelectionActive: Boolean
                get() = selectionManager.mode != SelectionMode.NONE

            override val scrollbackPosition: Int
                get() = screenState.scrollbackPosition

            override fun startSelection(mode: SelectionMode) {
                if (selectionManager.mode == SelectionMode.NONE) {
                    // Start at cursor position or center of screen
                    val row = screenState.snapshot.cursorRow.coerceIn(0, screenState.snapshot.rows - 1)
                    val col = screenState.snapshot.cursorCol.coerceIn(0, screenState.snapshot.cols - 1)
                    selectionManager.startSelection(row, col, screenState.snapshot.cols, mode, screenState.snapshot, screenState.scrollbackPosition)
                }
            }

            override fun toggleSelection() {
                if (selectionManager.mode == SelectionMode.NONE) {
                    startSelection()
                } else {
                    clearSelection()
                }
            }

            override fun moveSelectionUp() {
                selectionManager.moveSelectionUp(screenState.snapshot.rows)
            }

            override fun moveSelectionDown() {
                selectionManager.moveSelectionDown(screenState.snapshot.rows)
            }

            override fun moveSelectionLeft() {
                selectionManager.moveSelectionLeft(screenState.snapshot.cols)
            }

            override fun moveSelectionRight() {
                selectionManager.moveSelectionRight(screenState.snapshot.cols)
            }

            override fun toggleSelectionMode() {
                selectionManager.toggleMode(screenState.snapshot.cols, screenState.snapshot, screenState.scrollbackPosition)
            }

            override fun setSelectionMode(mode: SelectionMode) {
                selectionManager.setMode(mode, screenState.snapshot.cols, screenState.snapshot, screenState.scrollbackPosition)
            }

            override fun selectAll() {
                selectionManager.selectAll(screenState.snapshot.rows, screenState.snapshot.cols)
            }

            override fun finishSelection() {
                selectionManager.endSelection()
            }

            override fun copySelection(): String {
                val text = selectionManager.getSelectedText(screenState.snapshot, screenState.scrollbackPosition)
                if (text.isNotEmpty()) {
                    clipboardManager.setText(AnnotatedString(text))
                    selectionManager.clearSelection()
                }
                return text
            }

            override fun clearSelection() {
                selectionManager.clearSelection()
            }

            override fun updateSelectionStart(row: Int, col: Int) {
                selectionManager.updateSelectionStart(row, col)
            }

            override fun updateSelectionEnd(row: Int, col: Int) {
                selectionManager.updateSelectionEnd(row, col)
            }

            override fun getSelectionRange(): SelectionRange? = selectionManager.selectionRange

            override fun getSelectedText(): String = selectionManager.getSelectedText(
                screenState.snapshot,
                screenState.scrollbackPosition,
            )

            override fun shiftSelectionStartByRows(delta: Int) {
                selectionManager.shiftSelectionStartByRows(delta)
            }
        }
    }

    // Compose mode state
    val composeMode = remember(terminalEmulator) {
        ComposeMode()
    }

    // Compose controller - expose API for external control
    val composeController = remember(terminalEmulator, composeMode, selectionController) {
        object : ComposeController {
            override val isComposeModeActive: Boolean
                get() = composeMode.isActive

            override fun startComposeMode() {
                selectionController.clearSelection()
                composeMode.activate()
            }

            override fun stopComposeMode() {
                // Flush any in-flight IME composition into the terminal before leaving
                // compose mode, so the user's typing isn't silently dropped on toggle off.
                // cancel() only clears the buffer and stays in compose mode (that's the
                // Escape behaviour); the explicit toggle-off must go through deactivate()
                // — upstream 6d0f33a, dropped in the 0.1.1 merge (Haven #378).
                composeMode.commit()?.codePoints()?.forEach { codepoint ->
                    terminalEmulator.dispatchCharacter(0, codepoint)
                }
                composeMode.deactivate()
            }

            override fun toggleComposeMode() {
                if (composeMode.isActive) {
                    stopComposeMode()
                } else {
                    startComposeMode()
                }
            }

            override fun getComposedText(): String = composeMode.buffer

            override val pendingDeadChar: Int
                get() = keyboardHandler.pendingDeadChar
        }
    }

    // Wire compose mode into keyboard handler
    LaunchedEffect(composeMode) {
        keyboardHandler.composeMode = composeMode
    }

    // Provide selection controller to caller and keyboard handler
    LaunchedEffect(selectionController) {
        keyboardHandler.selectionController = selectionController
        onSelectionControllerAvailable?.invoke(selectionController)
    }

    // Provide compose controller to caller
    LaunchedEffect(composeController) {
        onComposeControllerAvailable?.invoke(composeController)
    }

    // Scroll controller implementation
    val scrollController = remember(screenState, scrollOffset, scope, maxScroll) {
        object : ScrollController {
            override val scrollbackPosition: Int
                get() = screenState.scrollbackPosition

            override val maxScrollback: Int
                get() = screenState.snapshot.scrollback.size

            override fun scrollToBottom() {
                screenState.scrollToBottom()
                scope.launch {
                    scrollOffset.snapTo(0f)
                }
            }

            override fun scrollToTop() {
                screenState.scrollToTop()
                scope.launch {
                    scrollOffset.snapTo(maxScroll)
                }
            }

            override fun scrollBy(lines: Int) {
                val newPosition = (screenState.scrollbackPosition + lines)
                    .coerceIn(0, maxScrollback)
                screenState.scrollBy(newPosition - screenState.scrollbackPosition)
                scope.launch {
                    scrollOffset.snapTo(newPosition * baseCharHeight)
                }
            }
        }
    }

    // Provide scroll controller to caller
    LaunchedEffect(scrollController) {
        onScrollControllerAvailable?.invoke(scrollController)
    }

    // Synthetic-gesture injection. Captured live so the injector reads
    // the current terminal geometry at injectDrag time rather than at
    // composition time: the content Box reports its window-root origin
    // via onGloballyPositioned below, and a SideEffect mirrors the char
    // metrics (which change on font-size / pinch zoom).
    val hostView = LocalView.current
    var contentOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val injectorCharWidth = remember { mutableFloatStateOf(0f) }
    val injectorCharHeight = remember { mutableFloatStateOf(0f) }
    SideEffect {
        injectorCharWidth.floatValue = baseCharWidth
        injectorCharHeight.floatValue = baseCharHeight
    }
    val gestureInjector = remember(hostView) {
        object : GestureInjector {
            override fun injectDrag(
                path: List<Pair<Int, Int>>,
                pressMs: Long,
                stepMs: Long,
                holdMs: Long,
            ) {
                require(path.isNotEmpty()) { "path must have at least one cell" }
                check(Looper.myLooper() != Looper.getMainLooper()) {
                    "injectDrag must not be called on the main thread — it blocks"
                }
                val origin = contentOriginInRoot
                val cw = injectorCharWidth.floatValue
                val ch = injectorCharHeight.floatValue
                check(cw > 0f && ch > 0f) { "terminal geometry not measured yet" }

                fun cellX(col: Int) = origin.x + (col + 0.5f) * cw
                fun cellY(row: Int) = origin.y + (row + 0.5f) * ch

                val handler = Handler(Looper.getMainLooper())
                val latch = java.util.concurrent.CountDownLatch(1)
                val downTime = SystemClock.uptimeMillis()

                fun dispatch(action: Int, offset: Long, cell: Pair<Int, Int>) {
                    val ev = MotionEvent.obtain(
                        downTime,
                        downTime + offset,
                        action,
                        cellX(cell.second),
                        cellY(cell.first),
                        0,
                    )
                    ev.source = InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        hostView.dispatchTouchEvent(ev)
                    } finally {
                        ev.recycle()
                    }
                }

                // DOWN, then a still-hold so the long-press selection
                // timeout fires; MOVEs through the path; a still-hold at
                // the end (the edge-scroll ticker's window); UP.
                handler.post { dispatch(MotionEvent.ACTION_DOWN, 0L, path[0]) }
                var offset = pressMs
                for (i in 1 until path.size) {
                    val at = offset
                    val cell = path[i]
                    handler.postDelayed({ dispatch(MotionEvent.ACTION_MOVE, at, cell) }, at)
                    offset += stepMs
                }
                // offset now points just past the last MOVE (or == pressMs
                // when path has a single cell). Hold, then lift.
                val upOffset = (offset - stepMs).coerceAtLeast(pressMs) + holdMs
                handler.postDelayed({
                    dispatch(MotionEvent.ACTION_UP, upOffset, path.last())
                    latch.countDown()
                }, upOffset)

                // Block the caller until the gesture finishes (plus slack).
                latch.await(upOffset + 3000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }
    LaunchedEffect(gestureInjector) {
        onGestureInjectorReady?.invoke(gestureInjector)
    }

    // Sync compose mode active state to ImeInputView so onCreateInputConnection returns the
    // correct outAttrs (and restartInput is called to apply the change).
    LaunchedEffect(composeMode.isActive, imeInputView) {
        imeInputView?.isComposeModeActive = composeMode.isActive
    }

    // Coroutine scope for animations
    val coroutineScope = rememberCoroutineScope()

    // Last tap tracking for double-tap detection.
    // Stored as plain vars in a remembered holder so writes don't trigger recomposition.
    val tapTracker = remember(terminalEmulator) {
        object {
            var lastTimestamp = 0L
            var lastPosition = Offset.Zero
        }
    }

    // Setup keyboard input callback to reset scroll position
    LaunchedEffect(screenState, scrollOffset) {
        keyboardHandler.onInputProcessed = {
            screenState.scrollToBottom()
            coroutineScope.launch {
                scrollOffset.snapTo(0f)
            }
        }
    }
    val viewConfiguration = LocalViewConfiguration.current

    var availableWidth by remember { mutableStateOf(0) }
    var availableHeight by remember { mutableStateOf(0) }
    // The keyboard-HIDDEN viewport height. The emulator is sized from this
    // (not from the live, keyboard-shrunk height), so toggling the soft
    // keyboard no longer resizes the PTY. That resize was sending a SIGWINCH
    // that made fancy-prompt shells repaint and shove visible output into
    // scrollback ("scrolled to the bottom" — issue #206).
    //
    // It is sampled in onSizeChanged ONLY when the IME is actually hidden
    // (read from the raw window insets), so it can never be seeded from a
    // keyboard-up measurement. An earlier version tracked the max height seen
    // and reset on width change, which re-seeded a keyboard-up height after a
    // tab-switch / return-to-foreground with the keyboard up — making the jump
    // recur (the #206 follow-up). Sampling only-when-hidden fixes that at the
    // root and also handles rotation (the new orientation's hidden height is
    // sampled the moment the keyboard is down).
    var maxAvailableHeight by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                availableWidth = it.width
                availableHeight = it.height
                // Sample the keyboard-HIDDEN baseline only when the IME is
                // actually hidden — read from the raw window insets, which
                // bypass the upstream consumeWindowInsets that zeroes the
                // Compose-side ime inset here. This stops the baseline ever
                // being seeded from a keyboard-up height (the #206 recurrence).
                // When opened straight into a keyboard-up state with no hidden
                // height seen yet, estimate it by adding the IME inset back so
                // the grid still gets a sane initial size (corrected exactly on
                // the first keyboard-down).
                val imeBottom = ViewCompat.getRootWindowInsets(hostView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                if (imeBottom == 0) {
                    maxAvailableHeight = it.height
                } else if (maxAvailableHeight == 0) {
                    maxAvailableHeight = it.height + imeBottom
                }
            }
            .then(
                if (keyboardEnabled) {
                    Modifier
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            // In Review Mode, let accessibility system handle navigation keys
                            if (isReviewMode) {
                                // Allow arrow keys, Page Up/Down to navigate accessibility tree
                                when (event.key) {
                                    Key.DirectionUp,
                                    Key.DirectionDown,
                                    Key.DirectionLeft,
                                    Key.DirectionRight,
                                    Key.PageUp,
                                    Key.PageDown,
                                    -> false

                                    // Don't consume - let system handle
                                    else -> {
                                        // Any other key exits Review Mode and goes to shell
                                        isReviewMode = false
                                        keyboardHandler.onKeyEvent(event)
                                    }
                                }
                            } else {
                                // Input Mode: send all keys to shell
                                keyboardHandler.onKeyEvent(event)
                            }
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        // Calculate font size if forcedSize is specified
        if (forcedSize != null) {
            val (forcedRows, forcedCols) = forcedSize
            LaunchedEffect(availableWidth, availableHeight, forcedRows, forcedCols) {
                if (availableWidth == 0 || availableHeight == 0) {
                    return@LaunchedEffect
                }

                val optimalSize = findOptimalFontSize(
                    targetRows = forcedRows,
                    targetCols = forcedCols,
                    availableWidth = availableWidth,
                    availableHeight = availableHeight,
                    minSize = minFontSize.value,
                    maxSize = maxFontSize.value,
                    typeface = typeface,
                    density = density.density,
                )
                calculatedFontSize = optimalSize.sp
            }
        } else {
            // Sync from external font size changes (e.g. settings slider).
            // Skip if a pinch gesture just set the size — the preference
            // will round-trip back as a new initialFontSize momentarily.
            LaunchedEffect(initialFontSize) {
                if (fontSetByPinch) {
                    fontSetByPinch = false
                } else if (calculatedFontSize != initialFontSize) {
                    calculatedFontSize = initialFontSize
                }
            }
        }

        // Track whether we've sized the emulator once so subsequent resizes
        // can debounce without delaying the initial paint.
        var initialResizeDone by remember(terminalEmulator) { mutableStateOf(false) }

        // Height the PTY is sized from. A plain line-mode shell keeps the
        // keyboard-hidden height, so a soft-keyboard toggle leaves the row
        // count unchanged and never resizes the PTY (issue #206) — instead the
        // render is shifted up (keyboardCoveredPx). A full-screen TUI instead
        // sizes to the live, keyboard-shrunk height so the app reflows to fit
        // above the keyboard (its status lines stay reachable) and expects the
        // SIGWINCH. The alternate buffer is the intrinsic full-screen signal;
        // [reflowOnKeyboard] is the host's hint for a PRIMARY-buffer full-screen
        // TUI (tmux/screen, or any app with mouse tracking active) that would
        // otherwise take the render-shift path and lose its TOP status row
        // (e.g. tmux's scroll/copy-mode indicator) off the top.
        val reflowToKeyboard = shouldReflowToKeyboard(
            altScreen = screenState.snapshot.altScreen,
            reflowOnKeyboard = reflowOnKeyboard,
        )
        val sizingHeight = if (reflowToKeyboard) availableHeight else maxAvailableHeight

        // Resize terminal when the sizing dimensions change. On the primary
        // buffer a soft-keyboard toggle leaves the row count unchanged (#206);
        // on the alternate buffer it follows the visible height.
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(terminalEmulator, availableWidth, sizingHeight, forcedSize, baseCharWidth, baseCharHeight) {
            if (availableWidth == 0 || sizingHeight == 0 || baseCharWidth <= 0f || baseCharHeight <= 0f) {
                return@LaunchedEffect
            }

            // Use base dimensions for terminal sizing (not zoomed dimensions)
            val newCols =
                forcedSize?.second ?: charsPerDimension(availableWidth, baseCharWidth)
            val newRows =
                forcedSize?.first ?: charsPerDimension(sizingHeight, baseCharHeight)

            val dimensions = terminalEmulator.dimensions
            if (newRows == dimensions.rows && newCols == dimensions.columns) {
                return@LaunchedEffect
            }

            // Debounce rapid size changes (IME insets animation, pinch-to-zoom).
            // The LaunchedEffect cancels in flight when keys change, so only the
            // final stable size completes the delay and reaches the emulator.
            // Without this, libvterm's inactive-buffer resize fills new cells
            // using the active buffer's drawing pen — leaking a mid-redraw
            // foreground app's pen colour into the primary buffer.
            //
            // #554: a row GROW on the reflow-to-keyboard path is what a
            // keyboard hide looks like, and Android hides the IME a few
            // hundred ms BEFORE the pause when the app is being backgrounded
            // (#515), while the window is still focused. Waiting the longer
            // debounce lets the focus loss arrive first, so a backgrounding
            // round-trip with the keyboard up never SIGWINCHes the guest
            // (zellij/tmux would repaint on return for no visible reason).
            // The cost is a user-initiated keyboard dismiss reflowing ~300ms
            // later in a full-screen TUI.
            if (initialResizeDone) {
                delay(resizeDebounceMs(reflowToKeyboard, growingRows = newRows > dimensions.rows))
                if (!windowInfo.isWindowFocused) {
                    // Backgrounded (or another window took focus) with a
                    // resize pending: hold it. On return, the #515 IME
                    // restore re-shrinks the insets, which changes
                    // sizingHeight and cancels this effect — the rerun sees
                    // unchanged dimensions and no resize happens at all. The
                    // grace only expires (and the resize applies) when no
                    // restore is coming, e.g. the keyboard was genuinely
                    // dismissed or a physical keyboard is attached.
                    snapshotFlow { windowInfo.isWindowFocused }.first { it }
                    delay(IME_RESTORE_GRACE_MS)
                }
            }

            terminalEmulator.resize(newRows, newCols)
            initialResizeDone = true

            // If selection is active, ensure it stays within the new visible bounds.
            // This ensures the Copy button resets to the last visible line when the screen
            // shrinks (e.g. keyboard up) without forcing a scroll to the bottom.
            if (selectionManager.mode != SelectionMode.NONE) {
                selectionManager.clampToDimensions(newRows, newCols)
            }
        }

        // Use base dimensions for terminal sizing (not zoomed dimensions).
        // Rows from sizingHeight: keyboard-independent on the primary buffer,
        // visible-height-following on the alternate buffer (see above).
        val newCols =
            forcedSize?.second ?: charsPerDimension(availableWidth, baseCharWidth)
        val newRows =
            forcedSize?.first ?: charsPerDimension(sizingHeight, baseCharHeight)

        // Auto-scroll to bottom when new content arrives (if not manually scrolled).
        //
        // Two cases where we need to snap back to the bottom:
        // 1. scrollbackPosition drifted to a small non-zero value due to a brief
        //    accidental touch (gesture set it to 1–2 lines, then updateSnapshot
        //    adjusted it upward as scrollback grew). Without this, the terminal
        //    stays stuck showing stale scrollback content mixed with the current
        //    screen — visible as green blocks and garbled text during animations.
        // 2. scrollbackPosition became non-zero due to updateSnapshot adjusting
        //    it after a resize that changed scrollback size.
        //
        // We auto-scroll only when the user is NOT actively scrolling (scrollOffset
        // animation not running) and the position is "close" to the bottom — a
        // small drift from 0 that the user didn't intentionally create by a long
        // sustained scroll gesture.
        LaunchedEffect(screenState.snapshot.lines.size, screenState.snapshot.scrollback.size) {
            val pos = screenState.scrollbackPosition
            if (pos in 1..3 && !scrollOffset.isRunning) {
                // Small drift — snap back to bottom
                screenState.scrollToBottom()
                scrollOffset.snapTo(0f)
            }
        }

        // Sync scrollOffset when scrollbackPosition changes externally (but not during user scrolling)
        //
        // "Externally" is load-bearing (#542): the drag path derives the position
        // FROM the offset with an integer floor, so during a drag the offset is
        // always within one line of position*charHeight — and snapping it to the
        // floor here hands back the fraction the floor just took. On an upward
        // drag those two floors compound into a full line lost per pointer
        // event (~10x the finger), which is the runaway-scroll bug. A real
        // external move (scroll controller, keyboard reset, output shift) puts
        // the offset a whole line or more away — only then reconcile.
        LaunchedEffect(screenState.scrollbackPosition) {
            val targetOffset = screenState.scrollbackPosition * baseCharHeight
            if (!scrollOffset.isRunning &&
                kotlin.math.abs(scrollOffset.value - targetOffset) >= baseCharHeight
            ) {
                scrollOffset.snapTo(targetOffset)
            }
        }

        // True while a selection handle is being dragged. Like
        // selectionManager.isSelecting for the long-press path, this tells the
        // scrollback LaunchedEffect below to leave the anchors alone — the
        // handle-drag edge-scroll ticker shifts them explicitly instead.
        var handleDragActive by remember { mutableStateOf(false) }

        // Keep an existing (non-actively-extending) selection anchored on
        // its logical lines while the viewport scrolls. Mirrors the
        // desktop-terminal behaviour where scrolling never disturbs the
        // current selection. Skipped while the user is mid-drag-extend
        // (selectionManager.isSelecting) or dragging a handle
        // (handleDragActive) — the gesture handler manages the anchors
        // explicitly there to avoid double-shifting.
        var lastScrollbackPos by remember { mutableIntStateOf(screenState.scrollbackPosition) }
        LaunchedEffect(screenState.scrollbackPosition) {
            val newPos = screenState.scrollbackPosition
            val delta = newPos - lastScrollbackPos
            lastScrollbackPos = newPos
            if (delta != 0 &&
                selectionManager.mode != SelectionMode.NONE &&
                !selectionManager.isSelecting &&
                !handleDragActive
            ) {
                selectionManager.shiftSelectionByRows(delta)
            }
        }

        // Calculate actual terminal dimensions in pixels
        val terminalWidthPx = newCols * baseCharWidth
        val terminalHeightPx = newRows * baseCharHeight

        // Live visible viewport height (shrinks while the keyboard is up).
        // Used for edge-scroll zone detection, which works in viewport space.
        // rememberUpdatedState so the long-lived gesture lambda sees it live.
        val visibleViewportPx by rememberUpdatedState(availableHeight.toFloat())

        // How far (px) to shift the rendered grid up so the cursor stays
        // visible while the soft keyboard is up. The emulator is sized for the
        // keyboard-hidden height (maxAvailableHeight), so when the keyboard
        // shrinks the viewport the grid is taller than what's visible. Rather
        // than resize the PTY (which made fancy-prompt shells repaint and jump
        // to the bottom — issue #206), shift the render up by this much. Scroll
        // just enough to keep the cursor row at the bottom edge, capped at the
        // covered height: a bottom-anchored shell prompt then sits right above
        // the keyboard, while a full-screen TUI with a higher cursor isn't
        // scrolled off the top. The render translate uses this and touch
        // hit-testing adds it back; 0 when the keyboard is hidden.
        // rememberUpdatedState so the gesture lambda reads the live value.
        val keyboardCoveredPx by rememberUpdatedState(
            if (reflowToKeyboard) {
                // PTY reflowed to the keyboard-shrunk height (resize), so the
                // content already fits above the keyboard — no render shift,
                // and the top status row can't be pushed off the top.
                0f
            } else {
                run {
                    val covered = (maxAvailableHeight - availableHeight).coerceAtLeast(0).toFloat()
                    // Bottom-most non-blank row — what the user expects pinned above
                    // the keyboard. Scanned only when a shift might apply (keyboard
                    // up, primary buffer, at the live bottom); 0 otherwise so the
                    // common keyboard-down path skips the row sweep entirely.
                    val lastContentRow =
                        if (covered <= 0f || screenState.snapshot.altScreen ||
                            screenState.scrollbackPosition != 0
                        ) {
                            screenState.snapshot.cursorRow
                        } else {
                            (screenState.snapshot.rows - 1 downTo 0)
                                .firstOrNull { screenState.getVisibleLine(it).text.isNotBlank() }
                                ?: screenState.snapshot.cursorRow
                        }
                    keyboardCoveredShiftPx(
                        covered = covered,
                        altScreen = screenState.snapshot.altScreen,
                        scrollbackPosition = screenState.scrollbackPosition,
                        cursorRow = screenState.snapshot.cursorRow,
                        lastContentRow = lastContentRow,
                        charHeight = baseCharHeight,
                        availableHeight = availableHeight,
                    )
                }
            },
        )

        val handleClassifiedTwoFingerSwipe: (Float, Float, Float) -> Unit = { deltaY, x, y ->
            if (deltaY == 0f) {
                indirectSwipeAccumY[0] = 0f
            } else if (gestureCallback != null) {
                // Match the ordinary touch-drag path: turn physical travel into
                // terminal wheel steps for tmux/vim/etc.
                indirectSwipeAccumY[0] += deltaY
                while (kotlin.math.abs(indirectSwipeAccumY[0]) >= scrollThreshold) {
                    val scrollUp = indirectSwipeAccumY[0] > 0
                    indirectSwipeAccumY[0] +=
                        if (scrollUp) -scrollThreshold else scrollThreshold
                    val col = (x / currentCharWidth.value).toInt()
                        .coerceIn(0, screenState.snapshot.cols - 1)
                    val row = ((y + keyboardCoveredPx) / currentCharHeight.value).toInt()
                        .coerceIn(0, screenState.snapshot.rows - 1)
                    val consumed = gestureCallback.onScroll(col, row, scrollUp)
                    if (!consumed) {
                        screenState.scrollBy(if (scrollUp) 1 else -1)
                        coroutineScope.launch {
                            scrollOffset.snapTo(
                                screenState.scrollbackPosition * currentCharHeight.value,
                            )
                        }
                    }
                }
            } else {
                // Local terminal scrollback remains pixel-smooth, exactly like
                // a direct touch drag.
                val newOffset =
                    (scrollOffset.value + deltaY).coerceIn(0f, currentMaxScroll.value)
                coroutineScope.launch { scrollOffset.snapTo(newOffset) }
                val lines = (newOffset / currentCharHeight.value).toInt()
                screenState.scrollBy(lines - screenState.scrollbackPosition)
            }
        }

        // Draw terminal content with context menu overlay
        Box(
            modifier = (
                if (forcedSize != null) {
                    // Add border outside the terminal content
                    Modifier
                        .size(
                            width = with(density) { terminalWidthPx.toDp() },
                            height = with(density) { terminalHeightPx.toDp() },
                        )
                        .border(
                            width = TERMINAL_BORDER_WIDTH,
                            color = Color(0xFF4CAF50).copy(alpha = 0.6f),
                        )
                } else {
                    Modifier.fillMaxSize()
                }
                )
                .onGloballyPositioned { contentOriginInRoot = it.positionInRoot() }
                // #419: advertise the terminal as vertically scrollable. Some
                // OEM touchpad drivers (seen on OPPO/ColorOS) only route a
                // two-finger touchpad scroll to a view that reports scroll
                // semantics — the way a Compose list does — so without this the
                // wheel/scroll handler below never receives the event even
                // though the app's own lists scroll fine. The synthesised
                // ACTION_SCROLL is still handled by the pointerInput below; the
                // scrollBy action here is the accessibility-driven fallback and
                // makes the terminal properly scrollable to TalkBack too.
                .semantics {
                    verticalScrollAxisRange = ScrollAxisRange(
                        value = { screenState.scrollbackPosition.toFloat() },
                        maxValue = { screenState.snapshot.scrollback.size.toFloat() },
                        reverseScrolling = true,
                    )
                    scrollBy { _, y ->
                        // Compose convention: +y scrolls toward the end (newer,
                        // toward the bottom). Our scrollBy raises the scrollback
                        // position to go back in history, so invert. Read the
                        // char height through State so a pinch-zoom can't leave
                        // this lambda mapping with stale metrics (see line ~700).
                        val ch = currentCharHeight.value
                        val lines = if (ch > 0f) (y / ch).toInt() else 0
                        if (lines != 0) scrollController.scrollBy(-lines)
                        true
                    }
                }
                // ColorOS can deliver an official two-finger swipe through the
                // host View's touch dispatcher while Compose excludes it from
                // both ordinary pointerInput and the indirect pointer node.
                // Read that raw stream before Compose re-classifies it.
                .pointerInteropFilter { native ->
                    val isTwoFingerSwipe =
                        android.os.Build.VERSION.SDK_INT >= 29 &&
                            native.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
                    if (!isTwoFingerSwipe) {
                        false
                    } else {
                        when (native.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                interopTwoFingerLastY[0] = native.y
                                handleClassifiedTwoFingerSwipe(0f, native.x, native.y)
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                val previousY = interopTwoFingerLastY[0]
                                interopTwoFingerLastY[0] = native.y
                                if (!previousY.isNaN()) {
                                    handleClassifiedTwoFingerSwipe(
                                        native.y - previousY,
                                        native.x,
                                        native.y,
                                    )
                                }
                                true
                            }

                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL,
                            -> {
                                interopTwoFingerLastY[0] = Float.NaN
                                true
                            }

                            else -> false
                        }
                    }
                }
                // Hardware mouse wheel. It needs its own handler: a wheel event
                // carries no pressed pointer, so the gesture loop below — which
                // opens with awaitFirstDown() — never wakes for one, and the
                // wheel was simply dead (a click-drag worked, because that does
                // produce a down). Each notch is routed through the SAME
                // onScroll callback a swipe uses, so the host keeps making the
                // decision: forward to a mouse-tracking app (tmux/vim/less),
                // send arrow keys on the alt screen, or fall through to our own
                // scrollback here.
                .pointerInput(terminalEmulator, gestureCallback) {
                    val baseCharWidth by currentCharWidth
                    val baseCharHeight by currentCharHeight
                    val notches = WheelNotchAccumulator()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val steps = notches.feed(change.scrollDelta.y)
                            // Consume even a banked fraction, so a high-res wheel
                            // can't also drive an ancestor scroller.
                            change.consume()
                            if (steps == 0) continue

                            val col = (change.position.x / baseCharWidth).toInt()
                                .coerceIn(0, screenState.snapshot.cols - 1)
                            val row = ((change.position.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                .coerceIn(0, screenState.snapshot.rows - 1)
                            val scrollUp = steps > 0

                            repeat(kotlin.math.abs(steps)) {
                                // One wheel report per notch — xterm semantics; the
                                // remote app decides how far that scrolls.
                                val consumed = gestureCallback?.onScroll(col, row, scrollUp) ?: false
                                if (!consumed) {
                                    screenState.scrollBy(
                                        if (scrollUp) WHEEL_SCROLLBACK_ROWS else -WHEEL_SCROLLBACK_ROWS,
                                    )
                                    coroutineScope.launch {
                                        scrollOffset.snapTo(
                                            screenState.scrollbackPosition * baseCharHeight,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // #419: a physical touchpad's two-finger scroll arrives on the
                // *indirect* pointer channel, which the wheel pointerInput above
                // never sees. Mirror its body so touchpad scroll drives scrollback
                // (or forwards to a mouse-tracking app) identically.
                .indirectVerticalScroll(
                    onScrollNotches = { steps, x, y ->
                        val col = (x / currentCharWidth.value).toInt()
                            .coerceIn(0, screenState.snapshot.cols - 1)
                        val row = ((y + keyboardCoveredPx) / currentCharHeight.value).toInt()
                            .coerceIn(0, screenState.snapshot.rows - 1)
                        val scrollUp = steps > 0
                        repeat(kotlin.math.abs(steps)) {
                            val consumed = gestureCallback?.onScroll(col, row, scrollUp) ?: false
                            if (!consumed) {
                                screenState.scrollBy(
                                    if (scrollUp) WHEEL_SCROLLBACK_ROWS else -WHEEL_SCROLLBACK_ROWS,
                                )
                                coroutineScope.launch {
                                    scrollOffset.snapTo(
                                        screenState.scrollbackPosition * currentCharHeight.value,
                                    )
                                }
                            }
                        }
                    },
                    onTwoFingerSwipe = { deltaY, x, y ->
                        handleClassifiedTwoFingerSwipe(deltaY, x, y)
                    },
                )
                .pointerInput(terminalEmulator, gestureCallback) {
                    // Shadow the composition-scope metrics with fresh State reads:
                    // this block's closure outlives any pinch-zoom font change.
                    val baseCharWidth by currentCharWidth
                    val baseCharHeight by currentCharHeight
                    val maxScroll by currentMaxScroll
                    val touchSlopSquared =
                        viewConfiguration.touchSlop * viewConfiguration.touchSlop
                    var lastMultiTouchTime = 0L
                    var lastTapTime = 0L
                    // #435: consecutive taps that share an id are ONE gesture as
                    // far as Compose is concerned — which is the bug the
                    // reporter's log revealed, and impossible to see without it.
                    var gestureIdCounter = 0L
                    coroutineScope {
                        awaitEachGesture {
                            var gestureType: GestureType = GestureType.Undetermined
                            val down = awaitFirstDown(requireUnconsumed = false)

                            // Suppress taps/swipes for a window after pinch-to-zoom ends
                            val sincePinch = System.currentTimeMillis() - lastMultiTouchTime
                            val inPinchCooldown = sincePinch < MULTITOUCH_LIFTOFF_MS

                            // 1. Check if touching a selection handle first
                            if (gestureType == GestureType.Undetermined && selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                                val range = selectionManager.selectionRange
                                if (range != null) {
                                    val (touchingStart, touchingEnd) = isTouchingHandle(
                                        down.position,
                                        range,
                                        baseCharWidth,
                                        baseCharHeight,
                                        keyboardCoveredPx = keyboardCoveredPx,
                                    )
                                    if (touchingStart || touchingEnd) {
                                        gestureType = GestureType.HandleDrag
                                        // Handle drag
                                        showMagnifier = true
                                        magnifierPosition = down.position
                                        handleDragActive = true

                                        // Local variable to keep track of which handle we are moving in case they cross
                                        var isMovingStart = touchingStart
                                        // Latest pointer position, fed to the
                                        // edge-scroll ticker below.
                                        var handleDragPosition = down.position

                                        // Re-place the dragged handle at the current
                                        // pointer position. Shared by the drag loop and
                                        // the edge-scroll ticker.
                                        fun applyHandleAt(pos: Offset) {
                                            val newCol =
                                                (pos.x / baseCharWidth).toInt()
                                                    .coerceIn(0, screenState.snapshot.cols - 1)
                                            val newRow =
                                                ((pos.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                                    .coerceIn(0, screenState.snapshot.rows - 1)

                                            val current = selectionManager.selectionRange
                                                ?: return
                                            val result = applyHandleDrag(
                                                startRow = current.startRow,
                                                startCol = current.startCol,
                                                endRow = current.endRow,
                                                endCol = current.endCol,
                                                isMovingStart = isMovingStart,
                                                newRow = newRow,
                                                newCol = newCol,
                                            )
                                            isMovingStart = result.isMovingStart
                                            selectionManager.updateSelectionStart(result.startRow, result.startCol)
                                            selectionManager.updateSelectionEnd(result.endRow, result.endCol)
                                            selectionManager.adjustSelectionForMode(
                                                screenState.snapshot.cols,
                                                screenState.snapshot,
                                                screenState.scrollbackPosition,
                                            )
                                        }

                                        // Auto-repeat edge-scroll while the handle sits
                                        // in the top/bottom edge zone. Unlike the
                                        // Selection/MouseDrag paths there is no inline
                                        // edge-scroll for handle drags, so this ticker is
                                        // the sole driver and runs whether the handle is
                                        // moving or held still. The scrollback
                                        // LaunchedEffect is gated off via handleDragActive,
                                        // so shift both anchors here to keep the selection
                                        // pinned to its content, then re-place the dragged
                                        // handle under the (possibly stationary) finger. (#94)
                                        val handleEdgeScrollJob = launch {
                                            while (true) {
                                                delay(EDGE_SCROLL_TICK_MS)
                                                if (selectionManager.selectionRange == null) continue
                                                val viewportH =
                                                    visibleViewportPx
                                                val dir = edgeScrollDirection(
                                                    handleDragPosition.y,
                                                    viewportH,
                                                    screenState.scrollbackPosition,
                                                    screenState.snapshot.scrollback.size,
                                                )
                                                if (dir == EdgeScroll.NONE) continue
                                                // Velocity scales with how deep the
                                                // finger is in the edge zone — at the
                                                // inner edge of the zone the scroll
                                                // rate matches the old fixed 1 row/tick,
                                                // at the screen edge it caps at
                                                // MAX_EDGE_SCROLL_ROWS rows/tick.
                                                val rows = edgeScrollRowsPerTick(
                                                    handleDragPosition.y,
                                                    viewportH,
                                                    dir,
                                                )
                                                val delta = if (dir == EdgeScroll.UP) +rows else -rows
                                                screenState.scrollBy(delta)
                                                selectionManager.shiftSelectionByRows(delta)
                                                scrollOffset.snapTo(
                                                    screenState.scrollbackPosition * baseCharHeight,
                                                )
                                                applyHandleAt(handleDragPosition)
                                            }
                                        }

                                        try {
                                            drag(down.id) { change ->
                                                handleDragPosition = change.position
                                                applyHandleAt(change.position)
                                                magnifierPosition = change.position
                                                change.consume()
                                            }
                                        } finally {
                                            handleEdgeScrollJob.cancel()
                                            handleDragActive = false
                                        }

                                        // After lifting finger, ensure selection is fully adjusted and handles snap
                                        selectionManager.adjustSelectionForMode(
                                            screenState.snapshot.cols,
                                            screenState.snapshot,
                                            screenState.scrollbackPosition,
                                        )
                                        // Re-sort start/end to reading order once the
                                        // drag commits (start = top-left-most char).
                                        selectionManager.endSelection()

                                        showMagnifier = false
                                        // Don't auto-show menu again after dragging handle
                                        return@awaitEachGesture
                                    }
                                }
                            }

                            // 2. Long press detection
                            // Long-press always starts a Haven-local selection so the
                            // visible Copy/menu workflow works in every session,
                            // including tmux/zellij mouse-mode tabs. The previous
                            // callback-driven path (CALLBACK_LONG_PRESS_MS → onLongPress
                            // right-click and "long-press-then-drag = multiplexer
                            // copy-mode", #186) is retired — tmux's own copy-mode is
                            // still reachable via its Ctrl-B [ keybinding.
                            var longPressDetected = false
                            var gestureEnded = false
                            // #435 instrumentation: measure the gesture rather
                            // than rely on anyone estimating how long they held.
                            val gestureDownMs = System.currentTimeMillis()
                            var gestureMaxMovePx = 0f
                            val gestureId = ++gestureIdCounter
                            fun logGesture(outcome: String) {
                                // Every outcome is logged, not just the first.
                                // Previously one line per gesture meant a gesture
                                // that started a selection never reported how it
                                // ENDED — so the real finger-down duration, the
                                // one measurement the diagnosis needed, was never
                                // recorded at all (#435).
                                // Log.i, not Log.d: release builds strip debug
                                // logging, and this line is the only record of
                                // which path claimed a swipe — the exact datum
                                // a misbehaving-gesture report needs (#542). One
                                // line per gesture outcome, geometry and timing
                                // only, so it is cheap and content-free.
                                Log.i(
                                    "HavenGesture",
                                    gestureLogLine(
                                        outcome = outcome,
                                        gestureId = gestureId,
                                        sinceDownMs = System.currentTimeMillis() - gestureDownMs,
                                        thresholdMs = longPressDelayMs(
                                            mouseMode = currentMouseModeActive,
                                            systemLongPressMs = viewConfiguration.longPressTimeoutMillis,
                                        ),
                                        mouseMode = currentMouseModeActive,
                                        movedPx = gestureMaxMovePx,
                                        touchSlopPx = viewConfiguration.touchSlop,
                                        pointerType = down.type.toString(),
                                    ),
                                )
                            }
                            // Kept (always false) so the existing drag-detection branches
                            // (`if (armMouseDrag)` / `!callbackLongPressFired`) compile
                            // without surgery and fall through to the plain-swipe path.
                            val callbackLongPressFired = false
                            val armMouseDrag = false
                            val callbackLongPressJob: kotlinx.coroutines.Job? = null

                            val longPressJob = launch {
                                // #435: a mouse-mode app owns clicks, so give the
                                // press longer before we steal it for a selection —
                                // otherwise a careful tap on a zellij/tmux tab never
                                // reaches the app.
                                delay(
                                    longPressDelayMs(
                                        mouseMode = currentMouseModeActive,
                                        systemLongPressMs = viewConfiguration.longPressTimeoutMillis,
                                    ),
                                )
                                // Haven-local text selection on long-press. Previously
                                // gated to pure-terminal mode (no gestureCallback) so
                                // the multiplexer could own mouse-mode selection via
                                // long-press-then-drag (#186), but that left tmux/zellij
                                // users with no usable "select text and tap Copy"
                                // workflow — they had to drop into copy-mode via
                                // Ctrl-B [ and lose the visible-on-screen Copy button.
                                // Restored: long-press always starts a Haven local
                                // selection; tmux's own copy-mode remains reachable
                                // via its keybinding. immediateMouseDrag is the
                                // opt-in exception: the remote owns the finger.
                                if (gestureType == GestureType.Undetermined &&
                                    selectionManager.mode == SelectionMode.NONE &&
                                    !gestureEnded &&
                                    !currentImmediateMouseDrag
                                ) {
                                    longPressDetected = true
                                    gestureType = GestureType.Selection
                                    logGesture("selection-started")

                                    // Start selection
                                    val col = (down.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                    val row = ((down.position.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)
                                    selectionManager.startSelection(
                                        row,
                                        col,
                                        screenState.snapshot.cols,
                                        SelectionMode.CHARACTER,
                                    )
                                    hostView.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS,
                                    )
                                    showMagnifier = true
                                    magnifierPosition = down.position
                                }
                            }

                            suspend fun AwaitPointerEventScope.handleMultiTouchGesture() {
                                longPressJob.cancel()
                                callbackLongPressJob?.cancel()

                                // Two fingers: disambiguate pinch-zoom (font size)
                                // from a two-finger pan. Scale divergence => zoom;
                                // parallel vertical motion => scroll; whichever crosses
                                // its threshold first wins and locks for the rest of the
                                // gesture. Opt-in immediate mouse-drag routes the pan to
                                // the remote wheel; OFF mode keeps Haven scrollback.
                                val startFontSize = calculatedFontSize.value
                                var cumulativeZoom = 1f
                                var decideAccumY = 0f
                                var remoteScrollAccumY = 0f
                                var mode = 0 // 0 = undecided, 1 = zoom, 2 = scroll
                                val zoomDecide = 0.08f
                                val panDecidePx = with(density) { 16.dp.toPx() }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { !it.pressed }) break
                                    if (event.changes.size > 1) {
                                        cumulativeZoom *= event.calculateZoom()
                                        val panY = event.calculatePan().y
                                        var classifiedScrollNow = false
                                        if (mode == 0) {
                                            decideAccumY += panY
                                            when {
                                                kotlin.math.abs(1f - cumulativeZoom) > zoomDecide -> {
                                                    mode = 1
                                                    gestureType = GestureType.Zoom
                                                }

                                                kotlin.math.abs(decideAccumY) > panDecidePx -> {
                                                    mode = 2
                                                    gestureType = GestureType.Scroll
                                                    remoteScrollAccumY = decideAccumY
                                                    classifiedScrollNow = true
                                                }
                                            }
                                        }
                                        when (mode) {
                                            1 -> {
                                                val newSize = (startFontSize * cumulativeZoom)
                                                    .coerceIn(MIN_PINCH_FONT_SP, MAX_PINCH_FONT_SP)
                                                calculatedFontSize = newSize.sp
                                            }

                                            2 -> {
                                                if (currentImmediateMouseDrag && gestureCallback != null) {
                                                    if (!classifiedScrollNow) {
                                                        remoteScrollAccumY += panY
                                                    }
                                                    val pointer = event.changes.first { it.pressed }.position
                                                    val (col, row) = terminalCell(
                                                        pointer.x,
                                                        pointer.y,
                                                        baseCharWidth,
                                                        baseCharHeight,
                                                        keyboardCoveredPx,
                                                        screenState.snapshot.cols,
                                                        screenState.snapshot.rows,
                                                    )
                                                    while (kotlin.math.abs(remoteScrollAccumY) >= scrollThreshold) {
                                                        val draggedDown = remoteScrollAccumY > 0f
                                                        remoteScrollAccumY +=
                                                            if (draggedDown) -scrollThreshold else scrollThreshold
                                                        val scrollUp = draggedDown
                                                        gestureCallback.onScroll(col, row, scrollUp)
                                                    }
                                                } else {
                                                    val newOffset = (scrollOffset.value + panY)
                                                        .coerceIn(0f, maxScroll)
                                                    coroutineScope.launch { scrollOffset.snapTo(newOffset) }
                                                    val scrolledLines = (newOffset / baseCharHeight).toInt()
                                                    screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
                                                }
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }

                                if (mode == 1) {
                                    // Persist the new font size; flag prevents the
                                    // LaunchedEffect from resetting before round-trip.
                                    fontSetByPinch = true
                                    onFontSizeChanged?.invoke(calculatedFontSize)
                                }
                                lastMultiTouchTime = System.currentTimeMillis()
                            }

                            // 3. Check for multi-touch (zoom)
                            //
                            // ★This probe CONSUMES the one event it waits for. If that
                            // event is the finger coming back up — a tap whose release
                            // lands inside this window — it matches no second pointer,
                            // is discarded here, and the main loop below then waits
                            // forever for a release that has already happened. The
                            // gesture never ends, and the long-press timer fires into a
                            // finger that is no longer on the screen, producing a text
                            // selection out of nowhere (#435).
                            //
                            // Delivery timing decides whether a real tap lands in the
                            // window, which is why this looked device-specific: an
                            // accessibility service re-injecting touches delivers them
                            // in bunches, so the release arrives far sooner after the
                            // press than a finger could manage.
                            var releasedDuringProbe = false
                            var eventDuringProbe: PointerEvent? = null
                            val secondPointer = withTimeoutOrNull(
                                WAIT_FOR_SECOND_TOUCH_MS,
                            ) {
                                val probed = awaitPointerEvent()
                                eventDuringProbe = probed
                                if (probed.changes.all { !it.pressed }) {
                                    releasedDuringProbe = true
                                }
                                probed.changes.firstOrNull { it.id != down.id && it.pressed }
                            }

                            if (secondPointer != null && forcedSize == null) {
                                handleMultiTouchGesture()
                                return@awaitEachGesture
                            }

                            // 4. Track velocity for scroll fling
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            // 5. Event loop for single-touch gestures
                            // Track accumulated scroll for callback quantization
                            var accumulatedScrollY = 0f
                            // Track whether this is a horizontal drag (tab swipe)
                            var isHorizontalDrag = false
                            // Held-swipe state (#524): the current direction and
                            // the travel accumulated AGAINST it. A reversal only
                            // fires once the counter-travel passes touch slop, so
                            // finger jitter doesn't flicker the direction.
                            var swipeHoldDirection = SwipeHoldDirection.Up
                            var swipeHoldFlipAccum = 0f
                            // Last cell coordinates dispatched as a MouseDrag(Move),
                            // used to quantize per-pixel events down to per-cell.
                            var lastMouseDragCol = -1
                            var lastMouseDragRow = -1
                            var mouseDragStarted = false
                            var mouseDragEnded = false
                            fun endMouseDragIfStarted() {
                                if (!mouseDragStarted || mouseDragEnded) return
                                mouseDragEnded = true
                                val (fallbackCol, fallbackRow) = terminalCell(
                                    down.position.x,
                                    down.position.y,
                                    baseCharWidth,
                                    baseCharHeight,
                                    keyboardCoveredPx,
                                    screenState.snapshot.cols,
                                    screenState.snapshot.rows,
                                )
                                val endCol = if (lastMouseDragCol >= 0) lastMouseDragCol else fallbackCol
                                val endRow = if (lastMouseDragRow >= 0) lastMouseDragRow else fallbackRow
                                gestureCallback?.onMouseDrag(endCol, endRow, MouseDragPhase.End)
                            }

                            fun startMouseDrag(col: Int, row: Int): Boolean {
                                if (gestureCallback?.onMouseDrag(col, row, MouseDragPhase.Start) != true) {
                                    return false
                                }
                                gestureType = GestureType.MouseDrag
                                mouseDragStarted = true
                                lastMouseDragCol = col
                                lastMouseDragRow = row
                                return true
                            }

                            try {
                                // Latest pointer position + timestamp, fed to the
                                // held-still edge-scroll ticker below.
                                var lastDragPosition = down.position
                                var lastDragEventTime = 0L

                            // Auto-repeat edge-scroll for a finger held still in the
                            // edge zone. The pointer-event loop only fires while the
                            // finger moves; this ticker covers the stationary case for
                            // Selection and MouseDrag. Cancelled in section 6. (#94)
                            val edgeAutoScrollJob = launch {
                                while (true) {
                                    delay(EDGE_SCROLL_TICK_MS)
                                    // If a pointer event arrived within the last tick the
                                    // inline edge-scroll already handled it — only act as
                                    // the held-still fallback.
                                    if (System.currentTimeMillis() - lastDragEventTime <
                                        EDGE_SCROLL_TICK_MS
                                    ) {
                                        continue
                                    }
                                    val viewportH = visibleViewportPx
                                    if (viewportH <= 0f) continue
                                    val (dragCol, dragRow) = terminalCell(
                                        lastDragPosition.x,
                                        lastDragPosition.y,
                                        baseCharWidth,
                                        baseCharHeight,
                                        keyboardCoveredPx,
                                        screenState.snapshot.cols,
                                        screenState.snapshot.rows,
                                    )
                                    when (gestureType) {
                                        GestureType.Selection -> {
                                            if (!selectionManager.isSelecting) continue
                                            val dir = edgeScrollDirection(
                                                lastDragPosition.y,
                                                viewportH,
                                                screenState.scrollbackPosition,
                                                screenState.snapshot.scrollback.size,
                                            )
                                            if (dir == EdgeScroll.NONE) continue
                                            // Mouse-mode selections forward the wheel event;
                                            // native selections scroll our own viewport and
                                            // shift the start anchor in lockstep.
                                            val handled = gestureCallback
                                                ?.onScroll(dragCol, dragRow, dir == EdgeScroll.UP)
                                                ?: false
                                            if (!handled) {
                                                val rows = edgeScrollRowsPerTick(
                                                    lastDragPosition.y,
                                                    viewportH,
                                                    dir,
                                                )
                                                val delta = if (dir == EdgeScroll.UP) +rows else -rows
                                                screenState.scrollBy(delta)
                                                selectionManager.shiftSelectionStartByRows(delta)
                                                scrollOffset.snapTo(
                                                    screenState.scrollbackPosition * baseCharHeight,
                                                )
                                            }
                                            selectionManager.updateSelection(dragRow, dragCol)
                                        }

                                        GestureType.MouseDrag -> {
                                            if (!currentImmediateMouseDrag) {
                                                endMouseDragIfStarted()
                                                gestureType = GestureType.Ignored
                                                continue
                                            }
                                            // Keep sending held motion while stationary at an
                                            // edge. Remote scroll containers use drag position
                                            // for their own symmetric auto-scroll. Interleaving
                                            // wheel packets breaks a nested mouse state machine:
                                            // Zellij forgets the held button on wheel, then
                                            // reclassifies the next motion as a new press.
                                            val cb = gestureCallback ?: continue
                                            val relY = lastDragPosition.y / viewportH
                                            if (relY < EDGE_SCROLL_ZONE ||
                                                relY > 1f - EDGE_SCROLL_ZONE
                                            ) {
                                                cb.onMouseDrag(dragCol, dragRow, MouseDragPhase.Move)
                                                lastMouseDragCol = dragCol
                                                lastMouseDragRow = dragRow
                                             }
                                        }

                                        else -> {}
                                    }
                                }
                            }

                            // Skipped when the release was already seen (and consumed) by the
                            // multi-touch probe above: waiting for it again is what hung the
                            // gesture. Falling straight through leaves gestureType
                            // Undetermined, so the tap is still delivered below (#435).
                            var pendingPointerEvent = eventDuringProbe
                            while (!releasedDuringProbe) {
                                val event = pendingPointerEvent
                                    ?.also { pendingPointerEvent = null }
                                    ?: awaitPointerEvent(PointerEventPass.Main)
                                if (event.changes.all { !it.pressed }) break
                                if (forcedSize == null &&
                                    event.changes.count { it.pressed } > 1 &&
                                    (currentImmediateMouseDrag || gestureType == GestureType.MouseDrag)
                                ) {
                                    val previousGestureType = gestureType
                                    // Stop the edge ticker before handing the remaining
                                    // gesture to Haven-local two-finger pan/zoom.
                                    gestureType = GestureType.Zoom
                                    if (previousGestureType == GestureType.MouseDrag) {
                                        endMouseDragIfStarted()
                                    } else if (previousGestureType == GestureType.SwipeHold) {
                                        gestureCallback?.onSwipeHoldEnd()
                                    }
                                    edgeAutoScrollJob.cancel()
                                    handleMultiTouchGesture()
                                    return@awaitEachGesture
                                }

                                val change = event.changes.first()
                                // Farthest the finger strayed from where it landed —
                                // a "short tap" that drifts past touch slop is a drag,
                                // and the log has to be able to show that (#435).
                                gestureMaxMovePx = maxOf(
                                    gestureMaxMovePx,
                                    (change.position - down.position).getDistance(),
                                )
                                velocityTracker.addPosition(
                                    change.uptimeMillis,
                                    change.position,
                                )
                                // Use raw position delta — positionChange() may return
                                // zero if pagerSwipeOverride consumed on Initial pass.
                                val dragAmount = change.position - change.previousPosition

                                // Feed the held-still edge-scroll ticker the latest
                                // pointer position and timestamp.
                                lastDragPosition = change.position
                                lastDragEventTime = System.currentTimeMillis()

                                // Determine gesture if still undetermined.
                                // Use total distance from touch-down (not per-frame delta)
                                // so moderate-speed scrolling still triggers classification.
                                // Skip once a native selection has started (longPressDetected)
                                // — the user is holding intentionally and movement extends it.
                                if (gestureType == GestureType.Undetermined && !longPressDetected) {
                                    val totalDx = change.position.x - down.position.x
                                    val totalDy = change.position.y - down.position.y
                                    if (totalDx * totalDx + totalDy * totalDy > touchSlopSquared) {
                                        longPressJob.cancel()
                                        callbackLongPressJob?.cancel()
                                        val (downCol, downRow) = terminalCell(
                                            down.position.x,
                                            down.position.y,
                                            baseCharWidth,
                                            baseCharHeight,
                                            keyboardCoveredPx,
                                            screenState.snapshot.cols,
                                            screenState.snapshot.rows,
                                        )
                                        val immediateDragClaimed = currentImmediateMouseDrag &&
                                            startMouseDrag(downCol, downRow)
                                        if (immediateDragClaimed) {
                                            // The current event is handled as MouseDrag below,
                                            // so Start is followed by its first cell Move.
                                        } else if (armMouseDrag) {
                                            // Long-press-then-drag → forward the drag so the
                                            // remote (tmux/zellij) runs its own pane-aware
                                            // copy-mode selection. Falls back to Scroll if the
                                            // callback declines (e.g. mouse input disabled). (#186)
                                            if (!startMouseDrag(downCol, downRow)) {
                                                gestureType = GestureType.Scroll
                                            }
                                        } else if (!callbackLongPressFired) {
                                            // Plain one-finger swipe (no long-press).
                                            val absDx = kotlin.math.abs(totalDx)
                                            val absDy = kotlin.math.abs(totalDy)
                                            if (absDx > absDy) {
                                                if (selectionManager.mode != SelectionMode.NONE) {
                                                    selectionManager.clearSelection()
                                                }
                                                // Offer the swipe as a held gesture first
                                                // (#524): a host in swipe-arrows mode claims
                                                // it and owns ←/→ key repeat; unclaimed
                                                // horizontal drags stay with the pager.
                                                val holdDir = if (totalDx > 0) SwipeHoldDirection.Right else SwipeHoldDirection.Left
                                                if (gestureCallback?.onSwipeHoldStart(downCol, downRow, holdDir) == true) {
                                                    swipeHoldDirection = holdDir
                                                    swipeHoldFlipAccum = 0f
                                                    gestureType = GestureType.SwipeHold
                                                } else {
                                                    // Horizontal drag — let pager handle
                                                    isHorizontalDrag = true
                                                }
                                            } else {
                                                // Vertical swipe → Scroll. In mouse mode this
                                                // forwards the wheel to the app, scrolling the
                                                // multiplexer pane; with no callback it scrolls
                                                // Haven's local scrollback. A copy-mode selection
                                                // now needs an explicit long-press first
                                                // (armMouseDrag above), so a one-finger swipe is
                                                // always a scroll and the two no longer fight
                                                // over the same gesture. (#186)
                                                // Offer as a held gesture first (#524);
                                                // unclaimed swipes keep quantized Scroll.
                                                val holdDir = if (totalDy < 0) SwipeHoldDirection.Up else SwipeHoldDirection.Down
                                                if (gestureCallback?.onSwipeHoldStart(downCol, downRow, holdDir) == true) {
                                                    swipeHoldDirection = holdDir
                                                    swipeHoldFlipAccum = 0f
                                                    gestureType = GestureType.SwipeHold
                                                } else {
                                                    gestureType = GestureType.Scroll
                                                }
                                            }
                                        }
                                        // else: a right-click long-press already fired and was
                                        // consumed — ignore the trailing drag.
                                    }
                                }

                                // Handle based on gesture type
                                when (gestureType) {
                                    GestureType.Selection -> {
                                        if (selectionManager.isSelecting) {
                                            val dragCol =
                                                (change.position.x / baseCharWidth).toInt()
                                                    .coerceIn(0, screenState.snapshot.cols - 1)
                                            val dragRow =
                                                ((change.position.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                                    .coerceIn(0, screenState.snapshot.rows - 1)

                                            // Edge-zone extension. Two paths:
                                            //  1. Mouse-mode (callback present) — forward
                                            //     wheel events to the remote so e.g. tmux
                                            //     copy-mode auto-scrolls and extends its
                                            //     own selection.
                                            //  2. Native (no callback) — scroll our own
                                            //     viewport one line and shift the selection
                                            //     anchor in lockstep so it stays on the
                                            //     same logical content line. This is what
                                            //     lets a drag-select extend off the top of
                                            //     the viewport into scrollback (#94).
                                            val relY = change.position.y /
                                                (visibleViewportPx)
                                            val nearTop = relY < EDGE_SCROLL_ZONE
                                            val nearBottom = relY > 1f - EDGE_SCROLL_ZONE
                                            val callbackHandled = if ((nearTop || nearBottom) && gestureCallback != null) {
                                                gestureCallback.onScroll(dragCol, dragRow, nearTop)
                                            } else {
                                                false
                                            }

                                            if (!callbackHandled) {
                                                if (nearTop && screenState.scrollbackPosition < screenState.snapshot.scrollback.size) {
                                                    screenState.scrollBy(+1)
                                                    selectionManager.shiftSelectionStartByRows(+1)
                                                    coroutineScope.launch {
                                                        scrollOffset.snapTo(
                                                            screenState.scrollbackPosition * baseCharHeight,
                                                        )
                                                    }
                                                } else if (nearBottom && screenState.scrollbackPosition > 0) {
                                                    screenState.scrollBy(-1)
                                                    selectionManager.shiftSelectionStartByRows(-1)
                                                    coroutineScope.launch {
                                                        scrollOffset.snapTo(
                                                            screenState.scrollbackPosition * baseCharHeight,
                                                        )
                                                    }
                                                }
                                            }

                                            selectionManager.updateSelection(
                                                dragRow,
                                                dragCol,
                                            )
                                            magnifierPosition = change.position
                                        }
                                    }

                                    GestureType.MouseDrag -> {
                                        if (!currentImmediateMouseDrag) {
                                            endMouseDragIfStarted()
                                            gestureType = GestureType.Ignored
                                        } else {
                                            // Quantize motion to cell boundaries — the remote
                                            // (tmux et al.) only cares about cell-resolution
                                            // changes; sending per-pixel events would flood the
                                            // wire and make tmux's selection-extension stutter.
                                            val (dragCol, dragRow) = terminalCell(
                                                change.position.x,
                                                change.position.y,
                                                baseCharWidth,
                                                baseCharHeight,
                                                keyboardCoveredPx,
                                                screenState.snapshot.cols,
                                                screenState.snapshot.rows,
                                            )
                                            // Force held motion in either edge zone even when
                                            // cell quantization would suppress an unchanged edge
                                            // cell. The remote scroll container owns drag-edge
                                            // auto-scroll; wheel must not interrupt this held
                                            // button stream (see the stationary path above).
                                            val relY = change.position.y / visibleViewportPx
                                            val inEdgeZone = relY < EDGE_SCROLL_ZONE ||
                                                relY > 1f - EDGE_SCROLL_ZONE
                                            if (gestureCallback != null && inEdgeZone) {
                                                gestureCallback.onMouseDrag(
                                                    dragCol,
                                                    dragRow,
                                                    MouseDragPhase.Move,
                                                )
                                                lastMouseDragCol = dragCol
                                                lastMouseDragRow = dragRow
                                            } else if (dragCol != lastMouseDragCol ||
                                                dragRow != lastMouseDragRow
                                            ) {
                                                gestureCallback?.onMouseDrag(
                                                    dragCol,
                                                    dragRow,
                                                    MouseDragPhase.Move,
                                                )
                                                lastMouseDragCol = dragCol
                                                lastMouseDragRow = dragRow
                                            }
                                        }
                                    }

                                    GestureType.Scroll -> {
                                        if (gestureCallback != null) {
                                            // Quantized scroll: accumulate drag and fire the
                                            // callback for each quantum crossed. The quantum is
                                            // the host-scaled callbackScrollThreshold — coarser
                                            // in swipe-arrows mode (#524), identical to
                                            // scrollThreshold otherwise.
                                            accumulatedScrollY += dragAmount.y
                                            while (kotlin.math.abs(accumulatedScrollY) >= callbackScrollThreshold) {
                                                val draggedUp = accumulatedScrollY < 0
                                                accumulatedScrollY += if (draggedUp) callbackScrollThreshold else -callbackScrollThreshold
                                                // Natural scrolling: finger down = scroll up (older content)
                                                val scrollUp = !draggedUp
                                                val col = (change.position.x / baseCharWidth).toInt()
                                                    .coerceIn(0, screenState.snapshot.cols - 1)
                                                val row = ((change.position.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                                    .coerceIn(0, screenState.snapshot.rows - 1)
                                                val consumed = gestureCallback.onScroll(col, row, scrollUp)
                                                if (!consumed) {
                                                    // Callback didn't handle it — do scrollback
                                                    val scrollDir = if (scrollUp) 1 else -1
                                                    screenState.scrollBy(scrollDir)
                                                    coroutineScope.launch {
                                                        scrollOffset.snapTo(
                                                            screenState.scrollbackPosition * baseCharHeight,
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // No callback: smooth pixel-level scrollback
                                            val newOffset = (scrollOffset.value + dragAmount.y)
                                                .coerceIn(0f, maxScroll)
                                            coroutineScope.launch {
                                                scrollOffset.snapTo(newOffset)
                                            }

                                            // Update terminal buffer scrollback position
                                            val scrolledLines =
                                                (newOffset / baseCharHeight).toInt()
                                            screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
                                        }
                                    }

                                    GestureType.SwipeHold -> {
                                        // Distance no longer matters (#524): only axis
                                        // reversals do. Accumulate travel AGAINST the
                                        // current direction; a reversal fires once it
                                        // passes touch slop, so jitter can't flicker
                                        // the held key. Same-direction travel resets
                                        // the counter.
                                        val axisDelta = when (swipeHoldDirection) {
                                            SwipeHoldDirection.Up, SwipeHoldDirection.Down -> dragAmount.y
                                            SwipeHoldDirection.Left, SwipeHoldDirection.Right -> dragAmount.x
                                        }
                                        val against = when (swipeHoldDirection) {
                                            SwipeHoldDirection.Up, SwipeHoldDirection.Left -> axisDelta > 0f
                                            SwipeHoldDirection.Down, SwipeHoldDirection.Right -> axisDelta < 0f
                                        }
                                        if (against) {
                                            swipeHoldFlipAccum += kotlin.math.abs(axisDelta)
                                            if (swipeHoldFlipAccum * swipeHoldFlipAccum > touchSlopSquared) {
                                                swipeHoldDirection = when (swipeHoldDirection) {
                                                    SwipeHoldDirection.Up -> SwipeHoldDirection.Down
                                                    SwipeHoldDirection.Down -> SwipeHoldDirection.Up
                                                    SwipeHoldDirection.Left -> SwipeHoldDirection.Right
                                                    SwipeHoldDirection.Right -> SwipeHoldDirection.Left
                                                }
                                                swipeHoldFlipAccum = 0f
                                                gestureCallback?.onSwipeHold(swipeHoldDirection)
                                            }
                                        } else if (axisDelta != 0f) {
                                            swipeHoldFlipAccum = 0f
                                        }
                                    }

                                    else -> {}
                                }

                                // Always consume on Main pass to prevent the
                                // HorizontalPager's scrollable from intercepting
                                // drags. Tab swiping still works because the
                                // pagerSwipeOverride runs on Initial pass (before Main).
                                change.consume()
                            }

                            // 6. Gesture ended - cleanup
                            logGesture("ended-${gestureType.name.lowercase()}")
                            gestureEnded = true
                            longPressJob.cancel()
                            callbackLongPressJob?.cancel()
                            edgeAutoScrollJob.cancel()
                            when (gestureType) {
                                GestureType.Scroll -> {
                                    // Flush any remaining accumulated scroll that didn't
                                    // reach the threshold — ensures small flicks register.
                                    if (gestureCallback != null && kotlin.math.abs(accumulatedScrollY) > callbackScrollThreshold / 3f) {
                                        val scrollUp = accumulatedScrollY > 0 // natural: positive drag = scroll up
                                        val col = (down.position.x / baseCharWidth).toInt()
                                            .coerceIn(0, screenState.snapshot.cols - 1)
                                        val row = ((down.position.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                            .coerceIn(0, screenState.snapshot.rows - 1)
                                        val consumed = gestureCallback.onScroll(col, row, scrollUp)
                                        if (!consumed) {
                                            val scrollDir = if (scrollUp) 1 else -1
                                            screenState.scrollBy(scrollDir)
                                            coroutineScope.launch {
                                                scrollOffset.snapTo(
                                                    screenState.scrollbackPosition * baseCharHeight,
                                                )
                                            }
                                        }
                                    } else if (gestureCallback == null) {
                                        // Apply fling animation (only for non-callback scrollback)
                                        val velocity = velocityTracker.calculateVelocity()
                                        coroutineScope.launch {
                                            var targetValue = scrollOffset.targetValue
                                            scrollOffset.animateDecay(
                                                initialVelocity = velocity.y,
                                                animationSpec = splineBasedDecay(density),
                                            ) {
                                                targetValue = value.coerceIn(0f, maxScroll)
                                                // Update terminal buffer during animation
                                                val scrolledLines =
                                                    (targetValue / baseCharHeight).toInt()
                                                screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
                                            }

                                            // Snap to final clamped position.
                                            // If we settled within 2 lines of the bottom, snap
                                            // fully to 0 — prevents accidental micro-flicks from
                                            // leaving the terminal stuck 1–2 lines scrolled up,
                                            // which causes garbled display during animations.
                                            val threshold = 2f * baseCharHeight
                                            scrollOffset.snapTo(targetValue.coerceIn(0f, maxScroll))
                                            if (targetValue <= threshold) {
                                                screenState.scrollToBottom()
                                                scrollOffset.snapTo(0f)
                                            }
                                        }
                                    }
                                }

                                GestureType.Selection -> {
                                    showMagnifier = false
                                    if (selectionManager.isSelecting) {
                                        selectionManager.endSelection()
                                    }
                                }

                                GestureType.MouseDrag -> {
                                    endMouseDragIfStarted()
                                }

                                GestureType.SwipeHold -> {
                                    // The host's key repeat runs until the finger
                                    // lifts — exactly one End per claimed gesture,
                                    // on release or cancellation alike (#524).
                                    gestureCallback?.onSwipeHoldEnd()
                                }

                                GestureType.Undetermined -> {
                                    // No drag occurred — this is a tap
                                    if (isHorizontalDrag) {
                                        // Horizontal swipe — not a tap (selection already
                                        // cleared during classification above)
                                    } else if (inPinchCooldown) {
                                        // Suppress accidental taps from pinch finger liftoff
                                    } else if (callbackLongPressFired) {
                                        // A long-press fired (haptic given) but the finger
                                        // lifted without dragging — it was a deliberate hold,
                                        // not a tap. Any right-click was already sent in
                                        // onLongPress; don't also emit a tap/click. (#186)
                                    } else if (tapOnlyDismissesSelection(
                                            selectionActive = selectionManager.mode != SelectionMode.NONE,
                                            mouseMode = currentMouseModeActive,
                                        )
                                    ) {
                                        // Nothing else wants this click: dismissing the
                                        // selection is the whole gesture.
                                        selectionManager.clearSelection()
                                    } else {
                                        // In a mouse-mode app the click belongs to the
                                        // remote, so a tap that lands while a selection
                                        // happens to be showing must dismiss it *and*
                                        // still be delivered. Swallowing it makes taps
                                        // look dead until you tap a second time — which
                                        // is exactly what a stolen press leaves you
                                        // doing. (#435)
                                        if (selectionManager.mode != SelectionMode.NONE) {
                                            selectionManager.clearSelection()
                                        }
                                        val tapCol = (down.position.x / baseCharWidth).toInt()
                                            .coerceIn(0, screenState.snapshot.cols - 1)
                                        val tapRow = ((down.position.y + keyboardCoveredPx) / baseCharHeight).toInt()
                                            .coerceIn(0, screenState.snapshot.rows - 1)

                                        // Check hyperlinks first — they take priority over
                                        // mouse mode callbacks so URLs are always tappable.
                                        // Uses screen-state method which joins soft-wrapped
                                        // lines for cross-line URL detection.
                                        val hyperlinkUrl = screenState.getHyperlinkUrlAt(
                                            tapRow,
                                            tapCol,
                                            terminalEmulator.autoDetectUrls,
                                        )

                                        if (hyperlinkUrl != null) {
                                            onHyperlinkClick(hyperlinkUrl)
                                        } else {
                                            // Give callback chance to handle tap (mouse mode)
                                            val callbackHandled = gestureCallback?.onTap(tapCol, tapRow) == true

                                            // Tap-to-position-cursor on an OSC 133 prompt input
                                            // line: synthesise arrow-key dispatches so the
                                            // shell's readline cursor lands at the tapped
                                            // column. Only fires when not in mouse mode, the
                                            // viewport is at the live bottom, and the cursor
                                            // row's segments confirm we're between OSC 133;B
                                            // and the next 133;D for that prompt. Focus still
                                            // happens so the keyboard pops up for further
                                            // typing; only the tap/double-tap callbacks are
                                            // suppressed to avoid e.g. a fullscreen-toggle
                                            // double-tap eating a quick double-click reposition.
                                            val tapPositionedCursor = !callbackHandled &&
                                                tapToPositionCursorOnPrompt &&
                                                screenState.scrollbackPosition == 0 &&
                                                dispatchTapToPositionCursor(
                                                    terminalEmulator,
                                                    screenState.snapshot,
                                                    tapRow,
                                                    tapCol,
                                                )

                                            if (!callbackHandled) {
                                                if (keyboardEnabled) {
                                                    focusRequester.requestFocus()
                                                }
                                                if (!tapPositionedCursor) {
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastTapTime < 300) {
                                                        onTerminalDoubleTap()
                                                        lastTapTime = 0L
                                                    } else {
                                                        onTerminalTap()
                                                        lastTapTime = now
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // Record tap for double-tap detection
                                    tapTracker.lastTimestamp = down.uptimeMillis
                                    tapTracker.lastPosition = down.position
                                }

                                else -> {}
                            }
                            } finally {
                                endMouseDragIfStarted()
                            }
                        }
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Clip to the visible viewport. The row loop is drawn inside
                    // translate(top = -keyboardCoveredPx) (#206); without a clip
                    // a Canvas paints outside its bounds, so the scrolled-up top
                    // rows bled over the status bar. Clipping keeps them inside
                    // the terminal box. No-op when keyboardCoveredPx == 0.
                    .clipToBounds()
                    .clearAndSetSemantics {
                        // Hide Canvas from accessibility tree - AccessibilityOverlay provides semantic structure
                    },
            ) {
                // Fill background. backgroundOpacity < 1 makes only this
                // screen-fill translucent; default/empty cells skip painting
                // (see drawLine's defaultBg check) so they reveal whatever the
                // host renders behind the Canvas (e.g. the device wallpaper via
                // a translucent window). Cell-coloured backgrounds stay opaque.
                drawRect(
                    color = if (backgroundOpacity >= 1f) {
                        backgroundColor
                    } else {
                        backgroundColor.copy(alpha = backgroundOpacity)
                    },
                    size = size,
                )

                // Precompute which rows are part of a multi-line URL group.
                // A row is a URL continuation iff the previous row ends with a
                // URL-safe character (after trimming terminal-width padding)
                // AND this row begins with a URL-safe character **at column 0**
                // — i.e. no leading whitespace. The "no leading whitespace"
                // rule distinguishes a wrapped URL ("…/issu" + "es/78") from
                // a URL followed by a new indented line of prose. Matches the
                // same rule used for tap-to-open in
                // [TerminalScreenState.getHyperlinkUrlAt]; without it, the
                // visual underline would extend onto unrelated text.
                val urlContinuationRows = mutableSetOf<Int>()
                for (row in 1 until screenState.snapshot.rows) {
                    val prev = screenState.getVisibleLine(row - 1).text.trimEnd()
                    val curText = screenState.getVisibleLine(row).text
                    if (prev.isNotEmpty() && curText.isNotEmpty() &&
                        prev.last().isUrlSafe() &&
                        curText[0] != ' ' && curText[0] != '\t' &&
                        curText[0].isUrlSafe()
                    ) {
                        // Check that the chain traces back to a line with an actual URL
                        if ((row - 1) in urlContinuationRows ||
                            screenState.getVisibleLine(row - 1).autoDetectedUrls.isNotEmpty()
                        ) {
                            urlContinuationRows.add(row)
                        }
                    }
                }

                // Shift all drawn content up by the height the soft keyboard
                // covers so the cursor row (bottom of the keyboard-independent
                // grid) stays visible just above the keyboard. The background
                // fill above is intentionally NOT translated — it must cover
                // the whole viewport. Touch hit-testing adds the same offset
                // back (see keyboardCoveredPx). 0 when the keyboard is hidden,
                // so this is a no-op then. (#206)
                translate(top = -keyboardCoveredPx) {
                    // Draw each line
                    for (row in 0 until screenState.snapshot.rows) {
                        val line = screenState.getVisibleLine(row)
                        // For URL continuation rows, compute where the URL-safe
                        // prefix ends: skip leading spaces, then find the first
                        // space after URL-safe content.
                        val urlEndCol = if (row in urlContinuationRows) {
                            val cells = line.cells
                            var i = 0
                            // Skip leading spaces (left margin padding)
                            while (i < cells.size && cells[i].char == ' ') i++
                            // Advance through URL-safe characters
                            while (i < cells.size && cells[i].char.isUrlSafe()) i++
                            i
                        } else {
                            0
                        }
                        drawLine(
                            line = line,
                            row = row,
                            isUrlContinuation = row in urlContinuationRows,
                            urlContinuationEndCol = urlEndCol,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                            defaultFg = foregroundColor,
                            defaultBg = backgroundColor,
                            selectionManager = selectionManager,
                            autoDetectUrls = terminalEmulator.autoDetectUrls,
                            selectionBackgroundColor = selectionBackgroundColor,
                            selectionForegroundColor = selectionForegroundColor,
                        )
                    }

                    // Draw cursor (only when viewing current screen, not scrollback)
                    if (screenState.snapshot.cursorVisible && screenState.scrollbackPosition == 0 && cursorBlinkVisible) {
                        drawCursor(
                            row = screenState.snapshot.cursorRow,
                            col = screenState.snapshot.cursorCol,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            foregroundColor = foregroundColor,
                            backgroundColor = backgroundColor,
                            cursorShape = screenState.snapshot.cursorShape,
                            pendingDeadChar = composeController.pendingDeadChar,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                        )
                    }

                    // Draw compose mode overlay. The accumulated buffer and the
                    // in-flight IME composition render as ONE run — they used to be
                    // two overlays anchored at the same cursor cell, so while an
                    // IME held a live composition (HeliBoard composes every word)
                    // the current word overpainted the buffered ones and the
                    // overlay appeared to "erase" earlier words until Enter.
                    // Gboard commits words quickly, which is why it looked fine
                    // there.
                    if (composeMode.isActive && screenState.scrollbackPosition == 0) {
                        drawComposeOverlay(
                            buffer = composeMode.buffer + imeComposerText,
                            cursorRow = screenState.snapshot.cursorRow,
                            cursorCol = screenState.snapshot.cursorCol,
                            totalCols = screenState.snapshot.cols,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                        )
                    }

                    // Draw IME composer overlay (romaji/pinyin etc. mid-composition).
                    // Rendered at the cursor; the remote shell sees nothing until the
                    // IME calls commitText() with the final selection. In compose
                    // mode the live composition is already folded into the compose
                    // overlay above.
                    if (!composeMode.isActive && imeComposerText.isNotEmpty() && screenState.scrollbackPosition == 0) {
                        drawImeComposerOverlay(
                            text = imeComposerText,
                            cursorRow = screenState.snapshot.cursorRow,
                            cursorCol = screenState.snapshot.cursorCol,
                            totalCols = screenState.snapshot.cols,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                        )
                    }

                    // Draw selection handles
                    if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                        val range = selectionManager.selectionRange
                        if (range != null) {
                            // Start handle
                            val startPosition = range.getStartPosition()
                            drawSelectionHandle(
                                row = startPosition.first,
                                col = startPosition.second,
                                charWidth = baseCharWidth,
                                charHeight = baseCharHeight,
                                pointingDown = false,
                            )

                            // End handle
                            val endPosition = range.getEndPosition()
                            drawSelectionHandle(
                                row = endPosition.first,
                                col = endPosition.second,
                                charWidth = baseCharWidth,
                                charHeight = baseCharHeight,
                                pointingDown = true,
                            )
                        }
                    }
                } // end translate(top = -keyboardCoveredPx)
            }

            if (accessibilityEnabled) {
                AccessibilityOverlay(
                    screenState = screenState,
                    charHeight = baseCharHeight,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(reviewFocusRequester)
                        .focusable(),
                    onToggleReviewMode = { isReviewMode = !isReviewMode },
                    isReviewMode = isReviewMode,
                )

                if (!isReviewMode && keyboardEnabled) {
                    LiveOutputRegion(
                        screenState = screenState,
                        enabled = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Magnifying glass
        if (showMagnifier) {
            MagnifyingGlass(
                position = magnifierPosition,
                keyboardCoveredPx = keyboardCoveredPx,
                screenState = screenState,
                baseCharWidth = baseCharWidth,
                baseCharHeight = baseCharHeight,
                baseCharBaseline = baseCharBaseline,
                textPaint = textPaint,
                backgroundColor = backgroundColor,
                foregroundColor = foregroundColor,
                selectionManager = selectionManager,
                autoDetectUrls = terminalEmulator.autoDetectUrls,
                componentWidth = availableWidth,
                componentHeight = availableHeight,
                selectionBackgroundColor = selectionBackgroundColor,
                selectionForegroundColor = selectionForegroundColor,
            )
        }

        // Copy button (+ overflow menu) when text is selected
        if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
            val range = selectionManager.selectionRange
            if (range != null) {
                val endPosition = range.getEndPosition()
                val buttonRowWidthPx = with(density) { (COPY_BUTTON_SIZE * 2 + 8.dp).toPx() }
                val buttonX = (endPosition.second * baseCharWidth)
                    .coerceAtMost(availableWidth - buttonRowWidthPx)
                    .coerceAtLeast(0f)
                // Button is laid out in the parent Box (viewport coords), but the
                // selection itself is rendered shifted up by keyboardCoveredPx
                // inside the Canvas (#206). Apply the same shift so the button
                // sits above the visible selection-end row, not below the
                // keyboard.
                val buttonY = endPosition.first * baseCharHeight -
                    keyboardCoveredPx -
                    with(density) { COPY_BUTTON_OFFSET.toPx() }
                val context = LocalContext.current
                var overflowMenuExpanded by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .offset(
                            x = with(density) { buttonX.toDp() },
                            y = with(density) { buttonY.coerceAtLeast(0f).toDp() },
                        ),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloatingActionButton(
                            onClick = {
                                val selectedText =
                                    selectionManager.getSelectedText(screenState.snapshot, screenState.scrollbackPosition)
                                // Only write when there is something to write.
                                // getSelectedText returns "" when the selection's
                                // rows have drifted out of the snapshot (new output
                                // between long-press and Copy), and setText with an
                                // empty string would clobber the user's previous
                                // clipboard content (#639).
                                if (selectedText.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(selectedText))
                                }
                                selectionManager.clearSelection()
                            },
                            modifier = Modifier.size(COPY_BUTTON_SIZE),
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ) {
                            Text(stringResource(R.string.terminal_selection_copy), style = MaterialTheme.typography.labelSmall)
                        }

                        Box {
                            val moreOptionsLabel = stringResource(R.string.terminal_selection_more_options_description)
                            FloatingActionButton(
                                onClick = {
                                    overflowMenuExpanded = true
                                    if (shouldShowIme) {
                                        scope.launch {
                                            delay(IME_SHOW_DELAY_MS)
                                            imeInputView?.showIme()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(COPY_BUTTON_SIZE)
                                    .semantics {
                                        contentDescription = moreOptionsLabel
                                        role = Role.Button
                                    },
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ) {
                                Text(stringResource(R.string.terminal_selection_more_options), style = MaterialTheme.typography.labelSmall)
                            }

                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = {
                                    overflowMenuExpanded = false
                                    if (shouldShowIme) {
                                        scope.launch {
                                            delay(IME_SHOW_DELAY_MS)
                                            imeInputView?.showIme()
                                        }
                                    }
                                },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_selection_select_all)) },
                                    onClick = {
                                        selectionController.selectAll()
                                        overflowMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_selection_share)) },
                                    onClick = {
                                        val selectedText = selectionManager.getSelectedText(
                                            screenState.snapshot,
                                            screenState.scrollbackPosition,
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, selectedText)
                                            if (context !is Activity) {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        }
                                        try {
                                            context.startActivity(Intent.createChooser(intent, null))
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            // No app available to handle the share intent; silently ignore
                                        }
                                        overflowMenuExpanded = false
                                    },
                                )
                                if (onPasteRequest != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.terminal_selection_paste)) },
                                        onClick = {
                                            selectionManager.clearSelection()
                                            onPasteRequest()
                                            overflowMenuExpanded = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        val modeLabel = when (selectionManager.mode) {
                                            SelectionMode.CHARACTER -> stringResource(R.string.terminal_selection_mode_character)
                                            SelectionMode.WORD -> stringResource(R.string.terminal_selection_mode_word)
                                            else -> stringResource(R.string.terminal_selection_mode_line)
                                        }
                                        Text(modeLabel)
                                    },
                                    onClick = {
                                        selectionController.toggleSelectionMode()
                                        // Keep menu open for easy cycling
                                    },
                                )
                            }
                        }
                    }
                    if (onPasteRequest != null) {
                        FloatingActionButton(
                            onClick = {
                                selectionManager.clearSelection()
                                onPasteRequest()
                            },
                            modifier = Modifier.size(COPY_BUTTON_SIZE),
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ) {
                            Text("Paste", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Hidden AndroidView with custom InputConnection for IME (software keyboard) input
        // This provides proper backspace, enter key, and keyboard type handling
        // Must have non-zero size for Android to accept IME focus
        if (keyboardEnabled) {
            AndroidView(
                factory = { context ->
                    ImeInputView(context, keyboardHandler).apply {
                        this.allowStandardKeyboard = allowStandardKeyboard
                        this.rawKeyboardMode = rawKeyboardMode
                        this.customImeFlags = customImeFlags
                        // Set up key event handling
                        setOnKeyListener { _, _, event ->
                            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                                ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                            ) {
                                resetImeBuffer()
                            }
                            // this.keyboardHandler, not the captured one: a
                            // reused node keeps the View but gets a fresh
                            // handler through the update block below.
                            this.keyboardHandler.onKeyEvent(
                                androidx.compose.ui.input.key.KeyEvent(event),
                            )
                        }
                        // Store reference for IME control
                        imeInputView = this
                    }
                },
                // Compose calls this immediately before
                // AndroidViewHolder.removeAllViewsInLayout() — same stack
                // frame — so it is the one point where the interop view is
                // guaranteed to be unfocused at removal time, whatever the
                // device's frame timing. A focused child there sends
                // ViewGroup down rootViewRequestFocus() back into the
                // half-disposed Compose hierarchy, which is the HyperOS /
                // Android 16 warm-return crash.
                onReset = { view -> view.onInteropReset() },
                onRelease = { view -> view.onInteropReset() },
                update = { view ->
                    // Refreshed here rather than captured in the factory:
                    // supplying onReset makes this a reusable Compose node, and
                    // a reused node does not re-run the factory.
                    view.keyboardHandler = keyboardHandler
                    imeInputView = view
                    view.allowStandardKeyboard = allowStandardKeyboard
                    view.rawKeyboardMode = rawKeyboardMode
                    view.customImeFlags = customImeFlags
                    view.onPasteRequest = onPasteRequest
                    view.onInteropUpdate()
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusable()
                    .focusRequester(focusRequester),
            )
        }
    }
}

/**
 * Draw a single terminal line.
 */
private fun DrawScope.drawLine(
    line: TerminalLine,
    row: Int,
    isUrlContinuation: Boolean = false,
    urlContinuationEndCol: Int = 0,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
    defaultFg: Color,
    defaultBg: Color,
    selectionManager: SelectionManager,
    autoDetectUrls: Boolean = false,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
) {
    val y = row * charHeight
    var x = 0f

    line.cells.forEachIndexed { col, cell ->
        val cellWidth = charWidth * cell.width

        // Check if this cell is selected
        val isSelected = selectionManager.isCellSelected(row, col, line)

        // Check if this cell is part of a hyperlink.
        // For URL continuation rows, only underline the leading URL-safe
        // prefix (spaces are allowed at the left/right terminal margins
        // where wrapping occurs, but a mid-line space ends the URL).
        val isHyperlink = line.getHyperlinkUrlAt(col, autoDetectUrls) != null ||
            (isUrlContinuation && col < urlContinuationEndCol)

        // Determine colors (handle reverse video and selection)
        val baseFgColor = if (cell.reverse) cell.bgColor else cell.fgColor
        val bgColor = if (cell.reverse) cell.fgColor else cell.bgColor

        // Draw background (with selection highlight)
        val finalBgColor = if (isSelected) selectionBackgroundColor else bgColor
        if (finalBgColor != defaultBg || isSelected) {
            drawRect(
                color = finalBgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, charHeight),
            )
        }

        // Draw character
        if (cell.char != ' ' || cell.combiningChars.isNotEmpty()) {
            val text = buildString {
                append(cell.char)
                cell.combiningChars.forEach { append(it) }
            }

            // Force high contrast for text on the selection background
            val fgColor = if (isSelected) selectionForegroundColor else baseFgColor

            // Configure text paint for this cell
            textPaint.color = fgColor.toArgb()
            textPaint.isFakeBoldText = cell.bold
            textPaint.textSkewX = if (cell.italic) -0.25f else 0f
            // Underline if cell has underline OR if it's a hyperlink
            textPaint.isUnderlineText = cell.underline == 1 || isHyperlink
            textPaint.isStrikeThruText = cell.strike

            // Draw text
            drawContext.canvas.nativeCanvas.drawText(
                text,
                x,
                y + charBaseline,
                textPaint,
            )

            // Draw double underline if needed
            if (cell.underline == 2) {
                drawDoubleUnderline(
                    x = x,
                    y = y + charBaseline,
                    width = cellWidth,
                    color = fgColor,
                )
            }

            // Draw curly underline if needed
            if (cell.underline == 3) {
                drawCurlyUnderline(
                    x = x,
                    y = y + charBaseline,
                    width = cellWidth,
                    charWidth = charWidth,
                    color = fgColor,
                )
            }
        }

        x += cellWidth
    }
}

/**
 * Draw a double underline (two parallel lines).
 *
 * @param x Start x position
 * @param y Baseline y position
 * @param width Width to draw the underline
 * @param color Color of the underline
 */
private fun DrawScope.drawDoubleUnderline(
    x: Float,
    y: Float,
    width: Float,
    color: Color,
) {
    val paint = Paint().apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    val baseY = y + 2f
    val canvas = drawContext.canvas.nativeCanvas

    // Draw first line
    canvas.drawLine(x, baseY, x + width, baseY, paint)

    // Draw second line below the first
    canvas.drawLine(x, baseY + DOUBLE_UNDERLINE_SPACING, x + width, baseY + DOUBLE_UNDERLINE_SPACING, paint)
}

/**
 * Draw a curly/zigzag underline pattern (like spell-check underlines).
 * The pattern repeats seamlessly across characters by aligning with the character grid.
 * Each character starts at the same phase (top of wave) ensuring perfect continuity.
 *
 * @param x Start x position
 * @param y Baseline y position
 * @param width Width to draw the underline
 * @param charWidth Base character width (used to calculate wavelength)
 * @param color Color of the underline
 */
private fun DrawScope.drawCurlyUnderline(
    x: Float,
    y: Float,
    width: Float,
    charWidth: Float,
    color: Color,
) {
    val path = Path()
    val wavelength = charWidth / CURLY_UNDERLINE_CYCLES_PER_CHAR
    val amplitude = CURLY_UNDERLINE_AMPLITUDE
    val halfWave = wavelength / 2

    val baseY = y + 3f

    // Start at the top of the wave (phase = 0)
    path.moveTo(x, baseY - amplitude)

    // Draw zigzag pattern across the width
    var currentX = x
    var goingDown = true // Start by going down from the top

    while (currentX < x + width) {
        currentX += halfWave
        if (currentX > x + width) {
            currentX = x + width
        }

        val nextY = if (goingDown) baseY + amplitude else baseY - amplitude
        path.lineTo(currentX, nextY)

        goingDown = !goingDown
    }

    // Draw the path
    drawContext.canvas.nativeCanvas.drawPath(
        path,
        Paint().apply {
            this.color = color.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        },
    )
}

/** Which way an edge-held drag should scroll the viewport, if at all. */
internal enum class EdgeScroll { NONE, UP, DOWN }

/**
 * Decides whether a pointer held at [posY] within a viewport of [viewportHeightPx]
 * is inside the top/bottom edge zone and has scrollback room to move that way.
 *
 * UP means scroll toward older content (increasing scrollbackPosition); DOWN means
 * toward the live bottom. Returns NONE in the middle or when already at the limit.
 */
internal fun edgeScrollDirection(
    posY: Float,
    viewportHeightPx: Float,
    scrollbackPosition: Int,
    maxScrollback: Int,
    edgeZone: Float = EDGE_SCROLL_ZONE,
): EdgeScroll {
    if (viewportHeightPx <= 0f) return EdgeScroll.NONE
    val relY = posY / viewportHeightPx
    return when {
        relY < edgeZone && scrollbackPosition < maxScrollback -> EdgeScroll.UP
        relY > 1f - edgeZone && scrollbackPosition > 0 -> EdgeScroll.DOWN
        else -> EdgeScroll.NONE
    }
}

/**
 * Rows-per-tick to scroll given the finger's depth into the edge zone.
 *
 *  - At the boundary between the edge zone and the live viewport: 1 row/tick
 *    (matches the old fixed behaviour).
 *  - At the screen edge (top 0 or bottom 1): caps at [MAX_EDGE_SCROLL_ROWS].
 *  - Outside the edge zone: 0 — callers normally guard with
 *    [edgeScrollDirection] first.
 *
 * Always returns a positive count; the caller applies the sign based on the
 * direction it got from [edgeScrollDirection]. Issue #94.
 */
internal fun edgeScrollRowsPerTick(
    posY: Float,
    viewportHeightPx: Float,
    dir: EdgeScroll,
    edgeZone: Float = EDGE_SCROLL_ZONE,
    maxRows: Int = MAX_EDGE_SCROLL_ROWS,
): Int {
    if (dir == EdgeScroll.NONE || viewportHeightPx <= 0f) return 0
    val relY = posY / viewportHeightPx
    val depth = when (dir) {
        EdgeScroll.UP -> ((edgeZone - relY) / edgeZone).coerceIn(0f, 1f)
        EdgeScroll.DOWN -> ((relY - (1f - edgeZone)) / edgeZone).coerceIn(0f, 1f)
        EdgeScroll.NONE -> 0f
    }
    // Linear ramp from 1 row/tick at zone boundary to maxRows at screen edge.
    return 1 + (depth * (maxRows - 1)).toInt().coerceIn(0, maxRows - 1)
}

/**
 * Whether to reflow the PTY to the keyboard-shrunk height (resize / SIGWINCH)
 * on a soft-keyboard toggle, rather than keep the keyboard-hidden row count and
 * render-shift the grid up (#206).
 *
 * True for the alternate screen ([altScreen]: vim/less/htop and tmux-on-alt),
 * which intrinsically reflows; OR when the host passes [reflowOnKeyboard] —
 * its hint that a PRIMARY-buffer full-screen TUI is running (a tmux/screen
 * multiplexer, or any app with mouse tracking active) whose TOP status row
 * (e.g. tmux's scroll/copy-mode indicator) would otherwise be shifted off the
 * top. termlib stays Haven-agnostic: the host owns that policy and reduces it
 * to this one Boolean. A plain line-mode shell passes false, so a keyboard
 * toggle keeps the no-resize render-shift and never SIGWINCHes the shell.
 */
internal fun shouldReflowToKeyboard(altScreen: Boolean, reflowOnKeyboard: Boolean): Boolean = altScreen || reflowOnKeyboard

/** Debounce for a shrink, a width change, or any primary-buffer resize. */
internal const val RESIZE_DEBOUNCE_MS = 80L

/**
 * Debounce for a row GROW on the reflow-to-keyboard path (#554). Must exceed
 * the gap between Android taking the IME down for a backgrounding and the
 * window-focus loss that follows (a few hundred ms, see
 * IME_BACKGROUND_HIDE_WINDOW_MS on the host side), so the focus check after
 * the delay can tell a backgrounding from a user keyboard dismiss.
 */
internal const val KEYBOARD_HIDE_RESIZE_DEBOUNCE_MS = 400L

/**
 * How long after window focus returns to keep holding a pending resize (#554),
 * giving the #515 IME restore (focus-gain tick -> showIme -> inset animation
 * start) time to change sizingHeight and cancel the resize entirely.
 */
internal const val IME_RESTORE_GRACE_MS = 600L

/**
 * Which debounce a pending PTY resize gets before it is committed (#554): a
 * row grow while reflowed-to-keyboard is keyboard-hide-shaped and waits long
 * enough for a backgrounding's focus loss to arrive; everything else keeps the
 * short pen-leak debounce.
 */
internal fun resizeDebounceMs(reflowToKeyboard: Boolean, growingRows: Boolean): Long =
    if (reflowToKeyboard && growingRows) KEYBOARD_HIDE_RESIZE_DEBOUNCE_MS else RESIZE_DEBOUNCE_MS

/**
 * Vertical distance (px) to translate the rendered grid UP so the bottom-most
 * content stays visible just above the soft keyboard, on the PRIMARY buffer
 * (issue #206 and the tmux/Claude-Code status-line follow-up).
 *
 * Returns 0 when the keyboard is hidden ([covered] <= 0) or on the alternate
 * buffer ([altScreen] — the PTY is reflowed to the live height there, so the
 * grid already fits). When scrolled into history ([scrollbackPosition] != 0)
 * it shows the bottom of the window ([covered]).
 *
 * Otherwise it anchors on whichever row is LOWER on screen — the cursor (so a
 * bottom-anchored shell prompt stays put) or [lastContentRow] (so a
 * full-screen TUI living on the primary buffer, e.g. a REPL inside tmux that
 * keeps scrollback and never enters the alternate screen, doesn't hide its
 * status/prompt rows behind the keyboard). The shift is capped at [covered]
 * and never exceeds `cursorRow * charHeight`, so the cursor's own row is never
 * scrolled off the top of the viewport.
 *
 * @param covered Pixels the keyboard currently covers (maxHeight - liveHeight).
 * @param availableHeight Live (keyboard-shrunk) viewport height in px.
 */
internal fun keyboardCoveredShiftPx(
    covered: Float,
    altScreen: Boolean,
    scrollbackPosition: Int,
    cursorRow: Int,
    lastContentRow: Int,
    charHeight: Float,
    availableHeight: Int,
): Float = when {
    covered <= 0f -> 0f

    altScreen -> 0f

    scrollbackPosition != 0 -> covered

    else -> {
        val anchorRow = maxOf(cursorRow, lastContentRow)
        val desired = ((anchorRow + 1) * charHeight) - availableHeight
        desired.coerceIn(0f, minOf(covered, cursorRow * charHeight))
    }
}

/**
 * Result of a single handle-drag update step.
 *
 * @param startRow Updated selection start row
 * @param startCol Updated selection start col
 * @param endRow Updated selection end row
 * @param endCol Updated selection end col
 * @param isMovingStart Which handle the finger is now dragging after this step
 */
internal data class HandleDragResult(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
    val isMovingStart: Boolean,
)

/**
 * Computes the next selection range and active-handle flag for one drag event, handling
 * the case where the two handles cross each other.
 *
 * When the moving handle passes the anchor, ownership flips and the anchor is restored to
 * its pre-cross position so it doesn't jump to the dragged handle's column.
 *
 * @param startRow Current selection start row (before this event)
 * @param startCol Current selection start col (before this event)
 * @param endRow Current selection end row (before this event)
 * @param endCol Current selection end col (before this event)
 * @param isMovingStart True if the start handle is being dragged
 * @param newRow Row the finger moved to
 * @param newCol Col the finger moved to
 */
@VisibleForTesting
internal fun applyHandleDrag(
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int,
    isMovingStart: Boolean,
    newRow: Int,
    newCol: Int,
): HandleDragResult {
    val anchorRow = if (isMovingStart) endRow else startRow
    val anchorCol = if (isMovingStart) endCol else startCol

    val updatedStartRow = if (isMovingStart) newRow else startRow
    val updatedStartCol = if (isMovingStart) newCol else startCol
    val updatedEndRow = if (isMovingStart) endRow else newRow
    val updatedEndCol = if (isMovingStart) endCol else newCol

    val crossed = if (isMovingStart) {
        newRow > anchorRow || (newRow == anchorRow && newCol > anchorCol)
    } else {
        newRow < anchorRow || (newRow == anchorRow && newCol < anchorCol)
    }

    return if (!crossed) {
        HandleDragResult(updatedStartRow, updatedStartCol, updatedEndRow, updatedEndCol, isMovingStart)
    } else {
        // Flip ownership and restore the stationary handle to its pre-cross position.
        val newIsMovingStart = !isMovingStart
        if (newIsMovingStart) {
            // Was moving end, now moving start — end goes back to anchor
            HandleDragResult(newRow, newCol, anchorRow, anchorCol, newIsMovingStart)
        } else {
            // Was moving start, now moving end — start goes back to anchor
            HandleDragResult(anchorRow, anchorCol, newRow, newCol, newIsMovingStart)
        }
    }
}

/**
 * Check if a touch position is near a selection handle.
 * Returns (touchingStart, touchingEnd).
 */
private fun isTouchingHandle(
    touchPos: Offset,
    range: SelectionRange,
    charWidth: Float,
    charHeight: Float,
    hitRadius: Float = HANDLE_HIT_RADIUS,
    keyboardCoveredPx: Float = 0f,
): Pair<Boolean, Boolean> {
    // Handle circles are drawn offset from the character edge by their radius (~12dp).
    // Start handle points up (circle above the character top),
    // end handle points down (circle below the character bottom).
    // Use a generous hit radius centered on the circle's visual center.
    val handleRadius = charHeight * 0.4f // approximate circle radius
    val startPos = Offset(
        range.startCol * charWidth + charWidth / 2,
        range.startRow * charHeight - handleRadius,
    )
    val endPos = Offset(
        range.endCol * charWidth + charWidth / 2,
        range.endRow * charHeight + charHeight + handleRadius,
    )

    // touchPos is in viewport coords; handles are drawn shifted up by
    // keyboardCoveredPx (the #206 translate), so compare in content space.
    val contentTouch = Offset(touchPos.x, touchPos.y + keyboardCoveredPx)
    val distToStart = (contentTouch - startPos).getDistance()
    val distToEnd = (contentTouch - endPos).getDistance()

    return Pair(
        distToStart < hitRadius,
        distToEnd < hitRadius,
    )
}

/**
 * Lightweight placeholder shown when the Terminal composable is rendered
 * inside Android Studio's Compose Preview (LocalInspectionMode).
 */
@Composable
private fun TerminalPreview(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
) {
    val previewLines = listOf(
        "$ echo hello",
        "hello",
        "$ _",
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        val textPaint = TextPaint().apply {
            color = foregroundColor.toArgb()
            typeface = Typeface.MONOSPACE
            textSize = 14f * density
        }
        val metrics = textPaint.fontMetrics
        val lineHeight = ceil(metrics.descent - metrics.ascent)
        val baseline = ceil(-metrics.ascent)

        previewLines.forEachIndexed { index, line ->
            drawContext.canvas.nativeCanvas.drawText(
                line,
                0f,
                baseline + index * lineHeight,
                textPaint,
            )
        }
    }
}

/**
 * Calculates the top-left offset for the magnifying glass box so it stays visible and
 * never obscured by the finger.
 *
 * Prefers positioning above the touch point (with [verticalOffsetPx] gap). Flips below
 * the finger (adding [fingerHeightPx] + [verticalOffsetPx]) when there is not enough
 * room above. The result is clamped to the component bounds on all sides.
 */
@VisibleForTesting
internal fun magnifierOffset(
    position: Offset,
    magnifierSizePx: Float,
    verticalOffsetPx: Float,
    fingerHeightPx: Float,
    componentWidth: Int,
    componentHeight: Int,
): Offset {
    val clampedX = (position.x - magnifierSizePx / 2)
        .coerceIn(0f, (componentWidth.toFloat() - magnifierSizePx).coerceAtLeast(0f))
    val spaceAbove = position.y - verticalOffsetPx - magnifierSizePx
    return if (spaceAbove >= 0f) {
        Offset(x = clampedX, y = spaceAbove)
    } else {
        val yBelow = position.y + fingerHeightPx + verticalOffsetPx
        Offset(x = clampedX, y = yBelow.coerceAtMost((componentHeight.toFloat() - magnifierSizePx).coerceAtLeast(0f)))
    }
}

/**
 * Magnifying glass for text selection.
 */
@Composable
private fun MagnifyingGlass(
    position: Offset,
    keyboardCoveredPx: Float = 0f,
    screenState: TerminalScreenState,
    baseCharWidth: Float,
    baseCharHeight: Float,
    baseCharBaseline: Float,
    textPaint: TextPaint,
    backgroundColor: Color,
    foregroundColor: Color,
    selectionManager: SelectionManager,
    autoDetectUrls: Boolean = false,
    componentWidth: Int = 0,
    componentHeight: Int = 0,
    selectionBackgroundColor: Color = Color(0xFFB3D7FF),
    selectionForegroundColor: Color = Color.Black,
) {
    val magnifierSize = MAGNIFIER_SIZE_DP.dp
    val magnifierScale = MAGNIFIER_SCALE
    val density = LocalDensity.current
    val magnifierSizePx = with(density) { magnifierSize.toPx() }

    val verticalOffset = with(density) { MAGNIFIER_VERTICAL_OFFSET.toPx() }
    val fingerHeightPx = with(density) { FINGER_HEIGHT_DP.toPx() }

    val magnifierPos = magnifierOffset(
        position = position,
        magnifierSizePx = magnifierSizePx,
        verticalOffsetPx = verticalOffset,
        fingerHeightPx = fingerHeightPx,
        componentWidth = componentWidth,
        componentHeight = componentHeight,
    )

    // Compute the terminal-coordinate origin that maps the touch point
    // to the center of the magnifier. scale() uses the Canvas center as
    // pivot, so the offset is simply position minus half the magnifier.
    val centerOffset = Offset(
        x = position.x - magnifierSizePx / 2f,
        // The finger's content-y is position.y + keyboardCoveredPx (the main
        // canvas draws shifted up by that for #206), so centre the loupe on
        // the content actually under the finger.
        y = position.y + keyboardCoveredPx - magnifierSizePx / 2f,
    )

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { magnifierPos.x.toDp() },
                y = with(density) { magnifierPos.y.toDp() },
            )
            .size(magnifierSize)
            .border(
                width = MAGNIFIER_BORDER_WIDTH,
                color = Color.Gray,
                shape = CircleShape,
            )
            .background(
                color = Color.White.copy(alpha = MAGNIFIER_BACKGROUND_ALPHA),
                shape = CircleShape,
            )
            .clip(CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Fill background
            drawRect(
                color = backgroundColor,
                size = size,
            )

            // Apply magnification and translate to center the touch point
            translate(-centerOffset.x * magnifierScale, -centerOffset.y * magnifierScale) {
                scale(magnifierScale, magnifierScale) {
                    // Calculate which rows to draw
                    val centerRow = ((position.y + keyboardCoveredPx) / baseCharHeight).toInt().coerceIn(0, screenState.snapshot.rows - 1)

                    // Draw a few rows around the touch point
                    for (rowOffset in -MAGNIFIER_ROW_RANGE..MAGNIFIER_ROW_RANGE) {
                        val row = (centerRow + rowOffset).coerceIn(0, screenState.snapshot.rows - 1)
                        val line = screenState.getVisibleLine(row)
                        drawLine(
                            line = line,
                            row = row,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                            defaultFg = foregroundColor,
                            defaultBg = backgroundColor,
                            selectionManager = selectionManager,
                            autoDetectUrls = autoDetectUrls,
                            selectionBackgroundColor = selectionBackgroundColor,
                            selectionForegroundColor = selectionForegroundColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draw a selection handle (teardrop shape).
 */
private fun DrawScope.drawSelectionHandle(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    pointingDown: Boolean,
    color: Color = Color.White,
) {
    val handleWidthPx = SELECTION_HANDLE_WIDTH.toPx()

    // Position handle at the character position
    val charX = col * charWidth
    val charY = row * charHeight

    // Center the handle horizontally on the character
    val handleX = charX + charWidth / 2

    // Position vertically based on direction
    val handleY = if (pointingDown) {
        charY + charHeight // Bottom of character
    } else {
        charY // Top of character
    }

    val circleRadius = handleWidthPx / 2
    val circleY = if (pointingDown) {
        handleY + circleRadius
    } else {
        handleY - circleRadius
    }

    drawCircle(
        color = color,
        radius = circleRadius,
        center = Offset(handleX, circleY),
    )
}

/**
 * Draw the cursor with shape support (block, underline, bar).
 */
private fun DrawScope.drawCursor(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    foregroundColor: Color,
    backgroundColor: Color = Color.Transparent,
    cursorShape: CursorShape = CursorShape.BLOCK,
    pendingDeadChar: Int = 0,
    charBaseline: Float = 0f,
    textPaint: TextPaint? = null,
) {
    val x = col * charWidth
    val y = row * charHeight

    when (cursorShape) {
        CursorShape.BLOCK -> {
            // Block cursor - full cell rectangle outline
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(charWidth, charHeight),
                alpha = CURSOR_BLOCK_ALPHA,
            )
        }

        CursorShape.UNDERLINE -> {
            // Underline cursor - line at bottom of cell
            val underlineHeight = charHeight * CURSOR_UNDERLINE_HEIGHT_RATIO
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y + charHeight - underlineHeight),
                size = Size(charWidth, underlineHeight),
                alpha = CURSOR_LINE_ALPHA,
            )
        }

        CursorShape.BAR_LEFT -> {
            // Bar cursor - vertical line at left of cell
            val barWidth = charWidth * CURSOR_BAR_WIDTH_RATIO
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, charHeight),
                alpha = CURSOR_LINE_ALPHA,
            )
        }
    }

    // Draw pending dead character if present
    if (pendingDeadChar != 0 && textPaint != null) {
        val savedColor = textPaint.color
        val savedBold = textPaint.isFakeBoldText
        val savedSkew = textPaint.textSkewX
        val savedUnderline = textPaint.isUnderlineText
        val savedStrike = textPaint.isStrikeThruText

        // Use opposite color for block cursor to ensure visibility
        val accentColor =
            if (cursorShape == CursorShape.BLOCK) {
                if (foregroundColor.luminance() > 0.5f) Color.Black else Color.White
            } else {
                foregroundColor
            }

        textPaint.color = accentColor.toArgb()
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false

        drawContext.canvas.nativeCanvas.drawText(
            String(Character.toChars(pendingDeadChar)),
            x,
            y + charBaseline,
            textPaint,
        )

        textPaint.color = savedColor
        textPaint.isFakeBoldText = savedBold
        textPaint.textSkewX = savedSkew
        textPaint.isUnderlineText = savedUnderline
        textPaint.isStrikeThruText = savedStrike
    }
}

/**
 * Background color for the compose mode overlay.
 */
private val COMPOSE_OVERLAY_BACKGROUND = Color(0xFF2E7D32).copy(alpha = 0.85f)

// Distinct blue tint so the IME composer doesn't look identical to the
// compose-key overlay — users can tell "romaji being converted" apart
// from "compose key buffering".
private val IME_COMPOSER_BACKGROUND = Color(0xFF1565C0).copy(alpha = 0.85f)

/**
 * Width of the compose mode cursor bar in pixels.
 */
private const val COMPOSE_CURSOR_BAR_WIDTH = 2f

/**
 * Draw the compose mode overlay at the cursor position.
 *
 * Displays the buffered text with a slide-left behavior when the buffer
 * exceeds available space. Shows an ellipsis at the left edge when text
 * is truncated.
 */
private fun DrawScope.drawComposeOverlay(
    buffer: String,
    cursorRow: Int,
    cursorCol: Int,
    totalCols: Int,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
) {
    val x = cursorCol * charWidth
    val y = cursorRow * charHeight
    val availableCols = totalCols - cursorCol

    // Determine displayed text with slide-left behavior
    val displayText = if (buffer.length > availableCols) {
        "\u2026" + buffer.takeLast(availableCols - 1)
    } else {
        buffer
    }

    val displayWidth = displayText.length * charWidth

    // Draw background
    drawRect(
        color = COMPOSE_OVERLAY_BACKGROUND,
        topLeft = Offset(x, y),
        size = Size(displayWidth.coerceAtLeast(charWidth), charHeight),
    )

    // Draw text
    if (displayText.isNotEmpty()) {
        val savedColor = textPaint.color
        val savedBold = textPaint.isFakeBoldText
        val savedSkew = textPaint.textSkewX
        val savedUnderline = textPaint.isUnderlineText
        val savedStrike = textPaint.isStrikeThruText

        textPaint.color = Color.White.toArgb()
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false

        drawContext.canvas.nativeCanvas.drawText(
            displayText,
            x,
            y + charBaseline,
            textPaint,
        )

        textPaint.color = savedColor
        textPaint.isFakeBoldText = savedBold
        textPaint.textSkewX = savedSkew
        textPaint.isUnderlineText = savedUnderline
        textPaint.isStrikeThruText = savedStrike
    }

    // Draw cursor bar at end of displayed text
    val cursorX = x + displayText.length * charWidth
    drawRect(
        color = Color.White,
        topLeft = Offset(cursorX, y),
        size = Size(COMPOSE_CURSOR_BAR_WIDTH, charHeight),
        alpha = CURSOR_LINE_ALPHA,
    )
}

/**
 * Draw the IME composer overlay at the terminal cursor. The partial IME
 * composition (romaji being converted to kanji, pinyin candidates, swipe-
 * typed word in progress, etc.) never reaches the terminal stream — it
 * lives here until [TerminalInputConnection.commitText] fires. Distinct
 * background tint from [drawComposeOverlay] so users can tell the compose
 * key state apart from IME composition.
 */
private fun DrawScope.drawImeComposerOverlay(
    text: String,
    cursorRow: Int,
    cursorCol: Int,
    totalCols: Int,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
) {
    if (text.isEmpty()) return
    val x = cursorCol * charWidth
    val y = cursorRow * charHeight
    // Use TextPaint to measure real display width — matters for CJK glyphs
    // whose visual width is ~2 cells rather than 1.
    val measuredWidth = textPaint.measureText(text)
    val availablePx = (totalCols - cursorCol) * charWidth

    // If it overflows the line, slide-left with an ellipsis leader.
    val displayText: String
    val displayWidth: Float
    if (measuredWidth > availablePx) {
        val ellipsis = "\u2026"
        var endIndex = text.length
        // Walk back from the end until the ellipsis + tail fits.
        while (endIndex > 0 &&
            textPaint.measureText(ellipsis + text.substring(text.length - endIndex)) > availablePx
        ) {
            endIndex--
        }
        displayText = if (endIndex == 0) {
            ellipsis
        } else {
            ellipsis + text.substring(text.length - endIndex)
        }
        displayWidth = textPaint.measureText(displayText)
    } else {
        displayText = text
        displayWidth = measuredWidth
    }

    drawRect(
        color = IME_COMPOSER_BACKGROUND,
        topLeft = Offset(x, y),
        size = Size(displayWidth.coerceAtLeast(charWidth), charHeight),
    )

    val savedColor = textPaint.color
    val savedBold = textPaint.isFakeBoldText
    val savedSkew = textPaint.textSkewX
    val savedUnderline = textPaint.isUnderlineText
    val savedStrike = textPaint.isStrikeThruText

    textPaint.color = Color.White.toArgb()
    textPaint.isFakeBoldText = false
    textPaint.textSkewX = 0f
    textPaint.isUnderlineText = true
    textPaint.isStrikeThruText = false

    drawContext.canvas.nativeCanvas.drawText(
        displayText,
        x,
        y + charBaseline,
        textPaint,
    )

    textPaint.color = savedColor
    textPaint.isFakeBoldText = savedBold
    textPaint.textSkewX = savedSkew
    textPaint.isUnderlineText = savedUnderline
    textPaint.isStrikeThruText = savedStrike
}

/**
 * Calculate pixel dimensions for a specific row/column count at a given font size.
 *
 * @param rows Number of rows
 * @param cols Number of columns
 * @param fontSize Font size in sp
 * @param typeface The typeface to use for measurement
 * @param density Screen density for sp to px conversion
 * @return Pair of (width in pixels, height in pixels)
 */
private fun calculateDimensions(
    rows: Int,
    cols: Int,
    fontSize: Float,
    typeface: Typeface,
    density: Float,
): Pair<Float, Float> {
    val textPaint = TextPaint().apply {
        this.typeface = typeface
        textSize = fontSize * density
    }

    val charWidth = textPaint.measureText("M")
    val metrics = textPaint.fontMetrics
    val charHeight = ceil(metrics.descent - metrics.ascent)

    val width = cols * charWidth
    val height = rows * charHeight

    return Pair(width, height)
}

/**
 * Find the optimal font size that allows the terminal to fit within the available space
 * while maintaining the exact target rows and columns.
 *
 * Uses binary search to efficiently find the largest font size that fits.
 *
 * @param targetRows Target number of rows
 * @param targetCols Target number of columns
 * @param availableWidth Available width in pixels
 * @param availableHeight Available height in pixels
 * @param minSize Minimum font size in sp
 * @param maxSize Maximum font size in sp
 * @param typeface The typeface to use for measurement
 * @param density Screen density for sp to px conversion
 * @return Optimal font size in sp
 */
private fun findOptimalFontSize(
    targetRows: Int,
    targetCols: Int,
    availableWidth: Int,
    availableHeight: Int,
    minSize: Float,
    maxSize: Float,
    typeface: Typeface,
    density: Float,
): Float {
    var minSizeCurrent = minSize
    var maxSizeCurrent = maxSize

    // Binary search for optimal font size
    while (maxSizeCurrent - minSizeCurrent > FONT_SIZE_SEARCH_EPSILON) {
        val midSize = (minSizeCurrent + maxSizeCurrent) / 2f
        val (width, height) = calculateDimensions(
            rows = targetRows,
            cols = targetCols,
            fontSize = midSize,
            typeface = typeface,
            density = density,
        )

        if (width <= availableWidth && height <= availableHeight) {
            // This size fits, try larger
            minSizeCurrent = midSize
        } else {
            // This size doesn't fit, try smaller
            maxSizeCurrent = midSize
        }
    }

    // Return the largest size that fits
    return minSizeCurrent.coerceIn(minSize, maxSize)
}

private fun charsPerDimension(pixels: Int, charPixels: Float) = (pixels / charPixels).toInt().coerceAtLeast(1)
