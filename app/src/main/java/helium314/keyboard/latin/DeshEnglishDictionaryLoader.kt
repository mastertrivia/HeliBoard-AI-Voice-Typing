/*
 * Copyright (C) 2026 HeliBorg / HeliBoard modifications
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.utils.Log
import java.io.File

/**
 * Phase 13B resource boundary for the Desh English dictionary.
 *
 * This class deliberately does NOT route English suggestions through the Desh engine yet.
 * It only makes the original Desh English binary dictionary available through HeliBoard's
 * existing BinaryDictionary reader. English suggestion generation/ranking remains unchanged
 * until the later prediction-integration phase.
 *
 * The original asset is copied byte-for-byte to filesDir because BinaryDictionary opens a real
 * file path through native code. The English LM asset is exposed here for the next phase but is
 * intentionally not loaded yet.
 */
object DeshEnglishDictionaryLoader {
    private const val TAG = "DeshEnglishDictionary"
    private const val ASSET_DIRECTORY = "desh_predictor/english"
    private const val DICTIONARY_ASSET = "$ASSET_DIRECTORY/english_dictionary.bin"
    private const val LM_ASSET = "$ASSET_DIRECTORY/english_lm.db"
    private const val CACHE_DIRECTORY = "desh_english"
    private const val DICTIONARY_FILE = "english_dictionary.bin"
    private const val LM_FILE = "english_lm.db"

    @Volatile private var appContext: Context? = null
    @Volatile private var dictionary: ReadOnlyBinaryDictionary? = null

    /** Initializes only the resource boundary; no English suggestions are changed. */
    fun initialize(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                }
            }
        }
    }

    /**
     * Loads the original Desh English binary dictionary once.
     * Returns null when the resource is unavailable or is rejected by HeliBoard's binary
     * dictionary validator. No fallback behavior is changed by this method.
     */
    fun loadDictionary(): ReadOnlyBinaryDictionary? {
        dictionary?.let { return it }
        synchronized(this) {
            dictionary?.let { return it }
            val context = appContext ?: return null
            return try {
                val file = extractDictionary(context) ?: return null
                val header = BinaryDictionaryUtils.getHeader(file)
                val locale = header.mLocaleString.constructLocale()
                val dictType = header.mIdString.substringBefore(":")
                val loaded = ReadOnlyBinaryDictionary(
                    file.absolutePath,
                    0L,
                    file.length(),
                    false,
                    locale,
                    dictType
                )
                if (!loaded.isValidDictionary) {
                    loaded.close()
                    Log.e(TAG, "Desh English dictionary failed binary validation")
                    return null
                }
                // Desh's actual BinaryDictionary path loads the English LM through the
                // native loadTrigramLanguageModel entry point, using the original asset name
                // and AssetManager. Do not convert the model or score it in Kotlin.
                val lmLoaded = loaded.loadTrigramLanguageModel(LM_ASSET, context.assets)
                Log.i(TAG, "Desh English dictionary resource loaded: ${file.length()} bytes; " +
                        "English LM native load=$lmLoaded")
                dictionary = loaded
                loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to load Desh English dictionary resource", t)
                null
            }
        }
    }

    /** Original Desh English LM resource path inside the APK assets.
     * Phase 13D extracts the exact bytes but deliberately does not pass them to
     * HeliBoard's BinaryDictionary, because the Desh LM format is not proven to
     * be the same as HeliBoard's native n-gram/trigram format.
     */
    fun languageModelAssetPath(): String = LM_ASSET

    /** Extracts the original Desh English LM byte-for-byte and returns its path. */
    fun extractLanguageModel(): File? {
        val context = appContext ?: return null
        val directory = File(context.filesDir, CACHE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create Desh English cache directory for LM")
            return null
        }
        val target = File(directory, LM_FILE)
        if (target.isFile && target.length() > 0L) return target
        val temporary = File(directory, "$LM_FILE.tmp")
        return try {
            if (temporary.exists()) temporary.delete()
            FileUtils.copyStreamToNewFile(context.assets.open(LM_ASSET), temporary)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                Log.e(TAG, "Unable to finalize Desh English LM extraction")
                null
            } else target
        } catch (t: Throwable) {
            temporary.delete()
            Log.e(TAG, "Unable to extract $LM_ASSET", t)
            null
        }
    }

    fun extractedLanguageModelFile(): File? {
        val context = appContext ?: return null
        return File(context.filesDir, "$CACHE_DIRECTORY/$LM_FILE").takeIf { it.isFile }
    }

    /** Returns the extracted dictionary file when the resource boundary has been loaded. */
    fun extractedDictionaryFile(): File? {
        val context = appContext ?: return null
        return File(context.filesDir, "$CACHE_DIRECTORY/$DICTIONARY_FILE").takeIf { it.isFile }
    }

    fun close() {
        synchronized(this) {
            dictionary?.close()
            dictionary = null
        }
    }

    private fun extractDictionary(context: Context): File? {
        val directory = File(context.filesDir, CACHE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Unable to create Desh English cache directory")
            return null
        }

        val target = File(directory, DICTIONARY_FILE)
        if (target.isFile && target.length() > 0L) return target

        val temporary = File(directory, "$DICTIONARY_FILE.tmp")
        return try {
            if (temporary.exists()) temporary.delete()
            FileUtils.copyStreamToNewFile(context.assets.open(DICTIONARY_ASSET), temporary)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                Log.e(TAG, "Unable to finalize Desh English dictionary extraction")
                null
            } else {
                target
            }
        } catch (t: Throwable) {
            temporary.delete()
            Log.e(TAG, "Unable to extract $DICTIONARY_ASSET", t)
            null
        }
    }
}
