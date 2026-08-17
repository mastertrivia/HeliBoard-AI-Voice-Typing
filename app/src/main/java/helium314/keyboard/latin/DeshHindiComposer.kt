/*
 * Desh Hindi composer — the isolated Desh input layer for the "desh_hindi" subtype.
 *
 * HeliBoard normally inserts each key's character directly through InputLogic.
 * Desh instead builds the current syllable (consonant + matra / halant + conjunct)
 * as composing text and commits it at word boundaries. This class reproduces that
 * behavior with Desh's own data (DeshHindiLayoutData.SYLLABLES, the exact syllable
 * list from Desh's lb/b.java) and owns the complete key -> composition -> commit
 * path for desh_hindi.
 *
 * Safety: every entry point is wrapped so that any unexpected state falls back to
 * plain HeliBoard insertion (return false) — the resulting text is then identical
 * to the previous behavior, never corrupted.
 */
package helium314.keyboard.latin

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

/** Desh Hindi syllable composer — scoped strictly to the desh_hindi subtype. */
class DeshHindiComposer(private val host: Host) {

    /** The only bridge to Android: everything the composer needs from the editor. */
    interface Host {
        fun getTextBeforeCursor(maxLength: Int): String?
        fun setComposingText(text: String)
        fun commitText(text: String)
        fun deleteSurroundingText(before: Int)
        fun finishComposingText()
    }

    // ---- Devanagari building blocks (layout uses decomposed forms) ----
    private val HALANT = "\u094D" // ्
    private val NUKTA = "\u093C" // ़

    private val VOWELS: Set<String> = setOf(
        "\u0905", "\u0906", "\u0907", "\u0908", "\u0909", "\u090A", "\u090B", // अ आ इ ई उ ऊ ऋ
        "\u090D", "\u090F", "\u0910", "\u0911", "\u0913", "\u0914" // ऍ ए ऐ ऑ ओ औ
    )

    private val MATRAS: Set<String> = setOf(
        "\u093E", "\u093F", "\u0940", "\u0941", "\u0942", "\u0943", // ा ि ी ु ू ृ
        "\u0945", "\u0947", "\u0948", "\u0949", "\u094B", "\u094C" // ॅ े ै ॉ ो ौ
    )

    private val MODIFIERS: Set<String> = setOf("\u0901", "\u0902", "\u0903") // ँ ं ः

    private val SINGLE_CONSONANTS: Set<String> = setOf(
        "\u0915", "\u0916", "\u0917", "\u0918", "\u0919", // क ख ग घ ङ
        "\u091A", "\u091B", "\u091C", "\u091D", "\u091E", // च छ ज झ ञ
        "\u091F", "\u0920", "\u0921", "\u0922", "\u0923", // ट ठ ड ढ ण
        "\u0924", "\u0925", "\u0926", "\u0927", "\u0928", // त थ द ध न
        "\u092A", "\u092B", "\u092C", "\u092D", "\u092E", // प फ ब भ म
        "\u092F", "\u0930", "\u0932", "\u0935", "\u0936", // य र ल व श
        "\u0937", "\u0938", "\u0939" // ष स ह
    )

    /** Nukta forms as typed by the layout: base + ़ (e.g. क़ = क + ़). */
    private val NUKTA_CONSONANTS: Set<String> =
        setOf("\u0915", "\u0916", "\u0917", "\u091C", "\u0921", "\u0922", "\u092B", "\u091D", "\u092F")
            .map { it + NUKTA }.toSet() // क़ ख़ ग़ ज़ ड़ ढ़ फ़ झ़ य़

    /** Atomic conjunct letters as emitted by the layout: base + ् + last. */
    private val ATOMIC_CONJUNCTS: Set<String> = setOf(
        "\u0915" + HALANT + "\u0937", // क्ष
        "\u0924" + HALANT + "\u0930", // त्र
        "\u091C" + HALANT + "\u091E", // ज्ञ
        "\u0936" + HALANT + "\u0930" // श्र
    )

    private val CONSONANTS: Set<String> =
        SINGLE_CONSONANTS + NUKTA_CONSONANTS + ATOMIC_CONJUNCTS

    private enum class Type { CONSONANT, VOWEL, MATRA, MODIFIER, HALANT, OTHER }

    /** The currently composed syllable (Desh's "syllable state"). */
    private var active = ""

    /** Called on input start / finish / subtype change. */
    fun reset() {
        active = ""
    }

    /** True while the composer holds an unfinished composed syllable. */
    fun hasActiveSyllable(): Boolean = active.isNotEmpty()

    /** Commits any pending syllable and clears the composition state. */
    fun commitPending() {
        if (active.isEmpty()) return
        host.commitText(active)
        active = ""
    }

    /**
     * Desh vowel-diacritic mode, owned by the composer.
     *
     * True while the composed syllable can still take a matra (i.e. it is a member of
     * Desh's own syllable set, all of which end in a consonant). Deriving this from the
     * editor text instead would race with the asynchronous setComposingText round-trip
     * and keep the vowel keys permanently standalone.
     */
    fun isVowelDiacriticMode(): Boolean =
        active.isNotEmpty() && DeshHindiLayoutData.SYLLABLES.contains(active)

    /**
     * The current syllable while vowel-diacritic mode is active (Desh's fe.f.F).
     * Null when the keys should show their standalone vowel forms. This is the
     * exact state Desh's mainkeyboard/a uses to compose key labels as syllable+matra.
     */
    fun getActiveSyllable(): String? =
        if (isVowelDiacriticMode()) active else null

    /** Handles a single-key event (letter keys arrive here). */
    fun onKey(code: Int): Boolean {
        return try {
            when (code) {
                KeyCode.DELETE -> onBackspace()
                else -> if (code in 1..0x10FFFF) onUnit(String(Character.toChars(code))) else {
                    commit()
                    false
                }
            }
        } catch (t: Throwable) {
            commit()
            false
        }
    }

    /** Handles multi-codepoint text keys (क्ष, क़, … arrive here). */
    fun onText(text: String): Boolean {
        return try {
            if (text.isEmpty()) false else onUnit(text)
        } catch (t: Throwable) {
            commit()
            false
        }
    }

    /** Backspace: remove one unit from the composed syllable first, else fall through. */
    private fun onBackspace(): Boolean {
        sync()
        if (active.isEmpty()) return false
        val last = active.codePointBefore(active.length)
        active = active.substring(0, active.length - Character.charCount(last))
        if (active.isEmpty()) {
            host.finishComposingText()
        } else {
            host.setComposingText(active)
        }
        return true
    }

    private fun onUnit(unit: String): Boolean {
        when (classify(unit)) {
            Type.CONSONANT -> return onConsonant(unit)
            Type.MATRA, Type.MODIFIER -> return onMatraOrModifier(unit)
            Type.HALANT -> return onHalant()
            Type.VOWEL -> {
                // Standalone vowels are direct text in Desh too; the diacritic-mode
                // layout already turned the key into its matra when it should attach.
                commit()
                return false
            }
            Type.OTHER -> {
                // Word boundary / punctuation / non-Hindi: commit the syllable.
                commit()
                return false
            }
        }
    }

    private fun onConsonant(unit: String): Boolean {
        sync()
        if (active.endsWith(HALANT)) {
            // Half-form pending: try to build a valid conjunct with Desh's own syllable set.
            val candidate = active + unit
            if (DeshHindiLayoutData.SYLLABLES.contains(candidate)) {
                active = candidate
                host.setComposingText(active)
                return true
            }
            // Not a valid conjunct: commit the half-form, start fresh.
            commit()
        } else if (active.isNotEmpty()) {
            commit()
        }
        active = unit
        host.setComposingText(active)
        return true
    }

    private fun onMatraOrModifier(unit: String): Boolean {
        sync()
        if (active.isNotEmpty() && endsWithConsonant(active)) {
            active += unit
            host.setComposingText(active)
            return true
        }
        if (active.isNotEmpty()) {
            // matra after halant/matra: not a valid combination — commit and fall through
            commit()
            return false
        }
        // No composed syllable yet: the matra belongs to the last syllable already
        // in the text (Desh treats it as still-open). Adopt it if it is a real syllable.
        val adopted = adoptSyllableFromText()
        if (adopted != null) {
            host.deleteSurroundingText(adopted.length)
            active = adopted + unit
            host.setComposingText(active)
            return true
        }
        return false
    }

    private fun onHalant(): Boolean {
        sync()
        if (active.isNotEmpty() && endsWithConsonant(active)) {
            active += HALANT
            host.setComposingText(active)
            return true
        }
        if (active.isNotEmpty()) {
            commit()
            return false
        }
        val adopted = adoptSyllableFromText()
        if (adopted != null) {
            host.deleteSurroundingText(adopted.length)
            active = adopted + HALANT
            host.setComposingText(active)
            return true
        }
        return false
    }

    /** Longest suffix of the text before the cursor that is a Desh syllable ending in a
     *  consonant (i.e. a syllable the user is still extending). */
    private fun adoptSyllableFromText(): String? {
        val before = host.getTextBeforeCursor(DeshHindiLayoutData.DESH_SYLLABLE_WINDOW) ?: return null
        for (start in before.indices) {
            val suffix = before.substring(start)
            if (suffix in DeshHindiLayoutData.SYLLABLES && endsWithConsonant(suffix))
                return suffix
        }
        return null
    }

    /**
     * Our composed syllable must match the actual text, otherwise the editor moved
     * the cursor / committed / the app edited the text — start over.
     *
     * The read immediately after our own setComposingText can lag (async
     * InputConnection round-trip), so an empty read is never trusted here — only a
     * non-empty read that definitively no longer ends with our composition wipes it.
     */
    private fun sync() {
        if (active.isEmpty()) return
        val before = host.getTextBeforeCursor(active.length + 8) ?: return
        if (before.isEmpty()) return
        if (!before.endsWith(active))
            active = ""
    }

    private fun commit() {
        if (active.isEmpty()) return
        host.commitText(active)
        active = ""
    }

    private fun classify(unit: String): Type = when {
        unit == HALANT -> Type.HALANT
        unit in MATRAS -> Type.MATRA
        unit in MODIFIERS -> Type.MODIFIER
        unit in VOWELS -> Type.VOWEL
        unit in CONSONANTS -> Type.CONSONANT
        else -> Type.OTHER
    }

    private fun endsWithConsonant(text: String): Boolean {
        if (text.isEmpty()) return false
        val last = text.codePointBefore(text.length)
        val lastChar = String(Character.toChars(last))
        if (lastChar in SINGLE_CONSONANTS) return true
        // nukta-form consonant (base + ़) — e.g. क़ ends with ़, not with क
        return lastChar == NUKTA && NUKTA_CONSONANTS.any { text.endsWith(it) }
    }
}
