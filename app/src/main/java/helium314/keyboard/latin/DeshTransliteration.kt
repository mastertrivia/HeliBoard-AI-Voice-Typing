package helium314.keyboard.latin

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.utils.Log

/**
 * Desh-derived transliteration (Hinglish) bridge.
 *
 * Mirrors Desh's `Transliteration.smali`:
 *   - `System.loadLibrary("playwright")` (the FST transliteration engine)
 *   - `loadModelNative("transliteration.db", assets)` -> native handle
 *   - `predictNative(handle, text.toLowerCase(), 12)` -> String[] candidates
 *
 * The model asset ships in `assets/desh_predictor/transliteration.db` (the
 * original file, byte-for-byte). The engine is lazy and isolated: if the native
 * lib or model cannot be loaded, HeliBoard simply continues without it.
 */
object DeshTransliteration {
    private const val TAG = "DeshTransliteration"
    private const val MAX_RESULTS = 12 // Desh Transliteration.b(): const/16 v4, 0xc
    private const val MODEL_ASSET = "desh_predictor/transliteration.db"

    @Volatile private var initialized = false
    @Volatile private var loadAttempted = false
    @Volatile private var available = false
    private var assets: android.content.res.AssetManager? = null
    private var engine: com.deshkeyboard.suggestions.nativesuggestions.transliteration.Transliteration? = null
    private var handle = 0L

    /** Stores the application AssetManager only; no native code is loaded at app startup. */
    fun initialize(context: Context) {
        if (!initialized) synchronized(this) {
            if (!initialized) {
                assets = context.applicationContext.assets
                initialized = true
            }
        }
    }

    private fun ensureLoaded(): Boolean {
        if (available) return true
        if (loadAttempted) return false
        synchronized(this) {
            if (available) return true
            if (loadAttempted) return false
            loadAttempted = true
            if (!Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
                Log.i(TAG, "Disabled: Desh transliteration engine is arm64-v8a only")
                return false
            }
            val manager = assets ?: return false
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("openfst")
                System.loadLibrary("playwright")
                engine = com.deshkeyboard.suggestions.nativesuggestions.transliteration.Transliteration()
                handle = engine!!.loadModelNative(MODEL_ASSET, manager)
                if (handle == 0L) {
                    Log.e(TAG, "Desh transliteration model failed to load")
                    engine = null
                    return false
                }
                available = true
                Log.i(TAG, "Desh transliteration engine enabled")
                return true
            } catch (t: Throwable) {
                available = false
                handle = 0L
                engine = null
                Log.e(TAG, "Desh transliteration unavailable", t)
                return false
            }
        }
    }

    /**
     * Transliterates a Latin (Hinglish) word into Devanagari candidates.
     * Returns null when the engine is unavailable; otherwise the candidate list
     * (already deduplicated), which may be empty.
     */
    fun getSuggestions(word: String): List<String>? {
        if (word.isBlank()) return emptyList()
        if (!ensureLoaded()) return null
        return try {
            val result = engine!!.predictNative(handle, word.lowercase(), MAX_RESULTS)
            result?.filter { it.isNotBlank() }?.distinct()?.take(MAX_RESULTS).orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Desh transliteration call failed; disabling", t)
            available = false
            handle = 0L
            engine = null
            null
        }
    }
}
