package com.nirowear.settings

import com.nirowear.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialEntryTest {
    @Test
    fun sanitizeStripsWhitespaceAndInvisibleCharacters() {
        // Clipboard bridges routinely append a newline; SecretValue rejects it with a message that
        // does not name the offending character.
        assertEquals("sk-proj-abc", sanitizeKey("sk-proj-abc\n"))
        assertEquals("sk-proj-abc", sanitizeKey(" sk-proj-abc\r\n"))
        assertEquals("sk-proj-abc", sanitizeKey("sk-proj-abc\u200B"))
        assertEquals("sk-ant-api03-xyz", sanitizeKey("sk-ant-api03-xyz\t"))
    }

    @Test
    fun sanitizeKeepsEveryPrintableAsciiCharacter() {
        val key = "sk-" + (33..126).map { it.toChar() }.joinToString("")
        assertEquals(key, sanitizeKey(key))
    }

    @Test
    fun sanitizeResultAlwaysSatisfiesSecretValueContract() {
        // The invariant that matters: whatever the user pastes, the normalized key is storable.
        listOf("  ", "\n\n", "AIza\u00A0abc", "sk-proj-x\n\n").forEach { raw ->
            val cleaned = sanitizeKey(raw)
            if (cleaned.isNotEmpty()) {
                assertTrue(cleaned.all { it.code in 33..126 })
                com.nirowear.provider.SecretValue(cleaned)
            }
        }
    }

    @Test
    fun providerForKeyRecognizesEachPrefix() {
        assertEquals(ProviderType.ANTHROPIC, providerForKey("sk-ant-api03-abc"))
        assertEquals(ProviderType.OPENAI, providerForKey("sk-proj-abc"))
        assertEquals(ProviderType.OPENAI, providerForKey("sk-abc"))
        assertEquals(ProviderType.GEMINI, providerForKey("AIzaSyABC"))
        assertNull(providerForKey(""))
        assertNull(providerForKey("random-key"))
    }

    @Test
    fun mismatchNamesTheLikelyProviderAsACauseNotASentence() {
        // Returns the CAUSE so the screen can render it in the reader's language (#66). The sentence
        // itself now lives in R.string.provider_key_mismatch.
        assertEquals(ProviderType.ANTHROPIC, impliedProviderMismatch("sk-ant-api03-abc", ProviderType.OPENAI))
        assertEquals(ProviderType.GEMINI, impliedProviderMismatch("AIzaSyABC", ProviderType.OPENAI))
        assertEquals(ProviderType.OPENAI, impliedProviderMismatch("sk-proj-abc", ProviderType.ANTHROPIC))
    }

    @Test
    fun mismatchIsSilentWhenItAgreesOrCannotTell() {
        assertNull(impliedProviderMismatch("sk-ant-api03-abc", ProviderType.ANTHROPIC))
        assertNull(impliedProviderMismatch("AIzaSyABC", ProviderType.GEMINI))
        assertNull(impliedProviderMismatch("sk-proj-abc", ProviderType.OPENAI))
        assertNull(impliedProviderMismatch("some-other-format", ProviderType.OPENAI))
        assertNull(impliedProviderMismatch("", ProviderType.OPENAI))
    }

    @Test
    fun mismatchNeverBlocksCompatibleProfiles() {
        // A compatible endpoint may legitimately accept a key that resembles a first-party format.
        assertNull(impliedProviderMismatch("sk-ant-api03-abc", ProviderType.OPENAI_COMPATIBLE))
        assertNull(impliedProviderMismatch("AIzaSyABC", ProviderType.OPENAI_COMPATIBLE))
    }

    @Test
    fun remoteTypingFollowsEnabledImeList() {
        assertTrue(remoteTypingAvailable(setOf("com.google.android.inputmethod.latin")))
        assertFalse(remoteTypingAvailable(setOf("com.example.otherkeyboard")))
        assertFalse(remoteTypingAvailable(emptySet()))
    }}
