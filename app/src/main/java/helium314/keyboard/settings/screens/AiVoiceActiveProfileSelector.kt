// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.runtime.AiVoiceRecordingState
import helium314.keyboard.latin.aivoice.runtime.AiVoiceRuntimeState
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Card 2. This composable is only an engine client: [AiVoiceSettingsRepository] owns profile
 * selection, serialization, persistence, and diagnostics. No API key or provider is exposed here.
 */
@Composable
internal fun AiVoiceActiveProfileSelector(
    config: AiVoiceConfig,
    runtime: AiVoiceRuntimeState,
    repository: AiVoiceSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var selectionFailed by remember { mutableStateOf(false) }
    val recordingActive = runtime.recording == AiVoiceRecordingState.RECORDING ||
        runtime.recording == AiVoiceRecordingState.PAUSED
    val selectedId = if (recordingActive) config.pendingProfileId ?: config.activeProfileId else config.activeProfileId
    val selected = config.profiles.firstOrNull { it.id == selectedId && it.enabled }
    val enabledProfiles = config.profiles.filter { it.enabled }

    Preference(
        name = stringResource(R.string.ai_voice_active_profile),
        description = selected?.let(::profileLabel) ?: stringResource(R.string.ai_voice_no_profile_selected),
        onClick = { if (enabledProfiles.isNotEmpty()) showPicker = true },
    )

    if (showPicker) {
        ListPickerDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.ai_voice_active_profile)) },
            items = enabledProfiles,
            selectedItem = selected,
            getItemName = { profileLabel(it) },
            onItemSelected = { profile ->
                scope.launch {
                    try {
                        repository.selectActiveProfile(profile.id, recordingActive)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        selectionFailed = true
                    }
                }
            },
        )
    }

    if (selectionFailed) {
        InfoDialog(
            message = stringResource(R.string.ai_voice_profile_selection_failed),
            onDismissRequest = { selectionFailed = false },
        )
    }
}

private fun profileLabel(profile: ApiProfile): String = "API #${profile.serialNumber} - ${profile.displayName}"
