package com.nirowear.chat

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.FailureToken
import com.nirowear.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * A stored failure is a token; this is the step that turns it into words.
 *
 * The JVM suite pins the token round-trip and the resource parity, but only a device can prove the two
 * things this issue was about: that the SAME stored value renders Indonesian for an Indonesian reader,
 * and that a row written before the tokens existed still shows its original sentence instead of
 * blanking out.
 */
@RunWith(AndroidJUnit4::class)
class ErrorTextTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val english get() = context.resources

    private fun localized(tag: String) =
        context
            .createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
            ).resources

    @Test
    fun aTokenRendersTheEnglishString() {
        assertEquals(
            english.getString(R.string.provider_error_network_unavailable),
            errorText(english, "NETWORK_UNAVAILABLE"),
        )
    }

    @Test
    fun theSameTokenRendersIndonesianForAnIndonesianReader() {
        val indonesian = localized("in-ID").getString(R.string.provider_error_network_unavailable)
        // Guard against a silent no-op: if the locale overlay did not apply, this would "pass" while
        // proving nothing.
        assert(indonesian != english.getString(R.string.provider_error_network_unavailable)) {
            "the in-ID resource set did not apply; the assertion below would be meaningless"
        }
        assertEquals(indonesian, errorText(localized("in-ID"), "NETWORK_UNAVAILABLE"))
    }

    @Test
    fun thePartialPromiseIsSuffixedOnlyWhenStored() {
        val cause = english.getString(R.string.provider_error_timeout)
        assertEquals(cause, errorText(english, "TIMEOUT"))
        assertEquals(
            english.getString(R.string.provider_error_partial, cause),
            errorText(english, "TIMEOUT|partial"),
        )
    }

    @Test
    fun aRowWrittenBeforeTheTokensKeepsItsSentence() {
        // Every pre-migration row is prose. Rendering it must be identity, not a blank row and not a
        // guessed cause.
        val legacy = "Cannot reach provider. Check connection."
        assertEquals(legacy, errorText(english, legacy))
        assertEquals(legacy, errorText(localized("in-ID"), legacy))
    }

    @Test
    fun aStoredLocalTokenRendersInTheReadersLanguage() {
        assertEquals(
            english.getString(R.string.failure_truncated),
            errorText(english, FailureToken.TRUNCATED),
        )
        assertEquals(
            english.getString(R.string.failure_history_unreadable),
            errorText(english, FailureToken.HISTORY_UNREADABLE),
        )
        val indonesian = localized("in-ID").getString(R.string.failure_truncated)
        assert(indonesian != english.getString(R.string.failure_truncated)) {
            "the in-ID resource set did not apply; the assertion below would be meaningless"
        }
        assertEquals(indonesian, errorText(localized("in-ID"), FailureToken.TRUNCATED))
    }

    @Test
    fun absentErrorRendersNothing() {
        assertNull(errorText(english, null))
    }
}
