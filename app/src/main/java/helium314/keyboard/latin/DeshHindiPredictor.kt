package helium314.keyboard.latin

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.Log

/**
 * Optional Desh-derived Hindi predictor bridge.
 *
 * The predictor is lazy and isolated: if its native backend or model cannot be loaded,
 * HeliBoard simply continues with its normal suggestion engine.
 */
object DeshHindiPredictor {
    private const val TAG = "DeshHindiPredictor"
    private const val MAX_RESULTS = 12
    private const val WORDS_ASSET = DeshHindiDictionaryInfo.ASSET
    private const val LM_ASSET = DeshHindiDictionaryInfo.LANGUAGE_MODEL_ASSET

    @Volatile private var initialized = false
    @Volatile private var loadAttempted = false
    @Volatile private var available = false
    private var handle = 0L
    private var assets: android.content.res.AssetManager? = null

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
                Log.i(TAG, "Disabled: Desh predictor backend is arm64-v8a only")
                return false
            }
            val manager = assets ?: return false
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("common_utils")
                System.loadLibrary("language_model")
                System.loadLibrary("nativepredictor")
                handle = com.deshkeyboard.suggestions.nativesuggestions.nativepredictor.NativePredictor
                    .load(WORDS_ASSET, manager)
                if (handle == 0L) {
                    Log.e(TAG, "Desh predictor word model failed to load")
                    return false
                }
                if (!com.deshkeyboard.suggestions.nativesuggestions.nativepredictor.NativePredictor
                        .loadLm(handle, LM_ASSET, manager)) {
                    Log.e(TAG, "Desh predictor language model failed to load")
                    handle = 0L
                    return false
                }
                available = true
                Log.i(TAG, "Desh Hindi predictor enabled")
                return true
            } catch (t: Throwable) {
                available = false
                handle = 0L
                Log.e(TAG, "Desh predictor unavailable; using HeliBoard fallback", t)
                return false
            }
        }
    }

    fun getSuggestions(
        typedWord: String,
        context: NgramContext,
        forNextWord: Boolean
    ): List<String>? {
        if (!ensureLoaded()) return null
        return try {
            val previous = context.extractPrevWordsContextArray()
            val result = if (forNextWord) {
                com.deshkeyboard.suggestions.nativesuggestions.nativepredictor.NativePredictor
                    .getNextWords(handle, previous, MAX_RESULTS)
            } else {
                com.deshkeyboard.suggestions.nativesuggestions.nativepredictor.NativePredictor
                    .nativeLayoutPrefixSearch(
                        handle, previous, typedWord, MAX_RESULTS,
                        context.isBeginningOfSentenceContext()
                    )
            }
            result?.filter { it.isNotBlank() }?.distinct()?.take(MAX_RESULTS)
        } catch (t: Throwable) {
            Log.e(TAG, "Desh predictor call failed; falling back to HeliBoard", t)
            available = false
            handle = 0L
            null
        }
    }

    fun toSuggestionResults(
        words: List<String>,
        context: NgramContext,
        prediction: Boolean
    ): helium314.keyboard.latin.utils.SuggestionResults {
        val results = helium314.keyboard.latin.utils.SuggestionResults(
            words.size.coerceAtLeast(1),
            context.isBeginningOfSentenceContext(),
            false
        )
        words.forEachIndexed { index, word ->
            val kind = if (prediction) SuggestedWords.SuggestedWordInfo.KIND_PREDICTION
            else SuggestedWords.SuggestedWordInfo.KIND_COMPLETION
            results.add(
                SuggestedWords.SuggestedWordInfo(
                    word,
                    context.extractPrevWordsContext(),
                    1_000_000 - index,
                    kind,
                    Dictionary.DICTIONARY_USER_TYPED,
                    SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX,
                    SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE
                )
            )
        }
        return results
    }
}
