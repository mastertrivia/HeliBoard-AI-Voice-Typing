// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.RotationConfig
import helium314.keyboard.latin.aivoice.domain.RotationTrigger
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import helium314.keyboard.latin.aivoice.provider.ProviderFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole owner of automatic profile selection. It never interrupts a recording, upload, provider
 * call, or insertion: callers record a trigger, then resolve exactly once before a new session.
 */
interface RotationCoordinator {
    suspend fun resolveProfileForNewSession(nowWallMillis: Long, nowElapsedMillis: Long): ApiProfile?
    suspend fun recordActiveDuration(profileId: String, activeMillis: Long)
    /**
     * Records an eligible failure for the next session boundary; it never changes the profile now.
     * Returns true only when the failure was actually queued for rotation.
     */
    suspend fun requestFailureRotation(profileId: String, failure: ProviderFailure): Boolean
    /** Eligible profiles for a single-job failover retry, in ring order after the failed profile. */
    suspend fun failoverCandidates(fromProfileId: String): List<ApiProfile>
    suspend fun completeSession(sessionProfileId: String, nowWallMillis: Long, nowElapsedMillis: Long)
}

/**
 * Serialized, provider-neutral ring coordinator. Profile eligibility is limited to durable
 * metadata, installed provider implementations, and key availability. API keys remain behind
 * the resolver and are never returned to this coordinator.
 */
class DefaultRotationCoordinator(
    private val repository: AiVoiceSettingsRepository,
    private val catalog: ProviderCatalog,
    private val isProviderInstalled: (String) -> Boolean,
    private val isProfileUsable: suspend (ApiProfile) -> Boolean,
    private val diagnostics: AiDiagnosticsSink,
) : RotationCoordinator {
    private val mutex = Mutex()
    /** In-memory trace of the failure class that queued each profile, for the later disable log. Never persisted. */
    private val pendingFailureTypes = mutableMapOf<String, String>()

    override suspend fun resolveProfileForNewSession(nowWallMillis: Long, nowElapsedMillis: Long): ApiProfile? = try {
        mutex.withLock {
        // Key checks are performed before the atomic configuration mutation. The resulting ID
        // set is used only for this boundary decision; no secret is retained in rotation state.
        val usableProfileIds = mutableSetOf<String>()
        for (profile in repository.config.value.profiles) {
            if (catalog.supports(profile.providerId, profile.modelId) &&
                isProviderInstalled(profile.providerId) && isProfileUsable(profile)) {
                usableProfileIds += profile.id
            }
        }
        var result: ApiProfile? = null
        repository.update("resolve_rotation_for_new_session") { config ->
            val active = config.activeProfileId?.let { id -> config.profiles.firstOrNull { it.id == id } }
            val pendingManual = config.pendingProfileId?.let { id -> config.profiles.firstOrNull { it.id == id } }
            when {
                pendingManual != null && eligible(pendingManual, usableProfileIds) -> {
                    result = pendingManual
                    diagnostics.info("AI-0705", pendingManual, "Pending manual profile activated at session boundary",
                        reason = buildString {
                            append("PROFILE")
                            active?.let { append(" | from=").append(it.traceLabel()) }
                            append(" | to=").append(pendingManual.traceLabel())
                            append(" | reason=manual")
                        })
                    config.copy(activeProfileId = pendingManual.id, pendingProfileId = null,
                        rotation = resetForNewActiveProfile(config.rotation, nowWallMillis, nowElapsedMillis))
                }
                active == null || !eligible(active, usableProfileIds) -> {
                    result = firstEligible(config, usableProfileIds)
                    if (result == null) {
                        emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.ERROR, code = "AI-0703", message = "No eligible AI Voice profile is available", reason = "cause=no enabled, supported, installed profile"))
                        config
                    } else {
                        config.copy(activeProfileId = result!!.id,
                            rotation = resetForNewActiveProfile(config.rotation, nowWallMillis, nowElapsedMillis))
                    }
                }
                else -> resolveFromActive(config, active, nowWallMillis, nowElapsedMillis, usableProfileIds).also { result = it.second }.first
            }
        }
        result
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Rotation is optional. Preserve the last durable active profile so the core pipeline can continue.
        emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.ERROR, code = "AI-0708", message = "Rotation decision failed; current profile retained"))
        val active = repository.config.value.activeProfileId
            ?.let { id -> repository.config.value.profiles.firstOrNull { it.id == id } }
        if (active != null && active.enabled && isProfileUsable(active)) active else null
    }

    override suspend fun recordActiveDuration(profileId: String, activeMillis: Long) {
        try {
            mutex.withLock {
            if (activeMillis <= 0L) return@withLock
            repository.update("record_profile_usage") { config ->
                if (config.profiles.none { it.id == profileId }) return@update config
                config.copy(
                    profiles = config.profiles.map { profile -> if (profile.id == profileId) profile.copy(
                        accumulatedRecordingMillis = profile.accumulatedRecordingMillis + activeMillis,
                    ) else profile },
                    rotation = config.rotation.copy(usageCycleRecordingMillis = config.rotation.usageCycleRecordingMillis + activeMillis),
                )
            }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0708", message = "Usage rotation accounting failed"))
        }
    }

    override suspend fun requestFailureRotation(profileId: String, failure: ProviderFailure): Boolean = try {
        mutex.withLock {
        if (!failure.isRotationEligible()) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.INFO, code = "AI-0709", message = "Provider failure does not qualify for profile rotation"))
            return@withLock false
        }
        var queued = false
        repository.update("request_failure_rotation") { config ->
            val active = config.activeProfileId
            queued = config.rotation.failureRotationEnabled && active == profileId
            if (!queued) config else config.copy(
                rotation = config.rotation.copy(
                    pendingTriggers = config.rotation.pendingTriggers + RotationTrigger.FAILURE,
                    pendingFailureProfileId = profileId,
                ),
            )
        }
        if (queued) pendingFailureTypes[profileId] = failure.javaClass.simpleName
        emitSafely(AiDiagnosticEvent(
            level = if (queued) DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
            code = if (queued) "AI-0701" else "AI-0709",
            message = if (queued) "Eligible provider failure queued for next session boundary" else "Eligible failure ignored because rotation is disabled or profile is no longer active",
            reason = buildString {
                append("profile=").append(profileId)
                append(" | failure=").append(failure.javaClass.simpleName)
                append(" | active=").append(repository.config.value.activeProfileId == profileId)
                append(" | rotationEnabled=").append(repository.config.value.rotation.failureRotationEnabled)
            },
        ))
        queued
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0708", message = "Failure rotation request was ignored"))
        false
    }

    override suspend fun failoverCandidates(fromProfileId: String): List<ApiProfile> = try {
        mutex.withLock {
            val usableProfileIds = mutableSetOf<String>()
            for (profile in repository.config.value.profiles) {
                if (catalog.supports(profile.providerId, profile.modelId) &&
                    isProviderInstalled(profile.providerId) && isProfileUsable(profile)) {
                    usableProfileIds += profile.id
                }
            }
            val ordered = repository.config.value.profiles
            val start = ordered.indexOfFirst { it.id == fromProfileId }
            if (start < 0) return@withLock emptyList()
            buildList {
                for (offset in 1..ordered.size) {
                    val candidate = ordered[(start + offset) % ordered.size]
                    if (candidate.id == fromProfileId) break
                    if (candidate.id in usableProfileIds) add(candidate)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0708", message = "Failover candidate resolution failed"))
        emptyList()
    }

    override suspend fun completeSession(sessionProfileId: String, nowWallMillis: Long, nowElapsedMillis: Long) {
        // Deliberately no selection here. Completion is a safe boundary, but selection stays at
        // the next start so a completed session can never be changed retrospectively.
    }

    private fun resolveFromActive(config: AiVoiceConfig, active: ApiProfile, wall: Long, elapsed: Long, usableProfileIds: Set<String>): Pair<AiVoiceConfig, ApiProfile> {
        // A newly active/manual profile starts its time windows at the next safe boundary.
        val anchoredConfig = config.copy(rotation = ensureTimeAnchors(config.rotation, wall, elapsed))
        val due = dueTriggers(anchoredConfig.rotation, wall, elapsed)
        if (due.isEmpty()) return anchoredConfig to active
        val winner = triggerPriority.first { it in due }
        val failedProfileId = anchoredConfig.rotation.pendingFailureProfileId
        if (winner == RotationTrigger.FAILURE && failedProfileId != null && failedProfileId != active.id) {
            // The pending failure belongs to a profile that is no longer active (a manual
            // selection superseded it). It may only disable that profile; it must never rotate
            // or disable the newly selected active profile.
            val retained = (anchoredConfig.rotation.pendingTriggers + due) - RotationTrigger.FAILURE
            (due - RotationTrigger.FAILURE).forEach { emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.INFO, code = "AI-0706", profileSerial = active.serialNumber, message = "Rotation trigger deferred by safe-boundary arbitration: $it")) }
            if (config.rotation.disableFailedProfile) {
                config.profiles.firstOrNull { it.id == failedProfileId }?.let { emitProfileDisabled(it, pendingFailureTypes.remove(failedProfileId)) }
            }
            return config.copy(
                profiles = if (config.rotation.disableFailedProfile) {
                    config.profiles.map { profile -> if (profile.id == failedProfileId) profile.copy(enabled = false) else profile }
                } else config.profiles,
                rotation = anchoredConfig.rotation.copy(pendingTriggers = retained, pendingFailureProfileId = null),
            ) to active
        }
        val next = nextEligible(config, active, usableProfileIds)
        if (next == null) {
            emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.WARNING, code = "AI-0703", profileSerial = active.serialNumber, message = "Rotation is due but no alternate eligible profile exists", reason = "cause=no alternate enabled, supported, installed profile"))
            return anchoredConfig.copy(rotation = anchoredConfig.rotation.copy(pendingTriggers = anchoredConfig.rotation.pendingTriggers + due)) to active
        }
        val retained = (anchoredConfig.rotation.pendingTriggers + due) - winner
        (due - winner).forEach { emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.INFO, code = "AI-0706", profileSerial = active.serialNumber, message = "Rotation trigger deferred by safe-boundary arbitration: $it")) }
        val disabledFrom = winner == RotationTrigger.FAILURE && config.rotation.disableFailedProfile
        if (disabledFrom) emitProfileDisabled(active, pendingFailureTypes.remove(active.id))
        diagnostics.info("AI-0707", next, "Profile rotated for $winner at session boundary",
            reason = buildString {
                append("ROTATION | from=").append(active.traceLabel())
                append(" | to=").append(next.traceLabel())
                append(" | reason=").append(winner.reasonLabel())
                append(" | disabled=").append(disabledFrom)
            })
        return config.copy(
            activeProfileId = next.id,
            // This uses the same durable enabled flag as the Profiles page toggle. It runs only
            // after failure rotation has selected an alternate profile, never on other triggers.
            profiles = if (winner == RotationTrigger.FAILURE && config.rotation.disableFailedProfile) {
                config.profiles.map { profile -> if (profile.id == active.id) profile.copy(enabled = false) else profile }
            } else config.profiles,
            rotation = resetForNewActiveProfile(
                anchoredConfig.rotation.copy(
                    pendingTriggers = retained,
                    pendingFailureProfileId = if (winner == RotationTrigger.FAILURE) null else anchoredConfig.rotation.pendingFailureProfileId,
                ),
                wall, elapsed,
            ),
        ) to next
    }

    private fun dueTriggers(rotation: RotationConfig, wall: Long, elapsed: Long): Set<RotationTrigger> = buildSet {
        addAll(rotation.pendingTriggers.filter { it != RotationTrigger.FAILURE || rotation.failureRotationEnabled })
        rotation.dayRotationIntervalDays?.let { days -> rotation.dayRotationAnchorEpochMillis?.let { anchor ->
            if (wall - anchor >= days * MILLIS_PER_DAY) add(RotationTrigger.DAY)
        } }
        rotation.clockRotationIntervalMillis?.let { interval ->
            val elapsedDue = rotation.clockRotationAnchorElapsedRealtime?.let { anchor -> elapsed >= anchor && elapsed - anchor >= interval } ?: false
            val rebootRecoveryDue = rotation.clockRotationAnchorEpochMillis?.let { anchor -> wall >= anchor && wall - anchor >= interval } ?: false
            if (elapsedDue || rebootRecoveryDue) add(RotationTrigger.CLOCK)
        }
        rotation.usageRotationLimitMillis?.let { limit -> if (rotation.usageCycleRecordingMillis >= limit) add(RotationTrigger.USAGE) }
    }

    private fun resetForNewActiveProfile(rotation: RotationConfig, wall: Long, elapsed: Long) = rotation.copy(
        dayRotationAnchorEpochMillis = wall,
        clockRotationAnchorEpochMillis = wall,
        clockRotationAnchorElapsedRealtime = elapsed,
        usageCycleRecordingMillis = 0L,
    )

    private fun ensureTimeAnchors(rotation: RotationConfig, wall: Long, elapsed: Long) = rotation.copy(
        dayRotationAnchorEpochMillis = rotation.dayRotationAnchorEpochMillis ?: wall,
        clockRotationAnchorEpochMillis = rotation.clockRotationAnchorEpochMillis ?: wall,
        clockRotationAnchorElapsedRealtime = rotation.clockRotationAnchorElapsedRealtime ?: elapsed,
    )

    private fun firstEligible(config: AiVoiceConfig, usableProfileIds: Set<String>) = config.profiles.firstOrNull { eligible(it, usableProfileIds) }
    private fun nextEligible(config: AiVoiceConfig, current: ApiProfile, usableProfileIds: Set<String>): ApiProfile? {
        val ordered = config.profiles
        if (ordered.size < 2) return null
        val start = ordered.indexOfFirst { it.id == current.id }
        for (offset in 1 until ordered.size) ordered[(start + offset) % ordered.size].let { if (eligible(it, usableProfileIds)) return it }
        return null
    }
    private fun eligible(profile: ApiProfile, usableProfileIds: Set<String>) = profile.enabled && profile.id in usableProfileIds
    private fun ProviderFailure.isRotationEligible() = this is ProviderFailure.Authentication || this is ProviderFailure.Authorization || this is ProviderFailure.RateLimited || this is ProviderFailure.ModelUnavailable
    private fun RotationTrigger.reasonLabel() = when (this) {
        RotationTrigger.DAY, RotationTrigger.CLOCK -> "time_boundary"
        RotationTrigger.USAGE -> "usage_limit"
        RotationTrigger.FAILURE -> "failure"
    }
    private fun emitProfileDisabled(profile: ApiProfile, failureType: String?) {
        emitSafely(AiDiagnosticEvent(
            level = DiagnosticLevel.ERROR, code = "AI-0710",
            profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId,
            message = "Failed profile disabled",
            reason = buildString {
                append("PROFILE_DISABLED | profile=").append(profile.traceLabel())
                append(" | cause=confirmed_profile_failure")
                failureType?.let { append(" | failure=").append(it) }
            },
        ))
    }
    private fun AiDiagnosticsSink.info(code: String, profile: ApiProfile, message: String, reason: String? = null) = emitSafely(AiDiagnosticEvent(level = DiagnosticLevel.INFO, code = code, profileSerial = profile.serialNumber, providerId = profile.providerId, modelId = profile.modelId, message = message, reason = reason))
    private fun emitSafely(event: AiDiagnosticEvent) {
        runCatching { diagnostics.emit(event) }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        val triggerPriority = listOf(RotationTrigger.FAILURE, RotationTrigger.DAY, RotationTrigger.CLOCK, RotationTrigger.USAGE)
    }
}
