// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DiagnosticLevel { INFO, WARNING, ERROR }

data class AiDiagnosticEvent(
    val epochMillis: Long = System.currentTimeMillis(),
    val elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    val level: DiagnosticLevel,
    val code: String,
    val sessionId: String? = null,
    val chunkSequence: Long? = null,
    val profileSerial: Int? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val message: String,
    /**
     * Safe, caller-supplied detail that survives the code-label redaction so the console can show the
     * exact reason (HTTP status, provider error type/code, retry-after, rotation trigger, from->to
     * profile). It is sanitized by [AiDiagnosticsRepository] and must never contain keys, transcripts,
     * headers, paths, or raw response bodies.
     */
    val reason: String? = null,
)

fun interface AiDiagnosticsSink { fun emit(event: AiDiagnosticEvent) }

/**
 * Bounded process-memory observability store. [emit] discards arbitrary caller-supplied message
 * text and replaces it with a code-registry label before retaining an event. This prevents a
 * future accidental diagnostic from exposing a key, transcript, path, header, or raw response.
 */
class AiDiagnosticsRepository(private val capacity: Int = 200) : AiDiagnosticsSink {
    private val lock = Any()
    private val mutableEvents = MutableStateFlow<List<AiDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<AiDiagnosticEvent>> = mutableEvents

    override fun emit(event: AiDiagnosticEvent) {
        val safeEvent = event.copy(
            message = safeMessageFor(event.code),
            reason = safeReasonFor(event.reason),
        )
        runCatching { synchronized(lock) { mutableEvents.value = (mutableEvents.value + safeEvent).takeLast(capacity) } }
    }

    fun clear() = synchronized(lock) { mutableEvents.value = emptyList() }

    /** Snapshot for a future redacting formatter; callers must not serialize arbitrary objects. */
    fun snapshotForExport(): List<AiDiagnosticEvent> = synchronized(lock) { mutableEvents.value.toList() }

    /**
     * Second redaction gate for [AiDiagnosticEvent.reason]. It keeps printable ASCII and newlines
     * (so structured multi-line traces survive), collapses space runs, truncates, and strips common
     * secret patterns so an accidentally verbose caller still cannot leak a key, transcript, or raw
     * response into the console.
     */
    private fun safeReasonFor(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        return redact(reason.filter { it.code in 32..126 || it.code == 10 })
            .replace(SPACE_PATTERN, " ")
            .trim()
            .take(MAX_REASON_CHARS)
            .takeIf { it.isNotBlank() }
    }

    private fun redact(value: String): String = value
        .replace(GSK_KEY_PATTERN, "<redacted>")
        .replace(BEARER_PATTERN, "<redacted>")

    private fun safeMessageFor(code: String) = when (code) {
        "AI-0102" -> "Recording could not start"
        "AI-0103" -> "Microphone permission is required"
        "AI-0104" -> "Recording started"
        "AI-0105" -> "Recording stop requested"
        "AI-0106" -> "Recording session stopped"
        "AI-0108" -> "IME engine cleanup completed"
        "AI-0109" -> "AI engine recovered from an internal error"
        "AI-0201" -> "Configuration saved"
        "AI-0202" -> "Configuration reset or unavailable"
        "AI-0203" -> "Profile selected"
        "AI-0204" -> "Profile selection rejected"
        "AI-0205" -> "Configuration write failed"
        "AI-0206" -> "Configuration migrated"
        "AI-0301" -> "Profile created"
        "AI-0302" -> "Profile updated"
        "AI-0304" -> "Profile deleted"
        "AI-0305" -> "Profile operation failed"
        "AI-0310" -> "Microphone could not start"
        "AI-0311" -> "Microphone capture failed"
        "AI-0401" -> "Audio chunk queued"
        "AI-0412" -> "Audio queue is full"
        "AI-0501" -> "Provider request completed"
        "AI-0502" -> "Provider retry scheduled"
        "AI-0503" -> "Provider authentication or key issue"
        "AI-0504" -> "Provider/network request failed"
        "AI-0505" -> "Provider configuration or response rejected"
        "AI-0506" -> "Provider request cancelled"
        "AI-0507" -> "Provider authorization or permission denied"
        "AI-0508" -> "Provider rate limit exceeded"
        "AI-0509" -> "Provider server or upstream error"
        "AI-0510" -> "Provider request failed with unknown code"
        "AI-0511" -> "Request trace"
        "AI-0512" -> "Failover step"
        "AI-0513" -> "Failover succeeded"
        "AI-0601" -> "Empty or blank transcript"
        "AI-0602" -> "Transcript inserted"
        "AI-0603" -> "Text insertion rejected"
        "AI-0604" -> "Fallback cycle exhausted"
        "AI-0701" -> "Failure rotation queued"
        "AI-0703" -> "No eligible rotation profile"
        "AI-0704" -> "Initial eligible profile selected"
        "AI-0705" -> "Pending profile activated"
        "AI-0706" -> "Rotation trigger deferred"
        "AI-0707" -> "Profile rotation completed"
        "AI-0708" -> "Rotation operation failed"
        "AI-0709" -> "Rotation not applied"
        "AI-0710" -> "Failed profile disabled"
        "AI-0711" -> "Fallback success persisted"
        "AI-0712" -> "Fallback candidate failure recorded"
        else -> "AI engine event recorded"
    }

    private companion object {
        const val MAX_REASON_CHARS = 1_000
        val SPACE_PATTERN = Regex("[ \t]+")
        val GSK_KEY_PATTERN = Regex("gsk_[A-Za-z0-9_-]+")
        val BEARER_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    }
}
