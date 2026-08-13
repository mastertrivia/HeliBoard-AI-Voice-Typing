/*
 * Built-in Desh Hindi vocabulary metadata.
 *
 * The original Desh vocabulary is not an AOSP .dict file. It is a proprietary
 * native vocabulary/index consumed by libnativepredictor.so. We therefore keep
 * it as a built-in read-only vocabulary source instead of pretending it is an
 * AOSP BinaryDictionary file.
 */
package helium314.keyboard.latin

import android.content.Context
import java.io.IOException

object DeshHindiDictionaryInfo {
    const val ASSET = "desh_predictor/native_words.db"
    const val LANGUAGE_MODEL_ASSET = "desh_predictor/native_lm.db"

    /** Number of native vocabulary entries recorded in the supplied Desh DB header. */
    const val WORD_COUNT = 85_676

    fun isBundled(context: Context): Boolean = runCatching {
        context.assets.open(ASSET).use { true }
    }.getOrDefault(false)

    /**
     * Reads the native DB's entry count without decoding the proprietary word index.
     * The supplied Desh v17.4.9 file stores the count as a little-endian uint32 at
     * byte offset 4. This is only metadata validation; prediction still goes through
     * the native predictor.
     */
    fun readBundledWordCount(context: Context): Int? = runCatching {
        context.assets.open(ASSET).use { input ->
            val header = ByteArray(8)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) throw IOException("Short native_words.db header")
                read += n
            }
            (header[4].toInt() and 0xff) or
                ((header[5].toInt() and 0xff) shl 8) or
                ((header[6].toInt() and 0xff) shl 16) or
                ((header[7].toInt() and 0xff) shl 24)
        }
    }.getOrNull()
}
