package com.nirowear.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiProviderTest {
    private val profile =
        ProviderProfile("id", ProviderType.OPENAI, "Native", "https://api.openai.com/v1", "selected", "ref")
    private val secret = SecretValue("native-controlled-key")
    private val chat =
        ChatRequest(
            listOf(
                ChatMessage(ChatRole.USER, "First"),
                ChatMessage(ChatRole.ASSISTANT, "Old α"),
                ChatMessage(ChatRole.USER, "Next"),
            ),
            "Instruction",
        )

    private fun delta(text: String) = """{"type":"response.output_text.delta","delta":"$text"}"""

    private fun complete(text: String) =
        """{"type":"response.completed","response":{"id":"resp","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"$text"}]}]}}"""

    private fun sse(vararg data: String) =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(data.joinToString("") { "data: $it\n\n" })

    @Test fun serializationDiscoveryAndSemanticCompletion() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = OpenAiProvider(allowLocalHttp = true)
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"selected"},{"id":"other"}]}"""))
                assertEquals(ModelDiscoveryResult.Models(listOf("other", "selected")), provider.listModels(p, secret))
                assertEquals("/v1/models", server.takeRequest().path)
                // Official event list emits response.queued; it must not abort an otherwise valid stream.
                server.enqueue(
                    sse(
                        """{"type":"response.created","response":{"id":"resp"}}""",
                        """{"type":"response.queued","response":{"id":"resp"}}""",
                        delta("α answer"),
                        complete("α answer"),
                    ),
                )
                assertEquals(
                    listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed),
                    provider.streamChat(chat, p, secret).toList(),
                )
                val wire = server.takeRequest()
                assertEquals("/v1/responses", wire.path)
                assertEquals("Bearer native-controlled-key", wire.getHeader("Authorization"))
                val body = Json.parseToJsonElement(wire.body.readUtf8()).jsonObject
                assertEquals("selected", body.getValue("model").jsonPrimitive.content)
                assertFalse(body.getValue("store").jsonPrimitive.boolean)
                assertEquals("Instruction", body.getValue("instructions").jsonPrimitive.content)
                assertEquals(
                    listOf("user", "assistant", "user"),
                    body.getValue("input").jsonArray.map {
                        it.jsonObject
                            .getValue("role")
                            .jsonPrimitive.content
                    },
                )
                assertFalse(body.containsKey("messages"))
                assertFalse(body.containsKey("previous_response_id"))
            }
        }

    @Test fun malformedTruncatedIncompleteAndExplicitRetry() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = OpenAiProvider(allowLocalHttp = true)
                for (terminal in listOf(
                    "",
                    "[DONE]",
                    "{",
                    complete("different"),
                )) {
                    server.enqueue(if (terminal.isEmpty()) sse(delta("partial")) else sse(delta("partial"), terminal))
                    val events = provider.streamChat(chat, p, secret).toList()
                    assertEquals(StreamEvent.TextDelta("partial"), events.first())
                    assertTrue(events.last() is StreamEvent.Failure)
                    assertFalse(events.contains(StreamEvent.Completed))
                }
                // response.incomplete is a documented terminal (output cap / content filter) and is
                // no longer reported as an interruption: the text that arrived is kept. It is
                // Truncated rather than Completed, so the reader is told the answer was cut at the
                // output cap instead of being shown a cut answer as if it were whole.
                server.enqueue(sse(delta("partial"), """{"type":"response.incomplete"}"""))
                assertEquals(
                    listOf(StreamEvent.TextDelta("partial"), StreamEvent.Truncated),
                    provider.streamChat(chat, p, secret).toList(),
                )
                // The documented reason decides the classification: a CONTENT-FILTERED incomplete is
                // policy, not a length cap, and reporting it as "cut at the output limit" was wrong.
                server.enqueue(
                    sse(
                        delta("partial"),
                        """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"content_filter"}}}""",
                    ),
                )
                assertEquals(
                    ProviderError.PROMPT_BLOCKED,
                    (provider.streamChat(chat, p, secret).last() as StreamEvent.Failure).error,
                )
                // An incomplete with NO text is not a kept answer with a note: nothing to show.
                server.enqueue(sse("""{"type":"response.incomplete"}"""))
                assertEquals(
                    ProviderError.PROTOCOL,
                    (provider.streamChat(chat, p, secret).last() as StreamEvent.Failure).error,
                )
                assertEquals(7, server.requestCount)
                server.enqueue(sse(delta("retry"), complete("retry")))
                assertEquals(StreamEvent.Completed, provider.streamChat(chat, p, secret).last())
                assertEquals(8, server.requestCount)
            }
        }

    @Test fun safeHttpAndInStreamErrors() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = OpenAiProvider(allowLocalHttp = true)
                for ((code, error) in mapOf(
                    401 to ProviderError.INVALID_CREDENTIAL,
                    403 to ProviderError.PERMISSION_DENIED,
                    404 to ProviderError.MODEL_UNAVAILABLE,
                    429 to ProviderError.RATE_LIMITED,
                    500 to ProviderError.SERVER,
                )) {
                    server.enqueue(MockResponse().setResponseCode(code).setBody("native-controlled-key"))
                    assertEquals(listOf(StreamEvent.Failure(error)), provider.streamChat(chat, p, secret).toList())
                }
                val decoder = OpenAiStreamDecoder()
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.RATE_LIMITED)),
                    decoder.accept(
                        """{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"native-controlled-key"}}}""",
                    ),
                )
                // ResponseOutputItem is a large union. An item type this client does not render is
                // informational and must NOT abort the answer; only the completed-text equality
                // check has the authority to reject a stream. These used to assert PROTOCOL.
                assertEquals(
                    emptyList<StreamEvent>(),
                    decoder.accept("""{"type":"response.output_item.added","item":{"type":"function_call"}}"""),
                )
                assertEquals(
                    emptyList<StreamEvent>(),
                    decoder.accept("""{"type":"response.output_item.added","item":{"type":"web_search_call"}}"""),
                )
            }
        }

    @Test fun cancellationClosesNativeHttp() =
        runBlocking {
            MockWebServer().use { server ->
                val client = providerHttpClient()
                server.enqueue(
                    sse(
                        delta("partial"),
                    ).setBody(
                        "data: ${delta("partial")}\n\n" + " ".repeat(16384),
                    ).throttleBody(1024, 1, TimeUnit.SECONDS),
                )
                val started = CompletableDeferred<Unit>()
                val job =
                    launch {
                        OpenAiProvider(
                            client,
                            true,
                        ).streamChat(chat, profile.copy(baseUrl = server.url("/v1").toString()), secret).collect {
                            started.complete(Unit)
                        }
                    }
                withTimeout(10000) { started.await() }
                job.cancelAndJoin()
                withTimeout(3000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
                assertEquals(0, client.dispatcher.runningCallsCount())
            }
        }

    @Test fun reasoningAndPhaseStayOutsidePortableText() {
        val decoder = OpenAiStreamDecoder()
        val privateFrames =
            listOf(
                """{"type":"response.output_item.added","item":{"type":"reasoning","encrypted_content":"incomplete-private"}}""",
                """{"type":"response.reasoning_summary_part.added","part":{"type":"summary_text","text":"private summary"}}""",
                """{"type":"response.reasoning_summary_text.delta","delta":"hidden reasoning"}""",
                """{"type":"response.reasoning_summary_text.done","text":"hidden reasoning"}""",
                """{"type":"response.reasoning_summary_part.done","part":{"type":"summary_text","text":"private summary"}}""",
                """{"type":"response.reasoning_text.delta","delta":"hidden reasoning"}""",
                """{"type":"response.reasoning_text.done","text":"hidden reasoning"}""",
                """{"type":"response.output_item.done","item":{"type":"reasoning","encrypted_content":"completed-private"}}""",
                """{"type":"response.output_item.added","item":{"type":"message","phase":"commentary"}}""",
            )
        // Private frames carry no content. Some are proof of life (a reasoning model can stream them
        // for minutes) and some are pure bookkeeping: what must never happen is text leaking.
        privateFrames.forEach {
            assertTrue(it, decoder.accept(it).all { event -> event is StreamEvent.Working })
        }
        assertEquals(listOf(StreamEvent.TextDelta("Visible update. ")), decoder.accept(delta("Visible update. ")))
        assertTrue(
            decoder
                .accept(
                    """{"type":"response.output_item.done","item":{"type":"message","phase":"commentary"}}""",
                ).isEmpty(),
        )
        assertTrue(
            decoder
                .accept(
                    """{"type":"response.output_item.added","item":{"type":"message","phase":"final_answer"}}""",
                ).isEmpty(),
        )
        assertEquals(listOf(StreamEvent.TextDelta("Visible answer.")), decoder.accept(delta("Visible answer.")))
        assertEquals(
            listOf(
                StreamEvent.OpenAiPhases(listOf(OpenAiPhase(16, "commentary"), OpenAiPhase(15, "final_answer"))),
                StreamEvent.Completed,
            ),
            decoder.accept(
                """{"type":"response.completed","response":{"status":"completed","reasoning":{"context":"all_turns"},"output":[{"type":"reasoning","encrypted_content":"completed-private","summary":[{"text":"hidden reasoning"}]},{"type":"message","role":"assistant","status":"completed","phase":"commentary","content":[{"type":"output_text","text":"Visible update. "}]},{"type":"message","role":"assistant","status":"completed","phase":"final_answer","content":[{"type":"output_text","text":"Visible answer."}]}]}}""",
            ),
        )
    }

    @Test fun phaseReplayIsScopedAndMalformedPhaseFailsClosed() =
        runBlocking {
            for (phase in listOf("42", "{}", "\"unknown\"")) {
                val decoder = OpenAiStreamDecoder()
                decoder.accept(delta("answer"))
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
                    decoder.accept(
                        complete("answer").replace("\"role\":\"assistant\"", "\"phase\":$phase,\"role\":\"assistant\""),
                    ),
                )
            }
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = OpenAiProvider(allowLocalHttp = true)
                val history =
                    listOf(
                        ChatMessage(ChatRole.USER, "First"),
                        ChatMessage(ChatRole.ASSISTANT, "Update.Answer."),
                        ChatMessage(ChatRole.USER, "Next"),
                    )
                val local =
                    OpenAiContinuation(
                        p.id,
                        p.selectedModelId,
                        Endpoints.normalize(p.baseUrl, true),
                        mapOf(1 to listOf(OpenAiPhase(7, "commentary"), OpenAiPhase(7, "final_answer"))),
                    )
                for (scope in listOf(
                    local,
                    null,
                    local.copy(profileId = "other"),
                    local.copy(modelId = "other"),
                    local.copy(baseUrl = "https://elsewhere.test/v1"),
                )) {
                    server.enqueue(sse(delta("next"), complete("next")))
                    assertEquals(
                        StreamEvent.Completed,
                        provider.streamChat(ChatRequest(history, openAiContinuation = scope), p, secret).last(),
                    )
                    val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val input = body.getValue("input").jsonArray
                    assertFalse(body.toString().contains("encrypted_content"))
                    if (scope == local) {
                        assertEquals(
                            listOf("commentary", "final_answer"),
                            input
                                .filter {
                                    it.jsonObject["phase"] != null
                                }.map {
                                    it.jsonObject
                                        .getValue("phase")
                                        .jsonPrimitive.content
                                },
                        )
                        assertEquals(
                            listOf("First", "Update.", "Answer.", "Next"),
                            input.map {
                                it.jsonObject
                                    .getValue("content")
                                    .jsonPrimitive.content
                            },
                        )
                    } else {
                        assertEquals(3, input.size)
                        assertFalse(body.toString().contains("phase"))
                    }
                }
                assertTrue(
                    runCatching {
                        provider.streamChat(
                            ChatRequest(
                                history,
                                openAiContinuation =
                                    local.copy(
                                        phases =
                                            mapOf(1 to listOf(OpenAiPhase(1, "commentary"))),
                                    ),
                            ),
                            p,
                            secret,
                        )
                    }.isFailure,
                )
                val unicode = listOf(ChatMessage(ChatRole.ASSISTANT, "😀answer"))
                assertTrue(
                    runCatching {
                        provider.streamChat(
                            ChatRequest(
                                unicode,
                                openAiContinuation =
                                    local.copy(
                                        phases =
                                            mapOf(
                                                0 to
                                                    listOf(
                                                        OpenAiPhase(1, "commentary"),
                                                        OpenAiPhase(7, "final_answer"),
                                                    ),
                                            ),
                                    ),
                            ),
                            p,
                            secret,
                        )
                    }.isFailure,
                )
                assertEquals(5, server.requestCount)
            }
        }

    @Test fun malformedDiscoveryEntryDoesNotDiscardValidModels() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                server.enqueue(
                    MockResponse().setBody(
                        """{"data":[{"id":"alpha"},{"no_id":true},{"id":null},{"id":""},{"id":""" + "x".repeat(257) +
                            """},{"id":"beta"}]}""",
                    ),
                )
                assertEquals(
                    ModelDiscoveryResult.Models(listOf("alpha", "beta")),
                    OpenAiProvider(allowLocalHttp = true).listModels(p, secret),
                )
                server.enqueue(MockResponse().setBody("""{"data":[{"id":null}]}"""))
                assertEquals(
                    ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                    OpenAiProvider(allowLocalHttp = true).listModels(p, secret),
                )
            }
        }

    @Test fun unknownResponseEventDoesNotAbortButUnknownNamespaceStaysFatal() {
        val decoder = OpenAiStreamDecoder()
        assertEquals(
            emptyList<StreamEvent>(),
            decoder.accept("""{"type":"response.created","response":{"id":"resp"}}"""),
        )
        assertEquals(
            emptyList<StreamEvent>(),
            decoder.accept("""{"type":"response.some_future_event","response":{"id":"resp"}}"""),
        )
        assertEquals(listOf(StreamEvent.TextDelta("answer")), decoder.accept(delta("answer")))
        assertEquals(listOf(StreamEvent.Completed), decoder.accept(complete("answer")))
        // Text carried by an ignored event cannot hide: completion restates it and the equality holds.
        val tampered = OpenAiStreamDecoder()
        tampered.accept(delta("partial"))
        assertEquals(
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
            tampered.accept(complete("partial plus hidden")),
        )
        assertEquals(
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
            OpenAiStreamDecoder().accept("""{"type":"totally_unknown"}"""),
        )
    }

    @Test
    fun reasoningDeltasAreProofOfLifeButNotContent() {
        // A reasoning model streams these for as long as it thinks, before any visible token. They
        // decode to no content, and turning them into an EMPTY list made the idle deadline treat a
        // thinking model as a dead socket.
        val decoder = OpenAiStreamDecoder()
        for (frame in
            listOf(
                """{"type":"response.reasoning_text.delta","delta":"thinking"}""",
                """{"type":"response.reasoning_summary_text.delta","delta":"summary"}""",
            )) {
            assertEquals("$frame must count as progress", listOf(StreamEvent.Working), decoder.accept(frame))
        }
    }
    @Test
    fun aBodyCodeBeatsTheBareStatus() =
        runBlocking {
            // A 400 that names the key read as "Provider rejected request. Check configuration." because
            // the status was reported before the body was read.
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"error":{"code":"invalid_api_key","message":"bad key"}}"""),
                )
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.INVALID_CREDENTIAL)),
                    OpenAiProvider(allowLocalHttp = true).streamChat(chat, p, secret).toList(),
                )
            }
            assertEquals(ProviderError.RATE_LIMITED, openAiHttpError("""{"error":{"code":"insufficient_quota"}}"""))
            assertEquals("a body this build cannot read leaves the status alone", null, openAiHttpError("""{"ok":true}"""))
            assertEquals("an unrecognised code is not a guess", null, openAiHttpError("""{"error":{"code":"weird"}}"""))
        }

    @Test fun duplicateTerminalEventCompletesExactlyOnce() =
        runBlocking {
            // Mandate E: "Duplicate terminal event" and "fallback tidak ... menyebabkan write ganda."
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = OpenAiProvider(allowLocalHttp = true)
                server.enqueue(sse(delta("answer"), complete("answer"), complete("answer")))
                val events = provider.streamChat(chat, p, secret).toList()
                assertEquals(
                    "a repeated terminal event must not emit Completed twice",
                    listOf(StreamEvent.TextDelta("answer"), StreamEvent.Completed),
                    events,
                )
            }
        }

    @Test fun jsonNullIncompleteDetailsDoesNotThrowProtocolError() {
        val decoder = OpenAiStreamDecoder()
        decoder.accept(delta("partial"))
        assertEquals(
            listOf(StreamEvent.Truncated),
            decoder.accept("""{"type":"response.incomplete","response":{"incomplete_details":null}}"""),
        )
    }
}
