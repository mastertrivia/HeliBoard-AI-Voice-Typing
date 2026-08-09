package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.NewProfileDraft
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.ProviderDescriptor
import helium314.keyboard.latin.aivoice.domain.RotationConfig
import helium314.keyboard.latin.aivoice.provider.ProviderFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit coverage for the single-job failover candidate order and its eligibility gating. */
@RunWith(RobolectricTestRunner::class)
class DefaultRotationCoordinatorFailoverTest {

    private class FakeRepository(initial: AiVoiceConfig) : AiVoiceSettingsRepository {
        override val config = MutableStateFlow(initial)
        override suspend fun update(reason: String, transform: (AiVoiceConfig) -> AiVoiceConfig): AiVoiceConfig {
            val next = transform(config.value)
            config.value = next
            return next
        }
        override suspend fun createProfile(draft: NewProfileDraft): ApiProfile = error("unused")
        override suspend fun saveProfile(profile: ApiProfile): ApiProfile = error("unused")
        override suspend fun deleteProfile(profileId: String) = error("unused")
        override suspend fun setProfileEnabled(profileId: String, enabled: Boolean) = error("unused")
        override suspend fun reorderProfiles(profileIds: List<String>) = error("unused")
        override suspend fun selectActiveProfile(profileId: String, recordingActive: Boolean) = error("unused")
    }

    private fun profile(id: String, serial: Int, enabled: Boolean = true) = ApiProfile(
        id = id,
        serialNumber = serial,
        displayName = id,
        providerId = "groq",
        modelId = "m",
        enabled = enabled,
    )

    private fun coordinator(
        profiles: List<ApiProfile>,
        activeId: String? = null,
        failureRotationEnabled: Boolean = true,
        isProfileUsable: (ApiProfile) -> Boolean = { it.enabled },
    ): Pair<DefaultRotationCoordinator, FakeRepository> {
        val repo = FakeRepository(AiVoiceConfig(
            activeProfileId = activeId ?: profiles.firstOrNull()?.id,
            profiles = profiles,
            rotation = RotationConfig(failureRotationEnabled = failureRotationEnabled),
        ))
        val catalog = object : ProviderCatalog {
            override fun providers(): List<ProviderDescriptor> = emptyList()
            override fun provider(providerId: String): ProviderDescriptor? = null
            override fun supports(providerId: String, modelId: String): Boolean = true
        }
        val coordinator = DefaultRotationCoordinator(
            repository = repo,
            catalog = catalog,
            isProviderInstalled = { true },
            isProfileUsable = isProfileUsable,
            diagnostics = AiDiagnosticsSink { },
        )
        return coordinator to repo
    }

    @Test
    fun `candidates follow the configured ring order after the failed profile`() = runBlocking {
        val (c, _) = coordinator(listOf(profile("a", 1), profile("b", 2), profile("c", 3)), activeId = "a")
        assertEquals(listOf("b", "c"), c.failoverCandidates("a").map { it.id })
    }

    @Test
    fun `candidates wrap around to the start of the ring`() = runBlocking {
        val (c, _) = coordinator(listOf(profile("b", 1), profile("a", 2), profile("c", 3)), activeId = "a")
        assertEquals(listOf("c", "b"), c.failoverCandidates("a").map { it.id })
    }

    @Test
    fun `wraparound is bounded to one complete cycle`() = runBlocking {
        val (c, _) = coordinator(listOf(profile("a", 1), profile("b", 2), profile("c", 3), profile("d", 4)), activeId = "a")
        assertEquals(listOf("b", "c", "d"), c.failoverCandidates("a").map { it.id })
    }

    @Test
    fun `disabled profiles are skipped`() = runBlocking {
        val (c, _) = coordinator(listOf(profile("a", 1), profile("b", 2, enabled = false), profile("c", 3)), activeId = "a")
        assertEquals(listOf("c"), c.failoverCandidates("a").map { it.id })
    }

    @Test
    fun `profiles unusable for other reasons are skipped`() = runBlocking {
        val (c, _) = coordinator(
            listOf(profile("a", 1), profile("b", 2), profile("c", 3)),
            activeId = "a",
            isProfileUsable = { it.id != "b" },
        )
        assertEquals(listOf("c"), c.failoverCandidates("a").map { it.id })
    }

    @Test
    fun `empty when only one profile exists`() = runBlocking {
        val (c, _) = coordinator(listOf(profile("a", 1)), activeId = "a")
        assertTrue(c.failoverCandidates("a").isEmpty())
    }

    @Test
    fun `empty when the failed profile is unknown`() = runBlocking {
        val (c, _) = coordinator(listOf(profile("a", 1), profile("b", 2)), activeId = "a")
        assertTrue(c.failoverCandidates("missing").isEmpty())
    }

    @Test
    fun `requestFailureRotation queues only rotation eligible failures`() = runBlocking {
        val (c, repo) = coordinator(listOf(profile("a", 1), profile("b", 2)), activeId = "a")
        assertTrue(c.requestFailureRotation("a", ProviderFailure.Authentication))
        assertEquals("a", repo.config.value.rotation.pendingFailureProfileId)
        assertFalse(c.requestFailureRotation("a", ProviderFailure.InvalidRequest))
        assertEquals("a", repo.config.value.rotation.pendingFailureProfileId, "non-eligible failures must not change rotation state")
    }

    @Test
    fun `requestFailureRotation is ignored while the profile is no longer active`() = runBlocking {
        val (c, repo) = coordinator(listOf(profile("a", 1), profile("b", 2)), activeId = "a")
        assertFalse(c.requestFailureRotation("b", ProviderFailure.Authentication))
        assertNull(repo.config.value.rotation.pendingFailureProfileId)
    }

    @Test
    fun `requestFailureRotation returns false when rotation is disabled`() = runBlocking {
        val (c, repo) = coordinator(
            listOf(profile("a", 1), profile("b", 2)),
            activeId = "a",
            failureRotationEnabled = false,
        )
        assertFalse(c.requestFailureRotation("a", ProviderFailure.Authentication))
        assertNull(repo.config.value.rotation.pendingFailureProfileId)
    }
}
