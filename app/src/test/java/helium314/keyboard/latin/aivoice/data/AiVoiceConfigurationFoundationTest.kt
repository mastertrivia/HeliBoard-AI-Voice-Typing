package helium314.keyboard.latin.aivoice.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.aivoice.domain.AiVoiceConfig
import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.domain.BuiltInProviderCatalog
import helium314.keyboard.latin.aivoice.domain.NewProfileDraft
import helium314.keyboard.latin.aivoice.domain.VoiceMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AiVoiceConfigurationFoundationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `v6 decodable profiles migrate explicitly to recording`() {
        val profile = recordingProfile("v6")
        val stored = json.encodeToString(AiVoiceConfig(schemaVersion = 6, profiles = listOf(profile), nextProfileSerial = 2))
        val repository = repositoryWith(stored)

        assertEquals(AiVoiceConfig.SCHEMA_VERSION, repository.config.value.schemaVersion)
        assertEquals(VoiceMode.RECORDING, repository.config.value.profiles.single().mode)
        assertNull(repository.config.value.profiles.single().liveBackendBaseUrl)
        assertEquals(profile.copy(mode = VoiceMode.RECORDING, liveBackendBaseUrl = null), repository.config.value.profiles.single())
    }

    @Test
    fun `older decodable profiles migrate to recording without changing recording semantics`() {
        val profile = recordingProfile("v1").copy(enabled = false, providerOptions = mapOf("language" to "en"))
        val stored = json.encodeToString(AiVoiceConfig(schemaVersion = 1, profiles = listOf(profile), nextProfileSerial = 2))
        val migrated = repositoryWith(stored).config.value.profiles.single()

        assertEquals(VoiceMode.RECORDING, migrated.mode)
        assertNull(migrated.liveBackendBaseUrl)
        assertEquals(profile.enabled, migrated.enabled)
        assertEquals(profile.providerId, migrated.providerId)
        assertEquals(profile.modelId, migrated.modelId)
        assertEquals(profile.providerOptions, migrated.providerOptions)
    }

    @Test
    fun `catalog preserves existing models and declares mode compatibility`() {
        val catalog = BuiltInProviderCatalog()

        assertTrue(catalog.supports("groq", "whisper-large-v3-turbo", VoiceMode.RECORDING))
        assertTrue(catalog.supports("groq", "whisper-large-v3", VoiceMode.RECORDING))
        assertTrue(catalog.supports("google", "gemini-3.5-flash-lite", VoiceMode.RECORDING))
        assertTrue(catalog.supports("google", "gemini-3.6-flash", VoiceMode.RECORDING))
        assertTrue(catalog.supports("google", "gemini-flash-latest", VoiceMode.RECORDING))
        assertFalse(catalog.supports("groq", "whisper-large-v3", VoiceMode.LIVE))
        assertFalse(catalog.supports("google", "gemini-flash-latest", VoiceMode.LIVE))
        assertTrue(catalog.supports("google", "gemini-3.1-flash-live-preview", VoiceMode.LIVE))
        assertFalse(catalog.supports("google", "gemini-3.1-flash-live-preview", VoiceMode.RECORDING))
        assertEquals("Gemini 3.1 Flash Live", catalog.provider("google")!!.models.single {
            it.id == "gemini-3.1-flash-live-preview"
        }.label)
    }

    @Test
    fun `live profile validation requires exact model and credential-free https backend`() {
        runBlocking {
            val repository = repositoryWith(null)
            val valid = repository.createProfile(NewProfileDraft(
                displayName = "Live",
                providerId = "google",
                modelId = "gemini-3.1-flash-live-preview",
                mode = VoiceMode.LIVE,
                liveBackendBaseUrl = "https://voice.example.test/root/",
            ))
            assertEquals("https://voice.example.test/root", valid.liveBackendBaseUrl)

            assertFailsWith<IllegalArgumentException> {
                repository.createProfile(NewProfileDraft("Wrong model", "google", "gemini-flash-latest", VoiceMode.LIVE, "https://voice.example.test"))
            }
            assertFailsWith<IllegalArgumentException> {
                repository.createProfile(NewProfileDraft("Wrong provider", "groq", "whisper-large-v3", VoiceMode.LIVE, "https://voice.example.test"))
            }
            assertFailsWith<IllegalArgumentException> {
                repository.createProfile(NewProfileDraft("No TLS", "google", "gemini-3.1-flash-live-preview", VoiceMode.LIVE, "http://voice.example.test"))
            }
            assertFailsWith<IllegalArgumentException> {
                repository.createProfile(NewProfileDraft("Credentials", "google", "gemini-3.1-flash-live-preview", VoiceMode.LIVE, "https://user:secret@voice.example.test"))
            }
        }
    }

    @Test
    fun `recording profile semantics reject live configuration but retain existing model`() {
        runBlocking {
            val repository = repositoryWith(null)
            val created = repository.createProfile(NewProfileDraft(
                displayName = "Recording",
                providerId = "google",
                modelId = "gemini-flash-latest",
            ))

            assertEquals(VoiceMode.RECORDING, created.mode)
            assertNull(created.liveBackendBaseUrl)
            assertFailsWith<IllegalArgumentException> {
                repository.createProfile(NewProfileDraft(
                    "Recording with backend", "google", "gemini-flash-latest", VoiceMode.RECORDING,
                    "https://voice.example.test",
                ))
            }
        }
    }

    private fun repositoryWith(stored: String?): SharedPrefsAiVoiceSettingsRepository {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (stored != null) prefs.edit().putString(CONFIG_KEY, stored).commit()
        return SharedPrefsAiVoiceSettingsRepository(prefs, json = json)
    }

    private fun recordingProfile(id: String) = ApiProfile(
        id = id,
        serialNumber = 1,
        displayName = "Existing",
        providerId = "google",
        modelId = "gemini-flash-latest",
    )

    private companion object {
        const val PREFS_NAME = "ai_voice_configuration_foundation_test"
        const val CONFIG_KEY = "ai_voice_config_v1"
    }
}
