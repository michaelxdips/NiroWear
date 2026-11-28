package com.nirowear.settings

import com.nirowear.provider.ModelDiscoveryResult
import com.nirowear.provider.ProviderError
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SafeProviderException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The pairing listener is the only inbound socket in the app, so its contract is pinned here rather
 * than described in a doc: single use, constant-time token check, no plaintext fallback, and one
 * converged save path. Runs on the JVM because every invariant is pure socket + JCE work.
 */
class PairingServerTest {
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String) = Base64.getUrlDecoder().decode(text)

    private fun get(url: String): Pair<Int, String> {
        val connection = connection(url)
        connection.requestMethod = "GET"
        return connection.responseCode to
            (if (connection.responseCode < 400) connection.inputStream else connection.errorStream)
                .bufferedReader()
                .use { it.readText() }
    }

    private fun post(
        url: String,
        body: String,
    ): Pair<Int, String> {
        val connection = connection(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to stream.bufferedReader().use { it.readText() }
    }

    /**
     * The listener serves TLS with a certificate minted per session, so a test client has to accept
     * that certificate. Trusting everything here is the point: the phone does the same thing by
     * clicking through the browser warning, and no test may depend on a CA the watch cannot obtain.
     */
    private fun connection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = trustAll.socketFactory
            hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            // Non-infinite, so a server that never answers fails the test instead of hanging it.
            connectTimeout = 15_000
            readTimeout = 15_000
        }

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

    /** Minimal browser stand-in: the same ECDH + AES-GCM the page performs with WebCrypto. */
    private inner class Browser(
        handshake: String,
        capability: ByteArray,
    ) {
        val watchKey: ECPublicKey
        val token: ByteArray = capability
        private val aes: SecretKeySpec
        val publicPoint: ByteArray

        init {
            val root = Json.parseToJsonElement(handshake).jsonObject
            val raw = unb64(root.getValue("pub").jsonPrimitive.content)
            val params =
                KeyPairGenerator
                    .getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
                    .public as ECPublicKey
            watchKey =
                KeyFactory.getInstance("EC").generatePublic(
                    ECPublicKeySpec(
                        ECPoint(
                            java.math.BigInteger(1, raw.copyOfRange(1, 33)),
                            java.math.BigInteger(1, raw.copyOfRange(33, 65)),
                        ),
                        params.params,
                    ),
                ) as ECPublicKey
            val mine =
                KeyPairGenerator
                    .getInstance(
                        "EC",
                    ).apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(mine.private)
            agreement.doPhase(watchKey, true)
            aes = SecretKeySpec(agreement.generateSecret(), "AES")
            publicPoint = uncompressed(mine.public as ECPublicKey)
        }

        fun encrypt(
            payload: String,
            iv: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) },
        ): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
            cipher.updateAAD(token)
            val ct = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            return """{"v":1,"t":"${b64(token)}","pub":"${b64(publicPoint)}","iv":"${b64(iv)}","ct":"${b64(ct)}"}"""
        }

        private fun uncompressed(key: ECPublicKey): ByteArray {
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
    }

    private fun payload(key: String = "sk-proj-controlled-not-real") =
        """
        {"v":1,"type":"OPENAI","name":"Phone sent","base":"https://api.openai.com/v1","model":"gpt-controlled","key":"$key"}
        """.trimIndent()

    private fun server(
        received: AtomicReference<PairingPayload?>,
        ttl: Long = 120_000,
        clock: () -> Long = System::currentTimeMillis,
    ) = PairingServer("127.0.0.1", clock = clock, ttlMillis = ttl) { received.set(it) }.also { it.start() }

    /** The capability is the first path segment of the URL the watch displays. */
    private fun capability(url: String): ByteArray = unb64(url.substringAfter("://").substringAfter("/").trimEnd('/'))

    @Test
    fun pageAndHandshakeAreServedUnderCapability() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val (pageCode, page) = get(pairing.url)
            assertEquals(200, pageCode)
            assertTrue(page.contains("Send to watch"))
            val (code, handshake) = get(pairing.url + "handshake")
            assertEquals(200, code)
            val root = Json.parseToJsonElement(handshake).jsonObject
            assertTrue(root.containsKey("pub"))
            // The token is the capability the caller already proved it had. Echoing it back would
            // hand the session to anyone who could merely reach the port.
            assertTrue("handshake must not disclose the token", !root.containsKey("token"))
        }
    }

    @Test
    fun callerWithoutCapabilityGets404OnEveryRoute() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val root = "https://127.0.0.1:" + pairing.port + "/"
            assertEquals(404, get(root).first)
            assertEquals(404, get(root + "handshake").first)
            assertEquals(404, post(root + "pair", "{}").first)
            assertNull(received.get())
        }
    }

    @Test
    fun nearMissCapabilitiesAreRejectedExactlyLikeAbsentOnes() {
        // The router used to compare the path with String.startsWith, which returns at the first
        // mismatching byte; a LAN peer could therefore recover the 128-bit token from 404 timings a
        // byte at a time. Outcomes must be identical for a correct-length wrong token and for a
        // genuine prefix of the real one.
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val token = capability(pairing.url)
            val wrong = token.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
            val prefix = token.copyOf(token.size - 1)
            for (candidate in listOf(token.copyOf(0), prefix, wrong)) {
                val path = "https://127.0.0.1:${pairing.port}/${b64(candidate)}/"
                assertEquals("candidate ${candidate.size} bytes must be refused", 404, get(path).first)
                assertEquals(404, get(path + "handshake").first)
            }
            assertNull(received.get())
            // The real capability still works, so the check is not simply refusing everything.
            assertEquals(200, get(pairing.url + "handshake").first)
        }
    }

    @Test
    fun validEncryptedPayloadArrivesExactlyOnce() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val browser = Browser(get(pairing.url + "handshake").second, capability(pairing.url))
            val (code, body) = post(pairing.url + "pair", browser.encrypt(payload()))
            assertEquals(200, code)
            assertEquals("""{"ok":true}""", body)
            assertEquals(ProviderType.OPENAI, received.get()!!.type)
            assertEquals("Phone sent", received.get()!!.name)
            assertEquals("gpt-controlled", received.get()!!.modelId)
            assertEquals("sk-proj-controlled-not-real", received.get()!!.apiKey)
            // Replay: the token was consumed, so the same bytes are refused without touching crypto.
            val (replayCode, _) = post(pairing.url + "pair", browser.encrypt(payload()))
            assertEquals(401, replayCode)
        }
    }

    @Test
    fun wrongTokenIsRejectedWithoutDecrypting() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val browser = Browser(get(pairing.url + "handshake").second, capability(pairing.url))
            val forged = browser.encrypt(payload()).replace(b64(browser.token), b64(ByteArray(16) { 7 }))
            val (code, body) = post(pairing.url + "pair", forged)
            assertEquals(401, code)
            assertEquals("""{"ok":false}""", body)
            assertNull(received.get())
        }
    }

    @Test
    fun aDribblingClientCannotHoldTheSession() {
        // Over TLS the first read() is where JSSE runs the whole handshake, so the request wall clock
        // inside readHead cannot run until it returns, and soTimeout bounds one read rather than a
        // handshake. Measured failure: a peer dribbling a partial ClientHello with gaps shorter than
        // soTimeout held the single worker, so the phone's real request was never accepted — the page
        // never loaded and the watch said nothing. serve() now joins each request with a hard bound.
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val dribbler = java.net.Socket("127.0.0.1", pairing.port).apply { soTimeout = 5000 }
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val writer =
                Thread {
                    val out = dribbler.getOutputStream()
                    // A TLS record header promising 512 bytes, then a byte at a time.
                    runCatching {
                        out.write(byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00))
                        out.flush()
                    }
                    while (!stop.get()) {
                        runCatching {
                            out.write(0x41)
                            out.flush()
                        }
                        Thread.sleep(900)
                    }
                }.apply { isDaemon = true; start() }
            val start = System.currentTimeMillis()
            val (code, _) = get(pairing.url + "handshake")
            val elapsed = System.currentTimeMillis() - start
            stop.set(true)
            dribbler.runCatching { close() }
            writer.join(3_000)
            assertEquals("the real client must still be served", 200, code)
            assertTrue("a dribbler held the session for ${elapsed}ms", elapsed < 20_000)
        }
    }

    @Test
    fun corruptCiphertextGetsGeneric400AndWritesNothing() {        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val browser = Browser(get(pairing.url + "handshake").second, capability(pairing.url))
            val root = Json.parseToJsonElement(browser.encrypt(payload())).jsonObject
            val broken =
                """
                {"v":1,"t":"${root.getValue("t").jsonPrimitive.content}",
                "pub":"${root.getValue("pub").jsonPrimitive.content}",
                "iv":"${root.getValue("iv").jsonPrimitive.content}",
                "ct":"${b64(ByteArray(20) { 3 })}"}
                """.trimIndent()
            val (code, body) = post(pairing.url + "pair", broken)
            assertEquals(400, code)
            assertEquals("""{"ok":false}""", body)
            assertNull(received.get())
            // A rejected body must not consume the session: the real phone can still pair after a
            // hostile or merely broken attempt.
            val (retryCode, _) = post(pairing.url + "pair", browser.encrypt(payload()))
            assertEquals(200, retryCode)
            assertEquals("Phone sent", received.get()!!.name)
        }
    }

    @Test
    fun keyWithNonPrintableCharacterIsRefusedBeforeSave() {
        // SecretValue is the storage gate; an arriving key must not bypass it just because it came
        // over the LAN instead of the keyboard.
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val browser = Browser(get(pairing.url + "handshake").second, capability(pairing.url))
            val (code, _) = post(pairing.url + "pair", browser.encrypt(payload("sk-proj-with space")))
            assertEquals(400, code)
            assertNull(received.get())
        }
    }

    @Test
    fun ttlFollowsInjectedClockNotWallClock() {
        val received = AtomicReference<PairingPayload?>(null)
        var now = 1_000_000L
        server(received, ttl = 120_000, clock = { now }).use { pairing ->
            // 119 s in: session alive.
            now += 119_000
            assertEquals(200, get(pairing.url + "handshake").first)
            // 121 s in: past ttl, expired.
            now += 2_000
            val (code, _) = post(pairing.url + "pair", payload())
            assertEquals(410, code)
            assertNull(received.get())
        }
    }

    @Test
    fun expiredSessionAnswers410() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received, ttl = 0).use { pairing ->
            val browser = Browser(get(pairing.url + "handshake").second, capability(pairing.url))
            val (code, _) = post(pairing.url + "pair", browser.encrypt(payload()))
            assertEquals(410, code)
            assertNull(received.get())
        }
    }

    @Test
    fun oversizedBodyIsRejected() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            // Derived from the real cap, not a hardcoded 9000: MAX_BODY was raised from 4096 to
            // 16 KiB so pairing can carry an 8192-character key, and a literal here would have
            // silently stopped testing the bound.
            // The server rejects on the declared Content-Length before reading the body, so it may
            // close the socket while this client is still uploading — HttpsURLConnection then reports
            // "connection aborted" instead of the 400. Both outcomes mean the same thing (refused,
            // nothing consumed), and WHICH one happens is a race, so the assertion is on the outcome
            // that matters: no payload arrived.
            val outcome =
                runCatching {
                    post(
                        pairing.url + "pair",
                        "{\"v\":1,\"ct\":\"" + "A".repeat(PairingServer.MAX_BODY + 1000) + "\"}",
                    ).first
                }
            assertEquals(
                "either an explicit 400 or an aborted upload, never a 200",
                true,
                outcome.getOrNull() == 400 || outcome.exceptionOrNull() is IOException,
            )
            assertNull(received.get())
        }
    }

    @Test
    fun closedListenerRefusesFurtherConnections() {
        val received = AtomicReference<PairingPayload?>(null)
        val pairing = server(received)
        val url = pairing.url
        get(url + "handshake")
        pairing.close()
        var refused = false
        try {
            get(url + "handshake")
        } catch (_: IOException) {
            refused = true
        }
        assertTrue("Listener still accepting after close()", refused)
    }

    @Test
    fun lanAddressIsNullOrPrivateSpaceOnly() {
        // Enumeration must never hand back a loopback or link-local address: a phone cannot reach them.
        lanAddress()?.let { host ->
            assertNotEquals("127.0.0.1", host)
            assertTrue("Unexpected address shape: $host", host.matches(Regex("[0-9.]+")))
        }
    }

    @Test
    fun privateAddressDetectionCoversRfc1918() {
        assertTrue(isPrivateAddress("192.168.1.7"))
        assertTrue(isPrivateAddress("10.0.2.16"))
        assertTrue(isPrivateAddress("172.16.0.1"))
        assertTrue(isPrivateAddress("172.31.255.254"))
        assertTrue(!isPrivateAddress("172.32.0.1"))
        assertTrue(!isPrivateAddress("8.8.8.8"))
    }

    @Test
    fun providerErrorsSurfaceAsTheirOwnCause() {
        // Phase 3: a 401 must not read like a bad model, and a 404 must not read like a bad key.
        // Since #37 the classifier returns the CAUSE and the sentence is resolved at display time, so
        // this asserts on the cause — comparing prose here would only prove the copy matched.
        assertEquals(
            ProviderError.INVALID_CREDENTIAL,
            validationCause(SafeProviderException(ProviderError.INVALID_CREDENTIAL)),
        )
        assertEquals(
            ProviderError.MODEL_UNAVAILABLE,
            validationCause(SafeProviderException(ProviderError.MODEL_UNAVAILABLE)),
        )
        assertEquals(
            ProviderError.NETWORK_UNAVAILABLE,
            validationCause(SafeProviderException(ProviderError.NETWORK_UNAVAILABLE)),
        )
        assertNotEquals(
            ProviderError.INVALID_CREDENTIAL,
            validationCause(SafeProviderException(ProviderError.MODEL_UNAVAILABLE)),
        )
        // Anything unclassified reports NO cause, so the caller shows the honest three-field answer
        // instead of inventing one.
        assertNull(validationCause(IllegalStateException("boom")))
    }

    @Test
    fun httpErrorMappingKeepsCredentialAndModelDistinct() {
        assertEquals(ProviderError.INVALID_CREDENTIAL, com.nirowear.provider.httpError(401))
        assertEquals(ProviderError.MODEL_UNAVAILABLE, com.nirowear.provider.httpError(404))
        assertEquals(ProviderError.RATE_LIMITED, com.nirowear.provider.httpError(429))
    }

    @Test
    fun handshakeCarriesPresetAndEncryptedModelsFetchDoesNotConsumeSession() {
        val received = AtomicReference<PairingPayload?>(null)
        val preset = PairingPreset(ProviderType.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4")
        PairingServer(
            host = "127.0.0.1",
            initialPreset = preset,
            onFetchModels = { req ->
                assertEquals(ProviderType.ANTHROPIC, req.type)
                assertEquals("https://api.anthropic.com/v1", req.baseUrl)
                ModelDiscoveryResult.Models(listOf("claude-sonnet-4", "claude-haiku-3-5"))
            },
        ) { received.set(it) }.apply { start() }.use { pairing ->
            val hs = get(pairing.url + "handshake").second
            val hsObj = Json.parseToJsonElement(hs).jsonObject
            assertEquals("ANTHROPIC", hsObj.getValue("preset").jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue("handshake must never echo token", !hsObj.containsKey("token"))

            val browser = Browser(hs, capability(pairing.url))
            val modelReq = """{"type":"ANTHROPIC","base":"https://api.anthropic.com/v1","key":"sk-ant-test"}"""
            val (mCode, mBody) = post(pairing.url + "models", browser.encrypt(modelReq))
            assertEquals(200, mCode)
            assertTrue(mBody.contains("claude-sonnet-4"))
            assertNull("fetching models must not consume pairing", received.get())

            // Invalid payload must get 400 and still keep session alive for a subsequent valid /pair.
            val invalidModelPayload =
                """{"v":1,"type":"OPENAI","name":"Test","base":"https://api.openai.com/v1","model":"","key":"sk-valid"}"""
            assertEquals(400, post(pairing.url + "pair", browser.encrypt(invalidModelPayload)).first)
            assertEquals(200, post(pairing.url + "pair", browser.encrypt(payload())).first)
            assertEquals("Phone sent", received.get()?.name)
        }
    }

    @Test
    fun malformedOrNegativeContentLengthIsRejectedWith400() {
        val received = AtomicReference<PairingPayload?>(null)
        server(received).use { pairing ->
            val path = "/" + pairing.url.substringAfter("://").substringAfter("/") + "pair"
            for (badLength in listOf("-5", "not-a-number", "99999999999999999999999")) {
                val socket = trustAll.socketFactory.createSocket("127.0.0.1", pairing.port)
                socket.soTimeout = 5000
                socket.use { s ->
                    val req = "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: $badLength\r\n\r\n"
                    s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                    val statusLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                    assertTrue("Expected 400 for Content-Length: $badLength, got: $statusLine", statusLine.contains(" 400 "))
                }
            }
            assertNull(received.get())
        }
    }

    @Test
    fun nonStringJsonFieldsAreRejectedWith400AndCloseCancelsSlowModelFetch() {
        val received = AtomicReference<PairingPayload?>(null)
        val fetchStarted = java.util.concurrent.CountDownLatch(1)
        val fetchCancelled = java.util.concurrent.CountDownLatch(1)
        val fetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val pairing =
            PairingServer(
                host = "127.0.0.1",
                allowLocalHttp = true,
                onFetchModels = {
                    kotlinx.coroutines.runBlocking {
                        fetchScope
                            .async {
                                fetchStarted.countDown()
                                try {
                                    kotlinx.coroutines.delay(30_000)
                                    ModelDiscoveryResult.Models(listOf("never"))
                                } catch (c: kotlinx.coroutines.CancellationException) {
                                    fetchCancelled.countDown()
                                    throw c
                                }
                            }.await()
                    }
                },
            ) { received.set(it) }.apply { start() }
        try {
            val hs = get(pairing.url + "handshake").second
            val browser = Browser(hs, capability(pairing.url))
            val nullNamePayload =
                """{"type":"OPENAI","name":null,"base":"https://api.openai.com/v1","model":"gpt-4o","key":"sk-valid"}"""
            assertEquals(400, post(pairing.url + "pair", browser.encrypt(nullNamePayload)).first)
            val numBasePayload =
                """{"type":"OPENAI","name":"Test","base":123,"model":"gpt-4o","key":"sk-valid"}"""
            assertEquals(400, post(pairing.url + "pair", browser.encrypt(numBasePayload)).first)
            assertNull("non-string JSON fields must not be accepted", received.get())

            // Start a slow /models request, then cancel fetchScope + close server cleanly.
            val reqThread =
                Thread {
                    runCatching {
                        val modelReq = """{"type":"OPENAI","base":"https://api.openai.com/v1","key":"sk-valid"}"""
                        post(pairing.url + "models", browser.encrypt(modelReq))
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue("fetch callback must start", fetchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            fetchScope.cancel()
            pairing.close()
            assertTrue("cancelling fetchScope must abort slow discovery", fetchCancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            reqThread.join(5000)
            assertTrue("request thread must exit promptly on close", !reqThread.isAlive)
        } finally {
            fetchScope.cancel()
            pairing.close()
        }
    }
}
