package com.nirowear.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.nirowear.provider.SecretValue
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialUnavailable : Exception("Secure credential unavailable. Re-entry required.")

/** Only versioned AES-GCM nonce/ciphertext leaves Android Keystore. Never fail open. */
class SecretStore(context: Context, private val keyAlias: String = KEY_ALIAS) {
    private val directory = File(context.noBackupFilesDir, "credentials")
    private fun file(ref: String): AtomicFile {
        require(Regex("[a-f0-9-]{36}").matches(ref)) { "Invalid credential reference" }
        return AtomicFile(File(directory, ref))
    }

    @Synchronized
    fun save(ref: String, value: SecretValue) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(ref.toByteArray(Charsets.UTF_8))
            val plaintext = value.forRequest().toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
            CredentialPromotionProbe.reached("CANDIDATE_ENCRYPTED")
            check(cipher.iv.size == 12)
            check(directory.isDirectory || directory.mkdirs())
            val target = file(ref)
            val output = target.startWrite()
            try {
                output.write(byteArrayOf(1))
                output.write(cipher.iv)
                CredentialPromotionProbe.reached("CANDIDATE_WRITING")
                output.write(encrypted)
                output.fd.sync()
                target.finishWrite(output)
            } catch (error: Exception) {
                target.failWrite(output)
                throw error
            }
        } catch (_: Exception) { invalidateKey(); throw CredentialUnavailable() }
    }

    @Synchronized
    fun read(ref: String): SecretValue {
        try {
            val payload = file(ref).readFully()
            require(payload.size in 30..16384 && payload[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
            cipher.updateAAD(ref.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(payload.copyOfRange(13, payload.size))
            return try { SecretValue(plaintext.toString(Charsets.UTF_8)) } finally { plaintext.fill(0) }
        } catch (_: Exception) {
            // A cached handle can go stale if the key was invalidated (e.g. lock-screen change or
            // device reset). Drop it so the next read re-loads and surfaces CredentialUnavailable,
            // rather than repeatedly failing against a dead reference.
            invalidateKey()
            throw CredentialUnavailable()
        }
    }

    @Synchronized
    fun delete(ref: String) { file(ref).delete() }

    /** Caller serializes against profile saves; keep referenced base, discard stale sidecars/orphans. */
    @Synchronized
    fun cleanup(referenced: Set<String>) {
        directory.listFiles()?.forEach { payload ->
            val ref = payload.name.removeSuffix(".new").removeSuffix(".bak")
            if (!Regex("[a-f0-9-]{36}").matches(ref)) return@forEach
            if (ref !in referenced) file(ref).delete()
            else if (payload.name.endsWith(".new")) check(!payload.exists() || payload.delete())
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
            (store.getKey(keyAlias, null) as? SecretKey)?.let { cachedKey = it; return it }
            if (!create) throw CredentialUnavailable()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey().also { cachedKey = it }
        }
    }

    /** Drop the cached handle so the next read re-loads from the KeyStore. Called on crypto failure. */
    private fun invalidateKey() { synchronized(this) { cachedKey = null } }

    companion object { const val KEY_ALIAS = "nirowear_credentials_v1" }
}
