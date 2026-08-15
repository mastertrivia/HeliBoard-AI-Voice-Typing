// Ported from Desh Keyboard v17.4.9 — com/deshkeyboard/translation/f.smali
// ("TranslationState.kt"). Sealed state hierarchy exactly as Desh defines it:
//   f$g = Idle, f$b = Init, f$f = Stopped (all render nothing in the view),
//   f$d = Loading(dots), f$e = Translated(text), f$a = Error(message), f$c = NoInternet.
package helium314.keyboard.latin.translation

/** Desh TranslationState (f.smali) — drives the translation panel UI. */
sealed class DeshTranslationState {
    /** f$g — initial state, nothing shown. */
    object Idle : DeshTranslationState()

    /** f$b — transient state set right after the panel becomes visible. */
    object Init : DeshTranslationState()

    /** f$f — unused stop state, nothing shown. */
    object Stopped : DeshTranslationState()

    /** f$d — retry in progress; the view shows "Retrying" + dots. */
    data class Loading(val dots: Int) : DeshTranslationState()

    /** f$e — translated text; the view commits it into the target editor. */
    data class Translated(val text: String) : DeshTranslationState()

    /** f$a — error with a message ("Try with shorter text" / "Translation failed"). */
    data class Error(val message: String) : DeshTranslationState()

    /** f$c — no internet; the view shows the no-internet row. */
    object NoInternet : DeshTranslationState()
}
