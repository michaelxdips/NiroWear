package com.nirowear.security

import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.NiroApplication
import com.nirowear.data.*
import com.nirowear.provider.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run seed, force-stop app process, then verify in a separate instrumentation invocation. */
class ByokPersistenceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NiroApplication
    private val id = "11111111-1111-1111-1111-111111111111"
    private val marker = "nirowear-controlled-test-credential-not-real"
    private val nativeIds = (0..2).map { "22222222-2222-2222-2222-22222222222$it" }

    /**
     * Suite-visible leak, measured: `nativeRestartProbe` saves three profiles and deletes them at the
     * END of the test body. A failed assertion skips that delete, so the profiles survived into every
     * later class in the same app process — `ProviderSetupUiTest` then opened a setup screen that was
     * already pointing at `persisted-OPENAI`, and `ProviderValidationRaceTest` found three providers
     * where its contract requires none. Cleanup belongs in a lifecycle callback that runs after a
     * failure too.
     */
    @org.junit.After
    fun removeSuitesOwnProfiles() =
        runBlocking {
            // Never touch state a HOST-driven seed run is deliberately leaving behind.
            if (InstrumentationRegistry.getArguments().getString("restartMode") == "seed") return@runBlocking
            val created = (nativeIds + id).toSet()
            app.settings.state
                .first()
                .profiles
                .filter { it.id in created }
                .forEach { app.settings.delete(it.id) }
            app.database.chats().deleteConversation("native-restart-interrupted")
        }

    @Test fun restartProbe() =
        runBlocking {
            val mode = InstrumentationRegistry.getArguments().getString("restartMode")
            if (mode != "verify") {
                val profile =
                    ProviderProfile(
                        id,
                        ProviderType.OPENAI_COMPATIBLE,
                        "Restart test",
                        "https://example.com/v1",
                        "manual-model",
                        id,
                    )
                app.settings.save(profile, SecretValue(marker)) { _, _ -> null }
            }
            val selected =
                app.settings.state
                    .first()
                    .selected!!
            assertEquals(id, selected.id)
            assertEquals("manual-model", selected.selectedModelId)
            assertEquals(marker, app.secrets.read(selected.credentialRef).forRequest())
            inspectNoPlaintext(marker)
            if (mode != "seed") app.settings.delete(id)
        }

    @Test fun nativeRestartProbe() =
        runBlocking {
            val mode = InstrumentationRegistry.getArguments().getString("restartMode")
            val types = listOf(ProviderType.OPENAI, ProviderType.GEMINI, ProviderType.ANTHROPIC)
            val ids = nativeIds
            val conversation = "native-restart-interrupted"
            // Saving must never retarget the next send (ProviderSettings.save). Captured before the
            // first save so the assertion below holds whether or not this run started with nothing
            // configured — the suite shares one app process, so "nothing selected" is not a given.
            val selectedBefore = app.settings.state.first().selectedId
            if (mode != "verify") {
                types.forEachIndexed { i, type ->
                    app.settings.save(
                        ProviderProfile(
                            ids[i],
                            type,
                            "Restart $type",
                            if (type ==
                                ProviderType.GEMINI
                            ) {
                                "https://generativelanguage.googleapis.com/v1beta"
                            } else if (type ==
                                ProviderType.OPENAI
                            ) {
                                "https://api.openai.com/v1"
                            } else {
                                "https://api.anthropic.com/v1"
                            },
                            "persisted-$type",
                            ids[i],
                        ),
                        SecretValue("native-restart-controlled-$type"),
                    ) { _, _ -> null }
                }
                val dao = app.database.chats()
                dao.deleteConversation(conversation)
                dao.insertConversation(Conversation(conversation, "Interrupted restart", 0))
                dao.insertMessage(
                    StoredMessage(conversationId = conversation, role = "USER", rawContent = "Keep partial"),
                )
                dao.insertMessage(
                    StoredMessage(
                        conversationId = conversation,
                        role = "ASSISTANT",
                        rawContent = "Durable native partial",
                        status = "STREAMING",
                        providerProfileId = ids[0],
                        modelId = "persisted-OPENAI",
                    ),
                )
            }
            app.settings.recover()
            val settings = app.settings.state.first()
            types.forEachIndexed { i, type ->
                val p = settings.profiles.single { it.id == ids[i] }
                assertEquals(type, p.type)
                assertEquals("persisted-$type", p.selectedModelId)
                assertEquals("native-restart-controlled-$type", app.secrets.read(p.credentialRef).forRequest())
                inspectNoPlaintext("native-restart-controlled-$type")
            }
            // The FIRST save becomes the selection only when no selection exists yet; saving two
            // more must NOT re-target the next send. Asserting `ids.last()` pinned the pre-fix
            // behaviour, where every save moved the selection (measured actual: ids[0]).
            assertEquals(selectedBefore ?: ids.first(), settings.selectedId)
            if (mode != "seed") {
                app.database.chats().recoverInterrupted()
                val partial =
                    app.database
                        .chats()
                        .snapshot(conversation)
                        .last()
                assertEquals("Durable native partial", partial.rawContent)
                assertEquals("STOPPED", partial.status)
                assertEquals(ids[0], partial.providerProfileId)
                ids.forEach { app.settings.delete(it) }
                app.database.chats().deleteConversation(conversation)
            }
        }

    @Test fun rotationCorruptionDeletionAndLinearEdit() =
        runBlocking {
            val unique =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val profile =
                ProviderProfile(
                    unique,
                    ProviderType.OPENAI_COMPATIBLE,
                    "Security test",
                    "https://example.com/v1",
                    "model-a",
                    unique,
                )
            app.settings.save(profile, SecretValue(marker))
            val original =
                app.settings.state
                    .first()
                    .profiles
                    .single { it.id == unique }
            app.settings.save(original, SecretValue("rotated-controlled-test-key")) { _, _ -> null }
            val rotated =
                app.settings.state
                    .first()
                    .profiles
                    .single { it.id == unique }
            assertNotEquals(original.credentialRef, rotated.credentialRef)
            assertTrue(
                runCatching { app.secrets.read(original.credentialRef) }.exceptionOrNull() is CredentialUnavailable,
            )
            assertEquals("rotated-controlled-test-key", app.secrets.read(rotated.credentialRef).forRequest())
            inspectNoPlaintext("rotated-controlled-test-key")
            val payload = File(app.noBackupFilesDir, "credentials/${rotated.credentialRef}")
            payload.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(
                runCatching { app.secrets.read(rotated.credentialRef) }.exceptionOrNull() is CredentialUnavailable,
            )
            val dao = app.database.chats()
            dao.insertConversation(Conversation(unique, "Test history", 0))
            val userId =
                dao.insertMessage(
                    StoredMessage(conversationId = unique, role = "USER", rawContent = "original"),
                )
            dao.insertMessage(
                StoredMessage(
                    conversationId = unique,
                    role = "ASSISTANT",
                    rawContent = "old answer",
                    providerProfileId = unique,
                    modelId = "model-a",
                ),
            )
            dao.edit(unique, userId, "edited")
            assertEquals(listOf("edited"), dao.snapshot(unique).map { it.rawContent })
            dao.insertMessage(
                StoredMessage(
                    conversationId = unique,
                    role = "ASSISTANT",
                    rawContent = "history survives",
                    providerProfileId = unique,
                    modelId = "model-a",
                ),
            )
            app.settings.delete(unique)
            assertFalse(payload.exists())
            assertNull(
                app.settings.state
                    .first()
                    .selected,
            )
            assertEquals("history survives", dao.snapshot(unique).last().rawContent)
            dao.deleteConversation(unique)
        }

    private fun inspectNoPlaintext(secret: String) {
        val roots =
            listOf(
                app.filesDir,
                app.noBackupFilesDir,
                File(app.applicationInfo.dataDir, "databases"),
                File(app.applicationInfo.dataDir, "shared_prefs"),
            )
        roots.filter { it.exists() }.flatMap { it.walkTopDown().filter(File::isFile).toList() }.forEach { file ->
            assertFalse(
                "Plaintext credential in ${file.name}",
                file.readBytes().toString(Charsets.ISO_8859_1).contains(secret),
            )
        }
    }

    @Test fun missingKeystoreKeyRequiresReentry() {
        val alias = "nirowear_test_${java.util.UUID.randomUUID()}"
        val ref =
            java.util.UUID
                .randomUUID()
                .toString()
        val store = SecretStore(app, alias)
        try {
            store.save(ref, SecretValue(marker))
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
            assertTrue(runCatching { store.read(ref) }.exceptionOrNull() is CredentialUnavailable)
            inspectNoPlaintext(marker)
        } finally {
            store.delete(ref)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    @Test fun oneCorruptPayloadDoesNotDestroyTheSharedKey() {
        // The regression this pins: read() used to call invalidateKey(), which deletes the single
        // Keystore entry every profile is encrypted under. One corrupt file therefore made every
        // OTHER credential permanently undecryptable, and there is no plaintext fallback.
        val alias = "nirowear_test_${java.util.UUID.randomUUID()}"
        val good = java.util.UUID.randomUUID().toString()
        val corrupt = java.util.UUID.randomUUID().toString()
        val store = SecretStore(app, alias)
        val dir = java.io.File(app.noBackupFilesDir, "credentials")
        try {
            store.save(good, SecretValue(marker))
            store.save(corrupt, SecretValue(marker))
            // Damage exactly one payload: flip a byte inside its ciphertext.
            val victim = java.io.File(dir, corrupt)
            val bytes = victim.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0xFF).toByte()
            victim.writeBytes(bytes)

            assertTrue(
                "the corrupt ref must report re-entry, not a crash",
                runCatching { store.read(corrupt) }.exceptionOrNull() is CredentialUnavailable,
            )
            assertEquals(
                "the sibling credential must still decrypt: one bad payload is not a dead key",
                marker,
                store.read(good).forRequest(),
            )
        } finally {
            store.delete(good)
            store.delete(corrupt)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    @Test fun saveFailureDropsInvalidCachedHandleBeforeExplicitReentry() {
        val alias = "nirowear_test_${java.util.UUID.randomUUID()}"
        val ref =
            java.util.UUID
                .randomUUID()
                .toString()
        val store = SecretStore(app, alias)
        try {
            store.save(ref, SecretValue(marker))
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
            assertTrue(runCatching { store.save(ref, SecretValue(marker)) }.exceptionOrNull() is CredentialUnavailable)
            val cache = SecretStore::class.java.getDeclaredField("cachedKey").apply { isAccessible = true }
            assertNull("Failed encryption must invalidate stale handle", cache.get(store))
            store.save(ref, SecretValue(marker))
            assertEquals(marker, store.read(ref).forRequest())
        } finally {
            store.delete(ref)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    @Test fun rejectedCancelledRotationAndCrashOrphansKeepReferencedSecret() =
        runBlocking {
            val id =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val profile =
                ProviderProfile(id, ProviderType.OPENAI_COMPATIBLE, "Rotation", "https://example.com/v1", "manual", id)
            try {
                app.settings.save(profile, SecretValue(marker))
                val old =
                    app.settings.state
                        .first()
                        .selected!!
                assertTrue(
                    runCatching {
                        app.settings.save(
                            old,
                            SecretValue("rejected-candidate"),
                        ) { _, _ -> ProviderError.INVALID_CREDENTIAL }
                    }.exceptionOrNull() is SafeProviderException,
                )
                assertEquals(
                    old,
                    app.settings.state
                        .first()
                        .selected,
                )
                assertEquals(marker, app.secrets.read(old.credentialRef).forRequest())
                assertTrue(
                    runCatching {
                        app.settings.save(
                            old,
                            SecretValue("cancelled-candidate"),
                        ) { _, _ -> throw kotlinx.coroutines.CancellationException() }
                    }.exceptionOrNull() is kotlinx.coroutines.CancellationException,
                )
                val orphan =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                app.secrets.save(orphan, SecretValue("unpromoted-candidate"))
                val dir = File(app.noBackupFilesDir, "credentials")
                File(dir, "${old.credentialRef}.new").writeBytes(byteArrayOf(1, 2))
                File(dir, "$orphan.new").writeBytes(byteArrayOf(1, 2))
                app.settings.recover()
                assertFalse(File(dir, orphan).exists())
                assertFalse(File(dir, "$orphan.new").exists())
                assertFalse(File(dir, "${old.credentialRef}.new").exists())
                assertEquals(marker, app.secrets.read(old.credentialRef).forRequest())
                val interrupted =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                File(dir, "$interrupted.new").writeBytes(byteArrayOf(1, 2))
                assertTrue(runCatching { app.secrets.read(interrupted) }.exceptionOrNull() is CredentialUnavailable)
            } finally {
                app.settings.delete(id)
            }
        }
}
