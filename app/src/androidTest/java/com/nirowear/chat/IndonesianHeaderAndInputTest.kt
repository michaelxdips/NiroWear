package com.nirowear.chat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

class IndonesianHeaderAndInputTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(root: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private fun freshNodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return nodes()
    }

    private fun awaitNode(
        what: String,
        timeoutMs: Long = 10_000,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            freshNodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        val visible = freshNodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
        error("Missing $what; visible=$visible")
    }

    private fun clickableOwner(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && !cur.isClickable) cur = cur.parent
        return cur ?: error("No clickable owner for ${node.text ?: node.contentDescription}")
    }

    private fun shell(cmd: String): String =
        i.uiAutomation.executeShellCommand(cmd).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }

    private fun savePng(name: String) {
        val img = i.uiAutomation.takeScreenshot() ?: return
        try {
            val dir = i.targetContext.getExternalFilesDir(null) ?: return
            File(dir, "$name.png").outputStream().use {
                img.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            img.recycle()
        }
    }

    @Test fun indonesianHeaderFitsCircleAndEditResendAndVoiceStatesWork() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val previousProfile = app.settings.state.first().selectedId
            val dao = app.database.chats()
            val token = UUID.randomUUID().toString()
            val convId = "n6-edit-${UUID.randomUUID().toString().take(8)}"

            // 1. Verify empty-provider header fits inside circular 466x466 chord and keeps accessible Menu label.
            val emptyActivity =
                i.startActivitySync(
                    Intent(i.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                ) as MainActivity
            try {
                awaitNode("in-ID empty reader") { it.text?.toString() == "Tanya apa saja" }
                val menuNode = awaitNode("Menu node") { it.contentDescription?.toString() == "Menu" }
                val headerNode =
                    awaitNode("Indonesian header text") { n ->
                        val t = n.text?.toString().orEmpty()
                        t.contains("provider") && t.contains("Menu")
                    }
                val headerText = headerNode.text.toString()
                assertFalse("Header text must not truncate with ellipsis: $headerText", headerText.contains("…") || headerText.endsWith("..."))
                assertEquals("Menu semantics must stay intact", "Menu", menuNode.contentDescription?.toString())
                savePng("n6-home-header")

                val r = Rect().also(headerNode::getBoundsInScreen)
                // Compute inscribed circular chord of 466x466 display (radius = 233 px) at top edge r.top
                val dy = (233.0 - r.top.coerceAtLeast(0)).coerceAtLeast(0.0)
                val halfChord = sqrt((233.0 * 233.0 - dy * dy).coerceAtLeast(0.0))
                val chordLeft = (233.0 - halfChord).toInt()
                val chordRight = (233.0 + halfChord).toInt()
                assertTrue(
                    "Header bounds $r must fit inside circular chord [$chordLeft..$chordRight] at y=${r.top}",
                    r.left >= chordLeft && r.right <= chordRight,
                )

                // Exercise keyboard composer in in-ID before seeding
                clickableOwner(awaitNode("Input keyboard") { it.contentDescription?.toString() == "Input keyboard" })
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode("Tulis pesan") { it.text?.toString() == "Tulis pesan" }
                awaitNode("Kolom pesan") { it.contentDescription?.toString() == "Kolom pesan" }
                awaitNode("Kirim") { it.text?.toString() == "Kirim" }
                repeat(3) {
                    if (freshNodes().any { it.contentDescription?.toString() == "Menu" }) return@repeat
                    shell("input keyevent 4")
                    SystemClock.sleep(450)
                }
                awaitNode("Back on empty reader") { it.contentDescription?.toString() == "Menu" }

                // Exercise Voice input control & observe AVD voice state (notice or speech activity)
                val voiceBtn = awaitNode("Input suara") { it.contentDescription?.toString() == "Input suara" }
                clickableOwner(voiceBtn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(800)
                val voiceNotice =
                    freshNodes().firstOrNull {
                        it.text?.toString()?.let { t ->
                            t.contains("Pengenalan suara tidak tersedia") || t.contains("Pengenalan suara diblokir")
                        } == true
                    }?.text?.toString()
                if (voiceNotice == null && freshNodes().none { it.contentDescription?.toString() == "Menu" }) {
                    shell("input keyevent 4")
                    awaitNode("Returned after voice activity") { it.contentDescription?.toString() == "Menu" }
                }
                i.sendStatus(
                    0,
                    Bundle().apply {
                        putString("n6VoiceNotice", voiceNotice ?: "external_recognizer_activity")
                        putString("n6HeaderBounds", "$r chord=[$chordLeft..$chordRight] text=$headerText")
                    },
                )
            } finally {
                i.runOnMainSync { emptyActivity.finish() }
            }

            // 2. Exercise edit/resend in in-ID with a seeded conversation & MockWebServer SSE response.
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Jawaban edit berhasil.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                )
                server.start()
                try {
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
                    dao.insertConversation(Conversation(convId, "Uji edit Indo", System.currentTimeMillis()))
                    dao.insertMessage(StoredMessage(conversationId = convId, role = "USER", rawContent = "Pertanyaan awal"))
                    dao.insertMessage(StoredMessage(conversationId = convId, role = "ASSISTANT", rawContent = "Jawaban lama"))

                    val editActivity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        ) as MainActivity
                    try {
                        val userText = awaitNode("Seeded user prompt") { it.text?.toString()?.contains("Pertanyaan awal") == true }
                        var editOwner: AccessibilityNodeInfo? = userText
                        while (editOwner != null && !editOwner.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) {
                            editOwner = editOwner.parent
                        }
                        assertNotNull("User row must expose long-click edit action", editOwner)
                        val editActionLabel =
                            editOwner!!.actionList
                                .first { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
                                .label
                                ?.toString()
                        assertEquals("Edit pesan", editActionLabel)
                        assertTrue(editOwner.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))

                        val resendBtn = awaitNode("Edit & kirim ulang") { it.text?.toString() == "Edit & kirim ulang" }
                        val editor = awaitNode("Edit Kolom pesan") { it.isEditable || it.contentDescription?.toString() == "Kolom pesan" }
                        assertTrue(
                            editor.performAction(
                                AccessibilityNodeInfo.ACTION_SET_TEXT,
                                Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Pertanyaan edit")
                                },
                            ),
                        )
                        clickableOwner(resendBtn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        awaitNode("Edited assistant answer", timeoutMs = 15_000) {
                            it.text?.toString()?.contains("Jawaban edit berhasil.") == true
                        }
                        val stored = dao.snapshot(convId)
                        assertEquals(listOf("Pertanyaan edit", "Jawaban edit berhasil."), stored.map { it.rawContent.trim() })
                    } finally {
                        i.runOnMainSync { editActivity.finish() }
                    }
                } finally {
                    dao.deleteConversation(convId)
                    app.settings.delete(token)
                    previousProfile?.let { app.settings.select(it) }
                }
            }
        }
}
