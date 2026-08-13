/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Voice-result formatting layer for HeliBoard.  This reproduces the
 * observable Speechnotes dictation behaviors (voice punctuation/formatting,
 * spacing and sentence capitalization) without copying proprietary source.
 */
package helium314.keyboard.latin.voice

import java.util.Locale

object SpeechnotesVoiceResultProcessor {
    data class ProcessedResult(
        val text: String,
        val appendTrailingSpace: Boolean,
    )

    fun process(raw: String, locale: Locale): ProcessedResult {
        var text = raw.trim()
        if (text.isEmpty()) return ProcessedResult("", false)

        text = applyVoiceCommands(text, locale)
        text = normalizeWhitespace(text)
        text = capitalizeSentences(text, locale)
        text = normalizePunctuationSpacing(text)
        text = text.trim()

        if (text.isEmpty()) return ProcessedResult("", false)

        // Dictation normally leaves the cursor ready for the next spoken word.
        // Do not add a space after explicit punctuation/line breaks.
        val appendSpace = !text.endsWith(" ") &&
            !text.endsWith("\n") &&
            !endsWithSentenceOrClosingPunctuation(text)

        return ProcessedResult(text, appendSpace)
    }

    private fun applyVoiceCommands(input: String, locale: Locale): String {
        // The supplied Speechnotes Android build delegates recognition to the
        // OS recognizer. Its documented dictation commands are primarily
        // English phrases; never reinterpret ordinary Hindi/other-language
        // words as English punctuation commands.
        if (!locale.language.equals("en", ignoreCase = true)) return input

        // Longest phrases first. Word boundaries prevent accidental changes to
        // ordinary words such as "periodic" or "commas" inside a sentence.
        val commands = listOf(
            "new paragraph" to "\n\n",
            "new line" to "\n",
            "next line" to "\n",
            "open parentheses" to "(",
            "close parentheses" to ")",
            "open bracket" to "[",
            "close bracket" to "]",
            "open quotation" to "\"",
            "close quotation" to "\"",
            "open quote" to "\"",
            "close quote" to "\"",
            "question mark" to "?",
            "exclamation mark" to "!",
            "exclamation point" to "!",
            "semicolon" to ";",
            "colon" to ":",
            "full stop" to ".",
            "period" to ".",
            "comma" to ",",
            "hyphen" to "-",
            "dash" to "—",
        )

        var text = input
        for ((spoken, symbol) in commands) {
            val pattern = Regex("(?i)(?<!\\p{L})${Regex.escape(spoken)}(?!\\p{L})")
            text = pattern.replace(text, " $symbol ")
        }
        return text
    }

    private fun normalizeWhitespace(input: String): String {
        return input
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizePunctuationSpacing(input: String): String {
        return input
            .replace(Regex(" +([,.;:!?])"), "$1")
            .replace(Regex("([([{]) +"), "$1")
            .replace(Regex(" +([)\\]}])"), "$1")
            // Avoid creating a space before a line break.
            .replace(Regex(" +\\n"), "\n")
            // Keep a normal space after sentence punctuation when another
            // word follows on the same line.
            .replace(Regex("([,;:!?])(?=\\p{L})"), "$1 ")
        
    }

    private fun capitalizeSentences(input: String, locale: Locale): String {
        if (!locale.language.equals("en", ignoreCase = true)) return input
        val chars = input.toCharArray()
        var capitalizeNext = true
        for (i in chars.indices) {
            val c = chars[i]
            if (capitalizeNext && c.isLetter()) {
                chars[i] = c.uppercaseChar()
                capitalizeNext = false
            }
            if (c == '.' || c == '?' || c == '!' || c == '\n') {
                capitalizeNext = true
            }
        }
        return String(chars)
    }

    private fun endsWithSentenceOrClosingPunctuation(text: String): Boolean {
        return text.lastOrNull()?.let { it in charArrayOf('.', ',', '?', '!', ':', ';', ')', ']', '}', '"', '\'') } == true
    }
}
