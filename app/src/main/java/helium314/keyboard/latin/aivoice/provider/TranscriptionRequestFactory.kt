// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.provider

import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageBehavior
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageHint
import helium314.keyboard.latin.aivoice.language.currentBehavior
import java.io.File

/**
 * Single provider-neutral construction boundary for speech requests. The live dispatcher must use
 * this factory instead of reading keyboard state or hard-coding a language itself.
 */
class TranscriptionRequestFactory(private val keyboardLanguageHint: KeyboardLanguageHint) {
    /** Snapshot once when a Live session starts; Recording snapshots once for each provider request. */
    fun currentBehavior(): KeyboardLanguageBehavior = keyboardLanguageHint.currentBehavior()

    fun create(
        wavFile: File,
        profile: ApiProfile,
        apiKey: String,
        sessionId: String?,
        chunkSequence: Long?,
    ): TranscriptionRequest {
        val behavior = keyboardLanguageHint.currentBehavior()
        return TranscriptionRequest(
            wavFile = wavFile,
            profile = profile,
            apiKey = apiKey,
            languageBehavior = behavior,
            sessionId = sessionId,
            chunkSequence = chunkSequence,
        )
    }
}
