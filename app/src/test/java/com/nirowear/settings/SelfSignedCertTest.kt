package com.nirowear.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The pairing page can only run WebCrypto in a secure context, so the listener serves TLS with a
 * certificate minted on the watch. There is no CA and no dependency: the certificate is written as
 * DER by hand, so it is proven here — parsed back into an X509Certificate, verified against its own
 * public key, and used for a real TLS handshake. A certificate that "looks right" is worth nothing:
 * the failure mode is an SSLException on the user's phone after the watch told them to open the URL.
 */
class SelfSignedCertTest {
    private fun keyPair() =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private val trustAll: SSLContext by lazy {
        val trust =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    }

    @Test
    fun certificateParsesVerifiesAndCarriesTheAddressItIsServedOn() {
        val keys = keyPair()
        val address = InetAddress.getByName("192.168.8.2")
        val cert = SelfSignedCert.mint(keys, "192.168.8.2", address)
        assertTrue("self-signed: the issuer is the subject", cert.subjectX500Principal == cert.issuerX500Principal)
        assertTrue("the subject must name the host", cert.subjectX500Principal.name.contains("192.168.8.2"))
        // Self-signature: proves the DER signature block, the TBS digest input and the key agree.
        cert.verify(keys.public)
        // A watch can boot with no real-time clock. Deriving validity from it produced notBefore in
        // 1969, which UTCTime reads back as 2069 — an INVERTED window and ERR_CERT_DATE_INVALID on
        // the phone. The window is fixed instead, inside UTCTime's 1950..2049 range.
        assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z"), cert.notBefore.toInstant())
        assertEquals(java.time.Instant.parse("2049-12-31T23:59:59Z"), cert.notAfter.toInstant())
        assertTrue("notBefore must precede notAfter", cert.notBefore.before(cert.notAfter))
        // The phone types an IP. Without an iPAddress SAN (type 7) the browser reports a name mismatch
        // on top of the unknown-issuer warning, which reads as "the watch is broken".
        val san = cert.subjectAlternativeNames.orEmpty()
        assertEquals(
            "iPAddress SAN",
            listOf("192.168.8.2"),
            san.filter { (it[0] as Int) == 7 }.map { it[1].toString() },
        )
    }

    @Test
    fun serverContextCompletesATlsHandshake() {
        val keys = keyPair()
        val address = InetAddress.getByName("127.0.0.1")
        val socket =
            SelfSignedCert
                .serverContext(keys, SelfSignedCert.mint(keys, "127.0.0.1", address))
                .serverSocketFactory
                .createServerSocket(0, 1, address)
        socket.use {
            val accepted = AtomicReference<Throwable?>(null)
            val worker =
                Thread {
                    runCatching {
                        socket.accept().use { raw ->
                            val client = raw as javax.net.ssl.SSLSocket
                            client.startHandshake()
                            client.outputStream.apply {
                                write("ok".toByteArray())
                                flush()
                            }
                        }
                    }.onFailure { accepted.set(it) }
                }.apply { isDaemon = true; start() }
            val client =
                trustAll.socketFactory.createSocket("127.0.0.1", socket.localPort) as javax.net.ssl.SSLSocket
            client.use {
                it.startHandshake()
                assertEquals("ok", it.inputStream.readBytes().toString(Charsets.UTF_8))
            }
            worker.join(5000)
            assertTrue("server side handshake failed: ${accepted.get()}", accepted.get() == null)
        }
    }

    @Test
    fun pairingPageIsServedOverTls() {
        val received = AtomicReference<PairingPayload?>(null)
        PairingServer("127.0.0.1", ttlMillis = 120_000) { received.set(it) }.use { pairing ->
            pairing.start()
            assertTrue("the page needs https or WebCrypto is undefined", pairing.url.startsWith("https://"))
            val connection =
                (java.net.URL(pairing.url).openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = trustAll.socketFactory
                    requestMethod = "GET"
                }
            assertEquals(200, connection.responseCode)
            val page = connection.inputStream.bufferedReader().use { it.readText() }
            assertNotNull(page)
            assertTrue("the served page must be the pairing form", page.contains("Send to watch"))
        }
    }
}
