package com.nirowear.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.NiroApplication
import com.nirowear.data.ProviderSettings
import com.nirowear.provider.*
import com.nirowear.security.CredentialPromotionProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SettingsRecoveryTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NiroApplication

    @Test fun unreadableNewCandidateNeverPromotesProfileReference() =
        runBlocking {
            val profile =
                ProviderProfile(
                    UUID.randomUUID().toString(),
                    ProviderType.OPENAI_COMPATIBLE,
                    "Unreadable candidate",
                    "https://example.invalid/v1",
                    "test",
                    UUID.randomUUID().toString(),
                )
            val before = app.settings.state.first()
            val directory = java.io.File(app.noBackupFilesDir, "credentials")
            val oldFiles =
                directory
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .toSet()
            try {
                CredentialPromotionProbe.observer = { stage ->
                    if (stage == "CANDIDATE_WRITTEN") {
                        directory
                            .listFiles()
                            .orEmpty()
                            .filter { it.name !in oldFiles }
                            .forEach { it.writeBytes(byteArrayOf(0)) }
                    }
                }
                assertTrue(
                    "Initial save must verify committed ciphertext before promotion",
                    runCatching { app.settings.save(profile, SecretValue("synthetic-unreadable-not-real")) }.isFailure,
                )
                assertEquals(before, app.settings.state.first())
                assertTrue(directory.listFiles().orEmpty().all { it.name in oldFiles })
            } finally {
                CredentialPromotionProbe.observer = null
                app.settings.delete(profile.id)
                app.settings.recover()
            }
        }

    @Test fun corruptProfilesFailClosedWithoutDeletingCredentialsOrBlockingHistory() =
        runBlocking {
            val profile =
                ProviderProfile(
                    UUID.randomUUID().toString(),
                    ProviderType.OPENAI_COMPATIBLE,
                    "Recovery test",
                    "https://example.invalid/v1",
                    "test",
                    UUID.randomUUID().toString(),
                )
            app.settings.save(profile, SecretValue("controlled-recovery-not-real"))
            val saved =
                app.settings.state
                    .first()
                    .profiles
                    .first { it.id == profile.id }

            @Suppress("UNCHECKED_CAST")
            val store =
                ProviderSettings::class.java
                    .getDeclaredField("store")
                    .apply {
                        isAccessible = true
                    }.get(app.settings) as DataStore<Preferences>
            val key = stringPreferencesKey("profiles_v1")
            val original = store.data.first()[key]!!
            try {
                // One case per row, with the outcome the code ACTUALLY guarantees. The old loop
                // asserted a single outcome (error != null, empty profiles, credential readable) for
                // all five, which only ever matched the pre-0.1.2 all-or-nothing decode.
                //
                //   brokenEnvelope  — the document itself does not parse: fail closed, no writes,
                //                     and cleanup is skipped so the ciphertext survives.
                //   unknownType     — one undecodable ENTRY is dropped; settings stay usable, and a
                //                     ref still named in the document keeps its ciphertext.
                //   refDestroyed    — the corruption removes the reference itself. The ciphertext is
                //                     then orphaned and SecretStore.cleanup deletes it by design:
                //                     nothing can re-derive a lost ref and there is no plaintext
                //                     fallback, so re-entry is the honest outcome.
                class Case(val corrupt: String, val envelopeBroken: Boolean, val refDestroyed: Boolean)

                val cases =
                    listOf(
                        Case("{not json", envelopeBroken = true, refDestroyed = false),
                        Case(
                            original.replace("OPENAI_COMPATIBLE", "UNKNOWN_PROVIDER"),
                            envelopeBroken = false,
                            refDestroyed = false,
                        ),
                        Case(
                            original.replace("\"${saved.credentialRef}\"", "null"),
                            envelopeBroken = false,
                            refDestroyed = true,
                        ),
                        Case(original.replace(saved.credentialRef, ""), envelopeBroken = false, refDestroyed = true),
                        Case(
                            original.replace(saved.credentialRef, "invalid-ref"),
                            envelopeBroken = false,
                            refDestroyed = true,
                        ),
                    )
                for (case in cases) {
                    store.edit { it[key] = case.corrupt }
                    val state = app.settings.state.first()
                    if (case.envelopeBroken) {
                        assertNotNull("broken envelope must fail closed", state.error)
                        assertTrue(state.profiles.isEmpty())
                        assertTrue(runCatching { app.settings.save(profile, SecretValue("not-promoted")) }.isFailure)
                    } else {
                        // A dropped entry must not block the rest of the settings screen.
                        assertNull("one undecodable entry must not block settings", state.error)
                    }
                    app.settings.recover()
                    if (case.refDestroyed) {
                        assertTrue(
                            "an orphaned ref means re-entry, not a crash",
                            runCatching { app.secrets.read(saved.credentialRef) }.exceptionOrNull() is
                                com.nirowear.security.CredentialUnavailable,
                        )
                    } else {
                        assertEquals(
                            "a ref the document still names must keep its ciphertext",
                            "controlled-recovery-not-real",
                            app.secrets.read(saved.credentialRef).forRequest(),
                        )
                    }
                    // Reading must not rewrite what was stored; only an explicit save may repair it.
                    assertEquals(case.corrupt, store.data.first()[key])
                    app.database
                        .chats()
                        .conversations()
                        .first() // History remains readable independently.
                }
            } finally {
                store.edit { it[key] = original }
                app.settings.delete(profile.id)
            }
        }

    @Test fun postPromotionCleanupFailureReportsSavedAndKeepsNewReference() =
        runBlocking {
            val profile =
                ProviderProfile(
                    UUID.randomUUID().toString(),
                    ProviderType.OPENAI_COMPATIBLE,
                    "Promotion test",
                    "https://example.invalid/v1",
                    "test",
                    UUID.randomUUID().toString(),
                )
            try {
                app.settings.save(profile, SecretValue("controlled-old-not-real"))
                val old =
                    app.settings.state
                        .first()
                        .profiles
                        .first { it.id == profile.id }
                        .credentialRef
                CredentialPromotionProbe.observer =
                    { if (it == "REFERENCE_PROMOTED") error("Controlled cleanup failure") }
                assertFalse(app.settings.save(profile, SecretValue("controlled-new-not-real")) { _, _ -> null })
                val saved =
                    app.settings.state
                        .first()
                        .profiles
                        .first { it.id == profile.id }
                assertNotEquals(old, saved.credentialRef)
                assertEquals("controlled-new-not-real", app.secrets.read(saved.credentialRef).forRequest())
                CredentialPromotionProbe.observer = null
                app.settings.recover()
                assertTrue(runCatching { app.secrets.read(old) }.isFailure)
            } finally {
                CredentialPromotionProbe.observer = null
                app.settings.delete(profile.id)
            }
        }
}
