// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.VoiceMode
import helium314.keyboard.latin.aivoice.live.EnrolledInstallationTokenProvider
import helium314.keyboard.latin.aivoice.provider.ProviderResolver

/** One mode-aware preflight shared by startup and every rotation boundary. */
class ProfileEligibility(
    private val catalog: ProviderCatalog,
    private val providerResolver: ProviderResolver,
    private val liveRuntimeAvailable: () -> Boolean,
) {
    suspend fun isEligible(profile: ApiProfile): Boolean {
        if (!profile.enabled || !catalog.supports(profile.providerId, profile.modelId, profile.mode)) return false
        return when (profile.mode) {
            VoiceMode.RECORDING -> providerResolver.isProfileUsable(profile)
            VoiceMode.LIVE -> liveRuntimeAvailable() && isValidLiveBackend(profile.liveBackendBaseUrl)
        }
    }

    private fun isValidLiveBackend(value: String?): Boolean =
        value != null && runCatching { EnrolledInstallationTokenProvider.validateOrigin(value) }.isSuccess
}
