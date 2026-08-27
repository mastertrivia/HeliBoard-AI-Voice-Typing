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
import org.junit.Assert.assertArrayEquals
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
    private var sessionStarted = false
    private var sessionEnded = false

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.getSharedPreferences("desh_translation", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        editor = FakeInputConnection()
        val host = object : DeshTranslationHost {
            override fun getInputConnection(): InputConnection = editor
            override fun inputViewWindowToken(): IBinder? = null
            override fun currentLanguageCode(): String = "hi"
            override fun onTranslationVisibilityChanged(visible: Boolean) {}
            override fun onTranslationSessionStart() { sessionStarted = true }
            override fun onTranslationSessionEnd() { sessionEnded = true }
            override fun onTranslationSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {}
            override fun onTranslationTextChanged() {}
            override fun onTranslationSourceLanguageChanged(sourceCode: String) {}
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

    @Test
    fun `mandatory Hindi to English request has exact reference parameters`() {
        val url = engine.buildUrl("नमस्ते दुनिया", "hi", "en")
        assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/t?"))
        assertTrue(url.contains("client=gtx"))
        assertTrue(url.contains("sl=hi"))
        assertTrue(url.contains("tl=en"))
        assertTrue(url.contains("q=%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87+%E0%A4%A6%E0%A5%81%E0%A4%A8%E0%A4%BF%E0%A4%AF%E0%A4%BE"))
    }

    @Test
    fun `mandatory English to Hindi request has exact reference parameters`() {
        val url = engine.buildUrl("hello world", "en", "hi")
        assertTrue(url.startsWith("https://translate.googleapis.com/translate_a/t?"))
        assertTrue(url.contains("client=gtx"))
        assertTrue(url.contains("sl=en"))
        assertTrue(url.contains("tl=hi"))
        assertTrue(url.contains("q=hello+world"))
    }

    @Test
    fun `show is idempotent while translation panel is already open`() {
        sessionStarted = false
        engine.show()
        assertTrue(sessionStarted)
        sessionStarted = false
        engine.show()
        assertFalse(sessionStarted)
        assertEquals(View.VISIBLE, view.visibility)
    }

    // ------------------------------------------------------------------
    // Input session — Desh bg/g.p0 (box InputConnection + host state swap)
    // ------------------------------------------------------------------

    @Test
    fun `show starts the IME input session and hide ends it`() {
        sessionStarted = false
        sessionEnded = false
        engine.show()
        assertTrue(sessionStarted)
        assertFalse(sessionEnded)
        engine.hide(true)
        assertTrue(sessionEnded)
    }

    @Test
    fun `box is the Desh KeyboardEditText and never enables suggestions`() {
        val box = view.editText
        assertTrue(box is DeshKeyboardEditText)
        assertFalse(box.isSuggestionsEnabled)
    }

    @Test
    fun `selection change in the box is reported back to the IME`() {
        var reported = false
        var reportedOld = intArrayOf(-1, -1)
        var reportedNew = intArrayOf(-1, -1)
        view.setSelectionCallback(object : DeshKeyboardEditTextCallback {
            override fun onSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
                reported = true
                reportedOld = intArrayOf(oldStart, oldEnd)
                reportedNew = intArrayOf(newStart, newEnd)
            }
            override fun onTextSet(selectionStart: Int, selectionEnd: Int) {}
        })
        view.editText.setText("hello")
        view.editText.setSelection(2)
        assertTrue(reported)
        assertArrayEquals(intArrayOf(0, 0), reportedOld)
        assertArrayEquals(intArrayOf(2, 2), reportedNew)
    }

    @Test
    fun `programmatic setText reports the composer-flush callback`() {
        var reported = false
        view.setSelectionCallback(object : DeshKeyboardEditTextCallback {
            override fun onSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {}
            override fun onTextSet(selectionStart: Int, selectionEnd: Int) { reported = true }
        })
        view.editText.setText("translated")
        assertTrue(reported)
    }

    @Test
    fun `MRU removes duplicate moves selection to front and caps at four`() {
        val updated = DeshTranslationEngine.updateMru(
            listOf("de", "fr", "es", "it", "fr"), "fr")
        assertEquals(listOf("fr", "de", "es", "it"), updated)
    }

    @Test
    fun `legacy pipe and reference JSON recent values parse robustly`() {
        assertEquals(listOf("fr", "de", "es"),
            DeshTranslationEngine.parseUsedLanguages("fr|de||fr|es"))
        assertEquals(listOf("it", "fr"),
            DeshTranslationEngine.parseUsedLanguages("[\"it\",\"fr\",\"it\"]"))
        assertEquals(emptyList<String>(), DeshTranslationEngine.parseUsedLanguages("not-json"))
    }

    @Test
    fun `picker pins Hindi and English then excludes them and recents from remaining`() {
        val sections = engine.pickerSections(listOf("fr", "hi", "de", "en", "fr"))
        assertEquals(listOf("hi", "en"), sections.pinned.map { it.code })
        assertEquals(listOf("fr", "de"), sections.recent.map { it.code })
        val remaining = sections.remaining.map { it.code }
        assertFalse("hi" in remaining)
        assertFalse("en" in remaining)
        assertFalse("fr" in remaining)
        assertFalse("de" in remaining)
        assertEquals(remaining.size, remaining.distinct().size)
    }

    @Test
    fun `keyboard switch is requested when the source language changes`() {
        var requestedCode: String? = null
        val host = object : DeshTranslationHost {
            override fun getInputConnection(): InputConnection = editor
            override fun inputViewWindowToken(): IBinder? = null
            override fun currentLanguageCode(): String = "hi"
            override fun onTranslationVisibilityChanged(visible: Boolean) {}
            override fun onTranslationSessionStart() {}
            override fun onTranslationSessionEnd() {}
            override fun onTranslationSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {}
            override fun onTranslationTextChanged() {}
            override fun onTranslationSourceLanguageChanged(sourceCode: String) { requestedCode = sourceCode }
        }
        val engineWithHost = DeshTranslationEngine(
            ApplicationProvider.getApplicationContext<App>(), host)
        val v = DeshTranslationView(ApplicationProvider.getApplicationContext<App>())
        v.engine = engineWithHost
        engineWithHost.view = v
        engineWithHost.show()
        // pick a source language different from the default
        val french = DeshTranslationLanguage.TABLE.firstOrNull { it.code == "fr" }
            ?: DeshTranslationLanguage.ENGLISH
        engineWithHost.onLanguageSelected(french, isSource = true)
        assertEquals("fr", requestedCode)
    }

    @Test
    fun `target selection does not request keyboard switch`() {
        var requestedCode: String? = null
        val host = object : DeshTranslationHost {
            override fun getInputConnection(): InputConnection = editor
            override fun inputViewWindowToken(): IBinder? = null
            override fun currentLanguageCode(): String = "hi"
            override fun onTranslationVisibilityChanged(visible: Boolean) {}
            override fun onTranslationSessionStart() {}
            override fun onTranslationSessionEnd() {}
            override fun onTranslationSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {}
            override fun onTranslationTextChanged() {}
            override fun onTranslationSourceLanguageChanged(sourceCode: String) { requestedCode = sourceCode }
        }
        val context = ApplicationProvider.getApplicationContext<App>()
        val engineWithHost = DeshTranslationEngine(context, host)
        val v = DeshTranslationView(context)
        v.engine = engineWithHost
        engineWithHost.view = v
        engineWithHost.show()
        engineWithHost.onLanguageSelected(
            DeshTranslationLanguage.TABLE.first { it.code == "fr" }, isSource = false)
        assertEquals(null, requestedCode)
    }

    @Test
    fun `fast scroller scrolls the language list on drag`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val recycler = androidx.recyclerview.widget.RecyclerView(context)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        val languages = DeshTranslationLanguage.all().take(20)
        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = languages.size
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup, viewType: Int
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val tv = android.widget.TextView(context)
                tv.layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 100)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int
            ) { (holder.itemView as android.widget.TextView).text = languages[position].name }
        }
        // measure + layout the list so scrolling actually moves positions
        recycler.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 1000, 1000)
        val scroller = DeshFastScrollerView(context)
        scroller.targetRecyclerView = recycler
        // give the scroller a real size so the drag math works
        scroller.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(60, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY))
        scroller.layout(0, 0, 60, 1000)
        // drag near the bottom -> list scrolls to a later position
        scroller.dispatchTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 30f, 990f, 0))
        scroller.dispatchTouchEvent(
            android.view.MotionEvent.obtain(0, 1, android.view.MotionEvent.ACTION_UP, 30f, 990f, 0))
        val lm = recycler.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        // scrollToPositionWithOffset schedules a layout pass; flush it explicitly in Robolectric
        recycler.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY))
        recycler.layout(0, 0, 1000, 1000)
        val firstVisible = lm.findFirstVisibleItemPosition()
        assertTrue("list should have scrolled to a later position (was $firstVisible)", firstVisible > 0)
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
