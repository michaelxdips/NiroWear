package com.nirowear.security

import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.NiroApplication
import com.nirowear.provider.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CredentialPromotionCrashTest {
    @com.nirowear.HostDrivenGate
    @Test fun promotionRestartProbe() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val args = InstrumentationRegistry.getArguments()
            val point = args.getString("promotionPoint")
            assumeTrue(
                point in
                    setOf(
                        "CANDIDATE_ENCRYPTED",
                        "CANDIDATE_WRITING",
                        "CANDIDATE_WRITTEN",
                        "CANDIDATE_VALIDATED",
                        "REFERENCE_PROMOTED",
                        "OLD_PAYLOAD_CLEANED",
                    ),
            )
            val app = instrumentation.targetContext.applicationContext as NiroApplication
            val id = "55555555-5555-5555-5555-555555555555"
            val probe = File(app.noBackupFilesDir, "promotion-crash-probe")
            val marker = File(app.noBackupFilesDir, "promotion-validation-probe")
            val profile =
                ProviderProfile(
                    id,
                    ProviderType.OPENAI,
                    "Promotion crash fixture",
                    "https://api.openai.com/v1",
                    "fixture-model",
                    id,
                )
            if (args.getString("promotionMode") == "seed") {
                app.settings.state
                    .first()
                    .profiles
                    .find { it.id == id }
                    ?.let { app.settings.delete(id) }
                probe.delete()
                marker.delete()
                app.settings.save(profile, SecretValue("promotion-controlled-old"))
                val old =
                    app.settings.state
                        .first()
                        .profiles
                        .single { it.id == id }
                        .credentialRef
                CredentialPromotionProbe.observer = { state ->
                    if (state == point) {
                        assertEquals(
                            state in setOf("CANDIDATE_VALIDATED", "REFERENCE_PROMOTED", "OLD_PAYLOAD_CLEANED"),
                            marker.exists(),
                        )
                        probe.writeText(old)
                        instrumentation.uiAutomation.executeShellCommand("am force-stop com.nirowear").close()
                        Thread.sleep(30000)
                        error("Force-stop failed")
                    }
                }
                try {
                    app.settings.save(profile, SecretValue("promotion-controlled-new")) { _, candidate ->
                        assertEquals("promotion-controlled-new", candidate.forRequest())
                        marker.writeText("validated")
                        null
                    }
                    error("Expected process death")
                } finally {
                    CredentialPromotionProbe.observer = null
                }
            } else {
                assertTrue(probe.exists())
                val old = probe.readText()
                app.settings.recover()
                val active =
                    app.settings.state
                        .first()
                        .profiles
                        .single { it.id == id }
                val promoted = point in setOf("REFERENCE_PROMOTED", "OLD_PAYLOAD_CLEANED")
                assertEquals(promoted, active.credentialRef != old)
                assertEquals(
                    if (promoted) "promotion-controlled-new" else "promotion-controlled-old",
                    app.secrets.read(active.credentialRef).forRequest(),
                )
                assertEquals(
                    point in setOf("CANDIDATE_VALIDATED", "REFERENCE_PROMOTED", "OLD_PAYLOAD_CLEANED"),
                    marker.exists(),
                )
                val blobs =
                    File(app.noBackupFilesDir, "credentials").listFiles()!!.filter {
                        it.name == active.credentialRef ||
                            it.name.startsWith(active.credentialRef + ".")
                    }
                assertEquals(listOf(active.credentialRef), blobs.map { it.name })
                blobs.forEach { payload ->
                    assertFalse(payload.readBytes().toString(Charsets.ISO_8859_1).contains("promotion-controlled-"))
                    assertEquals(1.toByte(), payload.readBytes().first())
                }
                app.settings.state
                    .first()
                    .profiles
                    .forEach { app.secrets.read(it.credentialRef) }
                assertEquals(
                    app.settings.state
                        .first()
                        .profiles
                        .map { it.credentialRef }
                        .toSet(),
                    File(app.noBackupFilesDir, "credentials").listFiles()!!.map { it.name }.toSet(),
                )
                app.settings.delete(id)
                check(probe.delete())
                marker.delete()
            }
            Unit
        }
}
