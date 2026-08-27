package helium314.keyboard.latin

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused tests for the Desh Hindi composer state machine. */
class DeshHindiComposerTest {

    private class FakeHost : DeshHindiComposer.Host {
        val buffer = StringBuilder()
        var composingStart = -1
        var composingEnd = -1

        override fun getTextBeforeCursor(maxLength: Int): String {
            val before = if (composingStart >= 0) buffer.substring(0, composingStart) else buffer.toString()
            return before.takeLast(maxLength)
        }

        override fun setComposingText(text: String) {
            // replace the composing region with the new text
            if (composingStart >= 0 && composingEnd > composingStart)
                buffer.delete(composingStart, composingEnd)
            composingStart = buffer.length
            buffer.append(text)
            composingEnd = buffer.length
        }

        override fun commitText(text: String) {
            // commit composing region
            if (composingStart >= 0 && composingEnd > composingStart)
                buffer.delete(composingStart, composingEnd)
            buffer.append(text)
            composingStart = -1
            composingEnd = -1
        }

        override fun deleteSurroundingText(before: Int) {
            if (composingStart >= 0) {
                // InputConnection deletes only the requested preceding text; an
                // unchanged composing span is removed when that request covers it.
                val deleteStart = maxOf(0, composingEnd - before)
                buffer.delete(deleteStart, composingEnd)
                composingStart = -1
                composingEnd = -1
            } else {
                buffer.delete(maxOf(0, buffer.length - before), buffer.length)
            }
        }

        override fun finishComposingText() {
            composingStart = -1
            composingEnd = -1
        }
    }

    @Test
    fun consonantEnablesVowelDiacriticMode() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)
        assertFalse("empty buffer => no vowel mode", composer.isVowelDiacriticMode())
        assertTrue("क is a consonant", composer.onKey('क'.code))
        assertTrue("after a bare consonant the vowel keys must become matras", composer.isVowelDiacriticMode())
    }

    @Test
    fun matraAfterConsonantBuildsSyllableAndEndsVowelMode() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)
        composer.onKey('क'.code)
        assertTrue("मात्रा ा attaches after क", composer.onKey('ा'.code))
        assertEquals("composed text is का", "का", host.buffer.toString())
        assertFalse("का is a complete syllable, vowel mode off", composer.isVowelDiacriticMode())
    }

    @Test
    fun standaloneVowelNotAfterConsonantCommits() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)
        composer.onKey('क'.code)
        composer.onKey('ा'.code)
        // Standalone vowel after a complete syllable: the composer commits the
        // syllable and returns false so the vowel is inserted by plain HeliBoard.
        assertFalse("standalone vowel key must not return true (falls back to plain insertion)", composer.onKey('इ'.code))
        assertEquals("का committed before the standalone vowel", "का", host.buffer.toString())
        // The composer no longer holds any pending syllable.
        assertFalse("no active syllable after commit", composer.hasActiveSyllable())
    }

    @Test
    fun halantThenConsonantBuildsConjunct() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)
        composer.onKey('क'.code)
        assertTrue("हलंत ् after क", composer.onKey('्'.code))
        // क + ् + त = क्त
        assertTrue("त after क् builds conjunct क्त", composer.onKey('त'.code))
        assertEquals("conjunct composed", "क्त", host.buffer.toString())
    }

    @Test
    fun backspaceDeletesFromSyllable() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)
        composer.onKey('क'.code)
        composer.onKey('ा'.code)
        composer.onKey(KeyCode.DELETE)
        assertEquals("backspace removes the matra only", "क", host.buffer.toString())
    }

    @Test
    fun committedWordLeavesNoVowelMode() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)
        composer.onKey('क'.code)
        composer.onKey('ा'.code)
        composer.onKey(' '.code) // word boundary -> commit
        assertEquals("का committed", "का", host.buffer.toString())
        assertFalse("after commit, no vowel mode", composer.isVowelDiacriticMode())
    }

    @Test
    fun dedicatedConjunctsComposeAtomicallyAndSpaceFallsThroughOnce() {
        for (conjunct in listOf("क्ष", "त्र", "ज्ञ", "श्र")) {
            val host = FakeHost()
            val composer = DeshHindiComposer(host)

            assertTrue("$conjunct is handled as a single composer unit", composer.onText(conjunct))
            assertEquals("$conjunct is the only composing text", conjunct, host.buffer.toString())
            assertTrue("$conjunct remains an active composition", composer.hasActiveSyllable())

            // The composer finishes the span; normal InputLogic is still responsible
            // for inserting the actual space after the false return.
            assertFalse("Space must use normal insertion after $conjunct", composer.onKey(' '.code))
            host.commitText(" ")
            assertEquals("$conjunct is not duplicated before Space", "$conjunct ", host.buffer.toString())
            assertFalse("Space ends the $conjunct composition", composer.hasActiveSyllable())
        }
    }

    @Test
    fun dedicatedConjunctsDeleteAsOneUnchangedAtomicUnit() {
        for (conjunct in listOf("क्ष", "त्र", "ज्ञ", "श्र")) {
            val host = FakeHost()
            val composer = DeshHindiComposer(host)

            composer.onText(conjunct)
            assertTrue("one Backspace handles $conjunct", composer.onKey(KeyCode.DELETE))
            assertEquals("one Backspace removes all of $conjunct", "", host.buffer.toString())
            assertFalse("$conjunct deletion leaves no active composition", composer.hasActiveSyllable())
        }
    }

    @Test
    fun ordinaryHindiHalantCompositionStillWorks() {
        val host = FakeHost()
        val composer = DeshHindiComposer(host)

        composer.onKey('क'.code)
        composer.onKey('्'.code)
        assertTrue("ordinary Hindi consonant composition remains handled", composer.onKey('त'.code))
        assertEquals("ordinary conjunct composition is unchanged", "क्त", host.buffer.toString())
    }
}
