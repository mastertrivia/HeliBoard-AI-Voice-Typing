// Ported from Desh Keyboard v17.4.9 — com/deshkeyboard/translation/:
//   a.smali   — translation controller (engine, state, show/hide, language picker)
//   d.smali   — SharedPreferences store (used-language lists)
//   xk/d.smali — language selection handling
//   ld/g + ld/a — IME-attached dialog helper
// The only boundary adaptation is [DeshTranslationHost]: Desh passed its IME (bg/g)
// directly; HeliBoard exposes exactly what the block needs (InputConnection, IME
// window token for the attached dialog, current keyboard language, layout callback).
package helium314.keyboard.latin.translation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputConnection
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * HeliBoard boundary — Desh passed its IME (`bg/g`). HeliBoard exposes only what the
 * translation block needs.
 */
interface DeshTranslationHost {
    fun getInputConnection(): InputConnection?
    fun inputViewWindowToken(): IBinder?
    fun currentLanguageCode(): String
    fun onTranslationVisibilityChanged(visible: Boolean)
}

/** Desh translation controller (com/deshkeyboard/translation/a). */
class DeshTranslationEngine(
    private val context: Context,
    private val host: DeshTranslationHost,
) {
    // ---- d.smali — SharedPreferences ----
    private val prefs = context.getSharedPreferences("desh_translation", Context.MODE_PRIVATE)
    private val PREF_SOURCE = "last_selected_source_language"
    private val PREF_TARGET = "last_selected_target_language"
    private val PREF_USED_SOURCE = "used_source_languages"
    private val PREF_USED_TARGET = "used_target_languages"
    private val USED_SEPARATOR = "|"

    // ---- fields (a.smali) ----
    var state: DeshTranslationState = DeshTranslationState.Idle
    var view: DeshTranslationView? = null
    private var sourceLanguage: DeshTranslationLanguage
    private var targetLanguage: DeshTranslationLanguage
    private var request: DeshTranslationRequest? = null

    /** a.e — whether the target editor already has text (translation gets a leading space). */
    var addSpaceToTranslation = false

    /** Tracks the last committed translation so clearing the box removes it (jg/e semantics). */
    private var committedTranslation = ""

    /** Desh MAX_TEXT_LENGTH-equivalent — the built URL length check (a.smali c()). */
    private val MAX_URL_LENGTH = 0x3e80

    init {
        // a.smali <init>: source = prefs "last_selected_source_language" ?: native; target = prefs ?: "en"
        val srcCode = prefs.getString(PREF_SOURCE, null) ?: DeshTranslationLanguage.NATIVE_CODE
        sourceLanguage = DeshTranslationLanguage.find(srcCode, DeshTranslationLanguage.native())
        val tgtCode = prefs.getString(PREF_TARGET, null) ?: "en"
        targetLanguage = DeshTranslationLanguage.find(tgtCode, DeshTranslationLanguage.ENGLISH)
    }

    val source: DeshTranslationLanguage get() = sourceLanguage
    val target: DeshTranslationLanguage get() = targetLanguage

    /** a.g() — whether the translation panel is visible. */
    fun isOpen(): Boolean = view?.visibility == android.view.View.VISIBLE

    // ------------------------------------------------------------------
    // Engine — a.c(String): translate
    // ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun translate(text: String) {
        request?.cancel()
        if (text.isBlank()) {
            clearCommittedTranslation()
            return
        }
        val url = buildUrl(text, sourceLanguage.code, targetLanguage.code)
        if (url.length > MAX_URL_LENGTH) {
            updateState(DeshTranslationState.Error(context.getString(R.string.translate_try_shorter_text)))
            return
        }
        if (!isNetworkAvailable()) {
            updateState(DeshTranslationState.NoInternet)
            return
        }
        request = DeshTranslationRequest(
            url = url,
            onSuccess = { body -> updateState(parseResponse(body)) },
            onError = { updateState(DeshTranslationState.Error(context.getString(R.string.translate_failed))) },
            onRetry = { count -> updateState(DeshTranslationState.Loading(count)) }
        ).also { it.start() }
    }

    /** Desh's engine URL — translate.googleapis.com/translate_a/t?client=gtx&sl=&tl=&q= */
    private fun buildUrl(text: String, src: String, tgt: String): String {
        val base = "https://translate.googleapis.com/translate_a/t"
        val q = URLEncoder.encode(text, "UTF-8")
        return "$base?client=gtx&tl=${URLEncoder.encode(tgt, "UTF-8")}&sl=${URLEncoder.encode(src, "UTF-8")}&q=$q"
    }

    /** Desh's success parse (l0/h1.smali): Gson String[][] → [0][0] is the translated text. */
    private fun parseResponse(body: String): DeshTranslationState {
        return try {
            val root = Json.parseToJsonElement(body) as? JsonArray ?: return DeshTranslationState.Error("")
            val firstSentence = root.getOrNull(0) as? JsonArray ?: return DeshTranslationState.Error("")
            val translated = (firstSentence.getOrNull(0) as? JsonPrimitive)?.content
                ?: return DeshTranslationState.Error("")
            DeshTranslationState.Translated(translated)
        } catch (_: Exception) {
            DeshTranslationState.Error("")
        }
    }

    // ------------------------------------------------------------------
    // State — a.i(f): store + render
    // ------------------------------------------------------------------
    fun updateState(newState: DeshTranslationState) {
        state = newState
        view?.setState(newState)
    }

    // ------------------------------------------------------------------
    // Language selection — a.h(b) / a.j(b) + xk/d (onLanguageSelected)
    // ------------------------------------------------------------------
    fun setSource(language: DeshTranslationLanguage) {
        if (language.code == sourceLanguage.code) return
        val oldSource = sourceLanguage
        sourceLanguage = language
        prefs.edit { putString(PREF_SOURCE, language.code) }
        addUsedLanguage(PREF_USED_SOURCE, language.code)
        if (language.code == targetLanguage.code)
            setTarget(oldSource)
    }

    fun setTarget(language: DeshTranslationLanguage) {
        if (language.code == targetLanguage.code) return
        val oldTarget = targetLanguage
        targetLanguage = language
        prefs.edit { putString(PREF_TARGET, language.code) }
        addUsedLanguage(PREF_USED_TARGET, language.code)
        if (language.code == sourceLanguage.code)
            setSource(oldTarget)
    }

    /** xk/d.b(language) — called when a dialog row is picked. */
    fun onLanguageSelected(language: DeshTranslationLanguage, isSource: Boolean) {
        if (isSource) setSource(language) else setTarget(language)
        view?.initUi()
        translate(view?.typedText.orEmpty())
    }

    /** TranslationView static d — swap source and target. */
    fun swapLanguages() {
        val oldSource = sourceLanguage
        val oldTarget = targetLanguage
        setSource(oldTarget)
        setTarget(oldSource)
        view?.initUi()
        translate(view?.typedText.orEmpty())
    }

    // ------------------------------------------------------------------
    // Show / hide — a.l() / a.a() / a.e()
    // ------------------------------------------------------------------
    fun show() {
        val v = view ?: return
        v.visibility = android.view.View.VISIBLE
        updateState(DeshTranslationState.Init)
        v.initUi()
        val et = v.editText
        et.requestFocus()
        et.text.clear()
        et.isCursorVisible = true
        v.attachTextWatcher()
        v.typedTextMarker = ""
        v.showLanguageRow(showLanguageRow = true)
        // a.l(): compute e — whether the editor already contains text (prepend a space later)
        val ic = host.getInputConnection()
        val beforeCursor = ic?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
        addSpaceToTranslation = beforeCursor.any { it != ' ' && it != '\n' }
        updateState(DeshTranslationState.Idle)
        host.onTranslationVisibilityChanged(true)
    }

    /** a.a() + a.e() — close/apply then hide (panel cleanup identical in Desh). */
    fun hide(apply: Boolean) {
        val v = view ?: return
        if (!isOpen()) return
        request?.cancel()
        v.visibility = android.view.View.GONE
        v.detachTextWatcher()
        v.editText.isCursorVisible = false
        v.editText.text.clear()
        v.editText.clearFocus()
        if (apply) host.getInputConnection()?.finishComposingText()
        host.onTranslationVisibilityChanged(false)
    }

    // ------------------------------------------------------------------
    // Committing translated text into the target editor (jg/e U.C)
    // ------------------------------------------------------------------
    fun commitTranslatedText(text: String) {
        val ic = host.getInputConnection() ?: return
        val finalText = if (addSpaceToTranslation && text.isNotEmpty()) " $text" else text
        if (committedTranslation.isNotEmpty())
            ic.deleteSurroundingText(committedTranslation.length, 0)
        ic.commitText(finalText, 1)
        committedTranslation = finalText
    }

    /** a.c("") — clearing the box removes the previously committed translation. */
    private fun clearCommittedTranslation() {
        val ic = host.getInputConnection() ?: return
        if (committedTranslation.isNotEmpty()) {
            ic.deleteSurroundingText(committedTranslation.length, 0)
            committedTranslation = ""
        }
    }

    // ------------------------------------------------------------------
    // Language picker — a.k(Z)
    // ------------------------------------------------------------------
    fun showLanguageDialog(isSource: Boolean) {
        val contextTheme = context
        val root = LayoutInflater.from(contextTheme.applicationContext)
            .inflate(R.layout.translation_language_select_dialog, null, false)
        val title = root.findViewById<TextView>(R.id.dialog_title)
        title.text = contextTheme.getString(if (isSource) R.string.translate_from else R.string.translate_to)
        // HeliBoard theme tinting (Desh used its own theme colors)
        helium314.keyboard.latin.settings.Settings.getValues()?.mColors?.let { colors ->
            root.findViewById<android.view.View>(R.id.dialog_card).background
                ?.let { colors.setColor(it, helium314.keyboard.latin.common.ColorType.STRIP_BACKGROUND) }
            title.setTextColor(colors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT))
        }

        val used = usedLanguages(if (isSource) PREF_USED_SOURCE else PREF_USED_TARGET)
            .mapNotNull { code -> DeshTranslationLanguage.TABLE.firstOrNull { it.code == code } }
        val all = DeshTranslationLanguage.all()
        val usedSet = used.map { it.code }.toSet()
        val rest = all.filter { it.code !in usedSet }
        val selected = if (isSource) sourceLanguage else targetLanguage

        val list = root.findViewById<RecyclerView>(R.id.language_list)
        list.layoutManager = LinearLayoutManager(contextTheme)
        list.adapter = LanguageListAdapter(
            used = used,
            rest = rest,
            selected = selected,
            onPick = { language ->
                onLanguageSelected(language, isSource)
            }
        )

        val builder = AlertDialog.Builder(contextTheme)
        builder.setView(root)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(true)
        val token = host.inputViewWindowToken()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        if (token != null) dialog.window?.attributes?.token = token
        dialog.show()
    }

    // ------------------------------------------------------------------
    // Used-language prefs — d.smali
    // ------------------------------------------------------------------
    private fun usedLanguages(key: String): List<String> =
        prefs.getString(key, "")?.split(USED_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    private fun addUsedLanguage(key: String, code: String) {
        val current = usedLanguages(key)
        if (code in current) return
        prefs.edit { putString(key, (current + code).joinToString(USED_SEPARATOR)) }
    }

    // ------------------------------------------------------------------
    // Connectivity — qd/k0.z (Desh's network check)
    // ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return info.isConnected
    }

    /** a.m() — keyboard-mode adjust after a source-language change. HeliBoard keeps its
     *  keyboard; nothing to change here (documented boundary decision). */
    fun onSourceLanguageChangedForKeyboard() = Unit

    /** Release everything on IME destroy. */
    fun destroy() {
        request?.cancel()
        view = null
    }
}

/** RecyclerView adapter for the language picker (Desh xk/g): "Recent" header + rows
 *  with a radio indicator; the selected language is checked. */
private class LanguageListAdapter(
    private val used: List<DeshTranslationLanguage>,
    private val rest: List<DeshTranslationLanguage>,
    private val selected: DeshTranslationLanguage,
    private val onPick: (DeshTranslationLanguage) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val hasHeader = used.isNotEmpty()
    private val all: List<DeshTranslationLanguage> get() = if (hasHeader) used + rest else rest

    override fun getItemCount(): Int = all.size + if (hasHeader) 1 else 0

    override fun getItemViewType(position: Int): Int = if (hasHeader && position == 0) 0 else 1

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == 0) {
            val v = inflater.inflate(R.layout.translation_language_item_header, parent, false)
            return HeaderHolder(v)
        }
        val v = inflater.inflate(R.layout.translation_language_item, parent, false)
        helium314.keyboard.latin.settings.Settings.getValues()?.mColors?.let { colors ->
            val keyText = colors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
            v.findViewById<TextView>(R.id.language_name).setTextColor(keyText)
            v.findViewById<android.widget.RadioButton>(R.id.language_radio).buttonTintList =
                android.content.res.ColorStateList.valueOf(keyText)
        }
        return RowHolder(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is RowHolder) {
            val language = if (hasHeader) all[position - 1] else all[position]
            holder.name.text = language.name
            holder.radio.isChecked = language.code == selected.code
            holder.root.setOnClickListener { onPick(language) }
        } else if (holder is HeaderHolder && holder.itemView is TextView) {
            helium314.keyboard.latin.settings.Settings.getValues()?.mColors?.let { colors ->
                (holder.itemView as TextView).setTextColor(colors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT))
            }
        }
    }

    private class HeaderHolder(view: android.view.View) : RecyclerView.ViewHolder(view)
    private class RowHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val root: android.view.View = view
        val radio: android.widget.RadioButton = view.findViewById(R.id.language_radio)
        val name: TextView = view.findViewById(R.id.language_name)
    }
}
