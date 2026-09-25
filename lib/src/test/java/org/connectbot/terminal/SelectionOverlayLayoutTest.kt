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

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The floating Copy / more-options / Paste pill row shown while a selection
 * is active is positioned at the selection-end column with a right-edge
 * clamp (#661): the clamp used to budget the row for two pills while the
 * row renders three when paste is available, so a selection ending near the
 * right margin let the Paste pill hang past the screen edge.
 */
class SelectionOverlayLayoutTest {

    @Test
    fun rowWidth_withPaste_budgetsThreePills() {
        // Copy + more-options + Paste, 8dp gaps between each pair.
        assertEquals(48.dp * 3 + 8.dp * 2, selectionOverlayRowWidth(hasPaste = true))
    }

    @Test
    fun rowWidth_withoutPaste_budgetsTwoPills() {
        // Copy + more-options only (paste falls back to the keyboard toolbar).
        assertEquals(48.dp * 2 + 8.dp, selectionOverlayRowWidth(hasPaste = false))
    }

    @Test
    fun clampX_pullsOversizedRowFullyInsideTheRightEdge() {
        val rowWidthPx = 320f // three 48dp pills at 2x density + gaps
        val availableWidth = 1000f
        // Selection ends at the right margin: raw x would start the row at
        // the edge; the clamp must retract by the FULL row width.
        val clamped = clampSelectionOverlayX(rawX = 1000f, rowWidthPx = rowWidthPx, availableWidth = availableWidth)
        assertEquals(availableWidth - rowWidthPx, clamped, 0.001f)
    }

    @Test
    fun clampX_keepsMidScreenRowAtTheSelection() {
        val clamped = clampSelectionOverlayX(rawX = 300f, rowWidthPx = 320f, availableWidth = 1000f)
        assertEquals(300f, clamped, 0.001f)
    }

    @Test
    fun clampX_neverGoesNegativeOnNarrowViewports() {
        val clamped = clampSelectionOverlayX(rawX = 10f, rowWidthPx = 320f, availableWidth = 200f)
        assertEquals(0f, clamped, 0.001f)
    }

    @Test
    fun clampY_pullsOffBottomAnchorBackInsideTheViewport() {
        // Multi-screen drag-select (long-press low, drag up through the top
        // edge zone): autoscroll shifts the START anchor one row per scroll
        // step, so the selection's visually-last cell ends up ~2 screens
        // below the viewport. The raw Y is a huge positive number and the
        // pill row rendered entirely off-screen (maintainer repro
        // 2026-09-25); the clamp must retract it by the pill height.
        val clamped = clampSelectionOverlayY(
            rawY = 2400f,
            buttonHeightPx = 128f,
            availableHeight = 2000f,
        )
        assertEquals(2000f - 128f, clamped, 0.001f)
    }

    @Test
    fun clampY_keepsInViewportAnchorAtItsPosition() {
        val clamped = clampSelectionOverlayY(rawY = 300f, buttonHeightPx = 128f, availableHeight = 2000f)
        assertEquals(300f, clamped, 0.001f)
    }

    @Test
    fun clampY_neverGoesNegativeAboveTheTopEdge() {
        // A selection ending on row 0 puts the raw Y at -48dp-offset (minus
        // any keyboard shift); the menu pins to the top edge instead.
        val clamped = clampSelectionOverlayY(rawY = -50f, buttonHeightPx = 128f, availableHeight = 2000f)
        assertEquals(0f, clamped, 0.001f)
    }

    @Test
    fun clampY_tinyViewportNeverThrows() {
        // A coerceIn(min, max) formulation throws when min > max; the clamp
        // must degenerate to the top edge on viewports shorter than the pill.
        val clamped = clampSelectionOverlayY(rawY = 100f, buttonHeightPx = 128f, availableHeight = 50f)
        assertEquals(0f, clamped, 0.001f)
    }
}