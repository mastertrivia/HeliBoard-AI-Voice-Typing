// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.domain.SessionPolicySnapshot

sealed interface SessionState {
    data object Idle : SessionState
    /** The policy is captured once before a session can leave [Starting]. */
    data class Starting(
        val sessionId: String,
        val profileId: String,
        val policy: SessionPolicySnapshot,
    ) : SessionState
    data class Recording(
        val sessionId: String,
        val profileId: String,
        val startedAtElapsedRealtime: Long,
        val policy: SessionPolicySnapshot,
    ) : SessionState
    data class Paused(
        val sessionId: String,
        val profileId: String,
        val startedAtElapsedRealtime: Long,
        val policy: SessionPolicySnapshot,
    ) : SessionState
    data class Stopping(val sessionId: String, val reason: TerminationReason) : SessionState
}

/** All terminal triggers converge on the controller's single graceful finish path. */
enum class TerminationReason {
    MANUAL_STOP,
    SILENCE_TIMEOUT,
    MAXIMUM_DURATION,
    INPUT_INTERACTION,
    IME_DESTROYED,
    START_FAILED,
    CANCELLED,
    ERROR,
    ROTATION,
}

interface AiVoiceSessionController {
    fun onToolbarToggleRequested()
    /** Selects an enabled profile in the user's manual order; this never invokes rotation policy. */
    fun onManualProfileStepRequested(forward: Boolean)
    /** A non-terminal chunk was sealed; the toolbar timer starts the next chunk at this instant. */
    fun onNonTerminalChunkSealed(chunkStartedAtElapsedRealtime: Long)
    fun onInputInteraction()
    /** Called by the future session-owned silence detector; never directly by settings UI. */
    fun onSilenceTimeout()
    /** Called by the future session-owned maximum-duration timer; never directly by settings UI. */
    fun onMaximumDurationReached()
    fun onPauseRequested()
    fun onResumeRequested()
    /** The current editor is going away; never continue into a different app/editor. */
    fun onInputConnectionLost()
    fun onImeDestroyed()
}
