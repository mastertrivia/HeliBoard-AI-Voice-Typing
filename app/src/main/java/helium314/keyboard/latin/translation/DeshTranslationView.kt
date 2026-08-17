// Ported from Desh Keyboard v17.4.9 — com/deshkeyboard/translation/TranslationView.smali.
// Same structure and behavior: inflate layout_translation_view, bind all views,
// wire the click handlers exactly as Desh does, render the state machine, and
// forward keyboard input into the box. Colors come from HeliBoard's theme system
// (Desh used ?primaryContainer etc.; HeliBoard tints with its own ColorType).
package helium314.keyboard.latin.translation

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/** Desh TranslationView — the translation panel block. */
class DeshTranslationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Set by the host (Desh: setController$app_hindiRelease). */
    lateinit var engine: DeshTranslationEngine

    // ---- views (wd/c3 LayoutTranslationViewBinding) ----
    private lateinit var btnRetry: TextView
    private lateinit var clError: android.view.ViewGroup
    private lateinit var clTranslateFromToContainer: android.view.ViewGroup
    private lateinit var closeTranslate: ImageView
    private lateinit var ibLanguageSwitchSwitch: ImageButton
    private lateinit var icError: ImageView
    private lateinit var icNoInternet: ImageView
    private lateinit var tvError: TextView
    private lateinit var tvDots: TextView
    private lateinit var tvTranslateFrom: TextView
    private lateinit var tvTranslateTo: TextView

    /** Desh H / I strings: translate_hint_native / translate_hint_english. */
    private val hintNative = context.getString(R.string.translate_hint_native)
    private val hintEnglish = context.getString(R.string.translate_hint_english)

    /** Desh TranslationView.F — last typed text marker. */
    var typedTextMarker = ""

    val editText: EditText get() = etTranslate
    lateinit private var etTranslate: EditText

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            val newText = s?.toString().orEmpty()
            if (newText == typedTextMarker) return
            typedTextMarker = ""
            engine.translate(newText)
        }
    }

    init {
        val inflated = android.view.LayoutInflater.from(context)
            .inflate(R.layout.translation_view_panel, this, true)
        btnRetry = findViewById(R.id.btnRetry)
        clError = findViewById(R.id.cl_error)
        clTranslateFromToContainer = findViewById(R.id.clTranslateFromToContainer)
        closeTranslate = findViewById(R.id.closeTranslate)
        etTranslate = findViewById(R.id.etTranslate)
        ibLanguageSwitchSwitch = findViewById(R.id.ibLanguageSwitchSwitch)
        icError = findViewById(R.id.icError)
        icNoInternet = findViewById(R.id.icNoInternet)
        tvError = findViewById(R.id.tvError)
        tvDots = findViewById(R.id.tvDots)
        tvTranslateFrom = findViewById(R.id.tvTranslateFrom)
        tvTranslateTo = findViewById(R.id.tvTranslateTo)
        visibility = GONE

        // ---- click handlers (Desh constructor, mapped 1:1) ----
        // ibLanguageSwitchSwitch → static d (swap)
        ibLanguageSwitchSwitch.setOnClickListener { engine.swapLanguages() }
        // closeTranslate → static b (a() + e() = close & apply)
        closeTranslate.setOnClickListener { engine.hide(apply = true) }
        // etTranslate focus change → static c (focus gained → re-show)
        etTranslate.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) engine.show() }
        // etTranslate click → static g (not focused → re-show)
        etTranslate.setOnClickListener {
            if (!etTranslate.isFocused) engine.show()
        }
        // btnRetry → static a (re-translate current text)
        btnRetry.setOnClickListener { engine.translate(typedText) }
        // tvTranslateFrom → static f (source picker)
        tvTranslateFrom.setOnClickListener { engine.showLanguageDialog(isSource = true) }
        // tvTranslateTo → static e (target picker)
        tvTranslateTo.setOnClickListener { engine.showLanguageDialog(isSource = false) }

        etTranslate.showSoftInputOnFocus = false
        etTranslate.isFocusableInTouchMode = true

        applyThemeColors()
    }

    /** Re-applied on every open so the panel always reflects the current theme. */
    fun applyThemeColors() {
        val colors = Settings.getValues()?.mColors ?: return
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        listOf<android.view.View>(btnRetry, tvTranslateFrom, tvTranslateTo).forEach { pill ->
            pill.background?.let { colors.setColor(it, ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) }
            (pill as? TextView)?.setTextColor(colors.get(ColorType.TOOL_BAR_KEY))
        }
        ibLanguageSwitchSwitch.background?.let { colors.setColor(it, ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) }
        colors.setColor(ibLanguageSwitchSwitch, ColorType.TOOL_BAR_KEY)
        colors.setColor(closeTranslate, ColorType.TOOL_BAR_KEY)
        colors.setColor(icError, ColorType.KEY_TEXT)
        colors.setColor(icNoInternet, ColorType.KEY_TEXT)
        tvError.setTextColor(colors.get(ColorType.KEY_TEXT))
        tvDots.setTextColor(colors.get(ColorType.KEY_TEXT))
        etTranslate.setTextColor(colors.get(ColorType.KEY_TEXT))
        etTranslate.setHintTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        // input box background + text cursor tinted to the theme
        findViewById<android.view.View>(R.id.cvEditText).background
            ?.let { colors.setColor(it, ColorType.KEY_BACKGROUND) }
        // getTextCursorDrawable() is API 29+; the layout XML already sets the cursor
        // drawable (cursor_drawable_with_primary_text_color), so tint it only when available.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            etTranslate.textCursorDrawable?.setTint(colors.get(ColorType.KEY_TEXT))
    }

    // ------------------------------------------------------------------
    // i() — refresh labels / hint / switch description
    // ------------------------------------------------------------------
    fun initUi() {
        tvTranslateTo.text = engine.target.name
        tvTranslateFrom.text = engine.source.name
        // Desh i(): native hint only when source == native language && target == English
        val sourceIsNative = engine.source.code == DeshTranslationLanguage.NATIVE_CODE
        etTranslate.hint = if (sourceIsNative && engine.target.code == DeshTranslationLanguage.ENGLISH.code)
            hintNative else hintEnglish
        ibLanguageSwitchSwitch.contentDescription = context.getString(
            R.string.translate_language_switch_button_description,
            engine.source.name, engine.target.name
        )
        requestLayout()
    }

    // ------------------------------------------------------------------
    // j(f) — render state
    // ------------------------------------------------------------------
    fun setState(state: DeshTranslationState) {
        when (state) {
            DeshTranslationState.Idle, DeshTranslationState.Init, DeshTranslationState.Stopped -> {
                // Desh renders nothing for these
            }
            is DeshTranslationState.Translated -> {
                // f$e: language row visible, error row gone, commit immediately (jg/e U.C)
                clTranslateFromToContainer.visibility = VISIBLE
                clError.visibility = GONE
                engine.commitTranslatedText(state.text)
            }
            is DeshTranslationState.Error -> {
                // f$a: error row + retry, language row gone
                clError.visibility = VISIBLE
                btnRetry.visibility = VISIBLE
                clTranslateFromToContainer.visibility = GONE
                tvError.text = state.message
                icNoInternet.visibility = GONE
                icError.visibility = VISIBLE
                tvDots.visibility = GONE
            }
            DeshTranslationState.NoInternet -> {
                // f$c: no-internet icon + "Network unavailable" + retry
                clError.visibility = VISIBLE
                clTranslateFromToContainer.visibility = GONE
                tvError.text = context.getString(R.string.network_unavailable)
                icNoInternet.visibility = VISIBLE
                btnRetry.visibility = VISIBLE
                tvDots.visibility = GONE
                icError.visibility = GONE
            }
            is DeshTranslationState.Loading -> {
                // f$d: "Retrying" + dots, no retry button
                clError.visibility = VISIBLE
                tvDots.visibility = VISIBLE
                tvDots.text = ".".repeat(state.dots.coerceAtLeast(0))
                clTranslateFromToContainer.visibility = GONE
                icError.visibility = GONE
                tvError.text = context.getString(R.string.retrying)
                btnRetry.visibility = GONE
                icNoInternet.visibility = GONE
            }
        }
    }

    /** a.l() helper — show/hide the language row vs error row. */
    fun showLanguageRow(showLanguageRow: Boolean) {
        clTranslateFromToContainer.visibility = if (showLanguageRow) VISIBLE else GONE
        clError.visibility = if (showLanguageRow) GONE else VISIBLE
    }

    // ------------------------------------------------------------------
    // Text watcher attach/detach (Desh: addTextChangedListener(G) / removeTextChangedListener(G))
    // ------------------------------------------------------------------
    fun attachTextWatcher() {
        etTranslate.removeTextChangedListener(textWatcher)
        etTranslate.addTextChangedListener(textWatcher)
    }

    fun detachTextWatcher() {
        etTranslate.removeTextChangedListener(textWatcher)
    }

    val typedText: String get() = etTranslate.text.toString()

    // ------------------------------------------------------------------
    // Keyboard input routing (Desh routes keys via the IME → p0(KeyboardEditText);
    // HeliBoard routes keys through LatinIME.onEvent → this)
    // ------------------------------------------------------------------
    fun handleKeyCode(code: Int): Boolean {
        if (!isOpen()) return false
        when {
            code == KeyCodeBackspace -> {
                val s = etTranslate.selectionStart.coerceAtLeast(0)
                val e = etTranslate.selectionEnd.coerceAtLeast(s)
                if (s != e) etTranslate.text.delete(s, e)
                else if (s > 0) etTranslate.text.delete(Character.offsetByCodePoints(etTranslate.text, s, -1), s)
                return true
            }
            code == KeyCodeLeft -> { etTranslate.setSelection((etTranslate.selectionStart - 1).coerceAtLeast(0)); return true }
            code == KeyCodeRight -> { etTranslate.setSelection((etTranslate.selectionEnd + 1).coerceAtMost(etTranslate.text.length)); return true }
            code == KeyCodeHome -> { etTranslate.setSelection(0); return true }
            code == KeyCodeEnd -> { etTranslate.setSelection(etTranslate.text.length); return true }
            code == KeyCodeEnter -> { insertText("\n"); return true }
            code in 1..0x10FFFF -> { return runCatching { insertText(String(Character.toChars(code))) }.isSuccess }
        }
        return false
    }

    /** Multi-codepoint text routing (conjunct keys, emoji): inserts into the box. */
    fun handleText(value: String): Boolean {
        if (!isOpen()) return false
        insertText(value)
        return true
    }

    private fun insertText(value: String) {
        val s = etTranslate.selectionStart.coerceAtLeast(0)
        val e = etTranslate.selectionEnd.coerceAtLeast(s)
        etTranslate.text.replace(s, e, value)
        etTranslate.setSelection(s + value.length)
    }

    fun isOpen(): Boolean = visibility == VISIBLE

    companion object {
        // HeliBoard KeyCode values (matching LatinIME's key routing)
        private const val KeyCodeBackspace = -7
        private const val KeyCodeLeft = -21
        private const val KeyCodeRight = -22
        private const val KeyCodeHome = -27
        private const val KeyCodeEnd = -28
        private const val KeyCodeEnter = 10
    }
}
