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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An OSC payload is whatever the remote program chose to emit, and it is not
 * obliged to be valid UTF-8 — a window title from a non-UTF-8 Windows console
 * is the everyday case.
 *
 * `invokeOscSequence` used to hand those bytes straight to `NewStringUTF`, which
 * does not fail politely: ART treats invalid modified UTF-8 as a fatal error and
 * aborts the process. There is no exception to catch and nothing the Kotlin side
 * can do about it — the app is simply gone.
 *
 * ONE THING THESE TESTS DO NOT PROVE. They run on the host JVM, whose
 * NewStringUTF is lenient about malformed modified UTF-8 — it returns mojibake
 * rather than aborting. Only ART aborts. Measured: with the fix reverted, every
 * test here still passes. So the liveness tests below cannot catch the abort and
 * must not be read as proof against it; that regression is only reproducible on
 * a device.
 *
 * What IS falsifiable on the host is the decode itself — malformed bytes must
 * come out as U+FFFD rather than being passed through raw. That is
 * [malformedBytesBecomeReplacementCharacters], and it does fail against the old
 * NewStringUTF path.
 *
 * Note the truncated four-byte lead below. That case also walks off the end of
 * the input in `utf8_to_mutf8`, which reads `p[1..3]` unconditionally — the
 * reason this fix decodes to UTF-16 rather than routing through that helper.
 */
@RunWith(AndroidJUnit4::class)
class OscMalformedUtf8Test {

    /** Byte sequences that are not valid UTF-8, each malformed a different way. */
    private val malformedPayloads = listOf(
        "lone continuation byte" to byteArrayOf(0x80.toByte()),
        "truncated 2-byte lead" to byteArrayOf(0xC3.toByte()),
        "truncated 3-byte lead" to byteArrayOf(0xE2.toByte(), 0x82.toByte()),
        "truncated 4-byte lead" to byteArrayOf(0xF0.toByte(), 0x9F.toByte()),
        "bytes UTF-8 never uses" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
        "encoded surrogate" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        "overlong NUL" to byteArrayOf(0xC0.toByte(), 0x80.toByte()),
        "continuation without lead, mid-text" to
            "ok".toByteArray() + byteArrayOf(0xBF.toByte()) + "ok".toByteArray(),
    )

    /** ESC ] <command> ; <payload> BEL — without the ESC it is not an OSC at all. */
    private fun osc(command: String, payload: ByteArray): ByteArray = "\u001B]$command;".toByteArray() + payload + byteArrayOf(0x07)

    @Test
    fun malformedOscPayloadsDoNotAbortTheProcess() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        for ((_, payload) in malformedPayloads) {
            // OSC 0 is the window title, the sequence most likely to carry text
            // straight from the remote host's locale.
            emulator.writeInput(osc("0", payload))
            delay(10)
        }

        // Whatever those did, the terminal has to still work afterwards. Without
        // this a payload that was quietly dropped along with the rest of the
        // parser state would pass.
        emulator.writeInput("still alive\r\n".toByteArray())
        delay(100)

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val impl = emulator as TerminalEmulatorImpl
        impl.processPendingUpdates()
        val text = impl.snapshot.value.lines.joinToString("\n") { it.columnText }
        assertTrue(
            "terminal stopped echoing after malformed OSC payloads; got:\n$text",
            text.contains("still alive"),
        )
    }

    /**
     * The report that led here came from a capability-probe burst, so the
     * sequences arrive back to back rather than one per read.
     */
    @Test
    fun aBurstOfMalformedProbesDoesNotAbortTheProcess() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        var burst = ByteArray(0)
        for ((_, payload) in malformedPayloads) {
            burst += osc("0", payload)
            burst += osc("1337", payload)
            burst += osc("4", payload)
        }
        emulator.writeInput(burst)
        delay(150)

        emulator.writeInput("survived\r\n".toByteArray())
        delay(100)

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val impl = emulator as TerminalEmulatorImpl
        impl.processPendingUpdates()
        val text = impl.snapshot.value.lines.joinToString("\n") { it.columnText }
        assertTrue(
            "terminal stopped echoing after a malformed probe burst; got:\n$text",
            text.contains("survived"),
        )
    }

    /** Well-formed multi-byte text must still survive the new decode path. */
    @Test
    fun validMultiByteOscPayloadsStillWork() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        for (title in listOf("plain", "café", "日本語", "emoji 🎉", "mixed é日🎉")) {
            emulator.writeInput(osc("0", title.toByteArray(Charsets.UTF_8)))
            delay(10)
        }

        emulator.writeInput("after unicode\r\n".toByteArray())
        delay(100)

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val impl = emulator as TerminalEmulatorImpl
        impl.processPendingUpdates()
        val text = impl.snapshot.value.lines.joinToString("\n") { it.columnText }
        assertTrue(
            "valid UTF-8 titles broke the terminal; got:\n$text",
            text.contains("after unicode"),
        )
    }

    /**
     * The assertion that can actually fail on the host JVM.
     *
     * OSC 1337 `AddAnnotation=` reaches the fallback OSC handler — the one that
     * builds the jstring — and stores its payload as segment metadata, so the
     * decoded string is readable back out. Malformed bytes must arrive as U+FFFD
     * with the surrounding valid text intact; the old path passed the raw bytes
     * to NewStringUTF, which on this JVM yields mojibake instead.
     */
    @Test
    fun malformedBytesBecomeReplacementCharacters() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        // "ab" <lone continuation byte> "cd" — valid text either side of one bad byte.
        val payload = "AddAnnotation=ab".toByteArray() +
            byteArrayOf(0x80.toByte()) +
            "cd".toByteArray()
        emulator.writeInput("hello".toByteArray())
        emulator.writeInput(osc("1337", payload))
        delay(150)

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val impl = emulator as TerminalEmulatorImpl
        impl.processPendingUpdates()

        val metadata = impl.snapshot.value.lines
            .flatMap { it.semanticSegments }
            .mapNotNull { it.metadata }
        assertTrue(
            "no annotation metadata was recorded at all; got $metadata",
            metadata.isNotEmpty(),
        )
        val annotation = metadata.first()
        assertTrue(
            "the bad byte should decode to U+FFFD, got: ${annotation.map { it.code.toString(16) }}",
            annotation.contains('\uFFFD'),
        )
        assertTrue(
            "valid text either side of the bad byte should survive, got: $annotation",
            annotation.contains("ab") && annotation.contains("cd"),
        )
    }
}
