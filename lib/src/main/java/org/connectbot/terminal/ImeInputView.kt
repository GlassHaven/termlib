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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.text.Editable
import android.text.Selection
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * A minimal invisible View that provides proper IME input handling for terminal emulation.
 *
 * This view creates a custom InputConnection that:
 * - Handles backspace via deleteSurroundingText by sending KEYCODE_DEL
 * - Handles enter/return keys properly via sendKeyEvent
 * - Configures the keyboard to disable suggestions while allowing voice input
 * - Handles composing text from IME (for voice input partial results)
 * - Manages IME visibility using InputMethodManager for reliable show/hide
 *
 * Based on the ConnectBot v1.9.13 TerminalView implementation.
 */
internal class ImeInputView(
    context: Context,
    /**
     * Re-assignable because the hosting AndroidView is a *reusable* Compose
     * node (it supplies an `onReset` block — see [onInteropReset]). A reused
     * node keeps this View instance and does not re-run the factory, so the
     * handler has to be refreshed from the AndroidView `update` block instead
     * of being captured once at construction.
     */
    internal var keyboardHandler: KeyboardHandler,
    internal val inputMethodManager: InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager,
    internal val onUpdateSelection: (view: View, selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) -> Unit =
        { view, selStart, selEnd, candidatesStart, candidatesEnd ->
            inputMethodManager.updateSelection(view, selStart, selEnd, candidatesStart, candidatesEnd)
        },
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    var isComposeModeActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /** When true, use standard keyboard allowing voice input, swipe, and autocomplete. */
    var allowStandardKeyboard: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /**
     * Raw mode — returns a null [InputConnection] and reports this view as
     * not-a-text-editor, so the IME has nothing to attach to. Gboard's mic,
     * suggestion strip, and AI Core writing assist all disappear because
     * there is no text field for them to decorate. Physical keyboards still
     * work (dispatchKeyEvent goes through View). Trade-offs: IME composition
     * is impossible (no CJK) and soft-keyboard input comes through as raw
     * key events only.
     */
    var rawKeyboardMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /**
     * Custom IME flag bundle. When non-null, [onCreateInputConnection]
     * skips the Secure/Standard preset logic and assembles `inputType`
     * + `imeOptions` from these toggles directly. Lets a power user
     * tune around their specific IME's quirks (e.g. voice-on-Gboard
     * without autocorrect, or Samsung Honeyboard unblocked without
     * autocap).
     */
    var customImeFlags: ImeFlagBundle? = null
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                flushCompositionBeforeRestart()
                inputMethodManager.restartInput(this)
            }
        }

    /**
     * Live IME composition text (romaji mid-conversion, pinyin candidates, etc.)
     * Emits the empty string when no composition is active. Observed by the
     * terminal UI to render a floating composer overlay near the cursor
     * instead of projecting the partial text inline into the terminal.
     * Never null; never empty+non-empty transitions spuriously.
     */
    private val _composingText = MutableStateFlow("")
    val composingText: StateFlow<String> = _composingText.asStateFlow()

    /**
     * `restartInput()` discards the live [TerminalInputConnection]. If the
     * user is mid-composition when a mode flag changes (e.g. they toggle
     * Standard keyboard while a Japanese romaji string is partially typed),
     * the projected characters are already in the terminal but the state
     * that knows about them — [TerminalInputConnection.composingText] — is
     * about to be thrown away. Erase the projected text first so the
     * terminal matches the "empty composition" state the new connection
     * will start from.
     */
    private fun flushCompositionBeforeRestart() {
        activeConnection?.flushCompositionAsBackspaces()
    }

    private val restartImeRunnable = Runnable {
        // #298 diagnostics: this is the line-boundary IME reset. If a captured
        // trace shows the IME re-reading the old line right AFTER this fires,
        // restartInput() (restarting=true) didn't drop its context.
        Log.d(TAG, "restartImeAtLineBoundary -> restartInput()")
        _composingText.value = ""
        inputMethodManager.restartInput(this)
    }

    /**
     * Reset the IME's own state at a line boundary (#298, round 5 — SwiftKey).
     *
     * On Enter we clear the Editable and reset the selection, but that isn't
     * enough for every IME: SwiftKey keeps its internal composition context
     * across it, so the next word arrives as `<stale line>+<word>` — typed
     * `ls` after an executed `nas` reached the shell as `nasls`, and the
     * suggestion bar visibly still offered the dead line. `restartInput` is
     * the canonical "the text changed under you" signal and makes every IME
     * drop its composition/prediction state. Posted (not called inline) so it
     * never re-enters the framework from inside an active InputConnection
     * callback, and debounced so an Enter burst restarts once. fullEditor
     * only — Secure mode never composes and Raw mode has no InputConnection.
     */
    internal fun postRestartImeAtLineBoundary() {
        removeCallbacks(restartImeRunnable)
        post(restartImeRunnable)
    }

    /**
     * Show the IME forcefully. This is more reliable than SoftwareKeyboardController.
     */
    @Suppress("DEPRECATION")
    fun showIme() {
        // The window-invisible focus drop below must never swallow a show
        // request: requestFocus() returns false while unfocusable, and every
        // caller is edge-triggered (a tap, Terminal's LaunchedEffect on
        // shouldShowIme) so nothing retries — the terminal is then left
        // unable to accept input. Warm return races exactly here, with
        // IME_SHOW_DELAY_MS and REFOCUS_DELAY_MS both at 100ms.
        // Only safe once the window is back: holding focus in a hidden
        // window is the crash described at onWindowVisibilityChanged.
        if (!isFocusable && windowIsVisible) {
            removeCallbacks(restoreFocusRunnable)
            isFocusableInTouchMode = true // also restores isFocusable
        }
        // A show request is the other point where a broken chain is visible:
        // requestFocus() returns true for a view that already believes it is
        // focused, so the IME would be shown against a chain that does not
        // point here and the keystrokes would go nowhere.
        repairFocusChain("showIme")
        if (requestFocus()) {
            inputMethodManager.showSoftInput(this, showImeFlags())
        }
    }

    /**
     * SHOW_FORCED normally, nothing when a physical keyboard can type.
     *
     * #511: on a Meta Quest 3 with a keyboard attached, the virtual keyboard rose
     * over the session on every keypress. SHOW_FORCED is documented to hold the
     * IME open "until the user explicitly closes it", which overrides the
     * platform's own rule that a usable hardware keyboard suppresses the soft
     * one. Passing no flags hands that decision back to the platform, which still
     * honours the user's "show virtual keyboard" setting for anyone who wants
     * both.
     *
     * hardKeyboardHidden is part of the test because a folded or docked device
     * reports KEYBOARD_QWERTY while the keys are physically unreachable, and there
     * the soft keyboard is the only way to type.
     */
    private fun showImeFlags(): Int =
        imeShowFlags(resources.configuration.keyboard, resources.configuration.hardKeyboardHidden)

    /**
     * Hide the IME.
     */
    fun hideIme() {
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * This view must never hold View-focus while its window is not visible.
     * On HyperOS/Android 16 warm return, Compose flushes deferred disposal of
     * the hosting composition; AndroidViewHolder.removeAllViewsInLayout() sees
     * a focused child, takes the rootViewRequestFocus() branch, and the focus
     * request re-enters the half-disposed AndroidComposeView →
     * IllegalStateException "Searching for active node in inactive hierarchy".
     * The freeze always passes through window-invisible first, so dropping
     * focus here disarms that branch for as long as the window is away. It is
     * no longer the whole guard: the refocus below is on a fixed delay, and a
     * Xiaomi Mi 17 runs the deferred disposal flush *after* that delay expires.
     * [onInteropReset] closes that window; the delay here is now only about
     * when the user gets their keyboard back, not about crash safety.
     */
    private val restoreFocusRunnable = Runnable {
        if (isAttachedToWindow && !isFocusable) {
            isFocusableInTouchMode = true // also restores isFocusable
            requestFocus()
        }
    }

    /**
     * Last visibility seen by [onWindowVisibilityChanged]. Tracked separately
     * from [getWindowVisibility] because the drop/restore decisions must key
     * off the dispatched value. Assumed visible until told otherwise, matching
     * the focusable-at-construction default.
     */
    private var windowIsVisible = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowIsVisible = visibility == VISIBLE
        if (visibility != VISIBLE) {
            // A restore pending from a brief show must not fire while hidden —
            // it would re-arm the crash for the next warm return.
            removeCallbacks(restoreFocusRunnable)
            // No key up is coming for a backspace held as the window goes away.
            cancelHeldDelRepeat()
            // Not during a Compose interop teardown: the detach that follows
            // [onInteropReset] dispatches GONE from inside
            // removeAllViewsInLayout(), and dropping FOCUSABLE there would run
            // the rootViewRequestFocus() that onInteropReset exists to avoid.
            if (isFocused && !interopTeardown) {
                // Dropping FOCUSABLE gives focus up via clearFocus(), so a
                // root-level focus request does run — harmless here, where the
                // Compose hierarchy is alive, and it cannot land back on this
                // view because the flag is already unset by then. Stays
                // unfocusable for the whole hidden period; `!isFocusable`
                // doubles as the "restore on visible" marker.
                isFocusable = false
            }
        } else if (!isFocusable) {
            postDelayed(restoreFocusRunnable, REFOCUS_DELAY_MS)
        } else {
            repairFocusChain("window visible")
        }
    }

    /**
     * Put this view back in its parents' focus record when the two disagree.
     *
     * [onInteropReset] deliberately breaks the chain while keeping this view's
     * own focus flag, and counts on either the re-add (`addViewInner` restores
     * a re-added child that still has focus) or [onInteropUpdate] to put it
     * back. Neither runs if the teardown never detached the view and no
     * recomposition followed — and the result is silent and total: the view
     * believes it holds focus, so nothing re-requests it; the parent chain does
     * not record it, so the IME's input goes nowhere. Reported as "keys stop
     * reaching the terminal after a reconnect, until I disconnect and
     * reconnect", which rebuilds the session and hides it.
     *
     * The disagreement is cheap to detect and unambiguous, so repair it at
     * every point the view could notice: this is a targeted `requestChildFocus`
     * on the parent, not a focus search, so it cannot reach the root or
     * re-enter a disposing hierarchy.
     */
    private fun repairFocusChain(why: String) {
        if (!isFocused) return
        val group = parent as? ViewGroup ?: return
        if (group.focusedChild === this) return
        Log.d(TAG, "focus chain broken ($why) — repairing")
        interopTeardown = false
        group.requestChildFocus(this, this)
    }

    /**
     * True from [onInteropReset] until the detach that Compose's teardown
     * performs immediately afterwards. Marks the one window in which this
     * view must not touch its own FOCUSABLE flag — see below.
     */
    private var interopTeardown = false

    /**
     * Surrender this view's place in the focus chain for the Compose interop
     * teardown that is about to run. Wired in as the `onReset`/`onRelease`
     * block of the hosting [androidx.compose.ui.viewinterop.AndroidView].
     *
     * Compose 1.12.0's `AndroidViewHolder.onDeactivate()` is, in full,
     * `reset(); removeAllViewsInLayout()` — verified from the shipped
     * bytecode — so the reset block runs on the same stack frame, immediately
     * before the removal that inspects `mFocused`. That makes it the one place
     * where the guard holds whatever the device's frame timing is, which the
     * window-visibility drop below could only approximate: it assumed the
     * deferred disposal flush had already run [REFOCUS_DELAY_MS] after the
     * window came back, and on a Xiaomi Mi 17 (HyperOS, Android 16)
     * `outOfFrameRunnable` fires the deactivation later than that.
     *
     * What is NOT usable here is `clearFocus()` — or dropping FOCUSABLE, which
     * amounts to the same thing. `View.setFlags` gives up focus by calling the
     * public `clearFocus()` (AOSP 16, the FOCUSABLE branch), and that is
     * `clearFocusInternal(refocus = true)`, i.e. it calls
     * `rootViewRequestFocus()` itself. Inside the teardown a root-level focus
     * request is precisely the crash: it re-enters the half-disposed
     * AndroidComposeView → IllegalStateException "Searching for active node in
     * inactive hierarchy". Pinned by
     * `testInteropResetPreventsRootRefocusOnRemoval`.
     *
     * `ViewGroup.clearChildFocus()` is the primitive that does not: it nulls
     * `mFocused` up the parent chain and `ViewRootImpl` answers it by only
     * scheduling a traversal. `removeAllViewsInLayout()` then sees no focused
     * child and never takes the `rootViewRequestFocus()` branch.
     *
     * This view keeps its own PFLAG_FOCUSED, deliberately: `addViewInner()`
     * re-establishes the chain for a re-added child that still has focus, so
     * when `AndroidViewHolder.onReuse()` puts the view back, focus — and with
     * it the keyboard — returns by itself, with no timer and no second focus
     * request.
     */
    internal fun onInteropReset() {
        removeCallbacks(restoreFocusRunnable)
        if (!isFocused) return
        interopTeardown = true
        (parent as? ViewGroup)?.clearChildFocus(this)
    }

    /**
     * Put the focus chain back when the teardown did not actually detach this
     * view. Wired in as the tail of the hosting AndroidView's `update` block.
     *
     * `AndroidViewHolder.onReuse()` calls the reset block *without* removing
     * the view when it finds it still parented, so no detach/attach pair
     * follows and `addViewInner()` never gets to re-establish the chain that
     * [onInteropReset] broke. Left alone, this view would believe it holds
     * focus while every ancestor believes nothing does — a terminal that
     * silently refuses input (#476). The composition is active again by the
     * time `update` runs, so a targeted `requestChildFocus` is safe; it is a
     * chain repair, not a focus search, and cannot reach the root.
     */
    internal fun onInteropUpdate() {
        if (!interopTeardown || !isAttachedToWindow) return
        interopTeardown = false
        if (isFocused) {
            (parent as? ViewGroup)?.requestChildFocus(this, this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        interopTeardown = false
        // Always hide IME when view is detached to prevent SHOW_FORCED from keeping keyboard
        // open after the app/activity is destroyed
        hideIme()
        removeCallbacks(restoreFocusRunnable)
        // Cancel any deferred Enter-fallback so a Handler callback doesn't fire
        // into a detached view after the IME committed "\n" just before teardown.
        activeConnection?.cancelPending()
        cancelHeldDelRepeat()
    }

    /**
     * Gboard Vietnamese often stops generating DEL after the current
     * syllable even while the user is still holding backspace, and
     * restartInput() on the way through a word boundary aborts the rest.
     * After a short silence we keep sending DEL until ACTION_UP.
     *
     * The takeover is bounded, because nothing downstream of it is
     * reversible: these DELs go to a shell. It ends on ACTION_UP, on Enter,
     * on losing focus, on the window going away, on detach — and if none of
     * those arrive, after [HELD_DEL_MAX_TAKEOVER_MS]. The case that bound
     * exists for is an IME that sends a bare DEL down with no matching up;
     * `deleteSurroundingText` is one such source and is excluded explicitly
     * below, but it is unlikely to be the only one.
     */
    private var heldDelRepeatUntil = 0L

    private val heldDelRepeatRunnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() >= heldDelRepeatUntil) {
                Log.d(TAG, "held-DEL takeover stopped at its " +
                    "${HELD_DEL_MAX_TAKEOVER_MS}ms bound; no key up arrived")
                return
            }
            activeConnection?.onUserDelKey()
            keyboardHandler.onKeyEvent(
                ComposeKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)),
            )
            postDelayed(this, heldDelRepeatIntervalMs)
        }
    }

    private val heldDelRepeatIntervalMs: Long
        get() = ViewConfiguration.getKeyRepeatDelay().toLong().coerceIn(40L, 80L)

    private val heldDelTakeoverMs: Long
        get() = 120L

    private fun scheduleHeldDelRepeat() {
        removeCallbacks(heldDelRepeatRunnable)
        // Each real DEL down pushes the deadline out, so a genuine hold that
        // keeps producing events is never cut short; the bound only limits
        // how long we go on deleting with nothing arriving at all.
        heldDelRepeatUntil = SystemClock.uptimeMillis() + HELD_DEL_MAX_TAKEOVER_MS
        postDelayed(heldDelRepeatRunnable, heldDelTakeoverMs)
    }

    private fun cancelHeldDelRepeat() {
        removeCallbacks(heldDelRepeatRunnable)
        heldDelRepeatUntil = 0L
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // A key held while focus moves away has no up event coming here.
        if (!gainFocus) cancelHeldDelRepeat()
    }

    /**
     * Gboard can deliver one logical keystroke as [InputConnection.commitText]
     * *and* a raw [KeyEvent] on this view. [TerminalInputConnection] records
     * the committed printable chars; matching ACTION_DOWN events in the same
     * looper message are consumed here so [setOnKeyListener] never sees the
     * echo. Physical keys and a second genuine "a" do not match an empty
     * queue and still reach the listener.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DEL) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // User backspace (IME sendKeyEvent or raw view key). Keep the
                // Editable in lock-step with the terminal so Gboard Telex does
                // not keep composing against characters the shell already
                // deleted. Autocorrect sendBackspaces() bypasses this view.
                // Never restartInput while DEL is down or up-from-hold:
                // that tears down Gboard's repeat.
                activeConnection?.onUserDelKey()
                // deleteSurroundingText synthesizes DEL downs with no matching
                // UP; do not start a hold-repeat from those or we keep deleting
                // after the user lifts their finger.
                if (activeConnection?.suppressEditableDeleteForDel != true) {
                    scheduleHeldDelRepeat()
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                cancelHeldDelRepeat()
            }
        }
        if (activeConnection?.consumeSameMessagePrintableEcho(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Raw mode: no InputConnection at all. Gboard has nothing to attach
        // to, so its mic / suggestion strip / AI Core writing assist all go
        // away. Physical key events still flow through View.dispatchKeyEvent.
        if (rawKeyboardMode) {
            Log.d(TAG, "onCreateInputConnection: rawKeyboardMode -> null")
            activeConnection = null
            return null
        }

        // Custom mode: caller has set explicit flag toggles, ignore the
        // Secure/Standard inferential logic below and assemble exactly
        // what was asked for. Lets a power user tune around their
        // specific IME's quirks (#115 follow-up).
        customImeFlags?.let { return openCustomFlagInputConnection(outAttrs, it) }

        // Configure IME options. NO_PERSONALIZED_LEARNING tells on-device
        // IME features (Gboard's word bank, Gemini Nano / AI Core writing
        // assist) to keep their hands off this field. On recent Gboard
        // builds it's the single strongest signal — strong enough that
        // Gboard also hides the microphone in response. So we only set it
        // when the user has NOT explicitly opted into Standard keyboard —
        // in Standard mode the user has asked for full IME features and
        // we shouldn't suppress them.
        val noLearning = if (allowStandardKeyboard) {
            0
        } else {
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        // NO_EXTRACT_UI suppresses the IME's fullscreen-editor UI in
        // landscape. For Secure/Compose modes this is right — we render
        // our own text. But in Standard keyboard mode the user has
        // explicitly opted into full IME features, and some IMEs
        // (Gboard included) interpret NO_EXTRACT_UI as "this field
        // doesn't want rich editing" and suppress composition-based
        // autocorrect.
        val noExtractUi = if (allowStandardKeyboard) {
            0
        } else {
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        outAttrs.imeOptions = outAttrs.imeOptions or
            noExtractUi or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            noLearning or
            EditorInfo.IME_ACTION_NONE

        if (isComposeModeActive) {
            // Compose mode: allow voice input and IME suggestions.
            // TYPE_CLASS_TEXT without NO_SUGGESTIONS keeps the suggestion strip (and its
            // microphone button) visible. fullEditor=true gives BaseInputConnection a real
            // Editable — which is what our getExtractedText() reports from. (It does NOT
            // make BaseInputConnection's own getExtractedText() non-null: that one always
            // returns null, which is what #298 turned out to be.)
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
        } else if (allowStandardKeyboard) {
            // Standard keyboard: voice input, swipe typing, autocomplete
            // and autocorrect all enabled. The IME may learn typed input
            // including passwords.
            //
            // TYPE_TEXT_FLAG_AUTO_CORRECT tells the IME "this field wants
            // correction-as-you-type" — without it, Gboard shows
            // suggestions but never triggers the composition-based
            // replacement protocol (setComposingRegion + commitText).
            // See #99.
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
                EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
        } else {
            // Terminal mode (default): pick flags that keep the user's typing
            // verbatim and don't let the IME rewrite commands.
            //
            // Two paths, gated by isAutoCorrectGatedIme(context):
            //
            // 1. Samsung family (gated). Samsung's InputMethodManager_LC
            //    gate (Galaxy S, Android 16) drops every commitText unless
            //    TYPE_TEXT_FLAG_AUTO_CORRECT is present (#110, SeriousM).
            //    We pair it with NO_SUGGESTIONS *and* VISIBLE_PASSWORD: the
            //    eager-commit composing-text path keeps AUTO_CORRECT from
            //    rewriting what reaches the terminal, but Samsung Honeyboard
            //    ignores NO_SUGGESTIONS for the suggestion strip and only
            //    clears it on VISIBLE_PASSWORD (#614, imorourke — A15,
            //    Honeyboard 5.9.30.97). The reporter confirmed the two flags
            //    coexist: the strip stays gone and input still dispatches.
            //
            // 2. Everything else (Gboard, FUTO, Heliboard, Huawei Celia,
            //    ...). NO_SUGGESTIONS alone is *not* enough: Gboard hides
            //    the strip but its silent autocorrect / autocap / autospace
            //    still fires on word boundaries (#115, agevlakh — typed
            //    "cd" landed as "CD", "ip" as "IP "). The standard Android
            //    opt-out from every IME smart behaviour is
            //    TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: it tells the IME the
            //    field holds a free-form, non-correctable token. We layer
            //    NO_SUGGESTIONS on top so any IME that honours both gets
            //    no UI suggestions either.
            //
            //    Tradeoff: VISIBLE_PASSWORD disables IME composition, so
            //    CJK input doesn't work in default Secure mode. Users who
            //    type CJK should switch to Compose mode (Settings →
            //    Keyboard) — that path already uses TYPE_CLASS_TEXT only.
            //    Voice input / swipe typing similarly belong in Standard
            //    mode.
            val needsAutoCorrectGate = isAutoCorrectGatedIme(context)
            outAttrs.inputType = if (needsAutoCorrectGate) {
                EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
            } else {
                EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            Log.d(
                TAG,
                "Secure mode flags: gated=$needsAutoCorrectGate" +
                    " inputType=0x${outAttrs.inputType.toString(16)}",
            )
        }

        // fullEditor=true gives BaseInputConnection a real Editable, which
        // Gboard needs to track replacement ranges for autocorrect via
        // setComposingRegion / deleteSurroundingText. Enable it whenever
        // the user has opted into a mode that allows IME suggestions —
        // compose mode (CJK) and Standard keyboard. Leave it off for
        // default Secure mode where NO_SUGGESTIONS is already set and we
        // don't want the editable to accumulate shell input.
        val fullEditor = isComposeModeActive || allowStandardKeyboard
        Log.d(
            TAG,
            "onCreateInputConnection: mode=" +
                (if (allowStandardKeyboard) "STANDARD" else "SECURE") +
                " compose=$isComposeModeActive fullEditor=$fullEditor" +
                " inputType=0x${outAttrs.inputType.toString(16)}" +
                " imeOptions=0x${outAttrs.imeOptions.toString(16)}",
        )
        return TerminalInputConnection(this, fullEditor).also { activeConnection = it }
    }

    override fun onCheckIsTextEditor(): Boolean = !rawKeyboardMode

    /** Delegate IME paste actions to the same clipboard handler as the terminal context menu. */
    var onPasteRequest: (() -> Unit)? = null

    private var activeConnection: TerminalInputConnection? = null

    /**
     * Clears the IME's internal text buffer and resets its selection state to (0, 0).
     *
     * Call this after key events that are dispatched outside the InputConnection (e.g. physical
     * keyboard events handled via onPreviewKeyEvent or setOnKeyListener), so that the IME's
     * suggestion context stays in sync with the terminal's stateless text model.
     */
    fun resetImeBuffer() {
        // Clear both the Editable AND the composing-text tracking so an
        // in-flight IME composition doesn't resume against stale state
        // (which would make the next setComposingText() produce ghost
        // backspaces or duplicated input).
        activeConnection?.editable?.clear()
        activeConnection?.resetComposition()
        onUpdateSelection(this, 0, 0, -1, -1)
    }

    /**
     * Custom InputConnection that handles backspace and other special keys for terminal input.
     */
    private inner class TerminalInputConnection(
        targetView: View,
        private val fullEditor: Boolean,
    ) : BaseInputConnection(targetView, fullEditor) {

        private var composingText: String = ""

        override fun performContextMenuAction(id: Int): Boolean {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                val paste = onPasteRequest ?: return false
                paste()
                return true
            }
            return super.performContextMenuAction(id)
        }


        /**
         * Tell the IME where the cursor is. Custom InputConnection
         * implementations are required to do this explicitly after every
         * edit — TextView/EditText get it for free via the view layer, but
         * BaseInputConnection does not. Without these calls Gboard keeps
         * its own cursor-tracking frozen at (0, 0), never re-queries
         * [getTextBeforeCursor], and therefore never enters the
         * composition-based replacement protocol that word-level
         * autocorrect relies on (#99).
         *
         * Derives selection + composing-region positions from the
         * underlying [Editable], which is the single source of truth —
         * [BaseInputConnection] keeps it in sync as part of each super.*
         * call. Only fires in modes where [fullEditor] is true; in Secure
         * mode the Editable is a no-op proxy and the selection never
         * actually advances.
         */
        private fun notifyImeSelection() {
            val ed = editable ?: return
            if (!fullEditor && ed.isEmpty()) return
            inputMethodManager.updateSelection(
                this@ImeInputView,
                Selection.getSelectionStart(ed),
                Selection.getSelectionEnd(ed),
                getComposingSpanStart(ed),
                getComposingSpanEnd(ed),
            )
            // An IME that asked to watch the document (GET_EXTRACTED_TEXT_MONITOR) is
            // entitled to be told when it changes. Answering the first request and then
            // going silent leaves it with a stale mirror — the same failure as never
            // answering at all, which is what #298 was. (#298)
            if (extractedTextToken >= 0) {
                inputMethodManager.updateExtractedText(
                    this@ImeInputView,
                    extractedTextToken,
                    buildExtractedText(ed),
                )
            }
        }

        /**
         * Token from the IME's [InputConnection.GET_EXTRACTED_TEXT_MONITOR] request,
         * or -1 if it never asked to watch. Lives on this connection, so a
         * `restartInput` (new connection) forgets it, as it should.
         */
        private var extractedTextToken: Int = -1

        /**
         * Number of chars the IME has asked us (via [setComposingRegion])
         * to treat as re-composable. Consumed by the next [commitText] /
         * [setComposingText], which back-spaces over that many chars
         * before laying down its replacement.
         *
         * This is what makes English Gboard autocorrect work after a word
         * boundary: the IME marks the already-committed word as a
         * composing region and then commits the correction. Without this,
         * the correction just appends (terminal ends up with "teh the ").
         */
        private var pendingReplacementLength: Int = 0

        /**
         * In `fullEditor` mode (Standard / Compose), text the IME tried to
         * compose while a sticky Ctrl/Alt was active — already dispatched to
         * the terminal eagerly as a control combo (so e.g. Ctrl-D fires now
         * and the one-shot modifier is consumed instead of leaking onto the
         * next Enter as `^[[13;5u`). Remembered so the IME's follow-up
         * [commitText] of the same text doesn't send it a second time. (#298)
         */
        private var eagerControlText: String = ""

        /**
         * #298: text we flushed to the shell when Enter arrived mid-composition
         * (see [flushComposingBeforeEnter]). Consumed once so an IME that
         * re-delivers the same line via a later [commitText] (SwiftKey commits
         * on the *next* word boundary, after the newline) doesn't double-send
         * it. Any other commit clears it. Lives only on this connection, so a
         * mode-change `restartInput` (new connection) resets it.
         */
        private var enterFlushedText: String = ""

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            Log.d(TAG, "setComposingRegion(start=$start, end=$end) composingText.len=${composingText.length}")
            super.setComposingRegion(start, end)
            // Only honour this when no composition is already in flight.
            // An active composition means the IME is re-scoping its own
            // ongoing composition, which the floating-composer tracker in
            // [composingText] already handles — translating to terminal
            // backspaces there would double-erase.
            if (composingText.isEmpty()) {
                // Cap at a sane word/phrase length. A runaway IME handing us
                // e.g. the whole document length would otherwise blow away
                // the user's shell history on the next commit.
                pendingReplacementLength = (end - start).coerceIn(0, MAX_REPLACEMENT_LENGTH)
            }
            notifyImeSelection()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val newText = text?.toString() ?: ""
            Log.d(
                TAG,
                "setComposingText(text=${quoteForLog(text)}" +
                    ", newCursorPos=$newCursorPosition) fullEditor=$fullEditor",
            )

            if (!fullEditor) {
                // Secure mode: eager-commit each delta immediately so sticky
                // toolbar modifiers (Ctrl/Alt) take effect on the very next
                // keypress. Without this, Samsung Keyboard
                // (InputMethodManager_LC) buffers per-char sendKeyEvent
                // until the user presses space/enter, so Ctrl+D needs three
                // taps (Ctrl, D, ENTER) before the modifier+key actually
                // dispatches — by that point the user has already typed
                // ENTER which they didn't want as input. (#110)
                //
                // Skip super.setComposingText in this mode: BaseInputConnection
                // without an Editable can otherwise dispatch sendKeyEvent for
                // each composing char (visible in Robolectric and possibly
                // some real-device IMEs), which would race our deliberate
                // sendTextInput. We're authoritative here — the IME doesn't
                // need a matching Editable on this side.
                //
                // The composing buffer is the IME's running guess at the
                // current word; in Secure mode we treat it as authoritative
                // typed text. CJK composition uses fullEditor=true (Compose
                // mode) — that path is unchanged.
                applyComposingDelta(composingText, newText)
                composingText = newText
                notifyImeSelection()
                return true
            }

            if (keyboardHandler.hasStickyCtrlOrAlt()) {
                // A sticky Ctrl/Alt (toolbar) is active: this keystroke is a
                // control combo, not a composition. Dispatch the new
                // character(s) straight to the terminal — onTextInput applies
                // the modifier, so Ctrl-D becomes ^D — and remember them so
                // the IME's follow-up commitText of the same text doesn't
                // send them again. Consuming the modifier here stops it
                // leaking onto the next dispatched key, which would otherwise
                // encode Enter as ^[[13;5u (CSI-u Ctrl+Enter). #298
                val delta = if (newText.startsWith(eagerControlText)) {
                    newText.substring(eagerControlText.length)
                } else {
                    newText // shrink/replacement: best-effort, send as-is
                }
                if (delta.isNotEmpty()) sendTextInput(delta)
                eagerControlText = newText
                composingText = ""
                _composingText.value = ""
                notifyImeSelection()
                return true
            }
            // A normal (unmodified) composition is starting/continuing — drop
            // any stale control-combo guard so a later commit isn't skipped.
            eagerControlText = ""

            super.setComposingText(text, newCursorPosition)

            // Floating-composer model (fullEditor): the in-flight composition
            // lives in an overlay, never in the terminal. Crucial for CJK
            // (romaji → kanji conversion used to flicker the terminal with
            // backspace+rewrite each candidate) and for high-latency
            // transports (mosh, ET) where those round-trips were slow.
            // Bytes hit the remote shell only on commitText().
            if (newText != composingText) {
                composingText = newText
                _composingText.value = newText
            }
            notifyImeSelection()
            return true
        }

        /**
         * Compute and dispatch the diff between two consecutive composing
         * buffers. Pure append → send the new chars. Pure shrink → send
         * backspaces. Replacement → backspace old + send new. Each char
         * sent is also queued for [sendKeyEvent] suppression because
         * Samsung Keyboard re-fires each composed character via key event
         * on its eventual flush (space/enter); without suppression the
         * terminal would receive each char twice. (#110)
         */
        private fun applyComposingDelta(old: String, new: String) {
            if (old == new) return
            if (new.startsWith(old)) {
                val delta = new.substring(old.length)
                if (delta.isNotEmpty()) {
                    sendTextInput(delta)
                    delta.forEach { pendingSuppressChars.addLast(it) }
                }
                return
            }
            if (old.startsWith(new)) {
                sendBackspaces(old.length - new.length)
                return
            }
            // True replacement (rare; Samsung autocorrect/swipe). Backspace
            // the old run then write the new — best we can do without a
            // full diff. Suppress only the new chars; the backspaces don't
            // come back via sendKeyEvent.
            sendBackspaces(old.length)
            if (new.isNotEmpty()) {
                sendTextInput(new)
                new.forEach { pendingSuppressChars.addLast(it) }
            }
        }

        override fun finishComposingText(): Boolean {
            Log.d(
                TAG,
                "finishComposingText() composing.len=${composingText.length}" +
                    " pendingReplacement=$pendingReplacementLength fullEditor=$fullEditor",
            )
            super.finishComposingText()

            if (pendingReplacementLength > 0 && composingText.isNotEmpty()) {
                // Replacement confirmed via finishComposingText (rather than
                // commitText): the IME marked a region as composing then
                // laid down a new composition on top. Some keyboards
                // (Gboard's English autocorrect in particular) then fire
                // finishComposingText to accept it, never a commitText. If
                // we just clear state here the correction vanishes — apply
                // it now. (fullEditor path; Secure mode has already committed
                // each char via setComposingText.)
                sendBackspaces(pendingReplacementLength)
                sendTextInput(composingText)
            } else if (fullEditor && composingText.isNotEmpty()) {
                // #298 (agross): in fullEditor mode the composition lives only
                // in the floating overlay and reaches the shell on commitText —
                // which never comes when the IME accepts the line via
                // finishComposingText + Enter key event. finishComposingText
                // means "the composed text IS the final text": send it now, or
                // the line is lost and the Enter lands on an empty prompt (the
                // shell just draws a new prompt and the typing accumulates).
                // Secure mode is excluded — it already eager-sent every char.
                sendTextInput(composingText)
            }

            composingText = ""
            if (fullEditor) {
                _composingText.value = ""
            }
            // Composition ended; a control combo (if any) is fully consumed.
            eagerControlText = ""
            // Either applied above or cancelled without a commit — either
            // way, don't carry a pending length into an unrelated future
            // commit.
            pendingReplacementLength = 0
            // Do NOT clear the editable here. finishComposingText only
            // signals end of ONE composition event, not end of the
            // whole input. Clearing would wipe the context Gboard uses
            // to decide whether to offer a correction on the next word.
            // Enter (in sendKeyEvent) is the one real reset point.
            notifyImeSelection()
            return true
        }

        // #298 diagnostics: log the IME's *reads* of the field, not just its
        // writes. Prediction-heavy IMEs (SwiftKey) rebuild their word/composition
        // context by reading back the text around the cursor — if these return
        // stale content after an Enter (rather than empty), the IME re-offers the
        // executed line and the next word accretes onto it. The write-side trace
        // (setComposingText/commitText/sendKeyEvent) never showed this; these
        // overlays make a captured logcat (tag ImeInputView) reveal exactly what
        // the IME saw. Pure delegation to super — no behaviour change.
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            val r = super.getTextBeforeCursor(n, flags)
            Log.d(TAG, "getTextBeforeCursor(n=$n) -> ${quoteForLog(r)}")
            return r
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            val r = super.getTextAfterCursor(n, flags)
            Log.d(TAG, "getTextAfterCursor(n=$n) -> ${quoteForLog(r)}")
            return r
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val r = super.getSelectedText(flags)
            Log.d(TAG, "getSelectedText() -> ${quoteForLog(r)}")
            return r
        }

        /**
         * Hand the IME the document. [BaseInputConnection] never implements this —
         * it returns null whatever `fullEditor` says (agross's #298 trace: every
         * call logs `fullEditor=true` and `-> null`).
         *
         * That is a contract we were breaking on both ends. In Standard mode we
         * deliberately omit `IME_FLAG_NO_EXTRACT_UI` and set `AUTO_CORRECT` — we
         * advertise a rich-editing field — and then refused to say what was in it.
         * A prediction IME that mirrors the editor through ExtractedText (SwiftKey
         * polls it after *every* edit) is left with nothing but its own model of
         * the document, and that model is not reset by `restartInput`,
         * `updateSelection(-1, -1)` or an empty `getTextBeforeCursor` — the trace
         * shows Haven doing all three and SwiftKey composing over the executed line
         * regardless, so a second `ls` reaches the shell as `lsls` and it accretes
         * from there.
         *
         * We own the Editable. Report it. (#298)
         */
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            val ed = editable
            if (!fullEditor || ed == null) {
                Log.d(TAG, "getExtractedText(flags=$flags) -> null (fullEditor=$fullEditor)")
                return null
            }
            if (request != null && (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR) != 0) {
                extractedTextToken = request.token
            }
            val extracted = buildExtractedText(ed)
            Log.d(
                TAG,
                "getExtractedText(flags=$flags) -> ${quoteForLog(extracted.text)}" +
                    " sel=${extracted.selectionStart}..${extracted.selectionEnd}" +
                    " monitor=${extractedTextToken >= 0}",
            )
            return extracted
        }

        /** Snapshot of [ed] in the shape the IME expects. */
        private fun buildExtractedText(ed: Editable): ExtractedText = ExtractedText().apply {
            text = ed.toString()
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = Selection.getSelectionStart(ed).coerceAtLeast(0)
            selectionEnd = Selection.getSelectionEnd(ed).coerceAtLeast(0)
            flags = 0
        }

        /**
         * Whether this build may put typed text in the log at all (#518).
         *
         * Off unless a developer opts in on their own device with
         *
         *     adb shell setprop log.tag.ImeInputView VERBOSE
         *
         * which no ordinary install has set. These traces exist because IME
         * behaviour on Android is close to undebuggable without them, but the
         * text flowing through here is *what the user typed* — a report on #518
         * showed a session's keystrokes reconstructed character by character
         * from a logcat, and a sudo password would have landed there the same
         * way. Structure (lengths, positions, flags) is kept unconditionally
         * because it is what the logs are actually read for; the content is not.
         */
        private val logContent: Boolean get() = Log.isLoggable(TAG, Log.VERBOSE)

        private fun quoteForLog(cs: CharSequence?): String = when {
            cs == null -> "null"
            logContent -> "\"${cs.toString().take(40)}\" (len=${cs.length})"
            else -> "(len=${cs.length})"
        }

        override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
            Log.d(
                TAG,
                "deleteSurroundingText(left=$leftLength, right=$rightLength)" +
                    " composing.len=${composingText.length}",
            )
            // Handle backspace by sending DEL key events.
            // When IME sends delete, it often sends (0, 0) or (1, 0) for backspace.
            if (rightLength == 0 && leftLength == 0) {
                return sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // Cap the loop: real IMEs send single-digit values here. A misbehaving
            // or hostile IME asking for ~2^31 deletions would freeze the UI thread.
            val bounded = leftLength.coerceIn(0, MAX_DELETE_SURROUNDING)

            // If a composition is in flight, the IME is trying to delete part of
            // ITS composition (e.g. user pressed backspace while composing). The
            // composition lives only in the floating overlay, never in the
            // terminal, so we must NOT send DEL keys to the shell — just shrink
            // our tracker and let the overlay follow.
            if (composingText.isNotEmpty()) {
                val newLength = (composingText.length - bounded).coerceAtLeast(0)
                composingText = composingText.substring(0, newLength)
                _composingText.value = composingText
                super.deleteSurroundingText(leftLength, rightLength)
                notifyImeSelection()
                return true
            }

            // No composition active — this is a real backspace through the IME,
            // dispatch it to the terminal. sendKeyEvent(DEL) also reaches
            // dispatchKeyEvent; skip the Editable shrink there so
            // super.deleteSurroundingText is the single editor update.
            suppressEditableDeleteForDel = true
            try {
                for (i in 0 until bounded) {
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                }
            } finally {
                suppressEditableDeleteForDel = false
            }

            // TODO: Implement forward delete if rightLength > 0
            super.deleteSurroundingText(leftLength, rightLength)
            notifyImeSelection()
            return true
        }

        // Guard: when true, sendKeyEvent is suppressed to prevent double input
        // from BaseInputConnection.commitText() dispatching key events AND
        // our sendTextInput() both sending the same characters.
        private var suppressKeyEvents = false

        // deleteSurroundingText already updates the Editable via super; a
        // nested sendKeyEvent(DEL) must not delete a second character.
        internal var suppressEditableDeleteForDel = false

        // Chars committed eagerly via setComposingText (Secure mode) that
        // Samsung Keyboard will re-fire later as sendKeyEvent on its
        // batch-flush (space/enter). Each entry consumes exactly one
        // matching ACTION_DOWN. ACTION_UP is harmless — KeyboardHandler
        // only acts on KeyDown — so we let it through. (#110)
        private val pendingSuppressChars: ArrayDeque<Char> = ArrayDeque()

        // Printable ASCII committed via sendTextInput. Matching raw View /
        // sendKeyEvent ACTION_DOWN is the Gboard commitText+key echo.
        // Drain is posted *twice* so a key Gboard queues later in this
        // same message (after we post the drain) still sees the queue;
        // a later IPC / keystroke runs after the drain and is genuine.
        // Separate from pendingSuppressChars (#110 Samsung batch re-fire).
        private val committedThisMessage: ArrayDeque<Char> = ArrayDeque()
        private var echoDrainPosted = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val echoDrainRunnable = Runnable {
            committedThisMessage.clear()
            echoDrainPosted = false
        }
        private val echoDrainScheduler = Runnable {
            handler.post(echoDrainRunnable)
        }

        /**
         * Derive a printable codepoint from a KeyEvent for suppression
         * matching. Prefers [KeyEvent.unicodeChar] (works in production
         * with a real KeyCharacterMap) but falls back to deriving from
         * keyCode for the common A–Z / 0–9 / SPACE keys so the suppress
         * still fires in unit-test environments where unicodeChar can be
         * 0. Returns 0 for non-printable keys.
         */
        private fun effectiveCharCode(event: KeyEvent): Int {
            val u = event.unicodeChar
            if (u != 0) return u
            return when (event.keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                    'a'.code + (event.keyCode - KeyEvent.KEYCODE_A)

                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    '0'.code + (event.keyCode - KeyEvent.KEYCODE_0)

                KeyEvent.KEYCODE_SPACE -> ' '.code

                else -> 0
            }
        }

        /**
         * User-facing backspace reached the view. Shrink the Editable by one
         * code point so Gboard's getTextBeforeCursor matches the terminal.
         * sendBackspaces() (autocorrect) never comes through here.
         */
        internal fun onUserDelKey() {
            if (suppressEditableDeleteForDel) return
            deleteOneCodePointFromEditable()
        }

        private fun deleteOneCodePointFromEditable() {
            val ed = editable ?: return
            if (ed.isEmpty()) return
            var end = Selection.getSelectionStart(ed)
            if (end < 0) end = ed.length
            if (end <= 0) return
            val start =
                if (end >= 2 && Character.isSurrogatePair(ed[end - 2], ed[end - 1])) {
                    end - 2
                } else {
                    end - 1
                }
            ed.delete(start, end)
            notifyImeSelection()
        }

        /**
         * True when [event] is the raw-view echo of printable text this
         * connection just committed in the current looper message.
         */
        internal fun consumeSameMessagePrintableEcho(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return false
            if (committedThisMessage.isEmpty()) return false
            val incoming = effectiveCharCode(event)
            if (incoming == 0) return false
            if (committedThisMessage.first().code == incoming) {
                committedThisMessage.removeFirst()
                return true
            }
            // Different printable key: this is a new keystroke, not the echo.
            committedThisMessage.clear()
            return false
        }

        private fun rememberCommittedPrintableEcho(text: String) {
            for (ch in text) {
                if (ch.code in 0x20..0x7E) {
                    committedThisMessage.addLast(ch)
                }
            }
            if (committedThisMessage.isNotEmpty() && !echoDrainPosted) {
                echoDrainPosted = true
                handler.post(echoDrainScheduler)
            }
        }

        // Enter deduplication: commitText("\n") always defers to sendKeyEvent.
        // If sendKeyEvent(ENTER) arrives (from the same IME action), we cancel
        // the deferred dispatch. If it doesn't arrive within one frame (~16ms),
        // the deferred dispatch fires — covering IMEs that only commitText.
        private var enterHandledByKeyEvent = false
        private val enterFallbackRunnable = Runnable {
            if (!enterHandledByKeyEvent) {
                this@ImeInputView.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
                )
                // #298: mirror the sendKeyEvent(ENTER) bookkeeping — this path
                // bypasses that override, so without the reset a commitText-only
                // IME accumulates every command into one suggestion context.
                editable?.clear()
                onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
                if (fullEditor) postRestartImeAtLineBoundary()
            }
            enterHandledByKeyEvent = false
        }

        /**
         * #298: in fullEditor (Standard / Compose) mode the typed line lives
         * only in the floating composing buffer and reaches the shell on
         * [commitText]. Gboard commits before sending Enter, so it's fine — but
         * some IMEs (SwiftKey) send Enter while a composition is still in flight
         * and only commit it on the *next* word boundary. The bare newline then
         * lands on an empty prompt and the line shows up, late, on the next
         * prompt. Flush the pending composition to the shell *before* the
         * newline so ordering is right, and arm [enterFlushedText] so the IME's
         * later re-delivery of the same text is dropped instead of duplicated.
         * No-op in Secure mode (each char was already committed) and when
         * nothing is composing (the common Gboard / commit-then-Enter path).
         */
        private fun flushComposingBeforeEnter() {
            if (!fullEditor || composingText.isEmpty()) return
            val pending = composingText
            sendTextInput(pending)
            enterFlushedText = pending
            composingText = ""
            _composingText.value = ""
        }

        /**
         * IME moved the cursor explicitly (e.g. the user tapped to
         * place the caret inside an existing word). Mirror the new
         * position back to Gboard so its tracked selection stays in
         * sync with the Editable.
         */
        override fun setSelection(start: Int, end: Int): Boolean {
            val r = super.setSelection(start, end)
            notifyImeSelection()
            return r
        }

        /**
         * Code-point variant of [deleteSurroundingText]. BaseInputConnection
         * handles the editable update; we just mirror the selection back
         * to the IME so it doesn't fall out of sync.
         */
        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            val r = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            notifyImeSelection()
            return r
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            Log.d(
                TAG,
                // keyCode and unicodeChar each name the character typed, so they are
                // the payload rather than the metadata here (#518). What these logs
                // are read for is the suppression bookkeeping, which is kept.
                "sendKeyEvent(action=${event.action}" +
                    (if (logContent) " keyCode=${event.keyCode} unicode=${event.unicodeChar}" else "") +
                    ") suppressed=$suppressKeyEvents" +
                    " pendingSuppress=${pendingSuppressChars.size}",
            )
            if (suppressKeyEvents) return true

            // Consume the deferred ACTION_DOWN that Samsung Keyboard fires
            // for each character we already committed eagerly via
            // setComposingText (Secure mode). ACTION_UP is intentionally not
            // suppressed — KeyboardHandler ignores keyup, and consuming UP
            // without DOWN can confuse long-press detection elsewhere in the
            // view tree. (#110)
            if (event.action == KeyEvent.ACTION_DOWN && pendingSuppressChars.isNotEmpty()) {
                val incoming = effectiveCharCode(event)
                if (incoming != 0 && pendingSuppressChars.first().code == incoming) {
                    pendingSuppressChars.removeFirst()
                    return true
                }
            }

            val isEnterDown = event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_ENTER
            if (isEnterDown) {
                // Real Enter from IME — cancel any deferred commitText Enter
                enterHandledByKeyEvent = true
                handler.removeCallbacks(enterFallbackRunnable)
                this@ImeInputView.cancelHeldDelRepeat()
                // #298: send any in-flight composition to the shell before the
                // newline, so a still-composing line isn't lost / delivered late.
                flushComposingBeforeEnter()
            }
            val result = this@ImeInputView.dispatchKeyEvent(event)
            // Clear the IME's text buffer + reset selection ONLY on Enter.
            // Previously this ran after any ACTION_DOWN which clobbered
            // Gboard's editable-tracking after every backspace / DEL we
            // dispatched (including our own autocorrect backspaces), so
            // Gboard could never build up enough context to offer
            // composition-based corrections. Enter is the one real
            // command-boundary signal where dropping accumulated IME
            // state is correct — prevents "git status<enter>ls -l"
            // from landing in Gboard's suggestion bar as one candidate.
            if (isEnterDown) {
                editable?.clear()
                onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
                // #298 round 5: SwiftKey needs a full IME restart to drop its
                // composition context at the line boundary — selection reset
                // alone leaves the dead line in its buffer and the next word
                // arrives as "<stale>+<word>".
                if (fullEditor) postRestartImeAtLineBoundary()
            }
            return result
        }

        /**
         * API 34+ autocorrect replacement path. Gboard on Android 14+
         * uses this instead of `setComposingRegion` + `commitText` when
         * the user taps a suggestion-bar correction for an
         * already-committed word. [BaseInputConnection.replaceText]
         * rewrites the internal [Editable] via `content.replace(start,
         * end, text)` but — unlike `commitText` — doesn't dispatch key
         * events or otherwise inform the host of the change. Without
         * overriding this, Gboard's correction lands in our Editable
         * but never reaches the shell, leaving the terminal with the
         * uncorrected typing.
         *
         * Translation: the editable is a mirror of the characters the
         * terminal has received, so replacing `[start, end)` with
         * `text` maps to `(end - start)` backspaces followed by
         * emitting `text`. Only correct when `end` is at the cursor —
         * autocorrect always replaces the current word, so that holds
         * in practice. If an IME ever calls this with `end` mid-buffer
         * we log and stay conservative (editable updates, terminal
         * doesn't), because re-emitting the suffix is unsafe (the
         * shell may have moved the cursor, processed escapes, etc.).
         */
        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: android.view.inputmethod.TextAttribute?,
        ): Boolean {
            Log.d(
                TAG,
                "replaceText(start=$start, end=$end, text=${quoteForLog(text)}" +
                    ", newCursorPos=$newCursorPosition)",
            )
            val ed = editable
            val cursorBefore = ed?.let { Selection.getSelectionStart(it) } ?: -1
            val result = super.replaceText(start, end, text, newCursorPosition, textAttribute)
            notifyImeSelection()

            // Only rewrite the terminal when the replacement ends at the
            // current cursor — i.e. Gboard is correcting the last word.
            // An IME replacing mid-buffer text would require us to also
            // re-emit the suffix, which isn't safe because the shell may
            // have already echoed / processed those bytes.
            if (ed != null && end == cursorBefore && start in 0..end) {
                val backspaces = end - start
                if (backspaces > 0) sendBackspaces(backspaces)
                val replacement = text.toString().replace("\n", "").replace("\r", "")
                if (replacement.isNotEmpty()) sendTextInput(replacement)
            }
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committedText = text?.toString() ?: ""
            Log.d(
                TAG,
                "commitText(${quoteForLog(committedText)}, newCursorPos=$newCursorPosition)" +
                    " pendingReplacement=$pendingReplacementLength",
            )
            // super.commitText() updates the Editable state (needed for IME sync)
            // but also dispatches key events via sendKeyEvent(). Suppress those
            // to avoid double input — we send the text ourselves via sendTextInput().
            suppressKeyEvents = true
            super.commitText(text, newCursorPosition)
            suppressKeyEvents = false

            // The IME is committing text we already dispatched eagerly as a
            // control combo (sticky Ctrl/Alt was active during composition,
            // #298). Don't send it again — just clear the guard and state.
            if (eagerControlText.isNotEmpty() && committedText == eagerControlText) {
                eagerControlText = ""
                composingText = ""
                _composingText.value = ""
                notifyImeSelection()
                return true
            }
            eagerControlText = ""

            // #298: this is the IME re-delivering a line we already flushed to
            // the shell when Enter arrived mid-composition. Drop it (consume the
            // guard) so it isn't sent twice. Any non-matching commit clears the
            // guard so a later, legitimately-identical command isn't suppressed.
            if (enterFlushedText.isNotEmpty()) {
                if (committedText == enterFlushedText) {
                    enterFlushedText = ""
                    composingText = ""
                    _composingText.value = ""
                    notifyImeSelection()
                    return true
                }
                enterFlushedText = ""
            }

            // #298: Secure mode (the default, fullEditor=false) already
            // eager-sent this composition char-by-char via setComposingText ->
            // applyComposingDelta. commitText must NOT re-send the whole word —
            // doing so made the shell see "lsls" and left Ctrl+D on a non-empty
            // prompt (so it never logged out). Reconcile only the delta between
            // what we already sent and the final commit (autocorrect / trailing
            // space / shrink) via the same applyComposingDelta, then consume.
            // Enter still arrives via the IME's separate sendKeyEvent, exactly
            // as in the eager finishComposingText path. fullEditor (Standard /
            // CJK) uses the floating composer and is unaffected; a bare commit
            // with no active composition (composingText empty) falls through to
            // the normal dispatch below.
            if (!fullEditor && composingText.isNotEmpty()) {
                val filtered = committedText.replace("\n", "").replace("\r", "")
                if (filtered.isNotEmpty()) applyComposingDelta(composingText, filtered)
                composingText = ""
                _composingText.value = ""
                notifyImeSelection()
                return true
            }

            if (committedText.isNotEmpty()) {
                // The floating-composer model never projects the in-flight
                // composition into the terminal, so there is nothing to erase
                // here — just dispatch the final committed text.
                //
                // Filter newlines from committed text — Enter is handled by
                // sendKeyEvent(KEYCODE_ENTER) when the IME sends both.
                // For IMEs that only commitText("\n"), a deferred fallback
                // dispatches Enter after one frame if sendKeyEvent didn't fire.
                val filtered = committedText.replace("\n", "").replace("\r", "")
                if (filtered.isNotEmpty()) {
                    // If the IME earlier marked a region as composing (English
                    // autocorrect: "teh " selected for replacement with "the"),
                    // back-space that many chars before laying the new text
                    // down. See [setComposingRegion] + [pendingReplacementLength].
                    if (pendingReplacementLength > 0) {
                        sendBackspaces(pendingReplacementLength)
                        pendingReplacementLength = 0
                    }
                    if (committedText.contains('\n') || committedText.contains('\r')) {
                        // #298 (agross): mixed text+newline commit — IMEs that
                        // submit the whole line as ONE commit ("ls\n"), and
                        // multi-line clipboard paste. The newlines used to be
                        // silently dropped, so successive lines concatenated on
                        // one prompt ("ls⏎ls⏎exit" arrived as "lslsexit").
                        // Dispatch text and Enters synchronously, in order. No
                        // deferral here: the dual commitText("\n")+sendKeyEvent
                        // delivery that needs dedup only happens for the
                        // pure-newline commit handled in the branch below.
                        val normalized = committedText
                            .replace("\r\n", "\n").replace('\r', '\n')
                        normalized.split('\n').forEachIndexed { i, segment ->
                            if (i > 0) {
                                this@ImeInputView.dispatchKeyEvent(
                                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
                                )
                                // Line boundary — same IME-state reset as the
                                // sendKeyEvent(ENTER) path.
                                editable?.clear()
                                onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
                            }
                            if (segment.isNotEmpty()) sendTextInput(segment)
                        }
                        // One IME restart after the batch (not per segment) —
                        // same SwiftKey line-boundary reset as the key path.
                        if (fullEditor) postRestartImeAtLineBoundary()
                    } else {
                        sendTextInput(filtered)
                    }
                } else if (committedText.contains('\n') || committedText.contains('\r')) {
                    // #298: pure-newline commit while still composing — flush the
                    // in-flight line before the deferred Enter (same ordering fix
                    // as the sendKeyEvent path, for IMEs that submit via commitText).
                    flushComposingBeforeEnter()
                    // Defer Enter dispatch — give sendKeyEvent a chance to handle it
                    enterHandledByKeyEvent = false
                    handler.removeCallbacks(enterFallbackRunnable)
                    handler.postDelayed(enterFallbackRunnable, 16)
                }
            }
            composingText = ""
            _composingText.value = ""
            // Do NOT clear the editable here — Gboard needs recent chars
            // in the Editable to decide whether to offer an autocorrect
            // suggestion and to route replacement via setComposingRegion.
            // Clearing on every commit leaves it permanently empty, which
            // drops Gboard into a dumb per-char insert mode (the #99
            // symptom). Enter still clears on the sendKeyEvent path to
            // avoid one command's text carrying into the next.
            //
            // Runaway protection: cap the editable length at
            // EDITABLE_MAX_LENGTH; if a caller commits more than that
            // without hitting Enter, drop the oldest half.
            editable?.let { ed ->
                if (ed.length > EDITABLE_MAX_LENGTH) {
                    ed.delete(0, ed.length / 2)
                }
            }
            // Tell the IME the cursor moved. Without this Gboard keeps
            // its tracked selection frozen at (0, 0), never re-queries
            // [getTextBeforeCursor], and therefore never enters the
            // composition protocol for word-level autocorrect. See
            // [notifyImeSelection].
            notifyImeSelection()
            return true
        }

        /**
         * Dispatch backspaces to the terminal without triggering the
         * enclosing view's key listener. That listener (see Terminal's
         * [setOnKeyListener]) calls [resetImeBuffer] on every
         * ACTION_DOWN to keep the IME's editable in sync with physical
         * keyboard input. For our internal autocorrect / replacement
         * flow that side-effect is actively harmful: it wipes the
         * Editable Gboard has just been notified about, so the IME
         * loses context one keystroke after we just established it.
         * Route straight to [KeyboardHandler.onKeyEvent] instead.
         */
        private fun sendBackspaces(count: Int) {
            repeat(count.coerceAtLeast(0)) {
                val native = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                keyboardHandler.onKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(native),
                )
            }
        }

        private fun sendTextInput(text: String) {
            if (text.isNotEmpty()) {
                rememberCommittedPrintableEcho(text)
                keyboardHandler.onTextInput(text.toByteArray(Charsets.UTF_8))
            }
        }

        /**
         * Cancel any pending deferred dispatches. Called from
         * [ImeInputView.onDetachedFromWindow] so a posted fallback doesn't
         * fire into a dead view after teardown.
         */
        internal fun cancelPending() {
            handler.removeCallbacks(enterFallbackRunnable)
            handler.removeCallbacks(echoDrainScheduler)
            handler.removeCallbacks(echoDrainRunnable)
            enterHandledByKeyEvent = false
            echoDrainPosted = false
            committedThisMessage.clear()
        }

        /** Drop in-flight composition state without touching the terminal output. */
        internal fun resetComposition() {
            composingText = ""
            _composingText.value = ""
            pendingReplacementLength = 0
            eagerControlText = ""
            // Stale entries here would suppress legitimate keypresses
            // typed after a mode-change restartInput. Clear with the rest
            // of the per-connection state.
            pendingSuppressChars.clear()
            handler.removeCallbacks(echoDrainScheduler)
            handler.removeCallbacks(echoDrainRunnable)
            committedThisMessage.clear()
            echoDrainPosted = false
        }

        /**
         * Drop any in-flight composition before the enclosing view calls
         * [InputMethodManager.restartInput]. Previously this also sent
         * backspaces to the terminal because the composition had been
         * projected inline; in the floating-composer model nothing was
         * ever sent, so we only need to clear the tracker + overlay.
         */
        internal fun flushCompositionAsBackspaces() {
            if (composingText.isNotEmpty()) {
                composingText = ""
                _composingText.value = ""
            }
            pendingReplacementLength = 0
            eagerControlText = ""
        }

        /** Drop any pending setComposingRegion replacement without consuming it. */
        internal fun resetPendingReplacement() {
            pendingReplacementLength = 0
        }
    }

    /**
     * Build an InputConnection from an explicit user-chosen flag set.
     * Bypasses the Secure/Standard preset logic; the caller is
     * presumed to know what they want. Conflicting bits (e.g. both
     * VISIBLE_PASSWORD and fullEditor) are preserved verbatim — the
     * Settings UI documents the trade-offs and the user picks.
     */
    private fun openCustomFlagInputConnection(
        outAttrs: EditorInfo,
        flags: ImeFlagBundle,
    ): InputConnection {
        val noLearning = if (flags.noPersonalizedLearning) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0
        val noExtractUi = if (flags.noExtractUi) EditorInfo.IME_FLAG_NO_EXTRACT_UI else 0
        outAttrs.imeOptions = outAttrs.imeOptions or
            noExtractUi or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            noLearning or
            EditorInfo.IME_ACTION_NONE

        var inputType = EditorInfo.TYPE_CLASS_TEXT
        if (flags.visiblePassword) inputType = inputType or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        if (flags.noSuggestions) inputType = inputType or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (flags.autoCorrect) inputType = inputType or EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
        outAttrs.inputType = inputType
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        Log.d(
            TAG,
            "onCreateInputConnection: CUSTOM" +
                " inputType=0x${outAttrs.inputType.toString(16)}" +
                " imeOptions=0x${outAttrs.imeOptions.toString(16)}" +
                " fullEditor=${flags.fullEditor}" +
                " flags=$flags",
        )
        return TerminalInputConnection(this, flags.fullEditor).also { activeConnection = it }
    }

    internal companion object {
        // Logcat tag for IME diagnostics. Logged from each InputConnection
        // callback so users hitting input regressions (e.g. #110) can
        // capture a logcat that shows which methods the IME is calling
        // and what we're returning. Enable in-app via Settings → Logcat
        // Capture, then `grep ImeInputView /sdcard/Download/haven-logcat.txt`.
        const val TAG = "ImeInputView"

        /**
         * Ceiling on an unattended held-DEL takeover. Long enough for a
         * genuine hold to clear a full command line at the repeat interval,
         * short enough that a missing key up costs seconds rather than the
         * life of the view.
         */
        const val HELD_DEL_MAX_TAKEOVER_MS = 5_000L

        // How long after the window returns the terminal waits before taking
        // focus (and so the keyboard) back. It was chosen to clear the frame
        // on which Compose flushes deferred composition disposal; a Xiaomi
        // Mi 17 showed that flush arriving well after 100ms, so the crash
        // guard now lives in onInteropReset and this is a UX delay only —
        // long enough not to fight the returning window, short enough that
        // the keyboard feels immediate.
        const val REFOCUS_DELAY_MS = 100L

        /**
         * Whether a hardware-keyboard key press should wipe the IME's tracked
         * buffer before the terminal processes the event.
         *
         * Called from the enclosing composable's setOnKeyListener. Physical
         * keys that produce text (letters, digits, symbols) or navigate
         * (arrows, backspace, Tab, function keys) deliberately do NOT trigger
         * a reset — the previous blanket reset-on-ACTION_DOWN policy wiped
         * the IME's context mid-word, so a single Shift+A on a physical
         * keyboard would lose Gboard's autocorrect state for everything
         * typed on the soft keyboard before that (#99 follow-up).
         *
         * Cross-modality autocorrect is inherently imperfect — physical-key
         * text bypasses the Editable, so the IME can't track it either way —
         * so leaving the IME's state alone for ordinary keys is at least as
         * good as clearing, and strictly better for the common case where a
         * user inserts a capital letter with Shift while mostly typing on
         * the soft keyboard.
         *
         * Reset fires only on keys that semantically clear or interrupt the
         * current shell line — Enter / Escape — so the IME starts the next
         * command context fresh.
         */
        fun shouldResetImeBufferOnKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_ESCAPE,
            -> true

            else -> false
        }

        /**
         * Whether the active IME requires `TYPE_TEXT_FLAG_AUTO_CORRECT` on the
         * Secure-mode input field to recognise it as a real text editor.
         *
         * Samsung Keyboard's `InputMethodManager_LC` framework (Galaxy S,
         * Android 16+) gates input dispatch on AUTO_CORRECT presence —
         * without it, every `commitText` is silently dropped (#110). Gboard
         * and most other IMEs honour AUTO_CORRECT literally and apply
         * autocorrect-on-space, which rewrites typed commands (#115). The
         * resolution is to set AUTO_CORRECT only for IMEs in the gating
         * family.
         *
         * Detected via `Settings.Secure.DEFAULT_INPUT_METHOD` — returns
         * something like `com.samsung.android.honeyboard/.service.HoneyBoardService`.
         * Match by package prefix so future Samsung releases (different
         * package names) keep working without code changes.
         */
        fun isAutoCorrectGatedIme(context: Context): Boolean {
            val activeIme = try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            } catch (_: Exception) {
                null
            } ?: return false
            val pkg = activeIme.substringBefore('/')
            return AUTO_CORRECT_GATED_IME_PACKAGES.any { gated ->
                pkg == gated || pkg.startsWith("$gated.")
            }
        }

        /**
         * IMEs that require `TYPE_TEXT_FLAG_AUTO_CORRECT` on the Secure-mode
         * input field for input to dispatch at all. Match by package prefix.
         * See [isAutoCorrectGatedIme]. Add new entries here when logcat
         * reveals an IME silently dropping `commitText` calls; revert any
         * AUTO_CORRECT-side-effect bug reports by removing entries.
         */
        private val AUTO_CORRECT_GATED_IME_PACKAGES = setOf(
            // Samsung Keyboard family — all known package names. Samsung
            // changes the package per major Android release; prefix-match
            // keeps us covered without churn.
            "com.samsung.android.honeyboard",
            "com.samsung.android.icecone",
            "com.sec.android.inputmethod",
        )

        /**
         * Upper bound on `deleteSurroundingText.leftLength`. Real IMEs request
         * at most a few chars at a time; this cap protects against a runaway
         * value from a buggy IME freezing the UI thread in a DEL-key loop.
         */
        const val MAX_DELETE_SURROUNDING = 4096

        /**
         * Upper bound on an IME-requested `setComposingRegion` length. Real
         * IMEs use this for word-level corrections (≤20 chars). Capping
         * prevents a misaligned IME state from sending a runaway backspace
         * count on the following commit.
         */
        const val MAX_REPLACEMENT_LENGTH = 64

        /**
         * Soft ceiling on the [BaseInputConnection] editable length. Enter
         * clears it normally, but a user who types a very long command
         * without pressing Enter would otherwise accumulate forever.
         * When exceeded we drop the first half — plenty of context left
         * for Gboard's correction window (usually the current word),
         * without unbounded memory growth.
         */
        const val EDITABLE_MAX_LENGTH = 512
    }
}

/**
 * Flags for `showSoftInput` given the current [Configuration] keyboard state.
 *
 * #511: on a Meta Quest 3 with a keyboard attached, the virtual keyboard rose over
 * the session on every keypress. SHOW_FORCED is documented to hold the IME open
 * "until the user explicitly closes it", which overrides the platform's own rule
 * that a usable hardware keyboard suppresses the soft one. Passing no flags hands
 * that decision back to the platform, which still honours the user's "show virtual
 * keyboard" setting for anyone who wants both.
 *
 * [hardKeyboardHidden] is part of the test because a folded or docked device
 * reports KEYBOARD_QWERTY while the keys are physically unreachable; there the soft
 * keyboard is the only way to type and SHOW_FORCED still earns its keep.
 *
 * Top-level and internal so the policy can be tested without a Context — the
 * values are plain Configuration constants.
 */
@Suppress("DEPRECATION")
internal fun imeShowFlags(keyboard: Int, hardKeyboardHidden: Int): Int {
    val physicalKeyboardAttached = keyboard != Configuration.KEYBOARD_NOKEYS &&
        hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    return if (physicalKeyboardAttached) 0 else InputMethodManager.SHOW_FORCED
}
