// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.domain

/** Static metadata only; provider-specific HTTP behavior remains behind SpeechProvider. */
class BuiltInProviderCatalog : ProviderCatalog {
    private val values = listOf(
        ProviderDescriptor("groq", "Groq", listOf(
            ModelDescriptor("whisper-large-v3-turbo", "whisper-large-v3-turbo"),
            ModelDescriptor("whisper-large-v3", "whisper-large-v3"),
        )),
        ProviderDescriptor("google", "Google", listOf(ModelDescriptor("gemini-speech", "Gemini Speech"))),
        ProviderDescriptor("openai", "OpenAI", emptyList()),
        ProviderDescriptor("deepgram", "Deepgram", emptyList()),
    )

    override fun providers() = values
    override fun provider(providerId: String) = values.firstOrNull { it.id == providerId }
}
