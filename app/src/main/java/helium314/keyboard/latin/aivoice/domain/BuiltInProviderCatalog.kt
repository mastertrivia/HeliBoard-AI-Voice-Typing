// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.domain

/** Static metadata only; provider-specific HTTP behavior remains behind SpeechProvider. */
class BuiltInProviderCatalog : ProviderCatalog {
    private val values = listOf(
        ProviderDescriptor("groq", "Groq", listOf(
            ModelDescriptor("whisper-large-v3-turbo", "whisper-large-v3-turbo"),
            ModelDescriptor("whisper-large-v3", "whisper-large-v3"),
        )),
        ProviderDescriptor("google", "Google", listOf(
            ModelDescriptor("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite — Fastest / cheapest"),
            ModelDescriptor("gemini-3.6-flash", "Gemini 3.6 Flash — Latest / recommended"),
            ModelDescriptor("gemini-flash-latest", "Gemini Flash Latest — Automatic latest Flash"),
        )),
        ProviderDescriptor("openai", "OpenAI", emptyList()),
        ProviderDescriptor("deepgram", "Deepgram", emptyList()),
    )

    override fun providers() = values
    override fun provider(providerId: String) = values.firstOrNull { it.id == providerId }
}
