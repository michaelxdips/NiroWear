package com.nirowear.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnthropicProviderTest {
    private val profile =
        ProviderProfile("id", ProviderType.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1", "selected", "ref")
    private val secret = SecretValue("anthropic-controlled-key")
    private val chat =
        ChatRequest(
            listOf(
                ChatMessage(ChatRole.USER, "First"),
                ChatMessage(ChatRole.ASSISTANT, "Old"),
                ChatMessage(ChatRole.USER, "Next"),
            ),
            "Instruction",
        )
    private val start = """{"type":"message_start","message":{"role":"assistant","content":[]}}"""
    private val block = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
    private val delta = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"α answer"}}"""
    private val endBlock = """{"type":"content_block_stop","index":0}"""

    private fun end(reason: String) = """{"type":"message_delta","delta":{"stop_reason":"$reason"}}"""

    private val stop = """{"type":"message_stop"}"""

    private fun sse(vararg data: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            data.joinToString("") {
                "data: $it\n\n"
            },
        )

    private fun metadata() = MockResponse().setBody("""{"id":"selected","max_tokens":32000}""")

    @Test fun paginatedDiscoveryRequestAndFullLifecycle() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                server.enqueue(
                    MockResponse().setBody("""{"data":[{"id":"selected"}],"has_more":true,"last_id":"selected"}"""),
                )
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"second"}],"has_more":false}"""))
                assertEquals(ModelDiscoveryResult.Models(listOf("second", "selected")), provider.listModels(p, secret))
                assertEquals("/v1/models?limit=1000", server.takeRequest().path)
                assertEquals("/v1/models?limit=1000&after_id=selected", server.takeRequest().path)
                server.enqueue(metadata())
                server.enqueue(sse(start, block, """{"type":"ping"}""", delta, endBlock, end("end_turn"), stop))
                assertEquals(
                    listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed),
                    provider.streamChat(chat, p, secret).toList(),
                )
                assertEquals("/v1/models/selected", server.takeRequest().path)
                val wire = server.takeRequest()
                assertEquals("/v1/messages", wire.path)
                assertEquals("anthropic-controlled-key", wire.getHeader("x-api-key"))
                assertNull("API keys authenticate with x-api-key, not a bearer token", wire.getHeader("Authorization"))
                assertEquals("2026-10-21", wire.getHeader("anthropic-version"))
                val body = Json.parseToJsonElement(wire.body.readUtf8()).jsonObject
                assertEquals(32000, body.getValue("max_tokens").jsonPrimitive.int)
                assertEquals("Instruction", body.getValue("system").jsonPrimitive.content)
                assertEquals(
                    listOf("user", "assistant", "user"),
                    body.getValue("messages").jsonArray.map {
                        it.jsonObject
                            .getValue("role")
                            .jsonPrimitive.content
                    },
                )
            }
        }

    @Test fun consecutiveSameRoleHistoryIsMergedOnTheWire() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val adjacent =
                    ChatRequest(
                        listOf(ChatMessage(ChatRole.USER, "first"), ChatMessage(ChatRole.USER, "second")),
                        "Instruction",
                    )
                server.enqueue(metadata())
                server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                assertEquals(
                    StreamEvent.Completed,
                    AnthropicProvider(allowLocalHttp = true).streamChat(adjacent, p, secret).last(),
                )
                server.takeRequest()
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val messages = body.getValue("messages").jsonArray
                assertEquals(
                    listOf("user"),
                    messages.map {
                        it.jsonObject
                            .getValue("role")
                            .jsonPrimitive.content
                    },
                )
                assertEquals(
                    "first\nsecond",
                    messages[0]
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content,
                )
            }
        }

    @Test fun truncatedMalformedWrongOrderLimitAndExplicitRetry() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                // ONE metadata answer for the whole provider: the cap is cached per endpoint+model, so
                // the turn after the first must not pay for it again.
                server.enqueue(metadata())
                for (data in listOf(
                    arrayOf(start, block, delta),
                    arrayOf("{"),
                    arrayOf(stop),
                    arrayOf(start, block, delta, stop),
                )) {
                    server.enqueue(sse(*data))
                    assertTrue(provider.streamChat(chat, p, secret).last() is StreamEvent.Failure)
                }
                // max_tokens is a documented StopReason, so a capped answer TERMINATES with the text
                // the provider sent. It used to be asserted as a FAILURE, which was wrong: the turn was
                // stored as ERROR and rendered "Stream interrupted" over a good answer. It is now
                // Truncated, not Completed: the text is kept AND the reader is told the answer reached
                // the output cap, instead of being shown a cut answer as if it were whole.
                server.enqueue(sse(start, block, delta, endBlock, end("max_tokens"), stop))
                assertEquals(StreamEvent.Truncated, provider.streamChat(chat, p, secret).last())
                assertEquals(6, server.requestCount)
                server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                assertEquals(StreamEvent.Completed, provider.streamChat(chat, p, secret).last())
            }
        }

    @Test fun safeErrorsAndPaginationLoop() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                server.enqueue(metadata())
                for ((code, error) in mapOf(
                    401 to ProviderError.INVALID_CREDENTIAL,
                    403 to ProviderError.PERMISSION_DENIED,
                    404 to ProviderError.MODEL_UNAVAILABLE,
                    429 to ProviderError.RATE_LIMITED,
                    529 to ProviderError.SERVER,
                )) {
                    server.enqueue(MockResponse().setResponseCode(code).setBody("anthropic-controlled-key"))
                    assertEquals(StreamEvent.Failure(error), provider.streamChat(chat, p, secret).last())
                }
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.SERVER)),
                    AnthropicStreamDecoder().accept(
                        """{"type":"error","error":{"type":"overloaded_error","message":"anthropic-controlled-key"}}""",
                    ),
                )
                repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[],"has_more":true,"last_id":"loop"}""")) }
                assertEquals(ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL), provider.listModels(p, secret))
            }
        }

    @Test
    fun aMissingStopReasonIsNotAFramingFault() {
        // The SDK types stop_reason as `StopReason | null`, so a gateway can omit it. Requiring a
        // known reason relabelled a fully delivered answer "Stream interrupted or unsupported" with
        // the whole text underneath it; but text IS the proof when the reason is absent, because a
        // stream that ends here having said nothing proves nothing (it used to be saved as a
        // permanently blank SUCCESS row).
        val decoder = AnthropicStreamDecoder()
        val events =
            listOf(start, block, delta, endBlock).flatMap { decoder.accept(it) } +
                decoder.accept("""{"type":"message_delta","delta":{"stop_reason":null}}""") +
                decoder.accept(stop)
        assertEquals(listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed), events)
        val silent = AnthropicStreamDecoder()
        silent.accept(start)
        silent.accept("""{"type":"message_delta","delta":{"stop_reason":null}}""")
        assertEquals(listOf(StreamEvent.Failure(ProviderError.PROTOCOL)), silent.accept(stop))
    }

    @Test
    fun aThinkingBlockKeepsTheStreamAliveInsteadOfLookingDead() {
        // Measured after the idle deadline was changed to advance only on frames that DECODE to an
        // event: a thinking block decodes to nothing, so a long think looked like a dead socket and
        // the turn was killed as "Provider timed out" with an empty answer.
        val decoder = AnthropicStreamDecoder()
        decoder.accept(start)
        assertEquals(
            listOf(StreamEvent.Working),
            decoder.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""),
        )
        assertEquals(
            listOf(StreamEvent.Working),
            decoder.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}"""),
        )
        // A keepalive is still NOT progress: the bound exists to outlive exactly this.
        assertEquals(emptyList<StreamEvent>(), decoder.accept("""{"type":"ping"}"""))
    }

    @Test
    fun aPausedOrToolUsingAnswerIsDisclosedAsCut() {
        // pause_turn asks the client to resend and continue; this client never does. tool_use stops to
        // call a tool that is never rendered or executed. Both left the answer stored SUCCESS with no
        // status line, while Gemini's CONTINUATION was already reported as Truncated.
        for (reason in listOf("pause_turn", "tool_use")) {
            val decoder = AnthropicStreamDecoder()
            val events =
                listOf(start, block, delta, endBlock).flatMap { decoder.accept(it) } +
                    decoder.accept(end(reason)) +
                    decoder.accept(stop)
            assertEquals(
                "$reason must not read as a finished answer",
                listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Truncated),
                events,
            )
        }
    }

    @Test
    fun modelMetadataIsFetchedOncePerProcess() =
        runBlocking {
            // Measured before the cache: this GET ran inside every streamChat, so a watch paid a radio
            // wakeup per send before the first token, and a hanging metadata host cost the whole
            // deadline each turn.
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                server.enqueue(metadata())
                server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                // Spare answers on purpose: a run that fetches metadata EVERY turn must fail the count
                // assertion below instead of blocking on an empty queue.
                repeat(3) { server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop)) }
                repeat(2) {
                    assertEquals(
                        listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed),
                        provider.streamChat(chat, p, secret).toList(),
                    )
                }
                assertEquals("metadata is a per-model cost, not a per-turn one", 3, server.requestCount)
            }
        }

    @Test
    fun theErrorBodyReasonBeatsTheBareStatus() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                server.enqueue(metadata())
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
                )
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"bad field"}}"""),
                )
                assertEquals(
                    "a 400 that names the credential must not read as a configuration problem",
                    listOf(StreamEvent.Failure(ProviderError.INVALID_CREDENTIAL)),
                    provider.streamChat(chat, p, secret).toList(),
                )
                assertEquals(
                    "an unknown reason leaves the status mapping alone",
                    listOf(StreamEvent.Failure(ProviderError.HTTP)),
                    provider.streamChat(chat, p, secret).toList(),
                )
                assertEquals(ProviderError.SERVER, anthropicHttpError("""{"error":{"type":"overloaded_error"}}"""))
                assertEquals("no error object, no opinion", null, anthropicHttpError("<html>portal</html>"))
            }
        }

    @Test fun metadataFailureFallsBackButStillGenerates() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                for (failure in listOf(
                    MockResponse().setResponseCode(404),
                    MockResponse().setBody("""{"not":"a number"}"""),
                    MockResponse().setBody("""{"max_tokens":0}"""),
                )) {
                    server.enqueue(failure)
                    server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                    assertEquals(
                        "metadata failure must not block generation",
                        listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed),
                        provider.streamChat(chat, p, secret).toList(),
                    )
                    server.takeRequest()
                    assertEquals(
                        4096,
                        Json
                            .parseToJsonElement(
                                server.takeRequest().body.readUtf8(),
                            ).jsonObject
                            .getValue("max_tokens")
                            .jsonPrimitive.int,
                    )
                }
                // A real credential failure still fails on the messages call itself.
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(401).setBody("anthropic-controlled-key"))
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.INVALID_CREDENTIAL)),
                    provider.streamChat(chat, p, secret).toList(),
                )
            }
        }

    @Test fun malformedDiscoveryEntryDoesNotDiscardValidModels() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val overlong = "x".repeat(257)
                server.enqueue(
                    MockResponse().setBody(
                        """{"data":[{"id":"alpha"},{"no_id":true},{"id":null},{"id":""},{"id":"$overlong"},{"id":"beta"}],"has_more":false}""",
                    ),
                )
                assertEquals(
                    ModelDiscoveryResult.Models(listOf("alpha", "beta")),
                    AnthropicProvider(allowLocalHttp = true).listModels(p, secret),
                )
            }
        }

    @Test fun cancellationClosesNativeHttp() =
        runBlocking {
            MockWebServer().use { server ->
                val client = providerHttpClient()
                server.enqueue(metadata())
                server.enqueue(
                    sse(start, block, delta)
                        .setBody(
                            listOf(start, block, delta).joinToString("") {
                                "data: $it\n\n"
                            } + " ".repeat(16384),
                        ).throttleBody(1024, 1, TimeUnit.SECONDS),
                )
                val started = CompletableDeferred<Unit>()
                val job =
                    launch {
                        AnthropicProvider(
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

    @Test fun cancellingDuringMetadataRequestDoesNotStartGeneration() =
        runBlocking {
            // Mandate E (B3-07): "Pastikan cancel tidak memulai request generation baru."
            // Metadata is best-effort, so a cancel that lands while it is in flight must not
            // fall through the fallback and fire a /v1/messages generation request.
            MockWebServer().use { server ->
                val client = providerHttpClient()
                // 1 byte per 5 s keeps the metadata response in flight long enough to cancel.
                server.enqueue(metadata().throttleBody(1, 5, TimeUnit.SECONDS))
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val job = launch { AnthropicProvider(client, true).streamChat(chat, p, secret).collect { } }
                withTimeout(10000) { while (server.requestCount < 1) delay(10) }
                job.cancelAndJoin()
                // Give any erroneous follow-up request a chance to appear before asserting.
                delay(500)
                assertEquals("only the metadata request may have been issued", 1, server.requestCount)
                assertEquals("/v1/models/selected", server.takeRequest().path)
            }
        }

    @Test fun metadataReadTimeoutFallsBackAndStillGenerates() =
        runBlocking {
            // Mandate E (B3-07): "Uji metadata timeout/failure ... generation failure setelah
            // metadata fallback." A metadata call that times out must fall back to the default
            // budget and still generate, rather than hanging or surfacing TIMEOUT to the user.
            // Production uses a 90 s read timeout, which is impractical in a unit test, so the
            // client is rebuilt with the same shape and a short read timeout. The path exercised
            // (SocketTimeoutException -> networkError -> fallback) is the production one.
            MockWebServer().use { server ->
                val client =
                    providerHttpClient()
                        .newBuilder()
                        .readTimeout(250, TimeUnit.MILLISECONDS)
                        .build()
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                server.enqueue(metadata().setBodyDelay(3, TimeUnit.SECONDS))
                server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                assertEquals(
                    "a metadata timeout must not block generation",
                    listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed),
                    AnthropicProvider(client, true).streamChat(chat, p, secret).toList(),
                )
                server.takeRequest()
                assertEquals(
                    4096,
                    Json
                        .parseToJsonElement(server.takeRequest().body.readUtf8())
                        .jsonObject
                        .getValue("max_tokens")
                        .jsonPrimitive.int,
                )
            }
        }

    @Test fun metadataConnectionFailureFallsBackAndStillGenerates() =
        runBlocking {
            // Mandate E (B3-07): connection-level failure of the optional metadata call.
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = AnthropicProvider(allowLocalHttp = true)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
                server.enqueue(sse(start, block, delta, endBlock, end("end_turn"), stop))
                assertEquals(
                    listOf(StreamEvent.TextDelta("α answer"), StreamEvent.Completed),
                    provider.streamChat(chat, p, secret).toList(),
                )
                server.takeRequest()
                assertEquals(
                    4096,
                    Json
                        .parseToJsonElement(server.takeRequest().body.readUtf8())
                        .jsonObject
                        .getValue("max_tokens")
                        .jsonPrimitive.int,
                )
            }
        }
}
