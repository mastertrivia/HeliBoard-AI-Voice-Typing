package helium314.keyboard.latin

import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion

/** Immutable input shared by language prediction providers. */
data class ProviderRequest(
    val composedText: String,
    val typedText: String,
    val ngramContext: NgramContext,
    val mode: PredictionMode,
    val settings: SettingsValuesForSuggestion?,
    val routing: ProviderRoutingIdentity,
    val generation: Long,
    val token: String,
    val composedData: ComposedData? = null,
    val sessionId: Int = 0,
    val inputStyle: Int = 0,
)

enum class PredictionMode { PREDICTION, CORRECTION }
data class ProviderRoutingIdentity(val localeTag: String, val subtypeId: String)
enum class CandidateProvenance { MAIN_DICTIONARY, LEARNED_DICTIONARY, HINDI_NATIVE, TYPED, UNKNOWN }
data class ProviderCandidate(
    val text: String,
    val score: Int,
    val provenance: CandidateProvenance,
    val sourceId: String? = null,
    val kindAndFlags: Int? = null,
    val sourceDictionary: helium314.keyboard.latin.dictionary.Dictionary? = null,
)
data class ProviderResult(val requestToken: String, val generation: Long, val candidates: List<ProviderCandidate>)

sealed interface ProviderLifecycleEvent {
    data class Commit(val text: String, val candidate: ProviderCandidate? = null) : ProviderLifecycleEvent
    data class ManualPick(val candidate: ProviderCandidate) : ProviderLifecycleEvent
    data class Reject(val candidate: ProviderCandidate? = null) : ProviderLifecycleEvent
    data class BackspaceUnlearn(val text: String) : ProviderLifecycleEvent
}

/** Boundary for language providers; lifecycle delivery is not wired in checkpoint 1. */
interface PredictionProvider {
    fun getCandidates(request: ProviderRequest): ProviderResult?
    fun onLifecycle(event: ProviderLifecycleEvent)
}

/** Sanitizes provider output without changing its source provenance. */
object ProviderCandidateNormalizer {
    fun normalize(candidates: List<ProviderCandidate>): List<ProviderCandidate> {
        val retained = LinkedHashMap<String, ProviderCandidate>()
        candidates.forEach { candidate ->
            val displayKey = normalizedDisplayKey(candidate.text) ?: return@forEach
            val normalized = candidate.copy(text = displayKey)
            val existing = retained[displayKey]
            if (existing == null || normalized.score > existing.score) retained[displayKey] = normalized
        }
        return retained.values.toList()
    }

    private fun normalizedDisplayKey(text: String): String? {
        if (text.isEmpty() || text.any { it.isSurrogate() }) return null
        return java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFC).takeIf { it.isNotEmpty() }
    }
}
