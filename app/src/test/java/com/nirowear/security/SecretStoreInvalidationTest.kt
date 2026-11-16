package com.nirowear.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator

/**
 * invalidateKey() must forget the key everywhere it lives: the in-memory handle AND the
 * Keystore entry. A corrupted entry (lock-screen change, restore) would otherwise fail every
 * future read the same way, and save() could never recreate from scratch.
 *
 * Runs on the JVM with a test-only JCA provider named "AndroidKeyStore": the real provider
 * only exists on device, but SecretStore looks it up by name, so a fake proves the
 * deleteEntry call happens. The store is instantiated without a Context (Unsafe skips the
 * constructor; the directory field is set directly) because the constructor only needs
 * noBackupFilesDir.
 */
class SecretStoreInvalidationTest {
    class FakeAndroidKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "test-only") {
        init {
            put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
        }

        class FakeKeyStoreSpi : KeyStoreSpi() {
            companion object {
                val entries = ConcurrentHashMap<String, Key>()
            }

            private fun norm(alias: String) = alias.lowercase()

            override fun engineGetKey(alias: String, password: CharArray?): Key? = entries[norm(alias)]

            override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

            override fun engineGetCertificate(alias: String): Certificate? = null

            override fun engineGetCreationDate(alias: String): Date? = null

            override fun engineSetKeyEntry(
                alias: String,
                key: Key,
                password: CharArray?,
                chain: Array<Certificate>?,
            ) {
                entries[norm(alias)] = key
            }

            override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) {
                throw UnsupportedOperationException("test-only")
            }

            override fun engineSetCertificateEntry(alias: String, cert: Certificate) {
                throw UnsupportedOperationException("test-only")
            }

            override fun engineDeleteEntry(alias: String) {
                entries.remove(norm(alias))
            }

            override fun engineAliases(): java.util.Enumeration<String> =
                java.util.Collections.enumeration(entries.keys)

            override fun engineContainsAlias(alias: String): Boolean = entries.containsKey(norm(alias))

            override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(norm(alias))

            override fun engineIsCertificateEntry(alias: String): Boolean = false

            override fun engineGetEntry(alias: String, prot: KeyStore.ProtectionParameter?): KeyStore.Entry? = null

            override fun engineGetCertificateAlias(cert: Certificate): String? = null

            override fun engineSetEntry(alias: String, entry: KeyStore.Entry, prot: KeyStore.ProtectionParameter?) {
                throw UnsupportedOperationException("test-only")
            }

            override fun engineEntryInstanceOf(alias: String, entryClass: Class<out KeyStore.Entry>): Boolean = false

            override fun engineSize(): Int = entries.size

            override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit

            override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit

            override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit

            override fun engineStore(param: KeyStore.LoadStoreParameter?) = Unit
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun install() {
            if (Security.getProvider("AndroidKeyStore") == null) {
                Security.addProvider(FakeAndroidKeyStoreProvider())
            }
            FakeAndroidKeyStoreProvider.FakeKeyStoreSpi.entries.clear()
        }

        private val unsafe: sun.misc.Unsafe by lazy {
            sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                as sun.misc.Unsafe
        }

        private fun field(name: String) =
            SecretStore::class.java.getDeclaredField(name).apply { isAccessible = true }

        private fun storeIn(dir: File): SecretStore {
            val store = unsafe.allocateInstance(SecretStore::class.java) as SecretStore
            field("directory").set(store, File(dir, "credentials"))
            field("keyAlias").set(store, SecretStore.KEY_ALIAS)
            return store
        }

        private fun invalidate(store: SecretStore) {
            SecretStore::class.java.getDeclaredMethod("invalidateKey").apply { isAccessible = true }.invoke(store)
        }
    }

    @Test
    fun invalidateKeyDeletesKeystoreEntryAndDropsCache() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val seeded = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        seeded.setKeyEntry(SecretStore.KEY_ALIAS, key, null, null)
        assertTrue(seeded.containsAlias(SecretStore.KEY_ALIAS))

        val secrets = storeIn(Files.createTempDirectory("nirotest").toFile())
        field("cachedKey").set(secrets, key)
        invalidate(secrets)

        assertNull("invalidateKey must drop the cached handle", field("cachedKey").get(secrets))
        val after = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(
            "invalidateKey must delete the Keystore entry, not just the cache",
            after.containsAlias(SecretStore.KEY_ALIAS),
        )
    }

    @Test
    fun entryCanBeRecreatedAfterInvalidation() {
        // save() on device recreates via AndroidKeyStore generateKey, which needs the framework.
        // This proves the store object still functions after an invalidation wiped the alias:
        // a fresh entry under the same alias is visible again.
        val secrets = storeIn(Files.createTempDirectory("nirotest").toFile())
        invalidate(secrets) // no entry present: must not throw
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(store.containsAlias(SecretStore.KEY_ALIAS))
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store.setKeyEntry(SecretStore.KEY_ALIAS, key, null, null)
        assertTrue(store.containsAlias(SecretStore.KEY_ALIAS))
    }
}
