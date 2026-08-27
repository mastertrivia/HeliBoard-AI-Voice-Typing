// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageBehavior
import helium314.keyboard.latin.aivoice.live.GeminiLiveTokenProvider
import helium314.keyboard.latin.aivoice.live.StreamingSpeechEvent
import helium314.keyboard.latin.aivoice.live.StreamingSpeechProvider
import helium314.keyboard.latin.aivoice.live.StreamingSpeechSession
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated raw-PCM Gemini Live runtime. It never enters the Recording WAV/provider pipeline. */
class GeminiLiveSession(
    private val sessionId: String,
    private val profile: ApiProfile,
    private val diagnostics: AiDiagnosticsSink,
    private val backendBaseUrl: String,
    private val tokenProvider: GeminiLiveTokenProvider,
    private val streamingProvider: StreamingSpeechProvider,
    private val languageBehaviorProvider: () -> KeyboardLanguageBehavior,
    private val recorder: AudioRecorder,
    private val composer: LiveTranscriptComposer,
    parentScope: CoroutineScope,
    private val onMaximumDurationReached: () -> Unit,
    private val onCaptureFailure: () -> Unit,
    private val recordingTimeoutMillis: Long?,
    private val setupTimeoutMillis: Long = SETUP_TIMEOUT_MILLIS,
    private val finalDrainTimeoutMillis: Long = FINAL_DRAIN_TIMEOUT_MILLIS,
) : AiVoiceSession {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val closeMutex = Mutex()
    private val acceptingFrames = AtomicBoolean(false)
    private val setupComplete = CompletableDeferred<Unit>()
    private val finalBoundary = CompletableDeferred<Unit>()
    private val transcriptUpdates = Channel<Unit>(Channel.CONFLATED)
    private val receivedInputTranscription = AtomicBoolean(false)
    @Volatile private var stream: StreamingSpeechSession? = null
    private var eventJob: Job? = null
    private var captureJob: Job? = null
    private var timeoutJob: Job? = null
    @Volatile private var closed = false
    @Volatile private var cancelled = false

    override suspend fun start(): Long {
        check(!closed && !cancelled) { "Live session is closed" }
        val token = tokenProvider.acquire(backendBaseUrl)
        check(!cancelled) { "Live session was cancelled" }
        val opened = streamingProvider.openSession(token, languageBehaviorProvider())
        stream = opened
        eventJob = scope.launch { consumeEvents(opened) }
        try {
            withTimeout(setupTimeoutMillis) { setupComplete.await() }
            check(!cancelled) { "Live session was cancelled" }
            withContext(Dispatchers.IO) {
                recorder.initialize()
                check(!cancelled) { "Live session capture was cancelled" }
                recorder.start()
            }
            check(!cancelled) { "Live session capture was cancelled" }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            acceptingFrames.set(true)
            captureJob = scope.launch(Dispatchers.IO) {
                try {
                    recorder.read { frame ->
                        if (acceptingFrames.get()) opened.sendPcm16Khz(frame.bytes)
                    }
                } catch (_: Exception) {
                    if (!closed && !cancelled) onCaptureFailure()
                }
            }
            recordingTimeoutMillis?.let { timeout ->
                timeoutJob = scope.launch {
                    delay(timeout)
                    if (!closed && !cancelled) onMaximumDurationReached()
                }
            }
            return startedAt
        } catch (failure: Exception) {
            cancelCaptureImmediately()
            runCatching { eventJob?.cancelAndJoin() }
            sessionJob.cancel()
            throw failure
        }
    }

    override suspend fun stopGracefully() = close(graceful = true)
    override suspend fun cancelAndJoin() = close(graceful = false)

    override fun cancelCaptureImmediately() {
        cancelled = true
        acceptingFrames.set(false)
        composer.invalidate()
        runCatching { recorder.close() }
        runCatching { stream?.cancel() }
    }

    override fun invalidateEditor() {
        cancelled = true
        composer.invalidate()
    }

    override fun onInputInteraction() = composer.invalidate()

    private suspend fun close(graceful: Boolean) {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            if (!graceful) {
                cancelled = true
                acceptingFrames.set(false)
            }
            try {
                // Keep accepting frames through recorder.stop(): it is the capture-drained boundary.
                runCatching { recorder.stop() }
                    .onFailure { Log.w(LOG_TAG, "Live capture drain failed before audio stream end", it) }
                runCatching { timeoutJob?.cancelAndJoin() }
                runCatching { captureJob?.cancelAndJoin() }
                acceptingFrames.set(false)
                if (graceful && !cancelled) {
                    val activeStream = stream
                    if (activeStream != null) {
                        runCatching { activeStream.endAudio() }
                            .onFailure {
                                Log.w(LOG_TAG, "Live audio stream end failed after capture drain", it)
                                emit(DiagnosticLevel.ERROR, "AI-0504", "stage=end_audio | cause=stream_end_failed")
                            }
                        awaitFinalDrain()
                    }
                    if (!cancelled && !composer.finalizeLatest()) {
                        Log.w(LOG_TAG, "Live final transcription was rejected by the composer/editor")
                        emit(DiagnosticLevel.WARNING, "AI-0603", "stage=live_finalize | cause=editor_rejected")
                    }
                } else {
                    composer.cancel()
                }
            } finally {
                if (!graceful || cancelled) runCatching { stream?.cancel() }
                else runCatching { stream?.close() }
                runCatching { eventJob?.cancelAndJoin() }
                runCatching { recorder.close() }
                transcriptUpdates.close()
                sessionJob.cancel()
            }
        }
    }

    private suspend fun consumeEvents(activeStream: StreamingSpeechSession) {
        for (event in activeStream.events) {
            when (event) {
                StreamingSpeechEvent.SetupComplete -> setupComplete.complete(Unit)
                is StreamingSpeechEvent.InputTranscription -> acceptTranscript(event.text)
                is StreamingSpeechEvent.OutputTranscription -> Unit
                StreamingSpeechEvent.TurnComplete,
                StreamingSpeechEvent.GenerationComplete -> finalBoundary.complete(Unit)
                is StreamingSpeechEvent.ProtocolError -> {
                    Log.w(LOG_TAG, "Live protocol error: stage=live_protocol, code=${event.code ?: "unknown"}")
                    emit(DiagnosticLevel.ERROR, "AI-0505", "stage=live_protocol | code=${event.code ?: "unknown"}")
                    if (!setupComplete.isCompleted) setupComplete.completeExceptionally(IllegalStateException("Live stream ended before setup"))
                    finalBoundary.complete(Unit)
                }
                is StreamingSpeechEvent.Closed -> {
                    Log.w(LOG_TAG, "Live stream closed: stage=live_closed, code=${event.code}")
                    emit(DiagnosticLevel.WARNING, "AI-0506", "stage=live_closed | code=${event.code}")
                    if (!setupComplete.isCompleted) setupComplete.completeExceptionally(IllegalStateException("Live stream ended before setup"))
                    finalBoundary.complete(Unit)
                }
                StreamingSpeechEvent.Interrupted,
                is StreamingSpeechEvent.GoAway -> Unit
            }
        }
        if (!setupComplete.isCompleted) setupComplete.completeExceptionally(IllegalStateException("Live stream ended before setup"))
        finalBoundary.complete(Unit)
    }

    private suspend fun acceptTranscript(text: String) {
        if (!cancelled && text.isNotBlank()) {
            receivedInputTranscription.set(true)
            if (composer.update(text)) transcriptUpdates.trySend(Unit)
            else {
                Log.w(LOG_TAG, "Live input transcription was rejected by the composer/editor")
                emit(DiagnosticLevel.WARNING, "AI-0603", "stage=live_update | cause=editor_rejected")
            }
        }
    }

    /** Boundary and transcription are independent; retain updates arriving shortly after the boundary. */
    private suspend fun awaitFinalDrain() {
        val drainCompleted = withTimeoutOrNull(finalDrainTimeoutMillis) {
            finalBoundary.await()
            while (withTimeoutOrNull(FINAL_TRANSCRIPT_QUIET_MILLIS) { transcriptUpdates.receive() } != null) Unit
            true
        } == true
        if (!drainCompleted) {
            Log.w(LOG_TAG, "Live final drain timed out waiting for stream completion")
            emit(DiagnosticLevel.WARNING, "AI-0504", "stage=final_drain | cause=timeout")
        }
        if (!receivedInputTranscription.get()) {
            Log.w(LOG_TAG, "Live stream completed without an input transcription")
            emit(DiagnosticLevel.WARNING, "AI-0601", "stage=live_input_transcription | cause=missing")
        }
    }

    private fun emit(level: DiagnosticLevel, code: String, reason: String) {
        runCatching {
            diagnostics.emit(AiDiagnosticEvent(
                level = level,
                code = code,
                sessionId = sessionId,
                profileSerial = profile.serialNumber,
                providerId = profile.providerId,
                modelId = profile.modelId,
                message = "Gemini Live terminal event",
                reason = reason,
            ))
        }
    }

    private companion object {
        const val LOG_TAG = "AiVoiceLive"
        const val SETUP_TIMEOUT_MILLIS = 15_000L
        const val FINAL_DRAIN_TIMEOUT_MILLIS = 8_000L
        const val FINAL_TRANSCRIPT_QUIET_MILLIS = 250L
    }
}
