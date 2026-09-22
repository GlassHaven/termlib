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
import android.text.Editable
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager

/**
 * Records [InputMethodManager.updateExtractedText] calls; the default shadow leaves that
 * method to the framework implementation, which needs a real binder service.
 */
@Implements(InputMethodManager::class)
class RecordingInputMethodManagerShadow : ShadowInputMethodManager() {
    data class Push(val view: View, val token: Int, val text: String)

    companion object {
        val extractedTextPushes = mutableListOf<Push>()

        fun reset() = extractedTextPushes.clear()
    }

    @Implementation
    fun updateExtractedText(view: View, token: Int, text: ExtractedText) {
        extractedTextPushes.add(Push(view, token, text.text?.toString() ?: ""))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(shadows = [RecordingInputMethodManagerShadow::class])
class ImeExtractedTextMonitorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyboardHandler: KeyboardHandler
    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    @Before
    fun setup() {
        RecordingInputMethodManagerShadow.reset()
        val terminalEmulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        keyboardHandler = KeyboardHandler(terminalEmulator)
    }

    private fun makeView(): ImeInputView = ImeInputView(context, keyboardHandler, imm, onUpdateSelection = { _, _, _, _, _ -> })

    private fun ImeInputView.ic(composeMode: Boolean = false): BaseInputConnection {
        isComposeModeActive = composeMode
        return onCreateInputConnection(EditorInfo()) as BaseInputConnection
    }

    @Test
    fun testMonitoredEditorReceivesPushesOnEveryEdit() {
        val view = makeView()
        val ic = view.ic(composeMode = true)

        val request = ExtractedTextRequest().apply { token = 42 }
        ic.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        assertTrue(RecordingInputMethodManagerShadow.extractedTextPushes.isEmpty())

        ic.setComposingText("gi", 1)
        assertEquals(1, RecordingInputMethodManagerShadow.extractedTextPushes.size)
        with(RecordingInputMethodManagerShadow.extractedTextPushes[0]) {
            assertEquals(view, this.view)
            assertEquals(42, token)
            assertEquals("gi", text)
        }

        ic.commitText("git status", 1)
        assertEquals(2, RecordingInputMethodManagerShadow.extractedTextPushes.size)
        assertEquals("git status", RecordingInputMethodManagerShadow.extractedTextPushes[1].text)
    }

    @Test
    fun testMonitorTokenTracksLatestRequest() {
        val view = makeView()
        val ic = view.ic(composeMode = true)

        ic.getExtractedText(ExtractedTextRequest().apply { token = 7 }, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        ic.setComposingText("a", 1)
        assertEquals(7, RecordingInputMethodManagerShadow.extractedTextPushes[0].token)

        ic.getExtractedText(ExtractedTextRequest().apply { token = 9 }, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        ic.setComposingText("ab", 1)
        assertEquals(9, RecordingInputMethodManagerShadow.extractedTextPushes[1].token)
        assertEquals("ab", RecordingInputMethodManagerShadow.extractedTextPushes[1].text)
    }

    @Test
    fun testUnmonitoredPollingGetsFreshSnapshotsWithoutPushes() {
        val ic = makeView().ic(composeMode = true)

        ic.getExtractedText(ExtractedTextRequest(), 0)
        ic.setComposingText("gi", 1)
        assertTrue(RecordingInputMethodManagerShadow.extractedTextPushes.isEmpty())

        // A polling IME reads the current snapshot itself.
        val snapshot = ic.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals("gi", snapshot.text.toString())
        assertTrue(RecordingInputMethodManagerShadow.extractedTextPushes.isEmpty())
    }

    @Test
    fun testFreshConnectionStartsWithNoMonitor() {
        val view = makeView()
        val first = view.ic(composeMode = true)
        first.getExtractedText(ExtractedTextRequest().apply { token = 5 }, InputConnection.GET_EXTRACTED_TEXT_MONITOR)

        // An IME restart creates a new connection; the old token must not leak into it.
        val second = view.ic(composeMode = true)
        second.setComposingText("x", 1)
        assertTrue(RecordingInputMethodManagerShadow.extractedTextPushes.isEmpty())

        // ...while the connection that owns the token still pushes on its own edits.
        first.setComposingText("xy", 1)
        assertEquals(1, RecordingInputMethodManagerShadow.extractedTextPushes.size)
        assertEquals(5, RecordingInputMethodManagerShadow.extractedTextPushes[0].token)
        assertEquals("xy", RecordingInputMethodManagerShadow.extractedTextPushes[0].text)
    }

    @Test
    fun testExtractedTextSnapshotShape() {
        val ic = makeView().ic(composeMode = true)

        ic.commitText("ls", 1)
        val snapshot = ic.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals("ls", snapshot.text.toString())
        assertEquals(2, snapshot.selectionStart)
        assertEquals(2, snapshot.selectionEnd)
        assertEquals(0, snapshot.startOffset)
        assertEquals(-1, snapshot.partialStartOffset)
        assertEquals(-1, snapshot.partialEndOffset)
        assertEquals(ExtractedText.FLAG_SINGLE_LINE, snapshot.flags)

        // Any newline in the editor turns off the single-line hint.
        ic.commitText("\ntail", 1)
        val multiline = ic.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals("ls\ntail", multiline.text.toString())
        assertEquals(0, multiline.flags)

        // Styled requests carry the spanned text rather than a flat copy.
        val styled = ic.getExtractedText(ExtractedTextRequest(), InputConnection.GET_TEXT_WITH_STYLES)!!
        assertTrue(styled.text is Editable)
        assertEquals("ls\ntail", styled.text.toString())
    }

    @Test
    fun testTerminalModeConnectionHasNoExtractedText() {
        val ic = makeView().ic(composeMode = false)

        assertNull(ic.getExtractedText(ExtractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR))
        assertTrue(RecordingInputMethodManagerShadow.extractedTextPushes.isEmpty())
    }
}
