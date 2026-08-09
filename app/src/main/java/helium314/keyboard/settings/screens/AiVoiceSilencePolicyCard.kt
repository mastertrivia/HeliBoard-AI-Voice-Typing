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
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Card 4. This is a persisted policy editor only. A future session snapshots these values before
 * capture begins; VAD, silence timing, chunks, and insertion remain outside the settings module.
 */
@Composable
internal fun AiVoiceSilencePolicyCard(
    config: AiVoiceConfig,
    repository: AiVoiceSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    var updateInFlight by remember { mutableStateOf(false) }
    var showAutoSendDurationPicker by remember { mutableStateOf(false) }
    var showProlongedSilenceDurationPicker by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val selectedAutoSendDuration = autoSendDurationOptions.first { it.millis == config.recording.silenceDurationMillis }
    val selectedProlongedSilenceDuration = prolongedSilenceDurationOptions.first {
        it.millis == config.recording.prolongedSilenceDurationMillis
    }

    fun updatePolicy(autoSendDurationMillis: Long?, prolongedSilenceDurationMillis: Long?) {
        if (updateInFlight) return
        if (autoSendDurationMillis == selectedAutoSendDuration.millis &&
            prolongedSilenceDurationMillis == selectedProlongedSilenceDuration.millis
        ) return
        updateInFlight = true
        scope.launch {
            try {
                repository.update("silence_policy_changed") { current ->
                    current.copy(recording = current.recording.copy(
                        silenceDurationMillis = autoSendDurationMillis,
                        prolongedSilenceDurationMillis = prolongedSilenceDurationMillis,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                updateInFlight = false
            }
        }
    }

    Preference(
        name = stringResource(R.string.ai_voice_auto_send_after_silence),
        description = silenceDurationLabel(selectedAutoSendDuration),
        onClick = { if (!updateInFlight) showAutoSendDurationPicker = true },
    )
    Preference(
        name = stringResource(R.string.ai_voice_stop_microphone_after_prolonged_silence),
        description = silenceDurationLabel(selectedProlongedSilenceDuration),
        onClick = { if (!updateInFlight) showProlongedSilenceDurationPicker = true },
    )

    if (showAutoSendDurationPicker) {
        ListPickerDialog(
            onDismissRequest = { showAutoSendDurationPicker = false },
            title = { Text(stringResource(R.string.ai_voice_auto_send_after_silence)) },
            items = autoSendDurationOptions,
            selectedItem = selectedAutoSendDuration,
            getItemName = { silenceDurationLabel(it) },
            onItemSelected = { option ->
                updatePolicy(option.millis, selectedProlongedSilenceDuration.millis)
            },
        )
    }
    if (showProlongedSilenceDurationPicker) {
        ListPickerDialog(
            onDismissRequest = { showProlongedSilenceDurationPicker = false },
            title = { Text(stringResource(R.string.ai_voice_stop_microphone_after_prolonged_silence)) },
            items = prolongedSilenceDurationOptions,
            selectedItem = selectedProlongedSilenceDuration,
            getItemName = { silenceDurationLabel(it) },
            onItemSelected = { option ->
                updatePolicy(selectedAutoSendDuration.millis, option.millis)
            },
        )
    }
    if (saveFailed) {
        InfoDialog(
            message = stringResource(R.string.ai_voice_silence_policy_save_failed),
            onDismissRequest = { saveFailed = false },
        )
    }
}

private data class SilenceDurationOption(val millis: Long?, val seconds: Int?)

private val autoSendDurationOptions = listOf(
    SilenceDurationOption(null, null),
    SilenceDurationOption(2_000L, 2),
    SilenceDurationOption(3_000L, 3),
    SilenceDurationOption(4_000L, 4),
    SilenceDurationOption(5_000L, 5),
    SilenceDurationOption(7_000L, 7),
    SilenceDurationOption(9_000L, 9),
)

private val prolongedSilenceDurationOptions = listOf(
    SilenceDurationOption(null, null),
    SilenceDurationOption(10_000L, 10),
    SilenceDurationOption(15_000L, 15),
    SilenceDurationOption(20_000L, 20),
    SilenceDurationOption(30_000L, 30),
    SilenceDurationOption(45_000L, 45),
    SilenceDurationOption(60_000L, 60),
)

@Composable
private fun silenceDurationLabel(option: SilenceDurationOption): String = option.seconds?.let {
    stringResource(R.string.ai_voice_seconds, it)
} ?: stringResource(R.string.ai_voice_never)
