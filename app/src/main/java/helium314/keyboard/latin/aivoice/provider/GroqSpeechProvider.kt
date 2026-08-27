// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.provider

import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.net.ConnectException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resumeWithException

/** Groq implementation of [SpeechProvider]. It owns Groq request shape and no runtime/session state. */
class GroqSpeechProvider(
    private val client: OkHttpClient,
    private val catalog: ProviderCatalog,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SpeechProvider {
    override val providerId = PROVIDER_ID

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult = withContext(Dispatchers.IO) {
        validateRequest(request)
        var attempt = 0
        while (true) {
            try {
                val response = await(buildRequest(request))
                response.use {
                    if (!it.isSuccessful) {
                        // Headers are read before any body access; the request ID is diagnostic metadata only.
                        val retryAfterMillis = retryAfterMillis(it.header("Retry-After"))
                        val requestId = it.header("x-request-id")
                        // Error body is parsed before classification so profile-specific error codes
                        // can upgrade a generic 4xx class to a rotation-eligible failure.
                        val providerError = parseErrorBody(it)
                        throw ProviderException(
                            failure = classifyHttp(it.code, providerError?.code),
                            httpCode = it.code,
                            retryAfterMillis = retryAfterMillis,
                            requestId = requestId,
                            providerError = providerError,
                            exceptionStage = "provider_http",
                        )
                    }
                    return@withContext parseResponse(it, request)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProviderException) {
                if (attempt == 0 && failure.failure.isTransient()) {
                    attempt++
                    delay(failure.retryAfterMillis ?: RETRY_DELAY_MILLIS)
                    continue
                }
                throw if (attempt == 0) failure else ProviderException(
                    failure = failure.failure,
                    httpCode = failure.httpCode,
                    retryAfterMillis = failure.retryAfterMillis,
                    providerError = failure.providerError,
                    exceptionStage = failure.exceptionStage,
                    retried = true,
                    requestId = failure.requestId,
                )
            } catch (exception: IOException) {
                val failure = classifyIo(exception)
                if (attempt == 0 && failure.isTransient()) {
                    attempt++
                    delay(RETRY_DELAY_MILLIS)
                    continue
                }
                throw ProviderException(failure, exceptionStage = classifyIoStage(exception), retried = attempt > 0)
            }
        }
        error("unreachable")
    }

    private fun validateRequest(request: TranscriptionRequest) {
        if (request.profile.providerId != PROVIDER_ID || !catalog.supports(PROVIDER_ID, request.profile.modelId))
            throw ProviderException(ProviderFailure.ModelUnavailable, exceptionStage = "request_validation")
        if (request.apiKey.isBlank()) throw ProviderException(ProviderFailure.Authentication, exceptionStage = "request_validation")
        if (!request.wavFile.isFile || request.wavFile.length() <= 0L)
            throw ProviderException(ProviderFailure.InvalidRequest, exceptionStage = "request_validation")
        if (request.languageTag != null && !request.languageTag.matches(ISO_639_1))
            throw ProviderException(ProviderFailure.InvalidRequest, exceptionStage = "request_validation")
    }

    private fun buildRequest(request: TranscriptionRequest): Request {
        val translateToEnglish = request.languageBehavior == KeyboardLanguageBehavior.ENGLISH_OUTPUT &&
            request.profile.modelId == WHISPER_LARGE_V3
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", request.wavFile.name, request.wavFile.asRequestBody(WAV_MEDIA_TYPE))
            .addFormDataPart("model", request.profile.modelId)
            .addFormDataPart("response_format", "json")
            .apply {
                // Groq's translations operation is English-only. For transcription, language is an
                // input-language hint, so only send Hindi when the Hindi keyboard is active. In
                // particular, do not mislabel Hindi speech as English for Turbo, which has no
                // provider-native translation operation.
                if (translateToEnglish) addFormDataPart("language", "en")
                else if (request.languageBehavior == KeyboardLanguageBehavior.HINDI_OUTPUT) addFormDataPart("language", "hi")
            }
            .build()
        return Request.Builder()
            .url(if (translateToEnglish) TRANSLATIONS_URL else TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer ${request.apiKey}")
            .post(body)
            .build()
    }

    private fun parseResponse(response: Response, request: TranscriptionRequest): TranscriptionResult = try {
        val root = json.parseToJsonElement(readResponseBody(response)).jsonObject
        val text = root["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (text.isBlank()) throw ProviderException(ProviderFailure.MalformedResponse, exceptionStage = "response_parser")
        TranscriptionResult(text = text, providerRequestId = response.header("x-request-id") ?: root["x_groq"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
    } catch (failure: ProviderException) {
        throw failure
    } catch (_: Exception) {
        throw ProviderException(ProviderFailure.MalformedResponse, exceptionStage = "response_parser")
    }

    private fun readResponseBody(response: Response): String {
        val body = response.body ?: throw ProviderException(ProviderFailure.MalformedResponse, exceptionStage = "response_parser")
        val reader = body.charStream()
        val output = StringBuilder()
        val buffer = CharArray(RESPONSE_READ_BUFFER_CHARS)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) return output.toString()
            if (output.length + read > MAX_RESPONSE_CHARS) throw ProviderException(ProviderFailure.MalformedResponse, exceptionStage = "response_parser")
            output.append(buffer, 0, read)
        }
    }

    private suspend fun await(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _ -> response.close() }
                } else response.close()
            }
        })
    }

    /**
     * Reads a bounded, sanitized [ProviderError] from a non-2xx response. The raw body never leaves this
     * function; only printable-ASCII, truncated type/code/message fields may reach a diagnostic.
     */
    private fun parseErrorBody(response: Response): ProviderError? = try {
        val body = readErrorBody(response)
        if (body.isBlank()) return null
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject ?: return null
        ProviderError(
            type = error["type"]?.jsonPrimitive?.content?.cleanUp()?.take(ERROR_DETAIL_CHARS)?.takeIf { it.isNotBlank() },
            code = error["code"]?.jsonPrimitive?.content?.cleanUp()?.take(ERROR_DETAIL_CHARS)?.takeIf { it.isNotBlank() },
            message = error["message"]?.jsonPrimitive?.content?.cleanUp()?.take(ERROR_DETAIL_CHARS)?.takeIf { it.isNotBlank() },
        )
    } catch (_: Exception) {
        null
    }

    private fun readErrorBody(response: Response): String {
        val body = response.body ?: throw ProviderException(ProviderFailure.MalformedResponse, exceptionStage = "response_parser")
        val reader = body.charStream()
        val output = StringBuilder()
        val buffer = CharArray(RESPONSE_READ_BUFFER_CHARS)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) return output.toString()
            if (output.length + read > ERROR_BODY_CHARS) return output.append(buffer, 0, ERROR_BODY_CHARS - output.length).toString()
            output.append(buffer, 0, read)
        }
    }

    private fun String.cleanUp(): String = filter { it.code in 32..126 }.trim()

    private fun ProviderFailure.isTransient() = this is ProviderFailure.RateLimited || this is ProviderFailure.NetworkUnavailable || this is ProviderFailure.NetworkTimeout || this is ProviderFailure.Server

    private companion object {
        const val PROVIDER_ID = "groq"
        const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val TRANSLATIONS_URL = "https://api.groq.com/openai/v1/audio/translations"
        const val WHISPER_LARGE_V3 = "whisper-large-v3"
        const val RETRY_DELAY_MILLIS = 500L
        const val MAX_RESPONSE_CHARS = 1_048_576
        const val RESPONSE_READ_BUFFER_CHARS = 4_096
        const val ERROR_BODY_CHARS = 2_048
        const val ERROR_DETAIL_CHARS = 160
        val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        val ISO_639_1 = Regex("^[a-zA-Z]{2}$")

        /**
         * Classifies a non-2xx HTTP status. [providerErrorCode] is optional; when Groq reports a
         * profile/credential-scoped error such as "organization_restricted", the failure is upgraded
         * from the generic InvalidRequest bucket to Authorization so profile rotation may engage.
         * A genuine request-shape 400 (no recognized provider error code) stays InvalidRequest and
         * deliberately never rotates, because retrying a malformed request on another profile is pointless.
         */
        fun classifyHttp(code: Int, providerErrorCode: String? = null): ProviderFailure = when {
            code == 400 && providerErrorCode == "organization_restricted" -> ProviderFailure.Authorization
            else -> when (code) {
                401 -> ProviderFailure.Authentication
                403 -> ProviderFailure.Authorization
                408 -> ProviderFailure.NetworkTimeout
                429 -> ProviderFailure.RateLimited
                404 -> ProviderFailure.ModelUnavailable
                400, 409, 413, 415, 422 -> ProviderFailure.InvalidRequest
                in 500..599 -> ProviderFailure.Server
                else -> ProviderFailure.Unknown(code)
            }
        }
        fun classifyIo(exception: IOException): ProviderFailure = when (exception) {
            is SocketTimeoutException, is InterruptedIOException -> ProviderFailure.NetworkTimeout
            is UnknownHostException, is ConnectException -> ProviderFailure.NetworkUnavailable
            else -> ProviderFailure.NetworkUnavailable
        }
        fun classifyIoStage(exception: IOException): String = when (exception) {
            is UnknownHostException -> "dns_resolution"
            is ConnectException -> "connect"
            is SocketTimeoutException, is InterruptedIOException -> "timeout"
            else -> "network"
        }

        fun retryAfterMillis(value: String?): Long? {
            val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            header.toLongOrNull()?.let { return it.coerceIn(0L, MAX_RETRY_AFTER_SECONDS).times(1_000L) }
            val retryAt = runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                ?: return null
            return (retryAt - System.currentTimeMillis()).coerceIn(0L, MAX_RETRY_AFTER_SECONDS * 1_000L)
        }

        const val MAX_RETRY_AFTER_SECONDS = 10L
    }
}
