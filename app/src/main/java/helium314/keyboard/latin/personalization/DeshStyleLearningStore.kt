package helium314.keyboard.latin.personalization

import android.content.Context
import helium314.keyboard.latin.NgramContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Fast, local, per-locale personal learner inspired by aggressive learned-word systems.
 *
 * It intentionally does not modify the system UserDictionary. Every accepted word is available
 * to the suggestion pipeline immediately after one use, together with a small context model.
 */
class DeshStyleLearningStore private constructor(private val context: Context) {
    data class LearnedWord(val word: String, val score: Int)

    private data class Entry(
        var count: Int = 0,
        var lastUsed: Long = 0L,
        val contexts: MutableMap<String, Int> = mutableMapOf()
    )

    private val cache = ConcurrentHashMap<String, MutableMap<String, Entry>>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val maxEntries = 5000
    private val maxContextsPerWord = 8

    companion object {
        @Volatile private var instance: DeshStyleLearningStore? = null

        @JvmStatic
        fun initialize(context: Context) {
            get(context)
        }

        @JvmStatic
        fun get(context: Context): DeshStyleLearningStore =
            instance ?: synchronized(this) {
                instance ?: DeshStyleLearningStore(context.applicationContext).also { instance = it }
            }

        @JvmStatic
        fun get(): DeshStyleLearningStore? = instance
    }

    fun record(locale: Locale, word: String, ngramContext: NgramContext) {
        val normalized = normalize(word) ?: return
        val key = localeKey(locale)
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            val map = load(key)
            val entry = map.getOrPut(normalized) { Entry() }
            entry.count = minOf(entry.count + 1, 1_000_000)
            entry.lastUsed = System.currentTimeMillis()

            val contextKey = makeContextKey(ngramContext)
            if (contextKey.isNotEmpty()) {
                entry.contexts[contextKey] = minOf((entry.contexts[contextKey] ?: 0) + 1, 1_000_000)
                if (entry.contexts.size > maxContextsPerWord) {
                    val toDrop = entry.contexts.entries.minByOrNull { it.value }?.key
                    if (toDrop != null) entry.contexts.remove(toDrop)
                }
            }
            prune(map)
            save(key, map)
        }
    }

    fun prefixCandidates(locale: Locale, prefix: String, limit: Int = 4): List<LearnedWord> {
        val p = prefix.trim()
        if (p.isEmpty()) return emptyList()
        val map = load(localeKey(locale))
        return map.entries.asSequence()
            .filter { it.key.startsWith(p, ignoreCase = true) }
            .map { (word, entry) ->
                val ageHours = max(0L, (System.currentTimeMillis() - entry.lastUsed) / 3_600_000L)
                val recencyBoost = max(0, 200 - ageHours.toInt().coerceAtMost(200))
                LearnedWord(word, 2_000_000 + entry.count.coerceAtMost(1000) * 250 + recencyBoost)
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    fun nextWordCandidates(locale: Locale, ngramContext: NgramContext, limit: Int = 4): List<LearnedWord> {
        val key = makeContextKey(ngramContext)
        if (key.isEmpty()) return emptyList()
        val map = load(localeKey(locale))
        return map.entries.asSequence()
            .mapNotNull { (word, entry) ->
                val contextCount = entry.contexts[key] ?: return@mapNotNull null
                val ageHours = max(0L, (System.currentTimeMillis() - entry.lastUsed) / 3_600_000L)
                val recencyBoost = max(0, 150 - ageHours.toInt().coerceAtMost(150))
                LearnedWord(word, 1_500_000 + contextCount.coerceAtMost(1000) * 800 + entry.count.coerceAtMost(500) * 100 + recencyBoost)
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    fun clear(locale: Locale) {
        val key = localeKey(locale)
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            cache.remove(key)
            fileFor(key).delete()
        }
    }

    private fun normalize(word: String): String? {
        val trimmed = word.trim()
        if (trimmed.length < 2 || trimmed.length > 80) return null
        if (trimmed.any { it.isWhitespace() }) return null
        if (trimmed.all { it.isDigit() || it in "-+().,/%:" }) return null
        return trimmed
    }

    private fun localeKey(locale: Locale): String =
        locale.toLanguageTag().replace("-", "_").ifBlank { "und" }

    private fun makeContextKey(context: NgramContext): String =
        context.extractPrevWordsContextArray()
            .filter { it.isNotBlank() && it != NgramContext.BEGINNING_OF_SENTENCE_TAG }
            .take(2)
            .joinToString(" ")
            .trim()

    private fun load(key: String): MutableMap<String, Entry> = cache.getOrPut(key) {
        val result = mutableMapOf<String, Entry>()
        val file = fileFor(key)
        if (!file.exists()) return@getOrPut result
        runCatching {
            val root = JSONObject(file.readText())
            val words = root.optJSONObject("words") ?: return@runCatching
            val keys = words.keys()
            while (keys.hasNext()) {
                val word = keys.next()
                val obj = words.optJSONObject(word) ?: continue
                val entry = Entry(obj.optInt("count", 0), obj.optLong("last", 0L))
                val contexts = obj.optJSONObject("contexts") ?: JSONObject()
                val cKeys = contexts.keys()
                while (cKeys.hasNext()) {
                    val c = cKeys.next()
                    entry.contexts[c] = contexts.optInt(c, 0)
                }
                if (entry.count > 0) result[word] = entry
            }
        }
        result
    }

    private fun save(key: String, map: Map<String, Entry>) {
        val root = JSONObject()
        val words = JSONObject()
        map.forEach { (word, entry) ->
            val obj = JSONObject()
                .put("count", entry.count)
                .put("last", entry.lastUsed)
            val contexts = JSONObject()
            entry.contexts.forEach { (context, count) -> contexts.put(context, count) }
            obj.put("contexts", contexts)
            words.put(word, obj)
        }
        root.put("version", 1)
        root.put("words", words)
        val file = fileFor(key)
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    private fun prune(map: MutableMap<String, Entry>) {
        if (map.size <= maxEntries) return
        val removeCount = map.size - maxEntries
        repeat(removeCount) {
            val victim = map.minByOrNull { it.value.count.toLong() * 10 + (it.value.lastUsed / 86_400_000L) }?.key
            if (victim != null) map.remove(victim)
        }
    }

    private fun fileFor(key: String): File = File(File(context.filesDir, "desh_style_learning"), "$key.json").also {
        it.parentFile?.mkdirs()
    }
}
