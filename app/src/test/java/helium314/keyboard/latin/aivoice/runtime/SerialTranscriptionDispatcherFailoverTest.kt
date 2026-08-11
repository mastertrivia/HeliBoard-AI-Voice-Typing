package helium314.keyboard.latin.aivoice.runtime

import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticEvent
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsSink
import helium314.keyboard.latin.aivoice.diagnostics.DiagnosticLevel
import helium314.keyboard.latin.aivoice.domain.ApiKeyStore
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.AiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.domain.NewProfileDraft
import helium314.keyboard.latin.aivoice.domain.ProviderCatalog
import helium314.keyboard.latin.aivoice.domain.ProviderDescriptor
import helium314.keyboard.latin.aivoice.domain.RotationConfig
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageHint
import helium314.keyboard.latin.aivoice.provider.ProviderException
import helium314.keyboard.latin.aivoice.provider.ProviderFailure
import helium314.keyboard.latin.aivoice.provider.ProviderResolver
import helium314.keyboard.latin.aivoice.provider.SpeechProvider
import helium314.keyboard.latin.aivoice.provider.SpeechProviderRegistry
import helium314.keyboard.latin.aivoice.provider.TranscriptionRequest
import helium314.keyboard.latin.aivoice.provider.TranscriptionRequestFactory
import helium314.keyboard.latin.aivoice.provider.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end coverage for the serial dispatcher's per-chunk failover retry cycle. */
@RunWith(RobolectricTestRunner::class)
class SerialTranscriptionDispatcherFailoverTest {

    private enum class Script { SUCCESS, BLANK, FAIL_AUTH, FAIL_SERVER, FAIL_INVALID }

    private class RecordingProvider(
        override val providerId: String,
        private val script: (profileId: String) -> Script,
    ) : SpeechProvider {
        val requests = mutableListOf<TranscriptionRequest>()
        override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult {
            requests += request
            return when (script(request.profile.id)) {
                Script.SUCCESS -> TranscriptionResult("ok-${request.profile.id}")
                Script.BLANK -> TranscriptionResult("   ")
                Script.FAIL_AUTH -> throw ProviderException(ProviderFailure.Authentication)
                Script.FAIL_SERVER -> throw ProviderException(ProviderFailure.Server)
                Script.FAIL_INVALID -> throw ProviderException(ProviderFailure.InvalidRequest)
            }
        }
    }

    private class RecordingInserter : TranscriptInserter {
        val inserted = mutableListOf<Pair<String, Long>>()
        override suspend fun insert(
            text: String,
            sessionId: String,
            chunkSequence: Long,
            sessionAllowsInsertion: () -> Boolean,
        ): InsertResult {
            inserted += text to chunkSequence
            return InsertResult.Inserted
        }
    }

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

    private class FailoverHarness(
        profiles: List<ApiProfile>,
        val activeProfile: ApiProfile,
        script: (profileId: String) -> Script,
        failureRotationEnabled: Boolean = true,
        diagnostics: AiDiagnosticsSink,
    ) {
        val provider = RecordingProvider("groq", script)
        val repo = FakeRepository(AiVoiceConfig(
            activeProfileId = activeProfile.id,
            profiles = profiles,
            rotation = RotationConfig(failureRotationEnabled = failureRotationEnabled),
        ))
        val inserter = RecordingInserter()
        private val catalog = object : ProviderCatalog {
            override fun providers(): List<ProviderDescriptor> = emptyList()
            override fun provider(providerId: String): ProviderDescriptor? = null
            override fun supports(providerId: String, modelId: String): Boolean = true
        }
        private val keyStore = object : ApiKeyStore {
            override suspend fun read(profileId: String): String? = "key-$profileId"
            override suspend fun write(profileId: String, key: String) = Unit
            override suspend fun delete(profileId: String) = Unit
        }
        val coordinator = DefaultRotationCoordinator(
            repository = repo,
            catalog = catalog,
            isProviderInstalled = { true },
            isProfileUsable = { it.enabled },
            diagnostics = diagnostics,
        )
        private val resolver = ProviderResolver(catalog, keyStore, SpeechProviderRegistry(setOf(provider)), repo, diagnostics)

        fun dispatcher(parentScope: CoroutineScope, profile: ApiProfile) = SerialTranscriptionDispatcher(
            sessionId = "session-1",
            profile = profile,
            providerResolver = resolver,
            requestFactory = TranscriptionRequestFactory(KeyboardLanguageHint { null }),
            inserter = inserter,
            insertionGate = SessionInsertionGate(),
            rotationCoordinator = coordinator,
            diagnostics = diagnostics,
            parentScope = parentScope,
        )
    }

    private fun profile(id: String, serial: Int, enabled: Boolean = true) = ApiProfile(
        id = id,
        serialNumber = serial,
        displayName = id,
        providerId = "groq",
        modelId = "m",
        enabled = enabled,
    )

    private fun runScenario(
        profiles: List<ApiProfile>,
        active: ApiProfile,
        script: (profileId: String) -> Script,
        failureRotationEnabled: Boolean = true,
        block: suspend (FailoverHarness, AudioChunk, List<AiDiagnosticEvent>) -> Unit,
    ) {
        val events = mutableListOf<AiDiagnosticEvent>()
        runBlocking {
            val harness = FailoverHarness(profiles, active, script, failureRotationEnabled, AiDiagnosticsSink { events.add(it) })
            val chunk = AudioChunk(sequence = 1L, wavFile = File.createTempFile("ai-voice-test", ".wav"), durationMillis = 1_000L)
            val dispatcher = harness.dispatcher(this, active)
            dispatcher.enqueue(chunk)
            dispatcher.drain()
            block(harness, chunk, events.toList())
        }
    }

    @Test
    fun `fallback reuses the same audio payload and stops on success`() = runScenario(
        profiles = listOf(profile("a", 1), profile("b", 2), profile("c", 3)),
        active = profile("a", 1),
        script = { if (it == "a") Script.FAIL_AUTH else Script.SUCCESS },
    ) { harness, chunk, _ ->
        assertEquals(listOf("a", "b"), harness.provider.requests.map { it.profile.id })
        harness.provider.requests.forEach { request ->
            assertTrue(request.wavFile === chunk.wavFile, "every attempt must reuse the identical audio file")
        }
        assertEquals(setOf("session-1"), harness.provider.requests.map { it.sessionId }.toSet())
        assertEquals(setOf(1L), harness.provider.requests.map { it.chunkSequence }.toSet())
        assertTrue(harness.provider.requests.all { it.languageTag == null })
        val bRequest = harness.provider.requests.first { it.profile.id == "b" }
        assertEquals("key-b", bRequest.apiKey, "each fallback profile must be re-resolved for its own key")
        assertEquals(listOf("ok-b" to 1L), harness.inserter.inserted)
    }

    @Test
    fun `fallback follows ring order with bounded wraparound`() = runScenario(
        profiles = listOf(profile("b", 1), profile("a", 2), profile("c", 3)),
        active = profile("a", 2),
        script = { if (it == "b") Script.SUCCESS else if (it == "a") Script.FAIL_AUTH else Script.FAIL_SERVER },
    ) { harness, chunk, _ ->
        // After "a" the ring continues with c, then wraps to b, and stops before returning to a.
        assertEquals(listOf("a", "c", "b"), harness.provider.requests.map { it.profile.id })
        harness.provider.requests.forEach { assertTrue(it.wavFile === chunk.wavFile) }
        assertEquals(listOf("ok-b" to 1L), harness.inserter.inserted)
    }

    @Test
    fun `disabled profiles are skipped during fallback`() = runScenario(
        profiles = listOf(profile("a", 1), profile("b", 2, enabled = false), profile("c", 3)),
        active = profile("a", 1),
        script = { if (it == "c") Script.SUCCESS else Script.FAIL_AUTH },
    ) { harness, _, _ ->
        assertEquals(listOf("a", "c"), harness.provider.requests.map { it.profile.id })
        assertEquals(listOf("ok-c" to 1L), harness.inserter.inserted)
    }

    @Test
    fun `complete exhaustion reports AI-0604 and preserves the audio after the cycle`() = runScenario(
        profiles = listOf(profile("a", 1), profile("b", 2)),
        active = profile("a", 1),
        script = { if (it == "a") Script.FAIL_AUTH else Script.FAIL_SERVER },
    ) { harness, chunk, events ->
        assertEquals(listOf("a", "b"), harness.provider.requests.map { it.profile.id })
        assertTrue(harness.inserter.inserted.isEmpty())
        val exhausted = events.filter { it.code == "AI-0604" }
        assertEquals(1, exhausted.size)
        assertEquals(DiagnosticLevel.ERROR, exhausted.single().level)
        assertEquals(1L, exhausted.single().chunkSequence)
        assertTrue(chunk.wavFile.exists(), "the WAV must be retained when no profile delivered the transcript")
    }

    @Test
    fun `single profile exhaustion still reports AI-0604`() = runScenario(
        profiles = listOf(profile("a", 1)),
        active = profile("a", 1),
        script = { Script.FAIL_AUTH },
    ) { harness, _, events ->
        assertEquals(listOf("a"), harness.provider.requests.map { it.profile.id })
        assertTrue(harness.inserter.inserted.isEmpty())
        assertTrue(events.any { it.code == "AI-0604" })
    }

    @Test
    fun `any genuine failure enters the fallback cycle regardless of rotation eligibility`() = runScenario(
        profiles = listOf(profile("a", 1), profile("b", 2)),
        active = profile("a", 1),
        script = { Script.FAIL_INVALID },
    ) { harness, chunk, events ->
        assertEquals(listOf("a", "b"), harness.provider.requests.map { it.profile.id })
        assertTrue(harness.inserter.inserted.isEmpty())
        assertTrue(events.any { it.code == "AI-0604" })
        assertTrue(chunk.wavFile.exists(), "the WAV must be retained when the fallback cycle is exhausted")
    }

    @Test
    fun `blank transcription does not sweep all profiles`() = runScenario(
        profiles = listOf(profile("a", 1), profile("b", 2)),
        active = profile("a", 1),
        script = { Script.BLANK },
    ) { harness, _, events ->
        assertEquals(listOf("a"), harness.provider.requests.map { it.profile.id })
        assertTrue(harness.inserter.inserted.isEmpty())
        assertTrue(events.any { it.code == "AI-0601" })
        assertTrue(events.none { it.code == "AI-0604" })
    }
}
