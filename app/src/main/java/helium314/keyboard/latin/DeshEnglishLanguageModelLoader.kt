/*
 * Copyright (C) 2026 HeliBorg / HeliBoard modifications
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import java.io.File

/**
 * Phase 13D boundary for Desh's English LM resource.
 *
 * This class deliberately performs no scoring and does not reinterpret the binary model.
 * It only guarantees that the exact Desh `english_lm.db` bytes are available to the future
 * Desh English runtime adapter.
 */
object DeshEnglishLanguageModelLoader {
    fun initialize(context: Context) {
        DeshEnglishDictionaryLoader.initialize(context)
    }

    fun ensureExtracted(): File? = DeshEnglishDictionaryLoader.extractLanguageModel()

    fun extractedFile(): File? = DeshEnglishDictionaryLoader.extractedLanguageModelFile()
}
