// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.provider

import helium314.keyboard.latin.aivoice.domain.ApiKeyStore
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageBehavior
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import kotlinx.coroutines.CancellationException
import java.io.File

data class TranscriptionRequest(
    val wavFile: File,
    val profile: ApiProfile,
    val apiKey: String,
    val languageBehavior: KeyboardLanguageBehavior = KeyboardLanguageBehavior.UNSPECIFIED,
    val languageTag: String? = languageBehavior.languageTag,
    val sessionId: String? = null,
    val chunkSequence: Long? = null,
) {
    override fun toString() = "TranscriptionRequest(profileId=${profile.id}, languageTag=$languageTag, sessionId=$sessionId, chunkSequence=$chunkSequence, wavFile=<redacted>, apiKey=<redacted>)"
}
data class TranscriptionResult(val text: String, val providerRequestId: String? = null)

/** Implement this interface to add a provider without changing controller/session code. */
interface SpeechProvider {
    val providerId: String
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult
}

sealed interface ProviderFailure {
    data object Authentication : ProviderFailure; data object Authorization : ProviderFailure
    data object RateLimited : ProviderFailure; data object ModelUnavailable : ProviderFailure
    data object InvalidRequest : ProviderFailure; data object NetworkUnavailable : ProviderFailure
    data object NetworkTimeout : ProviderFailure; data object Server : ProviderFailure
    data object MalformedResponse : ProviderFailure; data object Cancelled : ProviderFailure
    data class Unknown(val httpCode: Int?) : ProviderFailure
}

/** Structured, already-sanitized provider error details parsed from a non-2xx body. Never the raw body. */
data class ProviderError(
    val type: String? = null,
    val code: String? = null,
    val message: String? = null,
)

/**
 * Diagnostic evidence attached to a thrown transcription failure. [exceptionStage] pinpoints the
 * pipeline stage ("provider_http", "dns_resolution", "connect", "timeout", "network",
 * "response_parser", "request_validation"); [retried] records that the provider already consumed its
 * single transient retry. Both are metadata only and never influence rotation or fallback logic.
 */
data class ProviderException(
    val failure: ProviderFailure,
    val httpCode: Int? = null,
    val retryAfterMillis: Long? = null,
    val providerError: ProviderError? = null,
    val exceptionStage: String? = null,
    val retried: Boolean = false,
    val requestId: String? = null,
) : Exception(failure.toString())

class SpeechProviderRegistry(providers: Set<SpeechProvider>) {
    private val byId = providers.associateBy { it.providerId }
    fun provider(providerId: String): SpeechProvider? = byId[providerId]
    fun isInstalled(providerId: String): Boolean = provider(providerId) != null
}

/** Resolves profile metadata and obtains a key only at request time. */
class ProviderResolver(
    private val catalog: ProviderCatalog,
    private val keyStore: ApiKeyStore,
    private val registry: SpeechProviderRegistry,
    private val settingsRepository: AiVoiceSettingsRepository,
    private val diagnostics: AiDiagnosticsSink = AiDiagnosticsSink { },
) {
    /**
     * Validates only durable provider/model metadata. It deliberately never reads an API key;
     * callers use this before opening the microphone, while [resolve] obtains the ephemeral key
     * immediately before a provider request.
     */
    fun validate(profile: ApiProfile): ProviderProfileResolution {
        if (!catalog.supports(profile.providerId, profile.modelId)) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.ERROR, code = "AI-0505", profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId, message = "Unsupported provider or model"))
            return ProviderProfileResolution.InvalidProfile
        }
        if (registry.provider(profile.providerId) == null) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.ERROR, code = "AI-0505", profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId, message = "Provider is not installed"))
            return ProviderProfileResolution.ProviderUnavailable
        }
        return ProviderProfileResolution.Ready
    }

    /**
     * Checks whether a profile can be selected for a new session without exposing its key. This
     * intentionally verifies only key presence/readability; remote key validity remains a
     * provider response and must not require a speculative network call.
     */
    suspend fun isProfileUsable(profile: ApiProfile): Boolean {
        if (!profile.enabled) return false
        if (validate(profile) !is ProviderProfileResolution.Ready) return false
        val key = try {
            keyStore.read(profile.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0503", profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId, message = "API key could not be read"))
            return false
        }
        if (key.isNullOrBlank()) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0503", profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId, message = "API key is missing"))
            return false
        }
        return true
    }

    suspend fun resolveActiveProfile(): ProviderResolution {
        val config = settingsRepository.config.value
        val profile = config.activeProfileId?.let { activeId -> config.profiles.firstOrNull { it.id == activeId && it.enabled } }
            ?: return ProviderResolution.NoActiveProfile
        return resolve(profile)
    }

    suspend fun resolve(profile: ApiProfile): ProviderResolution {
        val provider = when (validate(profile)) {
            ProviderProfileResolution.InvalidProfile -> return ProviderResolution.InvalidProfile
            ProviderProfileResolution.ProviderUnavailable -> return ProviderResolution.ProviderUnavailable
            ProviderProfileResolution.Ready -> checkNotNull(registry.provider(profile.providerId))
        }
        // This is deliberately the last operation before handing the ephemeral key to the provider.
        val key = keyStore.read(profile.id)?.takeIf { it.isNotBlank() } ?: run {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0503", profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId, message = "API key is missing"))
            return ProviderResolution.MissingApiKey
        }
        return ProviderResolution.Ready(ResolvedProvider(provider, key))
    }

    private fun emitSafely(event: AiDiagnosticEvent) {
        runCatching { diagnostics.emit(event) }
    }
}

data class ResolvedProvider(val provider: SpeechProvider, val apiKey: String) {
    override fun toString() = "ResolvedProvider(providerId=${provider.providerId}, apiKey=<redacted>)"
}

/** Typed preflight failures let the dispatcher log/redact the cause and choose safe fallback policy. */
sealed interface ProviderResolution {
    data class Ready(val value: ResolvedProvider) : ProviderResolution
    data object NoActiveProfile : ProviderResolution
    data object InvalidProfile : ProviderResolution
    data object ProviderUnavailable : ProviderResolution
    data object MissingApiKey : ProviderResolution
}

/** Metadata-only provider readiness. This boundary intentionally has no API-key state. */
sealed interface ProviderProfileResolution {
    data object Ready : ProviderProfileResolution
    data object InvalidProfile : ProviderProfileResolution
    data object ProviderUnavailable : ProviderProfileResolution
}
