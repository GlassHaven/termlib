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
import androidx.compose.ui.graphics.Color

/**
 * Represents a single line in the terminal screen.
 *
 * Each line is immutable and tracks its last modification time for efficient redraws.
 * This is part of the architecture where each terminal line is a separate Kotlin class.
 */
@Immutable
internal data class TerminalLine(
    val row: Int,
    val cells: List<Cell>,
    val lastModified: Long = System.nanoTime(),
    val semanticSegments: List<SemanticSegment> = emptyList(),
    /**
     * True if this line ended with a soft wrap (visual line break due to terminal width),
     * false if it ended with a hard break (actual newline character).
     *
     * This is used when copying text to avoid inserting spurious newlines in wrapped
     * long commands. For example, a command like "echo foo bar baz..." that wraps to
     * multiple lines should be copied as a single line without embedded newlines.
     */
    val softWrapped: Boolean = false,
    // Inline images visible on this line (#583), sorted by z band then asset id.
    // Filled in by the emulator when building a snapshot; empty otherwise.
    val images: List<ImageSlice> = emptyList(),
) {
    /**
     * Get the text content of this line as a string.
     */
    val text: String by lazy {
        buildString {
            cells.forEach { cell ->
                if (cell.isImagePlaceholder) {
                    // Inline-image placeholder cells (#583) draw an image over
                    // this column; the underlying marker glyph is not content.
                    append(' ')
                } else {
                    append(cell.char)
                    cell.combiningChars.forEach { append(it) }
                }
            }
        }
    }

    /**
     * Text with one character per terminal cell.
     *
     * This keeps string indexes aligned with cell columns for hit-testing and
     * URL range calculations. Combining characters are intentionally omitted
     * because they do not occupy their own terminal cells.
     */
    internal val columnText: String by lazy {
        buildString {
            cells.forEach { cell ->
                append(if (cell.isImagePlaceholder) ' ' else cell.char)
            }
        }
    }

    /**
     * Get the semantic type at a specific column.
     * Returns DEFAULT if no segment covers that column.
     */
    fun getSemanticTypeAt(col: Int): SemanticType = semanticSegments.firstOrNull { it.contains(col) }?.semanticType
        ?: SemanticType.DEFAULT

    /**
     * Get all segments of a specific semantic type.
     */
    fun getSegmentsOfType(type: SemanticType): List<SemanticSegment> = semanticSegments.filter { it.semanticType == type }

    /**
     * Check if this line contains any prompt segments.
     */
    fun hasPrompt(): Boolean = semanticSegments.any { it.semanticType == SemanticType.PROMPT }

    /**
     * Auto-detected URLs in the line text (not from OSC 8 semanticSegments).
     * Lazily computed and cached per TerminalLine instance.
     * Each triple is (startCol, endColExclusive, url).
     */
    internal val autoDetectedUrls: List<Triple<Int, Int, String>> by lazy {
        if (cells.isEmpty()) return@lazy emptyList()
        URL_REGEX.findAll(columnText).mapNotNull { match ->
            val trimmed = match.value.trimDetectedUrl()
            if (trimmed.isEmpty()) {
                null
            } else {
                Triple(match.range.first, match.range.first + trimmed.length, trimmed)
            }
        }.toList()
    }

    /**
     * Get the hyperlink URL at a specific column, if any.
     * Checks OSC 8 semantic segments first. If no OSC 8 segment covers the column and
     * [autoDetectUrls] is true, falls back to plain-text URL auto-detection.
     * Returns null if no hyperlink covers that column.
     *
     * @param col The zero-based column index to query.
     * @param autoDetectUrls Whether to fall back to auto-detected plain-text URLs when no
     *                       OSC 8 segment is present. Defaults to false.
     */
    fun getHyperlinkUrlAt(col: Int, autoDetectUrls: Boolean = false): String? {
        // OSC 8 segments take priority
        val osc8 = semanticSegments.firstOrNull {
            it.semanticType == SemanticType.HYPERLINK && it.contains(col)
        }?.metadata
        if (osc8 != null) return osc8
        // Fallback: auto-detected plain-text URLs (only when enabled)
        if (!autoDetectUrls) return null
        return autoDetectedUrls.firstOrNull { col >= it.first && col < it.second }?.third
    }

    /**
     * Get the prompt ID for this line (from the first segment that has one).
     */
    val promptId: Int
        get() = semanticSegments.firstOrNull { it.promptId >= 0 }?.promptId ?: -1

    /**
     * A single cell in the terminal line with character and formatting.
     */
    @Immutable
    data class Cell(
        val char: Char,
        val combiningChars: List<Char> = emptyList(),
        val fgColor: Color,
        val bgColor: Color,
        val bold: Boolean = false,
        val italic: Boolean = false,
        // 0=none, 1=single, 2=double, 3=curly
        val underline: Int = 0,
        val blink: Boolean = false,
        val reverse: Boolean = false,
        val strike: Boolean = false,
        // 1 for normal, 2 for fullwidth (CJK)
        val width: Int = 1,
        // Kitty unicode-placeholder metadata (#583): the low image id (native chars[14])
        // and the placement id carried on the raw foreground colour. 0 for normal cells.
        val imageId: Long = 0L,
        val placementId: Long = 0L,
    )

    companion object {
        /**
         * Shared empty list to avoid allocation for 99% of cells without combining chars.
         * This single shared instance prevents ~1,920 empty list allocations per frame.
         */
        val EMPTY_COMBINING_CHARS = emptyList<Char>()

        /**
         * URL regex for auto-detection in terminal text. Matches:
         * - http://, https://, and ftp:// URLs (with any host including IP:port)
         * - Bare domain names with common TLDs, optional :port and /path
         * - IP:port patterns (e.g. 192.168.1.1:8080, 10.0.0.1:3000)
         *
         * Pure Kotlin regex (no android.util.Patterns dependency) for JUnit testability.
         */
        internal val URL_REGEX = Regex(
            // Scheme URLs: http(s)://... or ftp://...
            """(?:https?://|ftp://)[^\s<>"{}|\\^`\[\]]+""" +
                // Bare domains with common TLDs, optional :port and /path.
                // `in`, `cc` and `app` are deliberately absent: as file extensions
                // and package suffixes (`Makefile.in`, `main.cc`, `sh.haven.app`)
                // they turn up in terminal output far more often than as domains. (#385)
                """|(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+""" +
                """(?:com|org|net|edu|gov|io|dev|co|uk|de|fr|jp|ru|br|au|us|info|biz|me|tv)""" +
                // The TLD has to end the token: no trailing letter/digit/hyphen (else
                // `nginx.conf` matches `nginx.co`) and no further dotted segment (else
                // `scipy.io.wavfile` matches `scipy.io`). A trailing `.` with nothing
                // word-like after it is fine — trimDetectedUrl drops it. (#385)
                """(?![a-zA-Z0-9-]|\.[a-zA-Z0-9])""" +
                """(?::\d{1,5})?""" +
                """(?:/[^\s<>"{}|\\^`\[\]]*)?""" +
                // IP:port (e.g. 192.168.1.1:8080) — require port to avoid matching version numbers
                """|(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}(?:/[^\s<>"{}|\\^`\[\]]*)?""",
        )

        /**
         * Create an empty line with default cells.
         */
        fun empty(row: Int, cols: Int, defaultFg: Color = Color.White, defaultBg: Color = Color.Black): TerminalLine = TerminalLine(
            row = row,
            cells = List(cols) {
                Cell(
                    char = '\u0000',
                    fgColor = defaultFg,
                    bgColor = defaultBg,
                )
            },
            softWrapped = false,
        )
    }
}

/**
 * True when this cell is a Kitty unicode placeholder for an inline-image slice
 * (#583): the base codepoint U+10EEEE stored as a surrogate pair across
 * [TerminalLine.Cell.char] (high) and `combiningChars[0]` (low). The payload
 * diacritics follow in `combiningChars[1..3]`.
 */
internal val TerminalLine.Cell.isImagePlaceholder: Boolean
    get() = char == '\uDBFB' && combiningChars.firstOrNull() == '\uDEEE'

/**
 * True when [codepoint] is an invisible Unicode format control (Category Cf) that
 * has no visual glyph in a character-cell terminal and does not participate in
 * glyph shaping (ZWNJ U+200C, ZWJ U+200D, and emoji tag characters U+E0020..U+E007F
 * are preserved so emoji ZWJ/flag sequences and cursive shaping work).
 *
 * Notably includes U+FFF9..U+FFFB (Interlinear Annotation Anchor/Separator/Terminator)
 * and U+1D173..U+1D17A (Musical Symbol Begin/End Beam/Tie/Slur/Phrase): libvterm
 * classifies them as zero-width combining codepoints attached to the preceding cell,
 * and Unicode omits them from Default_Ignorable_Code_Point, so HarfBuzz/Minikin would
 * otherwise draw a missing-glyph .notdef box one cell to the right (around the next
 * cell's character).
 */
internal fun isNonRenderingFormatCodepoint(codepoint: Int): Boolean = when (codepoint) {
    0x200B, // ZERO WIDTH SPACE
    0x200E,
    0x200F, // LRM, RLM
    0xFEFF, // ZERO WIDTH NO-BREAK SPACE (BOM)
    0xE0001, // LANGUAGE TAG
    -> true

    in 0x202A..0x202E, // Bidi embedding/override controls
    in 0x2060..0x206F, // Word joiner, invisible operators, bidi isolates, deprecated format controls
    in 0xFFF9..0xFFFB, // Interlinear annotation anchor/separator/terminator
    in 0x1D173..0x1D17A, // Musical symbol beam/tie/slur/phrase controls
    -> true

    else -> false
}

internal fun TerminalLine.Cell.renderableText(): String? {
    if (isImagePlaceholder) return null
    if (combiningChars.isEmpty()) {
        val c = char
        if (c == ' ' || c == '\u0000' || isNonRenderingFormatCodepoint(c.code)) {
            return null
        }
        return c.toString()
    }

    var needsFilter = char == '\u0000' || char.isHighSurrogate() || isNonRenderingFormatCodepoint(char.code)
    if (!needsFilter) {
        for (i in combiningChars.indices) {
            val ch = combiningChars[i]
            if (ch == '\u0000' || ch.isHighSurrogate() || isNonRenderingFormatCodepoint(ch.code)) {
                needsFilter = true
                break
            }
        }
    }
    if (!needsFilter) {
        return buildString(1 + combiningChars.size) {
            append(char)
            for (i in combiningChars.indices) append(combiningChars[i])
        }
    }

    val totalUnits = 1 + combiningChars.size
    val filtered = buildString(totalUnits) {
        var idx = 0
        while (idx < totalUnits) {
            val hi = if (idx == 0) char else combiningChars[idx - 1]
            if (hi.isHighSurrogate() && idx + 1 < totalUnits) {
                val lo = combiningChars[idx]
                if (lo.isLowSurrogate()) {
                    val cp = Character.toCodePoint(hi, lo)
                    if (!isNonRenderingFormatCodepoint(cp)) {
                        append(hi)
                        append(lo)
                    }
                    idx += 2
                    continue
                }
            }
            if (hi != '\u0000' && !isNonRenderingFormatCodepoint(hi.code)) {
                append(hi)
            }
            idx += 1
        }
    }
    if (filtered.isEmpty() || filtered == " ") {
        return null
    }
    return filtered
}

/**
 * Append [cell]'s plain-text representation for clipboard copy: inline-image
 * placeholders copy as a space, and non-Default_Ignorable annotation/scoping
 * anchors (U+FFF9..U+FFFB, U+1D173..U+1D17A) are omitted per Unicode §23.8 so
 * pasted text does not render .notdef boxes in target apps.
 */
internal fun StringBuilder.appendCopyableCell(cell: TerminalLine.Cell) {
    if (cell.isImagePlaceholder) {
        append(' ')
        return
    }
    val extra = cell.combiningChars
    if (extra.isEmpty()) {
        val c = cell.char
        if (c.code !in 0xFFF9..0xFFFB) {
            append(c)
        }
        return
    }
    val totalUnits = 1 + extra.size
    var idx = 0
    while (idx < totalUnits) {
        val hi = if (idx == 0) cell.char else extra[idx - 1]
        if (hi.isHighSurrogate() && idx + 1 < totalUnits) {
            val lo = extra[idx]
            if (lo.isLowSurrogate()) {
                val cp = Character.toCodePoint(hi, lo)
                if (cp !in 0x1D173..0x1D17A) {
                    append(hi)
                    append(lo)
                }
                idx += 2
                continue
            }
        }
        if (hi.code !in 0xFFF9..0xFFFB) {
            append(hi)
        }
        idx += 1
    }
}
