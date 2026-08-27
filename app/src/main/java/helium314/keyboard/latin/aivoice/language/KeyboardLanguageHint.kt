// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.language

import helium314.keyboard.latin.RichInputMethodManager
import java.util.Locale

/**
 * Provider-neutral source of a best-effort speech language hint. It deliberately owns no state:
 * HeliBoard's currently active subtype is read for each request constructed by the runtime.
 */
fun interface KeyboardLanguageHint {
    /** ISO-639-1 code, or null when the active subtype is unsupported/unavailable. */
    fun currentLanguageTag(): String?
}

enum class KeyboardLanguageBehavior(val languageTag: String?) {
    ENGLISH_OUTPUT("en"),
    HINDI_OUTPUT("hi"),
    UNSPECIFIED(null),
}

fun KeyboardLanguageHint.currentBehavior(): KeyboardLanguageBehavior = when (currentLanguageTag()) {
    "en" -> KeyboardLanguageBehavior.ENGLISH_OUTPUT
    "hi" -> KeyboardLanguageBehavior.HINDI_OUTPUT
    else -> KeyboardLanguageBehavior.UNSPECIFIED
}

/** Reuses HeliBoard's authoritative active subtype rather than introducing an AI language setting. */
class HeliBoardKeyboardLanguageHint : KeyboardLanguageHint {
    override fun currentLanguageTag(): String? {
        if (!RichInputMethodManager.isInitialized()) return null
        return when (RichInputMethodManager.getInstance().currentSubtypeLocale.language.lowercase(Locale.ROOT)) {
            ENGLISH -> ENGLISH
            HINDI -> HINDI
            else -> null
        }
    }

    private companion object {
        const val ENGLISH = "en"
        const val HINDI = "hi"
    }
}
