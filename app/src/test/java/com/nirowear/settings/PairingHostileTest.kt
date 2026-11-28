package com.nirowear.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Hostile shapes from audit #33, driven over a raw socket so the bytes on the wire are the test's own.
 *
 * Every case here was measured against the listener before it was written down; the ones that only
 * report a status (`malformedRequestsAreBounded`) assert the refusal SET, not one code, because a
 * malformed request legitimately ends in 400, 404 or a bare close depending on where it dies — what
 * must never happen is a 200 or a payload.
 */
class PairingHostileTest {
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String) = Base64.getUrlDecoder().decode(text)

    private val trust: SSLSocketFactory by lazy {
        val manager =
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
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }.socketFactory
    }

    /** Raw TLS exchange. Returns the full response text, or null when the peer closed without one. */
    private fun raw(
        port: Int,
        request: ByteArray,
        readMs: Int = 10_000,
    ): String? {
        val socket = trust.createSocket("127.0.0.1", port) as SSLSocket
        socket.soTimeout = readMs
        return try {
            runCatching {
                socket.outputStream.apply {
                    write(request)
                    flush()
                }
            }
            val text = StringBuilder()
            val buffer = ByteArray(8192)
            while (true) {
                val read = socket.inputStream.read(buffer)
                if (read < 0) break
                text.append(String(buffer, 0, read, Charsets.ISO_8859_1))
            }
            text.toString()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun status(response: String?): Int? =
        response?.lineSequence()?.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: List<String> = emptyList(),
        omitLength: Boolean = false,
    ): ByteArray {
        val payload = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        if (body != null && !omitLength) head.append("Content-Length: ${payload.size}\r\n")
        extraHeaders.forEach { head.append(it).append("\r\n") }
        head.append("Connection: close\r\n\r\n")
        return head.toString().toByteArray(Charsets.US_ASCII) + payload
    }

    private fun server(
        counter: AtomicInteger? = null,
        received: AtomicReference<PairingPayload?> = AtomicReference(null),
    ) = PairingServer("127.0.0.1") {
        received.set(it)
        counter?.incrementAndGet()
    }.also { it.start() }

    private fun capability(url: String): ByteArray = unb64(url.substringAfter("://").substringAfter("/").trimEnd('/'))

    private fun refusal(code: Int?): Boolean = code == null || code == 400 || code == 404 || code == 401 || code == 410

    @Test
    fun pathShapesNeverReachThePayloadRoute() {
        val hits = AtomicInteger(0)
        val received = AtomicReference<PairingPayload?>(null)
        server(hits, received).use { pairing ->
            val encoded = b64(capability(pairing.url))
            val shapes =
                listOf(
                    "/../$encoded/pair",
                    "/%2e%2e%2f$encoded/pair",
                    "/%252e%252e%252f$encoded/pair",
                    "//$encoded/pair",
                    "/$encoded%00/pair",
                    "/$encoded/pair/../pair",
                    "/$encoded/pair#frag",
                    "/$encoded/Pair",
                    "/$encoded/pair;x=1",
                    "/$encoded/////",
                    // one character short / one character long
                    "/${encoded.dropLast(1)}/pair",
                    "/${encoded}A/pair",
                    // capability in a later segment only
                    "/x/$encoded/pair",
                    "/",
                    "",
                )
            val results = shapes.map { it to status(raw(pairing.port, request("POST", it, "{}"))) }
            assertNull("no shape may reach a payload", received.get())
            assertEquals("no shape may reach the payload route", 0, hits.get())
            assertTrue("unexpected status set: $results", results.all { refusal(it.second) })
        }
    }

    @Test
    fun queryStringAndTrailingSlashStillServeThePage() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/"))))
            assertEquals("a query string must not unname the page", 200, status(raw(pairing.port, request("GET", "/$cap/?x=1"))))
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake?x=1"))))
        }
    }

    @Test
    fun malformedRequestsAreBoundedAndTheListenerSurvives() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val cases =
                linkedMapOf(
                    "no terminator" to "GET /$cap/ HTTP/1.1\r\nHost: x\r\n".toByteArray(),
                    "request line only" to "GET\r\n\r\n".toByteArray(),
                    "empty line first" to "\r\n\r\n".toByteArray(),
                    "nul in path" to request("GET", "/$cap/\u0000"),
                    "absolute uri" to request("GET", "https://127.0.0.1/$cap/"),
                    "lowercase method" to request("get", "/$cap/"),
                    "head method" to request("HEAD", "/$cap/"),
                    "huge declared length" to
                        ("POST /$cap/pair HTTP/1.1\r\nContent-Length: 2147483647\r\n\r\n").toByteArray(),
                    "negative declared length" to
                        ("POST /$cap/pair HTTP/1.1\r\nContent-Length: -5\r\n\r\n").toByteArray(),
                    "chunked" to
                        ("POST /$cap/pair HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
                            .toByteArray(),
                    "post without length" to request("POST", "/$cap/pair", "{}", omitLength = true),
                    "duplicate length" to
                        ("POST /$cap/pair HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 900\r\n\r\n{}")
                            .toByteArray(),
                )
            val results = cases.map { it.key to status(raw(pairing.port, it.value, readMs = 12_000)) }
            assertNull(received.get())
            assertTrue("unexpected status set: $results", results.all { refusal(it.second) })
            // The listener must still serve after all of that.
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake"))))
        }
    }

    @Test
    fun bodyAtTheLimitAndOneByteOver() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            // Both fail JSON parsing, so both are 400 — but the one at the limit must have been READ
            // (400 from the JSON gate), not refused for size.
            assertEquals(400, status(raw(pairing.port, request("POST", "/$cap/pair", "x".repeat(PairingServer.MAX_BODY)), readMs = 15_000)))
            assertEquals(400, status(raw(pairing.port, request("POST", "/$cap/pair", "x".repeat(PairingServer.MAX_BODY + 1)), readMs = 15_000)))
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake"))))
        }
    }

    @Test
    fun plainHttpOnTheTlsPortIsRefusedAndTheListenerSurvives() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val plain =
                "GET /$cap/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            val answer =
                Socket("127.0.0.1", pairing.port).use { socket ->
                    socket.soTimeout = 5000
                    runCatching { socket.outputStream.apply { write(plain); flush() } }
                    runCatching {
                        val buffer = ByteArray(256)
                        val read = socket.inputStream.read(buffer)
                        if (read <= 0) null else String(buffer, 0, read, Charsets.ISO_8859_1)
                    }.getOrNull()
                }
            // A TLS server must not answer plain HTTP with a status line; it answers with an alert.
            assertTrue("plain HTTP got an HTTP response: $answer", answer == null || !answer.startsWith("HTTP/"))
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake"))))
        }
    }

    @Test
    fun idleConnectionsDoNotDenyTheCapability() {
        // Measured before the concurrent dispatch: 4 idle preconnects delayed the real browser by
        // 8,015 ms, because each one held the single worker for its whole soTimeout.
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val idle = (1..4).map { Socket("127.0.0.1", pairing.port).apply { soTimeout = 1000 } }
            val start = System.currentTimeMillis()
            val code = status(raw(pairing.port, request("GET", "/$cap/handshake"), readMs = 60_000))
            val elapsed = System.currentTimeMillis() - start
            idle.forEach { runCatching { it.close() } }
            assertEquals(200, code)
            assertTrue("idle peers delayed the real client ${elapsed}ms", elapsed < 5_000)
        }
    }

    @Test
    fun preconnectBurstDoesNotDenyTheRealBrowser() {
        // Backlog 8 + a serial accept loop made this fail outright (ConnectException) as well as
        // slowly: 16,018 ms of queueing at 8 sockets, and a reset once the queue itself overflowed.
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val burst = (1..8).map { runCatching { Socket("127.0.0.1", pairing.port) }.getOrNull() }
            assertEquals("the burst itself must connect", 8, burst.count { it != null })
            val start = System.currentTimeMillis()
            val code = status(raw(pairing.port, request("GET", "/$cap/"), readMs = 90_000))
            val elapsed = System.currentTimeMillis() - start
            burst.forEach { runCatching { it?.close() } }
            assertEquals("the real browser must still get the page", 200, code)
            assertTrue("a burst held the session for ${elapsed}ms", elapsed < 10_000)
        }
    }

    @Test
    fun closeIsFastIdempotentAndShutsThePort() {
        val received = AtomicReference<PairingPayload?>(null)
        val pairing = server(received = received)
        val port = pairing.port
        val cap = b64(capability(pairing.url))
        assertEquals(200, status(raw(port, request("GET", "/$cap/handshake"))))
        val idle = Socket("127.0.0.1", port)
        val first = System.currentTimeMillis()
        pairing.close()
        val firstMs = System.currentTimeMillis() - first
        val second = System.currentTimeMillis()
        pairing.close()
        val secondMs = System.currentTimeMillis() - second
        val refused =
            runCatching {
                val response = raw(port, request("GET", "/$cap/handshake"), readMs = 3000)
                response.isNullOrEmpty() || status(response) == null
            }.getOrDefault(true)
        runCatching { idle.close() }
        assertTrue("first close took ${firstMs}ms", firstMs < 1000)
        assertTrue("second close took ${secondMs}ms", secondMs < 1000)
        assertTrue("port still answering after close", refused)
    }

    @Test
    fun offCurveAndMalformedPointsAreRefused() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val shapes =
                mapOf(
                    // (1,1) is not a point on P-256: an implementation that skips the curve check
                    // would happily derive a shared secret from it.
                    "point (1,1)" to (byteArrayOf(4) + ByteArray(32) { 1 } + ByteArray(32) { 1 }),
                    "all zero" to ByteArray(65),
                    "prefix 0x02 (compressed)" to (byteArrayOf(2) + ByteArray(64) { 1 }),
                    "64 bytes (no prefix)" to ByteArray(64) { 1 },
                    "65 zeros with 0x04" to (byteArrayOf(4) + ByteArray(64)),
                    "empty" to ByteArray(0),
                )
            for ((name, point) in shapes) {
                val body =
                    """{"v":1,"t":"$cap","pub":"${b64(point)}","iv":"${b64(ByteArray(12))}","ct":"${b64(ByteArray(32))}"}"""
                assertEquals(name, 400, status(raw(pairing.port, request("POST", "/$cap/pair", body))))
            }
            assertNull("an off-curve point must never produce a payload", received.get())
            // Still usable afterwards: the session must not have been consumed or killed.
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake"))))
        }
    }

    @Test
    fun gcmShapeAndVersionAreCheckedBeforeAnyCrypto() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val point = b64(ByteArray(0))
            val cases =
                mapOf(
                    "iv 8 bytes" to """{"v":1,"t":"$cap","pub":"$point","iv":"${b64(ByteArray(8))}","ct":"${b64(ByteArray(32))}"}""",
                    // The 16-byte tag with no ciphertext cannot be a message.
                    "ct exactly the tag" to """{"v":1,"t":"$cap","pub":"$point","iv":"${b64(ByteArray(12))}","ct":"${b64(ByteArray(16))}"}""",
                    "ct empty" to """{"v":1,"t":"$cap","pub":"$point","iv":"${b64(ByteArray(12))}","ct":""}""",
                    "v=2" to """{"v":2,"t":"$cap","pub":"$point","iv":"${b64(ByteArray(12))}","ct":"${b64(ByteArray(32))}"}""",
                )
            for ((name, body) in cases) {
                assertEquals(name, 400, status(raw(pairing.port, request("POST", "/$cap/pair", body))))
            }
            assertNull(received.get())
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake"))))
        }
    }

    @Test
    fun jsonShapesAreBounded() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val nested = "{\"a\":" + "[".repeat(400) + "]".repeat(400) + "}"
            val cases =
                linkedMapOf(
                    "duplicate keys" to """{"v":1,"v":1,"t":"$cap"}""",
                    "wrong type for t" to """{"v":1,"t":5}""",
                    "extra fields" to """{"v":1,"t":"$cap","x":{"y":[1,2]}}""",
                    "deep nesting" to nested,
                    "not an object" to """[1,2,3]""",
                    "empty body" to "",
                )
            val results = cases.map { it.key to status(raw(pairing.port, request("POST", "/$cap/pair", it.value))) }
            assertNull(received.get())
            assertTrue("unexpected status set: $results", results.all { refusal(it.second) })
            assertEquals(200, status(raw(pairing.port, request("GET", "/$cap/handshake"))))
        }
    }

    @Test
    fun twoSimultaneousValidPayloadsSaveExactlyOnce() {
        // The capability is single use, and requests are served concurrently now: "check consumed,
        // then assign" would let both of these succeed and persist two profiles from one capability.
        val saves = AtomicInteger(0)
        val server = PairingServer("127.0.0.1") { saves.incrementAndGet() }.also { it.start() }
        try {
            val cap = capability(server.url)
            val aes = sharedKey(server)
            val bodies = (1..2).map { aes.seal(cap, payloadFor(it)) }
            val barrier = java.util.concurrent.CountDownLatch(1)
            val results = java.util.Collections.synchronizedList(mutableListOf<Int?>())
            val threads =
                bodies.map { body ->
                    Thread {
                        barrier.await()
                        results += status(raw(server.port, request("POST", "/${b64(cap)}/pair", body), readMs = 15_000))
                    }.apply { isDaemon = true; start() }
                }
            barrier.countDown()
            threads.forEach { it.join(20_000) }
            assertEquals("exactly one save, not ${saves.get()}", 1, saves.get())
            assertEquals("one 200", 1, results.count { it == 200 })
            assertEquals("the loser is refused like a replay", 1, results.count { it == 401 })
        } finally {
            server.close()
        }
    }

    private fun payloadFor(index: Int) =
        """{"v":1,"type":"OPENAI","name":"concurrent $index","base":"https://api.openai.com/v1",""" +
            """"model":"gpt-controlled","key":"sk-proj-controlled-not-real"}"""

    /** Browser stand-in: the ECDH + AES-GCM the page performs with WebCrypto. */
    private inner class BrowserSecret(
        private val aes: javax.crypto.SecretKey,
        private val publicPoint: ByteArray,
    ) {
        fun seal(
            capability: ByteArray,
            payload: String,
        ): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                aes,
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(capability)
            val ct = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            return """{"v":1,"t":"${b64(capability)}","pub":"${b64(publicPoint)}",""" +
                """"iv":"${b64(iv)}","ct":"${b64(ct)}"}"""
        }
    }

    private fun sharedKey(pairing: PairingServer): BrowserSecret {
        val handshake = raw(pairing.port, request("GET", "/${b64(capability(pairing.url))}/handshake"))
        val pub = unb64(
            kotlinx.serialization.json.Json
                .parseToJsonElement(handshake!!.substringAfter("\r\n\r\n"))
                .let { it as kotlinx.serialization.json.JsonObject }
                .getValue("pub")
                .let { it as kotlinx.serialization.json.JsonPrimitive }
                .content,
        )
        val params =
            java.security.KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
                .public as java.security.interfaces.ECPublicKey
        val watchKey =
            java.security.KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.ECPublicKeySpec(
                    java.security.spec.ECPoint(
                        java.math.BigInteger(1, pub.copyOfRange(1, 33)),
                        java.math.BigInteger(1, pub.copyOfRange(33, 65)),
                    ),
                    params.params,
                ),
            )
        val mine =
            java.security.KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(mine.private)
        agreement.doPhase(watchKey, true)
        val key = javax.crypto.spec.SecretKeySpec(agreement.generateSecret(), "AES")
        val point = uncompressed(mine.public as java.security.interfaces.ECPublicKey)
        return BrowserSecret(key, point)
    }

    private fun uncompressed(key: java.security.interfaces.ECPublicKey): ByteArray {
        fun fixed(value: java.math.BigInteger): ByteArray {
            val raw = value.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    @Test
    fun refusalBodiesCarryNoInternals() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received = received).use { pairing ->
            val cap = b64(capability(pairing.url))
            val bodies =
                listOf(
                    raw(pairing.port, request("GET", "/nope/")),
                    raw(pairing.port, request("POST", "/nope/pair", "{}")),
                    raw(pairing.port, request("POST", "/$cap/pair", "{}")),
                ).filterNotNull()
            assertEquals(3, bodies.size)
            val forbidden = listOf("Exception", "at com.", ".kt:", "C:\\", "/data/", "NiroWear 0.", "nginx")
            for (body in bodies) {
                for (needle in forbidden) {
                    assertTrue("refusal leaked '$needle': $body", !body.contains(needle))
                }
            }
        }
    }
}
