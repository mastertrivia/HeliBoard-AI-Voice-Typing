// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.domain

import kotlinx.serialization.Serializable

/** Durable, non-secret configuration consumed by the AI Voice runtime. */
@Serializable
data class AiVoiceConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val nextProfileSerial: Int = 1,
    val activeProfileId: String? = null,
    val pendingProfileId: String? = null,
    val profiles: List<ApiProfile> = emptyList(),
    val recording: RecordingConfig = RecordingConfig(),
    val rotation: RotationConfig = RotationConfig(),
) {
    companion object {
        const val SCHEMA_VERSION = 6
        /** Hard product limit: one encrypted credential is owned by each profile. */
        const val MAX_PROFILES = 100
    }
}

@Serializable
data class ApiProfile(
    val id: String,
    val serialNumber: Int,
    val displayName: String,
    val providerId: String,
    val modelId: String,
    /** Disabled profiles remain saved but are excluded from selection, rotation, and fallback. */
    val enabled: Boolean = true,
    val validation: ValidationState = ValidationState.UNTESTED,
    val providerOptions: Map<String, String> = emptyMap(),
    val accumulatedRecordingMillis: Long = 0L,
)

@Serializable
enum class ValidationState { UNTESTED, VALID, INVALID_API_KEY, NETWORK_ERROR, PROVIDER_ERROR }

@Serializable
data class RecordingConfig(
    val independentMicAndKeyboard: Boolean = false,
    /** Short silence seals and sends the current chunk while capture continues. Null disables auto-send. */
    val silenceDurationMillis: Long? = 2_000L,
    /** Continuous silence from the last speech frame that ends the microphone session. Null disables the stop. */
    val prolongedSilenceDurationMillis: Long? = 10_000L,
    val recordingTimeoutMillis: Long? = null,
)

@Serializable
data class RotationConfig(
    val failureRotationEnabled: Boolean = false,
    /** When failure rotation skips the active profile, retain it but make it unavailable. */
    val disableFailedProfile: Boolean = false,
    val dayRotationIntervalDays: Int? = null,
    val clockRotationIntervalMillis: Long? = null,
    /** Wall-clock anchor complements elapsedRealtime so clock policy can recover after reboot. */
    val clockRotationAnchorEpochMillis: Long? = null,
    val usageRotationLimitMillis: Long? = null,
    /** Recording accumulated within the current usage-rotation cycle. */
    val usageCycleRecordingMillis: Long = 0L,
    val dayRotationAnchorEpochMillis: Long? = null,
    val clockRotationAnchorElapsedRealtime: Long? = null,
    /** Due automatic policies which could not win the last safe-boundary arbitration. */
    val pendingTriggers: Set<RotationTrigger> = emptySet(),
    /** Profile that queued a pending failure trigger; a stale failure must never be attributed to a later active profile. */
    val pendingFailureProfileId: String? = null,
)

/** Persisted only to preserve safe-boundary rotation requests across IME/process recreation. */
@Serializable
enum class RotationTrigger { FAILURE, DAY, CLOCK, USAGE }

@Serializable
data class ProviderDescriptor(
    val id: String,
    val label: String,
    val models: List<ModelDescriptor>,
)

@Serializable
data class ModelDescriptor(val id: String, val label: String)

/** Immutable per-session view; settings edits never alter an active recording. */
data class SessionPolicySnapshot(
    val independentMicAndKeyboard: Boolean,
    val silenceDurationMillis: Long?,
    val prolongedSilenceDurationMillis: Long?,
    val recordingTimeoutMillis: Long?,
)

fun AiVoiceConfig.sessionPolicy() = SessionPolicySnapshot(
    independentMicAndKeyboard = recording.independentMicAndKeyboard,
    silenceDurationMillis = recording.silenceDurationMillis,
    prolongedSilenceDurationMillis = recording.prolongedSilenceDurationMillis,
    recordingTimeoutMillis = recording.recordingTimeoutMillis,
)
