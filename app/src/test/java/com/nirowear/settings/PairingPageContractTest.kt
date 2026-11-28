package com.nirowear.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the shipped pairing page.
 *
 * These are string assertions on purpose. The page's JavaScript cannot run in a JVM unit test, and
 * that is exactly how a P0 shipped: every existing pairing test re-implemented ECDH+AES-GCM in
 * Kotlin, so nothing ever exercised the real page. These assertions are the cheapest thing that
 * would have caught the two defects below.
 */
class PairingPageContractTest {
    private val page: String = PAIRING_PAGE

    /**
     * `crypto.subtle` only exists in a secure context. The page used to be served over plain HTTP from
     * a LAN address, where `window.isSecureContext` was measured false and `crypto.subtle` undefined,
     * so the handshake could never run. It is served over TLS now (self-signed, minted per session),
     * and the page must (a) still detect a missing secure context instead of failing generically and
     * (b) tell the user that the browser's certificate warning is expected.
     */
    @Test
    fun pageDetectsAMissingSecureContextAndWarnsAboutTheCertificate() {
        assertTrue(
            "the page must detect a missing secure context instead of failing generically",
            page.contains("isSecureContext"),
        )
        assertTrue(
            "the certificate warning is expected; the page must say so before the user panics",
            page.contains("certificate") && page.contains("not trusted"),
        )
    }

    /**
     * The IV must be generated per send. A single IV created at page load means the second Send —
     * the normal recovery path after a rejected key — reuses key+nonce, which leaks the XOR of the
     * two plaintexts and enables forgery.
     */
    @Test
    fun nonceIsGeneratedPerSendNotPerPageLoad() {
        assertTrue(
            "a fresh IV must be created inside the send path",
            page.contains("var iv=crypto.getRandomValues"),
        )
        assertFalse(
            "the pre-generated ready.iv must not be used for encryption",
            page.contains("iv:ready.iv"),
        )
    }

    @Test
    fun capabilityTokenIsNeverEchoedInTheHandshake() {
        // The token is the capability; returning it would hand the session to anyone who connects.
        val handshake = page.substringAfter("handshake").take(400)
        assertFalse("handshake response must not carry the token", handshake.contains("\"token\""))
    }

    @Test
    fun pageStillHasTheManualFallbackCopy() {
        assertTrue("Send to watch", page.contains("Send to watch"))
    }

    @Test
    fun pageStatesThePairingThreatModel() {
        // Same copy the watch screen shows: where the user decides, not just the READMEs.
        assertTrue(page.contains("Same Wi-Fi only"))
        assertTrue(page.contains("unauthenticated"))
        assertTrue(page.contains("manual entry on untrusted networks"))
    }

    @Test
    fun pageIncludesEncryptedModelFetcherAndPresetSwitcher() {
        assertTrue("Fetch models button must be present", page.contains("id=\"fetchModels\"") && page.contains("Fetch models"))
        assertTrue("Model dropdown must be present", page.contains("id=\"models\""))
        assertTrue("Type switcher must update presets", page.contains("el('type').onchange"))
        assertTrue("Encrypted /models route must be wired", page.contains("/models"))
    }

    @Test
    fun pageIgnoresLateModelFetchCompletionAfterPairSucceeds() {
        assertTrue("sent flag must be set on successful pair", page.contains("sent=true"))
        assertTrue("fetchModels must guard completion and re-enable against sent", page.contains("if(!sent)btn.disabled=false"))
    }
}
