package com.nirowear.chat

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.NiroApplication
import com.nirowear.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class CompatibleChatTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NiroApplication

    private fun frame(text: String) =
        "data: " +
            buildJsonObject {
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", 0)
                                put("delta", buildJsonObject { put("content", text) })
                            },
                        )
                    },
                )
            } + "\n\n"

    private fun response(text: String) =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(frame(text) + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")

    @Test fun failedFinalSavePreservesAnswerBlocksDiscardAndRetriesStorageWithoutHttpReplay() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val profile =
                    ProviderProfile(
                        UUID.randomUUID().toString(),
                        ProviderType.OPENAI_COMPATIBLE,
                        "Save failure",
                        server.url("/v1").toString(),
                        "test",
                        UUID.randomUUID().toString(),
                    )
                var vm: ChatViewModel? = null
                var id: String? = null
                try {
                    app.settings.save(profile, SecretValue("controlled-save-not-real"))
                    instrumentation.runOnMainSync { vm = ChatViewModel(app) }
                    val chat = vm!!
                    val ready =
                        ChatViewModel::class.java
                            .getDeclaredField("ready")
                            .apply {
                                isAccessible = true
                            }.get(chat) as CompletableDeferred<*>
                    withTimeout(5000) { ready.await() }
                    ChatStorageProbe.beforeFinish = { error("Controlled disk failure") }
                    server.enqueue(response("Only answer α\\frac{1}{2}"))
                    withContext(Dispatchers.Main) {
                        chat.newChat()
                        chat.send("Save probe")
                    }
                    withTimeout(10000) { chat.state.first { !it.generating } }
                    id = chat.state.value.conversationId!!
                    assertTrue(chat.state.value.unsaved)
                    assertEquals("Only answer α\\frac{1}{2}", chat.state.value.rawMarkdown)
                    withContext(Dispatchers.Main) {
                        chat.newChat()
                        chat.open("another-id")
                        chat.send("Must not send")
                        chat.retry()
                    }
                    assertEquals(id, chat.state.value.conversationId)
                    assertTrue(chat.state.value.unsaved)
                    assertEquals(1, server.requestCount)
                    ChatStorageProbe.beforeFinish = null
                    withContext(Dispatchers.Main) { chat.retrySaving() }
                    withTimeout(5000) { chat.state.first { !it.generating } }
                    assertFalse(chat.state.value.unsaved)
                    assertNull(chat.state.value.rawMarkdown)
                    assertEquals(
                        "SUCCESS",
                        app.database
                            .chats()
                            .snapshot(id)
                            .last()
                            .status,
                    )
                    assertEquals(
                        "Only answer α\\frac{1}{2}",
                        app.database
                            .chats()
                            .snapshot(id)
                            .last()
                            .rawContent,
                    )
                    assertEquals(1, server.requestCount)
                } finally {
                    ChatStorageProbe.beforeFinish = null
                    id?.let { app.database.chats().deleteConversation(it) }
                    app.settings.delete(profile.id)
                    instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
                }
            }
        }

    @Test fun immediateStopSettlesBeforeAnotherSend() =
        runBlocking {
            var vm: ChatViewModel? = null
            try {
                instrumentation.runOnMainSync { vm = ChatViewModel(app) }
                val chat = vm!!
                val ready =
                    ChatViewModel::class.java
                        .getDeclaredField("ready")
                        .apply {
                            isAccessible = true
                        }.get(chat) as CompletableDeferred<*>
                withTimeout(5000) { ready.await() }
                withContext(Dispatchers.Main) {
                    chat.newChat()
                    chat.send("Immediate cancellation probe")
                    chat.stop()
                }
                withTimeout(3000) { chat.state.first { !it.generating } }
                assertTrue(chat.state.value.interrupted)
                assertNull(chat.state.value.conversationId)
                withContext(Dispatchers.Main) { chat.newChat() }
                assertFalse(chat.state.value.generating)
            } finally {
                instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            }
        }

    @Test fun encryptedProfileStreamsStopsRetriesEditsAndSwitchesWithPortableHistory() =
        runBlocking {
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    first.start()
                    second.start()
                    val a =
                        ProviderProfile(
                            UUID.randomUUID().toString(),
                            ProviderType.OPENAI_COMPATIBLE,
                            "Server A",
                            first.url("/v1").toString(),
                            "model-a",
                            UUID.randomUUID().toString(),
                        )
                    val b =
                        a.copy(
                            id = UUID.randomUUID().toString(),
                            displayName = "Server B",
                            baseUrl = second.url("/v1").toString(),
                            selectedModelId = "model-b",
                        )
                    var vm: ChatViewModel? = null
                    try {
                        app.settings.save(a, SecretValue("controlled-watch-test-not-real"))
                        instrumentation.runOnMainSync { vm = ChatViewModel(app) }
                        val chat = vm!!
                        val ready =
                            ChatViewModel::class.java
                                .getDeclaredField("ready")
                                .apply {
                                    isAccessible = true
                                }.get(chat) as CompletableDeferred<*>
                        withTimeout(5000) { ready.await() }
                        withContext(Dispatchers.Main) { chat.newChat() }
                        first.enqueue(response("First answer α"))
                        withContext(Dispatchers.Main) { chat.send("First prompt") }
                        // 20 s, not 10: this is a cold ViewModel on a software-GPU emulator and the
                        // stream carries the same throttled body as the later steps.
                        withTimeout(20000) { chat.state.first { !it.generating } }
                        assertNull(chat.state.value.error)
                        val id = chat.state.value.conversationId!!
                        assertEquals(
                            "SUCCESS",
                            app.database
                                .chats()
                                .snapshot(id)
                                .last()
                                .status,
                        )
                        first.takeRequest(3, TimeUnit.SECONDS)!!

                        first.enqueue(
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(frame("Partial visible") + ":" + " ".repeat(16384))
                                .throttleBody(1024, 1, TimeUnit.SECONDS),
                        )
                        withContext(Dispatchers.Main) { chat.send("Follow-up") }
                        // 20 s, not 10: this is the throttled body (1024 B every second), so the
                        // checkpoint that makes the partial visible lands after the first whole frame
                        // plus the retry timer, and a software-GPU emulator doubles that again.
                        withTimeout(
                            20000,
                        ) {
                            app.database
                                .chats()
                                .messages(id)
                                .first { it.last().rawContent.contains("Partial visible") }
                        }
                        withContext(Dispatchers.Main) { chat.stop() }
                        withTimeout(3000) { chat.state.first { !it.generating } }
                        assertEquals(
                            "STOPPED",
                            app.database
                                .chats()
                                .snapshot(id)
                                .last()
                                .status,
                        )
                        assertEquals(
                            "Partial visible",
                            app.database
                                .chats()
                                .snapshot(id)
                                .last()
                                .rawContent,
                        )
                        first.takeRequest(3, TimeUnit.SECONDS)!!

                        first.enqueue(response("Retried answer"))
                        withContext(Dispatchers.Main) { chat.retry() }
                        withTimeout(10000) { chat.state.first { !it.generating } }
                        val retried = app.database.chats().snapshot(id)
                        assertEquals(4, retried.size)
                        assertEquals("Retried answer", retried.last().rawContent)
                        first.takeRequest(3, TimeUnit.SECONDS)!!

                        app.settings.save(b, SecretValue("second-controlled-key-not-real"))
                        // Saving an EXISTING profile must not re-target the next send
                        // (ProviderSettings.save: "never silently change a model"), so the switch
                        // below is an explicit select(). Without it the turn went to server A, which
                        // had nothing queued, and the 10 s deadline expired — the recorded failure.
                        app.settings.select(b.id)
                        second.enqueue(response("Switched answer"))
                        withContext(Dispatchers.Main) { chat.send("New provider follow-up") }
                        withTimeout(10000) { chat.state.first { !it.generating } }
                        val request = second.takeRequest(3, TimeUnit.SECONDS)!!
                        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        assertEquals("model-b", payload.getValue("model").jsonPrimitive.content)
                        val portable = payload.getValue("messages").jsonArray
                        assertEquals(
                            listOf(
                                SYSTEM_INSTRUCTION,
                                "First prompt",
                                "First answer α",
                                "Follow-up",
                                "Retried answer",
                                "New provider follow-up",
                            ),
                            portable.map {
                                it.jsonObject
                                    .getValue("content")
                                    .jsonPrimitive.content
                            },
                        )
                        val switched = app.database.chats().snapshot(id)
                        assertEquals(a.id, switched[1].providerProfileId)
                        assertEquals(b.id, switched.last().providerProfileId)

                        second.enqueue(response("Edited continuation"))
                        val accepted =
                            withContext(Dispatchers.Main) { chat.edit(switched.first().id, "Edited first prompt") }
                        // `edit()` returning true means the TURN WAS ACCEPTED, not that the row is on
                        // disk. `state.first { !it.generating }` could therefore read the idle state
                        // from BEFORE the edit started and assert against the whole old history —
                        // measured on CI 2026-11-18: expected the two edited rows, got all six.
                        assertTrue("edit() accepts the turn", accepted)
                        // Poll for the FINAL history, not for its first row. Waiting only for row 0
                        // let the assertion read "[Edited first prompt, ]" — the edit had landed but
                        // the continuation was still being written (measured on CI 2026-11-18).
                        val expected = listOf("Edited first prompt", "Edited continuation")
                        val deadline = SystemClock.uptimeMillis() + 20000
                        var edited = app.database.chats().snapshot(id).map { it.rawContent }
                        while (SystemClock.uptimeMillis() < deadline && edited != expected) {
                            delay(100)
                            edited = app.database.chats().snapshot(id).map { it.rawContent }
                        }
                        assertEquals(expected, edited)
                        app.database.chats().deleteConversation(id)
                    } finally {
                        app.settings.delete(a.id)
                        app.settings.delete(b.id)
                        instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
                    }
                }
            }
        }
}
