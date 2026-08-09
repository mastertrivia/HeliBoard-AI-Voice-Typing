// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.runtime.AiVoiceConnectionStatus
import helium314.keyboard.latin.aivoice.runtime.AiVoiceRecordingState
import helium314.keyboard.latin.aivoice.runtime.AiVoiceRuntimeState
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory

/** Immutable projection of engine state for Card 1. It owns no state and issues no commands. */
private data class AiVoiceSummaryUiModel(
    val connection: AiVoiceConnectionStatus,
    val recording: AiVoiceRecordingState,
    val configuredProfile: ApiProfile?,
    val sessionProfile: ApiProfile?,
    val languageMode: String,
    val chunkMode: String,
    val rotation: RotationSummary,
)

private data class RotationSummary(
    val fallbackEnabled: Boolean,
    val dayIntervalDays: Int?,
    val clockIntervalMillis: Long?,
    val usageLimitMillis: Long?,
)

private fun toAiVoiceSummaryUiModel(config: AiVoiceConfig, runtime: AiVoiceRuntimeState): AiVoiceSummaryUiModel {
    fun profile(id: String?) = id?.let { profileId -> config.profiles.firstOrNull { it.id == profileId } }
    return AiVoiceSummaryUiModel(
        connection = runtime.connection,
        recording = runtime.recording,
        configuredProfile = profile(config.activeProfileId),
        sessionProfile = profile(runtime.profileId),
        languageMode = runtime.languageModeLabel,
        chunkMode = runtime.chunkModeLabel,
        rotation = RotationSummary(
            fallbackEnabled = config.rotation.failureRotationEnabled,
            dayIntervalDays = config.rotation.dayRotationIntervalDays,
            clockIntervalMillis = config.rotation.clockRotationIntervalMillis,
            usageLimitMillis = config.rotation.usageRotationLimitMillis,
        ),
    )
}

/** Card 1: read-only engine/configuration summary. Keep mutable controls in their own cards. */
@Composable
internal fun AiVoiceActiveStatusCard(config: AiVoiceConfig, runtime: AiVoiceRuntimeState) {
    val model = toAiVoiceSummaryUiModel(config, runtime)
    PreferenceCategory(stringResource(R.string.ai_voice_category_status))
    Preference(
        name = stringResource(R.string.ai_voice_status),
        description = stringResource(
            R.string.ai_voice_status_summary,
            connectionLabel(model.connection),
            recordingLabel(model.recording),
        ),
        onClick = {},
    )
    Preference(
        name = stringResource(R.string.ai_voice_summary_active_profile),
        description = model.configuredProfile?.let { profileDescription(it) }
            ?: stringResource(R.string.ai_voice_no_profile_selected),
        onClick = {},
    )
    model.sessionProfile?.takeIf { it.id != model.configuredProfile?.id }?.let { profile ->
        Preference(
            name = stringResource(R.string.ai_voice_summary_session_profile),
            description = profileDescription(profile),
            onClick = {},
        )
    }
    Preference(
        name = stringResource(R.string.ai_voice_summary_recording_mode),
        description = stringResource(R.string.ai_voice_summary_recording_mode_value, model.languageMode, model.chunkMode),
        onClick = {},
    )
    Preference(
        name = stringResource(R.string.ai_voice_summary_rotation),
        description = rotationDescription(model.rotation),
        onClick = {},
    )
    Preference(
        name = stringResource(R.string.ai_voice_summary_usage),
        description = formatUsage(model.configuredProfile?.accumulatedRecordingMillis ?: 0L),
        onClick = {},
    )
}

@Composable
private fun connectionLabel(value: AiVoiceConnectionStatus) = stringResource(when (value) {
    AiVoiceConnectionStatus.IDLE -> R.string.ai_voice_connection_idle
    AiVoiceConnectionStatus.TESTING -> R.string.ai_voice_connection_testing
    AiVoiceConnectionStatus.CONNECTED -> R.string.ai_voice_connection_connected
    AiVoiceConnectionStatus.API_ERROR -> R.string.ai_voice_connection_api_error
})

@Composable
private fun recordingLabel(value: AiVoiceRecordingState) = stringResource(when (value) {
    AiVoiceRecordingState.IDLE -> R.string.ai_voice_recording_idle
    AiVoiceRecordingState.STARTING -> R.string.ai_voice_recording_starting
    AiVoiceRecordingState.RECORDING -> R.string.ai_voice_recording_active
    AiVoiceRecordingState.PAUSED -> R.string.ai_voice_recording_paused
    AiVoiceRecordingState.STOPPING -> R.string.ai_voice_recording_stopping
})

@Composable
private fun profileDescription(profile: ApiProfile) = stringResource(
    R.string.ai_voice_summary_profile_value,
    profile.serialNumber,
    profile.displayName,
    profile.providerId,
    profile.modelId,
)

@Composable
private fun rotationDescription(rotation: RotationSummary): String {
    val values = mutableListOf<String>()
    if (rotation.fallbackEnabled) values.add(stringResource(R.string.ai_voice_rotation_fallback_enabled))
    rotation.dayIntervalDays?.let { values.add(stringResource(R.string.ai_voice_rotation_day_value, it)) }
    rotation.clockIntervalMillis?.let { values.add(stringResource(R.string.ai_voice_rotation_clock_value, formatDuration(it))) }
    rotation.usageLimitMillis?.let { values.add(stringResource(R.string.ai_voice_rotation_usage_value, formatDuration(it))) }
    return values.takeIf { it.isNotEmpty() }?.joinToString(separator = " · ")
        ?: stringResource(R.string.ai_voice_rotation_none)
}

@Composable
private fun formatUsage(millis: Long) = formatDuration(millis.coerceAtLeast(0L))

@Composable
private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> stringResource(R.string.ai_voice_duration_hours, hours, minutes, seconds)
        minutes > 0L -> stringResource(R.string.ai_voice_duration_minutes, minutes, seconds)
        else -> stringResource(R.string.ai_voice_duration_seconds, seconds)
    }
}
