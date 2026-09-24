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

/**
 * Callback interface for terminal gesture events.
 *
 * The host app implements this to inject behavior (e.g. SGR mouse sequences)
 * at gesture decision points without needing a separate gesture interceptor layer.
 * Terminal.kt calls these at the appropriate moments; returning true suppresses
 * the default behavior for that gesture.
 */
interface TerminalGestureCallback {
    /** Single tap at terminal cell. Return true to suppress default (hyperlink/focus). */
    fun onTap(col: Int, row: Int): Boolean = false

    /**
     * Long-press at terminal cell. Return `true` if handled as a discrete action
     * (e.g. a right-click) — the terminal then ignores the trailing drag. Return
     * `false` to *arm* a copy-mode selection: a subsequent drag is forwarded via
     * [onMouseDrag] so the remote runs its own pane-aware selection. (#186)
     */
    fun onLongPress(col: Int, row: Int): Boolean = false

    /** Vertical scroll at terminal cell. Return true to suppress default (scrollback). */
    fun onScroll(col: Int, row: Int, scrollUp: Boolean): Boolean = false

    /**
     * A one-finger swipe has just been classified, offered as a *held*
     * gesture before any [onScroll] quantization (#524). Return `true` to
     * claim it: the host then receives [onSwipeHold] whenever the finger
     * reverses along the gesture's axis and [onSwipeHoldEnd] exactly once
     * when the finger lifts (or the gesture is cancelled) — and no
     * [onScroll] events are emitted for the remainder of the gesture.
     * Hosts use this to run their own press-and-hold key repeat: direction
     * selects the key, holding repeats it, lifting stops — distance
     * deliberately does not matter. Return `false` (the default) for the
     * classic quantized [onScroll] behaviour; horizontal swipes then stay
     * unclaimed for the host pager as before.
     */
    fun onSwipeHoldStart(col: Int, row: Int, direction: SwipeHoldDirection): Boolean = false

    /** The held swipe's finger reversed along its axis (#524). */
    fun onSwipeHold(direction: SwipeHoldDirection) {}

    /** The held swipe ended — finger lifted or gesture cancelled (#524). */
    fun onSwipeHoldEnd() {}

    /**
     * Mouse drag while a button is held. Called once at [MouseDragPhase.Start]
     * (when the press is first promoted to a drag), repeatedly at
     * [MouseDragPhase.Move] for each cell change, and once at
     * [MouseDragPhase.End] when the press releases.
     *
     * Reached after a long-press has armed the gesture (see [onLongPress]), or
     * when movement crosses touch slop while the host opts into immediate
     * mouse-drag. A drag without either of those scrolls instead.
     *
     * Return `true` from [MouseDragPhase.Start] to claim the gesture — the
     * terminal will then route subsequent moves and the end here, and skip
     * its built-in scroll handling. Returning `false` from Start lets the
     * terminal fall back to scroll-via-wheel-events. The return
     * value is ignored for Move and End — once a drag is claimed it stays
     * claimed for the rest of that gesture.
     *
     * Used for forwarding press+motion+release sequences to a remote that's
     * in mouse-tracking mode (tmux with `set -g mouse on`, etc.) so the
     * remote can do its own selection across its own scrollback. Without
     * motion forwarding, the remote sees only press+release and can't
     * select anything. (#94)
     */
    fun onMouseDrag(col: Int, row: Int, phase: MouseDragPhase): Boolean = false
}

/** Phase of a mouse-drag gesture. See [TerminalGestureCallback.onMouseDrag]. */
enum class MouseDragPhase {
    Start,
    Move,
    End,
}

/**
 * Direction of a held one-finger swipe, in literal finger terms.
 * See [TerminalGestureCallback.onSwipeHoldStart]. (#524)
 */
enum class SwipeHoldDirection {
    Up,
    Down,
    Left,
    Right,
}
