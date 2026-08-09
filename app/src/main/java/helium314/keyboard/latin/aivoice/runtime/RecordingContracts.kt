// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import java.io.Closeable
import java.io.File

data class PcmFormat(val sampleRateHz: Int = 16_000, val channels: Int = 1, val bitsPerSample: Int = 16)
data class AudioFrame(val bytes: ByteArray, val capturedAtElapsedRealtime: Long)
data class AudioChunk(val sequence: Long, val wavFile: File, val durationMillis: Long)

/** Thread-safe session token that immediately invalidates insertion after editor/IME loss. */
class SessionInsertionGate {
    @Volatile private var valid = true
    fun invalidate() { valid = false }
    fun allowsInsertion(): Boolean = valid
}

sealed interface ChunkAssemblyResult {
    data object None : ChunkAssemblyResult
    data class Chunk(val chunk: AudioChunk) : ChunkAssemblyResult
    /** The user has been continuously silent for the configured terminal threshold. */
    data class TerminalChunk(val chunk: AudioChunk) : ChunkAssemblyResult
    /** Terminal silence reached after a non-terminal chunk was already sealed. */
    data object TerminalNoAudio : ChunkAssemblyResult
}

/** Session-owned capture device. Implementations must release native microphone resources on [close]. */
interface AudioRecorder : Closeable {
    val format: PcmFormat
    /** Allocates and validates the capture device before the session becomes visible as recording. */
    suspend fun initialize()
    suspend fun start(onFrame: suspend (AudioFrame) -> Unit)
    suspend fun stop()
}

interface VoiceActivityDetector {
    fun reset()
    fun isSpeech(frame: AudioFrame, format: PcmFormat): Boolean
}

interface ChunkAssembler : Closeable {
    suspend fun accept(frame: AudioFrame): ChunkAssemblyResult
    suspend fun flushFinal(): AudioChunk?
    suspend fun discard()
}

interface TranscriptionDispatcher {
    suspend fun enqueue(chunk: AudioChunk)
    suspend fun drain()
    suspend fun cancelPending()
}
