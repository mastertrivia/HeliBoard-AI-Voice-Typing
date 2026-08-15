package helium314.keyboard.latin.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Desh Translate — ported from Desh Keyboard v17.4.9
 * (com/deshkeyboard/translation/{TranslationView,a,b,c,f}.smali).
 *
 * Structure kept faithful to the Desh implementation:
 *  - [TranslationState] sealed hierarchy mirrors `f` (Idle / Loading /
 *    Translated(text) / Error(message) / NoInternet / TooLong).
 *  - The engine hits `https://translate.googleapis.com/translate_a/t` with
 *    `client=gtx`, `sl`, `tl`, `q` — the same endpoint and parameters as the
 *    Desh controller (`a.smali`), including the 16000-character limit
 *    ("Try with shorter text") and the no-internet state.
 *  - The language table is Desh's own 248-entry list (`c.smali`).
 *  - The panel layout mirrors Desh's `layout_translation_view.xml`
 *    (close button top-right, from/switch/to language row, rounded input box).
 *
 * The only boundary adaptation is HeliBoard's [Host] (Desh passed its IME
 * `bg/g` directly; HeliBoard exposes the InputConnection + current language
 * through this interface instead).
 */
class DeshTranslationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** HeliBoard boundary — the Desh code talked to its own IME for these. */
    interface Host {
        fun getTargetInputConnection(): InputConnection?
        fun currentLanguage(): String
        fun finishTranslationComposition()
    }

    // ---- Translation state machine (Desh `f.smali`) ----
    sealed class TranslationState {
        object Idle : TranslationState()
        object Loading : TranslationState()
        object NoInternet : TranslationState()
        object TooLong : TranslationState()
        data class Translated(val text: String) : TranslationState()
        data class Error(val message: String) : TranslationState()
    }

    companion object {
        private const val PREFS = "desh_translation"
        private const val PREF_SOURCE = "last_selected_source_language"
        private const val PREF_TARGET = "last_selected_target_language"
        private const val DEFAULT_SOURCE = "hi"
        private const val DEFAULT_TARGET = "en"
        private const val MAX_TEXT_LENGTH = 0x3e80 // 16000, Desh's limit in a.smali
        private const val DEBOUNCE_MS = 180L
        private const val ENDPOINT = "https://translate.googleapis.com/translate_a/t"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var host: Host? = null
    private var translateJob: Job? = null
    private var sourceLanguage = DEFAULT_SOURCE
    private var targetLanguage = DEFAULT_TARGET

    private lateinit var closeButton: ImageButton
    private lateinit var sourceButton: TextView
    private lateinit var targetButton: TextView
    private lateinit var switchButton: ImageButton
    private lateinit var sourceEdit: EditText
    private lateinit var errorRow: android.view.View
    private lateinit var errorNoInternet: android.view.View
    private lateinit var errorIcon: android.view.View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView
    private lateinit var speakButton: android.widget.Button

    init {
        // Inflate the panel as children of this view (FrameLayout). The layout's
        // root is a FrameLayout too, so inflate attaches its contents directly
        // rather than wrapping, matching the Desh TranslationView composition.
        val inflater = android.view.LayoutInflater.from(context)
        inflater.inflate(R.layout.translation_view_panel, this, true)
        closeButton = findViewById(R.id.closeTranslate)
        sourceButton = findViewById(R.id.tvTranslateFrom)
        targetButton = findViewById(R.id.tvTranslateTo)
        switchButton = findViewById(R.id.ibLanguageSwitchSwitch)
        sourceEdit = findViewById(R.id.etTranslate)
        errorRow = findViewById(R.id.cl_error)
        errorNoInternet = findViewById(R.id.icNoInternet)
        errorIcon = findViewById(R.id.icError)
        errorText = findViewById(R.id.tvError)
        retryButton = findViewById(R.id.btnRetry)
        speakButton = findViewById(R.id.btnSpeak)
        visibility = GONE
        bindUi()
    }

    fun setHost(value: Host) { host = value }
    fun isOpen() = visibility == VISIBLE

    private fun bindUi() {
        closeButton.setOnClickListener { hidePanel(true) }
        switchButton.setOnClickListener { swapLanguages() }
        sourceButton.setOnClickListener { showLanguageDialog(source = true) }
        targetButton.setOnClickListener { showLanguageDialog(source = false) }
        retryButton.setOnClickListener { onTextChanged(sourceEdit.text.toString()) }
        speakButton.setOnClickListener { toggleSpeech() }
        speakButton.isEnabled = SpeechRecognizer.isRecognitionAvailable(context)
        sourceEdit.showSoftInputOnFocus = false
        sourceEdit.isFocusableInTouchMode = true
        sourceEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { onTextChanged(s?.toString().orEmpty()) }
        })
    }

    fun showPanel() {
        loadPair()
        updateLanguageLabels()
        updateHint()
        visibility = VISIBLE
        sourceEdit.requestFocus()
        post { requestLayout() }
    }

    fun hidePanel(commit: Boolean) {
        if (!isOpen()) return
        translateJob?.cancel()
        stopSpeech()
        if (commit) host?.finishTranslationComposition()
            else host?.getTargetInputConnection()?.finishComposingText()
        visibility = GONE
        post { requestLayout() }
    }

    /** Lets normal keyboard keys type into the translate box while the panel is open. */
    fun handleKeyCode(code: Int): Boolean {
        if (!isOpen()) return false
        when {
            code == -7 -> { // backspace
                val s = sourceEdit.selectionStart.coerceAtLeast(0)
                val e = sourceEdit.selectionEnd.coerceAtLeast(s)
                if (s != e) sourceEdit.text.delete(s, e)
                else if (s > 0) sourceEdit.text.delete(Character.offsetByCodePoints(sourceEdit.text, s, -1), s)
                return true
            }
            code == -21 -> { sourceEdit.setSelection((sourceEdit.selectionStart - 1).coerceAtLeast(0)); return true }
            code == -22 -> { sourceEdit.setSelection((sourceEdit.selectionEnd + 1).coerceAtMost(sourceEdit.text.length)); return true }
            code == -27 -> { sourceEdit.setSelection(0); return true }
            code == -28 -> { sourceEdit.setSelection(sourceEdit.text.length); return true }
            code == 10 -> { insertText("\n"); return true }
            code in 1..0x10FFFF -> { runCatching { insertText(String(Character.toChars(code))) }.isSuccess.also { return it } }
        }
        return false
    }

    private fun insertText(value: String) {
        val s = sourceEdit.selectionStart.coerceAtLeast(0)
        val e = sourceEdit.selectionEnd.coerceAtLeast(s)
        sourceEdit.text.replace(s, e, value)
        sourceEdit.setSelection(s + value.length)
    }

    private fun onTextChanged(text: String) {
        translateJob?.cancel()
        if (text.isBlank()) {
            host?.getTargetInputConnection()?.finishComposingText()
            return
        }
        if (text.length > MAX_TEXT_LENGTH) { setState(TranslationState.TooLong); return }
        val src = sourceLanguage; val tgt = targetLanguage
        setState(TranslationState.Loading)
        translateJob = scope.launch {
            delay(DEBOUNCE_MS)
            val result = withContext(Dispatchers.IO) {
                runCatching { translate(text, src, tgt) }
                    .fold(
                        onSuccess = { TranslationState.Translated(it) },
                        onFailure = { t -> if (t is IOException && t.message?.startsWith("HTTP") == true)
                                TranslationState.Error(context.getString(R.string.translate_error_unknown))
                            else TranslationState.NoInternet }
                    )
            }
            if (!isActive) return@launch
            setState(result)
        }
    }

    private fun setState(newState: TranslationState) {
        when (newState) {
            is TranslationState.Translated -> {
                // Desh commits the translation immediately (jg/e.C -> s() does
                // deleteSurroundingText + commitText); it does not leave grey
                // composing text in the target editor.
                val ic = host?.getTargetInputConnection()
                ic?.deleteSurroundingText(0, 0)
                ic?.commitText(newState.text, 1)
                errorRow.visibility = GONE
            }
            TranslationState.Loading -> errorRow.visibility = GONE
            TranslationState.TooLong -> showError(
                noInternet = false,
                message = context.getString(R.string.translate_try_shorter_text)
            )
            TranslationState.NoInternet -> showError(
                noInternet = true,
                message = "" // Desh shows just the icon for no-internet
            )
            is TranslationState.Error -> showError(
                noInternet = false,
                message = newState.message
            )
            else -> errorRow.visibility = GONE
        }
    }

    private fun showError(noInternet: Boolean, message: String) {
        host?.getTargetInputConnection()?.finishComposingText()
        errorNoInternet.visibility = if (noInternet) VISIBLE else GONE
        errorIcon.visibility = if (noInternet) GONE else VISIBLE
        errorText.text = message
        errorRow.visibility = VISIBLE
    }

    /** The engine — identical endpoint/params to Desh's controller. */
    private fun translate(text: String, src: String, tgt: String): String {
        if (src == tgt) return text
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("tl", tgt)
            .addQueryParameter("sl", src)
            .addQueryParameter("q", text)
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K)")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val root = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray
                ?: throw IOException("Invalid response")
            val sents = root.getOrNull(0) as? JsonArray ?: throw IOException("No sentences")
            return buildString {
                for (e in sents) {
                    val seg = e as? JsonArray ?: continue
                    (seg.getOrNull(0) as? JsonPrimitive)?.content?.let(::append)
                }
            }.ifEmpty { throw IOException("Empty translation") }
        }
    }

    private fun showLanguageDialog(source: Boolean) {
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(12.dpToPx(resources), 4.dpToPx(resources), 12.dpToPx(resources), 4.dpToPx(resources))
        }
        val search = SearchView(context).apply {
            isIconified = false
            queryHint = context.getString(R.string.translate_search_language)
        }
        val list = ListView(context)
        var filtered = DESH_TRANSLATION_LANGUAGES
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filtered.map { it.second }.toTypedArray())
        list.adapter = adapter
        container.addView(search, android.widget.LinearLayout.LayoutParams(-1, 50.dpToPx(resources)))
        container.addView(list, android.widget.LinearLayout.LayoutParams(-1, 420.dpToPx(resources)))
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(if (source) R.string.translate_select_source else R.string.translate_select_target)
            .setView(container).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val selected = filtered[pos].first
            if (source) {
                if (selected == targetLanguage) targetLanguage = sourceLanguage
                sourceLanguage = selected
            } else {
                if (selected == sourceLanguage) sourceLanguage = targetLanguage
                targetLanguage = selected
            }
            persistPair(); updateLanguageLabels(); updateHint()
            onTextChanged(sourceEdit.text.toString()); dialog.dismiss()
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                val term = q.orEmpty().trim().lowercase(Locale.ROOT)
                filtered = if (term.isEmpty()) DESH_TRANSLATION_LANGUAGES
                    else DESH_TRANSLATION_LANGUAGES.filter {
                        it.second.lowercase(Locale.ROOT).contains(term) || it.first.lowercase(Locale.ROOT).contains(term)
                    }
                adapter.clear(); adapter.addAll(filtered.map { it.second }); adapter.notifyDataSetChanged()
                return true
            }
        })
        dialog.show()
    }

    // ---- speech input (Desh's mic icon) ----
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var speechBase = ""

    private fun toggleSpeech() {
        if (listening) { recognizer?.stopListening(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.destroy(); recognizer = r; speechBase = sourceEdit.text.toString()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true; updateSpeakButton() }
            override fun onBeginningOfSpeech() { listening = true; updateSpeakButton() }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false; updateSpeakButton() }
            override fun onError(e: Int) { listening = false; updateSpeakButton(); r.destroy(); recognizer = null }
            override fun onResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::applySpeech)
                listening = false; updateSpeakButton(); r.destroy(); recognizer = null
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::applySpeech)
            }
            override fun onEvent(t: Int, p: Bundle?) = Unit
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        r.startListening(i)
    }

    private fun applySpeech(text: String) {
        val combined = if (speechBase.isBlank()) text
            else if (speechBase.endsWith(" ")) speechBase + text
            else "$speechBase $text"
        sourceEdit.setText(combined); sourceEdit.setSelection(sourceEdit.text.length)
        onTextChanged(combined)
    }

    private fun stopSpeech() {
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null; listening = false
        if (::speakButton.isInitialized) updateSpeakButton()
    }
    private fun updateSpeakButton() {
        speakButton.text = context.getString(if (listening) R.string.translate_stop_speaking else R.string.translate_speak)
    }

    // ---- language pair persistence (Desh's SharedPreferences usage) ----
    private fun loadPair() {
        sourceLanguage = prefs.getString(PREF_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE
        targetLanguage = prefs.getString(PREF_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET
        if (sourceLanguage == targetLanguage)
            targetLanguage = if (sourceLanguage == "hi") "en" else "hi"
        persistPair()
    }
    private fun persistPair() { prefs.edit { putString(PREF_SOURCE, sourceLanguage); putString(PREF_TARGET, targetLanguage) } }
    private fun swapLanguages() {
        val x = sourceLanguage; sourceLanguage = targetLanguage; targetLanguage = x
        persistPair(); updateLanguageLabels(); updateHint()
        onTextChanged(sourceEdit.text.toString())
    }
    private fun updateLanguageLabels() {
        sourceButton.text = languageName(sourceLanguage)
        targetButton.text = languageName(targetLanguage)
    }
    private fun updateHint() {
        sourceEdit.hint = when (targetLanguage) {
            "en" -> context.getString(R.string.translate_hint_to_english)
            "hi" -> context.getString(R.string.translate_hint_to_hindi)
            else -> context.getString(R.string.translate_hint_generic)
        }
    }
    private fun languageName(code: String) =
        DESH_TRANSLATION_LANGUAGES.firstOrNull { it.first == code }?.second ?: code.uppercase(Locale.ROOT)

    override fun onDetachedFromWindow() {
        translateJob?.cancel()
        stopSpeech()
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
