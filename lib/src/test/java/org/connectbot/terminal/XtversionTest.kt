/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * XTVERSION (CSI > Ps q) reply identity.
 *
 * Multiplexers probe this to auto-detect the outer terminal (tmux: XTVERSION
 * reply against a prefix table), so the hosting app overrides libvterm's
 * default identity. kitty replies only for mode 0/omitted; mode != 0 gets no
 * reply here either.
 */
@RunWith(RobolectricTestRunner::class)
class XtversionTest {
    private fun emulator(
        xtversion: String?,
    ): Pair<TerminalEmulatorImpl, MutableList<String>> {
        val responses = mutableListOf<String>()
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            onKeyboardInput = { responses.add(it.toString(Charsets.US_ASCII)) },
            xtversion = xtversion,
        ) as TerminalEmulatorImpl
        return terminal to responses
    }

    private fun TerminalEmulatorImpl.write(text: String) = writeInput(text.toByteArray())

    @Test
    fun setIdentityReplacesTheDefaultReply() {
        val (terminal, responses) = emulator("Haven(5.89.18)")
        terminal.write("\u001b[>q")
        terminal.write("\u001b[>0q")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, responses.size)
        assertEquals("\u001bP>|Haven(5.89.18)\u001b\\", responses[0])
        assertEquals("\u001bP>|Haven(5.89.18)\u001b\\", responses[1])
    }

    @Test
    fun unsetIdentityKeepsTheDefaultLibvtermReply() {
        val (terminal, responses) = emulator(null)
        terminal.write("\u001b[>q")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, responses.size)
        assertEquals("\u001bP>|libvterm(0.3)\u001b\\", responses[0])
    }

    @Test
    fun nonZeroModeGetsNoReply() {
        val (terminal, responses) = emulator("Haven(5.89.18)")
        terminal.write("\u001b[>1q")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, responses.size)
    }
}