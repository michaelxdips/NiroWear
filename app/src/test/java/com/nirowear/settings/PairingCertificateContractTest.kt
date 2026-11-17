package com.nirowear.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant

/**
 * The certificate half of #28, which needs no phone.
 *
 * [PairingPageContractTest] guards the page's JavaScript. This one guards the two things the browser
 * decides BEFORE that script runs: whether the name on the certificate matches the name it typed,
 * and whether the validity window is usable at all. Both were shipped broken once — an IP missing
 * from the SAN, and a window derived from a watch clock that reads 1970 on a cold boot.
 */
class PairingCertificateContractTest {
    private fun keyPair() =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    /**
     * The user types the address the watch shows, so the certificate must name BOTH the typed IP
     * (SAN type 7) and the advertised host (SAN type 2). An IP missing from the SAN is a
     * name-mismatch error stacked on top of the unknown-issuer warning, and two warnings is where
     * people stop.
     */
    @Test
    fun sanCarriesTheTypedIpAndTheAdvertisedHost() {
        val host = "nirowear.local"
        val address = InetAddress.getByName("192.168.1.42")
        val cert = SelfSignedCert.mint(keyPair(), host, address)

        assertEquals("CN=$host", cert.subjectX500Principal.name)

        val san = cert.subjectAlternativeNames.map { entry -> (entry[0] as Int) to entry[1].toString() }
        assertTrue("no IP SAN in $san", san.any { it.first == 7 && it.second == address.hostAddress })
        assertTrue("no DNS SAN in $san", san.any { it.first == 2 && it.second == host })
    }

    /**
     * A fixed window on purpose. A watch that boots with no real-time clock reads
     * `System.currentTimeMillis() == 0`; `now - 7 days` then lands in 1969, which UTCTime cannot
     * express, so the two-digit year reads back as 2069 and the phone sees notBefore AFTER notAfter
     * and refuses the page with ERR_CERT_DATE_INVALID. Asserting the exact instants is the only form
     * of this test that fails if anyone reintroduces a clock read.
     */
    @Test
    fun validityIsAFixedWindowThatSurvivesABrokenClock() {
        val cert = SelfSignedCert.mint(keyPair(), "nirowear.local", InetAddress.getByName("10.0.0.7"))

        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), cert.notBefore.toInstant())
        assertEquals(Instant.parse("2049-12-31T23:59:59Z"), cert.notAfter.toInstant())
        assertTrue("notBefore must precede notAfter", cert.notBefore.before(cert.notAfter))
        // Inside UTCTime's two-digit-year range, which is the whole reason for these bounds.
        assertTrue(cert.notBefore.toInstant().atZone(java.time.ZoneOffset.UTC).year in 1950..2049)
        assertTrue(cert.notAfter.toInstant().atZone(java.time.ZoneOffset.UTC).year in 1950..2049)
        assertFalse(
            "the window must not be a function of the wall clock",
            cert.notAfter.toInstant() == Instant.now().plusSeconds(86_400),
        )
    }

    /**
     * The browser that refuses the certificate never runs the page at all, so a guard that fires has
     * to mean the capability is absent — and it must send the key to the watch's own keyboard rather
     * than asking the user to fix a transport that cannot be the cause on an `https` URL.
     */
    @Test
    fun absentWebCryptoFallsBackToTheWatchKeyboard() {
        val page = PAIRING_PAGE
        assertTrue(
            "the page must name the absent capability, not fail generically",
            page.contains("!crypto.subtle"),
        )
        assertTrue(
            "the fallback must point at the watch keyboard",
            page.contains("Enter the key on the watch instead"),
        )
    }
}
