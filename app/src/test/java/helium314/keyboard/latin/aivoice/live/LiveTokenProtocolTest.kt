package helium314.keyboard.latin.aivoice.live

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.ArrayDeque
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LiveTokenProtocolTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `canonical mint exactly matches backend fields and body hash`() {
        assertEquals(
            "POST\n/v1/gemini-live/session-token\n1700000000000\nnonce_1234567890\n" +
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\nkey-id",
            EnrolledInstallationTokenProvider.canonicalMint(
                "1700000000000", "nonce_1234567890", "{}".toByteArray(), "key-id",
            ),
        )
    }

    @Test
    fun `challenge enroll mint sequence signs challenge then canonical request and persists no token`() = runBlocking {
        val transport = FakeTransport(challenge(), enrolled(), minted("memory-only-token"))
        val signer = FakeSigner(initiallyUsable = false)
        val store = FakeStore()
        val provider = provider(transport, signer, store)

        val token = provider.acquire("https://voice.example.test")

        assertEquals(listOf(
            EnrolledInstallationTokenProvider.CHALLENGE_PATH,
            EnrolledInstallationTokenProvider.ENROLL_PATH,
            EnrolledInstallationTokenProvider.MINT_PATH,
        ), transport.paths)
        assertEquals("challenge-value-123456", signer.messages[0])
        assertEquals(
            EnrolledInstallationTokenProvider.canonicalMint(now.toString(), "nonce_1234567890", "{}".toByteArray(), KEY_ID),
            signer.messages[1],
        )
        assertEquals("memory-only-token", token.value)
        assertEquals(InstallationIdentity("backend-installation", KEY_ID), store.value)
        assertFalse(store.writes.joinToString().contains("memory-only-token"))
        assertFalse(token.toString().contains("memory-only-token"))
    }

    @Test
    fun `unknown key clears state and performs one serialized re-enrollment`() = runBlocking {
        val store = FakeStore(InstallationIdentity("old-installation", KEY_ID))
        val transport = FakeTransport(
            error(403, "installation_inactive"), challenge(), enrolled(), minted("fresh-token"),
        )
        val provider = provider(transport, FakeSigner(), store)

        assertEquals("fresh-token", provider.acquire("https://voice.example.test").value)
        assertEquals(1, store.clearCount)
        assertEquals(2, transport.paths.count { it == EnrolledInstallationTokenProvider.MINT_PATH })
        assertEquals(1, transport.paths.count { it == EnrolledInstallationTokenProvider.ENROLL_PATH })
    }

    @Test
    fun `replay rejection retries mint once with a fresh nonce`() = runBlocking {
        val transport = FakeTransport(error(409, "replay_detected"), minted("fresh-token"))
        val nonces = ArrayDeque(listOf("nonce_1234567890", "nonce_0987654321"))
        val provider = EnrolledInstallationTokenProvider(
            transport, FakeSigner(), FakeStore(InstallationIdentity("installed", KEY_ID)), { now }, { nonces.removeFirst() },
        )

        provider.acquire("https://voice.example.test")
        val mintRequests = transport.requests.filter { it.url.endsWith(EnrolledInstallationTokenProvider.MINT_PATH) }
        assertEquals(listOf("nonce_1234567890", "nonce_0987654321"), mintRequests.map { it.headers["X-Request-Nonce"] })
    }

    @Test
    fun `rejects invalid endpoints redirects schema response size and expiry`() = runBlocking {
        listOf("http://voice.example.test", "https://user:secret@voice.example.test", "https://voice.example.test/path", "https://voice.example.test?x=1")
            .forEach { endpoint ->
                assertFailsWith<LiveTokenProtocolException> {
                    provider(FakeTransport(), FakeSigner(), FakeStore()).acquire(endpoint)
                }
            }

        val cases = listOf(
            BackendResponse(302, ByteArray(0), "https://other.example.test/x"),
            BackendResponse(200, "{\"name\":\"x\",\"expireTime\":\"2023-11-14T22:18:20Z\"}".toByteArray()),
            BackendResponse(200, ByteArray(16_385) { 'x'.code.toByte() }),
            minted("x", expire = "2023-11-14T22:13:20Z", newSessionExpire = "2023-11-14T22:13:10Z"),
        )
        cases.forEach { response ->
            assertFailsWith<LiveTokenProtocolException> {
                provider(FakeTransport(response), FakeSigner(), FakeStore(InstallationIdentity("installed", KEY_ID)))
                    .acquire("https://voice.example.test")
            }
        }
    }

    private fun provider(transport: FakeTransport, signer: FakeSigner, store: FakeStore) = EnrolledInstallationTokenProvider(
        transport, signer, store, { now }, { "nonce_1234567890" },
    )

    private fun challenge() = response(201, """{"challengeId":"challenge-id","challenge":"challenge-value-123456","expiresAt":"2023-11-14T22:18:20Z"}""")
    private fun enrolled() = response(201, """{"installationId":"backend-installation","keyId":"$KEY_ID","status":"active"}""")
    private fun minted(token: String, expire: String = "2023-11-14T22:18:20Z", newSessionExpire: String = "2023-11-14T22:14:20Z") =
        response(200, """{"name":"$token","expireTime":"$expire","newSessionExpireTime":"$newSessionExpire"}""")
    private fun error(status: Int, code: String) = response(status, """{"error":{"code":"$code","message":"error"}}""")
    private fun response(status: Int, body: String) = BackendResponse(status, body.toByteArray())

    private class FakeTransport(vararg responses: BackendResponse) : InstallationBackendTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<BackendRequest>()
        val paths get() = requests.map { java.net.URI(it.url).path }
        override suspend fun post(request: BackendRequest): BackendResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeSigner(initiallyUsable: Boolean = true) : InstallationSigner {
        private var usable = initiallyUsable
        val messages = mutableListOf<String>()
        override suspend fun hasUsableKey() = usable
        override suspend fun resetKey() { usable = false }
        override suspend fun publicKeySpkiBase64(): String { usable = true; return "fake-spki" }
        override suspend fun signBase64Url(message: ByteArray): String {
            messages += message.toString(Charsets.UTF_8)
            return "der-signature-base64url"
        }
    }

    private class FakeStore(initial: InstallationIdentity? = null) : InstallationStateStore {
        var value = initial
        val writes = mutableListOf<InstallationIdentity>()
        var clearCount = 0
        override suspend fun read() = value
        override suspend fun write(value: InstallationIdentity) { this.value = value; writes += value }
        override suspend fun clear() { value = null; clearCount++ }
    }

    private companion object {
        const val KEY_ID = "backend-key-id-1234567890"
    }
}
