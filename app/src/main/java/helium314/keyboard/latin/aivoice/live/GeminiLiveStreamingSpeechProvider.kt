// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.live

import helium314.keyboard.latin.aivoice.language.KeyboardLanguageBehavior
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GeminiLiveStreamingSpeechProvider(
    private val webSockets: LiveWebSocketFactory,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
) : StreamingSpeechProvider {
    override suspend fun openSession(
        token: GeminiLiveEphemeralToken,
        languageBehavior: KeyboardLanguageBehavior,
    ): StreamingSpeechSession {
        require(token.model == MODEL) { "Unexpected live model" }
        var tokenReference: String? = token.value
        try {
            require(tokenReference!!.isNotBlank() && tokenReference!!.length <= MAX_TOKEN_CHARS) { "Invalid live token" }
            val encoded = URLEncoder.encode(tokenReference, StandardCharsets.UTF_8.name()).replace("+", "%20")
            val session = GeminiLiveStreamingSpeechSession(
                webSockets,
                "$ENDPOINT?access_token=$encoded",
                json,
                scopeFactory(),
                setupPayload(),
            )
            session.connect()
            return session
        } finally {
            tokenReference = null
        }
    }

    private fun setupPayload(): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", MODEL)
            putJsonObject("generationConfig") { putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) } }
            put("inputAudioTranscription", buildJsonObject { })
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", 100)
                    put("silenceDurationMs", 1000)
                }
            }
        }
    }.toString()

    private companion object {
        const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
        const val MODEL = "models/gemini-3.1-flash-live-preview"
        const val MAX_TOKEN_CHARS = 8_192
    }
}

private class GeminiLiveStreamingSpeechSession(
    private val factory: LiveWebSocketFactory,
    url: String,
    private val json: Json,
    private val scope: CoroutineScope,
    private val setupPayload: String,
) : StreamingSpeechSession, LiveWebSocketListener {
    override val events = Channel<StreamingSpeechEvent>(EVENT_CAPACITY)
    private val outbound = Channel<Outbound>(OUTBOUND_CAPACITY)
    private val setupComplete = CompletableDeferred<Unit>()
    private val terminated = AtomicBoolean(false)
    private val endSent = AtomicBoolean(false)
    @Volatile private var socket: LiveWebSocket? = null
    private var connectionUrl: String? = url

    fun connect() {
        check(socket == null) { "Session already connected" }
        val target = checkNotNull(connectionUrl)
        connectionUrl = null
        socket = factory.open(target, this)
        scope.launch {
            for (message in outbound) {
                val sent = socket?.send(message.payload) == true
                if (!sent) {
                    failAndCancel()
                    break
                }
            }
        }
    }

    override fun onOpen(socket: LiveWebSocket) {
        if (terminated.get()) return socket.cancel()
        this.socket = socket
        if (!socket.send(setupPayload)) failAndCancel()
    }

    override suspend fun sendPcm16Khz(frame: ByteArray) {
        setupComplete.await()
        check(!terminated.get()) { "Streaming session is closed" }
        require(frame.isNotEmpty() && frame.size <= MAX_PCM_FRAME_BYTES) { "PCM frame size is outside protocol limits" }
        check(!endSent.get()) { "Audio stream has ended" }
        val copied = frame.copyOf()
        val payload = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("audio") {
                    add(buildJsonObject {
                        put("mimeType", PCM_MIME)
                        put("data", Base64.getEncoder().encodeToString(copied))
                    })
                }
            }
        }.toString()
        check(payload.length <= MAX_OUTBOUND_TEXT_CHARS) { "Encoded audio payload is too large" }
        outbound.send(Outbound(payload))
        copied.fill(0)
    }

    override suspend fun endAudio() {
        setupComplete.await()
        check(!terminated.get()) { "Streaming session is closed" }
        if (endSent.compareAndSet(false, true)) outbound.send(Outbound(AUDIO_STREAM_END_PAYLOAD))
    }

    override fun onMessage(text: String) {
        if (text.length > MAX_INBOUND_TEXT_CHARS) return failAndCancel()
        val root = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return emitError(null, "MALFORMED_RESPONSE") }

        if (root["setupComplete"] is JsonObject && !setupComplete.isCompleted) {
            setupComplete.complete(Unit)
            emit(StreamingSpeechEvent.SetupComplete)
        }
        root["error"]?.asObject()?.let { emitError(it["code"]?.asPrimitive()?.intOrNull, it["status"]?.asPrimitive()?.contentOrNull) }
        root["goAway"]?.asObject()?.let { emit(StreamingSpeechEvent.GoAway(it["timeLeft"]?.asPrimitive()?.contentOrNull)) }

        root["serverContent"]?.asObject()?.let { content ->
            content["inputTranscription"]?.asObject()?.get("text")?.asPrimitive()?.contentOrNull?.let {
                emitTranscript(it)
            }
            // Visit every part. Generated audio is deliberately ignored without Base64 decoding.
            content["modelTurn"]?.asObject()?.get("parts")?.let { parts ->
                runCatching { parts.jsonArray }.getOrNull()?.forEach(::discardGeneratedPart)
            }
            if (content["turnComplete"].isTrue()) emit(StreamingSpeechEvent.TurnComplete)
            if (content["generationComplete"].isTrue()) emit(StreamingSpeechEvent.GenerationComplete)
            if (content["interrupted"].isTrue()) emit(StreamingSpeechEvent.Interrupted)
        }
    }

    private fun emitTranscript(text: String) {
        if (text.length > MAX_TRANSCRIPT_CHARS) return emitError(null, "TRANSCRIPT_TOO_LARGE")
        emit(StreamingSpeechEvent.InputTranscription(text))
    }

    private fun discardGeneratedPart(part: JsonElement) {
        val objectPart = part as? JsonObject ?: return
        objectPart["inlineData"]?.asObject() // intentionally inspected, never decoded or retained
    }

    override fun onClosing(code: Int, reason: String?) {
        socket?.close(code, null)
    }

    override fun onClosed(code: Int, reason: String?) = terminate(StreamingSpeechEvent.Closed(code, reason?.take(MAX_REASON_CHARS)))

    override fun onFailure() = terminate(StreamingSpeechEvent.ProtocolError(null, "TRANSPORT_FAILURE"))

    override fun close() {
        if (terminated.compareAndSet(false, true)) {
            setupComplete.cancel()
            outbound.close()
            socket?.close(NORMAL_CLOSE, null)
            events.close()
            socket = null
            scope.cancel()
        }
    }

    override fun cancel() {
        if (terminated.compareAndSet(false, true)) {
            setupComplete.cancel()
            outbound.close()
            socket?.cancel()
            events.close()
            socket = null
            scope.cancel()
        }
    }

    private fun failAndCancel() {
        emitError(null, "OUTBOUND_REJECTED")
        cancel()
    }

    private fun terminate(event: StreamingSpeechEvent) {
        if (terminated.compareAndSet(false, true)) {
            setupComplete.cancel()
            outbound.close()
            emit(event)
            events.close()
            socket = null
            scope.cancel()
        }
    }

    private fun emitError(code: Int?, status: String?) = emit(StreamingSpeechEvent.ProtocolError(code, status?.take(MAX_STATUS_CHARS)))
    private fun emit(event: StreamingSpeechEvent) { events.trySend(event) }

    private data class Outbound(val payload: String) {
        override fun toString() = "Outbound(<redacted>)"
    }

    private companion object {
        const val OUTBOUND_CAPACITY = 8
        const val EVENT_CAPACITY = 32
        const val MAX_PCM_FRAME_BYTES = 32_000
        const val MAX_OUTBOUND_TEXT_CHARS = 64_000
        const val MAX_INBOUND_TEXT_CHARS = 256_000
        const val MAX_TRANSCRIPT_CHARS = 32_000
        const val MAX_REASON_CHARS = 256
        const val MAX_STATUS_CHARS = 128
        const val NORMAL_CLOSE = 1000
        const val PCM_MIME = "audio/pcm;rate=16000"

        val AUDIO_STREAM_END_PAYLOAD = buildJsonObject { putJsonObject("realtimeInput") { put("audioStreamEnd", true) } }.toString()
    }
}

private fun JsonElement.asObject() = this as? JsonObject
private fun JsonElement.asPrimitive() = this as? JsonPrimitive
private fun JsonElement?.isTrue() = (this as? JsonPrimitive)?.booleanOrNull == true
