// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.SessionPolicySnapshot
import helium314.keyboard.latin.aivoice.domain.VoiceMode
import helium314.keyboard.keyboard.KeyboardSwitcher
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** The sole adapter between HeliBoard's IME lifecycle and the isolated AI Voice module. */
object AiVoiceImeBridge {
    @JvmStatic
    fun create(ime: InputMethodService): AiVoiceSessionController {
        val dependencies = AiVoiceDependencies.get()
        val chunkRoot = File(ime.cacheDir, "ai_voice_chunks").also { root ->
            // A prior process can never finish or safely upload abandoned microphone audio.
            root.deleteRecursively()
            root.mkdirs()
        }
        val inserter = LatinImeTranscriptInserter(ime)
        var controller: AiVoiceSessionController? = null
        controller = DefaultAiVoiceSessionController(
            repository = dependencies.settingsRepository,
            runtimeState = dependencies.runtimeState,
            diagnostics = dependencies.diagnostics,
            rotationCoordinator = dependencies.rotationCoordinator,
            profileEligibility = dependencies.profileEligibility,
            hasMicrophonePermission = {
                ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            createSession = { sessionId: String, profile: ApiProfile, policy: SessionPolicySnapshot, parentScope: CoroutineScope ->
                val recorder = AndroidAudioRecorder()
                when (profile.mode) {
                    VoiceMode.LIVE -> GeminiLiveSession(
                        sessionId = sessionId,
                        profile = profile,
                        diagnostics = dependencies.diagnostics,
                        backendBaseUrl = checkNotNull(profile.liveBackendBaseUrl),
                        tokenProvider = dependencies.liveTokenProvider,
                        streamingProvider = dependencies.liveStreamingProvider,
                        languageBehaviorProvider = { dependencies.transcriptionRequestFactory.currentBehavior() },
                        recorder = recorder,
                        composer = LatinImeLiveTranscriptComposer(ime),
                        parentScope = parentScope,
                        onMaximumDurationReached = { controller?.onMaximumDurationReached() },
                        onCaptureFailure = { controller?.onPauseRequested() },
                        recordingTimeoutMillis = policy.recordingTimeoutMillis,
                    )
                    VoiceMode.RECORDING -> {
                        val assembler = WavChunkAssembler(
                            cacheRoot = chunkRoot,
                            sessionId = sessionId,
                            format = recorder.format,
                            detector = RmsVoiceActivityDetector(),
                            autoSendSilenceDurationMillis = policy.silenceDurationMillis,
                            prolongedSilenceDurationMillis = policy.prolongedSilenceDurationMillis,
                        )
                        RecordingSession(
                            id = sessionId,
                            profile = profile,
                            policy = policy,
                            recorder = recorder,
                            assembler = assembler,
                            parentScope = parentScope,
                            dispatcherFactory = { sessionScope, insertionGate ->
                                SerialTranscriptionDispatcher(
                                    sessionId = sessionId,
                                    profile = profile,
                                    providerResolver = dependencies.providerResolver,
                                    requestFactory = dependencies.transcriptionRequestFactory,
                                    inserter = inserter,
                                    insertionGate = insertionGate,
                                    rotationCoordinator = dependencies.rotationCoordinator,
                                    diagnostics = dependencies.diagnostics,
                                    parentScope = sessionScope,
                                )
                            },
                            onNonTerminalChunkSealed = { chunkStartedAtElapsedRealtime ->
                                controller?.onNonTerminalChunkSealed(chunkStartedAtElapsedRealtime)
                            },
                            onSilenceTimeout = { controller?.onSilenceTimeout() },
                            onMaximumDurationReached = { controller?.onMaximumDurationReached() },
                            onCaptureFailure = { controller?.onPauseRequested() },
                        )
                    }
                }
            },
            onManualProfileSelected = { profile ->
                KeyboardSwitcher.getInstance().showToast(
                    ime.getString(R.string.ai_voice_profile_selected, profile.serialNumber, profile.displayName),
                    true,
                )
            },
        )
        return checkNotNull(controller)
    }
}
