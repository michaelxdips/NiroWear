package com.nirowear.chat

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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class NativeProviderSwitchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NiroApplication

    private fun sse(vararg data: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            data.joinToString("") {
                "data: $it\n\n"
            },
        )

    /**
     * A bare TimeoutCancellationException is thrown from the timeout coroutine, so its stack never
     * names the step that stalled — which is why this file's failure could not be located from the
     * result XML. Every wait in the routing test goes through this helper instead.
     */
    private suspend fun settled(
        chat: ChatViewModel,
        label: String,
        budgetMs: Long = 10000,
    ) {
        try {
            withTimeout(budgetMs) { chat.state.first { !it.generating } }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Turn never settled at '$label' within $budgetMs ms; state=${chat.state.value}", timeout)
        }
    }

    /** Explicit host invocation only: kill-seed intentionally terminates instrumentation mid-HTTP. */
    @com.nirowear.HostDrivenGate
    @Test fun activeHttpProcessDeathProbe() =
        runBlocking {
            val mode = InstrumentationRegistry.getArguments().getString("restartMode")
            org.junit.Assume.assumeTrue("Host-driven process death probe", mode in setOf("kill-seed", "kill-verify"))
            val id = "33333333-3333-3333-3333-333333333333"
            val conversationFile = File(app.noBackupFilesDir, "active-http-restart-probe")
            MockWebServer().use { server ->
                var vm: ChatViewModel? = null
                try {
                    if (mode == "kill-seed") {
                        app.settings.save(
                            ProviderProfile(
                                id,
                                ProviderType.OPENAI,
                                "Active HTTP restart",
                                server.url("/v1").toString(),
                                "restart-model",
                                id,
                            ),
                            SecretValue("active-native-restart-controlled-key"),
                        ) { _, _ -> null }
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
                        server.enqueue(
                            sse("""{"type":"response.output_text.delta","delta":"Durable active HTTP partial"}""")
                                .setBody(
                                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Durable active HTTP partial\"}\n\n:" +
                                        " ".repeat(1024 * 1024),
                                ).throttleBody(1024, 1, TimeUnit.SECONDS),
                        )
                        withContext(Dispatchers.Main) { chat.send("Killed while HTTP active") }
                        withTimeout(10000) { chat.state.first { it.conversationId != null } }
                        val conversation = chat.state.value.conversationId!!
                        withTimeout(10000) {
                            app.database.chats().messages(conversation).first {
                                it.lastOrNull()?.rawContent ==
                                    "Durable active HTTP partial"
                            }
                        }
                        assertTrue(chat.state.value.generating)
                        assertEquals(
                            "STREAMING",
                            app.database
                                .chats()
                                .snapshot(conversation)
                                .last()
                                .status,
                        )
                        conversationFile.writeText(conversation)
                        // No finally-based settlement is possible after this real process kill.
                        instrumentation.uiAutomation.executeShellCommand("am force-stop com.nirowear").use {
                            android.os.ParcelFileDescriptor
                                .AutoCloseInputStream(it)
                                .readBytes()
                        }
                        error("Expected process termination")
                    }
                    val conversation = conversationFile.readText()
                    val p =
                        app.settings.state
                            .first()
                            .profiles
                            .single { it.id == id }
                    assertEquals(ProviderType.OPENAI, p.type)
                    assertEquals("restart-model", p.selectedModelId)
                    assertEquals("active-native-restart-controlled-key", app.secrets.read(p.credentialRef).forRequest())
                    instrumentation.runOnMainSync { vm = ChatViewModel(app) }
                    withTimeout(5000) {
                        app.database.chats().messages(conversation).first {
                            it.lastOrNull()?.status ==
                                "STOPPED"
                        }
                    }
                    val partial =
                        app.database
                            .chats()
                            .snapshot(conversation)
                            .last()
                    assertEquals("Durable active HTTP partial", partial.rawContent)
                    assertEquals(id, partial.providerProfileId)
                    assertEquals("restart-model", partial.modelId)
                    app.settings.save(p.copy(baseUrl = server.url("/v1").toString()), null)
                    val chat = vm!!
                    withContext(Dispatchers.Main) { chat.open(conversation) }
                    server.enqueue(
                        sse(
                            """{"type":"response.output_text.delta","delta":"Restart follow-up answer"}""",
                            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Restart follow-up answer"}]}]}}""",
                        ),
                    )
                    withContext(Dispatchers.Main) { chat.send("Follow-up after process death") }
                    settled(chat, "active-http partial turn", 10000L)
                    assertNull(chat.state.value.error)
                    val wire = server.takeRequest(3, TimeUnit.SECONDS)!!
                    val body = Json.parseToJsonElement(wire.body.readUtf8()).jsonObject
                    assertEquals(
                        listOf(
                            "Killed while HTTP active",
                            "Durable active HTTP partial",
                            "Follow-up after process death",
                        ),
                        body.getValue("input").jsonArray.map {
                            it.jsonObject
                                .getValue("content")
                                .jsonPrimitive.content
                        },
                    )
                    assertEquals(
                        "SUCCESS",
                        app.database
                            .chats()
                            .snapshot(conversation)
                            .last()
                            .status,
                    )
                    assertEquals(1, server.requestCount)
                    app.database.chats().deleteConversation(conversation)
                    app.settings.delete(id)
                    check(conversationFile.delete())
                } finally {
                    instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
                }
            }
        }

    @Test fun nativeRoutingPortableHistoryProvenanceStopFollowupAndEncryptedStorage() =
        runBlocking {
            MockWebServer().use { openAi ->
                MockWebServer().use { gemini ->
                    MockWebServer().use { anthropic ->
                        val profiles =
                            listOf(
                                ProviderType.OPENAI to openAi,
                                ProviderType.GEMINI to gemini,
                                ProviderType.ANTHROPIC to anthropic,
                            ).map { (type, server) ->
                                ProviderProfile(
                                    UUID.randomUUID().toString(),
                                    type,
                                    type.name,
                                    server
                                        .url(
                                            if (type ==
                                                ProviderType.GEMINI
                                            ) {
                                                "/v1beta"
                                            } else {
                                                "/v1"
                                            },
                                        ).toString(),
                                    "model-${type.name}",
                                    "unused",
                                )
                            }
                        val markers = profiles.map { "native-watch-controlled-key-${it.type.name}" }
                        var vm: ChatViewModel? = null
                        var conversation: String? = null
                        try {
                            profiles.forEachIndexed { i, p -> app.settings.save(p, SecretValue(markers[i])) }
                            app.settings.select(profiles[0].id)
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
                            openAi.enqueue(
                                sse(
                                    """{"type":"response.output_text.delta","delta":"OpenAI answer"}""",
                                    """{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","encrypted_content":"private-never-replayed","summary":[{"text":"private-never-visible"}]},{"type":"message","role":"assistant","status":"completed","phase":"final_answer","content":[{"type":"output_text","text":"OpenAI answer"}]}]}}""",
                                ),
                            )
                            withContext(Dispatchers.Main) { chat.send("First prompt") }
                            settled(chat, "first [OI] turn", 10000L)
                            assertNull(chat.state.value.error)
                            conversation = chat.state.value.conversationId!!
                            assertEquals("/v1/responses", openAi.takeRequest(3, TimeUnit.SECONDS)!!.path)

                            app.settings.select(profiles[1].id)
                            gemini.enqueue(
                                sse(
                                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Gemini answer"}]},"finishReason":"STOP"}]}""",
                                ),
                            )
                            withContext(Dispatchers.Main) { chat.send("Gemini follow-up") }
                            settled(chat, "gemini turn", 10000L)
                            assertNull(chat.state.value.error)
                            val geminiWire = gemini.takeRequest(3, TimeUnit.SECONDS)!!
                            assertEquals("/v1beta/models/model-GEMINI:streamGenerateContent?alt=sse", geminiWire.path)
                            assertEquals(markers[1], geminiWire.getHeader("x-goog-api-key"))
                            val geminiBody = Json.parseToJsonElement(geminiWire.body.readUtf8()).jsonObject
                            assertEquals(
                                listOf(
                                    "First prompt",
                                    "OpenAI answer",
                                    "Gemini follow-up",
                                ),
                                geminiBody.getValue("contents").jsonArray.map {
                                    it.jsonObject
                                        .getValue("parts")
                                        .jsonArray[0]
                                        .jsonObject
                                        .getValue("text")
                                        .jsonPrimitive.content
                                },
                            )
                            assertFalse(geminiBody.containsKey("previous_response_id"))
                            assertFalse(geminiBody.toString().contains("phase"))
                            assertFalse(geminiBody.toString().contains("private-never"))

                            app.settings.select(profiles[2].id)
                            val start = """{"type":"message_start","message":{"role":"assistant","content":[]}}"""
                            val block = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""

                            fun delta(text: String) =
                                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

                            fun metadata() = MockResponse().setBody("""{"max_tokens":32000}""")
                            anthropic.enqueue(metadata())
                            anthropic.enqueue(
                                sse(start, block, delta("Partial Anthropic"))
                                    .setBody(
                                        listOf(
                                            start,
                                            block,
                                            delta("Partial Anthropic"),
                                        ).joinToString("") { "data: $it\n\n" } +
                                            " ".repeat(16384),
                                    ).throttleBody(1024, 1, TimeUnit.SECONDS),
                            )
                            withContext(Dispatchers.Main) { chat.send("Anthropic follow-up") }
                            withTimeout(10000) {
                                app.database.chats().messages(conversation).first {
                                    it.last().rawContent ==
                                        "Partial Anthropic"
                                }
                            }
                            withContext(Dispatchers.Main) { chat.stop() }
                            settled(chat, "stop settle", 3000L)
                            assertEquals(
                                "STOPPED",
                                app.database
                                    .chats()
                                    .snapshot(conversation)
                                    .last()
                                    .status,
                            )
                            assertEquals(
                                "/v1/models/model-ANTHROPIC",
                                anthropic.takeRequest(3, TimeUnit.SECONDS)!!.path,
                            )
                            val anthropicWire = anthropic.takeRequest(3, TimeUnit.SECONDS)!!
                            assertEquals("/v1/messages", anthropicWire.path)
                            // AnthropicProvider sends the documented API-key header. The SDKs send
                            // `Authorization: Bearer`, and both authenticate — this assertion pinned
                            // Bearer and therefore failed against the shipping code, which uses
                            // `x-api-key` (see the comment in AnthropicProvider.authenticated).
                            assertEquals(markers[2], anthropicWire.getHeader("x-api-key"))
                            assertEquals(null, anthropicWire.getHeader("Authorization"))
                            val anthropicBody = Json.parseToJsonElement(anthropicWire.body.readUtf8()).jsonObject
                            assertEquals(
                                listOf(
                                    "First prompt",
                                    "OpenAI answer",
                                    "Gemini follow-up",
                                    "Gemini answer",
                                    "Anthropic follow-up",
                                ),
                                anthropicBody.getValue("messages").jsonArray.map {
                                    it.jsonObject
                                        .getValue("content")
                                        .jsonPrimitive.content
                                },
                            )
                            assertFalse(anthropicBody.containsKey("contents"))
                            assertFalse(anthropicBody.toString().contains("phase"))
                            assertFalse(anthropicBody.toString().contains("private-never"))

                            // NO metadata GET is queued for this turn: AnthropicProvider caches
                            // max_tokens per endpoint+model for the process, and the turn above
                            // already fetched it (`/v1/models/model-ANTHROPIC`, asserted at :292).
                            // Queuing a second metadata answer left it at the HEAD of the queue, so
                            // the messages POST received `{"max_tokens":32000}`, the decoder saw no
                            // SSE frame, and the stored error read "Stream interrupted or
                            // unsupported." — the recorded failure. The queue was stale, not the app.
                            anthropic.enqueue(
                                sse(
                                    start,
                                    block,
                                    delta("Anthropic final"),
                                    """{"type":"content_block_stop","index":0}""",
                                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                                    """{"type":"message_stop"}""",
                                ),
                            )
                            withContext(Dispatchers.Main) { chat.send("After Stop") }
                            settled(chat, "after Stop", 10000L)
                            assertEquals("/v1/messages", anthropic.takeRequest(3, TimeUnit.SECONDS)!!.path)
                            assertEquals(3, anthropic.requestCount)
                            assertNull(chat.state.value.error)
                            val rows = app.database.chats().snapshot(conversation)
                            assertEquals(8, rows.size)
                            assertEquals(
                                listOf(profiles[0].id, profiles[1].id, profiles[2].id, profiles[2].id),
                                rows
                                    .filter {
                                        it.role ==
                                            "ASSISTANT"
                                    }.map { it.providerProfileId },
                            )
                            assertEquals(
                                listOf("model-OPENAI", "model-GEMINI", "model-ANTHROPIC", "model-ANTHROPIC"),
                                rows
                                    .filter {
                                        it.role ==
                                            "ASSISTANT"
                                    }.map { it.modelId },
                            )
                            assertEquals("SUCCESS", rows.last().status)
                            // Model change must change payload; compatible switch must use its own protocol.
                            app.settings.save(profiles[1].copy(selectedModelId = "model-GEMINI-second"), null)
                            // Editing an EXISTING profile deliberately does not re-target the next
                            // send (ProviderSettings.save: "never silently change a model"), so the
                            // selection is still Anthropic here. Without this the turn went to the
                            // Anthropic mock, which had nothing queued, and hung until the deadline.
                            app.settings.select(profiles[1].id)
                            gemini.enqueue(
                                sse(
                                    """{"candidates":[{"content":{"parts":[{"text":"Second model answer"}]},"finishReason":"STOP"}]}""",
                                ),
                            )
                            withContext(Dispatchers.Main) { chat.send("Different Gemini model") }
                            settled(chat, "second gemini model", 10000L)
                            assertNull(chat.state.value.error)
                            assertEquals(
                                "/v1beta/models/model-GEMINI-second:streamGenerateContent?alt=sse",
                                gemini.takeRequest(3, TimeUnit.SECONDS)!!.path,
                            )
                            app.settings.save(
                                profiles[0].copy(
                                    type = ProviderType.OPENAI_COMPATIBLE,
                                    selectedModelId = "compatible-model",
                                ),
                                null,
                            )
                            // Same rule as above: switching WHICH provider answers is an explicit
                            // select(), not a side effect of saving a profile.
                            app.settings.select(profiles[0].id)
                            openAi.enqueue(
                                sse(
                                    """{"choices":[{"delta":{"content":"Compatible continuation"},"finish_reason":"stop"}]}""",
                                ),
                            )
                            withContext(Dispatchers.Main) { chat.send("Compatible follow-up") }
                            settled(chat, "final", 10000L)
                            assertNull(chat.state.value.error)
                            val compatibleWire = openAi.takeRequest(3, TimeUnit.SECONDS)!!
                            assertEquals("/v1/chat/completions", compatibleWire.path)
                            val compatibleBody = Json.parseToJsonElement(compatibleWire.body.readUtf8()).jsonObject
                            assertEquals("compatible-model", compatibleBody.getValue("model").jsonPrimitive.content)
                            val finalRows = app.database.chats().snapshot(conversation)
                            assertEquals(12, finalRows.size)
                            assertEquals(
                                finalRows.dropLast(1).map {
                                    it.rawContent
                                },
                                compatibleBody.getValue("messages").jsonArray.drop(1).map {
                                    it.jsonObject
                                        .getValue("content")
                                        .jsonPrimitive.content
                                },
                            )
                            assertEquals("model-GEMINI-second", finalRows[9].modelId)
                            assertEquals("compatible-model", finalRows.last().modelId)
                            assertEquals("model-OPENAI", finalRows[1].modelId)
                            assertFalse(compatibleBody.toString().contains("phase"))
                            app.settings.save(profiles[0], null)
                            openAi.enqueue(
                                sse(
                                    """{"type":"response.output_text.delta","delta":"Back to OpenAI"}""",
                                    """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Back to OpenAI"}]}]}}""",
                                ),
                            )
                            withContext(Dispatchers.Main) { chat.send("Return to OpenAI") }
                            settled(chat, "final", 10000L)
                            assertNull(chat.state.value.error)
                            val replay =
                                Json
                                    .parseToJsonElement(
                                        openAi.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8(),
                                    ).jsonObject
                            assertEquals(
                                "final_answer",
                                replay
                                    .getValue("input")
                                    .jsonArray[1]
                                    .jsonObject
                                    .getValue("phase")
                                    .jsonPrimitive.content,
                            )
                            assertEquals(
                                1,
                                replay.getValue("input").jsonArray.count { it.jsonObject.containsKey("phase") },
                            )
                            assertFalse(replay.toString().contains("private-never"))
                            val restored = app.database.chats().snapshot(conversation)
                            assertFalse(restored.any { it.rawContent.contains("private-never") })
                            assertEquals(profiles[0].id, restored.last().providerProfileId)
                            assertEquals("model-OPENAI", restored.last().modelId)
                            val base = Endpoints.normalize(profiles[0].baseUrl, true)
                            assertEquals(
                                1,
                                app.database
                                    .chats()
                                    .openAiPhases(conversation, profiles[0].id, "model-OPENAI", base)
                                    .size,
                            )
                            app.database.chats().edit(conversation, restored.first().id, "Edited")
                            assertTrue(
                                app.database
                                    .chats()
                                    .openAiPhases(conversation, profiles[0].id, "model-OPENAI", base)
                                    .isEmpty(),
                            )
                            val roots =
                                listOf(
                                    app.filesDir,
                                    app.noBackupFilesDir,
                                    File(app.applicationInfo.dataDir, "databases"),
                                    File(app.applicationInfo.dataDir, "shared_prefs"),
                                )
                            roots
                                .filter { it.exists() }
                                .flatMap {
                                    it
                                        .walkTopDown()
                                        .filter(
                                            File::isFile,
                                        ).toList()
                                }.forEach { file ->
                                    val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
                                    markers.forEach {
                                        assertFalse(
                                            "Plaintext marker in ${file.name}",
                                            bytes.contains(it),
                                        )
                                    }
                                }
                        } finally {
                            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
                            profiles.forEach { app.settings.delete(it.id) }
                            conversation?.let { app.database.chats().deleteConversation(it) }
                        }
                    }
                }
            }
        }
}
