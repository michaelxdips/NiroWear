package com.nirowear.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.nirowear.provider.SecretValue
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialUnavailable : Exception("Secure credential unavailable. Re-entry required.")

/** Only versioned AES-GCM nonce/ciphertext leaves Android Keystore. Never fail open. */
class SecretStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val directory = File(context.noBackupFilesDir, "credentials")

    private fun file(ref: String): AtomicFile {
        require(Regex("[a-f0-9-]{36}").matches(ref)) { "Invalid credential reference" }
        return AtomicFile(File(directory, ref))
    }

    @Synchronized
    fun save(
        ref: String,
        value: SecretValue,
    ) {
        // Encrypt first, in its own failure domain. Only a crypto failure here means the key
        // itself cannot do its job, and only that justifies replacing the key.
        //
        // Obtaining the key is NOT that failure domain. KeyStore.load and generateKey are Keystore IPC:
        // a daemon restart, a first access right after boot, or a transient provider error throws there
        // without saying anything about the existing key. Replacing the entry on such an error made
        // every OTHER profile's ciphertext permanently undecryptable (there is no plaintext fallback),
        // to save one new provider. Losing one save is recoverable; losing the shared key is not.
        val secret =
            try {
                key(create = true)
            } catch (_: Exception) {
                dropCachedKey()
                throw CredentialUnavailable()
            }
        val iv: ByteArray
        val encrypted: ByteArray
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.updateAAD(ref.toByteArray(Charsets.UTF_8))
            val plaintext = value.forRequest().toByteArray(Charsets.UTF_8)
            encrypted =
                try {
                    cipher.doFinal(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            iv = cipher.iv
            check(iv.size == 12)
        } catch (_: InvalidKeyException) {
            // The one proof that this key can no longer encrypt (permanently invalidated by a
            // lock-screen change, restored from another device, wrong purpose or size).
            invalidateKey()
            throw CredentialUnavailable()
        } catch (_: Exception) {
            // Anything else is a transient encryption failure: keep the entry so sibling profiles stay
            // readable, and only drop the cached handle.
            dropCachedKey()
            throw CredentialUnavailable()
        }
        CredentialPromotionProbe.reached("CANDIDATE_ENCRYPTED")
        // Filesystem failure is NOT a key failure. This used to sit inside the same catch-all, so a
        // full disk or a failed mkdir destroyed the shared Keystore entry and every other profile's
        // ciphertext became permanently undecryptable. Losing one save is recoverable; losing every
        // provider's key is not.
        try {
            check(directory.isDirectory || directory.mkdirs())
            val target = file(ref)
            val output = target.startWrite()
            try {
                output.write(byteArrayOf(1))
                output.write(iv)
                CredentialPromotionProbe.reached("CANDIDATE_WRITING")
                output.write(encrypted)
                output.fd.sync()
                target.finishWrite(output)
            } catch (error: Exception) {
                target.failWrite(output)
                throw error
            }
        } catch (_: Exception) {
            throw CredentialUnavailable()
        }
    }

    @Synchronized
    fun read(ref: String): SecretValue {
        // A missing payload (rotated-away or deleted ref) says nothing about the key: an
        // in-flight reader holding a stale ref must not nuke the live Keystore entry that
        // siblings still decrypt with.
        if (!file(ref).baseFile.exists()) throw CredentialUnavailable()
        try {
            val payload = file(ref).readFully()
            require(payload.size in 30..16384 && payload[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
            cipher.updateAAD(ref.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(payload.copyOfRange(13, payload.size))
            return try {
                SecretValue(plaintext.toString(Charsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            // ONE payload failing (truncated, bit-flipped, restored from another device, or a
            // different format version) says nothing about the key. Deleting the shared entry here
            // would make every OTHER profile undecryptable — permanent, since there is no plaintext
            // fallback. Drop the cached handle so the next read re-loads it, and report this ref as
            // unavailable; re-entry of that one key fixes it.
            dropCachedKey()
            throw CredentialUnavailable()
        }
    }

    @Synchronized
    fun delete(ref: String) {
        file(ref).delete()
    }

    /** Caller serializes against profile saves; keep referenced base, discard stale sidecars/orphans. */
    @Synchronized
    fun cleanup(referenced: Set<String>) {
        directory.listFiles()?.forEach { payload ->
            val ref = payload.name.removeSuffix(".new").removeSuffix(".bak")
            if (!Regex("[a-f0-9-]{36}").matches(ref)) return@forEach
            if (ref !in referenced) {
                file(ref).delete()
            } else if (payload.name.endsWith(".new")) {
                check(!payload.exists() || payload.delete())
            }
        }
    }

    // The SecretKey is a Keystore-held handle, not plaintext, so it is safe to cache in memory.
    // Loading the KeyStore and fetching the entry on every request is IPC; this keeps that cost to
    // once per process. The cache is invalidated on failure and recreated on demand, so a key that
    // becomes unavailable still surfaces as CredentialUnavailable rather than a stale reference.
    @Volatile private var cachedKey: SecretKey? = null

    private fun key(create: Boolean): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(keyAlias, null) as? SecretKey)?.let {
                cachedKey = it
                return it
            }
            if (!create) throw CredentialUnavailable()
            return KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            ).setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                }.generateKey()
                .also { cachedKey = it }
        }
    }

    /** Drop the cached handle only. The Keystore entry stays: it may still protect other payloads. */
    private fun dropCachedKey() {
        synchronized(this) {
            cachedKey = null
        }
    }

    /** Drop the cached handle AND the Keystore entry. Only for a key that cannot encrypt at all. */
    private fun invalidateKey() {
        synchronized(this) {
            cachedKey = null
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            }
        }
    }

    companion object {
        const val KEY_ALIAS = "nirowear_credentials_v1"
    }
}
