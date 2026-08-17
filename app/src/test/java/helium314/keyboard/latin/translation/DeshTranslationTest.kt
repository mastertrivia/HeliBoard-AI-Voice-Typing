// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translation

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification tests for the Desh translation block (engine + view) hosted in HeliBoard.
 * They execute the REAL engine/commit code paths against a deterministic fake
 * [InputConnection] and assert exactly what lands in the target editor.
 */
@RunWith(RobolectricTestRunner::class)
// sdk 34 (not 36): Robolectric requires Java 21 for API 36; this project compiles with JDK 17.
@Config(sdk = [34], shadows = [ShadowInputMethodManager2::class])
class DeshTranslationTest {

    private lateinit var editor: FakeInputConnection
    private lateinit var engine: DeshTranslationEngine
    private lateinit var view: DeshTranslationView

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<App>()
        editor = FakeInputConnection()
        val host = object : DeshTranslationHost {
            override fun getInputConnection(): InputConnection = editor
            override fun inputViewWindowToken(): IBinder? = null
            override fun currentLanguageCode(): String = "hi"
            override fun onTranslationVisibilityChanged(visible: Boolean) {}
        }
        engine = DeshTranslationEngine(context, host)
        view = DeshTranslationView(context)
        view.engine = engine
        engine.view = view
    }

    // ------------------------------------------------------------------
    // Diff-commit (faithful port of Desh jg/e.s) — the anti-duplication mechanism
    // ------------------------------------------------------------------

    @Test
    fun `commit appends when new translation extends the old one`() {
        engine.addSpaceToTranslation = false
        engine.commitTranslatedText("hello")
        assertEquals("hello", editor.text())
        engine.commitTranslatedText("hello world")
        assertEquals("hello world", editor.text())
    }

    @Test
    fun `commit replaces the old translation when text changed completely`() {
        engine.addSpaceToTranslation = false
        engine.commitTranslatedText("hello")
        engine.commitTranslatedText("bonjour")
        assertEquals("bonjour", editor.text())
    }

    @Test
    fun `commit with leading space when editor already has text`() {
        editor.setText("abc")
        engine.addSpaceToTranslation = true
        engine.commitTranslatedText("hello")
        assertEquals("abc hello", editor.text())
        engine.commitTranslatedText("hello world")
        assertEquals("abc hello world", editor.text())
    }

    @Test
    fun `clearing the box removes the previously committed translation`() {
        editor.setText("abc")
        engine.addSpaceToTranslation = true
        engine.commitTranslatedText("hello")
        assertEquals("abc hello", editor.text())
        engine.commitTranslatedText("")
        assertEquals("abc", editor.text())
    }

    @Test
    fun `re-committing identical text does not duplicate it`() {
        engine.addSpaceToTranslation = false
        engine.commitTranslatedText("hello")
        engine.commitTranslatedText("hello")
        assertEquals("hello", editor.text())
    }

    @Test
    fun `case-insensitive common prefix is kept`() {
        engine.addSpaceToTranslation = false
        engine.commitTranslatedText("Hello")
        engine.commitTranslatedText("hello world")
        // Desh compares the prefix case-insensitively, so only " world" is committed
        assertEquals("Hello world", editor.text())
    }

    @Test
    fun `surrogate pair boundary is not split`() {
        engine.addSpaceToTranslation = false
        engine.commitTranslatedText("smile \uD83D\uDE00")
        engine.commitTranslatedText("smile \uD83D\uDE03 more")
        assertEquals("smile \uD83D\uDE03 more", editor.text())
    }

    // ------------------------------------------------------------------
    // Key routing into the box — the "box not typing" fix
    // ------------------------------------------------------------------

    private fun openPanelWithoutWatcher() {
        view.visibility = View.VISIBLE
    }

    @Test
    fun `letter keys land in the box while the panel is open`() {
        openPanelWithoutWatcher()
        assertTrue(view.handleKeyCode('h'.code))
        assertTrue(view.handleKeyCode('i'.code))
        assertEquals("hi", view.typedText)
    }

    @Test
    fun `space enters the box while the panel is open`() {
        openPanelWithoutWatcher()
        view.handleKeyCode('h'.code)
        view.handleKeyCode(32)
        view.handleKeyCode('i'.code)
        assertEquals("h i", view.typedText)
    }

    @Test
    fun `backspace edits the box while the panel is open`() {
        openPanelWithoutWatcher()
        view.handleKeyCode('h'.code)
        view.handleKeyCode('i'.code)
        assertTrue(view.handleKeyCode(-7))
        assertEquals("h", view.typedText)
    }

    @Test
    fun `keys are ignored when the panel is closed`() {
        view.visibility = View.GONE
        assertFalse(view.handleKeyCode('h'.code))
        assertEquals("", view.typedText)
    }

    // ------------------------------------------------------------------
    // Show / hide / swap
    // ------------------------------------------------------------------

    @Test
    fun `show makes the panel visible and focuses the box`() {
        engine.show()
        assertEquals(View.VISIBLE, view.visibility)
        assertTrue(view.editText.isFocused)
    }

    @Test
    fun `close hides the panel`() {
        engine.show()
        engine.hide(true)
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun `addSpaceToTranslation is false when a space precedes the cursor`() {
        editor.setText("hello ")
        engine.show()
        assertFalse(engine.addSpaceToTranslation)
    }

    @Test
    fun `addSpaceToTranslation is true when a letter precedes the cursor`() {
        editor.setText("hello")
        engine.show()
        assertTrue(engine.addSpaceToTranslation)
    }

    @Test
    fun `swapLanguages swaps source and target without collision`() {
        val srcBefore = engine.source
        val tgtBefore = engine.target
        engine.swapLanguages()
        assertEquals(srcBefore.code, engine.target.code)
        assertEquals(tgtBefore.code, engine.source.code)
        // swapping twice restores the original state
        engine.swapLanguages()
        assertEquals(srcBefore.code, engine.source.code)
        assertEquals(tgtBefore.code, engine.target.code)
    }

    @Test
    fun `source and target never hold the same language after swap`() {
        engine.swapLanguages()
        assertTrue(engine.source.code != engine.target.code)
    }
}

/** Deterministic in-memory [InputConnection] — records exactly what the engine commits. */
private class FakeInputConnection(
    private val buffer: StringBuilder = StringBuilder(),
) : InputConnection {

    var cursor: Int = 0
        private set

    fun text(): String = buffer.toString()

    fun setText(value: String) {
        buffer.setLength(0)
        buffer.append(value)
        cursor = buffer.length
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null) return true
        buffer.insert(cursor, text)
        cursor += text.length
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val start = (cursor - beforeLength).coerceIn(0, buffer.length)
        val end = (cursor + afterLength).coerceIn(0, buffer.length)
        if (start < end) buffer.delete(start, end)
        cursor = start
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val start = (cursor - n).coerceAtLeast(0)
        return buffer.substring(start, cursor)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
    override fun setComposingRegion(start: Int, end: Int): Boolean = true
    override fun finishComposingText(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent?): Boolean = true
    override fun clearMetaKeyStates(states: Int): Boolean = true
    override fun performContextMenuAction(id: Int): Boolean = true
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
    override fun getHandler(): Handler? = null
    override fun closeConnection() {}
    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true
    override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: Bundle?): Boolean = true
    override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = true
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
    override fun performEditorAction(editorAction: Int): Boolean = true
    override fun setSelection(start: Int, end: Int): Boolean {
        cursor = end.coerceIn(0, buffer.length)
        return true
    }
}
