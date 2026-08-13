package helium314.keyboard.latin.personalization

import android.content.Context
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Owns the dedicated Desh-English learned dictionary. */
object DeshEnglishLearningManager {
    @Volatile private var context: Context? = null
    private val dictionaries = ConcurrentHashMap<String, DeshEnglishLearnedDictionary>()

    fun initialize(context: Context) {
        if (this.context == null) {
            synchronized(this) {
                if (this.context == null) this.context = context.applicationContext
            }
        }
    }

    private fun get(locale: Locale): DeshEnglishLearnedDictionary? {
        val c = context ?: return null
        val key = locale.toLanguageTag()
        dictionaries[key]?.let { return it }
        synchronized(this) {
            dictionaries[key]?.let { return it }
            return DeshEnglishLearnedDictionary(c, locale).also { dictionaries[key] = it }
        }
    }

    fun learn(locale: Locale, context: NgramContext, word: String, timestamp: Int) {
        get(locale)?.learn(context, word, timestamp)
    }

    fun suggestions(
        locale: Locale,
        data: ComposedData,
        context: NgramContext,
        settings: SettingsValuesForSuggestion,
        sessionId: Int,
    ): List<SuggestedWordInfo> = get(locale)?.suggestions(data, context, settings, sessionId)
        ?: emptyList()
}
