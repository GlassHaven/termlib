package org.connectbot.terminal

/**
 * Mode controlling how East Asian Ambiguous characters (such as geometric symbols,
 * arrows, Greek/Cyrillic letters) are measured and rendered.
 */
enum class AmbiwidthMode {
    /**
     * Fixed 1-cell narrow width for all ambiguous characters (standard Unix/XTerm ambiwidth=0).
     */
    NARROW,

    /**
     * Dynamically profiles active font glyph advance widths at runtime.
     * Measured wide characters (>= 1.35x Latin 'M' width) render as 2 cells.
     * Unprofiled / fallback ambiguous characters render as 1 cell.
     */
    AUTO_NARROW,

    /**
     * Fixed 2-cell wide width for all ambiguous characters (CJK ambiwidth=1).
     */
    WIDE,

    /**
     * Dynamically profiles active font glyph advance widths at runtime.
     * Measured wide characters (>= 1.35x Latin 'M' width) render as 2 cells.
     * Unprofiled / fallback ambiguous characters (e.g. Private Use Area) render as 2 cells.
     */
    AUTO_WIDE;

    val isAuto: Boolean get() = this == AUTO_NARROW || this == AUTO_WIDE
    val fallbackWide: Boolean get() = this == WIDE || this == AUTO_WIDE
}
