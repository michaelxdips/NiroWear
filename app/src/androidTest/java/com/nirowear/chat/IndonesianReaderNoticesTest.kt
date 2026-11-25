package com.nirowear.chat

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.FailureToken
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SecretValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * N4: Verifies all reader notices in Indonesian (`in-ID`) — truncated output, stopped partial
 * status + retry button, and the complete unsaved-save recovery flow (localized unsaved banner,
 * sensitive Copy action, repeated save-failure notice without HTTP replay, and final Retry saving
 * persistence).
 */
@RunWith(AndroidJUnit4::class)
class IndonesianReaderNoticesTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app get() = i.targetContext.applicationContext as NiroApplication

    private fun norm(s: CharSequence?): String =
        s
            ?.toString()
            .orEmpty()
            .replace("\u2068", "")
            .replace("\u2069", "")
            .replace("\u200E", "")
            .replace("\u200F", "")
            .trim()

    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    private fun scan(): List<AccessibilityNodeInfo> {
        i.uiAutomation.clearCache()
        val roots = i.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(i.uiAutomation.rootInActiveWindow)
        return roots.flatMap { nodes(it) }
    }

    private fun awaitNode(
        what: String,
        timeoutMs: Long = 10_000,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            scan().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $what")
    }

    private fun clickableOwner(start: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = start
        while (cur != null && !cur.isClickable) cur = cur.parent
        return cur ?: error("No clickable owner for ${start.text ?: start.contentDescription}")
    }

    private fun shell(cmd: String): String =
        i.uiAutomation.executeShellCommand(cmd).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }

    private fun scrollDownSmall() {
        val moved =
            scan()
                .firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        if (!moved) shell("input swipe 233 260 233 120 350")
        SystemClock.sleep(300)
    }

    private fun scrollUpSmall() {
        val moved =
            scan()
                .firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        if (!moved) shell("input swipe 233 140 233 320 350")
        SystemClock.sleep(300)
    }

    private fun visibleTexts(): List<String> = scan().mapNotNull { norm(it.text).takeIf { t -> t.isNotBlank() } }

    private fun scrollToText(
        needle: String,
        maxSteps: Int = 12,
        exact: Boolean = false,
    ): String? {
        repeat(maxSteps) {
            visibleTexts().firstOrNull { if (exact) it == needle else it.contains(needle) }?.let { return it }
            scrollDownSmall()
        }
        return visibleTexts().firstOrNull { if (exact) it == needle else it.contains(needle) }
    }

    private fun collectReaderTextsAfterScrolling(downward: Boolean): List<String> {
        val seen = linkedSetOf<String>()
        repeat(8) {
            seen += visibleTexts()
            if (downward) scrollDownSmall() else scrollUpSmall()
        }
        seen += visibleTexts()
        return seen.toList()
    }

    private fun saveScreenshot(name: String) {
        val dir = app.getExternalFilesDir(null) ?: return
        dir.mkdirs()
        val png = File(dir, "$name.png")
        shell("screencap -p ${png.absolutePath}")
    }

    @Test fun inIdReaderNoticesAndUnsavedRecoveryAreLocalized() =
        runBlocking {
            val previous = app.settings.state.first().selectedId
            val dao = app.database.chats()
            val token = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val stopId = "n4-stop-${UUID.randomUUID().toString().take(8)}"
            val truncId = "n4-trunc-${UUID.randomUUID().toString().take(8)}"

            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Respons n4 belum tersimpan.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                )
                server.start()
                var sentConvId: String? = null
                try {
                    // 1. Verify STOPPED in-ID notice + Ulangi jawaban on stopped fixture (most recent).
                    dao.insertConversation(Conversation(stopId, "N4 Stopped", now + 20_000L))
                    dao.insertMessage(StoredMessage(conversationId = stopId, role = "USER", rawContent = "Tanya stop"))
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = stopId,
                            role = "ASSISTANT",
                            rawContent = "Sebagian teks",
                            status = "STOPPED",
                        ),
                    )
                    val stopActivity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        ) as MainActivity
                    try {
                        awaitNode("N4 stopped reader") { norm(it.text).contains("Tanya stop") }
                        val stoppedBanner = scrollToText("Dihentikan")
                        assertEquals("Dihentikan · jawaban sebagian disimpan", stoppedBanner)
                        val retrySeen =
                            scrollToText("Ulangi jawaban") != null ||
                                scan().any { norm(it.contentDescription).contains("Ulangi jawaban") }
                        assertTrue("Expected localized 'Ulangi jawaban' row", retrySeen)
                    } finally {
                        i.runOnMainSync { stopActivity.finish() }
                    }

                    // 2. Verify TRUNCATED in-ID notice.
                    dao.insertConversation(Conversation(truncId, "N4 Trunc", now + 40_000L))
                    dao.insertMessage(StoredMessage(conversationId = truncId, role = "USER", rawContent = "Tanya trunc"))
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = truncId,
                            role = "ASSISTANT",
                            rawContent = "Potongan output",
                            status = "ERROR",
                            error = FailureToken.TRUNCATED,
                        ),
                    )
                    val truncActivity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        ) as MainActivity
                    try {
                        awaitNode("N4 trunc reader") { norm(it.text).contains("Tanya trunc") }
                        val truncBanner = scrollToText("Jawaban berhenti lebih awal")
                        assertEquals("Jawaban berhenti lebih awal dan mungkin tidak lengkap.", truncBanner)
                    } finally {
                        i.runOnMainSync { truncActivity.finish() }
                    }

                    // 3. Verify live unsaved-save recovery flow and actual banner text in in-ID.
                    app.settings.save(
                        ProviderProfile(
                            token,
                            ProviderType.OPENAI_COMPATIBLE,
                            "IndoMock",
                            server.url("/v1").toString(),
                            "mock-id",
                            token,
                        ),
                        SecretValue("controlled-not-real"),
                    )
                    val activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        ) as MainActivity
                    try {
                        clickableOwner(awaitNode("Menu") { norm(it.contentDescription) == "Menu" })
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickableOwner(awaitNode("Chat baru") { norm(it.text) == "Chat baru" })
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickableOwner(awaitNode("Input keyboard") { norm(it.contentDescription) == "Input keyboard" })
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        val editor = awaitNode("Kolom pesan") { it.isEditable || norm(it.contentDescription) == "Kolom pesan" }
                        assertTrue(
                            editor.performAction(
                                AccessibilityNodeInfo.ACTION_SET_TEXT,
                                Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Tanya unsaved n4")
                                },
                            ),
                        )
                        awaitNode("Typed prompt") { norm(it.text).contains("Tanya unsaved n4") }
                        repeat(3) {
                            if (scan().any { n -> norm(n.text) == "Kirim" }) return@repeat
                            shell("input keyevent 4")
                            SystemClock.sleep(350)
                        }

                        ChatStorageProbe.beforeFinish = { error("Controlled N4 save failure") }
                        clickableOwner(awaitNode("Kirim") { norm(it.text) == "Kirim" })
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        awaitNode("Unsaved banner or copy button", timeoutMs = 15_000) {
                            val t = norm(it.text)
                            t.contains("Jawaban belum tersimpan.") ||
                                t.contains("Answer not saved.") ||
                                t == "Salin jawaban belum tersimpan"
                        }
                        assertTrue(
                            "Expected localized 'Salin jawaban belum tersimpan' after scrolling",
                            scrollToText("Salin jawaban belum tersimpan", exact = true) != null,
                        )
                        awaitNode("Unsaved copy button", timeoutMs = 5_000) {
                            norm(it.text) == "Salin jawaban belum tersimpan"
                        }
                        scrollToText("Coba simpan lagi", exact = true)
                        awaitNode("Retry saving button", timeoutMs = 5_000) {
                            norm(it.text) == "Coba simpan lagi"
                        }

                        // Scroll up to bring the unsaved error banner (above the action buttons) into the viewport.
                        val upperTexts = linkedSetOf<String>()
                        var matchingBanner = ""
                        var capturedBannerShot = false
                        repeat(8) {
                            val nowTexts = visibleTexts()
                            upperTexts += nowTexts
                            nowTexts.firstOrNull {
                                it.contains("Answer not saved.") || it.contains("Jawaban belum tersimpan.")
                            }?.let { found ->
                                if (matchingBanner.isEmpty()) matchingBanner = found
                                if (!capturedBannerShot) {
                                    saveScreenshot("n4-unsaved-banner")
                                    capturedBannerShot = true
                                }
                            }
                            if (upperTexts.any { it.contains("Respons n4 belum tersimpan.") } && matchingBanner.isNotEmpty()) {
                                return@repeat
                            }
                            scrollUpSmall()
                        }
                        upperTexts += visibleTexts()
                        if (!capturedBannerShot) saveScreenshot("n4-unsaved-banner")
                        if (matchingBanner.isEmpty()) {
                            matchingBanner =
                                upperTexts.firstOrNull {
                                    it.contains("Answer not saved.") || it.contains("Jawaban belum tersimpan.")
                                }.orEmpty()
                        }
                        println("N4_OBSERVED_BANNER=$matchingBanner")
                        assertTrue("Expected partial answer to stay readable", upperTexts.any { it.contains("Respons n4 belum tersimpan.") })
                        assertFalse(
                            "Hardcoded English unsaved banner leaked into in-ID: $matchingBanner",
                            matchingBanner.contains("Answer not saved."),
                        )
                        assertTrue(
                            "Expected localized Indonesian unsaved banner, observed='$matchingBanner' all=$upperTexts",
                            matchingBanner.contains("Jawaban belum tersimpan."),
                        )

                        // Copy unsaved answer while unsaved state is active.
                        scrollToText("Salin jawaban belum tersimpan", exact = true)
                        clickableOwner(awaitNode("Copy unsaved") { norm(it.text) == "Salin jawaban belum tersimpan" })
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        val upAfterCopy = collectReaderTextsAfterScrolling(downward = false)
                        assertTrue(
                            "Expected copied notice in in-ID, got=$upAfterCopy",
                            upAfterCopy.any { it.contains("Jawaban yang belum tersimpan disalin") },
                        )
                        var copiedClip = ""
                        i.runOnMainSync {
                            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            copiedClip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        }
                        assertEquals("Respons n4 belum tersimpan.", copiedClip)

                        // Retry saving while storage failure still fires -> localized SAVE_STILL_FAILING notice, 1 HTTP request total.
                        assertTrue(
                            "Expected 'Coba simpan lagi' button after copy",
                            scrollToText("Coba simpan lagi", exact = true) != null,
                        )
                        scrollDownSmall()
                        val retryFailBtn =
                            clickableOwner(
                                awaitNode("Retry saving while failing") {
                                    norm(it.text) == "Coba simpan lagi" && it.isEnabled
                                },
                            )
                        println(
                            "N4_RETRY_FAIL_CLICK bounds=${Rect().also(retryFailBtn::getBoundsInScreen)} enabled=${retryFailBtn.isEnabled}",
                        )
                        assertTrue(
                            "Retry saving click must succeed",
                            retryFailBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        )
                        val failDeadline = SystemClock.uptimeMillis() + 8_000
                        var stillFailingTexts = emptyList<String>()
                        var scrollDownPhase = false
                        while (SystemClock.uptimeMillis() < failDeadline) {
                            stillFailingTexts =
                                if (scrollDownPhase) {
                                    collectReaderTextsAfterScrolling(downward = true)
                                } else {
                                    collectReaderTextsAfterScrolling(downward = false)
                                }
                            if (stillFailingTexts.any { it.contains("Masih tidak dapat menyimpan.") }) break
                            scrollDownPhase = !scrollDownPhase
                            SystemClock.sleep(200)
                        }
                        assertTrue(
                            "Expected localized SAVE_STILL_FAILING notice, got=$stillFailingTexts",
                            stillFailingTexts.any { it.contains("Masih tidak dapat menyimpan.") },
                        )
                        assertEquals("No HTTP replay during retrySaving failure", 1, server.requestCount)

                        // Clear fault injection and retry saving -> persists cleanly without HTTP replay.
                        ChatStorageProbe.beforeFinish = null
                        assertTrue(
                            "Expected 'Coba simpan lagi' button for recovery",
                            scrollToText("Coba simpan lagi", exact = true) != null,
                        )
                        scrollDownSmall()
                        val retryRecoverBtn =
                            clickableOwner(
                                awaitNode("Retry saving success") {
                                    norm(it.text) == "Coba simpan lagi" && it.isEnabled
                                },
                            )
                        assertTrue(
                            "Retry saving recovery click must succeed",
                            retryRecoverBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        )

                        val end = SystemClock.uptimeMillis() + 10_000
                        while (SystemClock.uptimeMillis() < end) {
                            if (scan().none { norm(it.text) == "Salin jawaban belum tersimpan" }) break
                            SystemClock.sleep(150)
                        }
                        assertTrue(
                            "Unsaved controls must clear after retrySaving succeeds",
                            scan().none { norm(it.text) == "Salin jawaban belum tersimpan" },
                        )
                        assertEquals("No HTTP replay during successful retrySaving", 1, server.requestCount)

                        val convs = dao.conversations().first()
                        val matching = convs.firstOrNull { it.title.contains("Tanya unsaved n4") }
                        sentConvId = matching?.id
                        val rows = matching?.let { dao.snapshot(it.id) }.orEmpty()
                        assertEquals("SUCCESS", rows.lastOrNull()?.status)
                        assertEquals("Respons n4 belum tersimpan.", rows.lastOrNull()?.rawContent?.trim())
                    } finally {
                        ChatStorageProbe.beforeFinish = null
                        i.runOnMainSync { activity.finish() }
                    }
                } finally {
                    ChatStorageProbe.beforeFinish = null
                    dao.deleteConversation(stopId)
                    dao.deleteConversation(truncId)
                    sentConvId?.let { dao.deleteConversation(it) }
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
            Unit
        }
}
