package com.nirowear.settings

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The whole transport on a real device: watch screen starts the listener, a phone-shaped HTTP client
 * encrypts a provider and posts it, and the arriving key must land in the existing save tail with no
 * typing at all. Uses the LAN listener exactly as shipped; nothing is stubbed.
 */
class PairingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NiroApplication
    private var profileId: String? = null

    private fun find(
        value: String,
        node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
    ): AccessibilityNodeInfo? {
        node ?: return null
        node.refresh()
        if (node.text?.toString()?.contains(value) == true || node.contentDescription?.toString() == value) return node
        for (i in 0 until node.childCount) find(value, node.getChild(i))?.let { return it }
        return null
    }

    private fun await(value: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            find(value)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI element missing: $value")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .use { it.readBytes() }
        }
    }

    private fun scrollTo(value: String) {
        repeat(12) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (find(value) != null) return
            shell("input swipe 233 310 233 170 400")
            SystemClock.sleep(400)
        }
        await(value)
    }

    private fun click(value: String) {
        scrollTo(value)
        SystemClock.sleep(400)
        var node: AccessibilityNodeInfo? = find(value)
        while (node != null) {
            if (node.isClickable) {
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot activate $value" }
                SystemClock.sleep(300)
                return
            }
            node = node.parent
        }
        error("Cannot click: $value")
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String) = Base64.getUrlDecoder().decode(text)

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

    /**
     * A phone-shaped HTTPS/1.1 client over a raw TLS socket trusting the watch's ephemeral cert.
     *
     * PairingServer serves TLS over the advertised LAN IP so phone browsers expose WebCrypto
     * (`isSecureContext === true`). Using a raw TLS socket trusting the ephemeral self-signed
     * certificate models the user clicking through the interstitial without mutating the advertised
     * host to loopback.
     */
    private fun http(
        url: String,
        body: String? = null,
    ): Pair<Int, String> {
        val uri = URL(url)
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 443
        val path = uri.file.ifEmpty { "/" }
        val payload = body?.toByteArray()
        val head =
            buildString {
                append(if (payload == null) "GET" else "POST").append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ")
                    .append(host)
                    .append(':')
                    .append(port)
                    .append("\r\n")
                append("Connection: close\r\n")
                if (payload != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(payload.size).append("\r\n")
                }
                append("\r\n")
            }
        (trustAll.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 10_000
            socket.startHandshake()
            socket.getOutputStream().apply {
                write(head.toByteArray())
                if (payload != null) write(payload)
                flush()
            }
            // The server answers with Connection: close, so read-to-EOF is the whole response.
            val text = String(socket.getInputStream().readBytes(), Charsets.UTF_8)
            val code =
                text
                    .substringBefore("\r\n")
                    .split(' ')
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: error("no status line in response: ${text.take(120)}")
            return code to text.substringAfter("\r\n\r\n", "")
        }
    }

    /**
     * What the phone browser does, in Kotlin: ECDH P-256 then AES-GCM with the token as AAD.
     *
     * The token is NOT in the handshake response — the server deliberately never echoes it, because
     * echoing it would hand the session to anyone who could merely connect (PairingServer.kt). The
     * caller already has it: it is the path segment of the URL printed on the watch. So it is passed
     * in, exactly as the browser's own JavaScript reads it from `location.pathname`.
     */
    private fun encryptedPayload(
        handshake: String,
        token: ByteArray,
        json: String,
    ): String {
        val root = Json.parseToJsonElement(handshake).jsonObject
        val raw = unb64(root.getValue("pub").jsonPrimitive.content)
        val params =
            (
                KeyPairGenerator
                    .getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
                    .public as ECPublicKey
            ).params
        val watchKey =
            KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(
                    ECPoint(
                        java.math.BigInteger(1, raw.copyOfRange(1, 33)),
                        java.math.BigInteger(1, raw.copyOfRange(33, 65)),
                    ),
                    params,
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
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(agreement.generateSecret(), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(token)
        val ct = cipher.doFinal(json.toByteArray())

        fun fixed(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                else -> ByteArray(32 - bytes.size) + bytes
            }
        }
        val point = mine.public as ECPublicKey
        val pub = byteArrayOf(4) + fixed(point.w.affineX) + fixed(point.w.affineY)
        return """{"v":1,"t":"${b64(token)}","pub":"${b64(pub)}","iv":"${b64(iv)}","ct":"${b64(ct)}"}"""
    }

    @After
    fun cleanup() {
        val id = profileId ?: return
        runBlocking { app.settings.delete(id) }
    }

    @Test
    fun keyArrivesFromPhoneWithoutTypingOnWatch() =
        runBlocking {
            val activity =
                instrumentation.startActivitySync(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            try {
                click("Menu")
                click("Providers / model")
                click("Add OpenAI")
                scrollTo("Set up from phone")
                click("Set up from phone")
                // Scroll past the QR canvas so the advertised HTTPS capability URL enters viewport.
                scrollTo("https://")
                val end = SystemClock.uptimeMillis() + 10_000
                var url: String? = null
                while (SystemClock.uptimeMillis() < end && url == null) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    url =
                        find("https://")
                            ?.text
                            ?.toString()
                            ?.let { Regex("https://[0-9.]+:[0-9]+/[A-Za-z0-9_-]+/").find(it)?.value }
                    SystemClock.sleep(100)
                }
                assertNotNull("Pairing URL not shown on the watch screen", url)
                // The listener binds the advertised LAN address on purpose (PairingServer.kt), so dial
                // exactly what the watch printed. Rewriting this to 127.0.0.1 makes the test dial an
                // address nothing is listening on, which is what produced the historical ConnectException.
                val advertised = url!!

                // Negative control: the bound-address design means loopback must NOT answer. This keeps
                // the security property under test instead of silently dropping it. If PairingServer ever
                // regresses to a wildcard bind, this assertion is what catches it.
                //
                // The exception type is asserted, not merely "an IOException was thrown". A cleartext
                // policy rejection, a DNS failure or a timeout are all IOExceptions too, and treating any
                // of them as proof of refusal would make this control pass for the wrong reason — the
                // same "test that cannot fail" defect the campaign exists to remove.
                val loopback = advertised.replace(Regex("https://[0-9.]+:"), "https://127.0.0.1:")
                val loopbackRefused =
                    try {
                        http(loopback)
                        false
                    } catch (e: java.net.ConnectException) {
                        true
                    }
                assertTrue("listener must refuse loopback with ConnectException", loopbackRefused)

                val (pageCode, page) = http(advertised)
                assertEquals(200, pageCode)
                assertTrue("Page is the setup form", page.contains("Send to watch"))
                // The token is the URL's path segment; the server never echoes it back.
                val token = unb64(advertised.trimEnd('/').substringAfterLast('/'))
                val (code, body) =
                    http(
                        advertised + "pair",
                        encryptedPayload(
                            http(advertised + "handshake").second,
                            token,
                            """{"v":1,"type":"OPENAI","name":"Paired from phone","base":"https://api.openai.com/v1","model":"gpt-paired","key":"sk-proj-paired-not-real"}""",
                        ),
                    )
                assertEquals("""{"ok":true}""", body)
                assertEquals(200, code)
                val profile =
                    withTimeout(20_000) {
                        app.settings.state
                            .first { it.profiles.any { p -> p.displayName == "Paired from phone" } }
                            .selected!!
                    }
                profileId = profile.id
                assertEquals("gpt-paired", profile.selectedModelId)
                assertEquals("https://api.openai.com/v1", profile.baseUrl)
                // Arrived through the same tail as manual entry: the key is stored encrypted, not echoed.
                val stored = app.secrets.read(profile.credentialRef).forRequest()
                assertEquals("sk-proj-paired-not-real", stored)
                scrollTo("Saved securely.")
                await("Saved securely.")
                // One-shot by construction: the successful arrival closes the socket, so a second attempt
                // cannot even connect. Refused-connection and an explicit 4xx both prove the same thing.
                // Anything else — a 2xx, or a failure that is not a refusal — must fail this assertion,
                // so the check cannot pass by accident.
                val replayOutcome =
                    try {
                        val (replayCode, _) =
                            http(
                                advertised + "pair",
                                encryptedPayload(
                                    http(advertised + "handshake").second,
                                    token,
                                    """{"v":1,"type":"OPENAI","name":"Second","base":"https://api.openai.com/v1","model":"m","key":"sk-proj-second"}""",
                                ),
                            )
                        "status $replayCode"
                    } catch (e: java.net.ConnectException) {
                        "refused"
                    } catch (e: java.io.IOException) {
                        "unexpected ${e.javaClass.simpleName}: ${e.message}"
                    }
                assertTrue(
                    "Pairing session accepted a second key (outcome: $replayOutcome)",
                    replayOutcome == "refused" || Regex("^status 4\\d\\d$").matches(replayOutcome),
                )
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
}
