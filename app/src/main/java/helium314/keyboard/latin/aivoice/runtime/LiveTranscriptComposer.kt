// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Session-owned composing span used only by Live transcription. */
interface LiveTranscriptComposer {
    /** False means the current editor rejected or no longer owns the composition. */
    suspend fun update(text: String): Boolean
    /** False means the final composition could not be committed to the current editor. */
    suspend fun finalizeLatest(): Boolean
    suspend fun cancel()
    fun invalidate()
}

/** Resolves and fences one editor generation on Main.immediate; no InputConnection outlives the session. */
internal class LatinImeLiveTranscriptComposer(
    private val ime: InputMethodService,
) : LiveTranscriptComposer {
    @Volatile private var valid = true
    private var connection: InputConnection? = null
    private var latest = ""
    private var finalized = false
    private var ownsComposition = false

    override suspend fun update(text: String): Boolean = onMain {
        if (!valid || finalized || text.isBlank()) return@onMain false
        val current = ime.currentInputConnection ?: run {
            invalidateOnMain()
            return@onMain false
        }
        val owned = connection
        if (owned != null && owned !== current) {
            invalidateOnMain()
            return@onMain false
        }
        connection = current
        if (current.setComposingText(text, 1)) {
            latest = text
            ownsComposition = true
            true
        } else {
            invalidateOnMain()
            false
        }
    }

    override suspend fun finalizeLatest(): Boolean = onMain {
        if (!valid || finalized) return@onMain false
        finalized = true
        val current = ime.currentInputConnection
        val owned = connection
        if (latest.isBlank() || owned == null || owned !== current) {
            invalidateOnMain()
            return@onMain false
        }
        if (owned.commitText(latest, 1)) {
            ownsComposition = false
            true
        } else {
            invalidateOnMain()
            false
        }
    }

    override suspend fun cancel() = onMain { invalidateOnMain() }

    override fun invalidate() {
        valid = false
        val owned = connection
        if (owned != null && ownsComposition) runCatching { owned.setComposingText("", 1) }
        ownsComposition = false
        connection = null
        latest = ""
    }

    private fun invalidateOnMain() {
        valid = false
        connection?.takeIf { ownsComposition }?.let { runCatching { it.setComposingText("", 1) } }
        ownsComposition = false
        connection = null
        latest = ""
    }

    private suspend inline fun <T> onMain(crossinline block: () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }
}
