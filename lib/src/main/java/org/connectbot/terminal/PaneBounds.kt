package org.connectbot.terminal

/**
 * The interior of a pane drawn by a multiplexer (tmux, zellij, herdr) on the
 * terminal screen, in viewport cells, all bounds inclusive. A selection that
 * carries one stays inside it: rows run from [left] to [right] instead of the
 * full terminal width, so copying from one pane no longer picks up the text of
 * the pane or sidebar beside it.
 */
data class PaneRect(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
)

/**
 * Finds the pane around viewport cell ([row], [col]) from the borders a
 * multiplexer draws, or null when there is none to find.
 *
 * The screen has no notion of panes, so the borders are the only evidence:
 * the nearest column of vertical box-drawing characters on each side of the
 * press — or of the block-element strokes herdr draws its pane scrollbar
 * with (▕ track, ▐ thumb), which bound the pane's text the same way. A column only counts as a border when it runs unbroken through the
 * pressed row for at least a third of the screen ([MIN_BORDER_FRACTION]) —
 * pane borders span the pane's height, while a table a program draws inside a
 * pane is usually short. Without a vertical border on either side there is
 * nothing to bound, and null keeps the selection full-width as before.
 *
 * Rows are bounded by horizontal borders (a run of horizontal box-drawing
 * across the whole pane) only where that run joins a side border through a
 * junction such as ├ or ┤ — a horizontal split. A free-standing rule inside
 * the pane (Claude Code's input box, a `---` heading) meets the side border as
 * a plain │ and does not bound the selection.
 *
 * [charAt] returns the character in a viewport cell, or null outside the
 * screen.
 */
internal fun detectPane(
    rows: Int,
    cols: Int,
    row: Int,
    col: Int,
    charAt: (row: Int, col: Int) -> Char?,
): PaneRect? {
    if (rows <= 0 || cols <= 0 || row !in 0 until rows || col !in 0 until cols) return null
    if (isPaneEdge(charAt(row, col))) return null

    val minRun = maxOf(MIN_BORDER_RUN, rows / MIN_BORDER_FRACTION)
    fun isBorderColumn(c: Int): Boolean {
        if (!isPaneEdge(charAt(row, c))) return false
        var run = 1
        var r = row - 1
        while (r >= 0 && isPaneEdge(charAt(r, c))) {
            run++
            r--
        }
        r = row + 1
        while (r < rows && isPaneEdge(charAt(r, c))) {
            run++
            r++
        }
        return run >= minRun
    }

    val leftBorder = (col - 1 downTo 0).firstOrNull(::isBorderColumn)
    val rightBorder = (col + 1 until cols).firstOrNull(::isBorderColumn)
    if (leftBorder == null && rightBorder == null) return null
    val left = leftBorder?.plus(1) ?: 0
    val right = rightBorder?.minus(1) ?: (cols - 1)
    if (left > right) return null

    fun isSplitRow(r: Int): Boolean {
        for (c in left..right) {
            if (!isBoxHorizontal(charAt(r, c))) return false
        }
        val joins = listOfNotNull(leftBorder, rightBorder).any { side ->
            val ch = charAt(r, side)
            ch != null && isBoxDrawing(ch) && !isPlainVertical(ch)
        }
        return joins
    }

    val top = (row - 1 downTo 0).firstOrNull(::isSplitRow)?.plus(1) ?: 0
    val bottom = (row + 1 until rows).firstOrNull(::isSplitRow)?.minus(1) ?: (rows - 1)
    return PaneRect(top = top, bottom = bottom, left = left, right = right)
}

/** Shortest border run that counts, however small the screen. */
private const val MIN_BORDER_RUN = 3

/** A border must span at least 1/this of the screen's rows. */
private const val MIN_BORDER_FRACTION = 3

private fun isBoxDrawing(ch: Char): Boolean = ch.code in 0x2500..0x257F

/** Pure horizontal strokes: no arm going up or down. */
private const val HORIZONTALS = "─━═╌╍┄┅┈┉╴╶╸╺╼╾"

/** Pure vertical strokes: no arm going left or right. */
private const val VERTICALS = "│┃║╎╏┆┇┊┋╵╷╹╻╽╿"

private fun isPlainVertical(ch: Char): Boolean = ch in VERTICALS

/** A box-drawing cell a vertical border can pass through: anything with an up or down arm. */
private fun isBoxVertical(ch: Char?): Boolean = ch != null && isBoxDrawing(ch) && ch !in HORIZONTALS

/** Vertical bars from Block Elements: herdr's pane scrollbar (▕ track, ▐ thumb) and their mirrors. */
private const val SCROLLBAR_STROKES = "▏▕▐▌"

private fun isPaneEdge(ch: Char?): Boolean = isBoxVertical(ch) || (ch != null && ch in SCROLLBAR_STROKES)

/** A box-drawing cell a horizontal border can pass through: anything but a plain vertical. */
private fun isBoxHorizontal(ch: Char?): Boolean = ch != null && isBoxDrawing(ch) && !isPlainVertical(ch)
