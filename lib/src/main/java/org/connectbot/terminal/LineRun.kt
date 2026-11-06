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

import androidx.compose.runtime.Immutable

/**
 * A run of consecutive terminal cells sharing one style.
 *
 * This is the compact storage unit behind [TerminalLine]: instead of one
 * [TerminalLine.Cell] object per column (64 bytes each), a line holds a few
 * runs. A monochrome 120-column line collapses from 120 cell objects
 * (~7.7 KB) to a single run (~0.3 KB). The scrollback stays byte-identical
 * in content — only the representation shrinks.
 *
 * [text] holds exactly one Char per cell (the per-cell `char` field of the
 * old Cell model; combining marks live in [combining]). Per-cell widths live
 * in [widths] only when at least one cell is double-width (CJK); `null`
 * means every cell is width 1, the overwhelmingly common case.
 *
 * [combining] is `null` when no cell carries combining characters; otherwise
 * it is a per-cell list of Char lists (empty list = no combining on that
 * cell). This mirrors the old `Cell.combiningChars` contract, including the
 * low-surrogate-of-a-supplementary-pair convention.
 *
 * [imageId] / [placementId] are run-level by construction: libvterm reports
 * Kitty placeholder runs as isolated (#583).
 */
@Immutable
internal data class LineRun(
    val text: String,
    val style: RunStyle,
    val widths: ByteArray? = null,
    val combining: List<List<Char>>? = null,
    val imageId: Long = 0L,
    val placementId: Long = 0L,
) {
    /** Number of cells (columns) covered by this run. */
    val cellCount: Int
        get() = text.length

    /**
     * Structural equality for Compose recomposition: [TerminalLine] is
     * compared line-by-line on every frame, and two runs built from the same
     * native row must compare equal even though their [widths] arrays are
     * distinct instances. Content equality for the array, identity for the
     * rest (text and style are immutable values).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LineRun) return false
        return text == other.text &&
            style == other.style &&
            java.util.Arrays.equals(widths, other.widths) &&
            combining == other.combining &&
            imageId == other.imageId &&
            placementId == other.placementId
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + java.util.Arrays.hashCode(widths)
        result = 31 * result + (combining?.hashCode() ?: 0)
        result = 31 * result + imageId.hashCode()
        result = 31 * result + placementId.hashCode()
        return result
    }

    /**
     * Display width of the cell at [index] (1 or 2). Cheap: falls back to 1
     * when the run has no double-width cells at all.
     */
    fun widthAt(index: Int): Int {
        val w = widths
        return if (w == null) 1 else w[index].toInt() and 0xFF
    }

    /**
     * Combining characters of the cell at [index]; empty list when none.
     */
    fun combiningAt(index: Int): List<Char> {
        val c = combining
        return if (c == null) TerminalLine.EMPTY_COMBINING_CHARS else c[index]
    }

    /**
     * Materialize the cell at [index] as the legacy [TerminalLine.Cell] type.
     * Used by consumers that still speak per-cell (selection, accessibility).
     */
    fun cellAt(index: Int): TerminalLine.Cell {
        val s = style
        return TerminalLine.Cell(
            char = text[index],
            combiningChars = combiningAt(index),
            fgColor = ColorCache.get(s.fgRed, s.fgGreen, s.fgBlue),
            bgColor = ColorCache.get(s.bgRed, s.bgGreen, s.bgBlue),
            bold = s.bold,
            italic = s.italic,
            underline = s.underline,
            blink = s.blink,
            reverse = s.reverse,
            strike = s.strike,
            width = widthAt(index),
            imageId = imageId,
            placementId = placementId,
        )
    }

    companion object {
        /**
         * Build one run from a legacy list of cells with identical style.
         * Used by the compatibility constructor of [TerminalLine].
         */
        fun fromCells(cells: List<TerminalLine.Cell>): LineRun {
            val first = cells[0]
            val sb = StringBuilder(cells.size)
            var anyWide = false
            var anyCombining = false
            for (c in cells) {
                sb.append(c.char)
                if (c.width == 2) anyWide = true
                if (c.combiningChars.isNotEmpty()) anyCombining = true
            }
            return LineRun(
                text = sb.toString(),
                style = RunStyle.fromCell(first),
                widths = if (anyWide) ByteArray(cells.size) { i -> cells[i].width.toByte() } else null,
                combining = if (anyCombining) cells.map { it.combiningChars } else null,
                imageId = first.imageId,
                placementId = first.placementId,
            )
        }
    }
}

/**
 * Packed run-level style: RGB components and attribute flags in plain Ints
 * instead of two Compose [Color] objects and eight per-cell fields.
 */
@Immutable
internal data class RunStyle(
    val fgRed: Int,
    val fgGreen: Int,
    val fgBlue: Int,
    val bgRed: Int,
    val bgGreen: Int,
    val bgBlue: Int,
    val flags: Int,
) {
    val bold: Boolean get() = flags and FLAG_BOLD != 0
    val italic: Boolean get() = flags and FLAG_ITALIC != 0
    val underline: Int get() = (flags ushr 2) and 0x3
    val blink: Boolean get() = flags and FLAG_BLINK != 0
    val reverse: Boolean get() = flags and FLAG_REVERSE != 0
    val strike: Boolean get() = flags and FLAG_STRIKE != 0

    /** True when this style renders exactly like [cell]'s style. */
    fun matches(cell: TerminalLine.Cell): Boolean =
        fgRed == (cell.fgColor.red * 255).toInt() &&
            fgGreen == (cell.fgColor.green * 255).toInt() &&
            fgBlue == (cell.fgColor.blue * 255).toInt() &&
            bgRed == (cell.bgColor.red * 255).toInt() &&
            bgGreen == (cell.bgColor.green * 255).toInt() &&
            bgBlue == (cell.bgColor.blue * 255).toInt() &&
            bold == cell.bold &&
            italic == cell.italic &&
            underline == cell.underline &&
            blink == cell.blink &&
            reverse == cell.reverse &&
            strike == cell.strike

    companion object {
        private const val FLAG_BOLD = 0x01
        private const val FLAG_ITALIC = 0x02
        private const val FLAG_BLINK = 0x10
        private const val FLAG_REVERSE = 0x20
        private const val FLAG_STRIKE = 0x40

        fun fromCell(cell: TerminalLine.Cell): RunStyle {
            var f = 0
            if (cell.bold) f = f or FLAG_BOLD
            if (cell.italic) f = f or FLAG_ITALIC
            f = f or ((cell.underline and 0x3) shl 2)
            if (cell.blink) f = f or FLAG_BLINK
            if (cell.reverse) f = f or FLAG_REVERSE
            if (cell.strike) f = f or FLAG_STRIKE
            return RunStyle(
                fgRed = (cell.fgColor.red * 255).toInt(),
                fgGreen = (cell.fgColor.green * 255).toInt(),
                fgBlue = (cell.fgColor.blue * 255).toInt(),
                bgRed = (cell.bgColor.red * 255).toInt(),
                bgGreen = (cell.bgColor.green * 255).toInt(),
                bgBlue = (cell.bgColor.blue * 255).toInt(),
                flags = f,
            )
        }

        fun fromComponents(
            fgRed: Int,
            fgGreen: Int,
            fgBlue: Int,
            bgRed: Int,
            bgGreen: Int,
            bgBlue: Int,
            bold: Boolean,
            italic: Boolean,
            underline: Int,
            blink: Boolean,
            reverse: Boolean,
            strike: Boolean,
        ): RunStyle {
            var f = 0
            if (bold) f = f or FLAG_BOLD
            if (italic) f = f or FLAG_ITALIC
            f = f or ((underline and 0x3) shl 2)
            if (blink) f = f or FLAG_BLINK
            if (reverse) f = f or FLAG_REVERSE
            if (strike) f = f or FLAG_STRIKE
            return RunStyle(fgRed, fgGreen, fgBlue, bgRed, bgGreen, bgBlue, f)
        }
    }
}
