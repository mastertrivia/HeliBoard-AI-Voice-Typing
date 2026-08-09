// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aivoice.runtime

/**
 * LatinIME-owned insertion boundary. Implementations must resolve the current InputConnection only
 * at invocation time on Main.immediate; they must never retain an editor reference or retry later.
 */
interface TranscriptInserter {
    suspend fun insert(
        text: String,
        sessionId: String,
        chunkSequence: Long,
        sessionAllowsInsertion: () -> Boolean,
    ): InsertResult
}

sealed interface InsertResult {
    data object Inserted : InsertResult
    data object NoConnection : InsertResult
    data object Rejected : InsertResult
}
