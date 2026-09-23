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

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer

@RunWith(AndroidJUnit4::class)
class ImeInputViewTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyboardHandler: KeyboardHandler

    @Before
    fun setup() {
        val terminalEmulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        keyboardHandler = KeyboardHandler(terminalEmulator)
    }

    private val noOpImm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun makeView(
        selectionUpdates: MutableList<SelectionUpdate>? = null,
    ): ImeInputView {
        val onUpdateSelection: (View, Int, Int, Int, Int) -> Unit =
            if (selectionUpdates != null) {
                { view, selStart, selEnd, cStart, cEnd ->
                    selectionUpdates.add(SelectionUpdate(view, selStart, selEnd, cStart, cEnd))
                }
            } else {
                { _, _, _, _, _ -> }
            }
        return ImeInputView(context, keyboardHandler, noOpImm, onUpdateSelection)
    }

    data class SelectionUpdate(
        val view: View,
        val selStart: Int,
        val selEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
    )

    private fun ImeInputView.ic(composeMode: Boolean = false): BaseInputConnection {
        isComposeModeActive = composeMode
        return onCreateInputConnection(EditorInfo()) as BaseInputConnection
    }

    private fun ImeInputView.standardIc(): BaseInputConnection {
        allowStandardKeyboard = true
        return onCreateInputConnection(EditorInfo()) as BaseInputConnection
    }

    // === IME paste actions reach the terminal paste handler ===
    //
    // Some IMEs offer paste through the InputConnection's context-menu
    // actions (GBoard's paste suggestion) rather than key events. Without
    // performContextMenuAction handling them, those suggestions are
    // silently dropped.

    @Test
    fun testImePasteActionsInvokeTerminalPasteHandler() {
        for (composeMode in listOf(false, true)) {
            val view = makeView()
            var pasteCount = 0
            view.onPasteRequest = { pasteCount++ }
            val ic = view.ic(composeMode)

            assertTrue(ic.performContextMenuAction(android.R.id.paste))
            assertEquals(1, pasteCount)
            assertTrue(ic.performContextMenuAction(android.R.id.pasteAsPlainText))
            assertEquals(2, pasteCount)
            assertFalse(ic.performContextMenuAction(android.R.id.copy))
            assertEquals(2, pasteCount)
        }
    }

    @Test
    fun testImePasteUsesUpdatedHandlerWithoutRecreatingConnection() {
        val view = makeView()
        val ic = view.ic()
        assertFalse(ic.performContextMenuAction(android.R.id.paste))
        assertFalse(ic.performContextMenuAction(android.R.id.pasteAsPlainText))

        var firstCount = 0
        var secondCount = 0
        view.onPasteRequest = { firstCount++ }
        assertTrue(ic.performContextMenuAction(android.R.id.paste))
        view.onPasteRequest = { secondCount++ }
        assertTrue(ic.performContextMenuAction(android.R.id.paste))
        assertEquals(1, firstCount)
        assertEquals(1, secondCount)

        view.onPasteRequest = null
        assertFalse(ic.performContextMenuAction(android.R.id.paste))
        assertEquals(1, secondCount)
    }

    // === #298: the IME must be able to READ the document ===
    //
    // BaseInputConnection.getExtractedText() always returns null. In Standard mode we
    // advertise a rich-editing field (no NO_EXTRACT_UI, AUTO_CORRECT set), so a
    // prediction IME that mirrors the editor through ExtractedText — SwiftKey polls it
    // after every edit — got nothing back and fell through to its own model of the
    // document, which no restartInput/updateSelection/getTextBeforeCursor resets. It
    // then kept composing over the already-executed line: a second `ls` arrived as
    // `lsls`, and it accreted from there (agross's trace).

    @Test
    fun testGetExtractedTextReportsTheDocumentInStandardMode() {
        val ic = makeView().standardIc()
        ic.commitText("ls", 1)

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)

        assertNotNull("null strands an IME that mirrors the doc via ExtractedText", extracted)
        assertEquals("ls", extracted!!.text.toString())
        assertEquals(2, extracted.selectionStart)
        assertEquals(2, extracted.selectionEnd)
    }

    @Test
    fun testGetExtractedTextIsEmptyAfterEnter() {
        val ic = makeView().standardIc()
        ic.commitText("ls", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        // The executed line is gone. An IME reading the document now sees an empty
        // field, so it has no basis to keep composing over `ls`.
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals("", extracted?.text?.toString())
        assertEquals(0, extracted?.selectionStart)
    }

    @Test
    fun testGetExtractedTextStaysNullInSecureMode() {
        // Secure mode has no real Editable and suppresses suggestions outright; there
        // is no document to report and nothing that wants one.
        val ic = makeView().ic()

        assertNull(ic.getExtractedText(ExtractedTextRequest(), 0))
    }

    // === IME editable buffer reset on key events (compose mode — has a real Editable) ===

    @Test
    fun testSendEnterKeyDownClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("git status", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSendKeyUpDoesNotClearEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.getEditable()?.append("hello")

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals("hello", ic.getEditable()?.toString())
    }

    @Test
    fun testSendBackspaceKeyDownShrinksEditableByOne() {
        // Gboard Telex reads getTextBeforeCursor. A terminal DEL that leaves
        // the Editable stale makes the next syllable compose onto the deleted
        // word (độc deleted on screen, Gboard still sees độc). Shrink one
        // code point — do not wipe the whole buffer (#99).
        val ic = makeView().ic(composeMode = true)
        ic.commitText("abc", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertEquals("ab", ic.getEditable()?.toString())
    }

    @Test
    fun testRepeatedDelDownShrinksEntireBufferWithoutKeyUp() {
        // Gboard hold-backspace is a stream of ACTION_DOWN. Restarting the IME
        // on a word boundary used to abort that stream after a few characters.
        val ic = makeView().ic(composeMode = true)
        ic.commitText("abcdefghij", 1)

        repeat(10) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSendBackspaceUntilWordGoneClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("độc", 1)

        repeat(3) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }

        assertEquals("", ic.getEditable()?.toString())
        assertEquals("", ic.getTextBeforeCursor(20, 0)?.toString())
    }

    @Test
    fun testDeleteSurroundingTextDoesNotDoubleDeleteEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("abc", 1)

        ic.deleteSurroundingText(1, 0)

        assertEquals("ab", ic.getEditable()?.toString())
    }

    @Test
    fun testDeletingWordLeavesPrecedingTokenInEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("ls độc", 1)

        repeat(3) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }

        assertEquals("ls ", ic.getEditable()?.toString())
    }

    @Test
    fun testSecondCommandDoesNotAccumulateAfterEnter() {
        // Regression: "git status<enter>ls -l" should not appear as one suggestion candidate.
        val ic = makeView().ic(composeMode = true)

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())

        ic.commitText("ls -l", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())
    }

    // === commitText RETAINS editable (#99 — Gboard autocorrect context) ===

    @Test
    fun testCommitTextRetainsTextInEditable() {
        // #99: committed text stays in Editable so Gboard can offer autocorrect
        // suggestions. Clearing on commit leaves Gboard in per-char mode.
        val ic = makeView().ic(composeMode = true)
        ic.commitText("some text", 1)

        assertEquals("some text", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextWithActiveCompositionRetainsReplacement() {
        // When a composition is active and commitText arrives, the committed
        // text replaces the composition region. Result lives in Editable.
        val ic = makeView().ic(composeMode = true)
        ic.setComposingText("wor", 1)
        ic.commitText("word", 1)

        assertEquals("word", ic.getEditable()?.toString())
    }

    // === updateSelection is called after ACTION_DOWN key events (compose mode) ===

    @Test
    fun testUpdateSelectionCalledAfterEnterKeyDown() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    @Test
    fun testUpdateSelectionNotResetAfterBackspaceKeyDown() {
        // #99: backspace should NOT reset IME state to (0, 0, -1, -1) — that
        // would drop Gboard's autocorrect context. Only Enter is a command
        // boundary that warrants a full selection reset.
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.commitText("abc", 1)
        val updatesBeforeDel = updates.size
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        // Mid-word backspace must not look like Enter.
        val newUpdates = updates.drop(updatesBeforeDel)
        assertTrue(
            "Mid-word backspace should not reset IME selection to (0,0,-1,-1)",
            newUpdates.none { it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 },
        )
    }

    @Test
    fun testUpdateSelectionNotCalledOnKeyUp() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertTrue(updates.isEmpty())
    }

    // === resetImeBuffer() — used by physical keyboard paths that bypass InputConnection ===

    @Test
    fun testResetImeBufferClearsEditable() {
        val view = makeView()
        val ic = view.ic(composeMode = true)

        ic.commitText("git status", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testResetImeBufferCallsUpdateSelection() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        view.ic()

        view.resetImeBuffer()

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    @Test
    fun testResetImeBufferBeforeConnectionCreatedDoesNotCrash() {
        val view = makeView()
        // No InputConnection created yet — should not throw
        view.resetImeBuffer()
    }

    @Test
    fun testResetImeBufferClearsEditableAccumulatedBySetComposingText() {
        // setComposingText (voice input path) writes to the editable but does not clear it —
        // only finishComposingText does. resetImeBuffer() must also handle this mid-composition
        // case, which can be triggered by a physical hardware key interrupting voice input.
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.setComposingText("hel", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    // === Physical-keyboard reset policy (issue #99 follow-up) ===

    @Test
    fun testShouldResetImeBufferOnKey_commandBoundaries() {
        // Enter and Escape end or interrupt the current shell line, so the IME
        // can safely forget its tracked prefix and start the next command fresh.
        assertTrue(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertTrue(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun testShouldResetImeBufferOnKey_textAndModifiersDoNotReset() {
        // Heart of the #99 follow-up: pressing Shift or typing a capital on a
        // physical keyboard must not wipe Gboard's tracked context for
        // subsequent soft-keyboard autocorrect.
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_CTRL_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_ALT_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_META_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_CAPS_LOCK))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_A))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_Z))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_0))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun testShouldResetImeBufferOnKey_navigationDoesNotReset() {
        // Cursor movement, backspace, Tab, and function keys either don't
        // produce text (nav) or aren't meaningful command boundaries (Tab,
        // Fn). Leaving them alone keeps the IME's context intact across
        // brief physical-keyboard interjections.
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_DEL))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_FORWARD_DEL))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_TAB))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_F1))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_F12))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_HOME))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_MOVE_END))
        assertFalse(ImeInputView.shouldResetImeBufferOnKey(KeyEvent.KEYCODE_PAGE_UP))
    }

    // === IME duplicate character tests (connectbot/connectbot#1955) ===

    private fun createKeyboardOutputCapture(): Pair<InputConnection, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(context, handler)
            view.isComposeModeActive = true
            view.setOnKeyListener { _, _, event ->
                handler.onKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(event),
                )
            }
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return ic!! to outputs
    }

    /**
     * Compute the effective text from captured keyboard output by applying
     * BS (0x08) and DEL (0x7F) as character erasure operations.
     */
    private fun effectiveText(outputs: List<ByteArray>): String {
        val buffer = StringBuilder()
        for (data in outputs) {
            for (byte in data) {
                val code = byte.toInt() and 0xFF
                when {
                    code == 0x08 || code == 0x7F -> {
                        if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                    }

                    code >= 0x20 -> buffer.append(byte.toInt().toChar())
                }
            }
        }
        return buffer.toString()
    }

    private fun drainMainLooper() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun testCommitAfterComposingDoesNotDuplicate() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("a", 1)
            ic.commitText("a", 1)
        }
        drainMainLooper()
        assertEquals("a", effectiveText(outputs))
    }

    @Test
    fun testMultiCharComposingCommitDoesNotDuplicate() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.commitText("hel", 1)
        }
        drainMainLooper()
        assertEquals("hel", effectiveText(outputs))
    }

    @Test
    fun testDirectCommitWithoutComposing() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("x", 1)
        }
        drainMainLooper()
        assertEquals("x", effectiveText(outputs))
    }

    // === #298: Enter while still composing (Standard mode / SwiftKey) ===
    // In fullEditor mode the typed line lives in the floating composing buffer
    // and only reaches the shell on commitText. SwiftKey sends Enter via
    // sendKeyEvent mid-composition, so the line must be flushed BEFORE the
    // newline rather than arriving (late) on the next prompt.

    @Test
    fun testEnterFlushesPendingCompositionInFullEditor() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("ls", 1) // composing, not yet committed
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()
        // The line reached the shell exactly once (CR is stripped by effectiveText).
        assertEquals("ls", effectiveText(outputs))
        // ...and the Enter (CR) reached the terminal too.
        assertTrue("Enter did not reach the terminal", outputs.any { it.contains(0x0D.toByte()) })
    }

    @Test
    fun testEnterFlushedLineNotDuplicatedOnLateRecommit() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("ls", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.commitText("ls", 1) // SwiftKey re-delivers the line after the newline
        }
        drainMainLooper()
        // Suppressed by the one-shot guard — "ls", not "lsls".
        assertEquals("ls", effectiveText(outputs))
    }

    @Test
    fun testEnterAfterCommitDoesNotSuppressIdenticalNextCommand() {
        // Gboard path: it commits before Enter, so nothing is composing at Enter
        // and the guard is never armed — a legitimately-identical second command
        // must still reach the shell.
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("ls", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.commitText("ls", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()
        assertEquals("lsls", effectiveText(outputs))
    }

    @Test
    fun testEnterWithNoCompositionStillReachesTerminal() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()
        assertEquals("", effectiveText(outputs))
        assertTrue("Enter did not reach the terminal", outputs.any { it.contains(0x0D.toByte()) })
    }

    // === Unicode precomposition (NFC normalization) ===

    /**
     * Some IMEs send decomposed Unicode (NFD): a base character followed by a combining
     * diacritic as separate code points. The terminal must send the precomposed NFC form
     * so the remote host receives a single character (e.g. ä U+00E4) rather than two
     * separate code points (a U+0061 + combining umlaut U+0308).
     */
    @Test
    fun testDecomposedUmlautIsPrecomposed() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFD: 'a' (U+0061) + combining diaeresis (U+0308) → should arrive as NFC ä (U+00E4)
        val nfdUmlaut = "a\u0308"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfdUmlaut, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        val expected = Normalizer.normalize(nfdUmlaut, Normalizer.Form.NFC)
        assertEquals(expected, received)
    }

    @Test
    fun testDecomposedCircumflexIsPrecomposed() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFD: 'e' (U+0065) + combining circumflex (U+0302) → should arrive as NFC ê (U+00EA)
        val nfdCircumflex = "e\u0302"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfdCircumflex, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        val expected = Normalizer.normalize(nfdCircumflex, Normalizer.Form.NFC)
        assertEquals(expected, received)
    }

    @Test
    fun testAlreadyNfcTextIsUnchanged() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFC ä (U+00E4) should pass through unchanged
        val nfcUmlaut = "\u00E4"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfcUmlaut, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals(nfcUmlaut, received)
    }

    @Test
    fun testSurrogatePairSentAsOneCodepoint() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // U+1F600 GRINNING FACE — encoded as a surrogate pair in Java/Kotlin strings
        val emoji = "\uD83D\uDE00"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(emoji, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals(emoji, received)
    }

    // === Soft-keyboard TYPE_NULL key event routing ===

    private fun createNonComposeModeCapture(): Triple<InputConnection, ImeInputView, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                    ) {
                        v.resetImeBuffer()
                    }
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }
        return Triple(ic!!, view!!, outputs)
    }

    /**
     * With TYPE_NULL, InputConnection.sendKeyEvent delivers the key directly to the terminal.
     */
    @Test
    fun testTypeNullSendKeyEventDeliversCharacter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, a raw view event (e.g. physical keyboard) that arrives independently
     * of sendKeyEvent still reaches the terminal via setOnKeyListener.
     */
    @Test
    fun testTypeNullRawViewEventDeliversCharacter() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, ENTER via View.dispatchKeyEvent must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewEventEnterReachesTerminal() {
        val outputs = mutableListOf<ByteArray>()
        var enterDispatched = false
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data ->
                outputs.add(data.copyOf())
                if (data.contains(0x0D.toByte())) enterDispatched = true
            },
        )
        val handler = KeyboardHandler(emulator)
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                    ) {
                        v.resetImeBuffer()
                    }
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                v.onCreateInputConnection(EditorInfo())
            }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        assertTrue("ENTER via dispatchKeyEvent did not reach the terminal", enterDispatched)
    }

    /**
     * Gboard can deliver one logical keystroke as commitText AND a concurrent
     * raw view event. commitText is authoritative; the raw echo is suppressed
     * so the shell sees a single 'a'.
     */
    @Test
    fun testTypeNullRawViewEventAfterCommitTextIsEchoSuppressed() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("a", 1)
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    @Test
    fun testTypeNullSendKeyEventAfterCommitTextIsEchoSuppressed() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("a", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    @Test
    fun testTypeNullPostedRawKeyAfterCommitTextIsEchoSuppressed() {
        val (ic, view, outputs) = createNonComposeModeCapture()
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("a", 1)
            main.post {
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            }
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    @Test
    fun testTypeNullRawKeyAfterEchoDrainIsGenuineRepeat() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("a", 1)
        }
        drainMainLooper()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("aa", effectiveText(outputs))
    }

    @Test
    fun testTypeNullCommitTextAaPreservesBothCharacters() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("aa", 1)
        }
        drainMainLooper()

        assertEquals("aa", effectiveText(outputs))
    }

    @Test
    fun testTypeNullTwoRawKeyAPreservesBothCharacters() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("aa", effectiveText(outputs))
    }

    @Test
    fun testTypeNullCommitTextAThenRawBIsNotSuppressed() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("a", 1)
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
        }
        drainMainLooper()

        assertEquals("ab", effectiveText(outputs))
    }

    @Test
    fun testTypeNullCommitTextDeliversAccentedCharacterWithoutDuplication() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("ü", 1)
            ic.commitText("a", 1)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("üa", received)
    }

    /**
     * With TYPE_NULL, KEYCODE_DEL delivered via View.dispatchKeyEvent (physical keyboard or
     * Gboard's raw key path) must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewDelKeyReachesTerminal() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("DEL via dispatchKeyEvent did not reach the terminal", received.contains(0x7F.toByte()))
    }

    /**
     * Samsung Keyboard composes typed text via setComposingText then accepts via
     * finishComposingText without firing commitText. In Secure mode our
     * setComposingText handler eagerly commits the delta so sticky toolbar
     * modifiers (Ctrl/Alt) take effect on the very next keypress. (#110)
     */
    @Test
    fun testTypeNullSetComposingTextEagerlyCommitsInSecureMode() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.setComposingText("hell", 1)
            ic.setComposingText("hello", 1)
            ic.finishComposingText()
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("hello", received)
    }

    /**
     * Samsung Keyboard re-fires each composed character as a sendKeyEvent on its
     * batch flush (space/enter). The chars we already committed eagerly via
     * setComposingText must be suppressed to prevent double input. (#110)
     */
    @Test
    fun testTypeNullSendKeyEventAfterEagerComposeIsSuppressed() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("d", 1)
            ic.finishComposingText()
            // Samsung's deferred sendKeyEvent for the same char.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("d", received)
    }

    /**
     * Real Samsung Keyboard "pwd<ENTER>" trace from SeriousM's logcat
     * (#110 v5.24.46 confirmation). Each char arrives via setComposingText
     * during typing (eager-committed), then finishComposingText with no
     * extra commit, then ENTER as a real sendKeyEvent that should reach
     * the terminal as 0x0d (CR). Asserts the full sequence renders as
     * "pwd\n" or "pwd\r" — whatever the terminal layer emits for ENTER.
     */
    @Test
    fun testTypeNullSamsungComposeMultiCharThenEnter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("p", 1)
            ic.setComposingText("pw", 1)
            ic.setComposingText("pwd", 1)
            ic.finishComposingText()
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        // Terminal emits CR for ENTER by default (DECCKM off, no LFE).
        assertEquals("pwd\r", received)
    }

    /**
     * Same multi-char path but with each char's *real-keyboard* ACTION_DOWN
     * sendKeyEvent arriving after finishComposingText (some keyboards do this
     * instead of accepting the composition). Each ACTION_DOWN should be
     * suppressed — the terminal must receive each char exactly once.
     */
    @Test
    fun testTypeNullSamsungMultiCharSuppressedOnDeferredKeyEvents() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.setComposingText("hell", 1)
            ic.setComposingText("hello", 1)
            ic.finishComposingText()
            // Deferred per-char sendKeyEvents that Samsung sometimes flushes.
            listOf(
                KeyEvent.KEYCODE_H,
                KeyEvent.KEYCODE_E,
                KeyEvent.KEYCODE_L,
                KeyEvent.KEYCODE_L,
                KeyEvent.KEYCODE_O,
            ).forEach { code ->
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("hello", received)
    }

    /**
     * Composition shrinks (user backspaces during composition before the IME
     * has flushed): we should send backspaces for the lost chars. (#110)
     */
    @Test
    fun testTypeNullSetComposingTextShrinkSendsBackspaces() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("test", 1)
            ic.setComposingText("te", 1)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        // First four bytes are "test"; then two DEL (0x7F) backspaces.
        assertEquals("test".toByteArray().toList() + listOf(0x7F.toByte(), 0x7F.toByte()), received.toList())
    }

    /**
     * With TYPE_NULL, soft-keyboard backspace arrives via deleteSurroundingText →
     * sendKeyEvent(KEYCODE_DEL). Verify it reaches the terminal.
     */
    @Test
    fun testTypeNullDeleteSurroundingTextDeliversBackspace() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("DEL via deleteSurroundingText did not reach the terminal", received.contains(0x7F.toByte()))
    }

    // === #298: Secure mode commitText after composition must not double-send ===
    // Default (Secure) mode eager-sends each composition delta via
    // setComposingText → applyComposingDelta. An IME that then *commits* the
    // word (Gboard-style commitText, unlike Samsung's finishComposingText) used
    // to have commitText re-send the whole word, so the shell saw "lsls" and
    // Ctrl+D never landed on an empty prompt. agross hit exactly this in default
    // Secure mode (#298); the earlier #298 fix only covered Standard (fullEditor)
    // mode, which is why it didn't help.

    @Test
    fun testSecureModeComposeThenCommitDoesNotDouble() {
        val (ic, _, outputs) = createNonComposeModeCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("l", 1)
            ic.setComposingText("ls", 1)
            ic.commitText("ls", 1)
        }
        drainMainLooper()
        assertEquals("ls", effectiveText(outputs))
    }

    @Test
    fun testSecureModeComposeThenCommitTrailingSpace() {
        // IME commits the composed word plus a trailing space in one commitText.
        // Only the delta (" ") should reach the shell, never the whole "ls" again.
        val (ic, _, outputs) = createNonComposeModeCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("l", 1)
            ic.setComposingText("ls", 1)
            ic.commitText("ls ", 1)
        }
        drainMainLooper()
        assertEquals("ls ", effectiveText(outputs))
    }

    @Test
    fun testSecureModeComposeThenCommitThenEnterKeyEvent() {
        // Full realistic "ls<enter>" Secure path: compose, commit, then a
        // separate ENTER via sendKeyEvent. The line reaches the shell once, + CR.
        val (ic, _, outputs) = createNonComposeModeCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("ls", 1)
            ic.commitText("ls", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()
        assertEquals("ls", effectiveText(outputs))
        assertTrue("Enter did not reach the terminal", outputs.any { it.contains(0x0D.toByte()) })
    }

    @Test
    fun testSecureModeDirectCommitWithoutComposingUnaffected() {
        // No composition first (some IMEs commit per key in Secure mode): the
        // char must still be delivered exactly once — the anti-double guard is
        // armed by an active composition only, so it must not eat this.
        val (ic, _, outputs) = createNonComposeModeCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("x", 1)
        }
        drainMainLooper()
        assertEquals("x", effectiveText(outputs))
    }

    // === DelKeyMode IME tests ===

    private fun createNonComposeModeWithMode(delKeyMode: DelKeyMode): Pair<InputConnection, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.delKeyMode = delKeyMode
        var ic: InputConnection? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        ImeInputView.shouldResetImeBufferOnKey(event.keyCode)
                    ) {
                        v.resetImeBuffer()
                    }
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }
        return ic!! to outputs
    }

    @Test
    fun testImeSoftBackspaceDeleteModeDefaultDeliversDel() {
        val (ic, outputs) = createNonComposeModeWithMode(DelKeyMode.Delete)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected DEL (0x7f) in default Delete mode", received.contains(0x7F.toByte()))
    }

    @Test
    fun testImeSoftBackspaceBackspaceModeDeliversCtrlH() {
        val (ic, outputs) = createNonComposeModeWithMode(DelKeyMode.Backspace)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected ^H (0x08) in Backspace mode", received.contains(0x08.toByte()))
        assertFalse("Should NOT send DEL (0x7f) in Backspace mode", received.contains(0x7F.toByte()))
    }

    /**
     * With TYPE_NULL, soft-keyboard ENTER arrives via sendKeyEvent(KEYCODE_ENTER) — it is a
     * non-printable key so there is no competing raw view event. Verify it reaches the terminal.
     */
    @Test
    fun testTypeNullSendKeyEventEnterReachesTerminal() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("ENTER via sendKeyEvent did not reach the terminal", received.contains(0x0D.toByte()))
    }

    // === Ctrl/Alt modifier key routing from soft keyboards (issue-2050) ===
    // Keyboards like "Unexpected keyboard", SwiftKey, and Hacker's Keyboard send Ctrl/Alt
    // combos via sendKeyEvent (with or without metaState). All sendKeyEvent calls are
    // forwarded directly to keyboardHandler.

    /**
     * Ctrl+A via sendKeyEvent (metaState=META_CTRL_ON) must reach the terminal as 0x01.
     * This is the path used by keyboards like "Unexpected keyboard" and SwiftKey.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlAProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+A via sendKeyEvent did not produce 0x01", received.contains(0x01.toByte()))
    }

    /**
     * Ctrl+C via sendKeyEvent must reach the terminal as 0x03.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlCProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+C via sendKeyEvent did not produce 0x03", received.contains(0x03.toByte()))
    }

    /**
     * Alt+A via sendKeyEvent must reach the terminal as ESC + 'a'.
     */
    @Test
    fun testTypeNullSendKeyEventAltAProducesEscapePrefix() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_ALT_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Alt+A via sendKeyEvent did not produce ESC prefix (0x1B)", received.contains(0x1B.toByte()))
        assertTrue("Alt+A via sendKeyEvent did not produce 'a'", received.contains('a'.code.toByte()))
    }

    /**
     * Plain printable key via sendKeyEvent (no modifier) delivers the character.
     * Hacker's Keyboard uses this path for number keys.
     */
    @Test
    fun testTypeNullSendKeyEventPlainPrintableDeliversCharacter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
        }
        drainMainLooper()

        assertEquals("b", effectiveText(outputs))
    }

    /**
     * Space via sendKeyEvent delivers a space. KEYCODE_SPACE has isPrintingKey()=false
     * (KeyCharacterMap classifies ' ' as SPACE_SEPARATOR), but it is still forwarded like
     * all other sendKeyEvent keys.
     */
    @Test
    fun testTypeNullSendKeyEventSpaceDeliversSpace() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        }
        drainMainLooper()

        assertEquals(" ", effectiveText(outputs))
    }

    // === Standard keyboard mode: sticky Ctrl/Alt must not get stuck (#298) ===
    //
    // In Standard (fullEditor) mode the IME composes typed characters in a
    // floating overlay and only commits on a word boundary. A sticky toolbar
    // Ctrl tapped before such a character used to never get consumed (the char
    // was composed, producing no terminal output), so it leaked onto the next
    // dispatched key — turning Enter into Ctrl+Enter, which libvterm encodes as
    // ^[[13;5u (CSI-u). zsh then echoed the literal bytes and choked. The fix
    // eager-dispatches control combos so Ctrl-D fires now and the modifier is
    // consumed immediately.

    private class TestModifierManager(
        var ctrl: Boolean = false,
        var alt: Boolean = false,
    ) : ModifierManager {
        override fun isCtrlActive() = ctrl
        override fun isAltActive() = alt
        override fun isShiftActive() = false

        // Mirror the production one-shot reset (TerminalScreen wires
        // clearTransients -> viewModel.clearStickyModifiers).
        override fun clearTransients() {
            ctrl = false
            alt = false
        }
    }

    private fun createFullEditorCaptureWithModifiers(
        mods: TestModifierManager,
    ): Triple<InputConnection, KeyboardHandler, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.modifierManager = mods
        var ic: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(context, handler)
            view.isComposeModeActive = true // fullEditor path (== Standard mode)
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return Triple(ic!!, handler, outputs)
    }

    private fun composeKeyDown(keyCode: Int) = androidx.compose.ui.input.key.KeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    @Test
    fun testStandardModeCtrlComboDispatchesControlCharAndConsumesModifier() {
        val mods = TestModifierManager(ctrl = true)
        val (ic, _, outputs) = createFullEditorCaptureWithModifiers(mods)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("d", 1)
        }
        drainMainLooper()

        val bytes = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+D should reach the terminal as ^D (0x04)", bytes.contains(0x04.toByte()))
        assertFalse("the one-shot Ctrl must be consumed by the dispatch", mods.ctrl)
    }

    @Test
    fun testStandardModeCtrlDoesNotLeakOntoEnter() {
        val mods = TestModifierManager(ctrl = true)
        val (ic, handler, outputs) = createFullEditorCaptureWithModifiers(mods)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("d", 1) // Ctrl consumed here
            handler.onKeyEvent(composeKeyDown(KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        val bytes = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+D should emit ^D", bytes.contains(0x04.toByte()))
        assertTrue("Enter should emit CR (0x0D)", bytes.contains(0x0D.toByte()))
        assertFalse(
            "Enter must not be encoded as Ctrl+Enter CSI-u (^[[13;5u contains ESC 0x1B)",
            bytes.contains(0x1B.toByte()),
        )
    }

    @Test
    fun testStandardModeCtrlComboFollowUpCommitDoesNotDouble() {
        val mods = TestModifierManager(ctrl = true)
        val (ic, _, outputs) = createFullEditorCaptureWithModifiers(mods)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("d", 1) // eager ^D
            ic.commitText("d", 1) // IME committing the same text — must be skipped
        }
        drainMainLooper()

        val bytes = outputs.flatMap { it.toList() }.toByteArray()
        assertEquals("exactly one ^D, no follow-up plain 'd'", 1, bytes.size)
        assertEquals(0x04.toByte(), bytes[0])
    }

    @Test
    fun testStandardModeNormalCompositionUnaffectedByCtrlFix() {
        val mods = TestModifierManager(ctrl = false)
        val (ic, _, outputs) = createFullEditorCaptureWithModifiers(mods)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("a", 1) // floating — no terminal output yet
            ic.commitText("a", 1)
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    // === #298 (agross repro): Enter mid-composition in Standard/Compose mode ===
    // "ls<Return>ls<Return>exit" reached the shell as "lslsexit" — the composed
    // line and/or its Enter never arrived, so lines concatenated on one prompt.
    // These drive the three routes an IME can take to end a line and assert the
    // shell receives the text AND the newline, exactly once, in order.

    /** Raw bytes to the PTY, undecoded — newline assertions need the \r. */
    private fun rawText(outputs: List<ByteArray>): String = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)

    /** Run delayed main-looper tasks (the 16 ms deferred-Enter fallback). */
    private fun advanceMainLooper(ms: Long = 32) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(ms))
    }

    @Test
    fun testComposeModeFinishComposingThenEnterKeySendsLine() {
        // Route 1: IME accepts the composition via finishComposingText() (no
        // commitText), then sends Enter as a key event.
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("l", 1)
            ic.setComposingText("ls", 1)
            ic.finishComposingText()
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        assertEquals("ls\r", rawText(outputs))
    }

    @Test
    fun testComposeModeCommitNewlineMidCompositionSendsLineThenEnter() {
        // Route 2: IME submits with a pure-newline commit while the word is
        // still composing (SwiftKey-style).
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("ls", 1)
            ic.commitText("\n", 1)
        }
        advanceMainLooper()
        drainMainLooper()

        assertEquals("ls\r", rawText(outputs))
    }

    @Test
    fun testComposeModeMixedCommitWithTrailingNewlineSendsEnter() {
        // Route 3: IME submits the line as ONE commit, text + newline together.
        // The newline must not be silently dropped.
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("ls", 1)
            ic.commitText("ls\n", 1)
        }
        advanceMainLooper()
        drainMainLooper()

        assertEquals("ls\r", rawText(outputs))
    }

    @Test
    fun testComposeModeRepeatedLinesDoNotConcatenate() {
        // The full agross sequence over route 1: ls⏎ ls⏎ exit⏎ must reach the
        // shell as three executed lines, not "lslsexit".
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (word in listOf("ls", "ls", "exit")) {
                var partial = ""
                for (ch in word) {
                    partial += ch
                    ic.setComposingText(partial, 1)
                }
                ic.finishComposingText()
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
        drainMainLooper()

        assertEquals("ls\rls\rexit\r", rawText(outputs))
    }

    @Test
    fun testComposeModeMixedCommitRepeatedLinesDoNotConcatenate() {
        // The same sequence over route 3 (one-shot "word\n" commits).
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (word in listOf("ls", "ls", "exit")) {
                ic.setComposingText(word, 1)
                ic.commitText("$word\n", 1)
            }
        }
        advanceMainLooper()
        drainMainLooper()

        assertEquals("ls\rls\rexit\r", rawText(outputs))
    }

    @Test
    fun testComposeModeCommitThenEnterKeyStillSingleEnter() {
        // Regression guard: the common Gboard path (commit the word, then a
        // separate Enter key event) must still produce exactly one Enter —
        // the mixed-commit fix must not stack a deferred second Enter.
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("ls", 1)
            ic.commitText("ls", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        advanceMainLooper()
        drainMainLooper()

        assertEquals("ls\r", rawText(outputs))
    }

    // === HyperOS/Android 16 warm-return crash guard ===
    //
    // A focused interop child at removeAllViewsInLayout() time makes ViewGroup
    // re-enter the disposing Compose hierarchy via rootViewRequestFocus()
    // ("Searching for active node in inactive hierarchy"). The view must hold
    // no focus while its window is invisible, and must NOT re-take focus
    // synchronously on window-visible (that is exactly the crash frame) —
    // only after REFOCUS_DELAY_MS.

    private fun attachedView(): ImeInputView {
        val view = makeView()
        val activity = org.robolectric.Robolectric
            .buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(view)
        return view
    }

    @Test
    fun testWindowInvisibleDropsFocus() {
        val view = attachedView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.requestFocus()
            assertTrue(view.isFocused)

            view.dispatchWindowVisibilityChanged(View.GONE)
        }
        // Focus must not bounce back via rootViewRequestFocus (the known
        // clearFocus-refocuses-first-focusable behaviour) even after idling.
        advanceMainLooper(ImeInputView.REFOCUS_DELAY_MS + 32)
        assertFalse(view.isFocused)
    }

    @Test
    fun testWindowVisibleAgainRestoresFocusOnlyAfterDelay() {
        val view = attachedView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("initial requestFocus", view.requestFocus())
            view.dispatchWindowVisibilityChanged(View.GONE)
            assertFalse("focus dropped on GONE", view.isFocused)
            assertFalse("unfocusable while hidden", view.isFocusable)

            view.dispatchWindowVisibilityChanged(View.VISIBLE)
            // Not synchronously: the deferred-disposal flush runs on the
            // first frame after return.
            assertFalse("no synchronous refocus", view.isFocused)
        }
        advanceMainLooper(ImeInputView.REFOCUS_DELAY_MS + 32)
        assertTrue("focusable restored after delay", view.isFocusable)
        assertTrue("focus restored after delay", view.isFocused)
    }

    @Test
    fun testUnfocusedViewDoesNotGrabFocusOnWindowVisible() {
        val view = attachedView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchWindowVisibilityChanged(View.GONE)
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
        advanceMainLooper(ImeInputView.REFOCUS_DELAY_MS + 32)
        assertFalse(view.isFocused)
    }

    // The focus drop above is deliberate, but it must never swallow a
    // show-keyboard request. showIme() gates on requestFocus(), which returns
    // false while the view is unfocusable — and every caller is edge-triggered
    // (a tap, or Terminal's LaunchedEffect(shouldShowIme)), so a swallowed
    // show is never retried: the terminal is left unable to accept input.
    // The race is real on warm return, where Terminal's IME_SHOW_DELAY_MS and
    // REFOCUS_DELAY_MS are both 100ms.

    @Test
    fun testShowImeWhileUnfocusableStillTakesFocus() {
        val view = attachedView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.requestFocus()
            view.dispatchWindowVisibilityChanged(View.GONE)
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
            assertFalse("precondition: still unfocusable pre-delay", view.isFocusable)

            // The user taps the terminal (or the resume effect fires) before
            // the deferred refocus lands.
            view.showIme()

            assertTrue("showIme must restore focusability", view.isFocusable)
            assertTrue("showIme must leave the view focused", view.isFocused)
        }
    }

    @Test
    fun testShowImeWhileWindowHiddenDoesNotRefocus() {
        // The converse: while the window really is hidden, showIme() must NOT
        // re-arm the crash by taking focus back (nothing legitimately asks for
        // the keyboard then, and holding focus is what crashed HyperOS).
        val view = attachedView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.requestFocus()
            view.dispatchWindowVisibilityChanged(View.GONE)

            view.showIme()

            assertFalse("must stay unfocused while the window is hidden", view.isFocused)
        }
    }

    // === Compose interop teardown guard (the exact form of the above) ===
    //
    // The window-visibility drop is timing-based: it re-takes focus
    // REFOCUS_DELAY_MS after the window returns, on the assumption that
    // Compose's deferred disposal flush has already run. A Xiaomi Mi 17
    // (HyperOS/Android 16) runs that flush later, so the view is focused again
    // when AndroidViewHolder.onDeactivate() finally calls
    // removeAllViewsInLayout(). onInteropReset() is wired into the AndroidView
    // reset block, which Compose invokes on the same stack frame immediately
    // before that removal, so it does not depend on timing at all.
    //
    // The observable signature of the crashing branch is that removing a
    // FOCUSED child sends ViewGroup through rootViewRequestFocus() — a focus
    // request from the root of the tree, which on the device re-enters the
    // half-disposed AndroidComposeView. Here there is no Compose hierarchy to
    // re-enter, so a plain focusable sibling acts as the canary: it can only
    // gain focus if that root-level request ran.

    private class InteropTeardownFixture(context: Context, val view: ImeInputView) {
        val canary = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val holder = android.widget.FrameLayout(context)
        val root = android.widget.LinearLayout(context).apply {
            addView(canary, android.view.ViewGroup.LayoutParams(SIZE, SIZE))
            addView(holder, android.view.ViewGroup.LayoutParams(SIZE, SIZE))
        }

        init {
            holder.addView(view, android.view.ViewGroup.LayoutParams(SIZE, SIZE))
            val activity = org.robolectric.Robolectric
                .buildActivity(android.app.Activity::class.java).setup().get()
            activity.setContentView(root)
            // View.canTakeFocus() refuses a zero-sized view once layout is
            // valid (sCanFocusZeroSized is false from targetSdk P). Robolectric
            // lays the content view out, so without a real size every
            // requestFocus() below silently returns false.
            listOf(root, canary, holder, view).forEach { it.layout(0, 0, SIZE, SIZE) }
        }

        private companion object {
            const val SIZE = 16
        }
    }

    private fun interopFixture(): InteropTeardownFixture =
        InteropTeardownFixture(context, makeView())

    @Test
    fun testRemovingFocusedInteropChildRefocusesFromRoot() {
        // Pins the branch the guard exists to avoid. If this ever stops
        // holding, the framework changed and the guard's rationale needs
        // re-checking — it does not mean the guard is unnecessary.
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("precondition: interop view focused", f.view.requestFocus())

            f.holder.removeAllViewsInLayout()

            assertTrue(
                "removal of a focused child must re-request focus from the root",
                f.canary.isFocused,
            )
        }
    }

    @Test
    fun testInteropResetPreventsRootRefocusOnRemoval() {
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("precondition: interop view focused", f.view.requestFocus())

            // Exactly what Compose does: reset block, then the removal.
            f.view.onInteropReset()

            // The ancestors' record of the focused child is what
            // removeAllViewsInLayout() reads; the view's own focus is kept on
            // purpose, so the chain can be restored when it is re-added.
            assertNull("holder has no focused child", f.holder.focusedChild)
            assertTrue("view keeps its own focus", f.view.isFocused)

            f.holder.removeAllViewsInLayout()

            assertFalse(
                "no root-level focus request may run during teardown",
                f.canary.isFocused,
            )
        }
    }

    @Test
    fun testInteropResetKeepsTheViewFocusableAndFocused() {
        // The teardown must NOT drop FOCUSABLE and must NOT clearFocus():
        // both give focus up through the public clearFocus(), which calls
        // rootViewRequestFocus() — the crash. Only the ancestors' record of
        // the focused child is cleared; the view's own focus survives, which
        // is what lets addViewInner() restore the chain when Compose puts it
        // back.
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()

            f.view.onInteropReset()

            assertTrue("still focusable", f.view.isFocusable)
            assertTrue("still holds focus itself", f.view.isFocused)
        }
    }

    @Test
    fun testInteropTeardownRoundTripRestoresTheFocusChain() {
        // The whole AndroidViewHolder.onDeactivate() / onReuse() cycle:
        // reset, removeAllViewsInLayout(), then addView() when the page comes
        // back. Focus has to come back with it, without a timer.
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()

            f.view.onInteropReset()
            f.holder.removeAllViewsInLayout()

            assertFalse("no root-level focus request during teardown", f.canary.isFocused)

            f.holder.addView(f.view, android.view.ViewGroup.LayoutParams(16, 16))
            f.view.layout(0, 0, 16, 16)

            assertSame("focus chain restored on re-add", f.view, f.holder.focusedChild)
            assertTrue("view focused again", f.view.isFocused)
        }
    }

    @Test
    fun testInteropUpdateRepairsTheChainAfterAResetWithoutDetach() {
        // onReuse() calls the reset block WITHOUT removing the view when it is
        // still parented, so no detach/attach pair follows and nothing else
        // would ever reconnect the chain.
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()
            f.view.onInteropReset()
            assertNull("precondition: chain broken", f.holder.focusedChild)

            f.view.onInteropUpdate()

            assertSame("chain repaired", f.view, f.holder.focusedChild)
            assertFalse("repair must not hand focus to a sibling", f.canary.isFocused)
        }
    }

    /**
     * The failure the self-heal exists for: a teardown that neither detached
     * the view nor was followed by an update leaves the view believing it holds
     * focus while its parent does not record it. Nothing re-requests focus (the
     * view thinks it has it) and the IME's input reaches nobody — reported as
     * "keys stop going to the terminal after a reconnect".
     */
    @Test
    fun testWindowVisibleRepairsABrokenFocusChain() {
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()
            f.view.onInteropReset()
            assertNull("precondition: chain broken", f.holder.focusedChild)
            assertTrue("precondition: view still believes it is focused", f.view.isFocused)

            // No detach, no update — just the window coming back.
            f.view.dispatchWindowVisibilityChanged(View.VISIBLE)

            assertSame("chain repaired on window-visible", f.view, f.holder.focusedChild)
        }
    }

    @Test
    fun testShowImeRepairsABrokenFocusChain() {
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()
            f.view.onInteropReset()
            assertNull("precondition: chain broken", f.holder.focusedChild)

            f.view.showIme()

            assertSame("chain repaired by showIme", f.view, f.holder.focusedChild)
        }
    }

    /** The repair must not invent focus for a view that never had it. */
    @Test
    fun testRepairDoesNotStealFocusForAnUnfocusedView() {
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.canary.requestFocus()

            f.view.dispatchWindowVisibilityChanged(View.VISIBLE)

            assertTrue("unrelated focus left alone", f.canary.isFocused)
            assertFalse(f.view.isFocused)
        }
    }

    @Test
    fun testInteropUpdateIsANoOpOutsideATeardown() {
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.canary.requestFocus()

            f.view.onInteropUpdate()

            assertTrue("must not steal focus on an ordinary update", f.canary.isFocused)
            assertFalse(f.view.isFocused)
        }
    }

    @Test
    fun testInteropTeardownDetachDoesNotDropFocusable() {
        // The detach inside removeAllViewsInLayout() dispatches GONE, which
        // normally drops FOCUSABLE — and that drop calls
        // rootViewRequestFocus(). During a teardown it must be suppressed.
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()

            f.view.onInteropReset()
            f.holder.removeAllViewsInLayout()

            assertFalse("no root-level focus request on the GONE dispatch", f.canary.isFocused)
            assertTrue("FOCUSABLE untouched during teardown", f.view.isFocusable)
        }
    }

    @Test
    fun testWindowHiddenOutsideATeardownStillDropsFocusable() {
        // The ordinary background path is unchanged: no teardown flag, so the
        // window-invisible drop still applies.
        val f = interopFixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            f.view.requestFocus()

            f.view.dispatchWindowVisibilityChanged(View.GONE)

            assertFalse("unfocusable while hidden", f.view.isFocusable)
            assertFalse("unfocused while hidden", f.view.isFocused)
        }
    }

    // ---------- held-DEL takeover bounds ----------

    /** DEL bytes delivered to the terminal so far. */
    private fun delCount(outputs: List<ByteArray>): Int =
        outputs.flatMap { it.toList() }.count { it == 0x7F.toByte() }

    /**
     * A capture whose view is attached to a real window and focusable.
     * The takeover reposts itself with [View.postDelayed], which on a view
     * with no attached window goes to the run queue and never fires — so an
     * unattached fixture silently proves nothing about it.
     */
    private class AttachedCapture(context: Context) {
        val outputs = mutableListOf<ByteArray>()
        val view: ImeInputView
        val sibling: View

        init {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                onKeyboardInput = { data -> outputs.add(data.copyOf()) },
            )
            val handler = KeyboardHandler(emulator)
            view = ImeInputView(context, handler)
            sibling = View(context).apply { isFocusableInTouchMode = true }
            val root = android.widget.LinearLayout(context).apply {
                addView(view, android.view.ViewGroup.LayoutParams(SIZE, SIZE))
                addView(sibling, android.view.ViewGroup.LayoutParams(SIZE, SIZE))
            }
            val activity = org.robolectric.Robolectric
                .buildActivity(android.app.Activity::class.java).setup().get()
            activity.setContentView(root)
            // A zero-sized view cannot take focus once layout is valid.
            listOf(root, view, sibling).forEach { it.layout(0, 0, SIZE, SIZE) }
            view.onCreateInputConnection(EditorInfo())
        }

        private companion object {
            const val SIZE = 16
        }
    }

    private fun attachedCapture(): AttachedCapture {
        var c: AttachedCapture? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            c = AttachedCapture(context)
        }
        return c!!
    }

    /** Start a hold and let the takeover get going. Returns the DEL count. */
    private fun startHeldDelTakeover(c: AttachedCapture): Int {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // A bare down with no up. deleteSurroundingText is excluded from
            // starting a takeover; an IME that does this another way is not.
            c.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }
        advanceMainLooper(1_000)
        val running = delCount(c.outputs)
        assertTrue("precondition: the takeover is repeating", running > 1)
        return running
    }

    /**
     * The held-DEL takeover keeps sending backspaces after the IME goes quiet,
     * which is what a Gboard hold needs. Nothing it sends is reversible, so it
     * must not ride a missing ACTION_UP forever.
     */
    @Test
    fun testHeldDelTakeoverStopsAtItsBound() {
        val c = attachedCapture()
        startHeldDelTakeover(c)

        advanceMainLooper(ImeInputView.HELD_DEL_MAX_TAKEOVER_MS)
        val settled = delCount(c.outputs)
        advanceMainLooper(3_000)

        assertEquals(
            "held-DEL takeover kept deleting past its bound",
            settled,
            delCount(c.outputs),
        )
    }

    /** The ordinary end: the key comes up and the takeover stops with it. */
    @Test
    fun testHeldDelTakeoverStopsOnKeyUp() {
        val c = attachedCapture()
        startHeldDelTakeover(c)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            c.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        val atKeyUp = delCount(c.outputs)
        advanceMainLooper(2_000)

        assertEquals("takeover continued after ACTION_UP", atKeyUp, delCount(c.outputs))
    }

    /** Focus moving away means no ACTION_UP is coming to this view. */
    @Test
    fun testHeldDelTakeoverStopsWhenFocusIsLost() {
        val c = attachedCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("precondition: view focused", c.view.requestFocus())
        }
        startHeldDelTakeover(c)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { c.view.clearFocus() }
        val atFocusLoss = delCount(c.outputs)
        advanceMainLooper(2_000)

        assertEquals(
            "takeover continued after the view lost focus",
            atFocusLoss,
            delCount(c.outputs),
        )
    }

    // === #614: Secure mode must set VISIBLE_PASSWORD on the Samsung-gated path too ===
    //
    // The non-gated Secure path already pairs VISIBLE_PASSWORD with
    // NO_SUGGESTIONS, but the Samsung-gated path (#110) set only
    // AUTO_CORRECT | NO_SUGGESTIONS. Samsung Honeyboard honours
    // VISIBLE_PASSWORD as the "don't rewrite / don't suggest" signal and kept
    // showing the suggestion strip under Secure (imorourke, A15, 5.9.30.97).
    // The gate must keep AUTO_CORRECT (its commitText dispatch requirement)
    // AND add VISIBLE_PASSWORD.

    private fun ImeInputView.secureInputType(): Int {
        val out = EditorInfo()
        onCreateInputConnection(out)
        return out.inputType
    }

    @Test
    fun testSecureModeSetsVisiblePasswordOnGatedIme() {
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.samsung.android.honeyboard/.service.HoneyBoardService",
        )
        val inputType = makeView().secureInputType()

        assertTrue(
            "gated Secure must keep AUTO_CORRECT (Samsung commitText gate, #110)",
            inputType and EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT != 0,
        )
        assertTrue(
            "gated Secure must set VISIBLE_PASSWORD — the flag that clears Samsung's suggestion bar (#614)",
            inputType and EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0,
        )
        assertTrue(
            "gated Secure must keep NO_SUGGESTIONS",
            inputType and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0,
        )
    }

    @Test
    fun testSecureModeSetsVisiblePasswordOnNonGatedIme() {
        // Gboard is not in the gated package set → gate returns false → non-gated path.
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            "com.google.android.inputmethod.latin/.LatinIME",
        )
        val inputType = makeView().secureInputType()

        assertTrue(
            "non-gated Secure must set VISIBLE_PASSWORD",
            inputType and EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0,
        )
        assertFalse(
            "non-gated Secure must NOT set AUTO_CORRECT (it rewrites input on Gboard, #115)",
            inputType and EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT != 0,
        )
    }
}
