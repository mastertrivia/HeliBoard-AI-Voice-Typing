package helium314.keyboard.latin.translation

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Desh translation workspace integrated above the HeliBoard toolbar. */
class DeshTranslationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    interface Host {
        fun getTargetInputConnection(): InputConnection?
        fun currentLanguage(): String
        fun finishTranslationComposition()
    }

    private data class Language(val code: String, val name: String)

    companion object {
        private const val PREFS = "desh_translation"
        private const val PREF_SOURCE = "last_selected_source_language"
        private const val PREF_TARGET = "last_selected_target_language"
        private const val PREF_USED_SOURCE = "used_source_languages"
        private const val PREF_USED_TARGET = "used_target_languages"
        private const val DEFAULT_SOURCE = "hi"
        private const val DEFAULT_TARGET = "en"
        private const val DEBOUNCE_MS = 180L
        private val LANGUAGES = listOf(
            Language("af", "Afrikaans"), Language("sq", "Albanian"), Language("am", "Amharic"),
            Language("ar", "Arabic"), Language("hy", "Armenian"), Language("az", "Azerbaijani"),
            Language("eu", "Basque"), Language("be", "Belarusian"), Language("bn", "Bengali"),
            Language("bs", "Bosnian"), Language("bg", "Bulgarian"), Language("ca", "Catalan"),
            Language("ceb", "Cebuano"), Language("zh-CN", "Chinese (Simplified)"), Language("zh-TW", "Chinese (Traditional)"),
            Language("hr", "Croatian"), Language("cs", "Czech"), Language("da", "Danish"), Language("nl", "Dutch"),
            Language("en", "English"), Language("et", "Estonian"), Language("fi", "Finnish"), Language("fr", "French"),
            Language("de", "German"), Language("el", "Greek"), Language("gu", "Gujarati"), Language("he", "Hebrew"),
            Language("hi", "Hindi"), Language("hu", "Hungarian"), Language("id", "Indonesian"), Language("it", "Italian"),
            Language("ja", "Japanese"), Language("kn", "Kannada"), Language("km", "Khmer"), Language("ko", "Korean"),
            Language("lo", "Lao"), Language("lv", "Latvian"), Language("lt", "Lithuanian"), Language("ms", "Malay"),
            Language("ml", "Malayalam"), Language("mr", "Marathi"), Language("ne", "Nepali"), Language("nl", "Dutch"),
            Language("no", "Norwegian"), Language("or", "Odia"), Language("fa", "Persian"), Language("pl", "Polish"),
            Language("pa", "Punjabi"), Language("pt", "Portuguese"), Language("ro", "Romanian"), Language("ru", "Russian"),
            Language("sr", "Serbian"), Language("si", "Sinhala"), Language("sk", "Slovak"), Language("sl", "Slovenian"),
            Language("es", "Spanish"), Language("sw", "Swahili"), Language("sv", "Swedish"), Language("ta", "Tamil"),
            Language("te", "Telugu"), Language("th", "Thai"), Language("tr", "Turkish"), Language("uk", "Ukrainian"),
            Language("ur", "Urdu"), Language("uz", "Uzbek"), Language("vi", "Vietnamese"), Language("cy", "Welsh"),
            Language("yo", "Yoruba"), Language("zu", "Zulu")
        ).distinctBy { it.code }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private var host: Host? = null
    private var translateJob: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var speechBase = ""
    private var suppressWatcher = false

    private lateinit var sourceButton: TextView
    private lateinit var targetButton: TextView
    private lateinit var sourceEdit: EditText
    private lateinit var speakButton: Button

    private var sourceLanguage = DEFAULT_SOURCE
    private var targetLanguage = DEFAULT_TARGET

    init {
        orientation = VERTICAL
        visibility = GONE
        val bg = Settings.getValues().mColors.get(ColorType.MAIN_BACKGROUND)
        setBackgroundColor(bg)
        setPadding(10.dpToPx(resources), 4.dpToPx(resources), 10.dpToPx(resources), 4.dpToPx(resources))
        buildUi()
    }

    fun setHost(value: Host) { host = value }
    fun isOpen() = visibility == VISIBLE

    fun showPanel() {
        loadPair()
        updateLanguageLabels()
        updateHint()
        visibility = VISIBLE
        sourceEdit.requestFocus()
        post { requestLayout() }
    }

    fun hidePanel(commit: Boolean) {
        if (visibility != VISIBLE) return
        translateJob?.cancel()
        stopSpeech()
        if (commit) host?.finishTranslationComposition() else host?.getTargetInputConnection()?.finishComposingText()
        visibility = GONE
        post { requestLayout() }
    }

    fun handleKeyCode(code: Int): Boolean {
        if (!isOpen()) return false
        when {
            code == -7 -> {
                val s = sourceEdit.selectionStart.coerceAtLeast(0); val e = sourceEdit.selectionEnd.coerceAtLeast(s)
                if (s != e) sourceEdit.text.delete(s, e) else if (s > 0) sourceEdit.text.delete(Character.offsetByCodePoints(sourceEdit.text, s, -1), s)
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
        val s = sourceEdit.selectionStart.coerceAtLeast(0); val e = sourceEdit.selectionEnd.coerceAtLeast(s)
        sourceEdit.text.replace(s, e, value); sourceEdit.setSelection(s + value.length)
    }

    private fun buildUi() {
        val c = Settings.getValues().mColors
        val bg = c.get(ColorType.MAIN_BACKGROUND); val strip = c.get(ColorType.STRIP_BACKGROUND); val fg = c.get(ColorType.KEY_TEXT)
        val langRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 38.dpToPx(resources)) }
        sourceButton = languageChip(DEFAULT_SOURCE, strip, fg)
        targetButton = languageChip(DEFAULT_TARGET, strip, fg)
        langRow.addView(sourceButton)
        langRow.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_translate_language_switch); background = null; scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.translate_switch_languages)
            layoutParams = LayoutParams(40.dpToPx(resources), 36.dpToPx(resources)); setOnClickListener { swapLanguages() }
        })
        langRow.addView(targetButton)
        langRow.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_close); background = null; scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.translate_close)
            layoutParams = LayoutParams(40.dpToPx(resources), 36.dpToPx(resources)); setOnClickListener { hidePanel(true) }
        })
        addView(langRow)

        sourceEdit = EditText(context).apply {
            setTextSize(16f); setTextColor(fg); setHintTextColor(withAlpha(fg, 0x99)); gravity = Gravity.TOP or Gravity.START
            setSingleLine(false); minLines = 1; maxLines = 4
            setPadding(14.dpToPx(resources), 6.dpToPx(resources), 14.dpToPx(resources), 6.dpToPx(resources))
            background = ContextCompat.getDrawable(context, R.drawable.translation_view_edit_text_bg)
            ?: rounded(strip, fg, 1)
            showSoftInputOnFocus = false; isFocusableInTouchMode = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 56.dpToPx(resources)).apply { topMargin = 2.dpToPx(resources) }
        }
        addView(sourceEdit)

        val speakRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL; layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 36.dpToPx(resources)) }
        speakButton = Button(context).apply {
            setAllCaps(false); text = context.getString(R.string.translate_speak); setTextSize(14f); setTextColor(Color.WHITE)
            minWidth = 92.dpToPx(resources); minHeight = 32.dpToPx(resources)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.sym_keyboard_voice_lxx, 0, 0, 0); compoundDrawablePadding = 6.dpToPx(resources)
            background = rounded(0xFF233737.toInt(), 0xFF233737.toInt(), 0); setOnClickListener { toggleSpeech() }
        }
        speakRow.addView(speakButton); addView(speakRow)

        sourceButton.setOnClickListener { showLanguageDialog(true) }
        targetButton.setOnClickListener { showLanguageDialog(false) }
        sourceEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (!suppressWatcher) scheduleTranslation(s?.toString().orEmpty()) }
        })
    }

    private fun languageChip(code: String, fill: Int, fg: Int) = TextView(context).apply {
        text = languageName(code); setTextSize(14f); setTextColor(fg); gravity = Gravity.CENTER
        background = rounded(fill, fg, 1); setPadding(14.dpToPx(resources), 0, 14.dpToPx(resources), 0)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, 30.dpToPx(resources)).apply { marginEnd = 4.dpToPx(resources) }
    }

    private fun showLanguageDialog(source: Boolean) {
        val container = LinearLayout(context).apply { orientation = VERTICAL; setPadding(12.dpToPx(resources), 4.dpToPx(resources), 12.dpToPx(resources), 4.dpToPx(resources)) }
        val search = SearchView(context).apply { isIconified = false; queryHint = context.getString(R.string.translate_search_language) }
        val list = ListView(context)
        var filtered = LANGUAGES
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filtered.map { it.name }.toTypedArray())
        list.adapter = adapter
        container.addView(search, LayoutParams(-1, 50.dpToPx(resources))); container.addView(list, LayoutParams(-1, 420.dpToPx(resources)))
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (source) R.string.translate_select_source else R.string.translate_select_target)
            .setView(container).create()
        list.setOnItemClickListener { _,_,pos,_ ->
            val selected = filtered[pos].code
            if (source) { if (selected == targetLanguage) targetLanguage = sourceLanguage; sourceLanguage = selected; remember(PREF_USED_SOURCE, selected) }
            else { if (selected == sourceLanguage) sourceLanguage = targetLanguage; targetLanguage = selected; remember(PREF_USED_TARGET, selected) }
            persistPair(); updateLanguageLabels(); updateHint(); scheduleTranslation(sourceEdit.text.toString()); dialog.dismiss()
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                val term = q.orEmpty().trim().lowercase(Locale.ROOT)
                filtered = if (term.isEmpty()) LANGUAGES else LANGUAGES.filter { it.name.lowercase(Locale.ROOT).contains(term) || it.code.lowercase(Locale.ROOT).contains(term) }
                adapter.clear(); adapter.addAll(filtered.map { it.name }); adapter.notifyDataSetChanged(); return true
            }
        })
        dialog.show()
    }

    private fun scheduleTranslation(text: String) {
        translateJob?.cancel()
        if (text.isBlank()) { host?.getTargetInputConnection()?.finishComposingText(); return }
        val src = sourceLanguage; val tgt = targetLanguage
        translateJob = scope.launch {
            delay(DEBOUNCE_MS)
            val translated = withContext(Dispatchers.IO) { runCatching { translate(text, src, tgt) }.getOrNull() }
            if (!isActive || translated.isNullOrEmpty()) return@launch
            host?.getTargetInputConnection()?.setComposingText(translated, 1)
        }
    }

    private fun translate(text: String, src: String, tgt: String): String {
        if (src == tgt) return text
        val url = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx").addQueryParameter("tl", tgt).addQueryParameter("sl", src).addQueryParameter("q", text).build()
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K)").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val root = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray ?: throw IOException("Invalid response")
            val sents = root.getOrNull(0) as? JsonArray ?: throw IOException("No sentences")
            return buildString { for (e in sents) (e as? JsonArray)?.getOrNull(0)?.toString()?.trim('"')?.let(::append) }.ifEmpty { throw IOException("Empty translation") }
        }
    }

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
            override fun onResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::applySpeech); listening = false; updateSpeakButton(); r.destroy(); recognizer = null }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::applySpeech) }
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
        suppressWatcher = true
        val combined = if (speechBase.isBlank()) text else if (speechBase.endsWith(" ")) speechBase + text else "$speechBase $text"
        sourceEdit.setText(combined); sourceEdit.setSelection(sourceEdit.text.length); suppressWatcher = false
        scheduleTranslation(combined)
    }

    private fun stopSpeech() { recognizer?.cancel(); recognizer?.destroy(); recognizer = null; listening = false; if (::speakButton.isInitialized) updateSpeakButton() }
    private fun updateSpeakButton() { speakButton.text = context.getString(if (listening) R.string.translate_stop_speaking else R.string.translate_speak) }

    private fun loadPair() {
        sourceLanguage = prefs.getString(PREF_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE
        targetLanguage = prefs.getString(PREF_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET
        if (sourceLanguage == targetLanguage) targetLanguage = if (sourceLanguage == "hi") "en" else "hi"
        persistPair()
    }
    private fun persistPair() { prefs.edit { putString(PREF_SOURCE, sourceLanguage); putString(PREF_TARGET, targetLanguage) } }
    private fun remember(k: String, code: String) { val set = prefs.getStringSet(k, emptySet()).orEmpty().toMutableSet(); set.add(code); prefs.edit { putStringSet(k, set) } }
    private fun swapLanguages() { val x = sourceLanguage; sourceLanguage = targetLanguage; targetLanguage = x; remember(PREF_USED_SOURCE, sourceLanguage); remember(PREF_USED_TARGET, targetLanguage); persistPair(); updateLanguageLabels(); updateHint(); scheduleTranslation(sourceEdit.text.toString()) }
    private fun updateLanguageLabels() { sourceButton.text = languageName(sourceLanguage); targetButton.text = languageName(targetLanguage) }
    private fun updateHint() { sourceEdit.hint = when (targetLanguage) { "en" -> context.getString(R.string.translate_hint_to_english); "hi" -> context.getString(R.string.translate_hint_to_hindi); else -> context.getString(R.string.translate_hint_generic) } }
    private fun languageName(code: String) = LANGUAGES.firstOrNull { it.code == code }?.name ?: code.uppercase(Locale.ROOT)
    private fun rounded(fill: Int, stroke: Int, width: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 16.dpToPx(resources).toFloat(); setColor(fill); if (width > 0) setStroke(width.dpToPx(resources), withAlpha(stroke, 0x55)) }
    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDetachedFromWindow() { translateJob?.cancel(); stopSpeech(); scope.cancel(); super.onDetachedFromWindow() }
}
