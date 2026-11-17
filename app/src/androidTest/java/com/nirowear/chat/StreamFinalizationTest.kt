package com.nirowear.chat

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import com.nirowear.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StreamFinalizationTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app get() = i.targetContext.applicationContext as NiroApplication

    @Test fun burstTailAppearsWhileProviderWaitsForCompletion() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val profile =
                    ProviderProfile(
                        UUID.randomUUID().toString(),
                        ProviderType.OPENAI_COMPATIBLE,
                        "Burst tail",
                        server.url("/v1").toString(),
                        "test",
                        UUID.randomUUID().toString(),
                    )
                var vm: ChatViewModel? = null
                var id: String? = null
                try {
                    app.settings.save(profile, SecretValue("synthetic-burst-not-real"))
                    i.runOnMainSync { vm = ChatViewModel(app) }
                    val chat = vm!!
                    val ready =
                        ChatViewModel::class.java
                            .getDeclaredField("ready")
                            .apply { isAccessible = true }
                            .get(chat) as CompletableDeferred<*>
                    withTimeout(5000) { ready.await() }
                    val body =
                        listOf("Burst first", " second").joinToString("") {
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$it\"}}]}\n\n"
                        }
                    server.enqueue(
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(body)
                            .setHeader("Content-Length", body.toByteArray().size + 1),
                    )
                    withContext(Dispatchers.Main) {
                        chat.newChat()
                        chat.send("Burst probe")
                    }
                    withTimeout(5000) { chat.state.first { it.conversationId != null } }
                    id = chat.state.value.conversationId!!
                    withTimeout(3000) { chat.messages.first { it.lastOrNull()?.rawContent == "Burst first second" } }
                    assertTrue("Intermediate text observed before completion", chat.state.value.generating)
                    withContext(Dispatchers.Main) { chat.stop() }
                    withTimeout(3000) { chat.state.first { !it.generating } }
                    assertEquals(
                        "Burst first second",
                        app.database
                            .chats()
                            .snapshot(id)
                            .last()
                            .rawContent,
                    )
                    assertEquals(
                        "STOPPED",
                        app.database
                            .chats()
                            .snapshot(id)
                            .last()
                            .status,
                    )
                } finally {
                    withContext(Dispatchers.Main) { vm?.stop() }
                    vm?.let { withTimeout(5000) { it.state.first { state -> !state.generating } } }
                    id?.let { app.database.chats().deleteConversation(it) }
                    app.settings.delete(profile.id)
                    i.runOnMainSync { vm?.viewModelScope?.cancel() }
                }
            }
        }

    @Test fun stopDuringFinalSaveKeepsStoppedStateAndPartialText() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val profile =
                    ProviderProfile(
                        UUID.randomUUID().toString(),
                        ProviderType.OPENAI_COMPATIBLE,
                        "Final save",
                        server.url("/v1").toString(),
                        "test",
                        UUID.randomUUID().toString(),
                    )
                var vm: ChatViewModel? = null
                var id: String? = null
                try {
                    app.settings.save(profile, SecretValue("synthetic-final-save-not-real"))
                    i.runOnMainSync { vm = ChatViewModel(app) }
                    val chat = vm!!
                    val ready =
                        ChatViewModel::class.java
                            .getDeclaredField("ready")
                            .apply { isAccessible = true }
                            .get(chat) as CompletableDeferred<*>
                    withTimeout(5000) { ready.await() }
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Retained final text\"}}]}\n\n" +
                                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                    )
                    ChatStorageProbe.beforeFinish = { chat.stop() }
                    withContext(Dispatchers.Main) {
                        chat.newChat()
                        chat.send("Final save stop probe")
                    }
                    withTimeout(10000) { chat.state.first { !it.generating } }
                    id = chat.state.value.conversationId!!
                    assertEquals(
                        "Retained final text",
                        app.database
                            .chats()
                            .snapshot(id)
                            .last()
                            .rawContent,
                    )
                    assertEquals(
                        // The stream above DELIVERS its terminal event (`finish_reason: stop`)
                        // before this Stop lands, so the answer is complete. Asserting STOPPED here
                        // pinned the opposite of the contract: it recorded a finished answer as
                        // stopped, which also deletes its phase record (ChatDao.finish drops phases
                        // for any non-SUCCESS status). A Stop arriving BEFORE the terminal event is
                        // a different case, covered by the interrupted-stream test.
                        "SUCCESS",
                        app.database
                            .chats()
                            .snapshot(id)
                            .last()
                            .status,
                    )
                    // `interrupted` describes the OUTCOME, and the outcome here is a complete
                    // answer: the terminal event arrived before the Stop. It must not be set.
                    assertFalse(chat.state.value.interrupted)
                    assertEquals(1, server.requestCount)
                } finally {
                    ChatStorageProbe.beforeFinish = null
                    id?.let { app.database.chats().deleteConversation(it) }
                    app.settings.delete(profile.id)
                    i.runOnMainSync { vm?.viewModelScope?.cancel() }
                }
            }
        }

    @Test fun unchangedContentStillRendersNewStoppedStatus() =
        runBlocking {
            val dao = app.database.chats()
            val id = "memo-status-${UUID.randomUUID()}"
            dao.insertConversation(Conversation(id, "Memo status fixture", System.currentTimeMillis() + 100000))

            val answer =
                dao.insertMessage(
                    StoredMessage(
                        conversationId = id,
                        role = "ASSISTANT",
                        rawContent = "Memo partial text",
                        providerLabel = "Local",
                        modelId = "test",
                    ),
                )
            val activity =
                i.startActivitySync(
                    Intent(i.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )

            fun norm(s: CharSequence?): String =
                s?.toString().orEmpty()
                    .replace("\u2068", "")
                    .replace("\u2069", "")
                    .replace("\u200E", "")
                    .replace("\u200F", "")

            fun find(
                text: String,
                n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow,
            ): Boolean =
                // The renderer wraps every inline run in FSI/PDI, so exact equality fails against text
                // that is visibly on screen ("⁨Partial stop content⁩"). Measured on the AVD.
                n != null &&
                    (
                        norm(n.text) == text || (0 until n.childCount).any { find(text, n.getChild(it)) }
                    )

            fun await(text: String): Boolean {
                val deadline = SystemClock.uptimeMillis() + 8000
                var steps = 0
                while (SystemClock.uptimeMillis() < deadline) {
                    i.uiAutomation.clearCache()
                    if (find(text)) return true
                    if (++steps % 10 == 0) {
                        i.uiAutomation.executeShellCommand("input swipe 233 280 233 200 300").use {
                            android.os.ParcelFileDescriptor
                                .AutoCloseInputStream(it)
                                .readBytes()
                        }
                    }
                    SystemClock.sleep(100)
                }
                return false
            }
            try {
                val visible = await("Memo partial text")

                fun describe(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): String =
                    if (n == null) {
                        "NO_WINDOW"
                    } else {
                        "${n.packageName}:${n.text}:${n.contentDescription}" +
                            (0 until n.childCount).joinToString(";", "[", "]") { describe(n.getChild(it)) }
                    }
                assertTrue("Initial content rendered; ${describe()}", visible)
                dao.checkpoint(answer, "Memo partial text", "STOPPED")
                assertTrue("Status-only update must invalidate presentation", await("Stopped · partial answer kept"))
            } finally {
                i.runOnMainSync { activity.finish() }
                dao.deleteConversation(id)
            }
        }
}
