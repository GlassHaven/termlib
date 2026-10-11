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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #687: rotating portrait -> landscape -> portrait strands a duplicate prompt
 * pinned to the top row.
 *
 * Device-verified (/tmp/repro687b/rot2.log): on rotation-back the sizing path
 * samples the window at its FULL portrait height for one frame — the new
 * configuration's height arrives before the IME inset re-dispatches — so it
 * computes 40 rows where the settled grid is 23, with the IME inset reading 0
 * while the keyboard is actually up:
 *
 *   avail=1080x1709 imeBottom=0    -> 40x51   (cur 14x97)   ← phantom grow
 *   avail=1080x1499 imeBottom=962  -> 38x51   (cur 40x51)
 *   ...animating down...
 *   avail=1080x999  imeBottom=962  -> 23x51   (cur 40x51)   ← settled
 *
 * The 40-row sample reaches the emulator grid AND the PTY (setPtySize 51x40,
 * then 51x23 458ms later). The server SIGWINCH-repaints its prompt for the
 * 40-row grid (row 39); the 40->23 rows-only shrink strands that repaint at
 * row 0 while the real prompt sits at row 22.
 *
 * The sample is untrustworthy exactly when the IME is supposed to be shown but
 * its inset reads zero — a full-height window and a visible IME are mutually
 * exclusive — and the measurement would GROW the grid into that phantom space.
 * A genuine keyboard hide also reads imeBottom == 0, but then shouldShowIme is
 * false (the host owns showSoftKeyboard), so it must still resize (#206, #554).
 */
class RotationTransientRowsTest {

    @Test
    fun `rotation-back phantom grow with the keyboard up is held`() {
        // The device sequence: reflow-to-keyboard (the shell runs a TUI hint /
        // alt screen), keyboard up, inset not yet re-arrived, rows growing.
        assertTrue(
            "the 40-row sample (imeBottom=0 while the IME should be shown, " +
                "growing 14->40) must be held back from the emulator + PTY",
            isRotationTransientRows(
                reflowToKeyboard = true,
                shouldShowIme = true,
                imeBottomPx = 0,
                growingRows = true,
            ),
        )
    }

    @Test
    fun `settled portrait size with the inset re-arrived is not held`() {
        // Once the IME inset re-dispatches (imeBottom=962) the measurement is
        // real again — the shrink to 23 rows must propagate.
        assertFalse(
            isRotationTransientRows(
                reflowToKeyboard = true,
                shouldShowIme = true,
                imeBottomPx = 962,
                growingRows = false,
            ),
        )
    }

    @Test
    fun `genuine keyboard hide still resizes`() {
        // #206 / #554: a real keyboard hide reads imeBottom == 0, but the host
        // has set showSoftKeyboard=false, so shouldShowIme is false and the
        // row grow must reach the PTY.
        assertFalse(
            "a keyboard hide (shouldShowIme=false) must never be held — " +
                "that is the #206/#554 path",
            isRotationTransientRows(
                reflowToKeyboard = true,
                shouldShowIme = false,
                imeBottomPx = 0,
                growingRows = true,
            ),
        )
    }

    @Test
    fun `primary-buffer shell is not held`() {
        // #206: on the primary buffer the grid uses the keyboard-hidden
        // baseline, so a keyboard toggle never resizes at all. The transient
        // gate only applies to the reflow-to-keyboard path.
        assertFalse(
            isRotationTransientRows(
                reflowToKeyboard = false,
                shouldShowIme = true,
                imeBottomPx = 0,
                growingRows = true,
            ),
        )
    }

    @Test
    fun `rotation shrink is not held`() {
        // A rows-only SHRINK is never the phantom — the phantom is always a
        // grow into the missing inset. Holding a shrink would strand the grid
        // too small.
        assertFalse(
            isRotationTransientRows(
                reflowToKeyboard = true,
                shouldShowIme = true,
                imeBottomPx = 0,
                growingRows = false,
            ),
        )
    }

    @Test
    fun `physical keyboard is not held`() {
        // With a hardware keyboard the host keeps shouldShowIme false, so a
        // full-height measurement is genuine and must resize.
        assertFalse(
            isRotationTransientRows(
                reflowToKeyboard = true,
                shouldShowIme = false,
                imeBottomPx = 0,
                growingRows = true,
            ),
        )
    }
}
