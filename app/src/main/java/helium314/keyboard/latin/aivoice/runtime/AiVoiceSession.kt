// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

/** Small controller-owned lifecycle shared by isolated Recording and Live implementations. */
interface AiVoiceSession {
    /** Returns only after capture is confirmed active. */
    suspend fun start(): Long
    suspend fun stopGracefully()
    suspend fun cancelAndJoin()
    fun cancelCaptureImmediately()
    fun invalidateEditor()
    /** User edits fence Live composition; Recording keeps its established behavior. */
    fun onInputInteraction() = Unit
}
