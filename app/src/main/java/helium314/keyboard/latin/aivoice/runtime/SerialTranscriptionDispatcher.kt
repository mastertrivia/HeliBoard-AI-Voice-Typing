// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.provider.ProviderException
import helium314.keyboard.latin.aivoice.provider.ProviderFailure
import helium314.keyboard.latin.aivoice.provider.ProviderResolution
import helium314.keyboard.latin.aivoice.provider.ProviderResolver
import helium314.keyboard.latin.aivoice.provider.ResolvedProvider
import helium314.keyboard.latin.aivoice.provider.TranscriptionRequestFactory
import helium314.keyboard.latin.aivoice.provider.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable, user-visible identity used in diagnostic traces and rotation lines. Never a secret. */
internal fun ApiProfile.traceLabel(): String = displayName.ifBlank { id }

/** Ordered, bounded, session-owned bridge from WAV chunks to provider and current editor. */
internal class SerialTranscriptionDispatcher(
    private val sessionId: String,
    private val profile: ApiProfile,
    private val providerResolver: ProviderResolver,
    private val requestFactory: TranscriptionRequestFactory,
    private val inserter: TranscriptInserter,
    private val insertionGate: SessionInsertionGate,
    private val rotationCoordinator: RotationCoordinator,
    private val diagnostics: AiDiagnosticsSink,
    parentScope: CoroutineScope,
) : TranscriptionDispatcher {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val queue = Channel<AudioChunk>(QUEUE_CAPACITY)
    private val closeMutex = Mutex()
    private var closed = false
    private val worker = scope.launch {
        for (chunk in queue) process(chunk)
    }

    override suspend fun enqueue(chunk: AudioChunk) {
        check(!closed) { "Transcription dispatcher is closed" }
        if (queue.trySend(chunk).isFailure) {
            chunk.wavFile.delete()
            emit(DiagnosticLevel.ERROR, "AI-0412", message = "Audio queue is full", reason = "cause=queue_capacity_exceeded")
            throw IllegalStateException("AI Voice chunk queue is full")
        }
    }

    override suspend fun drain() = closeMutex.withLock {
        if (!closed) {
            closed = true
            queue.close()
        }
        worker.join()
    }

    override suspend fun cancelPending() = closeMutex.withLock {
        if (!closed) {
            closed = true
            queue.close()
        }
        job.cancelAndJoin()
        while (true) queue.tryReceive().getOrNull()?.wavFile?.delete() ?: break
    }

    private suspend fun process(chunk: AudioChunk) {
        try {
            if (!insertionGate.allowsInsertion()) return
            val sequence = chunk.sequence
            val initial = providerResolver.resolve(profile)
            if (initial !is ProviderResolution.Ready) {
                emit(DiagnosticLevel.ERROR, "AI-0505", sequence, message = "Provider resolution failed", reason = "stage=resolution | profile=${profile.traceLabel()}")
                return
            }
            when (val outcome = attempt(chunk, profile, initial.value)) {
                is AttemptResult.Success -> { insertOutcome(outcome, sequence, profile); return }
                AttemptResult.Blank -> { emitBlank(chunk, profile); return }
                is AttemptResult.Failure -> {
                    val failover = runCatching {
                        rotationCoordinator.requestFailureRotation(profile.id, outcome.failure.failure)
                    }.getOrDefault(false)
                    if (!failover) {
                        emitRequestTrace(chunk, profile, outcome.failure, fallbackTriggered = false, nextProfile = null)
                        return
                    }
                    // Failover preserves the exact same audio payload and only advances the profile.
                    val candidates = runCatching { rotationCoordinator.failoverCandidates(profile.id) }.getOrDefault(emptyList())
                    emitRequestTrace(chunk, profile, outcome.failure, fallbackTriggered = true, nextProfile = candidates.firstOrNull())
                    emitFailover(chunk, profile, outcome.failure, nextProfile = candidates.firstOrNull())
                    fallback(chunk, sequence, candidates)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProviderException) {
            // Fallback is advisory. A persistence/diagnostic fault must not kill the serial
            // worker or prevent a later chunk from using the still-active profile.
            runCatching { rotationCoordinator.requestFailureRotation(profile.id, failure.failure) }
        } catch (_: Exception) {
            emit(DiagnosticLevel.ERROR, "AI-0505", chunk.sequence, message = "Dispatch failure")
        } finally {
            chunk.wavFile.delete()
        }
    }

    private suspend fun fallback(chunk: AudioChunk, sequence: Long, candidates: List<ApiProfile>) {
        val attempted = mutableListOf(profile.traceLabel())
        var lastFailure: ProviderException? = null
        for ((index, candidate) in candidates.withIndex()) {
            val resolved = providerResolver.resolve(candidate)
            if (resolved !is ProviderResolution.Ready) continue
            attempted += candidate.traceLabel()
            val next = candidates.getOrNull(index + 1)
            when (val outcome = attempt(chunk, candidate, resolved.value)) {
                is AttemptResult.Success -> {
                    emitFailoverSuccess(chunk, candidate, attempted.size)
                    insertOutcome(outcome, sequence, candidate)
                    return
                }
                AttemptResult.Blank -> emitBlank(chunk, candidate)
                is AttemptResult.Failure -> {
                    lastFailure = outcome.failure
                    emitRequestTrace(chunk, candidate, outcome.failure, fallbackTriggered = true, nextProfile = next)
                    emitFailover(chunk, candidate, outcome.failure, nextProfile = next)
                    runCatching { rotationCoordinator.requestFailureRotation(candidate.id, outcome.failure.failure) }
                }
            }
        }
        // The complete one-pass fallback cycle was exhausted without a successful transcription.
        emit(
            DiagnosticLevel.ERROR,
            "AI-0604",
            sequence,
            message = "Fallback cycle exhausted: no eligible profile produced a transcription",
            reason = fallbackExhaustedReason(sequence, attempted, lastFailure),
        )
    }

    /** One transcription attempt with a fixed profile; the WAV payload is reused across attempts. */
    private suspend fun attempt(chunk: AudioChunk, target: ApiProfile, resolved: ResolvedProvider): AttemptResult = try {
        val request = requestFactory.create(
            wavFile = chunk.wavFile,
            profile = target,
            apiKey = resolved.apiKey,
            sessionId = sessionId,
            chunkSequence = chunk.sequence,
        )
        val result = resolved.provider.transcribe(request)
        if (result.text.isBlank()) AttemptResult.Blank else AttemptResult.Success(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ProviderException) {
        AttemptResult.Failure(failure)
    }

    private suspend fun insertOutcome(outcome: AttemptResult.Success, sequence: Long, target: ApiProfile) {
        when (inserter.insert(outcome.result.text, sessionId, sequence, insertionGate::allowsInsertion)) {
            InsertResult.Inserted -> Unit
            InsertResult.NoConnection, InsertResult.Rejected -> emit(DiagnosticLevel.WARNING, "AI-0603", sequence, target = target, reason = "stage=insert | cause=editor_no_longer_accepts_text")
        }
    }

    /** Multi-line pipeline trace for one failed request attempt. Only actually-known stages are marked. */
    private fun emitRequestTrace(chunk: AudioChunk, target: ApiProfile, failure: ProviderException, fallbackTriggered: Boolean, nextProfile: ApiProfile?) {
        val stage = failure.exceptionStage ?: defaultStageFor(failure.failure)
        val requestSend = when (stage) {
            "provider_http" -> "OK"
            "dns_resolution", "connect", "timeout", "network" -> "FAILED"
            else -> "not_reached"
        }
        val serverResponse = if (stage == "provider_http") "received" else "not_reached"
        val lines = mutableListOf(
            "REQUEST_TRACE",
            "profile=${target.traceLabel()}",
            "provider=${target.providerId}",
            "model=${target.modelId}",
            "chunk=${chunk.sequence}",
            "audio=created",
            "request_build=OK",
            "request_send=$requestSend",
            "server_response=$serverResponse",
        )
        failure.httpCode?.let { lines += "http_status=$it" }
        failure.providerError?.type?.let { lines += "provider_error_type=$it" }
        failure.providerError?.code?.let { lines += "provider_error_code=$it" }
        failure.providerError?.message?.let { lines += "provider_error_message=$it" }
        failure.retryAfterMillis?.let { lines += "retry_after=${it / 1_000}s" }
        failure.requestId?.let { lines += "request_id=$it" }
        lines += "response_parse=${if (stage == "response_parser") "FAILED" else "not_reached"}"
        lines += "transcription=FAILED"
        lines += "failure_stage=$stage"
        if (failure.retried) lines += "provider_retry=yes"
        if (fallbackTriggered) lines += "fallback=TRIGGERED"
        nextProfile?.let { lines += "next_profile=${it.traceLabel()}" }
        emit(DiagnosticLevel.ERROR, "AI-0511", chunk.sequence, message = "Request trace", target = target, reason = lines.joinToString("\n"))
    }

    /** One compact step of the actual fallback path. */
    private fun emitFailover(chunk: AudioChunk, failedTarget: ApiProfile, failure: ProviderException, nextProfile: ApiProfile?) {
        emit(DiagnosticLevel.WARNING, "AI-0512", chunk.sequence, message = "Failover step", target = failedTarget, reason = buildString {
            append("FAILOVER | failed_profile=").append(failedTarget.traceLabel())
            append(" | failure_reason=").append(failureReasonLabel(failure))
            append(" | retry_same_audio=true")
            nextProfile?.let { append(" | next_profile=").append(it.traceLabel()) }
        })
    }

    private fun emitFailoverSuccess(chunk: AudioChunk, target: ApiProfile, attempts: Int) {
        emit(DiagnosticLevel.INFO, "AI-0513", chunk.sequence, message = "Failover succeeded", target = target, reason = buildString {
            append("FAILOVER_SUCCESS | profile=").append(target.traceLabel())
            append(" | transcription_received=true")
            append(" | attempts=").append(attempts)
        })
    }

    private fun emitBlank(chunk: AudioChunk, target: ApiProfile) {
        emit(DiagnosticLevel.WARNING, "AI-0601", chunk.sequence, message = "Empty or blank transcript", target = target, reason = buildString {
            appendLine("TRANSCRIPTION_EMPTY")
            append("chunk=").append(chunk.sequence)
            append(" | stage=response_parser")
        })
    }

    private fun fallbackExhaustedReason(sequence: Long, attemptedProfiles: List<String>, lastFailure: ProviderException?): String = buildString {
        appendLine("FALLBACK_EXHAUSTED")
        append("chunk=").append(sequence); appendLine()
        append("attempted_profiles=").append(attemptedProfiles.joinToString(",")); appendLine()
        append("successful_profile=none")
        lastFailure?.let { appendLine(); append("final_failure=").append(failureReasonLabel(it)) }
    }

    /** Machine-readable single-line failure label: prefers the real HTTP evidence, else the failure class. */
    private fun failureReasonLabel(failure: ProviderException): String {
        val http = failure.httpCode?.let { "http_$it" }
        val type = failure.providerError?.type
        return when {
            http != null && type != null -> "$http/$type"
            http != null -> http
            else -> when (failure.failure) {
                ProviderFailure.Authentication -> "auth"
                ProviderFailure.Authorization -> "permission"
                ProviderFailure.RateLimited -> "rate_limit"
                ProviderFailure.ModelUnavailable -> "model_unavailable"
                ProviderFailure.InvalidRequest -> "invalid_request"
                ProviderFailure.MalformedResponse -> "malformed_response"
                ProviderFailure.NetworkUnavailable -> "network"
                ProviderFailure.NetworkTimeout -> "timeout"
                ProviderFailure.Server -> "server"
                is ProviderFailure.Unknown -> "unknown"
                ProviderFailure.Cancelled -> "cancelled"
            }
        }
    }

    /** Fallback stage guess used only when the provider did not attach explicit [ProviderException.exceptionStage] evidence. */
    private fun defaultStageFor(failure: ProviderFailure): String = when (failure) {
        ProviderFailure.RateLimited, ProviderFailure.Authorization, ProviderFailure.Server -> "provider_http"
        ProviderFailure.NetworkUnavailable -> "network"
        ProviderFailure.NetworkTimeout -> "timeout"
        ProviderFailure.MalformedResponse -> "response_parser"
        ProviderFailure.Authentication, ProviderFailure.InvalidRequest, ProviderFailure.ModelUnavailable -> "request_validation"
        is ProviderFailure.Unknown, ProviderFailure.Cancelled -> "unknown"
    }

    private sealed interface AttemptResult {
        data class Success(val result: TranscriptionResult) : AttemptResult
        data object Blank : AttemptResult
        data class Failure(val failure: ProviderException) : AttemptResult
    }

    private fun emit(level: DiagnosticLevel, code: String, chunkSequence: Long? = null, message: String = "AI Voice dispatch event", target: ApiProfile = profile, reason: String? = null) {
        runCatching {
            diagnostics.emit(AiDiagnosticEvent(
                level = level,
                code = code,
                sessionId = sessionId,
                chunkSequence = chunkSequence,
                profileSerial = target.serialNumber,
                providerId = target.providerId,
                modelId = target.modelId,
                message = message,
                reason = reason,
            ))
        }
    }

    private companion object { const val QUEUE_CAPACITY = 8 }
}
