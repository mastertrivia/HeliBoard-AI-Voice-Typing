// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.SessionPolicySnapshot
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
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val insertionGate = SessionInsertionGate()
    private val dispatcher by lazy { dispatcherFactory(scope, insertionGate) }
    private val closeMutex = Mutex()
    private var captureJob: Job? = null
    private var timeoutJob: Job? = null
    @Volatile private var closed = false

    suspend fun start() {
        check(!closed) { "Recording session is closed" }
        check(captureJob == null) { "Recording session already started" }
        // Device allocation must complete before the controller publishes RECORDING to the toolbar.
        withContext(Dispatchers.IO) { recorder.initialize() }
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                recorder.start { frame ->
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
    }

    suspend fun stopGracefully() = close(graceful = true)
    suspend fun cancelAndJoin() = close(graceful = false)
    /** Called synchronously by the IME when the old editor is no longer valid. */
    fun invalidateEditor() = insertionGate.invalidate()

    private suspend fun close(graceful: Boolean) {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            if (!graceful) insertionGate.invalidate()
            try {
                // A broken recorder must not prevent queued work/files from being cancelled and closed.
                runCatching { recorder.stop() }
                runCatching { timeoutJob?.cancelAndJoin() }
                runCatching { captureJob?.cancelAndJoin() }
                if (graceful) {
                    runCatching { assembler.flushFinal() }.getOrNull()?.let { chunk ->
                        runCatching { dispatcher.enqueue(chunk) }
                    }
                    // The dispatcher worker is detached from the session scope, so a timed-out drain
                    // leaves it running in the background and never abandons an in-flight chunk.
                    // Provider calls stay bounded by the OkHttp call timeout, and the WAV is deleted
                    // only after a confirmed insertion, so nothing is silently dropped here.
                    runCatching { withTimeoutOrNull(FINAL_DRAIN_TIMEOUT_MILLIS) { dispatcher.drain() } }
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

    private companion object { const val FINAL_DRAIN_TIMEOUT_MILLIS = 45_000L }
}
