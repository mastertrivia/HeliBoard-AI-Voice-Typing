// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import android.os.SystemClock
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.SessionPolicySnapshot
import helium314.keyboard.latin.aivoice.domain.sessionPolicy
import helium314.keyboard.latin.aivoice.provider.ProviderResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

/** IME-owned state machine. Every terminal trigger is serialized through one session finish path. */
class DefaultAiVoiceSessionController(
    private val repository: AiVoiceSettingsRepository,
    private val runtimeState: AiVoiceRuntimeStateHolder,
    private val diagnostics: AiDiagnosticsSink,
    private val rotationCoordinator: RotationCoordinator,
    private val providerResolver: ProviderResolver,
    private val hasMicrophonePermission: () -> Boolean,
    private val createSession: (String, ApiProfile, SessionPolicySnapshot, CoroutineScope) -> RecordingSession,
    private val onManualProfileSelected: (ApiProfile) -> Unit = {},
) : AiVoiceSessionController {
    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var state: SessionState = SessionState.Idle
    private var session: RecordingSession? = null
    @Volatile private var drainingSession: RecordingSession? = null
    private var destroyed = false
    private val commandJob: Job = controllerScope.launch {
        for (command in commands) {
            try {
                reduce(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recoverFromUnexpectedCommandFailure()
            }
        }
    }

    override fun onToolbarToggleRequested() = enqueue(Command.Toggle)
    override fun onManualProfileStepRequested(forward: Boolean) = enqueue(Command.ManualProfileStep(forward))
    override fun onNonTerminalChunkSealed(chunkStartedAtElapsedRealtime: Long) =
        enqueue(Command.NonTerminalChunkSealed(chunkStartedAtElapsedRealtime))
    override fun onInputInteraction() = enqueue(Command.InputInteraction)
    override fun onSilenceTimeout() = enqueue(Command.SilenceTimeout)
    override fun onMaximumDurationReached() = enqueue(Command.MaximumDuration)
    override fun onPauseRequested() = enqueue(Command.Pause)
    override fun onResumeRequested() = enqueue(Command.Resume)
    override fun onInputConnectionLost() {
        // Do not wait behind a graceful provider drain before fencing the old editor.
        session?.invalidateEditor()
        drainingSession?.invalidateEditor()
        enqueue(Command.ConnectionLost)
    }
    override fun onImeDestroyed() {
        session?.invalidateEditor()
        drainingSession?.invalidateEditor()
        enqueue(Command.Destroy)
    }

    private fun enqueue(command: Command) {
        if (!destroyed) commands.trySend(command)
    }

    private suspend fun reduce(command: Command) {
        when (command) {
            Command.Toggle -> when (state) {
                SessionState.Idle -> startSession()
                is SessionState.Starting, is SessionState.Recording, is SessionState.Paused -> finishSession(TerminationReason.MANUAL_STOP)
                is SessionState.Stopping -> Unit
            }
            is Command.ManualProfileStep -> selectAdjacentManualProfile(command.forward)
            is Command.NonTerminalChunkSealed -> (state as? SessionState.Recording)?.let { recording ->
                runtimeState.publish(AiVoiceRuntimeState(
                    connection = AiVoiceConnectionStatus.CONNECTED,
                    recording = AiVoiceRecordingState.RECORDING,
                    sessionId = recording.sessionId,
                    profileId = recording.profileId,
                    startedAtElapsedRealtime = recording.startedAtElapsedRealtime,
                    chunkStartedAtElapsedRealtime = command.chunkStartedAtElapsedRealtime,
                    languageModeLabel = "Keyboard language",
                    chunkModeLabel = "Sequential WAV chunks",
                ))
            }
            Command.InputInteraction -> (state as? SessionState.Recording)
                ?.takeIf { !it.policy.independentMicAndKeyboard }
                ?.let { finishSession(TerminationReason.INPUT_INTERACTION) }
            Command.SilenceTimeout -> if (state is SessionState.Recording) finishSession(TerminationReason.SILENCE_TIMEOUT)
            Command.MaximumDuration -> if (state is SessionState.Recording) finishSession(TerminationReason.MAXIMUM_DURATION)
            Command.ConnectionLost -> if (state !is SessionState.Idle) finishSession(TerminationReason.CANCELLED)
            Command.Pause -> if (state is SessionState.Recording) {
                emitSafely(DiagnosticLevel.ERROR, "AI-0311")
                finishSession(TerminationReason.ERROR)
            }
            Command.Resume -> Unit
            Command.Destroy -> destroy()
        }
    }

    private suspend fun startSession() {
        if (!hasMicrophonePermission()) {
            emitSafely(DiagnosticLevel.WARNING, "AI-0103")
            publishIdle()
            return
        }
        val config = repository.config.value
        val profile = rotationCoordinator.resolveProfileForNewSession(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (profile == null) {
            emitSafely(DiagnosticLevel.WARNING, "AI-0102")
            publishIdle()
            return
        }
        if (!providerResolver.isProfileUsable(profile)) {
            publishIdle(errorCode = "AI-0505")
            return
        }
        val policy = config.sessionPolicy()
        val sessionId = UUID.randomUUID().toString()
        state = SessionState.Starting(sessionId, profile.id, policy)
        runtimeState.publish(AiVoiceRuntimeState(
            connection = AiVoiceConnectionStatus.IDLE,
            recording = AiVoiceRecordingState.STARTING,
            sessionId = sessionId,
            profileId = profile.id,
            languageModeLabel = "Keyboard language",
            chunkModeLabel = "Sequential WAV chunks",
        ))
        var recordingSession: RecordingSession? = null
        try {
            recordingSession = createSession(sessionId, profile, policy, controllerScope)
            session = recordingSession
            recordingSession.start()
            val startedAt = SystemClock.elapsedRealtime()
            state = SessionState.Recording(sessionId, profile.id, startedAt, policy)
            runtimeState.publish(AiVoiceRuntimeState(
                connection = AiVoiceConnectionStatus.CONNECTED,
                recording = AiVoiceRecordingState.RECORDING,
                sessionId = sessionId,
                profileId = profile.id,
                startedAtElapsedRealtime = startedAt,
                chunkStartedAtElapsedRealtime = startedAt,
                languageModeLabel = "Keyboard language",
                chunkModeLabel = "Sequential WAV chunks",
            ))
        } catch (_: Exception) {
            runCatching { recordingSession?.cancelAndJoin() }
            session = null
            state = SessionState.Idle
            emitSafely(DiagnosticLevel.ERROR, "AI-0310", sessionId, profile)
            publishIdle(errorCode = "AI-0310")
        }
    }

    /** Reuses repository-backed manual selection; automatic rotation is intentionally not consulted. */
    private suspend fun selectAdjacentManualProfile(forward: Boolean) {
        val config = repository.config.value
        val enabledProfiles = config.profiles.filter { it.enabled }
        if (enabledProfiles.isEmpty()) return
        val recordingActive = state is SessionState.Recording || state is SessionState.Paused
        val selectedId = if (recordingActive) config.pendingProfileId ?: config.activeProfileId else config.activeProfileId
        val selectedIndex = enabledProfiles.indexOfFirst { it.id == selectedId }
        val targetIndex = when {
            selectedIndex < 0 && forward -> 0
            selectedIndex < 0 -> enabledProfiles.lastIndex
            forward -> (selectedIndex + 1) % enabledProfiles.size
            else -> (selectedIndex - 1 + enabledProfiles.size) % enabledProfiles.size
        }
        val target = enabledProfiles[targetIndex]
        if (target.id == selectedId) return
        try {
            repository.selectActiveProfile(target.id, recordingActive)
            runCatching { onManualProfileSelected(target) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Profile switching is optional; a rejected/persistence-failed selection must not affect a session.
            emitSafely(DiagnosticLevel.WARNING, "AI-0204", profile = target)
        }
    }

    private suspend fun finishSession(reason: TerminationReason) {
        val previousState = state
        val recordingState = previousState as? SessionState.Recording
        val sessionId = when (previousState) {
            is SessionState.Starting -> previousState.sessionId
            is SessionState.Recording -> previousState.sessionId
            is SessionState.Paused -> previousState.sessionId
            is SessionState.Stopping -> previousState.sessionId
            SessionState.Idle -> null
        }
        if (sessionId == null) {
            publishIdle()
            return
        }
        state = SessionState.Stopping(sessionId, reason)
        runtimeState.publish(AiVoiceRuntimeState(recording = AiVoiceRecordingState.STOPPING, sessionId = sessionId))
        val closingSession = session
        session = null
        drainingSession = closingSession
        try {
            if (reason == TerminationReason.IME_DESTROYED || reason == TerminationReason.CANCELLED) {
                runCatching { closingSession?.cancelAndJoin() }
            } else {
                runCatching { closingSession?.stopGracefully() }
            }
        } finally {
            drainingSession = null
        }
        recordingState?.let {
            val duration = (SystemClock.elapsedRealtime() - it.startedAtElapsedRealtime).coerceAtLeast(0L)
            runCatching { rotationCoordinator.recordActiveDuration(it.profileId, duration) }
            runCatching { rotationCoordinator.completeSession(it.profileId, System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
        }
        state = SessionState.Idle
        publishIdle()
    }

    private suspend fun destroy() {
        if (destroyed) return
        destroyed = true
        commands.close()
        finishSession(TerminationReason.IME_DESTROYED)
        controllerJob.cancel()
    }

    private suspend fun recoverFromUnexpectedCommandFailure() {
        val activeSession = session
        session = null
        drainingSession = null
        runCatching { activeSession?.cancelAndJoin() }
        state = SessionState.Idle
        publishIdle(errorCode = "AI-0109")
        emitSafely(DiagnosticLevel.ERROR, "AI-0109")
    }

    private fun publishIdle(errorCode: String? = null) {
        runCatching { runtimeState.publish(AiVoiceRuntimeState(lastErrorCode = errorCode)) }
    }

    private fun emitSafely(level: DiagnosticLevel, code: String, sessionId: String? = null, profile: ApiProfile? = null) {
        runCatching {
            diagnostics.emit(AiDiagnosticEvent(
                level = level,
                code = code,
                sessionId = sessionId,
                profileSerial = profile?.serialNumber,
                providerId = profile?.providerId,
                modelId = profile?.modelId,
                message = "AI Voice controller event",
            ))
        }
    }

    private sealed interface Command {
        data object Toggle : Command
        data class ManualProfileStep(val forward: Boolean) : Command
        data class NonTerminalChunkSealed(val chunkStartedAtElapsedRealtime: Long) : Command
        data object InputInteraction : Command
        data object SilenceTimeout : Command
        data object MaximumDuration : Command
        data object Pause : Command
        data object Resume : Command
        data object ConnectionLost : Command
        data object Destroy : Command
    }
}
