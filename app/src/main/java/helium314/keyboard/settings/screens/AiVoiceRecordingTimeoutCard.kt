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
 * Card 5. It only persists the maximum-duration policy. A future session-owned monotonic timer
 * calls the controller's shared finish path with TerminationReason.MAXIMUM_DURATION.
 */
@Composable
internal fun AiVoiceRecordingTimeoutCard(
    config: AiVoiceConfig,
    repository: AiVoiceSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    var updateInFlight by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val selected = recordingTimeoutOptions.first { it.millis == config.recording.recordingTimeoutMillis }

    fun updateTimeout(timeoutMillis: Long?) {
        if (updateInFlight || timeoutMillis == selected.millis) return
        updateInFlight = true
        scope.launch {
            try {
                repository.update("recording_timeout_changed") { current ->
                    current.copy(recording = current.recording.copy(recordingTimeoutMillis = timeoutMillis))
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
        name = stringResource(R.string.ai_voice_max_recording_duration),
        description = timeoutLabel(selected),
        onClick = { if (!updateInFlight) showPicker = true },
    )

    if (showPicker) {
        ListPickerDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.ai_voice_max_recording_duration)) },
            items = recordingTimeoutOptions,
            selectedItem = selected,
            getItemName = { timeoutLabel(it) },
            onItemSelected = { option -> updateTimeout(option.millis) },
        )
    }
    if (saveFailed) {
        InfoDialog(
            message = stringResource(R.string.ai_voice_recording_timeout_save_failed),
            onDismissRequest = { saveFailed = false },
        )
    }
}

private data class RecordingTimeoutOption(val millis: Long?, val minutes: Int?)

private val recordingTimeoutOptions = listOf(
    RecordingTimeoutOption(null, null),
    RecordingTimeoutOption(60_000L, 1),
    RecordingTimeoutOption(120_000L, 2),
    RecordingTimeoutOption(300_000L, 5),
)

@Composable
private fun timeoutLabel(option: RecordingTimeoutOption): String = option.minutes?.let {
    stringResource(R.string.ai_voice_minutes, it)
} ?: stringResource(R.string.ai_voice_never)
