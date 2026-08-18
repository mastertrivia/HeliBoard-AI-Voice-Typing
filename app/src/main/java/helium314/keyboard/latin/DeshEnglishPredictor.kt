/*
 * Copyright (C) 2026 HeliBorg / HeliBoard modifications
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.SuggestionResults

/**
 * Desh English predictor — mirrors sj/b.g() from the original Desh app.
 *
 * Desh's DictionaryFacilitatorImpl (sj/b) iterates over two dictionary types for English:
 *   sj/b.d = ["main", "history"]   (primary path)
 *   sj/b.e = ["aosp_native_layout_dict", "history"]  (alternative path)
 *
 * Both dictionaries are queried and their results are merged by score using a priority
 * queue (vj/a). This class replicates that merge: the main dictionary (english_dictionary.bin)
 * provides base candidates, and the learned dictionary provides user-history candidates.
 * Results from both sources are interleaved by score, matching Desh's ordering.
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
        val typedWord = composedData.mTypedWord.toString()

        // --- Main dictionary ("main" type in sj/b.d) ---
        val mainSuggestions = dictionary.getSuggestions(
            composedData,
            ngramContext,
            0L,
            settings,
            sessionId,
            1.0f,
            FloatArray(1),
        )

        // --- Learned dictionary ("history" type in sj/b.d) ---
        val locale = dictionary.mLocale
        val learnedSuggestions: List<SuggestedWordInfo>? = try {
            helium314.keyboard.latin.personalization.DeshEnglishLearningManager.suggestions(
                locale, composedData, ngramContext, settings, sessionId
            ).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) { null }

        // Merge: combine both sources, deduplicate, sort by score descending
        // (Desh's sj/b.g() uses vj/a priority queue for this merge)
        val allCandidates = mutableListOf<SuggestedWordInfo>()
        mainSuggestions?.forEach { s ->
            if (s.kind != SuggestedWordInfo.KIND_TYPED) allCandidates.add(s)
        }
        learnedSuggestions?.forEach { s ->
            if (s.kind != SuggestedWordInfo.KIND_TYPED) allCandidates.add(s)
        }

        if (allCandidates.isEmpty()) return null

        // Deduplicate: learned words override main dict (Desh's merge replaces duplicates)
        val seen = linkedMapOf<String, SuggestedWordInfo>()
        for (candidate in allCandidates) {
            val existing = seen[candidate.mWord]
            if (existing == null || candidate.mScore > existing.mScore) {
                seen[candidate.mWord] = candidate
            }
        }

        // Sort by score descending (higher score = better = shown first in strip)
        val sorted = seen.values.sortedByDescending { it.mScore }

        val results = SuggestionResults(
            RESULT_CAPACITY,
            ngramContext.isBeginningOfSentenceContext,
            false,
        )
        for (suggestion in sorted) {
            results.add(suggestion)
            if (results.size >= RESULT_CAPACITY) break
        }
        return results.takeIf { it.isNotEmpty() }
    }
}
