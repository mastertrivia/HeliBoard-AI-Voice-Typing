// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.SessionPolicySnapshot
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Owns one capture/dispatch session. It does not know the toolbar, config repository, or editor.
 * Its dependencies are session-scoped so a failed capture cannot leak into a future recording.
 */
class RecordingSession(
    val id: String,
    val profile: ApiProfile,
    val policy: SessionPolicySnapshot,
    private val recorder: AudioRecorder,
    private val assembler: ChunkAssembler,
    parentScope: CoroutineScope,
    private val dispatcherFactory: (CoroutineScope, SessionInsertionGate) -> TranscriptionDispatcher,
    private val onNonTerminalChunkSealed: (Long) -> Unit,
    private val onSilenceTimeout: () -> Unit,
    private val onMaximumDurationReached: () -> Unit,
    private val onCaptureFailure: () -> Unit,
) : AiVoiceSession {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val insertionGate = SessionInsertionGate()
    private val dispatcher by lazy { dispatcherFactory(scope, insertionGate) }
    private val closeMutex = Mutex()
    private var captureJob: Job? = null
    private var timeoutJob: Job? = null
    @Volatile private var closed = false
    @Volatile private var captureCancelled = false

    /** Returns only after AudioRecord has confirmed active capture for the runtime RECORDING state. */
    override suspend fun start(): Long {
        check(!closed && !captureCancelled) { "Recording session is closed" }
        check(captureJob == null) { "Recording session already started" }
        withContext(Dispatchers.IO) {
            recorder.initialize()
            check(!captureCancelled) { "Recording session capture was cancelled" }
            recorder.start()
        }
        check(!captureCancelled) { "Recording session capture was cancelled" }
        val startedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                recorder.read { frame ->
                    when (val result = assembler.accept(frame)) {
                        ChunkAssemblyResult.None -> Unit
                        is ChunkAssemblyResult.Chunk -> {
                            onNonTerminalChunkSealed(frame.capturedAtElapsedRealtime)
                            dispatcher.enqueue(result.chunk)
                        }
                        is ChunkAssemblyResult.TerminalChunk -> {
                            dispatcher.enqueue(result.chunk)
                            onSilenceTimeout()
                        }
                        ChunkAssemblyResult.TerminalNoAudio -> onSilenceTimeout()
                    }
                }
            } catch (_: Exception) {
                if (!closed) onCaptureFailure()
            }
        }
        policy.recordingTimeoutMillis?.let { timeoutMillis ->
            timeoutJob = scope.launch {
                delay(timeoutMillis)
                if (!closed) onMaximumDurationReached()
            }
        }
        return startedAtElapsedRealtime
    }

    override suspend fun stopGracefully() = close(graceful = true)
    override suspend fun cancelAndJoin() = close(graceful = false)
    /**
     * Synchronously fences insertion and releases capture before another microphone owner starts.
     * The controller still follows with [cancelAndJoin] to drain the session's jobs and files.
     */
    override fun cancelCaptureImmediately() {
        captureCancelled = true
        insertionGate.invalidate()
        runCatching { recorder.close() }
    }
    /** Called synchronously by the IME when the old editor is no longer valid. */
    override fun invalidateEditor() = insertionGate.invalidate()

    private suspend fun close(graceful: Boolean) {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            if (!graceful) insertionGate.invalidate()
            try {
                // stop() establishes the capture-drained boundary before final WAV sealing.
                runCatching { recorder.stop() }.onFailure {
                    Log.w(LOG_TAG, "Recording capture drain failed before finalization", it)
                }
                runCatching { timeoutJob?.cancelAndJoin() }
                runCatching { captureJob?.cancelAndJoin() }
                if (graceful) {
                    val finalChunk = runCatching { assembler.flushFinal() }
                        .onFailure { Log.w(LOG_TAG, "Recording final WAV seal failed after capture drain", it) }
                        .getOrNull()
                    if (finalChunk == null) Log.w(LOG_TAG, "Recording final capture produced no dispatchable WAV (empty or too short)")
                    finalChunk?.let { chunk ->
                        runCatching { dispatcher.enqueue(chunk) }
                            .onFailure { Log.w(LOG_TAG, "Recording final WAV enqueue failed", it) }
                    }
                    // The dispatcher explicitly owns accepted WAV work independently of sessionJob.
                    // A timed-out close returns, but the bounded worker finishes its current serial
                    // upload/fallback chain and then deterministically releases its own job.
                    if (withTimeoutOrNull(FINAL_DRAIN_TIMEOUT_MILLIS) { dispatcher.drain() } == null) {
                        insertionGate.invalidate()
                        Log.w(LOG_TAG, "Recording final dispatcher drain timed out; retained WAV remains owned by dispatcher")
                    }
                } else {
                    runCatching { dispatcher.cancelPending() }
                    runCatching { assembler.discard() }
                }
            } catch (_: Exception) {
                runCatching { dispatcher.cancelPending() }
                runCatching { assembler.discard() }
            } finally {
                runCatching { recorder.close() }
                runCatching { assembler.close() }
                sessionJob.cancel()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "AiVoiceRecording"
        const val FINAL_DRAIN_TIMEOUT_MILLIS = 45_000L
    }
}
