// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.live

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest

/** A short-lived Gemini Live credential. It must remain in memory and must not be logged or persisted. */
class GeminiLiveEphemeralToken internal constructor(
    val value: String,
    val expireAtEpochMillis: Long,
    val newSessionExpireAtEpochMillis: Long,
    val model: String = EnrolledInstallationTokenProvider.MODEL,
) {
    override fun toString(): String = "GeminiLiveEphemeralToken(<redacted>)"
}

interface GeminiLiveTokenProvider {
    suspend fun acquire(baseUrl: String): GeminiLiveEphemeralToken
}

data class InstallationIdentity(val installationId: String, val keyId: String)

interface InstallationStateStore {
    suspend fun read(): InstallationIdentity?
    suspend fun write(value: InstallationIdentity)
    suspend fun clear()
}

interface InstallationSigner {
    suspend fun hasUsableKey(): Boolean
    suspend fun resetKey()
    suspend fun publicKeySpkiBase64(): String
    /** Returns a Base64URL (without padding) DER-encoded ECDSA signature. */
    suspend fun signBase64Url(message: ByteArray): String
}

data class BackendRequest(val url: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())
data class BackendResponse(val status: Int, val body: ByteArray, val redirectLocation: String? = null)

interface InstallationBackendTransport {
    suspend fun post(request: BackendRequest): BackendResponse
}

class LiveTokenProtocolException(val code: String, message: String = code) : Exception(message)

/** Implements the exact protocol currently served by backend/gemini-live-token/src/service.ts. */
class EnrolledInstallationTokenProvider(
    private val transport: InstallationBackendTransport,
    private val signer: InstallationSigner,
    private val stateStore: InstallationStateStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String,
    private val json: Json = Json,
) : GeminiLiveTokenProvider {
    private val mutex = Mutex()

    override suspend fun acquire(baseUrl: String): GeminiLiveEphemeralToken = mutex.withLock {
        val origin = validateOrigin(baseUrl)
        var state = stateStore.read()
        if (state == null || !signer.hasUsableKey()) {
            state = enroll(origin, replaceKey = !signer.hasUsableKey())
        }

        var response = mint(origin, state)
        val errorCode = response.errorCode()
        if (response.status == 403 && errorCode == "installation_inactive") {
            stateStore.clear()
            state = enroll(origin, replaceKey = false)
            response = mint(origin, state)
        } else if (response.status == 409 && errorCode == "replay_detected") {
            // A rejected replay did not mint a token. A single request with a fresh nonce is safe.
            response = mint(origin, state)
        }
        parseMint(response)
    }

    private suspend fun enroll(origin: String, replaceKey: Boolean): InstallationIdentity {
        if (replaceKey) signer.resetKey()
        if (!signer.hasUsableKey()) signer.publicKeySpkiBase64() // creates the non-exportable key
        val challengeResponse = postWithOneSafeRetry(BackendRequest("$origin$CHALLENGE_PATH", EMPTY_OBJECT))
        val challenge = parseStrictObject(challengeResponse, 201, setOf("challengeId", "challenge", "expiresAt"))
        val challengeId = challenge.requiredString("challengeId", 1, 256)
        val challengeValue = challenge.requiredString("challenge", 16, 1024)
        val challengeExpiry = parseTime(challenge.requiredString("expiresAt", 1, 128))
        if (challengeExpiry <= nowMillis()) throw LiveTokenProtocolException("challenge_expired")

        val publicKey = signer.publicKeySpkiBase64()
        val signature = signer.signBase64Url(challengeValue.toByteArray(Charsets.UTF_8))
        val body = buildJsonObject {
            put("challengeId", challengeId)
            put("publicKey", publicKey)
            put("signature", signature)
            // Optional attestation deliberately remains absent for sideloaded builds.
        }.toString().toByteArray(Charsets.UTF_8)
        val enrollResponse = transport.post(BackendRequest("$origin$ENROLL_PATH", body))
        val enrolled = parseStrictObject(enrollResponse, 201, setOf("installationId", "keyId", "status"))
        val identity = InstallationIdentity(
            installationId = enrolled.requiredString("installationId", 1, 256),
            keyId = enrolled.requiredString("keyId", 16, 256),
        )
        if (enrolled.requiredString("status", 1, 32) != "active") throw LiveTokenProtocolException("enrollment_inactive")
        stateStore.write(identity)
        return identity
    }

    private suspend fun mint(origin: String, state: InstallationIdentity): BackendResponse {
        val timestamp = nowMillis().toString()
        val requestNonce = nonce().also {
            if (it.length !in 16..256 || !it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' })
                throw LiveTokenProtocolException("invalid_nonce")
        }
        val canonical = canonicalMint(timestamp, requestNonce, EMPTY_OBJECT, state.keyId)
        val signature = signer.signBase64Url(canonical.toByteArray(Charsets.UTF_8))
        return transport.post(BackendRequest(
            url = "$origin$MINT_PATH",
            body = EMPTY_OBJECT,
            headers = mapOf(
                "X-Installation-Key-Id" to state.keyId,
                "X-Request-Timestamp" to timestamp,
                "X-Request-Nonce" to requestNonce,
                "X-Request-Signature" to signature,
            ),
        ))
    }

    private fun parseMint(response: BackendResponse): GeminiLiveEphemeralToken {
        val value = parseStrictObject(response, 200, setOf("name", "expireTime", "newSessionExpireTime"))
        val token = value.requiredString("name", 1, MAX_TOKEN_CHARS)
        val expires = parseTime(value.requiredString("expireTime", 1, 128))
        val newSessionExpires = parseTime(value.requiredString("newSessionExpireTime", 1, 128))
        val now = nowMillis()
        if (expires <= now || expires > now + MAX_EXPIRY_FUTURE_MILLIS) throw LiveTokenProtocolException("invalid_expiry")
        if (newSessionExpires <= now || newSessionExpires > expires) throw LiveTokenProtocolException("invalid_new_session_expiry")
        return GeminiLiveEphemeralToken(token, expires, newSessionExpires)
    }

    private suspend fun postWithOneSafeRetry(request: BackendRequest): BackendResponse = try {
        transport.post(request)
    } catch (_: java.io.IOException) {
        // Challenge creation has no client-visible side effect beyond an expiring orphan, so one retry is safe.
        transport.post(request)
    }

    private fun parseStrictObject(response: BackendResponse, expectedStatus: Int, fields: Set<String>): JsonObject {
        rejectRedirect(response)
        if (response.body.size > MAX_RESPONSE_BYTES) throw LiveTokenProtocolException("response_too_large")
        if (response.status != expectedStatus) throw LiveTokenProtocolException(response.errorCode() ?: "http_${response.status}")
        val objectValue = try { json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject }
        catch (_: Exception) { throw LiveTokenProtocolException("invalid_response") }
        if (objectValue.keys != fields) throw LiveTokenProtocolException("invalid_schema")
        return objectValue
    }

    private fun BackendResponse.errorCode(): String? {
        if (body.size > MAX_RESPONSE_BYTES) return null
        return try { json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject["error"]?.jsonObject
            ?.get("code")?.jsonPrimitive?.content }
        catch (_: Exception) { null }
    }

    private fun rejectRedirect(response: BackendResponse) {
        if (response.status in 300..399 || response.redirectLocation != null)
            throw LiveTokenProtocolException("redirect_rejected")
    }

    companion object {
        const val MODEL = "models/gemini-3.1-flash-live-preview"
        const val CHALLENGE_PATH = "/v1/installations/challenge"
        const val ENROLL_PATH = "/v1/installations/enroll"
        const val MINT_PATH = "/v1/gemini-live/session-token"
        private val EMPTY_OBJECT = "{}".toByteArray(Charsets.UTF_8)
        private const val MAX_RESPONSE_BYTES = 16_384
        private const val MAX_TOKEN_CHARS = 8_192
        private const val MAX_EXPIRY_FUTURE_MILLIS = 30L * 60L * 1000L

        fun validateOrigin(raw: String): String {
            val uri = try { URI(raw.trim()) } catch (_: Exception) { throw LiveTokenProtocolException("invalid_endpoint") }
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.query != null || uri.port == 0)
                throw LiveTokenProtocolException("invalid_endpoint")
            val path = uri.rawPath.orEmpty().trimEnd('/')
            if (path.isNotEmpty() && path != "/") throw LiveTokenProtocolException("invalid_endpoint")
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "https://${uri.host.lowercase()}$port"
        }

        fun canonicalMint(timestamp: String, nonce: String, body: ByteArray, keyId: String): String = listOf(
            "POST", MINT_PATH, timestamp, nonce, sha256Hex(body), keyId,
        ).joinToString("\n")

        private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value).joinToString("") { "%02x".format(it) }

        private fun parseTime(value: String): Long = try { java.time.Instant.parse(value).toEpochMilli() }
        catch (_: Exception) { throw LiveTokenProtocolException("invalid_time") }
    }
}

private fun JsonObject.requiredString(name: String, min: Int, max: Int): String {
    val primitive = this[name] as? JsonPrimitive ?: throw LiveTokenProtocolException("invalid_schema")
    if (!primitive.isString) throw LiveTokenProtocolException("invalid_schema")
    return primitive.content.also { if (it.length !in min..max) throw LiveTokenProtocolException("invalid_schema") }
}
