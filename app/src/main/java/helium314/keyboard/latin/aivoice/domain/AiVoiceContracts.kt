// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.domain

import kotlinx.coroutines.flow.StateFlow

/** The only writer for persisted AI configuration. UI and runtime share this contract. */
interface AiVoiceSettingsRepository {
    val config: StateFlow<AiVoiceConfig>
    suspend fun update(reason: String, transform: (AiVoiceConfig) -> AiVoiceConfig): AiVoiceConfig
    suspend fun createProfile(draft: NewProfileDraft): ApiProfile
    suspend fun saveProfile(profile: ApiProfile): ApiProfile
    suspend fun deleteProfile(profileId: String)
    suspend fun setProfileEnabled(profileId: String, enabled: Boolean)
    /** Persists the exact user-defined ring order without changing profile serial numbers. */
    suspend fun reorderProfiles(profileIds: List<String>)
    suspend fun selectActiveProfile(profileId: String, recordingActive: Boolean)
}

data class NewProfileDraft(
    val displayName: String,
    val providerId: String,
    val modelId: String,
    val providerOptions: Map<String, String> = emptyMap(),
)

/** Secrets deliberately do not belong in [AiVoiceConfig], diagnostics, or saved UI state. */
interface ApiKeyStore {
    suspend fun read(profileId: String): String?
    suspend fun write(profileId: String, key: String)
    suspend fun delete(profileId: String)
}

interface ProviderCatalog {
    fun providers(): List<ProviderDescriptor>
    fun provider(providerId: String): ProviderDescriptor?
    fun supports(providerId: String, modelId: String): Boolean =
        provider(providerId)?.models?.any { it.id == modelId } == true
}
