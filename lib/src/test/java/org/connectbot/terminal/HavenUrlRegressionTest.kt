/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [TerminalScreenState.getHyperlinkUrlAt], the multi-line URL
 * resolver used for tap-to-open hyperlinks on the terminal canvas.
 *
 * Regression coverage for the "whitespace gobbling" bug reported by
 * the Haven maintainer: the pre-fix implementation stripped every
 * whitespace run between URL-safe characters in the joined raw text,
 * without any notion of row boundaries. That meant a URL followed by
 * real English prose on the **same row** would have the intervening
 * spaces collapsed, and `URL_REGEX` would then greedily match all the
 * prose characters onto the URL. Example:
 *
 *     "see https://github.com/.../issues/78.  i think the is about..."
 *
 * became:
 *
 *     "https://github.com/.../issues/78.ithinktheisabout..."
 *
 * The fix keeps within-row whitespace intact and only joins rows that
 * are genuine URL continuations (previous row ends URL-safe, next row
 * starts URL-safe *at column 0* — no leading indentation allowed).
 */
class HavenUrlRegressionTest {

    private fun cells(text: String, cols: Int): List<TerminalLine.Cell> {
        // Pad the text to the full column width, as a real terminal does.
        val padded = text.padEnd(cols)
        return padded.map { ch ->
            TerminalLine.Cell(char = ch, fgColor = Color.White, bgColor = Color.Black)
        }
    }

    private fun lineOf(row: Int, text: String, cols: Int, softWrapped: Boolean = false): TerminalLine = TerminalLine(row = row, cells = cells(text, cols), softWrapped = softWrapped)

    private fun screenState(cols: Int, vararg lineTexts: String): TerminalScreenState {
        val lines = lineTexts.mapIndexed { i, t -> lineOf(i, t, cols) }
        val snapshot = TerminalSnapshot(
            lines = lines,
            scrollback = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = lines.size,
            cols = cols,
            timestamp = 0L,
            sequenceNumber = 0L,
        )
        return TerminalScreenState(snapshot)
    }

    // --- Single-line / no-wrap cases (baseline) ---

    @Test
    fun `single url mid-line returns exactly the url`() {
        val state = screenState(80, "go to https://example.com/path for info")
        val url = state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true) // inside "https://..."
        assertEquals("https://example.com/path", url)
    }

    @Test
    fun `tap outside any url returns null`() {
        val state = screenState(80, "go to https://example.com/path for info")
        assertNull(state.getHyperlinkUrlAt(row = 0, col = 0, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(row = 0, col = 35, autoDetectUrls = true))
    }

    // --- REGRESSION: bug from #78 review ---

    /**
     * URL followed by real prose on the SAME row. Previously the
     * whitespace between the period and the next word would be
     * collapsed (because both sides are URL-safe), and the regex
     * would match the prose onto the URL.
     */
    @Test
    fun `url followed by prose on same row does not gobble prose`() {
        val state = screenState(
            120,
            "https://github.com/GlassOnTin/Haven/issues/78.  i think the is about the Connections screen tab button",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true)
        // Post-0.1.0 rebase: trailing sentence punctuation is trimmed by
        // upstream's trimDetectedUrl. The point of this spec — the prose
        // after the URL must not be gobbled — still holds.
        assertEquals(
            "https://github.com/GlassOnTin/Haven/issues/78",
            url,
        )
    }

    /**
     * The same bug, but with no trailing period — a bare URL followed
     * by a space and an alphabetic word. Pre-fix the regex match would
     * have been "https://example.com/pathsomemoretext".
     */
    @Test
    fun `bare url followed by single space and word does not gobble word`() {
        val state = screenState(80, "see https://example.com/path somemoretext here")
        val url = state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true)
        assertEquals("https://example.com/path", url)
    }

    // --- Multi-line URL continuation (the feature that must still work) ---

    /**
     * URL wraps exactly at the terminal column width. The continuation
     * line starts at column 0 with URL-safe characters (no leading
     * whitespace). This is the case the multi-line resolver exists for.
     */
    @Test
    fun `url wrapped at column boundary is joined across rows`() {
        // 40-col terminal. URL starts at col 0 of row 0, wraps at col 40.
        // Row 0 exactly 40 chars, row 1 starts with the tail of the URL.
        val row0 = "https://example.com/very/long/path/xxxx" // 40 chars, ends at col 40
        val row1 = "yyyy/zzzz"
        val state = screenState(40, row0, row1)

        val url = state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true)
        assertEquals("https://example.com/very/long/path/xxxxyyyy/zzzz", url)

        // Tapping the continuation line should also resolve to the same URL.
        val urlFromCont = state.getHyperlinkUrlAt(row = 1, col = 2, autoDetectUrls = true)
        assertEquals("https://example.com/very/long/path/xxxxyyyy/zzzz", urlFromCont)
    }

    /**
     * A URL that ends on row 0 followed by a new line of prose starting
     * with leading indentation (like a `  i think...` bullet) must NOT
     * be treated as a continuation, even though the row 0 ends URL-safe
     * and the first non-space on row 1 is URL-safe. The disambiguator is
     * that a real wrapped URL continuation would have its next character
     * at column 0, not after leading whitespace.
     */
    @Test
    fun `indented prose on next row is not a url continuation`() {
        val state = screenState(
            60,
            "see https://example.com/path",
            "  i think the is about stuff",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true)
        assertEquals("https://example.com/path", url)
        // Tapping the second row's prose must not claim it's a URL.
        assertNull(state.getHyperlinkUrlAt(row = 1, col = 6, autoDetectUrls = true))
    }

    /**
     * URL wraps across more than one row. All continuation rows must
     * start at column 0 with URL-safe characters.
     */
    @Test
    fun `url wrapped across three rows is joined`() {
        val cols = 20
        val row0 = "https://example.com/" // 20 chars exactly
        val row1 = "a/b/c/d/e/f/g/h/i/j/" // 20 chars exactly
        val row2 = "k/l/m/" // continuation, not padded yet
        val state = screenState(cols, row0, row1, row2)

        val url = state.getHyperlinkUrlAt(row = 1, col = 5, autoDetectUrls = true)
        assertEquals("https://example.com/a/b/c/d/e/f/g/h/i/j/k/l/m/", url)
    }

    /**
     * Continuation chain broken by a blank line: the URL on row 0 must
     * not be joined to content after the blank line.
     */
    @Test
    fun `blank row breaks continuation chain`() {
        val state = screenState(
            40,
            "https://example.com/something/that/is/lo",
            "",
            "ng/should/not/be/joined",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true)
        // Only row 0's content — regex gives whatever it can match there.
        assertEquals("https://example.com/something/that/is/lo", url)
    }

    // --- REGRESSION: bug reported when Claude Code output wrapped a URL
    //     at the Haven terminal width.  The URL started mid-row after a
    //     prose prefix and wrapped to the next row with NO leading
    //     whitespace on the continuation row — a plain forced wrap.  The
    //     old single-line fast path returned the row 0 partial because
    //     the URL match did not touch either "edge" of the trimmed line
    //     (there were a few unused trailing cells where the wrap
    //     happened), which was interpreted as "URL is truly interior,
    //     no need to walk neighbours".  In reality, "followed only by
    //     whitespace padding" is not the same as "followed by prose" —
    //     the row *might* be part of a wrap.  Skip the fast path in that
    //     ambiguous case so the continuation walker gets a chance to
    //     look at the next row.

    @Test
    fun `url wrapped after trailing unused cells joins to next row`() {
        // 80-col terminal, matches a realistic Haven-on-phone width.
        // Prefix "…Issue #92 reply posted — " pushes the URL to col 27.
        // The URL is 72 chars.  27+72 = 99, so the URL cannot fit on
        // row 0 — it has to wrap.  The CLI tool that produced the
        // output hard-wrapped at the 80-col boundary leaving a few
        // trailing blanks on row 0 before the continuation.
        val prefix = "- Issue #92 reply posted — "
        val url = "https://github.com/GlassOnTin/Haven/issues/92#issuecomment-4253770624"
        // cols=80 → row 0 fits prefix + first 53 chars of URL
        //         → row 1 starts at col 0 with the remaining 19 chars
        val row0 = prefix + url.substring(0, 80 - prefix.length) // 80 chars exactly
        val row1 = url.substring(80 - prefix.length) // tail of URL
        val state = screenState(80, row0, row1)

        // Tap anywhere inside the URL on row 0.
        val clickCol = prefix.length + 10 // inside "github.com/Glass…"
        assertEquals(url, state.getHyperlinkUrlAt(row = 0, col = clickCol, autoDetectUrls = true))
        // Tapping the continuation row resolves to the full URL too.
        assertEquals(url, state.getHyperlinkUrlAt(row = 1, col = 4, autoDetectUrls = true))
    }

    @Test
    fun `url interrupted by trailing blanks only, not by prose, is not truncated`() {
        // Degenerate: 80-col terminal, URL ends before row edge, nothing
        // after it on the row, and no continuation on the next row.
        // The walker should return the complete URL on row 0, not a
        // truncated prefix.  This used to go through the fast path and
        // return the URL correctly — the risk of the fix is we break
        // this case, so cover it explicitly.
        val state = screenState(
            80,
            "prefix https://example.com/path", // ends at col 31, blanks after
            "next line of unrelated prose",
        )
        assertEquals(
            "https://example.com/path",
            state.getHyperlinkUrlAt(row = 0, col = 12, autoDetectUrls = true),
        )
    }

    // ------------------------------------------------------------------
    // TDD spec: anchor-based continuation across formatting prefixes.
    //
    // These tests describe the behaviour requested by the Haven maintainer
    // for URLs that appear in tool output (Claude Code transcripts,
    // markdown tables, piped indented logs). The current implementation
    // does NOT pass all of these; they are the target spec.
    //
    // Core rules:
    //   1. A `https://` / `http://` / `ftp://` anchor on any row starts
    //      URL collection.
    //   2. On a continuation row, strip any leading run of whitespace OR
    //      formatting characters (box-drawing, markdown-table pipes, list
    //      markers, etc.) before deciding continuation.
    //   3. After stripping, if the row begins with a URL-safe char and
    //      the URL-safe run contains at least one `/`, it counts as a
    //      continuation of the previous row's URL.
    //   4. A mid-row whitespace run INSIDE what would otherwise be a URL
    //      ends the URL at the whitespace (tool padding, not real URL).
    //   5. Three consecutive non-continuation rows after an anchor
    //      confirms the URL has ended and we should stop walking.
    //
    // Naming convention: prefix every case with `spec_` so it's obvious
    // which belong to the new spec vs the legacy cases above.
    // ------------------------------------------------------------------

    // ---- Rule 1 + 2: leading whitespace on the continuation row ----

    @Test
    fun `spec_continuation_leading_whitespace_with_slash`() {
        // bash command wrapped by Claude Code's renderer. Row 1 starts with
        // spaces (Claude Code's alignment padding) then /path.
        val state = screenState(
            80,
            "curl -s \"https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata",
            "      /merge_requests/36819\" 2>&1 | python3 -c ...",
        )
        val url = state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true) // inside URL
        assertEquals(
            "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/36819",
            url,
        )
    }

    @Test
    fun `spec_continuation_from_tap_on_continuation_row`() {
        // Same URL as above but user taps on row 1 (the continuation).
        val state = screenState(
            80,
            "curl -s \"https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata",
            "      /merge_requests/36819\" 2>&1 | python3 -c ...",
        )
        val url = state.getHyperlinkUrlAt(row = 1, col = 10, autoDetectUrls = true) // inside "/merge_req..."
        assertEquals(
            "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/36819",
            url,
        )
    }

    // ---- Rule 2: leading box-drawing / formatting chars ----

    @Test
    fun `spec_continuation_leading_box_drawing_char`() {
        // Claude Code's "⎿  " prefix on a continuation line (U+23BF).
        // The prefix should be stripped before the continuation check.
        val state = screenState(
            80,
            "● Bash(curl https://example.com/api/v1/projects/very-long-name-here",
            "  ⎿  /subpath/result.json)",
        )
        assertEquals(
            "https://example.com/api/v1/projects/very-long-name-here/subpath/result.json",
            state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_continuation_leading_pipe_and_tee`() {
        // Markdown / table-drawing prefixes. `│` and `├` at row start.
        val state = screenState(
            80,
            "│ Endpoint: https://api.example.com/orgs/haven/projects/termlib",
            "│           /issues/78",
        )
        assertEquals(
            "https://api.example.com/orgs/haven/projects/termlib/issues/78",
            state.getHyperlinkUrlAt(row = 0, col = 25, autoDetectUrls = true),
        )
    }

    // ---- Rule 3: markdown table column ----

    @Test
    fun `spec_continuation_markdown_table_column`() {
        // | col1 | https://very-long.example.com/a | col3 |
        // | cont | /path/more-stuff                | end  |
        // Tap inside the URL on row 0 should follow into the /path/more-stuff
        // cell on row 1 — same column, continuation with `/`.
        val state = screenState(
            80,
            "| col1 | https://very-long.example.com/a       | col3 |",
            "| cont | /path/more-stuff                      | end  |",
        )
        assertEquals(
            "https://very-long.example.com/a/path/more-stuff",
            state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true),
        )
    }

    // ---- Rule 4: mid-row whitespace ends the URL ----

    @Test
    fun `spec_mid_row_whitespace_ends_url`() {
        // Tool concatenates two tokens with spaces between them on the
        // SAME row. The URL must end at the first space, not get joined
        // with the trailing text on the same row.
        val state = screenState(
            80,
            "see https://example.com/path   unrelated prose here",
        )
        assertEquals(
            "https://example.com/path",
            state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true),
        )
    }

    // ---- Rule 5: three non-continuation rows terminate the walk ----

    @Test
    fun `spec_terminate_after_three_prose_rows`() {
        // Anchor on row 0; row 1-3 are prose rows that each strip down to
        // non-URL content (no `/`, no URL-safe prefix after stripping).
        // Walker must stop at row 0; tap on row 0 returns just the URL.
        val state = screenState(
            80,
            "prefix https://example.com/path",
            "This is a normal sentence of prose.",
            "Another ordinary line follows.",
            "Third prose line, no URL continuation.",
        )
        assertEquals(
            "https://example.com/path",
            state.getHyperlinkUrlAt(row = 0, col = 12, autoDetectUrls = true),
        )
    }

    // ---- Rule 3: URL-structural chars on continuation row ----

    @Test
    fun `spec_fragment_anchor_on_continuation_row`() {
        // Real-world case from a GitHub issue-comment URL where the
        // continuation row carries the #fragment (no / of its own). The
        // continuation rule accepts /, #, ?, &, or = as URL-structural
        // evidence.
        val state = screenState(
            80,
            "  ⎿ https://github.com/GlassHaven/Haven/issues/",
            "    106#issuecomment-4298664962",
        )
        assertEquals(
            "https://github.com/GlassHaven/Haven/issues/106#issuecomment-4298664962",
            state.getHyperlinkUrlAt(row = 0, col = 15, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_query_string_on_continuation_row`() {
        // Same relaxation covers `?foo=bar&baz=qux` wraps — the query-string
        // structure is strong evidence the row continues a URL.
        val state = screenState(
            80,
            "See https://search.example.com/results",
            "        ?q=terminal&limit=50",
        )
        assertEquals(
            "https://search.example.com/results?q=terminal&limit=50",
            state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_lone_slash_path_is_continuation`() {
        // A row that, after stripping formatting, starts with `/` and contains
        // only URL-safe chars is almost certainly a URL continuation even if
        // the previous row didn't perfectly fill the column width.
        val state = screenState(
            80,
            "Visit https://example.com/very-long",
            "        /subpath",
        )
        assertEquals(
            "https://example.com/very-long/subpath",
            state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true),
        )
    }

    // ---- Guard against greedy absorption ----

    @Test
    fun `spec_continuation_row_with_space_in_middle_is_rejected`() {
        // Row 1 starts with /path but has a space mid-content — that's
        // prose, not a URL continuation.
        val state = screenState(
            80,
            "Visit https://example.com/a",
            "        /subpath of prose",
        )
        // Should include /subpath (first URL-safe run after prefix strip)
        // but stop at the space before "of". Open question: should we
        // join /subpath at all, given the trailing prose on the same row?
        // Proposed: yes, join up to the space.
        assertEquals(
            "https://example.com/a/subpath",
            state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_hanging_indent_single_char_tail_joins`() {
        // The reported iSpindle case: a git URL whose final "t" of ".git"
        // got wrapped onto the next line behind a hanging indent. The tail
        // has no /#?&= of its own, but it sits alone on an otherwise-blank
        // line — the shape of a wrap, not prose.
        val state = screenState(
            61,
            "  To https://github.com/GlassOnTin/iSpindlePlotter.gi",
            "     t",
        )
        assertEquals(
            "https://github.com/GlassOnTin/iSpindlePlotter.git",
            state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_hanging_indent_multi_char_slashless_tail_joins`() {
        // Not just single characters: a short slash-less run ("ana") alone on
        // a mostly-blank line is still a wrap tail.
        val state = screenState(
            61,
            "  See https://github.com/GlassOnTin/Haven/blob/main/READMEban",
            "     ana",
        )
        assertEquals(
            "https://github.com/GlassOnTin/Haven/blob/main/READMEbanana",
            state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_slashless_word_amid_prose_is_not_a_tail`() {
        // A slash-less run followed by more words on the same line is prose,
        // not a wrap tail — must stay rejected (guards the relaxation).
        val state = screenState(
            61,
            "  See https://github.com/GlassOnTin/Haven/tree/main/READMEx",
            "     ample text here",
        )
        assertEquals(
            "https://github.com/GlassOnTin/Haven/tree/main/READMEx",
            state.getHyperlinkUrlAt(row = 0, col = 20, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_tap_before_anchor_returns_null`() {
        // Tap on a row that does not contain a URL anchor and is not a
        // continuation of one. No URL should be returned.
        val state = screenState(
            80,
            "Just some prose.",
            "More prose here.",
        )
        assertNull(state.getHyperlinkUrlAt(row = 0, col = 5, autoDetectUrls = true))
    }

    // ---- Legacy continuation cases still pass under the new spec ----

    @Test
    fun `spec_legacy_column_boundary_wrap_still_works`() {
        // Regression: same case as the legacy `url wrapped at column
        // boundary is joined across rows` test, pinned here as a guard
        // against the new spec accidentally regressing the simple case.
        // 40-col terminal, URL exactly fills row 0, tail on row 1 at col 0.
        val row0 = "https://example.com/very/long/path/xxxx" // 40 chars
        val row1 = "yyyy/zzzz"
        val state = screenState(40, row0, row1)
        assertEquals(
            "https://example.com/very/long/path/xxxxyyyy/zzzz",
            state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true),
        )
    }

    /**
     * Device-caught over-join (2026-07-11, post-0.1.0 merge verification):
     * a screen stacking several URL blocks separated by `== header ==`
     * rows glued EVERYTHING into one mega-URL. `==` passed the blob's
     * `/#?&=` continuation signal (a pure-punctuation run is decoration,
     * not a query fragment) and a row with its own `https://` scheme was
     * accepted as a continuation of the URL above it. Each tapped URL must
     * resolve to exactly its own block.
     */
    @Test
    fun `spec_adjacent_url_blocks_do_not_glue`() {
        val state = screenState(
            60,
            "== A2 ==",
            "https://example.com/very/long/path/segments/abcdefghijklmnop", // 60 chars, fills row
            "tail/xyz",
            "== B ==",
            "  ⎿  https://github.com/GlassOnTin/iSpindlePlotter.gi",
            "     t",
            "== D ==",
            "  \"https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata",
            "      /merge_requests/36819\" 2>&1",
            "== end ==",
            "root@proot:~# ",
        )
        assertEquals(
            "https://example.com/very/long/path/segments/abcdefghijklmnoptail/xyz",
            state.getHyperlinkUrlAt(row = 1, col = 10, autoDetectUrls = true),
        )
        assertEquals(
            "https://github.com/GlassOnTin/iSpindlePlotter.git",
            state.getHyperlinkUrlAt(row = 4, col = 20, autoDetectUrls = true),
        )
        assertEquals(
            "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/36819",
            state.getHyperlinkUrlAt(row = 7, col = 20, autoDetectUrls = true),
        )
    }

    @Test
    fun `spec_full_width_url_row_does_not_glue_to_url_below`() {
        // Margin-wrap acceptance must not bridge into a row that carries
        // its own scheme — two independent URLs printed on adjacent rows.
        val state = screenState(
            43,
            "https://example.com/very/long/path/xxxxyyyy", // 43 chars, fills row
            "https://other.example.org/second",
        )
        assertEquals(
            "https://example.com/very/long/path/xxxxyyyy",
            state.getHyperlinkUrlAt(row = 0, col = 10, autoDetectUrls = true),
        )
        assertEquals(
            "https://other.example.org/second",
            state.getHyperlinkUrlAt(row = 1, col = 10, autoDetectUrls = true),
        )
    }

    /**
     * Haven #791: when the terminal prints a URL followed by an explicit
     * newline and a new unindented line starting with a slash path at column 0
     * (e.g. `https://example.com\n/1`), clicking the URL must open
     * `https://example.com` rather than `https://example.com/1`, and clicking
     * `/1` on the next row must not resolve as a link.
     */
    @Test
    fun `spec_unindented_slash_line_after_short_url_row_is_not_continuation`() {
        val state = screenState(
            80,
            "https://example.com",
            "/1",
        )
        assertEquals(
            "https://example.com",
            state.getHyperlinkUrlAt(row = 0, col = 5, autoDetectUrls = true),
        )
        assertNull(state.getHyperlinkUrlAt(row = 1, col = 0, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(row = 1, col = 1, autoDetectUrls = true))

        val trailingSlashState = screenState(
            80,
            "https://example.com/",
            "/1",
        )
        assertEquals(
            "https://example.com/",
            trailingSlashState.getHyperlinkUrlAt(row = 0, col = 5, autoDetectUrls = true),
        )
        assertNull(trailingSlashState.getHyperlinkUrlAt(row = 1, col = 0, autoDetectUrls = true))

        val plainWordState = screenState(
            80,
            "https://example.com",
            "foo",
        )
        assertEquals(
            "https://example.com",
            plainWordState.getHyperlinkUrlAt(row = 0, col = 5, autoDetectUrls = true),
        )
        assertNull(plainWordState.getHyperlinkUrlAt(row = 1, col = 0, autoDetectUrls = true))
    }
}
