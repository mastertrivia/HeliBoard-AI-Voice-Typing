// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.material3.Switch
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
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Card 3. It persists only the policy used by a future session factory; the active session keeps
 * its already captured SessionPolicySnapshot.
 */
@Composable
internal fun AiVoiceRecordingBehaviourCard(
    config: AiVoiceConfig,
    repository: AiVoiceSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    var updateInFlight by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val enabled = config.recording.independentMicAndKeyboard

    fun updatePolicy(requestedValue: Boolean) {
        if (updateInFlight || requestedValue == enabled) return
        updateInFlight = true
        scope.launch {
            try {
                repository.update("recording_behaviour_changed") { current ->
                    current.copy(recording = current.recording.copy(independentMicAndKeyboard = requestedValue))
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
        name = stringResource(R.string.ai_voice_keep_recording_while_typing),
        description = stringResource(R.string.ai_voice_keep_recording_while_typing_summary),
        onClick = { updatePolicy(!enabled) },
    ) {
        Switch(
            checked = enabled,
            enabled = !updateInFlight,
            onCheckedChange = ::updatePolicy,
        )
    }

    if (saveFailed) {
        InfoDialog(
            message = stringResource(R.string.ai_voice_recording_behaviour_save_failed),
            onDismissRequest = { saveFailed = false },
        )
    }
}
