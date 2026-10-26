package com.nirowear.settings

import com.nirowear.provider.ProviderType
import com.nirowear.provider.SecretValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
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
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Credential arrival over the LAN, phone browser -> watch. No companion app, no backend, no cloud.
 *
 * The watch serves one small page; the browser encrypts the form with an ephemeral ECDH P-256 secret
 * and AES-GCM. The watch never sends a key out, only accepts one in, and the whole listener lives only
 * while the setup screen is resumed. Cleartext HTTP is acceptable here because the payload is
 * end-to-end encrypted and `usesCleartextTraffic="false"` governs outbound requests only, not an
 * inbound `ServerSocket`.
 *
 * Deliberately no dependency: `ServerSocket` + a ~40 line request reader. No NanoHTTPD, no Ktor.
 */
internal data class PairingPayload(
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String,
)

internal class PairingServer(
    private val host: String,
    ttlMillis: Long = 120_000,
    private val onPayload: (PairingPayload) -> Unit,
) : Closeable {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val pair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    private val deadline = System.currentTimeMillis() + ttlMillis

    // Bound to the address we advertise, not the wildcard: the listener must not be reachable on a
    // tethered or secondary interface the user never saw.
    private val socket = ServerSocket(0, 1, InetAddress.getByName(host))

    @Volatile private var consumed = false

    @Volatile private var closed = false

    @Volatile private var active: Socket? = null
    private var worker: Thread? = null

    val port: Int get() = socket.localPort

    /**
     * Capability URL. The token is a path segment, not something the server hands out: whoever can
     * read the watch screen learns it, and nobody else does. It doubles as the AES-GCM associated
     * data, so a payload encrypted for one session cannot be replayed into another.
     */
    val url: String get() = "http://$host:$port/${b64(token)}/"

    fun start() {
        if (worker != null) return
        socket.soTimeout = 500
        worker =
            Thread({
                try {
                    // Accepts for a short grace past the deadline so an expired attempt still gets the
                    // explicit 410 instead of a bare connection reset, then self-closes.
                    while (!socket.isClosed && System.currentTimeMillis() < deadline + GRACE_MILLIS) {
                        val client =
                            try {
                                socket.accept()
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            }
                        active = client
                        try {
                            client.use { handle(it) }
                        } catch (_: java.io.IOException) {
                            // A single bad client must not kill the listener. Before this catch,
                            // an idle connection (port scan, browser preconnect, `nc -z`) hit the
                            // 2s read timeout, threw SocketTimeoutException, unwound past this
                            // try/finally into the outer `catch (_: Throwable)`, and that catch's
                            // finally closed the socket — so any scanner could end pairing with no
                            // token. Keep accepting; only the loop condition ends the session.
                        } finally {
                            active = null
                        }
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
        runCatching { socket.close() }
        // Closing the listener alone leaves the worker blocked in read() on an accepted client, so
        // the socket would outlive the screen. Closing the active client unblocks it.
        runCatching { active?.close() }
    }

    private fun handle(client: Socket) {
        // A per-read timeout alone lets one byte every 1.9 s hold the single worker for hours and
        // outlive the screen. The wall clock is the real bound.
        val requestDeadline = System.currentTimeMillis() + REQUEST_MILLIS
        client.soTimeout = 2000
        val input = client.getInputStream()
        val head = readHead(input, requestDeadline) ?: return respond(client, 400, TEXT, "bad request")
        val lines = head.split("\r\n")
        val parts = lines.firstOrNull().orEmpty().split(' ')
        if (parts.size < 2) return respond(client, 400, TEXT, "bad request")
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val declared =
            lines
                .drop(1)
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: 0
        if (declared > MAX_BODY) return respond(client, 400, TEXT, "too large")
        val body =
            if (declared > 0) {
                readExactly(input, declared) ?: return respond(client, 400, TEXT, "short body")
            } else {
                ByteArray(0)
            }
        // The whole session lives under an unguessable prefix. A caller without the capability gets
        // 404 on every route, which is also the honest answer: there is nothing here for them.
        val prefix = "/${b64(token)}"
        when {
            !path.startsWith(prefix) -> respond(client, 404, TEXT, "not found")
            method == "GET" && path == "$prefix/" -> respond(client, 200, HTML, PAIRING_PAGE)
            method == "GET" && path == "$prefix/handshake" -> respond(client, 200, JSON, handshake())
            method == "POST" && path == "$prefix/pair" -> pair(client, body)
            else -> respond(client, 404, TEXT, "not found")
        }
    }

    /**
     * Public half of the ephemeral key only. The token is deliberately absent: it is the capability
     * the caller already had to know in order to reach this route, and echoing it back would hand
     * the session to anyone who could merely connect.
     */
    private fun handshake(): String = """{"v":1,"pub":"${b64(uncompressed(pair.public as ECPublicKey))}"}"""

    private fun pair(
        client: Socket,
        body: ByteArray,
    ) {
        // Expiry is checked before the token so a late request cannot burn the session's only use.
        if (System.currentTimeMillis() > deadline) return respond(client, 410, JSON, """{"ok":false}""")
        if (consumed) return respond(client, 401, JSON, """{"ok":false}""")
        val root =
            runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull()
                ?: return respond(client, 400, JSON, """{"ok":false}""")
        val presented = runCatching { unb64(root.getValue("t").jsonPrimitive.content) }.getOrNull()
        // Constant-time, before any crypto work: a wrong token must not even reach ECDH.
        if (presented == null || !MessageDigest.isEqual(token, presented)) {
            return respond(client, 401, JSON, """{"ok":false}""")
        }
        // Only a payload that actually decrypts consumes the session. Marking it consumed before
        // decryption let one garbage body burn the remaining TTL and block the real phone.
        val payload =
            try {
                decrypt(root)
            } catch (_: Exception) {
                null
            }
        // One generic 400 for every failure: a decrypt error must not tell an attacker which byte was
        // wrong, and a rejected payload must never leave a half-written profile behind.
        if (payload == null) return respond(client, 400, JSON, """{"ok":false}""")
        consumed = true
        // Answer before handing the payload on: the consumer may close the listener from the
        // callback, and the browser must not lose the confirmation to that race.
        respond(client, 200, JSON, """{"ok":true}""")
        runCatching { onPayload(payload) }
    }

    private fun decrypt(root: kotlinx.serialization.json.JsonObject): PairingPayload? {
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
            toPayload(String(plain, Charsets.UTF_8))
        } finally {
            plain.fill(0)
        }
    }

    private fun toPayload(json: String): PairingPayload? =
        runCatching {
            val root = Json.parseToJsonElement(json).jsonObject

            fun field(
                name: String,
                max: Int,
            ): String {
                val value = root.getValue(name).jsonPrimitive.content
                require(value.length <= max) { "field too long" }
                return value
            }
            val type = ProviderType.valueOf(field("type", 32))
            val apiKey = field("key", 8192)
            SecretValue(apiKey) // Storage contract stays the gate: throws on non-printable or blank.
            PairingPayload(
                type = type,
                name = field("name", 100).trim(),
                baseUrl = field("base", 2048).trim(),
                modelId = field("model", 256).trim(),
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
            if (System.currentTimeMillis() > deadline) return null
            val byte = input.read()
            if (byte < 0) return null
            out.append(byte.toChar())
            if (out.endsWith("\r\n\r\n")) return out.toString()
        }
        return null
    }

    private fun readExactly(
        input: InputStream,
        length: Int,
    ): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private companion object {
        const val MAX_BODY = 4096
        const val MAX_HEAD = 8192
        const val GRACE_MILLIS = 5_000L
        const val REQUEST_MILLIS = 5_000L
        const val HTML = "text/html; charset=utf-8"
        const val JSON = "application/json"
        const val TEXT = "text/plain; charset=utf-8"
    }
}

/**
 * Served to the phone browser. Dependency-free on purpose: no CDN, no framework, no build step.
 * The page never sees the watch private key; WebCrypto derives a shared AES-GCM key instead, so a
 * LAN sniffer sees only ciphertext.
 */
internal const val PAIRING_PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NiroWear setup</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:20px;background:#111;color:#eee}
h1{font-size:19px;margin:0 0 4px}p{font-size:13px;color:#9aa0a6;margin:0 0 18px}
label{display:block;font-size:12px;color:#9aa0a6;margin:12px 0 4px}
input,select{width:100%;box-sizing:border-box;padding:11px;font-size:15px;border-radius:8px;
border:1px solid #41454d;background:#202124;color:#fff}
button{width:100%;margin-top:22px;padding:14px;font-size:16px;border-radius:8px;border:0;
background:#a8c7fa;color:#062e6f;font-weight:600}
#out{margin-top:16px;font-size:13px;color:#ffd28a;min-height:18px}
</style></head><body>
<h1>NiroWear</h1>
<p>Send this provider to your watch. It is encrypted before it leaves this page.</p>
<label>Provider</label>
<select id="type">
<option value="OPENAI">OpenAI</option>
<option value="ANTHROPIC">Anthropic</option>
<option value="GEMINI">Gemini</option>
<option value="OPENAI_COMPATIBLE">OpenAI-compatible</option>
</select>
<label>Name</label><input id="name" value="OpenAI">
<label>Base URL</label><input id="base" value="https://api.openai.com/v1">
<label>Model ID</label><input id="model" placeholder="optional, can be picked on the watch">
<label>API key</label><input id="key" type="password" autocomplete="off" spellcheck="false">
<button id="go">Send to watch</button>
<div id="out"></div>
<script>
var B='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
function b64u(buf){var b='',u=new Uint8Array(buf),i;
for(i=0;i<u.length;i++){b+=String.fromCharCode(u[i]);}
return btoa(b).replace(/\+/g,'-').replace(/\//g,'_').split('=').join('');}
function unb64u(s){s=s.replace(/-/g,'+').replace(/_/g,'/');var b=atob(s),u=new Uint8Array(b.length),i;
for(i=0;i<b.length;i++){u[i]=b.charCodeAt(i);}return u;}
var ready=null;
// The capability token is the first path segment the user was given. Reading it from our own URL
// keeps it out of the handshake response, so an unauthenticated caller can never learn it.
var CAP=decodeURIComponent(location.pathname.split('/')[1]||'');
async function init(){
 if(!CAP){throw new Error('missing capability');}
 var hs=await (await fetch('/'+CAP+'/handshake')).json();
 var wp=await crypto.subtle.importKey('raw',unb64u(hs.pub),{name:'ECDH',namedCurve:'P-256'},false,[]);
 var mine=await crypto.subtle.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);
 var shared=await crypto.subtle.deriveBits({name:'ECDH',public:wp},mine.privateKey,256);
 var aes=await crypto.subtle.importKey('raw',shared,{name:'AES-GCM'},false,['encrypt']);
 ready={aes:aes,pub:b64u(await crypto.subtle.exportKey('raw',mine.publicKey)),
  iv:crypto.getRandomValues(new Uint8Array(12)),aad:unb64u(CAP)};
}
document.getElementById('go').onclick=async function(){
 var out=document.getElementById('out');
 if(!ready){out.textContent='Still connecting. Wait a moment and try again.';return;}
 var body=JSON.stringify({type:document.getElementById('type').value,
  name:document.getElementById('name').value,base:document.getElementById('base').value,
  model:document.getElementById('model').value,key:document.getElementById('key').value});
 try{
  // A FRESH nonce per send. Reusing ready.iv meant a second Send (the normal recovery path after a
  // rejected key) encrypted two different plaintexts under the same key and nonce — the one thing
  // AES-GCM must never see, because it leaks the XOR of the plaintexts and enables forgery.
  var iv=crypto.getRandomValues(new Uint8Array(12));
  var ct=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:ready.aad},
   ready.aes,new TextEncoder().encode(body));
  var r=await fetch('/'+CAP+'/pair',{method:'POST',headers:{'Content-Type':'application/json'},
   body:JSON.stringify({v:1,t:b64u(ready.aad),pub:ready.pub,iv:b64u(iv),ct:b64u(ct)})});
  out.textContent=r.ok?'Sent. Finish on the watch.':'Rejected. Restart pairing on the watch.';
  if(r.ok){document.getElementById('key').value='';}
 }catch(e){out.textContent='Could not send. Restart pairing on the watch.';}
};
init().catch(function(e){
  // WebCrypto (crypto.subtle) only exists in a secure context: https, file, localhost, 127.0.0.1.
  // This page is served over plain HTTP from a LAN address, so window.isSecureContext is false and
  // crypto.subtle is undefined — verified in a real browser (origin http://192.168.8.2, isSecureContext
  // false, typeof crypto.subtle undefined). The old copy said "Handshake failed. Restart pairing",
  // which sent the user round a loop that could never succeed.
  var o=document.getElementById('out');
  if(!window.isSecureContext || typeof crypto==='undefined' || !crypto.subtle){
    o.textContent='This browser will not run crypto on a plain-HTTP LAN address, so the key cannot '+
      'be encrypted here. Enter the key on the watch instead (Cancel pairing below).';
  } else {
    o.textContent='Handshake failed. Restart pairing on the watch.';
  }
});
</script></body></html>
"""
