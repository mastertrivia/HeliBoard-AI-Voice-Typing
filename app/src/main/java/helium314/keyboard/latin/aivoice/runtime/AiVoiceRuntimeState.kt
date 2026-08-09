// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AiVoiceConnectionStatus { IDLE, TESTING, CONNECTED, API_ERROR }
enum class AiVoiceRecordingState { IDLE, STARTING, RECORDING, PAUSED, STOPPING }
data class AiVoiceRuntimeState(
    val connection: AiVoiceConnectionStatus = AiVoiceConnectionStatus.IDLE,
    val recording: AiVoiceRecordingState = AiVoiceRecordingState.IDLE,
    val sessionId: String? = null,
    val profileId: String? = null,
    val startedAtElapsedRealtime: Long? = null,
    /** Start of the current audio chunk; resets without changing the recording session. */
    val chunkStartedAtElapsedRealtime: Long? = null,
    val languageModeLabel: String = "Not configured",
    val chunkModeLabel: String = "Disabled",
    val lastErrorCode: String? = null,
)

/** Single ephemeral state owner observed by the toolbar and future settings summary. */
class AiVoiceRuntimeStateHolder {
    private val mutableState = MutableStateFlow(AiVoiceRuntimeState())
    val state: StateFlow<AiVoiceRuntimeState> = mutableState
    fun publish(value: AiVoiceRuntimeState) { mutableState.value = value }
}
