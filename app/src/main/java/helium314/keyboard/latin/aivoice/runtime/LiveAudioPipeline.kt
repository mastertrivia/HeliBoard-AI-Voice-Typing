// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.sqrt

internal class AndroidAudioRecorder : AudioRecorder {
    override val format = PcmFormat()
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var stopped = false

    override suspend fun initialize() {
        check(audioRecord == null) { "Audio recorder is already initialized" }
        val minBufferSize = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "16 kHz mono PCM is not supported" }
        val bufferSize = max(minBufferSize * 2, FRAME_BYTES * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            format.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord could not be initialized")
        }
        audioRecord = record
    }

    override suspend fun start(onFrame: suspend (AudioFrame) -> Unit) {
        val record = checkNotNull(audioRecord) { "Audio recorder is not initialized" }
        stopped = false
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not enter recording state" }
            val buffer = ByteArray(FRAME_BYTES)
            while (!stopped) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (stopped) break
                    throw IllegalStateException("AudioRecord read failed: $read")
                }
                onFrame(AudioFrame(buffer.copyOf(read), SystemClock.elapsedRealtime()))
            }
        } finally {
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        }
    }

    override suspend fun stop() {
        stopped = true
        audioRecord?.let { record ->
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        }
    }

    override fun close() {
        stopped = true
        audioRecord?.let { record ->
            runCatching { record.release() }
        }
        audioRecord = null
    }

    private companion object {
        const val FRAME_BYTES = 3_200
    }
}

/** Lightweight offline VAD for v1. It can be replaced by a stronger implementation later. */
internal class RmsVoiceActivityDetector : VoiceActivityDetector {
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var speaking = false

    override fun reset() {
        noiseFloor = INITIAL_NOISE_FLOOR
        speaking = false
    }

    override fun isSpeech(frame: AudioFrame, format: PcmFormat): Boolean {
        if (frame.bytes.size < 2) return false
        var energy = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < frame.bytes.size) {
            val sample = ((frame.bytes[index + 1].toInt() shl 8) or (frame.bytes[index].toInt() and 0xff)).toShort().toInt()
            energy += sample.toDouble() * sample.toDouble()
            sampleCount++
            index += 2
        }
        val rms = sqrt(energy / sampleCount.coerceAtLeast(1))
        val enterThreshold = max(noiseFloor * ENTER_MULTIPLIER, ABSOLUTE_FLOOR)
        val leaveThreshold = max(noiseFloor * LEAVE_MULTIPLIER, ABSOLUTE_FLOOR)
        speaking = if (speaking) rms >= leaveThreshold else rms >= enterThreshold
        if (!speaking) noiseFloor = noiseFloor * NOISE_SMOOTHING + rms * (1.0 - NOISE_SMOOTHING)
        return speaking
    }

    private companion object {
        const val INITIAL_NOISE_FLOOR = 100.0
        const val ABSOLUTE_FLOOR = 700.0
        const val ENTER_MULTIPLIER = 3.0
        const val LEAVE_MULTIPLIER = 1.8
        const val NOISE_SMOOTHING = 0.95
    }
}

internal class WavChunkAssembler(
    private val cacheRoot: File,
    private val sessionId: String,
    private val format: PcmFormat,
    private val detector: VoiceActivityDetector,
    private val autoSendSilenceDurationMillis: Long?,
    private val prolongedSilenceDurationMillis: Long?,
) : ChunkAssembler {
    private val sessionDirectory = File(cacheRoot, sessionId)
    private val preRoll = ArrayDeque<ByteArray>()
    private val pendingSilence = ArrayDeque<ByteArray>()
    private var writer: WavWriter? = null
    private var sequence = 0L
    private var speechBytes = 0L
    private var lastSpeechAtElapsedRealtime: Long? = null
    private var terminalSignalled = false

    override suspend fun accept(frame: AudioFrame): ChunkAssemblyResult {
        if (terminalSignalled) return ChunkAssemblyResult.None
        val speech = detector.isSpeech(frame, format)
        val activeWriter = writer
        if (activeWriter == null) {
            if (!speech) {
                if (hasReachedProlongedSilence(frame)) {
                    terminalSignalled = true
                    return ChunkAssemblyResult.TerminalNoAudio
                }
                addPreRoll(frame.bytes)
                return ChunkAssemblyResult.None
            }
            val newWriter = openWriter()
            preRoll.forEach(newWriter::write)
            preRoll.clear()
            newWriter.write(frame.bytes)
            writer = newWriter
            speechBytes = frame.bytes.size.toLong()
            lastSpeechAtElapsedRealtime = frame.capturedAtElapsedRealtime
            return ChunkAssemblyResult.None
        }

        if (speech) {
            flushPendingSilence(activeWriter)
            activeWriter.write(frame.bytes)
            speechBytes += frame.bytes.size
            lastSpeechAtElapsedRealtime = frame.capturedAtElapsedRealtime
        } else {
            // Keep silence only until we know whether speech resumes. A terminal silence window
            // is discarded so the final WAV ends at the last speech boundary.
            pendingSilence.addLast(frame.bytes.copyOf())
        }

        return when {
            hasReachedProlongedSilence(frame) -> finishCurrent(terminal = true)
            hasReachedAutoSendSilence(frame) -> finishCurrent(terminal = false)
            activeWriter.dataBytes >= MAX_CHUNK_BYTES -> finishCurrent(terminal = false)
            else -> ChunkAssemblyResult.None
        }
    }

    override suspend fun flushFinal(): AudioChunk? = when (val result = finishCurrent(terminal = false)) {
        is ChunkAssemblyResult.Chunk -> result.chunk
        is ChunkAssemblyResult.TerminalChunk -> result.chunk
        ChunkAssemblyResult.TerminalNoAudio, ChunkAssemblyResult.None -> null
    }

    override suspend fun discard() {
        runCatching { writer?.discard() }
        writer = null
        preRoll.clear()
        pendingSilence.clear()
        lastSpeechAtElapsedRealtime = null
        terminalSignalled = false
        sessionDirectory.deleteRecursively()
    }

    override fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun finishCurrent(terminal: Boolean): ChunkAssemblyResult {
        val currentWriter = writer ?: return ChunkAssemblyResult.None
        writer = null
        pendingSilence.clear()
        if (terminal) terminalSignalled = true
        val chunk = if (speechBytes >= MIN_SPEECH_BYTES) currentWriter.finish(sequence++) else {
            currentWriter.discard()
            null
        }
        speechBytes = 0L
        return when {
            chunk == null && terminal -> ChunkAssemblyResult.TerminalNoAudio
            chunk == null -> ChunkAssemblyResult.None
            terminal -> ChunkAssemblyResult.TerminalChunk(chunk)
            else -> ChunkAssemblyResult.Chunk(chunk)
        }
    }

    /** Both clocks are anchored at the most recent actual speech frame, never at a chunk send. */
    private fun hasReachedAutoSendSilence(frame: AudioFrame): Boolean =
        autoSendSilenceDurationMillis != null &&
            lastSpeechAtElapsedRealtime?.let { frame.capturedAtElapsedRealtime - it >= autoSendSilenceDurationMillis } == true

    private fun hasReachedProlongedSilence(frame: AudioFrame): Boolean =
        prolongedSilenceDurationMillis != null &&
            lastSpeechAtElapsedRealtime?.let { frame.capturedAtElapsedRealtime - it >= prolongedSilenceDurationMillis } == true

    private fun addPreRoll(bytes: ByteArray) {
        preRoll.addLast(bytes.copyOf())
        var retained = preRoll.sumOf { it.size }
        while (retained > PRE_ROLL_BYTES) retained -= preRoll.removeFirst().size
    }

    private fun flushPendingSilence(target: WavWriter) {
        while (pendingSilence.isNotEmpty()) target.write(pendingSilence.removeFirst())
    }

    private fun openWriter(): WavWriter {
        check(sessionDirectory.exists() || sessionDirectory.mkdirs()) { "Could not create AI Voice cache directory" }
        return WavWriter(File(sessionDirectory, "${sequence}.wav"), format)
    }

    private companion object {
        const val PRE_ROLL_BYTES = 8_000
        const val MIN_SPEECH_BYTES = 16_000L
        const val MAX_CHUNK_BYTES = 1_440_000L
    }
}

internal class WavWriter(private val file: File, private val format: PcmFormat) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    var dataBytes = 0L
        private set

    init {
        output.setLength(0L)
        output.write(ByteArray(WAV_HEADER_BYTES))
    }

    fun write(bytes: ByteArray) {
        check(dataBytes + bytes.size <= Int.MAX_VALUE - 36L) { "WAV chunk is too large" }
        output.write(bytes)
        dataBytes += bytes.size
    }

    fun finish(sequence: Long): AudioChunk {
        check(dataBytes > 0L) { "WAV chunk is empty" }
        output.seek(0L)
        output.writeBytes("RIFF")
        output.writeIntLe((36L + dataBytes).toInt())
        output.writeBytes("WAVEfmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(format.channels)
        output.writeIntLe(format.sampleRateHz)
        val byteRate = format.sampleRateHz * format.channels * format.bitsPerSample / 8
        output.writeIntLe(byteRate)
        output.writeShortLe(format.channels * format.bitsPerSample / 8)
        output.writeShortLe(format.bitsPerSample)
        output.writeBytes("data")
        output.writeIntLe(dataBytes.toInt())
        close()
        val durationMillis = dataBytes * 1_000L / byteRate
        return AudioChunk(sequence, file, durationMillis)
    }

    fun discard() {
        close()
        file.delete()
    }

    override fun close() {
        runCatching { output.close() }
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private companion object { const val WAV_HEADER_BYTES = 44 }
}
