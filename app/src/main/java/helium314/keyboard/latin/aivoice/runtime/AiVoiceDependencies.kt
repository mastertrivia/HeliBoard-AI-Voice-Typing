// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import android.content.Context
import helium314.keyboard.latin.aivoice.data.SharedPrefsAiVoiceSettingsRepository
import helium314.keyboard.latin.aivoice.data.EncryptedPrefsApiKeyStore
import helium314.keyboard.latin.aivoice.diagnostics.AiDiagnosticsRepository
import helium314.keyboard.latin.aivoice.domain.BuiltInProviderCatalog
import helium314.keyboard.latin.aivoice.language.HeliBoardKeyboardLanguageHint
import helium314.keyboard.latin.aivoice.provider.SpeechProviderRegistry
import helium314.keyboard.latin.aivoice.provider.GroqSpeechProvider
import helium314.keyboard.latin.aivoice.provider.GeminiSpeechProvider
import helium314.keyboard.latin.aivoice.provider.ProviderResolver
import helium314.keyboard.latin.aivoice.provider.TranscriptionRequestFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide dependency owner. It contains no IME, view, audio, or InputConnection reference.
 * LatinIME creates its controller from this object only once live capture is intentionally enabled.
 */
class AiVoiceDependencies private constructor(context: Context) {
    val catalog = BuiltInProviderCatalog()
    val diagnostics = AiDiagnosticsRepository()
    val settingsRepository = SharedPrefsAiVoiceSettingsRepository(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), catalog, diagnostics = diagnostics,
    )
    val apiKeyStore = EncryptedPrefsApiKeyStore(context.applicationContext)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
    val providers = SpeechProviderRegistry(setOf(
        GroqSpeechProvider(httpClient, catalog),
        GeminiSpeechProvider(httpClient, catalog),
    ))
    val providerResolver = ProviderResolver(catalog, apiKeyStore, providers, settingsRepository, diagnostics)
    /** Future audio dispatcher uses this to snapshot the active HeliBoard subtype per request. */
    val transcriptionRequestFactory = TranscriptionRequestFactory(HeliBoardKeyboardLanguageHint())
    val rotationCoordinator: RotationCoordinator = DefaultRotationCoordinator(
        repository = settingsRepository,
        catalog = catalog,
        isProviderInstalled = providers::isInstalled,
        isProfileUsable = providerResolver::isProfileUsable,
        diagnostics = diagnostics,
    )
    val runtimeState = AiVoiceRuntimeStateHolder()

    companion object {
        private const val PREFS_NAME = "ai_voice_engine"
        @Volatile private var instance: AiVoiceDependencies? = null

        fun initialize(context: Context): AiVoiceDependencies = instance ?: synchronized(this) {
            instance ?: AiVoiceDependencies(context.applicationContext).also { instance = it }
        }

        fun get(): AiVoiceDependencies = checkNotNull(instance) { "AiVoiceDependencies was not initialized" }
    }
}
