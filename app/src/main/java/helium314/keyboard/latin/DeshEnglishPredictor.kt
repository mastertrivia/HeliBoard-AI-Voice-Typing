/*
 * Copyright (C) 2026 HeliBorg / HeliBoard modifications
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.SuggestionResults

/**
 * Phase 13C: English prefix prediction boundary.
 *
 * This deliberately uses the original Desh English dictionary resource. It does not merge
 * HeliBoard English candidates into the returned set. The English LM/learned-word path is
 * intentionally deferred to later phases.
 */
object DeshEnglishPredictor {
    private const val RESULT_CAPACITY = 18

    fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext,
        settings: SettingsValuesForSuggestion,
        sessionId: Int,
        inputStyle: Int,
    ): SuggestionResults? {
        val dictionary = DeshEnglishDictionaryLoader.loadDictionary() ?: return null
        val suggestions = dictionary.getSuggestions(
            composedData,
            ngramContext,
            0L,
            settings,
            sessionId,
            1.0f,
            FloatArray(1),
        ) ?: return null

        val results = SuggestionResults(
            RESULT_CAPACITY,
            ngramContext.isBeginningOfSentenceContext,
            false,
        )
        for (suggestion in suggestions) {
            if (suggestion.kind == SuggestedWords.SuggestedWordInfo.KIND_TYPED) continue
            results.add(suggestion)
        }
        return results.takeIf { it.isNotEmpty() }
    }
}
