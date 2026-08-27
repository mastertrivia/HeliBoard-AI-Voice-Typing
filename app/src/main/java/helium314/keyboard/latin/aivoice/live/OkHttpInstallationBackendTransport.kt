// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OkHttpInstallationBackendTransport(client: OkHttpClient) : InstallationBackendTransport {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun post(request: BackendRequest): BackendResponse = withContext(Dispatchers.IO) {
        val origin = EnrolledInstallationTokenProvider.validateOrigin(request.url.substringBefore("/v1/"))
        val httpRequest = Request.Builder().url(request.url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .post(request.body.toRequestBody(JSON))
            .build()
        client.newCall(httpRequest).execute().use { response ->
            val location = response.header("Location")
            if (location != null) {
                val targetOrigin = runCatching {
                    val resolved = response.request.url.resolve(location) ?: throw IOException("Invalid redirect")
                    EnrolledInstallationTokenProvider.validateOrigin(resolved.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
                }.getOrNull()
                if (targetOrigin == null || targetOrigin != origin) throw IOException("Cross-host redirect rejected")
            }
            val source = response.body?.source()
            val bytes = if (source == null) ByteArray(0) else {
                source.request(MAX_RESPONSE_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES) throw LiveTokenProtocolException("response_too_large")
                source.readByteArray()
            }
            BackendResponse(response.code, bytes, location)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_BYTES = 16_384L
    }
}
