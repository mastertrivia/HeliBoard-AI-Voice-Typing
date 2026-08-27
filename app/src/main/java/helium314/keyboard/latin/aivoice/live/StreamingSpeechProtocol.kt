// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.live

import helium314.keyboard.latin.aivoice.language.KeyboardLanguageBehavior
import kotlinx.coroutines.channels.ReceiveChannel

/** Isolated streaming-speech contract. It owns no microphone, editor, controller, or persistence. */
interface StreamingSpeechProvider {
    suspend fun openSession(
        token: GeminiLiveEphemeralToken,
        languageBehavior: KeyboardLanguageBehavior = KeyboardLanguageBehavior.UNSPECIFIED,
    ): StreamingSpeechSession
}

interface StreamingSpeechSession : AutoCloseable {
    val events: ReceiveChannel<StreamingSpeechEvent>

    /** Suspends while the bounded outbound queue is full. Audio is rejected until setup completes. */
    suspend fun sendPcm16Khz(frame: ByteArray)

    /** Ends the current automatic-VAD audio stream; it does not close the WebSocket. */
    suspend fun endAudio()

    fun cancel()
    override fun close()
}

sealed interface StreamingSpeechEvent {
    data object SetupComplete : StreamingSpeechEvent
    data class InputTranscription(val text: String) : StreamingSpeechEvent {
        override fun toString() = "InputTranscription(<redacted>)"
    }
    data class OutputTranscription(val text: String) : StreamingSpeechEvent {
        override fun toString() = "OutputTranscription(<redacted>)"
    }
    data object TurnComplete : StreamingSpeechEvent
    data object GenerationComplete : StreamingSpeechEvent
    data object Interrupted : StreamingSpeechEvent
    data class GoAway(val timeLeft: String?) : StreamingSpeechEvent
    data class ProtocolError(val code: Int?, val status: String?) : StreamingSpeechEvent
    data class Closed(val code: Int, val reason: String?) : StreamingSpeechEvent
}

/** Small injectable boundary so protocol tests never need a network stack. */
interface LiveWebSocketFactory {
    fun open(url: String, listener: LiveWebSocketListener): LiveWebSocket
}

interface LiveWebSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?): Boolean
    fun cancel()
}

interface LiveWebSocketListener {
    fun onOpen(socket: LiveWebSocket)
    fun onMessage(text: String)
    fun onClosing(code: Int, reason: String?)
    fun onClosed(code: Int, reason: String?)
    fun onFailure()
}
