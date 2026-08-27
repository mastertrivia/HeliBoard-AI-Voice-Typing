package helium314.keyboard.latin

import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.SuggestionResults

/** Adapter for the existing Desh English retrieval path. */
object EnglishPredictionProviderAdapter : PredictionProvider {
    override fun getCandidates(request: ProviderRequest): ProviderResult? {
        val composedData = request.composedData ?: return null
        val settings = request.settings ?: return null
        val suggestions = DeshEnglishPredictor.getSuggestions(
            composedData, request.ngramContext, settings, request.sessionId, request.inputStyle
        ) ?: return null
        return ProviderResult(request.token, request.generation, suggestions.map {
            ProviderCandidate(
                it.mWord,
                it.mScore,
                CandidateProvenance.MAIN_DICTIONARY,
                "desh-english",
                it.mKindAndFlags,
                it.mSourceDict,
            )
        })
    }
    override fun onLifecycle(event: ProviderLifecycleEvent) { }
}

/** Hindi lifecycle is exposed but intentionally has no learning or mutation. */
object HindiPredictionProviderAdapter : PredictionProvider {
    override fun getCandidates(request: ProviderRequest): ProviderResult? {
        val words = DeshHindiPredictor.getSuggestions(request.typedText, request.ngramContext, request.mode == PredictionMode.PREDICTION) ?: return null
        return ProviderResult(request.token, request.generation, words.mapIndexed { index, word ->
            ProviderCandidate(word, 1_000_000 - index, CandidateProvenance.HINDI_NATIVE, "desh-hindi-native:$index")
        })
    }
    override fun onLifecycle(event: ProviderLifecycleEvent) { }
}

/** Selects only normal pure-English and Devanagari-Hindi retrieval providers. */
object PredictionProviderResolver {
    fun resolve(mainLayoutName: String?): PredictionProvider? = when {
        DeshInputEngine.isHindiDevanagariSubtype(mainLayoutName) -> HindiPredictionProviderAdapter
        DeshInputEngine.isDeshEnglishSubtype(mainLayoutName) -> EnglishPredictionProviderAdapter
        else -> null
    }
}

internal fun ProviderResult.toSuggestionResults(context: NgramContext, prediction: Boolean): SuggestionResults {
    val normalizedCandidates = ProviderCandidateNormalizer.normalize(candidates)
    val results = SuggestionResults(normalizedCandidates.size.coerceAtLeast(1), context.isBeginningOfSentenceContext(), false)
    val kind = if (prediction) SuggestedWords.SuggestedWordInfo.KIND_PREDICTION else SuggestedWords.SuggestedWordInfo.KIND_COMPLETION
    normalizedCandidates.forEach { candidate ->
        results.add(SuggestedWords.SuggestedWordInfo(
            candidate.text,
            context.extractPrevWordsContext(),
            candidate.score,
            candidate.kindAndFlags ?: kind,
            candidate.sourceDictionary ?: dictionaryFor(candidate.provenance),
            SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE,
        ))
    }
    return results
}

private fun dictionaryFor(provenance: CandidateProvenance) = when (provenance) {
    CandidateProvenance.HINDI_NATIVE -> Dictionary.DICTIONARY_USER_TYPED
    CandidateProvenance.TYPED -> Dictionary.DICTIONARY_USER_TYPED
    CandidateProvenance.MAIN_DICTIONARY,
    CandidateProvenance.LEARNED_DICTIONARY,
    CandidateProvenance.UNKNOWN -> Dictionary.DICTIONARY_USER_TYPED
}
