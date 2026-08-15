/*
 * Desh user-native-word store — ported from Desh Keyboard v17.4.9
 * (com/deshkeyboard/suggestions/nativesuggestions/user/usernativewords/a.smali).
 *
 * Desh does NOT auto-learn typed words. The store is a plain
 * SharedPreferences("user_native_words") mapping word -> value string:
 *   - words are added/deleted manually from the settings screen
 *     (Desh's UserNativeWordEntryActivity / ik/m.smali putString write);
 *   - removal is key-based (usernativewords/a.b(word) -> Editor.remove);
 *   - at suggestion time the exact typed word is looked up (a(word, flag))
 *     and, when present, merged into the Desh native suggestions at
 *     Integer.MAX_VALUE (the ak/b$a USER_NATIVE_WORD kind), so stored words
 *     outrank everything by design.
 *
 * Scope: only the desh_hindi subtype consumes this. Normal HeliBoard
 * Hindi/English learning (UserHistoryDictionary etc.) is untouched.
 */
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

object DeshNativeWordStore {
    private const val PREFS = "user_native_words"

    @Volatile private var prefs: SharedPreferences? = null

    /** Called once at App startup, mirroring DeshHindiPredictor.initialize(). */
    fun initialize(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null)
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    /** Mirrors Desh's manual-entry save: word -> value, both lowercased + trimmed. */
    fun add(word: String, value: String) {
        val normalized = normalize(word)
        if (normalized.isEmpty()) return
        prefs?.edit()
            ?.putString(normalized, normalize(value))
            ?.apply()
    }

    /** Mirrors usernativewords/a.b(word): removes the key entirely. */
    fun remove(word: String) {
        val normalized = normalize(word)
        if (normalized.isEmpty()) return
        prefs?.edit()
            ?.remove(normalized)
            ?.apply()
    }

    /** Mirrors usernativewords/a.a(word, flag): null when the word is not stored. */
    fun get(word: String): String? {
        val normalized = normalize(word)
        if (normalized.isEmpty()) return null
        return prefs?.getString(normalized, null)
    }

    /** All stored pairs, for the settings screen (Desh's UserNativeWordListActivity). */
    fun all(): Map<String, String> {
        val p = prefs ?: return emptyMap()
        return p.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun normalize(word: String): String =
        word.trim().lowercase(Locale.ROOT)
}
