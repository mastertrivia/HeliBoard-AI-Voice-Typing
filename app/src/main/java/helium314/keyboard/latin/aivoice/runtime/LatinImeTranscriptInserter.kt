// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

import android.inputmethodservice.InputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves the editor only at insertion time so an old InputConnection is never retained. */
internal class LatinImeTranscriptInserter(
    private val ime: InputMethodService,
) : TranscriptInserter {
    override suspend fun insert(
        text: String,
        sessionId: String,
        chunkSequence: Long,
        sessionAllowsInsertion: () -> Boolean,
    ): InsertResult =
        withContext(Dispatchers.Main.immediate) {
            if (text.isBlank()) return@withContext InsertResult.Rejected
            // This check happens on Main immediately before resolving the editor. It prevents a
            // queued graceful drain from committing an old session into a newly focused editor.
            if (!sessionAllowsInsertion()) return@withContext InsertResult.NoConnection
            val connection = ime.currentInputConnection ?: return@withContext InsertResult.NoConnection
            var beganBatchEdit = false
            try {
                beganBatchEdit = connection.beginBatchEdit()
                if (connection.commitText(text, 1)) InsertResult.Inserted else InsertResult.Rejected
            } catch (_: Exception) {
                InsertResult.Rejected
            } finally {
                if (beganBatchEdit) runCatching { connection.endBatchEdit() }
            }
        }
}
