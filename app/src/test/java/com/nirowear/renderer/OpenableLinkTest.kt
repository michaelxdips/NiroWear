package com.nirowear.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link is painted as tappable only when it can actually be opened.
 *
 * The renderer used to colour and underline every link while only http(s) got a click annotation, so
 * a relative path, a bare domain or a `javascript:` target looked tappable and did nothing.
 */
class OpenableLinkTest {
    @Test
    fun httpAndHttpsAreOpenable() {
        assertTrue(isOpenableLink("https://example.com/a?b=c"))
        assertTrue(isOpenableLink("http://example.com"))
    }

    @Test
    fun nonWebSchemesAreNotPaintedAsLinks() {
        assertFalse(isOpenableLink("javascript:alert(1)"))
        assertFalse(isOpenableLink("mailto:a@b.c"))
        assertFalse(isOpenableLink("file:///sdcard/x"))
    }

    @Test
    fun relativeAndHostlessTargetsAreNotOpenable() {
        assertFalse(isOpenableLink("docs/setup.md"))
        assertFalse(isOpenableLink("example.com"))
        assertFalse(isOpenableLink("https:///nohost"))
    }

    @Test
    fun embeddedCredentialsAreRejected() {
        assertFalse(isOpenableLink("https://user:pass@example.com"))
    }
}