package helium314.keyboard.latin.aivoice.live

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiLiveStreamingSpeechProviderTest {
    @Test
    fun `uses exact constrained endpoint encoded token setup first and one socket`() = runBlocking {
        val factory = FakeFactory()
        val tokenValue = "ephemeral +/?&token"
        val session = provider(factory).openSession(token(tokenValue))

        assertEquals(1, factory.openCount)
        val uri = URI(factory.url)
        assertEquals("wss", uri.scheme)
        assertEquals("generativelanguage.googleapis.com", uri.host)
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained", uri.path)
        assertEquals(tokenValue, URLDecoder.decode(uri.rawQuery.substringAfter("access_token="), StandardCharsets.UTF_8.name()))
        assertFalse(factory.url.contains("+/?&token"))

        factory.open()
        val setup = Json.parseToJsonElement(factory.sent.single()).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.1-flash-live-preview", setup["model"]!!.jsonPrimitive.content)
        assertEquals("AUDIO", setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(setup["inputAudioTranscription"]!!.jsonObject.isEmpty())
        val vad = setup["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject
        assertEquals("START_SENSITIVITY_LOW", vad["startOfSpeechSensitivity"]!!.jsonPrimitive.content)
        assertEquals("END_SENSITIVITY_LOW", vad["endOfSpeechSensitivity"]!!.jsonPrimitive.content)
        session.cancel()
    }

    @Test
    fun `setup gates audio then preserves audio and end ordering`() = runBlocking {
        val factory = FakeFactory()
        val session = provider(factory).openSession(token())
        factory.open()
        val pending = async { session.sendPcm16Khz(byteArrayOf(1, 2, 3, 4)) }
        delay(50)
        assertFalse(pending.isCompleted)
        assertEquals(1, factory.sent.size)

        factory.message("""{"setupComplete":{}}""")
        pending.await()
        session.endAudio()
        awaitSent(factory, 3)
        val audio = Json.parseToJsonElement(factory.sent[1]).jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonArray.single().jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQIDBA==", audio["data"]!!.jsonPrimitive.content)
        assertEquals(true, Json.parseToJsonElement(factory.sent[2]).jsonObject["realtimeInput"]!!.jsonObject["audioStreamEnd"]!!.jsonPrimitive.content.toBoolean())
        session.endAudio()
        delay(20)
        assertEquals(3, factory.sent.size)
        session.close()
    }

    @Test
    fun `parses independent transcription all flags goAway errors and discards every generated audio part`() = runBlocking {
        val factory = FakeFactory()
        val session = provider(factory).openSession(token())
        factory.open()
        factory.message("""{"serverContent":{"inputTranscription":{"text":"hello"}}}""")
        factory.message("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAA"}},{"text":"ignored but visited"}]},"turnComplete":true,"generationComplete":true,"interrupted":true},"goAway":{"timeLeft":"3s"},"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")

        assertIs<StreamingSpeechEvent.InputTranscription>(receive(session)).also { assertEquals("hello", it.text); assertFalse(it.toString().contains("hello")) }
        assertIs<StreamingSpeechEvent.ProtocolError>(receive(session)).also { assertEquals(429, it.code) }
        assertIs<StreamingSpeechEvent.GoAway>(receive(session)).also { assertEquals("3s", it.timeLeft) }
        assertIs<StreamingSpeechEvent.TurnComplete>(receive(session))
        assertIs<StreamingSpeechEvent.GenerationComplete>(receive(session))
        assertIs<StreamingSpeechEvent.Interrupted>(receive(session))
        session.cancel()
    }

    @Test
    fun `bounded queue applies suspending backpressure and close cancel are idempotent`() = runBlocking {
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val factory = FakeFactory(blockAfterSetup = true, enteredSend = enteredSend, releaseSend = releaseSend)
        val session = provider(factory).openSession(token())
        factory.open()
        factory.message("""{"setupComplete":{}}""")
        receive(session)
        val sends = (0 until 10).map { async { session.sendPcm16Khz(byteArrayOf(it.toByte())) } }
        delay(100)
        assertTrue(sends.any { !it.isCompleted })
        releaseSend.countDown()
        sends.forEach { it.await() }
        session.close()
        session.close()
        session.cancel()
        assertEquals(1, factory.closeCount)
    }

    @Test
    fun `emits close and transport failure without leaking details`() = runBlocking {
        val closedFactory = FakeFactory()
        val closed = provider(closedFactory).openSession(token("secret-token"))
        closedFactory.open()
        closedFactory.closed(1001, "going away")
        assertIs<StreamingSpeechEvent.Closed>(receive(closed)).also { assertEquals(1001, it.code) }
        assertNull(closed.events.tryReceive().getOrNull())

        val failedFactory = FakeFactory()
        val failed = provider(failedFactory).openSession(token("another-secret"))
        failedFactory.open()
        failedFactory.failure()
        assertIs<StreamingSpeechEvent.ProtocolError>(receive(failed)).also { assertEquals("TRANSPORT_FAILURE", it.status) }
        assertFalse(token("secret-token").toString().contains("secret-token"))
        assertFalse(failedFactory.socket.toString().contains("another-secret"))
    }

    private fun provider(factory: FakeFactory) = GeminiLiveStreamingSpeechProvider(factory)
    private fun token(value: String = "memory-token") = GeminiLiveEphemeralToken(value, Long.MAX_VALUE, Long.MAX_VALUE, EnrolledInstallationTokenProvider.MODEL)
    private suspend fun receive(session: StreamingSpeechSession) = withTimeout(2_000) { session.events.receive() }
    private suspend fun awaitSent(factory: FakeFactory, count: Int) = withTimeout(2_000) { while (factory.sent.size < count) delay(5) }

    private class FakeFactory(
        private val blockAfterSetup: Boolean = false,
        private val enteredSend: CountDownLatch = CountDownLatch(0),
        private val releaseSend: CountDownLatch = CountDownLatch(0),
    ) : LiveWebSocketFactory {
        lateinit var url: String
        lateinit var listener: LiveWebSocketListener
        val sent = mutableListOf<String>()
        var openCount = 0
        var closeCount = 0
        val socket = object : LiveWebSocket {
            override fun send(text: String): Boolean {
                if (blockAfterSetup && sent.isNotEmpty()) {
                    enteredSend.countDown()
                    releaseSend.await(2, TimeUnit.SECONDS)
                }
                synchronized(sent) { sent += text }
                return true
            }
            override fun close(code: Int, reason: String?): Boolean { closeCount++; return true }
            override fun cancel() = Unit
            override fun toString() = "FakeSocket(<redacted>)"
        }
        override fun open(url: String, listener: LiveWebSocketListener): LiveWebSocket {
            this.url = url
            this.listener = listener
            openCount++
            return socket
        }
        fun open() = listener.onOpen(socket)
        fun message(value: String) = listener.onMessage(value)
        fun closed(code: Int, reason: String?) = listener.onClosed(code, reason)
        fun failure() = listener.onFailure()
    }
}
