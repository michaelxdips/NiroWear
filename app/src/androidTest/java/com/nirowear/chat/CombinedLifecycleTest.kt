package com.nirowear.chat

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run scripts/check-combined-lifecycle.ps1. Same conversation crosses three real app processes. */
class CombinedLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NiroApplication
    private val openAiId = "44444444-4444-4444-4444-444444444440"
    private val geminiId = "44444444-4444-4444-4444-444444444441"
    private val probe get() = File(app.noBackupFilesDir, "combined-lifecycle-probe")
    private val prompts =
        listOf("Before background", "After recovery", "Switch provider", "Switch model", "Stop request", "After Stop")
    private val answers =
        listOf(
            "Persisted prefix α",
            "Recovered answer",
            "Gemini answer",
            "New model answer",
            "Stopped prefix β",
            "Still usable",
        )

    private fun shell(command: String) =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(it)
                .use { stream -> stream.readBytes() }
        }

    private fun sse(vararg frames: String) =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(frames.joinToString("") { "data: $it\n\n" })

    private fun openAiDelta(text: String) = """{"type":"response.output_text.delta","delta":"$text"}"""

    private fun openAiResponse(text: String) =
        sse(
            openAiDelta(text),
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","phase":"final_answer","content":[{"type":"output_text","text":"$text"}]}]}}""",
        )

    private fun geminiFrame(
        text: String,
        done: Boolean = true,
    ) =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}${if (done) ",\"finishReason\":\"STOP\"" else ""}}]}"""

    private fun held(frame: String) =
        sse(frame)
            .setBody("data: $frame\n\n:" + " ".repeat(1024 * 1024))
            .throttleBody(1024, 1, TimeUnit.SECONDS)

    // Observe real call/body lifetime; dispatcher callback count ends before SSE reader does.
    private fun track(provider: LlmProvider): Pair<AtomicReference<Call>, CompletableDeferred<Unit>> {
        val http =
            provider.javaClass
                .getDeclaredField("http")
                .apply { isAccessible = true }
                .get(provider)
        val field = http.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val active = AtomicReference<Call>()
        val ended = CompletableDeferred<Unit>()
        field.set(
            http,
            (field.get(http) as OkHttpClient)
                .newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            active.set(call)
                        }

                        override fun callEnd(call: Call) {
                            ended.complete(Unit)
                        }

                        override fun callFailed(
                            call: Call,
                            ioe: IOException,
                        ) {
                            ended.complete(Unit)
                        }
                    },
                ).build(),
        )
        return active to ended
    }

    private suspend fun assertConversation(
        id: String,
        count: Int,
    ) {
        val rows = app.database.chats().snapshot(id)
        assertEquals(count * 2, rows.size)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        assertEquals(prompts.take(count), rows.filter { it.role == "USER" }.map { it.rawContent })
        val assistants = rows.filter { it.role == "ASSISTANT" }
        assertEquals(answers.take(count), assistants.map { it.rawContent }) // exact text catches duplicate prefixes
        assertEquals(
            listOf(openAiId, openAiId, geminiId, geminiId, geminiId, geminiId).take(count),
            assistants.map {
                it.providerProfileId
            },
        )
        assertEquals(
            listOf(
                "openai-a",
                "openai-a",
                "gemini-a",
                "gemini-b",
                "gemini-b",
                "gemini-b",
            ).take(count),
            assistants.map {
                it.modelId
            },
        )
        assertEquals(
            listOf(
                "STOPPED",
                "SUCCESS",
                "SUCCESS",
                "SUCCESS",
                "STOPPED",
                "SUCCESS",
            ).take(count),
            assistants.map {
                it.status
            },
        )
        assertFalse(rows.any { it.status == "STREAMING" })
    }

    @com.nirowear.HostDrivenGate
    @Test fun combinedReleaseGate() =
        runBlocking {
            val phase = InstrumentationRegistry.getArguments().getString("lifecyclePhase")
            assumeTrue("Host-driven lifecycle gate", phase in setOf("seed-kill", "continue", "verify"))
            if (phase == "verify") {
                val id = probe.readText()
                assertConversation(id, 6)
                val openAi =
                    app.settings.state
                        .first()
                        .profiles
                        .single { it.id == openAiId }
                val local =
                    app.database.chats().openAiPhases(
                        id,
                        openAiId,
                        "openai-a",
                        Endpoints.normalize(openAi.baseUrl, true),
                    )
                assertEquals(1, local.size)
                val phase =
                    Json
                        .parseToJsonElement(local.single().phases)
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(answers[1].length, phase.getValue("length").jsonPrimitive.int)
                assertEquals("final_answer", phase.getValue("phase").jsonPrimitive.content)
                assertEquals(
                    app.database
                        .chats()
                        .snapshot(id)[3]
                        .id,
                    local.single().messageId,
                )
                assertEquals(
                    "gemini-b",
                    app.settings.state
                        .first()
                        .selected!!
                        .selectedModelId,
                )
                assertEquals(
                    "lifecycle-controlled-gemini-key",
                    app.secrets
                        .read(
                            app.settings.state
                                .first()
                                .selected!!
                                .credentialRef,
                        ).forRequest(),
                )
                app.database.chats().deleteConversation(id)
                app.settings.delete(openAiId)
                app.settings.delete(geminiId)
                check(probe.delete())
                return@runBlocking
            }
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    var vm: ChatViewModel? = null
                    var activity: android.app.Activity? = null
                    try {
                        if (phase == "seed-kill") {
                            if (probe.exists()) check(probe.delete())
                            app.settings.save(
                                ProviderProfile(
                                    openAiId,
                                    ProviderType.OPENAI,
                                    "Lifecycle OpenAI",
                                    first.url("/v1").toString(),
                                    "openai-a",
                                    openAiId,
                                ),
                                SecretValue("lifecycle-controlled-openai-key"),
                            ) { _, _ -> null }
                            // Explicit, because save() deliberately does not retarget the next send.
                            // Residue from an aborted earlier phase left the Gemini profile selected,
                            // and the send then went to a closed mock URL: measured requests=0 with
                            // "Cannot reach provider. Check connection."
                            app.settings.select(openAiId)
                        } else {
                            val saved =
                                app.settings.state
                                    .first()
                                    .profiles
                                    .single { it.id == openAiId }
                            app.settings.save(saved.copy(baseUrl = first.url("/v1").toString()), null)
                            app.settings.select(openAiId)
                        }
                        activity =
                            instrumentation.startActivitySync(
                                Intent(
                                    app,
                                    MainActivity::class.java,
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                            )
                        instrumentation.runOnMainSync {
                            vm =
                                ViewModelProvider(activity as MainActivity)[ChatViewModel::class.java]
                        }
                        val chat = vm!!
                        val ready =
                            ChatViewModel::class.java
                                .getDeclaredField("ready")
                                .apply {
                                    isAccessible = true
                                }.get(chat) as CompletableDeferred<*>
                        withTimeout(5000) { ready.await() }
                        if (phase == "seed-kill") {
                            val (activeCall, ended) = track(app.provider(ProviderType.OPENAI)!!)
                            first.enqueue(held(openAiDelta(answers[0])))
                            withContext(Dispatchers.Main) {
                                chat.newChat()
                                chat.send(prompts[0])
                            }
                            withTimeout(10000) { chat.state.first { it.conversationId != null } }
                            val id = chat.state.value.conversationId!!
                            try {
                                withTimeout(10000) {
                                    app.database.chats().messages(id).first {
                                        it.lastOrNull()?.rawContent ==
                                            answers[0]
                                    }
                                }
                            } catch (stuck: Throwable) {
                                println(
                                    "GATE seed-kill: no partial. requests=${first.requestCount} " +
                                        "state=${chat.state.value} " +
                                        "rows=" +
                                        app.database
                                            .chats()
                                            .snapshot(id)
                                            .map { "${it.role}:${it.status}:'${it.rawContent}'" },
                                )
                                throw stuck
                            }
                            assertEquals(
                                "STREAMING",
                                app.database
                                    .chats()
                                    .snapshot(id)
                                    .last()
                                    .status,
                            )
                            assertEquals(1, first.requestCount)
                            shell("input keyevent KEYCODE_HOME")
                            withTimeout(5000) {
                                while (withContext(Dispatchers.Main) {
                                        (activity as MainActivity).lifecycle.currentState.isAtLeast(
                                            Lifecycle.State.STARTED,
                                        )
                                    }
                                ) {
                                    delay(50)
                                }
                            }
                            assertTrue(chat.state.value.generating)
                            assertNotNull(activeCall.get())
                            assertFalse(activeCall.get().isCanceled())
                            assertFalse(ended.isCompleted)
                            probe.writeText(id)
                            println("LIFECYCLE_KILL_READY")
                            shell("am force-stop com.nirowear")
                            error("Expected process death")
                        }
                        val id = probe.readText()
                        withTimeout(5000) {
                            app.database
                                .chats()
                                .messages(id)
                                .first { it.lastOrNull()?.status == "STOPPED" }
                        }
                        withContext(Dispatchers.Main) { chat.open(id) }
                        assertFalse(chat.state.value.generating)
                        assertConversation(id, 1)
                        assertEquals(0, first.requestCount) // recovery has no network replay

                        suspend fun send(index: Int) {
                            withContext(Dispatchers.Main) { chat.send(prompts[index]) }
                            try {
                                withTimeout(10000) { chat.state.first { !it.generating } }
                            } catch (stuck: Throwable) {
                                println(
                                    "GATE send($index) never settled: ${chat.state.value} " +
                                        "firstRequests=${first.requestCount} secondRequests=${second.requestCount} " +
                                        "selected=${app.settings.state.first().selected?.id}",
                                )
                                throw stuck
                            }
                            assertNull(chat.state.value.error)
                            assertConversation(id, index + 1)
                        }
                        first.enqueue(openAiResponse(answers[1]))
                        send(1)
                        val openAiWire = first.takeRequest(3, TimeUnit.SECONDS)!!
                        assertEquals("/v1/responses", openAiWire.path)
                        val a = Json.parseToJsonElement(openAiWire.body.readUtf8()).jsonObject
                        assertEquals(
                            listOf(prompts[0], answers[0], prompts[1]),
                            a.getValue("input").jsonArray.map {
                                it.jsonObject
                                    .getValue("content")
                                    .jsonPrimitive.content
                            },
                        )
                        app.settings.save(
                            ProviderProfile(
                                geminiId,
                                ProviderType.GEMINI,
                                "Lifecycle Gemini",
                                second.url("/v1beta").toString(),
                                "gemini-a",
                                geminiId,
                            ),
                            SecretValue("lifecycle-controlled-gemini-key"),
                        ) { _, _ -> null }
                        // Saving is deliberately not selecting (ProviderSettings.save: "Selecting is a
                        // separate, explicit action"), so the switch to Gemini needs select() or the
                        // next send keeps going to the OpenAI profile. Measured: firstRequests went to
                        // 2 while secondRequests stayed 0 and send(2) never settled.
                        app.settings.select(geminiId)

                        suspend fun checkGemini(
                            index: Int,
                            model: String,
                        ) {
                            val wire = second.takeRequest(3, TimeUnit.SECONDS)!!
                            assertEquals("/v1beta/models/$model:streamGenerateContent?alt=sse", wire.path)
                            assertEquals("lifecycle-controlled-gemini-key", wire.getHeader("x-goog-api-key"))
                            assertNull(wire.getHeader("Authorization"))
                            val b = Json.parseToJsonElement(wire.body.readUtf8()).jsonObject
                            assertEquals(setOf("store", "systemInstruction", "contents"), b.keys)
                            val contents = b.getValue("contents").jsonArray
                            assertEquals(
                                (0 until index).flatMap { listOf(prompts[it], answers[it]) } + prompts[index],
                                contents.map {
                                    it.jsonObject
                                        .getValue(
                                            "parts",
                                        ).jsonArray
                                        .single()
                                        .jsonObject
                                        .getValue("text")
                                        .jsonPrimitive.content
                                },
                            )
                            contents.forEach { assertEquals(setOf("role", "parts"), it.jsonObject.keys) }
                        }
                        second.enqueue(sse(geminiFrame(answers[2])))
                        send(2)
                        checkGemini(2, "gemini-a")
                        app.settings.save(
                            app.settings.state
                                .first()
                                .selected!!
                                .copy(selectedModelId = "gemini-b"),
                            null,
                        )
                        second.enqueue(sse(geminiFrame(answers[3])))
                        send(3)
                        checkGemini(3, "gemini-b")
                        val (activeCall, ended) = track(app.provider(ProviderType.GEMINI)!!)
                        second.enqueue(held(geminiFrame(answers[4], false)))
                        withContext(Dispatchers.Main) { chat.send(prompts[4]) }
                        withTimeout(10000) {
                            app.database
                                .chats()
                                .messages(id)
                                .first { it.last().rawContent == answers[4] }
                        }
                        checkGemini(4, "gemini-b")
                        assertNotNull(activeCall.get())
                        assertFalse(ended.isCompleted)
                        withContext(Dispatchers.Main) { chat.stop() }
                        withTimeout(5000) {
                            chat.state.first { !it.generating }
                            ended.await()
                        }
                        assertTrue(activeCall.get().isCanceled())
                        assertConversation(id, 5)
                        second.enqueue(sse(geminiFrame(answers[5])))
                        send(5)
                        checkGemini(5, "gemini-b")
                        assertEquals(1, first.requestCount)
                        assertEquals(4, second.requestCount)
                    } finally {
                        instrumentation.runOnMainSync {
                            vm?.viewModelScope?.cancel()
                            activity?.finish()
                        }
                    }
                }
            }
        }
}
