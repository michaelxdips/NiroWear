package com.nirowear.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Partial answer kept." is a claim about the turn, not about the error.
 *
 * Measured before this: a connection test that timed out said "Provider timed out. Partial answer
 * kept." with no answer on screen, and a 200 HTML portal page said "Stream interrupted or
 * unsupported. Partial answer kept." for zero text.
 *
 * Since #37 the claim is carried by a STORED TOKEN (`TIMEOUT|partial`) and the sentence is resolved at
 * display time, so the invariant lives in [ProviderError.code] rather than in prose. The rendering side
 * is covered by ErrorTextTest (instrumentation, it needs Resources).
 */
class ProviderErrorMessageTest {
    @Test
    fun thePartialPromiseNeedsText() {
        assertEquals("TIMEOUT", ProviderError.TIMEOUT.code(hasText = false))
        assertEquals("TIMEOUT|partial", ProviderError.TIMEOUT.code(hasText = true))
        assertEquals("PROTOCOL", ProviderError.PROTOCOL.code(hasText = false))
        assertEquals("PROTOCOL|partial", ProviderError.PROTOCOL.code(hasText = true))
        assertEquals("RESPONSE_TOO_LARGE", ProviderError.RESPONSE_TOO_LARGE.code(hasText = false))
    }

    @Test
    fun theValidatingFormNeverPromisesAPartialAnswer() {
        // Validation paths have no answer at all, so the token must stay bare.
        assertEquals("TIMEOUT", ProviderError.TIMEOUT.code(hasText = false))
        assertEquals("PROTOCOL", ProviderError.PROTOCOL.code(hasText = false))
    }

    @Test
    fun causesThatCannotKeepTextAreUnchanged() {
        assertEquals("NETWORK_UNAVAILABLE", ProviderError.NETWORK_UNAVAILABLE.code(hasText = true))
        assertEquals("INVALID_CREDENTIAL", ProviderError.INVALID_CREDENTIAL.code(hasText = true))
    }

    @Test
    fun everyPartialPromiseNamesItsCause() {
        for (error in ProviderError.entries) {
            val without = error.code(hasText = false)
            val with = error.code(hasText = true)
            assertEquals("$error must store its own name", error.name, without)
            assertFalse("$error promises a partial answer with no text", without.contains("partial"))
            assertTrue(
                "$error must either promise nothing or promise exactly the partial answer",
                with == without || with == "$without|partial",
            )
        }
        // The three causes that CAN leave text must all do it, so the loop above cannot go quiet.
        for (error in listOf(ProviderError.TIMEOUT, ProviderError.RESPONSE_TOO_LARGE, ProviderError.PROTOCOL)) {
            assertEquals("$error must promise the partial answer when text arrived", "${error.name}|partial", error.code(true))
        }
    }

    @Test
    fun aStoredTokenRoundTripsAndLegacyProseStaysUnparsed() {
        assertEquals(ProviderError.TIMEOUT, ProviderError.parse("TIMEOUT|partial"))
        assertTrue(ProviderError.isPartial("TIMEOUT|partial"))
        assertFalse(ProviderError.isPartial("TIMEOUT"))
        // Rows written before the tokens existed hold prose; parse must not claim to understand them.
        assertNull(ProviderError.parse("Provider timed out. Partial answer kept."))
        assertNull(ProviderError.parse(null))
        assertNull(ProviderError.parse(""))
        // A token this build does not know (a newer build's cause) is also not ours to render.
        assertNull(ProviderError.parse("SOMETHING_NEW|partial"))
    }
}
