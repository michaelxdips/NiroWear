package com.nirowear.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Voice input must ask for the language the screen is in.
 *
 * Measured before this: the `ACTION_RECOGNIZE_SPEECH` intent carried only `EXTRA_LANGUAGE_MODEL` and
 * `EXTRA_PROMPT`, so the recognizer used whatever the phone behind Gboard Wear was set to and an
 * Indonesian screen could dictate English. These are the two languages the app ships; anything else
 * falls back to the app's default, which is also what its strings fall back to.
 */
class VoiceLanguageTest {
    @Test
    fun englishVariantsAskForEnglish() {
        assertEquals("en-US", voiceLanguageTag(Locale.US))
        assertEquals("en-US", voiceLanguageTag(Locale.UK))
        assertEquals("en-US", voiceLanguageTag(Locale.forLanguageTag("en-AU")))
    }

    @Test
    fun indonesianVariantsAskForIndonesian() {
        // Android still reports the legacy "in" code on some builds, and "id" on others.
        assertEquals("in-ID", voiceLanguageTag(Locale("in", "ID")))
        assertEquals("in-ID", voiceLanguageTag(Locale.forLanguageTag("id-ID")))
        assertEquals("in-ID", voiceLanguageTag(Locale.forLanguageTag("in-SG")))
    }

    @Test
    fun anUnsupportedLanguageFallsBackToTheAppDefault() {
        // The app has no French strings, so it shows English and must ask for English too.
        assertEquals("en-US", voiceLanguageTag(Locale.FRANCE))
        assertEquals("en-US", voiceLanguageTag(Locale.JAPAN))
    }
}
