/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
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

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #676: tmux scroll redraws transiently leave wrong characters mid-word.
 *
 * The renderer's line cache (currentLines) is refreshed from the native buffer
 * only for rows the damage callback reports. libvterm merges consecutive
 * scroll-region scrolls into pending_scrollrect (DAMAGE_SCROLL mode), and the
 * tracked damage rect is adjusted to stay valid across the deferred user-side
 * scroll. If that adjustment drops a row whose content actually moved, the
 * renderer keeps a stale cached copy until a wider repaint re-pulls it.
 *
 * The differential: the same byte stream fed one emulator in a single
 * writeInput (damage merges across the burst) and another one byte at a time
 * (every scroll flushed before the next op, so no merge). The parse is
 * deterministic, so both native buffers end identical. If the merged path
 * tracks damage correctly, both mirrors end identical too. A divergence is
 * dropped damage — the #676 defect — and the differing rows name the sequence.
 */
@RunWith(AndroidJUnit4::class)
class ScrollDamageMergeTest {

    private fun settle(impl: TerminalEmulatorImpl): TerminalSnapshot {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        impl.processPendingUpdates()
        return impl.snapshot.value
    }

    /** The real #676 capture, decoded once, shared by the replay tests. */
    private fun captureBytes(): ByteArray = Base64.getDecoder().decode(
            "SBtbMTsyM3IbWzM1OzVIG1s/MjVsG1s/MTAwNmwbWz8xMDAwbBtbPzEwMDJsG1s/MTAwM2wbWz8xMDA2aBtbPzEwMDBoG1s/MTAw" +
            "MmgbWz8xMDAzaBtbMTh0G1sxNHQbKEIbW20bWz8xMmwbWz8yNWgbWz8xMDA2bBtbPzEwMDBsG1s/MTAwMmwbWz8xMDAzbBtbMTsx" +
            "SBtbMTsyM3IbWzIyOzVIG1s/MjVsG1s/MTAwNmwbWz8xMDAwbBtbPzEwMDJsG1s/MTAwM2wbWz8xMDA2aBtbPzEwMDBoG1s/MTAw" +
            "MmgbWz8xMDAzaBtbMTsySBtbMUsbW0NzdGlsbBtbMVgbW0Njb3JydXB0G1sxWBtbQ2V4YWN0bHkbWzFYG1tDdGhlG1sxWBtbQ1Ay" +
            "G1sxWBtbQ3N0aWZmbmVzcxtbMVgbW0PigJQbWzFYG1tDbWF0Y2hpbmcbWzI7MkgbWzFLG1tDZXZlcnkbWzFYG1tDc3ltcHRvbS4b" +
            "WzFYG1tDVGhlG1sxWBtbQ1AxG1sxWBtbQ2V4cGVyaW1lbnQbWzFYG1tDaGFzG1sxWBtbQ25vG1tLG1szOzJIG1sxSxtbQ2FuYWxv" +
            "Z291cxtbMVgbW0NnYXAuG1sxWBtbQ0NoZWNraW5nG1sxWBtbQ3doYXQbWzFYG1tDdGhhdBtbMVgbW0Nhcmd1bWVudBtbMVgbW0Nt" +
            "ZWFuczobWzQ7MUgbW0sbWzU7MkgbWzFLG1szODs1OzI0Nm0bW0NUaG91Z2h0IGZvciAbWzFtMW0gMjBzGyhCG1ttG1szODs1OzI0" +
            "Nm0sIHNlYXJjaGVkIGZvciAbWzFtMxsoQhtbbRtbMzg7NTsyNDZtIHBhdHRlcm5zLCByZWFkG1szOW0bWzY7MkgbWzFLG1szODs1" +
            "OzI0Nm0bWzFtG1tDMhsoQhtbbRtbMzg7NTsyNDZtIGZpbGVzIBtbMzltG1tLDQobW0sbWzM4OzU7MTE0bQ0K4pePG1szOW0bWzFY" +
            "G1tDQmFja2dyb3VuZBtbMVgbW0Njb21tYW5kG1sxWBtbQyJSdW4bWzFYG1tDZml4ZWQtdGhpY2tuZXNzG1sxWBtbQ2gbWzFYG1tD" +
            "c3dlZXAbW0sNCihQMikbWzFYG1tDYW5kG1sxWBtbQ1AxG1sxWBtbQ3Nhbml0eRtbMVgbW0NhdBtbMVgbW0NoPTAuMSIbWzFYG1tD" +
            "Y29tcGxldGVkG1sxWBtbQyhleGl0G1sxWBtbQ2NvZGUbW0sNCjApG1tLDQobW0sbWzM4OzU7MjMxbQ0K4pePG1szOW0bWzFYG1tD" +
            "VGhlG1sxWBtbQ3J1bGUbWzFYG1tDY2hlY2tzG1sxWBtbQ291dDobWzFYG1tDdGhlG1sxWBtbQ2ZvdXIbWzFYG1tDcG9pbnRzG1tL" +
            "G1sxMzsySBtbMUsbW0MoYSxhLGEpLChiLGEsYSksKGEsYixhKSwoYSxhLGIpG1sxWBtbQ3dpdGgbWzFYG1tDYT0oNeKIkuKImjUp" +
            "LzIwLBtbMTQ7MkgbWzFLG1tDYj0oNSsz4oiaNSkvMjAsG1sxWBtbQ3c9MS8yNBtbMVgbW0NhcmUbWzFYG1tDdGhlG1sxWBtbQ3N0" +
            "YW5kYXJkG1sxWBtbQ2RlZ3JlZS0yG1tLG1sxNTsySBtbMUsbW0N0ZXQbWzFYG1tDcnVsZRtbMVgbW0PigJQbWzFYG1tDSRtbMVgb" +
            "W0N2ZXJpZmllZBtbMVgbW0PiiKt44oKBeOKCghtbMVgbW0NieRtbMVgbW0NoYW5kOhtbMVgbW0MyYcKyKzJhYhtbMVgbW0M9G1tL" +
            "G1sxNjsySBtbMUsbW0MwLjIsG1sxWBtbQy8yNBtbMVgbW0M9G1sxWBtbQzEvMTIwG1sxWBtbQz0bWzFYG1tDZXhhY3QbWzFYG1tD" +
            "dmFsdWUuG1sxWBtbQ1F1YWRyYXR1cmUbWzFYG1tDaXMbW0sbWzE3OzJIG1sxSxtbQ2V4b25lcmF0ZWQ7G1sxWBtbQ3RoZRtbMVgb" +
            "W0NkZWdyZWUtMhtbMVgbW0NpbnRlZ3JhbmQbWzFYG1tDKGFmZmluZRtbMVgbW0Njb3JuZXIbWzE4OzJIG1sxSxtbQ21hcCwbWzFY" +
            "G1tDQhtbMVgbW0NsaW5lYXIpG1sxWBtbQ2lzG1sxWBtbQ2ludGVncmF0ZWQbWzFYG1tDZXhhY3RseS4bWzFYG1tDU28bWzFYG1tD" +
            "dGhlG1sxWBtbQ1AyG1tLG1sxOTsySBtbMUsbW0NtYWNoaW5lcnkbWzM4OzU7MjMxbRtbNDg7NTsyMzdtIEp1bXAgdG8gYm90dG9t" +
            "IChjdHJsK0VuZCkg4oaTIBtbMzltG1s0OW0bW0sNChtbSxtbMzg7NTsyNDRtDQrilIDilIDilIDilIDilIDilIDilIDilIDilIDi" +
            "lIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDi" +
            "lIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIAbKEIbW20bWzM4OzU7MjQ2bRtbMjI7MUji" +
            "na/CoBtbMzltG1sxWBtbQ2wbWzdtIBsoQhtbbRtbSxtbMzBtG1s0Mm0NCltnZW1pbmldIDxsPiLinLMgTG9jYWwgcHJvamVjdCBn" +
            "b2FsICIgMTQ6MTIgMDMtT2N0LTI2GyhCG1ttG1syMjs1SBtbMTsyMnIbWzIyUxtbMTsxSBtbMzg7NTsyMzltG1s0ODs1OzIzN23i" +
            "na8gd2UndmUgYmVlbiBwcm9ncmVzc2luZyB0aGUgbG9jYWwgcHJvamVjdCBnb2FsIHXigKYbWzM5bSAbWzM7MUgbWzQ5bRtbMzg7" +
            "NTsyMzFt4pePG1tDG1szOW1UaGUbW0Nhc3NlbWJseRtbQ3F1YWRyYXR1cmUbW0NpcxtbNDszSBtbMzg7NTsxNTNtdGV0cmFoZWRy" +
            "b25fcXVhZHJhdHVyZSgyKRtbQxtbMzltd2l0aBtbQ3RoZRtbQ21hcBtbQ2Fsd2F5cxtbNTszSGJ1aWx0G1tDZnJvbRtbQ2Nvcm5l" +
            "cnMbW0Nvbmx5G1s2OzNIKBtbMzg7NTsxNTNtZnJvbV90ZXRyYWhlZHJvbl92ZXJ0aWNlcxtbMzltKRtbQ+KAlBtbQ2FmZmluZRtb" +
            "Q21hcCwbW0NtaWQbWzc7M0hub2RlcxtbQ25ldmVyG1tDZW50ZXIbW0N0aGUbW0NnZW9tZXRyeS4bW0NXaXRoG1tDc3RyYWlnaHQb" +
            "Wzg7M0hlZGdlcxtbQ3RoZRtbQ3N0aWZmbmVzcxtbQ2ludGVncmFuZBtbQ2lzG1tDZXhhY3RseRtbQ2RlZ3JlZRtbOTszSDIsG1tD" +
            "c28bW0N0aGUbW0NydWxlG1tDaXMbW0NleGFjdBtbQxtbM21pZhtbQxsoQhtbbXRoZRtbQ2FyZ3VtZW50G1tDbWVhbnMbWzEwOzNI" +
            "ZGVncmVlLhtbQ0J1dBtbQ3RoZRtbQ3BhdGNoG1tDdGVzdHMbW0Nvbmx5G1tDZXhlcmNpc2UbWzExOzNIZGVncmVlLTEbW0NpbnRl" +
            "Z3JhbmRzG1tDKOKIh8+GX2HhtYDCt8+D4oKAG1tDaXMbW0NsaW5lYXIpLBtbQ3NvG1tDYRtbMTI7M0hydWxlG1tDdGhhdCdzG1tD" +
            "ZXhhY3QbW0Nmb3IbW0NsaW5lYXJzG1tDYnV0G1tDd3JvbmcbW0Nmb3IbWzEzOzNIcXVhZHJhdGljcxtbQ3dvdWxkG1tDcGFzcxtb" +
            "Q2V2ZXJ5G1tDY2hlY2sbW0NzbxtbQ2ZhchtbQ2FuZBtbMTQ7M0hzdGlsbBtbQ2NvcnJ1cHQbW0NleGFjdGx5G1tDdGhlG1tDUDIb" +
            "W0NzdGlmZm5lc3MbW0PigJQbW0NtYXRjaGluZxtbMTU7M0hldmVyeRtbQ3N5bXB0b20uG1tDVGhlG1tDUDEbW0NleHBlcmltZW50" +
            "G1tDaGFzG1tDbm8bWzE2OzNIYW5hbG9nb3VzG1tDZ2FwLhtbQ0NoZWNraW5nG1tDd2hhdBtbQ3RoYXQbW0Nhcmd1bWVudBtbQ21l" +
            "YW5zOhtbMTc7MTJIG1szODs1OzIzMW0bWzQ4OzU7MjM3bSBKdW1wIHRvIGJvdHRvbSAoY3RybCtFbmQpIOKGkyAbWzE5OzFIG1s0" +
            "OW0bKEIbW20bWzM4OzU7MjQ0beKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKU" +
            "gOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKU" +
            "gOKUgOKUgOKUgOKUgOKUgOKUgOKUgBtbMjA7MUgbKEIbW20bWzM4OzU7MjQ2beKdr8KgG1tDG1szOW1sG1s3bSANChsoQhtbbRtb" +
            "Mzg7NTsyNDRt4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA" +
            "4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA" +
            "4pSA4pSA4pSA4pSAG1syMjszSBsoQhtbbRtbMzg7NTsyMTFt4o+14o+1IGJ5cGFzcyBwZXJtaXNzaW9ucyBvbhsoQhtbbRtbMzg7" +
            "NTsyNDZtIChzaGlmdCt0YWIgdG8gY3ljbGUpG1sxOzIzchtbMjA7NUgbKEIbW20bKEIbW20bWz8xMmwbWz8yNWgbWz8xMDA2bBtb" +
            "PzEwMDBsG1s/MTAwMmwbWz8xMDAzbBtbMTsxSBtbMTsyM3IbWzIwOzVIG1s/MjVsG1s/MTAwNmwbWz8xMDAwbBtbPzEwMDJsG1s/" +
            "MTAwM2wbWz8xMDA2aBtbPzEwMDBoG1s/MTAwMmgbWz8xMDAzaBtbMzBtG1s0Mm0bWzIzOzFIW2dlbWluaV0gPGw+IuKcsyBMb2Nh" +
            "bCBwcm9qZWN0IGdvYWwgIiAxNDoxMyAwMy1PY3QtMjYbKEIbW20bWzIwOzVIG1sxNzsxMkggG1szODs1OzIzMW0bWzQ4OzU7MjM3" +
            "bSAxIG5ldyBtZXNzYWdlG1syMDs1SBsoQhtbbRtbMTg7MTFIG1szODs1OzI0Nm0wJSB1bnRpbCBhdXRvLWNvbXBhY3QgwrcgL21v" +
            "ZGVsIG9wdXNbMW1dG1syMDs1SBsoQhtbbQ==")

    /**
     * A tmux-shaped repaint burst: fill the screen, then repeat
     * DECSTBM(rows 1-22) + SU(22) + partial-line rewrites using ECH between
     * words, plus a status line written outside the scroll region. The status
     * line is what makes the damaged region only partially intersect the
     * scroll region — the case the scrollrect bookkeeping must still track.
     * Words are space-separated so a dropped space (replaced by a neighbour's
     * character) shows up as a text difference.
     */
    private fun burst(): String {
        val sb = StringBuilder()
        for (i in 0 until 24) sb.append("word$i alpha beta gamma delta\r\n")
        repeat(4) { cycle ->
            sb.append("\u001b[1;22r")
            sb.append("\u001b[22S")
            for (r in 1..20 step 3) {
                sb.append("\u001b[${r};1H")
                sb.append("new${cycle}text")
                sb.append("\u001b[1X")
                sb.append("more${cycle}words")
                sb.append("\u001b[1X")
                sb.append("tail${cycle}end")
            }
            sb.append("\u001b[23;1H[status ${cycle}]" + " ".repeat(60))
        }
        return sb.toString()
    }

    /**
     * Variant where damage spans inside and outside the scroll region BEFORE
     * the scroll lands. scrollrect() adjusts the tracked damaged rect to stay
     * valid across the deferred user-side scroll; the interesting case is a
     * region that only partially intersects the scroll at that moment, because
     * the adjustment has to split it. Writes here precede the DECSTBM+SU so the
     * damage is already accumulated when the scroll arrives.
     */
    private fun burstDamageBeforeScroll(): String {
        val sb = StringBuilder()
        for (i in 0 until 24) sb.append("word$i alpha beta gamma delta\r\n")
        repeat(4) { cycle ->
            // Damage inside the region (rows 2-20) and outside it (rows 23-24)
            for (r in 2..20 step 3) {
                sb.append("\u001b[${r};1H")
                sb.append("in${cycle}side words here")
            }
            sb.append("\u001b[23;1Houtside${cycle}status line padding text\r\n")
            sb.append("\u001b[24;1Hbottom${cycle}row also outside\r\n")
            // Now the scroll lands with damage already straddling the region edge
            sb.append("\u001b[1;22r")
            sb.append("\u001b[22S")
            sb.append("\u001b[1;1Hafter${cycle}scroll\r\n")
        }
        return sb.toString()
    }

    /**
     * The real capture from #676: a read_terminal_scrollback tail taken while
     * the glitch was visible on a tmux session (DECSTBM x5, SU, ECH x78). It
     * starts mid-stream, but the differential property does not care about the
     * starting state: the same bytes fed merged vs stepped must converge, so
     * any divergence is dropped damage in the merged path.
     */
    @Test
    fun realTmuxCaptureReplayMatches() {
        val bytes = captureBytes()

        val merged = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val mergedImpl = merged as TerminalEmulatorImpl
        val stepped = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val steppedImpl = stepped as TerminalEmulatorImpl

        merged.writeInput(bytes, 0, bytes.size)
        for (byte in bytes) stepped.writeInput(byteArrayOf(byte), 0, 1)

        val m = settle(mergedImpl)
        val s = settle(steppedImpl)

        val mLines = m.lines.map { it.text }
        val sLines = s.lines.map { it.text }
        for (row in mLines.indices) {
            assertEquals(
                "row $row diverges replaying the real #676 capture",
                sLines[row],
                mLines[row],
            )
        }
    }

    /**
     * The glitch is transient: drain() posts one writeInput per coalesced
     * socket chunk and processPendingUpdates rebuilds the mirror after each,
     * so a frame landing between chunks reflects the native buffer mid-burst.
     * A single writeInput never interleaves a frame, so the final-state test
     * above cannot see it. This replays the real capture in chunks and, at
     * every chunk boundary, compares the chunked mirror against the
     * byte-at-a-time mirror settled at the same offset. The native buffer is
     * the same function of bytes for both; byte-at-a-time never drops damage
     * (each scroll flushes), so it is ground truth. A divergence at a boundary
     * is the transient the user sees.
     */
    @Test
    fun realCaptureChunkBoundariesMatchGroundTruth() {
        val bytes = captureBytes()

        // Split at every scroll-region set (DECSTBM) so a boundary lands right
        // where a deferred scroll is pending, plus the very end.
        // No leading 0: a pristine emulator's lines are '?' placeholders until the
        // first writeInput flushes them to spaces, so comparing before any real
        // chunk is a test artifact, not a divergence.
        val boundaries = mutableListOf<Int>()
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0x1b.toByte() && bytes[i + 1] == 0x5b.toByte()) {
                var j = i + 2
                while (j < bytes.size) {
                    val ch = bytes[j].toInt() and 0xFF
                    if (ch.toChar() == 'r' || (ch in 'A'.code..'Z'.code) || (ch in 'a'.code..'z'.code)) break
                    j++
                }
                if (j < bytes.size && bytes[j] == 0x72.toByte()) boundaries.add(j + 1)
            }
            i++
        }
        boundaries.add(bytes.size)

        val chunked = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val chunkedImpl = chunked as TerminalEmulatorImpl
        val truth = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val truthImpl = truth as TerminalEmulatorImpl

        var prev = 0
        for (b in boundaries) {
            chunked.writeInput(bytes, prev, b - prev)
            for (k in prev until b) truth.writeInput(byteArrayOf(bytes[k]), 0, 1)
            prev = b
            val c = settle(chunkedImpl).lines.map { it.text }
            val t = settle(truthImpl).lines.map { it.text }
            for (row in c.indices) {
                assertEquals(
                    "row $row diverges at chunk boundary $b (transient #676)",
                    t[row],
                    c[row],
                )
            }
        }
    }

    /**
     * Absolute check: at each chunk boundary the incremental mirror must equal
     * a forced full re-pull of the native buffer. Unlike the differential above
     * (which compares two feeding modes and so misses a defect present in both),
     * this catches a stale mirror regardless of feeding mode: setAnsiPalette
     * redamages the whole screen, so the re-pull reflects the native truth.
     */
    @Test
    fun realCaptureMirrorMatchesFullRepullAtBoundaries() {
        val bytes = captureBytes()

        // Split at EVERY CSI sequence end, not just DECSTBM. tmux emits
        // DECSTBM then SU; the corrupt state exists right after the SU scroll,
        // before the next write repaints it, so a boundary must land there too.
        val boundaries = mutableListOf<Int>()
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0x1b.toByte() && bytes[i + 1] == 0x5b.toByte()) {
                var j = i + 2
                while (j < bytes.size) {
                    val ch = bytes[j].toInt() and 0xFF
                    if ((ch in 0x40..0x7E) && ch != 0x3b && !(ch in 0x30..0x3F)) break
                    j++
                }
                if (j < bytes.size) boundaries.add(j + 1)
            }
            i++
        }
        boundaries.add(bytes.size)

        val emu = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val impl = emu as TerminalEmulatorImpl
        val palette = IntArray(16) { 0 }

        var prev = 0
        for (b in boundaries) {
            emu.writeInput(bytes, prev, b - prev)
            prev = b
            val incremental = settle(impl).lines.map { it.text }
            // Force a full re-pull of the native buffer and settle again.
            emu.setAnsiPalette(palette)
            val full = settle(impl).lines.map { it.text }
            for (row in full.indices) {
                assertEquals(
                    "row $row stale at boundary $b: incremental mirror differs from full re-pull",
                    full[row],
                    incremental[row],
                )
            }
        }
    }

    /**
     * Starting-state dependence: the device screen was full of prior tmux
     * output when the glitch appeared, but every replay above starts on a
     * pristine screen. A moverect aliasing bug (a cached line object moved to
     * the wrong row, or a stale cached copy kept for a row that scrolled in)
     * can only fire when cached line objects already exist for the rows the
     * scroll touches. Pre-fill the screen, then replay the capture and run the
     * same absolute mirror-vs-full-repull check at every CSI boundary.
     */
    @Test
    fun realCaptureMirrorMatchesFullRepullFromPrefilledScreen() {
        val bytes = captureBytes()

        val boundaries = mutableListOf<Int>()
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0x1b.toByte() && bytes[i + 1] == 0x5b.toByte()) {
                var j = i + 2
                while (j < bytes.size) {
                    val ch = bytes[j].toInt() and 0xFF
                    if ((ch in 0x40..0x7E) && ch != 0x3b && !(ch in 0x30..0x3F)) break
                    j++
                }
                if (j < bytes.size) boundaries.add(j + 1)
            }
            i++
        }
        boundaries.add(bytes.size)

        val emu = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val impl = emu as TerminalEmulatorImpl
        val palette = IntArray(16) { 0 }

        // Pre-fill: a full screen of word-like content plus a scroll region,
        // so every row has a real cached line object before the capture lands.
        val prefill = burst().toByteArray(Charsets.UTF_8)
        emu.writeInput(prefill, 0, prefill.size)
        settle(impl)

        var prev = 0
        for (b in boundaries) {
            emu.writeInput(bytes, prev, b - prev)
            prev = b
            val incremental = settle(impl).lines.map { it.text }
            emu.setAnsiPalette(palette)
            val full = settle(impl).lines.map { it.text }
            for (row in full.indices) {
                assertEquals(
                    "row $row stale at boundary $b from prefilled start: incremental mirror differs from full re-pull",
                    full[row],
                    incremental[row],
                )
            }
        }
    }

    /**
     * The untested axis: real socket chunks end wherever the network read
     * stops, not at CSI sequence ends. Every boundary above lands after a
     * complete escape sequence, so a settle that interrupts a sequence (mid
     * CSI, mid-UTF8) was never exercised. This reruns the absolute
     * mirror-vs-full-repull check at EVERY byte offset. A warm-up re-pull
     * first converts never-damaged rows from pristine placeholders to the
     * native empty content, so an early boundary compares like for like.
     */
    @Test
    fun realCaptureMirrorMatchesFullRepullAtEveryByteOffset() {
        val bytes = captureBytes()

        val emu = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val impl = emu as TerminalEmulatorImpl
        val palette = IntArray(16) { 0 }

        // Warm the mirror: force a full re-pull of the empty native buffer so
        // every row holds native-empty content, not a pristine placeholder.
        emu.setAnsiPalette(palette)
        settle(impl)

        for (b in 1..bytes.size) {
            emu.writeInput(bytes, b - 1, 1)
            val incremental = settle(impl).lines.map { it.text }
            emu.setAnsiPalette(palette)
            val full = settle(impl).lines.map { it.text }
            for (row in full.indices) {
                assertEquals(
                    "row $row stale at byte offset $b: incremental mirror differs from full re-pull",
                    full[row],
                    incremental[row],
                )
            }
        }
    }

    /**
     * The every-byte-offset test feeds one byte per writeInput, so a scroll
     * never merges with a later op inside the same write — the coalesced path
     * the original scrollrect hypothesis targets is untested by it. Real
     * drain() batches socket bytes into multi-byte writeInputs. This feeds the
     * capture in random multi-byte chunks (seeded, reproducible) and runs the
     * absolute mirror-vs-full-repull check after each chunk, where a chunk's
     * internal scroll+rewrite damage merge actually happens.
     */
    @Test
    fun realCaptureMirrorMatchesFullRepullUnderRandomChunking() {
        val bytes = captureBytes()
        val rnd = java.util.Random(676L)
        val boundaries = mutableListOf<Int>()
        var pos = 0
        while (pos < bytes.size) {
            pos += 1 + rnd.nextInt(200)
            if (pos < bytes.size) boundaries.add(pos)
        }
        boundaries.add(bytes.size)

        val emu = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val impl = emu as TerminalEmulatorImpl
        val palette = IntArray(16) { 0 }
        emu.setAnsiPalette(palette)
        settle(impl)

        var prev = 0
        for (b in boundaries) {
            emu.writeInput(bytes, prev, b - prev)
            prev = b
            val incremental = settle(impl).lines.map { it.text }
            emu.setAnsiPalette(palette)
            val full = settle(impl).lines.map { it.text }
            for (row in full.indices) {
                assertEquals(
                    "row $row stale at chunk boundary $b: incremental mirror differs from full re-pull",
                    full[row],
                    incremental[row],
                )
            }
        }
    }

    @Test
    fun damageStraddlingRegionEdgeBeforeScrollMatches() {
        val merged = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val mergedImpl = merged as TerminalEmulatorImpl
        val stepped = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val steppedImpl = stepped as TerminalEmulatorImpl

        val bytes = burstDamageBeforeScroll().toByteArray()
        merged.writeInput(bytes, 0, bytes.size)
        for (byte in bytes) stepped.writeInput(byteArrayOf(byte), 0, 1)

        val m = settle(mergedImpl)
        val s = settle(steppedImpl)

        val mLines = m.lines.map { it.text }
        val sLines = s.lines.map { it.text }
        for (row in mLines.indices) {
            assertEquals(
                "row $row diverges when damage straddles the scroll region edge (#676)",
                sLines[row],
                mLines[row],
            )
        }
    }

    @Test
    fun mergedBurstMatchesByteAtATime() {
        val merged = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val mergedImpl = merged as TerminalEmulatorImpl
        val stepped = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        val steppedImpl = stepped as TerminalEmulatorImpl

        val bytes = burst().toByteArray()
        merged.writeInput(bytes, 0, bytes.size)
        for (byte in bytes) stepped.writeInput(byteArrayOf(byte), 0, 1)

        val m = settle(mergedImpl)
        val s = settle(steppedImpl)

        val mLines = m.lines.map { it.text }
        val sLines = s.lines.map { it.text }
        // Row-by-row so a failure names the row that diverged.
        for (row in mLines.indices) {
            assertEquals(
                "row $row diverges between merged and stepped feeding (#676 dropped damage)",
                sLines[row],
                mLines[row],
            )
        }
        assertEquals("scrollback must match", s.scrollback.map { it.text }, m.scrollback.map { it.text })
    }
}
