package com.nirowear.settings

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A self-signed TLS certificate minted in-process, so the pairing page can be served over https.
 *
 * The page needs a SECURE CONTEXT: browsers expose WebCrypto only on https, file, localhost or
 * 127.0.0.1, and this page is served from a LAN IP — measured in a real browser at the plain-HTTP
 * URL: `isSecureContext === false`, `typeof crypto.subtle === "undefined"`, so the ECDH handshake
 * never ran. TLS removes that wall.
 *
 * Android's public API mints no certificates: `KeyGenParameterSpec` has no subject field (the
 * `KeyPairGeneratorSpec` that did was removed in API 23), and BouncyCastle — the usual answer — is a
 * new dependency. The smallest correct alternative is the DER writer below, verified by parsing the
 * result back with `CertificateFactory` and by a real TLS handshake in a JVM test.
 *
 * This is not trust: no client knows the certificate, so the phone still shows "not private" and the
 * user clicks through. That warning is expected and the page copy says so. Authorisation is the
 * unguessable capability token in the URL, which no certificate would improve.
 */
internal object SelfSignedCert {
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_CN = "2.5.4.3"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"

    /** A week either way: a phone with a stale clock is not worth debugging. */
    private val VALIDITY: Duration = Duration.ofDays(7)

    private val TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    fun mint(
        keyPair: KeyPair,
        host: String,
        address: InetAddress,
        now: Instant = Instant.now(),
    ): X509Certificate = parse(encode(keyPair, host, address, now))

    /** The DER itself. Separate from [parse] so a test can name the failing bytes, not just the throw. */
    internal fun encode(
        keyPair: KeyPair,
        host: String,
        address: InetAddress,
        now: Instant = Instant.now(),
    ): ByteArray {
        val algorithm = der(0x30, oid(OID_ECDSA_SHA256))
        val name = der(0x30, der(0x31, der(0x30, oid(OID_CN) + der(0x0C, host.toByteArray(Charsets.UTF_8)))))
        val validity = der(0x30, utcTime(now.minus(VALIDITY)) + utcTime(now.plus(VALIDITY)))
        val extensions =
            der(
                0xA3,
                der(
                    0x30,
                    extension(OID_BASIC_CONSTRAINTS, critical = true, value = der(0x30, ByteArray(0))) +
                        // keyUsage: digitalSignature | keyEncipherment, five unused bits.
                        extension(OID_KEY_USAGE, critical = true, value = der(0x03, byteArrayOf(5, 0xA0.toByte()))) +
                        extension(
                            OID_SUBJECT_ALT_NAME,
                            critical = false,
                            // The phone types an IP, so the IP must be in the SAN or the browser reports
                            // a name mismatch on top of the unknown-issuer warning.
                            value =
                                der(
                                    0x30,
                                    der(0x87, address.address) + der(0x82, host.toByteArray(Charsets.US_ASCII)),
                                ),
                        ),
                ),
            )
        val tbs =
            der(
                0x30,
                der(0xA0, integer(BigInteger.valueOf(2))) + // v3
                    integer(BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it) })) +
                    algorithm +
                    name +
                    validity +
                    name +
                    keyPair.public.encoded +
                    extensions,
            )
        val signature =
            Signature
                .getInstance("SHA256withECDSA")
                .run {
                    initSign(keyPair.private)
                    update(tbs)
                    sign()
                }
        val encoded = der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signature))
        return encoded
    }

    /** Parsing it back is the cheapest proof the DER is well formed. */
    internal fun parse(encoded: ByteArray): X509Certificate =
        CertificateFactory
            .getInstance("X.509")
            .generateCertificate(encoded.inputStream()) as X509Certificate

    /**
     * TLS server context for [certificate]. PKCS12 rather than the platform default: the default
     * keystore type has changed across Android versions, and only PKCS12 reliably accepts an
     * in-memory key entry.
     */
    fun serverContext(
        keyPair: KeyPair,
        certificate: X509Certificate,
    ): SSLContext {
        val password = CharArray(24) { "0123456789abcdef"[SecureRandom().nextInt(16)] }
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("pairing", keyPair.private, password, arrayOf(certificate))
        val managers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, password)
            }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }

    private fun extension(
        dotted: String,
        critical: Boolean,
        value: ByteArray,
    ): ByteArray {
        val criticalPart = if (critical) der(0x01, byteArrayOf(0xFF.toByte())) else ByteArray(0)
        return der(0x30, oid(dotted) + criticalPart + der(0x04, value))
    }

    private fun utcTime(instant: Instant): ByteArray = der(0x17, TIME.format(instant).toByteArray(Charsets.US_ASCII))

    private fun integer(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2) { "an OID has at least two arcs" }
        val body = ArrayList<Byte>(8)

        fun emit(value: Long) {
            if (value < 0x80) {
                body.add(value.toByte())
            } else {
                val stack = ArrayList<Byte>(8)
                var rest = value
                while (rest > 0) {
                    stack.add((rest and 0x7F).toByte())
                    rest = rest shr 7
                }
                for (index in stack.indices.reversed()) {
                    val byte = stack[index].toInt()
                    body.add((if (index == 0) byte else byte or 0x80).toByte())
                }
            }
        }
        emit(arcs[0] * 40 + arcs[1])
        for (index in 2 until arcs.size) emit(arcs[index])
        return der(0x06, body.toByteArray())
    }

    private fun der(
        tag: Int,
        content: ByteArray,
    ): ByteArray {
        val length = content.size
        val header =
            when {
                length < 0x80 -> {
                    byteArrayOf(tag.toByte(), length.toByte())
                }

                length < 0x100 -> {
                    byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
                }

                length < 0x10000 -> {
                    byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), length.toByte())
                }

                else -> {
                    byteArrayOf(
                        tag.toByte(),
                        0x83.toByte(),
                        (length shr 16).toByte(),
                        (length shr 8).toByte(),
                        length.toByte(),
                    )
                }
            }
        return header + content
    }
}
