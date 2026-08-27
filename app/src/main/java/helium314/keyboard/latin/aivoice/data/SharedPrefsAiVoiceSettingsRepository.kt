// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.data

import android.content.SharedPreferences
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.BuiltInProviderCatalog
import helium314.keyboard.latin.aivoice.domain.NewProfileDraft
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.VoiceMode
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.UUID

/**
 * Transactional non-secret configuration store. A failed commit never updates [config].
 * Credentials are intentionally handled by ApiKeyStore, which is wired in a later security milestone.
 */
class SharedPrefsAiVoiceSettingsRepository(
    private val prefs: SharedPreferences,
    private val catalog: ProviderCatalog = BuiltInProviderCatalog(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val diagnostics: AiDiagnosticsSink = AiDiagnosticsSink { },
) : AiVoiceSettingsRepository {
    private val mutex = Mutex()
    private var updatesBlockedByFutureSchema = false
    private val mutableConfig = MutableStateFlow(load())
    override val config: StateFlow<AiVoiceConfig> = mutableConfig

    override suspend fun update(reason: String, transform: (AiVoiceConfig) -> AiVoiceConfig): AiVoiceConfig = try {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!updatesBlockedByFutureSchema) { "AI Voice configuration was written by a newer app version" }
                val next = transform(mutableConfig.value).normalized()
                next.requireValid(catalog)
                check(prefs.edit().putString(KEY_CONFIG_V1, json.encodeToString(next)).commit()) {
                    "AI Voice configuration commit failed: $reason"
                }
                mutableConfig.value = next
                next
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        emitSafely(AiDiagnosticEvent(
            level = DiagnosticLevel.ERROR,
            code = "AI-0205",
            message = "AI Voice configuration update failed",
        ))
        throw exception
    }

    override suspend fun createProfile(draft: NewProfileDraft): ApiProfile {
        var created: ApiProfile? = null
        update("create_profile") { config ->
            require(config.profiles.size < AiVoiceConfig.MAX_PROFILES) { "Maximum number of AI Voice profiles reached" }
            require(draft.displayName.isNotBlank()) { "Profile name is blank" }
            require(catalog.supports(draft.providerId, draft.modelId, draft.mode)) { "Unsupported provider, model, or voice mode" }
            requireValidLiveConfiguration(draft.mode, draft.liveBackendBaseUrl)
            val profile = ApiProfile(
                id = UUID.randomUUID().toString(), serialNumber = config.profiles.size + 1,
                displayName = draft.displayName.trim(), providerId = draft.providerId,
                modelId = draft.modelId, mode = draft.mode,
                liveBackendBaseUrl = draft.liveBackendBaseUrl?.trim()?.trimEnd('/'),
                providerOptions = draft.providerOptions,
            )
            created = profile
            config.copy(profiles = config.profiles + profile, nextProfileSerial = config.profiles.size + 2)
        }
        return checkNotNull(created)
    }

    override suspend fun saveProfile(profile: ApiProfile): ApiProfile {
        update("save_profile") { config ->
            val existing = config.profiles.firstOrNull { it.id == profile.id } ?: error("Unknown profile")
            require(profile.displayName.isNotBlank()) { "Profile name is blank" }
            // A profile from a newer provider catalog must remain editable/deletable without
            // destroying its unknown metadata. Known pairs are still strictly validated.
            require(catalog.supports(profile.providerId, profile.modelId, profile.mode) ||
                (profile.providerId == existing.providerId && profile.modelId == existing.modelId && profile.mode == existing.mode)) {
                "Unsupported provider, model, or voice mode"
            }
            requireValidLiveConfiguration(profile.mode, profile.liveBackendBaseUrl)
            config.copy(profiles = config.profiles.map { if (it.id == profile.id) profile.copy(
                liveBackendBaseUrl = profile.liveBackendBaseUrl?.trim()?.trimEnd('/'),
                serialNumber = existing.serialNumber,
                enabled = existing.enabled,
                displayName = profile.displayName.trim(),
            ) else it })
        }
        return profile
    }

    override suspend fun deleteProfile(profileId: String) {
        update("delete_profile") { config ->
            require(config.profiles.any { it.id == profileId }) { "Unknown profile" }
            require(config.activeProfileId != profileId) { "Select another active profile before deleting this profile" }
            require(config.pendingProfileId != profileId) { "This profile is queued for the next session" }
            config.copy(
                profiles = config.profiles.filterNot { it.id == profileId }.mapIndexed { index, profile ->
                    profile.copy(serialNumber = index + 1)
                },
                nextProfileSerial = config.profiles.size,
            )
        }
    }

    override suspend fun setProfileEnabled(profileId: String, enabled: Boolean) {
        update("set_profile_enabled") { config ->
            require(config.profiles.any { it.id == profileId }) { "Unknown profile" }
            config.copy(profiles = config.profiles.map { profile ->
                if (profile.id == profileId) profile.copy(enabled = enabled) else profile
            })
        }
    }

    override suspend fun reorderProfiles(profileIds: List<String>) {
        update("reorder_profiles") { config ->
            require(profileIds.size == config.profiles.size && profileIds.toSet().size == profileIds.size) {
                "Profile reorder is incomplete"
            }
            val profilesById = config.profiles.associateBy { it.id }
            require(profileIds.all { it in profilesById }) { "Profile reorder contains an unknown profile" }
            config.copy(profiles = profileIds.map { checkNotNull(profilesById[it]) })
        }
    }

    override suspend fun selectActiveProfile(profileId: String, recordingActive: Boolean) {
        try {
            update("select_active_profile") { config ->
                require(config.profiles.any { it.id == profileId && it.enabled }) { "Profile is disabled or unknown" }
                if (recordingActive) {
                    config.copy(pendingProfileId = profileId)
                } else {
                    // The coordinator seeds fresh time anchors before the next session. Usage is
                    // intentionally retained: manual choice changes the ring position, not use.
                    config.copy(
                        activeProfileId = profileId,
                        pendingProfileId = null,
                        rotation = config.rotation.copy(
                            dayRotationAnchorEpochMillis = null,
                            clockRotationAnchorEpochMillis = null,
                            clockRotationAnchorElapsedRealtime = null,
                        ),
                    )
                }
            }
        } catch (exception: IllegalArgumentException) {
            emitSafely(AiDiagnosticEvent(
                level = DiagnosticLevel.WARNING,
                code = "AI-0204",
                message = "Manual profile selection rejected",
            ))
            throw exception
        } catch (exception: IllegalStateException) {
            emitSafely(AiDiagnosticEvent(
                level = DiagnosticLevel.ERROR,
                code = "AI-0204",
                message = "Manual profile selection could not be persisted",
            ))
            throw exception
        }
    }

    private fun load(): AiVoiceConfig = try {
        val stored = prefs.getString(KEY_CONFIG_V1, null) ?: return AiVoiceConfig()
        val version = json.parseToJsonElement(stored).jsonObject["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (version > AiVoiceConfig.SCHEMA_VERSION) {
            updatesBlockedByFutureSchema = true
            emitSafely(AiDiagnosticEvent(
                level = DiagnosticLevel.ERROR,
                code = "AI-0202",
                message = "AI Voice configuration belongs to a newer app version",
            ))
            return AiVoiceConfig()
        }
        val decoded = when (version) {
            1, 2, 3, 4, 5, 6 -> json.decodeFromString<AiVoiceConfig>(stored)
            AiVoiceConfig.SCHEMA_VERSION -> json.decodeFromString<AiVoiceConfig>(stored)
            else -> throw IllegalStateException("Unsupported AI Voice configuration schema: $version")
        }
        val config = if (version < AiVoiceConfig.SCHEMA_VERSION) migrateLegacyConfig(decoded, version) else decoded
        if (config != decoded) {
            emitSafely(AiDiagnosticEvent(
                level = DiagnosticLevel.WARNING,
                code = "AI-0206",
                message = "AI Voice configuration migrated to the current policy",
            ))
        }
        config.also { it.requireValid(catalog) }
    } catch (exception: Exception) {
        // A corrupt document must never prevent HeliBoard from opening. It is replaced on the next mutation.
        emitSafely(AiDiagnosticEvent(
            level = DiagnosticLevel.ERROR,
            code = "AI-0202",
            message = "AI Voice configuration reset after ${exception.javaClass.simpleName}",
        ))
        AiVoiceConfig()
    }

    private fun AiVoiceConfig.normalized() = copy(
        schemaVersion = AiVoiceConfig.SCHEMA_VERSION,
        nextProfileSerial = profiles.size + 1,
        profiles = profiles.map { it.copy(displayName = it.displayName.trim(), accumulatedRecordingMillis = it.accumulatedRecordingMillis.coerceAtLeast(0L)) },
    )

    /** Diagnostics are intentionally best-effort and must never change a persistence result. */
    private fun emitSafely(event: AiDiagnosticEvent) {
        runCatching { diagnostics.emit(event) }
    }

    /** Upgrades the former silence policy and repairs legacy serial gaps after profile deletion. */
    private fun migrateLegacyConfig(config: AiVoiceConfig, version: Int): AiVoiceConfig {
        val recording = if (version < 5) config.recording.copy(
            silenceDurationMillis = config.recording.silenceDurationMillis
                ?.takeIf { it in SUPPORTED_AUTO_SEND_SILENCE_MILLIS }
                ?: DEFAULT_AUTO_SEND_SILENCE_MILLIS,
            prolongedSilenceDurationMillis = config.recording.silenceDurationMillis
                ?.takeIf { it in SUPPORTED_PROLONGED_SILENCE_MILLIS }
                ?: DEFAULT_PROLONGED_SILENCE_MILLIS,
        ) else config.recording
        val renumberedProfiles = config.profiles.mapIndexed { index, profile -> profile.copy(
            serialNumber = index + 1,
            mode = VoiceMode.RECORDING,
            liveBackendBaseUrl = null,
        ) }
        return config.copy(
            schemaVersion = AiVoiceConfig.SCHEMA_VERSION,
            nextProfileSerial = renumberedProfiles.size + 1,
            profiles = renumberedProfiles,
            recording = recording,
        )
    }

    private fun AiVoiceConfig.requireValid(catalog: ProviderCatalog) {
        require(schemaVersion == AiVoiceConfig.SCHEMA_VERSION) { "Unsupported AI Voice configuration version" }
        require(nextProfileSerial == profiles.size + 1 && profiles.size <= AiVoiceConfig.MAX_PROFILES)
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(profiles.map { it.serialNumber }.distinct().size == profiles.size)
        require(profiles.map { it.serialNumber }.toSet() == (1..profiles.size).toSet())
        // Unknown catalog entries can be restored from a future/newer app version. Preserve them
        // verbatim; create/edit of a changed provider-model pair remains catalog-validated above.
        require(profiles.all { it.serialNumber > 0 && it.displayName.isNotBlank() && it.providerId.isNotBlank() && it.modelId.isNotBlank() })
        profiles.forEach { profile ->
            if (profile.mode == VoiceMode.LIVE) {
                require(catalog.supports(profile.providerId, profile.modelId, profile.mode)) {
                    "Unsupported provider, model, or voice mode"
                }
            }
            requireValidLiveConfiguration(profile.mode, profile.liveBackendBaseUrl)
        }
        require(activeProfileId == null || profiles.any { it.id == activeProfileId })
        require(pendingProfileId == null || profiles.any { it.id == pendingProfileId })
        require(recording.silenceDurationMillis == null || recording.silenceDurationMillis in SUPPORTED_AUTO_SEND_SILENCE_MILLIS)
        require(recording.prolongedSilenceDurationMillis == null || recording.prolongedSilenceDurationMillis in SUPPORTED_PROLONGED_SILENCE_MILLIS)
        require(recording.silenceDurationMillis == null || recording.prolongedSilenceDurationMillis == null ||
            recording.prolongedSilenceDurationMillis > recording.silenceDurationMillis)
        require(recording.recordingTimeoutMillis == null || recording.recordingTimeoutMillis in SUPPORTED_TIMEOUT_MILLIS)
        require(rotation.dayRotationIntervalDays == null || rotation.dayRotationIntervalDays in SUPPORTED_DAY_INTERVAL_DAYS)
        require(rotation.clockRotationIntervalMillis == null || rotation.clockRotationIntervalMillis in SUPPORTED_CLOCK_INTERVAL_MILLIS)
        require(rotation.usageRotationLimitMillis == null || rotation.usageRotationLimitMillis in SUPPORTED_USAGE_LIMIT_MILLIS)
        require(rotation.usageCycleRecordingMillis >= 0L)
    }

    private fun requireValidLiveConfiguration(mode: VoiceMode, baseUrl: String?) {
        if (mode == VoiceMode.RECORDING) {
            require(baseUrl.isNullOrBlank()) { "Recording profiles cannot contain a Live backend endpoint" }
            return
        }
        val value = baseUrl?.trim().orEmpty()
        require(value.isNotBlank()) { "Live backend endpoint is required" }
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Live backend endpoint must be an HTTPS base URL without credentials, query, or fragment"
        }
    }

    private companion object {
        const val KEY_CONFIG_V1 = "ai_voice_config_v1"
        const val DEFAULT_AUTO_SEND_SILENCE_MILLIS = 2_000L
        const val DEFAULT_PROLONGED_SILENCE_MILLIS = 10_000L
        val SUPPORTED_AUTO_SEND_SILENCE_MILLIS = setOf(2_000L, 3_000L, 4_000L, 5_000L, 7_000L, 9_000L)
        val SUPPORTED_PROLONGED_SILENCE_MILLIS = setOf(10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L)
        val SUPPORTED_TIMEOUT_MILLIS = setOf(60_000L, 120_000L, 300_000L)
        val SUPPORTED_DAY_INTERVAL_DAYS = setOf(1, 2, 3, 7)
        val SUPPORTED_CLOCK_INTERVAL_MILLIS = setOf(
            1 * 60_000L, 2 * 60_000L, 3 * 60_000L, 4 * 60_000L, 5 * 60_000L, 6 * 60_000L, 7 * 60_000L,
            10 * 60_000L, 15 * 60_000L, 20 * 60_000L, 30 * 60_000L, 45 * 60_000L,
            1 * 3_600_000L, 2 * 3_600_000L, 3 * 3_600_000L, 6 * 3_600_000L,
            12 * 3_600_000L, 24 * 3_600_000L,
        )
        val SUPPORTED_USAGE_LIMIT_MILLIS = setOf(
            30_000L,
            1 * 60_000L, 2 * 60_000L, 3 * 60_000L, 4 * 60_000L, 5 * 60_000L, 6 * 60_000L, 7 * 60_000L,
            8 * 60_000L, 9 * 60_000L, 10 * 60_000L, 13 * 60_000L, 15 * 60_000L, 20 * 60_000L,
            30 * 60_000L, 45 * 60_000L,
            1 * 3_600_000L, 2 * 3_600_000L, 6 * 3_600_000L,
        )
    }
}
