/*
 * Desh input subsystem — coherent block.
 *
 * Single boundary through which HeliBoard talks to the Desh keyboard engines
 * (Desh Hindi + Desh English). It owns:
 *   - Desh subtype identification (desh_hindi / desh_english)
 *   - the Hindi vowel/matra composition state machine
 *   - the no-input-vowel (अ) handling
 *   - suggestion/learning routing to the Desh predictors
 *
 * HeliBoard stays the host shell/toolbar; everything Desh-specific about
 * *input* lives here or is reached through here.
 */
package helium314.keyboard.latin

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

object DeshInputEngine {
    /** Subtype main-layout names that select the Desh engines. */
    const val SUBTYPE_DESH_HINDI = "desh_hindi"
    const val SUBTYPE_DESH_ENGLISH = "desh_english"

    /** The अ key code in Desh Hindi vowel mode (app-internal range). */
    const val CODE_NO_INPUT_VOWEL = KeyCode.DESH_NO_INPUT_VOWEL

    // ------------------------------------------------------------------
    // Subtype identification
    // ------------------------------------------------------------------

    fun isDeshHindiSubtype(mainLayoutName: String?): Boolean =
        mainLayoutName == SUBTYPE_DESH_HINDI

    fun isDeshEnglishSubtype(mainLayoutName: String?): Boolean =
        mainLayoutName == SUBTYPE_DESH_ENGLISH

    fun isDeshSubtype(mainLayoutName: String?): Boolean =
        isDeshHindiSubtype(mainLayoutName) || isDeshEnglishSubtype(mainLayoutName)

    /**
     * All Devanagari Hindi main-layout names (the built-in Hindi layouts plus the
     * isolated Desh Hindi one). HeliBoard ships no Hindi .dict file, so the Desh
     * native vocabulary is the only Hindi dictionary source and should serve every
     * Hindi subtype — not just desh_hindi.
     */
    val HINDI_DEVANAGARI_LAYOUTS: Set<String> = setOf(
        "desh_hindi", "hindi", "hindi_compact", "hindi_phonetic"
    )

    fun isHindiDevanagariSubtype(mainLayoutName: String?): Boolean =
        mainLayoutName in HINDI_DEVANAGARI_LAYOUTS

    // ------------------------------------------------------------------
    // Hindi composition state machine (vowel/matra)
    // ------------------------------------------------------------------

    /**
     * Desh-style Hindi switches the vowel keys to matras immediately after a
     * consonant. The predicate matches Desh's own syllable set (lb/b.java):
     * the longest suffix of the text before the cursor that is a valid syllable.
     */
    @JvmStatic
    fun computeVowelDiacriticMode(textBeforeCursor: CharSequence?): Boolean {
        if (textBeforeCursor == null || textBeforeCursor.length == 0)
            return false
        return DeshHindiLayoutData.findDeshHindiSyllable(textBeforeCursor) != null
    }

    /**
     * The active Desh Hindi syllable before the cursor (fe.f.d -> fe.f.F), or null
     * when no vowel-diacritic syllable is present. This drives the vowel-key label
     * composition (syllable + matra) exactly like Desh's fe/f.F state.
     */
    @JvmStatic
    fun findDeshHindiSyllable(textBeforeCursor: CharSequence?): String? {
        if (textBeforeCursor == null || textBeforeCursor.length == 0)
            return null
        return DeshHindiLayoutData.findDeshHindiSyllable(textBeforeCursor)
    }

    /** True for the अ key that exits vowel mode without inserting text. */
    @JvmStatic
    fun isNoInputVowel(code: Int): Boolean = code == CODE_NO_INPUT_VOWEL

    /** Length of the window read before the cursor when detecting the current syllable. */
    const val SYLLABLE_WINDOW = DeshHindiLayoutData.DESH_SYLLABLE_WINDOW
}
