package com.nirowear.settings

import com.nirowear.provider.Endpoints
import com.nirowear.provider.ModelDiscoveryResult
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SecretValue
import com.nirowear.provider.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Credential arrival over the LAN, phone browser -> watch. No companion app, no backend, no cloud.
 *
 * The watch serves one small page over TLS; the browser encrypts the form with an ephemeral ECDH
 * P-256 secret and AES-GCM. The watch never sends a key out, only accepts one in, and the whole
 * listener lives only while the setup screen is resumed.
 *
 * TLS is not belt-and-braces here, it is load-bearing: browsers expose WebCrypto only in a secure
 * context, and plain HTTP from a LAN IP is not one (measured: `isSecureContext === false`,
 * `crypto.subtle === undefined`), so the handshake below could never run. The certificate is
 * self-signed and minted per session — see [SelfSignedCert]; the phone shows the usual warning, and
 * the capability token in the URL, not the certificate, is what authorises a payload.
 *
 * Deliberately no dependency: `ServerSocket` + a ~40 line request reader. No NanoHTTPD, no Ktor, and
 * no BouncyCastle for the certificate.
 */
internal data class PairingPayload(
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String,
)

internal data class PairingPreset(
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val modelId: String,
)

internal data class PairingModelRequest(
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
)

internal class PairingServer(
    private val host: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 120_000,
    private val initialPreset: PairingPreset? = null,
    private val allowLocalHttp: Boolean = false,
    private val onFetchModels: ((PairingModelRequest) -> ModelDiscoveryResult)? = null,
    private val onPayload: (PairingPayload) -> Unit,
) : Closeable {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val tokenB64 = b64(token)
    private val pair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    private val deadline = clock() + ttlMillis

    // Bound to the address we advertise, not the wildcard: the listener must not be reachable on a
    // tethered or secondary interface the user never saw.
    private val hostAddress: InetAddress = InetAddress.getByName(host)

    // A key pair of its own for TLS. Reusing the ECDH session key would mix a signature key with a
    // key-agreement key, and this one never touches the payload path.
    private val tlsKey =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private val socket =
        SelfSignedCert
            .serverContext(tlsKey, SelfSignedCert.mint(tlsKey, host, hostAddress))
            .serverSocketFactory
            // Backlog 1 refused the real browser whenever a hostile peer had already filled the
            // single queue slot while serve() was joining it: measured as ConnectException in
            // PairingServerTest.aDribblingClientCannotHoldTheSession.
            //
            // 8 was still too tight once requests were dispatched concurrently: measured with the
            // audit probe, an 8-socket preconnect burst plus the real browser made the browser's
            // connect fail outright — Windows answers a full accept queue with RST, so the user got
            // ERR_CONNECTION_RESET and nothing on the watch explained it. The queue is not what
            // bounds the session (MAX_IN_FLIGHT is), so it can be generous.
            .createServerSocket(0, ACCEPT_BACKLOG, hostAddress)

    private val consumed = AtomicBoolean(false)

    @Volatile private var closed = false

    /** Requests currently owned by a handler thread, capped by [MAX_IN_FLIGHT]. */
    private val inFlight = AtomicInteger(0)

    /** Every accepted client, so close() can unblock all of them, not just the newest. */
    private val liveClients: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    private var worker: Thread? = null

    val port: Int get() = socket.localPort

    fun remainingSeconds(): Int = (((deadline - clock()).coerceAtLeast(0L) + 999L) / 1000L).toInt()

    fun isExpired(): Boolean = clock() >= deadline

    /**
     * Capability URL. The token is a path segment, not something the server hands out: whoever can
     * read the watch screen learns it, and nobody else does. It doubles as the AES-GCM associated
     * data, so a payload encrypted for one session cannot be replayed into another.
     *
     * https, not http: WebCrypto does not exist on a plain-HTTP LAN origin, so the page that encrypts
     * the key could never run there.
     */
    val url: String get() = "https://$host:$port/$tokenB64/"

    fun start() {
        if (worker != null) return
        socket.soTimeout = 500
        worker =
            Thread({
                try {
                    // Accepts for a short grace past the deadline so an expired attempt still gets the
                    // explicit 410 instead of a bare connection reset, then self-closes.
                    while (!socket.isClosed && clock() < deadline + GRACE_MILLIS) {
                        // Only accept while a slot is free: accept() is what dequeues a client, so
                        // staying under the cap leaves the overflow in the kernel backlog, where the
                        // real browser waits a slot (bounded by one soTimeout) instead of being held
                        // behind every hostile connection in turn.
                        if (inFlight.get() >= MAX_IN_FLIGHT) {
                            Thread.sleep(ACCEPT_BACKOFF_MILLIS)
                            continue
                        }
                        val client =
                            try {
                                socket.accept()
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            }
                        dispatch(client)
                    }
                } catch (_: Throwable) {
                    // Close, timeout and the UI stopping the screen all land here. Nothing to report.
                } finally {
                    close()
                }
            }, "nirowear-pairing").apply {
                isDaemon = true
                start()
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Both closes are what actually stop the listener and unblock the workers; they are fast and
        // stay on the caller so `close()` returns with the port already shut.
        runCatching { socket.close() }
        // Closing the listener alone leaves handlers blocked in read() on accepted clients, so those
        // sockets would outlive the screen. Every live client is closed, not just the newest.
        val stalled = synchronized(liveClients) { liveClients.toList() }
        stalled.forEach { runCatching { it.close() } }
        // The join used to run on the CALLER, which is the Compose UI thread on the two paths that
        // matter (the Pairing button and the screen's onDispose): a session ending while a peer sat
        // in read() froze the dial for up to five seconds. It now happens on a short-lived daemon
        // thread, so the proof still exists without costing a frame. Skipped when the worker is the
        // caller — its own `finally` calls close(), and a self-join is a guaranteed five-second wait.
        if (worker != Thread.currentThread()) {
            worker?.let { dying ->
                Thread {
                    runCatching { dying.join(5000) }
                }.apply { isDaemon = true }.start()
            }
        }
    }

    /**
     * Runs one request on its own thread and returns IMMEDIATELY.
     *
     * This used to join the request thread before accepting the next client, so any peer that
     * connected and said nothing held the listener for one `soTimeout` (2 s) per connection. Measured
     * with the audit probe: 4 idle preconnects delayed the real browser by 8,015 ms and 8 delayed it
     * by 16,018 ms — linear in the attacker's connection count, against a 120 s session. A burst of
     * about sixty was therefore enough to push the phone's first request past the deadline, and the
     * user saw a page that never loaded with nothing on the watch explaining it.
     *
     * Concurrency removes the queueing; [MAX_IN_FLIGHT] is what keeps it bounded, and each handler
     * still owns its own wall clock inside [readHead]/[readBounded], so no handler depends on another
     * finishing.
     */
    private fun dispatch(client: Socket) {
        synchronized(liveClients) {
            if (closed) {
                runCatching { client.close() }
                return
            }
            inFlight.incrementAndGet()
            liveClients += client
        }
        Thread({
            try {
                client.use { runCatching { handle(it) } }
            } finally {
                synchronized(liveClients) { liveClients -= client }
                runCatching { client.close() }
                inFlight.decrementAndGet()
            }
        }, "nirowear-pairing-request").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(client: Socket) {
        // A per-read timeout alone lets one byte every 1.9 s hold the single worker for hours and
        // outlive the screen. The wall clock is the real bound.
        val requestDeadline = clock() + REQUEST_MILLIS
        client.soTimeout = 2000
        val input = client.getInputStream()
        val head = readHead(input, requestDeadline) ?: return respond(client, 400, TEXT, "bad request")
        val lines = head.split("\r\n")
        val parts = lines.firstOrNull().orEmpty().split(' ')
        if (parts.size < 2) return respond(client, 400, TEXT, "bad request")
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val contentLengthHeader =
            lines
                .drop(1)
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
        val declaredLong =
            if (contentLengthHeader == null) {
                0L
            } else {
                contentLengthHeader.toLongOrNull()?.takeIf { it >= 0L }
                    ?: return respond(client, 400, TEXT, "bad request")
            }
        if (declaredLong > MAX_BODY) return respond(client, 400, TEXT, "too large")
        val declared = declaredLong.toInt()
        val body =
            if (declared > 0) {
                readExactly(input, declared, requestDeadline) ?: return respond(client, 400, TEXT, "short body")
            } else {
                ByteArray(0)
            }
        // The whole session lives under an unguessable prefix. A caller without the capability gets
        // 404 on every route, which is also the honest answer: there is nothing here for them.
        //
        // The prefix is compared in CONSTANT TIME. It used to be `path.startsWith(prefix)`, which
        // returns at the first mismatching byte, so a peer on the same LAN could recover the 128-bit
        // token by timing the 404s a byte at a time — the later MessageDigest.isEqual in pair() only
        // protected the POST body after this shortcut had already leaked the prefix.
        val segment = path.trimStart('/').substringBefore('/')
        val presentedPrefix = runCatching { unb64(segment) }.getOrNull()
        val authorised =
            presentedPrefix != null &&
                MessageDigest.isEqual(token, presentedPrefix) &&
                path.length > segment.length + 1
        val prefix = "/$tokenB64"
        when {
            !authorised -> respond(client, 404, TEXT, "not found")
            method == "GET" && path == "$prefix/" -> respond(client, 200, HTML, PAIRING_PAGE)
            method == "GET" && path == "$prefix/handshake" -> respond(client, 200, JSON, handshake())
            method == "POST" && path == "$prefix/models" -> fetchModels(client, body)
            method == "POST" && path == "$prefix/pair" -> pair(client, body)
            else -> respond(client, 404, TEXT, "not found")
        }
    }

    /**
     * Public half of the ephemeral key only. The token is deliberately absent: it is the capability
     * the caller already had to know in order to reach this route, and echoing it back would hand
     * the session to anyone who could merely connect.
     */
    private fun handshake(): String =
        buildJsonObject {
            put("v", 1)
            put("pub", b64(uncompressed(pair.public as ECPublicKey)))
            initialPreset?.let { preset ->
                put(
                    "preset",
                    buildJsonObject {
                        put("type", preset.type.name)
                        put("name", preset.name)
                        put("base", preset.baseUrl)
                        put("model", preset.modelId)
                    },
                )
            }
        }.toString()

    private fun verifyEnvelope(
        client: Socket,
        body: ByteArray,
    ): JsonObject? {
        // Expiry is checked before the token so a late request cannot burn the session's only use.
        // `>=`, not `>`: `deadline` is the instant the session stops being valid, so a request
        // arriving exactly at it is late.
        if (clock() >= deadline) {
            respond(client, 410, JSON, """{"ok":false}""")
            return null
        }
        if (closed) return null
        if (consumed.get()) {
            respond(client, 401, JSON, """{"ok":false}""")
            return null
        }
        val root =
            runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull()
                ?: run {
                    respond(client, 400, JSON, """{"ok":false}""")
                    return null
                }
        val presented = runCatching { unb64(root.getValue("t").jsonPrimitive.content) }.getOrNull()
        // Constant-time, before any crypto work: a wrong token must not even reach ECDH.
        if (presented == null || !MessageDigest.isEqual(token, presented)) {
            respond(client, 401, JSON, """{"ok":false}""")
            return null
        }
        return root
    }

    /** Encrypted non-consuming model discovery proxy so the browser avoids CORS and plaintext keys. */
    private fun fetchModels(
        client: Socket,
        body: ByteArray,
    ) {
        val root = verifyEnvelope(client, body) ?: return
        val request =
            try {
                decryptJson(root)?.let(::toModelRequest)
            } catch (_: Exception) {
                null
            }
        if (request == null) return respond(client, 400, JSON, """{"ok":false}""")
        val fetcher =
            onFetchModels ?: return respond(client, 501, JSON, """{"ok":false,"code":"UNAVAILABLE"}""")
        val outcome =
            runCatching { fetcher(request) }
                .getOrElse { ModelDiscoveryResult.Unavailable(com.nirowear.provider.ProviderError.PROTOCOL) }
        val response =
            when (outcome) {
                is ModelDiscoveryResult.Models -> {
                    buildJsonObject {
                        put("ok", true)
                        put("models", JsonArray(outcome.ids.map { JsonPrimitive(it) }))
                    }.toString()
                }

                is ModelDiscoveryResult.Unavailable -> {
                    buildJsonObject {
                        put("ok", false)
                        put("code", outcome.error.name)
                    }.toString()
                }
            }
        respond(client, 200, JSON, response)
    }

    private fun pair(
        client: Socket,
        body: ByteArray,
    ) {
        val root = verifyEnvelope(client, body) ?: return
        // Only a payload that actually decrypts and validates consumes the session. Marking it
        // consumed before validation let one garbage body burn the TTL and block the real phone.
        val payload =
            try {
                decryptJson(root)?.let(::toPayload)
            } catch (_: Exception) {
                null
            }
        // One generic 400 for every failure: a decrypt error must not tell an attacker which byte was
        // wrong, and a rejected payload must never leave a half-written profile behind.
        if (payload == null) return respond(client, 400, JSON, """{"ok":false}""")
        // Two clients can be served at once, so "check then assign" would let both of them save. Only
        // the winner of this CAS consumes the capability; the loser is refused exactly like a replay.
        if (!consumed.compareAndSet(false, true)) return respond(client, 401, JSON, """{"ok":false}""")
        // Answer before handing the payload on: the consumer may close the listener from the
        // callback, and the browser must not lose the confirmation to that race.
        respond(client, 200, JSON, """{"ok":true}""")
        runCatching { onPayload(payload) }
    }

    private fun decryptJson(root: JsonObject): String? {
        if (root["v"]?.jsonPrimitive?.intOrNull != 1) return null
        val browserKey = decodePoint(unb64(root.getValue("pub").jsonPrimitive.content)) ?: return null
        val iv = unb64(root.getValue("iv").jsonPrimitive.content)
        val ciphertext = unb64(root.getValue("ct").jsonPrimitive.content)
        if (iv.size != 12 || ciphertext.size !in 17..MAX_BODY) return null
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(browserKey, true)
        val shared = agreement.generateSecret()
        val plain =
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(shared, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(token)
                cipher.doFinal(ciphertext)
            } finally {
                shared.fill(0)
            }
        return try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    private fun toModelRequest(json: String): PairingModelRequest? =
        runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val rawType = requireNotNull(root["type"].stringOrNull())
            require(rawType.length <= 32)
            val type = ProviderType.valueOf(rawType)
            val baseRaw = requireNotNull(root["base"].stringOrNull()).trim()
            require(baseRaw.isNotEmpty() && baseRaw.length <= 2048)
            val normalizedBase = Endpoints.normalize(baseRaw, allowLocalHttp)
            val apiKey = requireNotNull(root["key"].stringOrNull())
            require(apiKey.length <= 8192)
            SecretValue(apiKey)
            PairingModelRequest(type = type, baseUrl = normalizedBase, apiKey = apiKey)
        }.getOrNull()

    private fun toPayload(json: String): PairingPayload? =
        runCatching {
            val root = Json.parseToJsonElement(json).jsonObject

            fun field(
                name: String,
                max: Int,
            ): String {
                val value = requireNotNull(root[name].stringOrNull()) { "string field required" }
                require(value.length <= max) { "field too long" }
                return value
            }
            val type = ProviderType.valueOf(field("type", 32))
            val baseRaw = field("base", 2048).trim()
            val modelId = field("model", 256).trim()
            require(baseRaw.isNotEmpty() && modelId.isNotBlank()) { "base and model required" }
            val normalizedBase = Endpoints.normalize(baseRaw, allowLocalHttp)
            val apiKey = field("key", 8192)
            SecretValue(apiKey) // Storage contract stays the gate: throws on non-printable or blank.
            PairingPayload(
                type = type,
                name = field("name", 100).trim(),
                baseUrl = normalizedBase,
                modelId = modelId,
                apiKey = apiKey,
            )
        }.getOrNull()

    private fun decodePoint(raw: ByteArray): ECPublicKey? {
        if (raw.size != 65 || raw[0] != 4.toByte()) return null
        val params = (pair.public as ECPublicKey).params
        val x = java.math.BigInteger(1, raw.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, raw.copyOfRange(33, 65))
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
        }.getOrNull()
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

    private fun respond(
        client: Socket,
        code: Int,
        type: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header =
            "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
                "Content-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    /** Reads until the blank line that ends the request headers. Cap and wall clock keep a peer bounded. */
    private fun readHead(
        input: InputStream,
        deadline: Long,
    ): String? {
        val out = StringBuilder()
        while (out.length < MAX_HEAD) {
            if (clock() > deadline) return null
            val byte = input.read()
            if (byte < 0) return null
            out.append(byte.toChar())
            if (out.endsWith("\r\n\r\n")) return out.toString()
        }
        return null
    }

    /**
     * Bounded body read: the length is one header integer from an unauthenticated peer, so the
     * cap is enforced here as well as at the call site, and the request wall clock is checked
     * inside the loop so a trickling sender cannot hold the single worker past it.
     */
    private fun readExactly(
        input: InputStream,
        length: Int,
        deadline: Long,
    ): ByteArray? = readBounded(input, length, deadline, clock)

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    internal companion object {
        /**
         * Cap for the whole POST body.
         *
         * Was 4096, which silently made pairing unable to carry a long key: the body holds base64
         * ciphertext (4/3 of plaintext + 16-byte tag) plus pub/iv/token, so measured against the
         * real JSON shape a 3000-character key already produced a ~4430-byte body and was rejected
         * with a bare "too large". `ProviderContract` and manual entry both allow 8192 characters,
         * so pairing was the only path with a lower, undocumented ceiling.
         *
         * 16 KiB covers an 8192-character key with room for a long base URL and model id. The body
         * is still bounded, and the read itself is bounded by this value.
         */
        const val MAX_BODY = 16 * 1024
        const val MAX_HEAD = 8192
        const val GRACE_MILLIS = 5_000L
        const val REQUEST_MILLIS = 5_000L

        /**
         * Handler threads allowed at once.
         *
         * Eight matches the old queue depth, so a burst is absorbed without queueing the real
         * browser behind each connection in turn. Beyond that the overflow stays in the kernel
         * backlog, which costs a slot wait (at most one `soTimeout`) rather than a refusal.
         */
        const val MAX_IN_FLIGHT = 8

        /**
         * Kernel accept queue.
         *
         * Wider than [MAX_IN_FLIGHT] on purpose: the queue is not the thread bound, and a queue that
         * exactly matches the burst size turns a preconnect burst into a connection reset for the
         * real browser (measured: ConnectException with backlog 8 at an 8-socket burst).
         */
        const val ACCEPT_BACKLOG = 32

        /** Poll interval while every slot is busy; below the 2 s read timeout, far above a frame. */
        const val ACCEPT_BACKOFF_MILLIS = 20L
        const val HTML = "text/html; charset=utf-8"
        const val JSON = "application/json"
        const val TEXT = "text/plain; charset=utf-8"

        /**
         * Bounded body read: the length is one header integer from an unauthenticated peer, so the
         * cap is enforced here as well as at the call site, and the request wall clock is checked
         * inside the loop so a trickling sender cannot hold the single worker past it.
         */
        internal fun readBounded(
            input: InputStream,
            length: Int,
            deadline: Long,
            now: () -> Long = System::currentTimeMillis,
        ): ByteArray? {
            if (length <= 0 || length > MAX_BODY) return null
            val buffer = ByteArray(length)
            var offset = 0
            while (offset < length) {
                if (now() > deadline) return null
                val read = input.read(buffer, offset, length - offset)
                if (read < 0) return null
                offset += read
            }
            return buffer
        }
    }
}

/**
 * Served to the phone browser. Dependency-free on purpose: no CDN, no framework, no build step.
 * The page never sees the watch private key; WebCrypto derives a shared AES-GCM key instead, so a
 * LAN sniffer sees only ciphertext.
 */
internal const val PAIRING_PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark">
<title>NiroWear setup</title>
<style>
:root{--bg:#0A0D11;--card:#141A21;--inset:#0F141A;--border:#23303E;--border-hi:#355064;--text:#F1F5F9;--muted:#8B9BB0;--teal:#7EE6CC;--teal-ink:#05241F;--teal-soft:#12332F;--warn:#FFD28A;--err:#FF8B8B;--ok:#8EF0C8}
*{box-sizing:border-box}
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;padding:18px 14px 36px;background:radial-gradient(120% 60% at 50% 0%,#10222A 0%,var(--bg) 55%);color:var(--text);min-height:100vh;-webkit-font-smoothing:antialiased}
.wrap{max-width:440px;margin:0 auto}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:14px}
.logo{width:38px;height:38px;border-radius:11px;background:linear-gradient(145deg,#133238,#0C1F26);border:1px solid #265159;display:grid;place-items:center;flex:none;box-shadow:0 6px 18px rgba(0,0,0,.35)}
.brand h1{font-size:20px;font-weight:700;letter-spacing:-.01em;margin:0}
.brand .sub{font-size:12.5px;color:var(--muted);margin:2px 0 0;line-height:1.35}
.pill{display:inline-flex;align-items:center;gap:7px;padding:5px 11px;border-radius:999px;background:var(--inset);border:1px solid var(--border);font-size:12px;color:#CFE7E1;margin-bottom:14px}
.dot{width:7px;height:7px;border-radius:50%;background:var(--warn);flex:none}
.pill.ready .dot{background:var(--ok);box-shadow:0 0 8px rgba(142,240,200,.65)}
.pill.err .dot{background:var(--err)}
.card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:16px;box-shadow:0 16px 40px rgba(0,0,0,.45)}
.chips{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-top:6px}
.chip{appearance:none;background:var(--inset);color:var(--muted);border:1px solid var(--border);border-radius:9px;padding:8px 4px;font-size:12px;font-weight:600;cursor:pointer;text-align:center;margin:0;width:100%;transition:border-color .15s,background .15s,color .15s}
.chip.active{background:var(--teal-soft);color:var(--teal);border-color:#2D7F6F}
label{display:block;font-size:12px;font-weight:600;color:var(--muted);margin:13px 0 5px;letter-spacing:.01em}
input,select{width:100%;padding:11px 12px;font-size:15px;border-radius:10px;border:1px solid var(--border);background:var(--inset);color:var(--text);outline:none;transition:border-color .15s}
input:focus,select:focus{border-color:var(--teal)}
.keyRow{display:flex;gap:6px;align-items:stretch}
.keyRow input{flex:1;min-width:0}
.miniBtn{width:auto;margin:0;padding:0 11px;border-radius:10px;border:1px solid var(--border);background:#18212B;color:#CFE7E1;font-size:12.5px;font-weight:600;cursor:pointer;white-space:nowrap}
.miniBtn:hover{border-color:var(--border-hi)}
button.primary{width:100%;margin-top:18px;padding:13px 16px;font-size:15.5px;border-radius:11px;border:0;background:var(--teal);color:var(--teal-ink);font-weight:700;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px}
button.secondary{width:100%;margin-top:12px;padding:11px 14px;font-size:14px;border-radius:10px;border:1px solid #2A464D;background:#15232A;color:#D4F7ED;font-weight:600;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px}
button:disabled{opacity:.5;cursor:default}
.hidden{display:none !important}
#modelPickBox{margin-top:10px;padding:10px;border-radius:11px;background:#0D1318;border:1px solid var(--border)}
#modelFilter{margin-bottom:8px}
.spin{width:14px;height:14px;border-radius:50%;border:2px solid rgba(212,247,237,.28);border-top-color:var(--teal);animation:rot .7s linear infinite}
@keyframes rot{to{transform:rotate(360deg)}}
#out{margin-top:14px;padding:10px 12px;border-radius:10px;font-size:13px;line-height:1.4;min-height:18px;background:transparent;border:1px solid transparent;color:var(--warn)}
#out:empty{display:none}
#out.info{background:#12212B;border-color:#26465C;color:#CFE7E1}
#out.ok{background:#102B24;border-color:#266957;color:var(--ok)}
#out.warn{background:#292012;border-color:#5E451F;color:var(--warn)}
#out.err{background:#2C1418;border-color:#692B34;color:var(--err)}
details.sec{margin-top:14px;background:var(--card);border:1px solid var(--border);border-radius:12px;padding:10px 13px}
details.sec summary{font-size:12.5px;font-weight:600;color:#B8C7D9;cursor:pointer;list-style:none;display:flex;align-items:center;justify-content:space-between}
details.sec summary::after{content:"+";color:var(--teal);font-size:15px;font-weight:700}
details.sec[open] summary::after{content:"-"}
details.sec p{font-size:12.5px;line-height:1.5;color:var(--muted);margin:9px 0 2px}
</style></head><body>
<div class="wrap">
<div class="brand">
 <div class="logo" aria-hidden="true">
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
   <circle cx="12" cy="12" r="9" stroke="#7EE6CC" stroke-opacity=".35" stroke-width="1.6"/>
   <path d="M7.5 14.5C9 9.5 15 9.5 16.5 14.5" stroke="#7EE6CC" stroke-width="2" stroke-linecap="round"/>
   <circle cx="12" cy="8.5" r="1.6" fill="#7EE6CC"/>
  </svg>
 </div>
 <div>
  <h1>NiroWear</h1>
  <div class="sub">Send this provider to your watch. It is encrypted before it leaves this page.</div>
 </div>
</div>
<div class="pill" id="connPill"><span class="dot"></span><span id="connText">Connecting over local Wi-Fi · ECDH P-256</span></div>
<div class="card">
 <label for="type">Provider</label>
 <div class="chips" id="providerChips" role="group" aria-label="Quick provider">
  <button type="button" class="chip active" data-type="OPENAI">OpenAI</button>
  <button type="button" class="chip" data-type="ANTHROPIC">Anthropic</button>
  <button type="button" class="chip" data-type="GEMINI">Gemini</button>
  <button type="button" class="chip" data-type="OPENAI_COMPATIBLE">Custom</button>
 </div>
 <select id="type" style="margin-top:8px">
  <option value="OPENAI">OpenAI</option>
  <option value="ANTHROPIC">Anthropic</option>
  <option value="GEMINI">Gemini</option>
  <option value="OPENAI_COMPATIBLE">OpenAI-compatible</option>
 </select>
 <label for="name">Name</label><input id="name" value="OpenAI">
 <label for="base">Base URL</label><input id="base" value="https://api.openai.com/v1" inputmode="url" autocapitalize="off" spellcheck="false">
 <label for="key">API key</label>
 <div class="keyRow">
  <input id="key" type="password" autocomplete="off" spellcheck="false" placeholder="Paste API key">
  <button id="toggleKey" type="button" class="miniBtn" aria-label="Show API key">Show</button>
  <button id="pasteKey" type="button" class="miniBtn" aria-label="Paste from clipboard">Paste</button>
 </div>
 <button id="fetchModels" type="button" class="secondary"><span id="fetchSpin" class="spin hidden"></span><span id="fetchLabel">Fetch models</span></button>
 <div id="modelPickBox" class="hidden">
  <input id="modelFilter" class="hidden" placeholder="Filter fetched models...">
  <select id="models"></select>
 </div>
 <label for="model">Model ID</label><input id="model" placeholder="required — e.g. gpt-4o-mini" autocapitalize="off" spellcheck="false">
 <button id="go" type="button" class="primary">Send to watch</button>
 <div id="out" role="status" aria-live="polite"></div>
</div>
<details class="sec">
 <summary>Security &amp; self-signed certificate</summary>
 <p>Same Wi-Fi only. The watch serves its own certificate, so your browser warns that it is not trusted — accept it to load this page. Nothing is sent until you press Fetch models or Send. Encrypted with ECDH P-256 + AES-GCM, but the handshake is unauthenticated — confirm the URL on the watch matches. Prefer manual entry on untrusted networks.</p>
</details>
</div>
<script>
function b64u(buf){var b='',u=new Uint8Array(buf),i;
for(i=0;i<u.length;i++){b+=String.fromCharCode(u[i]);}
return btoa(b).replace(/\+/g,'-').replace(/\//g,'_').split('=').join('');}
function unb64u(s){s=s.replace(/-/g,'+').replace(/_/g,'/');var b=atob(s),u=new Uint8Array(b.length),i;
for(i=0;i<b.length;i++){u[i]=b.charCodeAt(i);}return u;}
var PRESETS={
 OPENAI:{name:'OpenAI',base:'https://api.openai.com/v1'},
 ANTHROPIC:{name:'Anthropic',base:'https://api.anthropic.com/v1'},
 GEMINI:{name:'Gemini',base:'https://generativelanguage.googleapis.com/v1beta'},
 OPENAI_COMPATIBLE:{name:'Custom provider',base:''}
};
var ready=null,allModels=[],lastType='OPENAI',sent=false;
// The capability token is the first path segment the user was given. Reading it from our own URL
// keeps it out of the handshake response, so an unauthenticated caller can never learn it.
var CAP=decodeURIComponent(location.pathname.split('/')[1]||'');
function el(id){return document.getElementById(id);}
function setStatus(msg,kind){
 var o=el('out');
 o.textContent=msg||'';
 o.className=msg?(kind||'warn'):'';
}
function syncChips(t){
 var chips=document.querySelectorAll('#providerChips .chip');
 for(var i=0;i<chips.length;i++){
  chips[i].classList.toggle('active',chips[i].getAttribute('data-type')===t);
 }
}
function clearModels(){
 allModels=[];
 el('modelPickBox').className='hidden';
 el('modelFilter').className='hidden';
 el('modelFilter').value='';
 el('models').innerHTML='';
}
function renderModels(){
 var q=el('modelFilter').value.trim().toLowerCase();
 var filtered=q?allModels.filter(function(m){return m.toLowerCase().indexOf(q)>=0;}):allModels;
 var sel=el('models');
 sel.innerHTML='';
 var ph=document.createElement('option');
 ph.value='';
 ph.textContent='-- Select a model ('+filtered.length+') --';
 sel.appendChild(ph);
 for(var i=0;i<filtered.length;i++){
  var opt=document.createElement('option');
  opt.value=filtered[i];
  opt.textContent=filtered[i];
  if(filtered[i]===el('model').value){opt.selected=true;}
  sel.appendChild(opt);
 }
}
async function encryptBody(obj){
 // A FRESH nonce per request. Reusing a nonce under the same ECDH key violates AES-GCM.
 var iv=crypto.getRandomValues(new Uint8Array(12));
 var ct=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:ready.aad},
  ready.aes,new TextEncoder().encode(JSON.stringify(obj)));
 return JSON.stringify({v:1,t:b64u(ready.aad),pub:ready.pub,iv:b64u(iv),ct:b64u(ct)});
}
function onTypeChange(){
 var next=el('type').value;
 var prevP=PRESETS[lastType]||{name:'',base:''};
 var nextP=PRESETS[next]||{name:'',base:''};
 var n=el('name').value.trim(),b=el('base').value.trim();
 if(!n||n===prevP.name){el('name').value=nextP.name;}
 if(!b||b===prevP.base){el('base').value=nextP.base;}
 lastType=next;
 syncChips(next);
 clearModels();
}
el('type').onchange=onTypeChange;
el('providerChips').onclick=function(ev){
 var btn=ev.target&&ev.target.closest?ev.target.closest('.chip'):null;
 if(!btn)return;
 var t=btn.getAttribute('data-type');
 if(!t||t===el('type').value)return;
 el('type').value=t;
 onTypeChange();
};
el('toggleKey').onclick=function(){
 var k=el('key'),btn=el('toggleKey');
 var show=k.type==='password';
 k.type=show?'text':'password';
 btn.textContent=show?'Hide':'Show';
 btn.setAttribute('aria-label',show?'Hide API key':'Show API key');
};
el('pasteKey').onclick=async function(){
 if(!navigator.clipboard||!navigator.clipboard.readText){
  setStatus('Clipboard paste unavailable in this browser — paste into API key directly.','warn');
  el('key').focus();
  return;
 }
 try{
  var txt=(await navigator.clipboard.readText()).trim();
  if(txt){el('key').value=txt;setStatus('API key pasted from clipboard.','info');}
  else{setStatus('Clipboard is empty.','warn');}
 }catch(e){
  setStatus('Clipboard permission denied — tap API key and paste manually.','warn');
  el('key').focus();
 }
};
el('modelFilter').oninput=renderModels;
el('models').onchange=function(){
 if(el('models').value){el('model').value=el('models').value;}
};
async function init(){
 if(!CAP){throw new Error('missing capability');}
 var hs=await (await fetch('/'+CAP+'/handshake')).json();
 if(hs.preset&&PRESETS[hs.preset.type]){
  el('type').value=hs.preset.type;
  lastType=hs.preset.type;
  syncChips(hs.preset.type);
  if(hs.preset.name){el('name').value=hs.preset.name;}
  if(typeof hs.preset.base==='string'){el('base').value=hs.preset.base;}
  if(hs.preset.model){el('model').value=hs.preset.model;}
 }
 var wp=await crypto.subtle.importKey('raw',unb64u(hs.pub),{name:'ECDH',namedCurve:'P-256'},false,[]);
 var mine=await crypto.subtle.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);
 var shared=await crypto.subtle.deriveBits({name:'ECDH',public:wp},mine.privateKey,256);
 var aes=await crypto.subtle.importKey('raw',shared,{name:'AES-GCM'},false,['encrypt']);
 ready={aes:aes,pub:b64u(await crypto.subtle.exportKey('raw',mine.publicKey)),
  aad:unb64u(CAP)};
 el('connPill').className='pill ready';
 el('connText').textContent='Local Wi-Fi · ECDH P-256 ready';
}
function fetchErrorText(code){
 if(code==='INVALID_CREDENTIAL')return 'API key rejected. Enter model ID manually or fix key.';
 if(code==='PERMISSION_DENIED')return 'Provider permission denied. Enter model ID manually.';
 if(code==='NETWORK_UNAVAILABLE')return 'Watch cannot reach provider. Check watch network.';
 if(code==='TIMEOUT')return 'Provider timed out. Enter model ID manually.';
 return 'Models unavailable. Enter model ID manually.';
}
el('fetchModels').onclick=async function(){
 if(sent)return;
 var btn=el('fetchModels'),spin=el('fetchSpin'),lbl=el('fetchLabel');
 if(!ready){setStatus('Still connecting. Wait a moment and try again.','warn');return;}
 var base=el('base').value.trim(),key=el('key').value.trim();
 if(!base||!key){setStatus('Enter Base URL and API key first.','warn');return;}
 btn.disabled=true;
 spin.className='spin';
 lbl.textContent='Fetching models...';
 setStatus('Fetching models...','info');
 try{
  var body=await encryptBody({type:el('type').value,base:base,key:key});
  var r=await fetch('/'+CAP+'/models',{method:'POST',headers:{'Content-Type':'application/json'},body:body});
  if(sent)return;
  if(r.status===401||r.status===410){setStatus('Pairing expired. Restart pairing on the watch.','err');return;}
  if(!r.ok){setStatus('Check Base URL and API key format.','err');return;}
  var res=await r.json();
  if(sent)return;
  if(res.ok&&Array.isArray(res.models)&&res.models.length>0){
   allModels=res.models;
   el('modelPickBox').className='';
   el('modelFilter').className=allModels.length>12?'':'hidden';
   renderModels();
   setStatus(allModels.length+' models loaded. Select one or enter below.','ok');
  }else{
   clearModels();
   setStatus(fetchErrorText(res.code),'warn');
  }
 }catch(e){
  if(sent)return;
  clearModels();
  setStatus('Could not fetch models. Enter model ID manually.','err');
 }finally{
  spin.className='spin hidden';
  lbl.textContent='Fetch models';
  if(!sent)btn.disabled=false;
 }
};
el('go').onclick=async function(){
 var btn=el('go'),fetchBtn=el('fetchModels');
 if(!ready){setStatus('Still connecting. Wait a moment and try again.','warn');return;}
 var base=el('base').value.trim(),model=el('model').value.trim(),key=el('key').value.trim();
 if(!base||!model||!key){setStatus('Base URL, Model ID, and API key are required.','warn');return;}
 btn.disabled=true;
 setStatus('Encrypting & sending to watch...','info');
 try{
  var body=await encryptBody({type:el('type').value,
   name:el('name').value,base:base,model:model,key:key});
  var r=await fetch('/'+CAP+'/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:body});
  setStatus(r.ok?'Sent. Finish on the watch.':'Rejected. Check Base URL/key or restart pairing.',r.ok?'ok':'err');
  if(r.ok){sent=true;el('key').value='';fetchBtn.disabled=true;}else{btn.disabled=false;}
 }catch(e){btn.disabled=false;setStatus('Could not send. Restart pairing on the watch.','err');}
};
init().catch(function(e){
  // WebCrypto (crypto.subtle) only exists in a secure context. This page is served over https with a
  // certificate the watch minted for this session, so it IS a secure context once the browser
  // accepted the warning; a browser that refused the certificate never runs this script at all.
  // Reaching this branch therefore means the handshake itself failed, and saying "plain HTTP" here
  // would send the user after a cause that no longer exists.
  el('connPill').className='pill err';
  el('connText').textContent='Pairing connection unavailable';
  if(!window.isSecureContext || typeof crypto==='undefined' || !crypto.subtle){
    setStatus('This browser will not run crypto on this page, so the key cannot be '+
      'encrypted here. Enter the key on the watch instead (Cancel pairing below).','err');
  } else {
    setStatus('Handshake failed. Restart pairing on the watch.','err');
  }
});
</script></body></html>
"""
