// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.provider

import helium314.keyboard.latin.aivoice.domain.ApiProfile
import helium314.keyboard.latin.aivoice.language.KeyboardLanguageHint
import java.io.File

/**
 * Single provider-neutral construction boundary for speech requests. The live dispatcher must use
 * this factory instead of reading keyboard state or hard-coding a language itself.
 */
class TranscriptionRequestFactory(private val keyboardLanguageHint: KeyboardLanguageHint) {
    fun create(
        wavFile: File,
        profile: ApiProfile,
        apiKey: String,
        sessionId: String?,
        chunkSequence: Long?,
    ) = TranscriptionRequest(
        wavFile = wavFile,
        profile = profile,
        apiKey = apiKey,
        languageTag = keyboardLanguageHint.currentLanguageTag(),
        sessionId = sessionId,
        chunkSequence = chunkSequence,
    )
}
