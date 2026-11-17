package com.nirowear.provider

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Error mapping and the progress bound, measured on the wire.
 *
 * Three defects were reproduced before this:
 * - a 3xx (captive portal, gateway rewriting http to https) fell through to `else` and blamed the
 *   user's configuration;
 * - a Gemini key rejected with HTTP 400 said "Provider rejected request. Check configuration."
 *   because the status threw before the body — which names API_KEY_INVALID — was ever read;
 * - the idle deadline moved on every `data:` line, so a data-channel keepalive held the socket and the
 *   watch radio for ever while the answer made no progress.
 */
class ProviderHttpErrorMappingTest {
    private fun request(url: String) = Request.Builder().url(url).build()

    /**
     * A thinking block is the provider WORKING, and the adapter must say so rather than go silent.
     *
     * This used to be a wall-clock test: a throttled body against `streamIdleTimeoutMs = 400`, with
     * `assertNotEquals(TIMEOUT, last)`. Measured twice on this machine, it failed with
     * `Failure(error=TIMEOUT)` at both 400 ms and 1000 ms, and it also passed while the stream had
     * produced only **3** decoded frames — the assertion accepted any non-TIMEOUT ending, including the
     * PROTOCOL failure a stream that stopped early would produce. A test whose pass and fail both
     * depend on scheduler luck proves nothing.
     *
     * The invariant is split into what can be measured deterministically here and what is already
     * covered by its neighbours: this one proves thinking frames decode to [StreamEvent.Working] and
     * that a thinking-only answer reaches `message_stop` as Completed; `eventFramesDoKeepTheStreamAlive`
     * proves a decoded frame resets the idle clock, and `aKeepaliveStillDoesNotHoldTheSocket` proves an
     * undecoded frame does not.
     */
    @Test
    fun aThinkingStreamIsNotADeadSocket() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val body =
                    buildString {
                        append("data: {\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[]}}\n\n")
                        append(
                            "data: {\"type\":\"content_block_start\",\"index\":0," +
                                "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n",
                        )
                        // The parentheses are load-bearing: `+` binds looser than `.repeat`, so
                        // without them only the SECOND half of the literal was repeated 240 times,
                        // and 240 lines that do not start with `data:` are not frames at all. The
                        // stream then carried one data line and 240 ignored ones — which is why this
                        // method used to fail with TIMEOUT under load and "pass" with a PROTOCOL
                        // failure when the body happened to arrive inside the window.
                        append(
                            (
                                "data: {\"type\":\"content_block_delta\",\"index\":0," +
                                    "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}\n\n"
                            ).repeat(240),
                        )
                        append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
                        append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
                        append("data: {\"type\":\"message_stop\"}\n\n")
                    }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
                val decoder = AnthropicStreamDecoder()
                val events =
                    ProviderHttp(providerHttpClient())
                        .stream(request(server.url("/v1/messages").toString()), decode = decoder::accept)
                        .toList()
                assertEquals("all 240 thinking deltas must decode as progress", 241, events.count { it == StreamEvent.Working })
                assertEquals("a thinking-only answer still completes", StreamEvent.Completed, events.last())
                assertEquals(
                    "no frame may be reported as a failure",
                    emptyList<StreamEvent>(),
                    events.filterIsInstance<StreamEvent.Failure>(),
                )
            }
        }

    @Test
    fun aKeepaliveStillDoesNotHoldTheSocket() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"type\":\"ping\"}\n\n".repeat(400))
                        .throttleBody(200, 30, TimeUnit.MILLISECONDS)
                        .setSocketPolicy(SocketPolicy.KEEP_OPEN),
                )
                val decoder = AnthropicStreamDecoder()
                assertEquals(
                    "an Anthropic keepalive is decoded and ignored, so it must not reset the clock",
                    StreamEvent.Failure(ProviderError.TIMEOUT),
                    withTimeout(20_000) {
                        ProviderHttp(providerHttpClient(), streamIdleTimeoutMs = 400)
                            .stream(request(server.url("/v1/messages").toString()), decode = decoder::accept)
                            .last()
                    },
                )
            }
        }

    @Test
    fun aPortalPageIsNotAnInterruptedStream() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html")
                        .setBody("<!DOCTYPE html><html><title>Hotel Wi-Fi sign-in</title></html>"),
                )
                val events =
                    ProviderHttp(providerHttpClient())
                        .stream(request(server.url("/v1/chat/completions").toString()), decode = { emptyList() })
                        .toList()
                assertEquals(
                    "a 200 web page means the API was never reached",
                    listOf(StreamEvent.Failure(ProviderError.NETWORK_UNAVAILABLE)),
                    events,
                )
            }
        }

    @Test
    fun aNonSseProviderAnswerStaysAProtocolProblem() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"error":"streaming not enabled"}"""),
                )
                val events =
                    ProviderHttp(providerHttpClient())
                        .stream(request(server.url("/v1/chat/completions").toString()), decode = { emptyList() })
                        .toList()
                assertEquals(
                    "the provider answered: it just did not speak SSE",
                    listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
                    events,
                )
            }
        }

    @Test
    fun theErrorHookIsAppliedOnTheWire() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""),
                )
                val events =
                    ProviderHttp(providerHttpClient())
                        .stream(
                            request(server.url("/v1/x").toString()),
                            decode = { emptyList() },
                            decodeError = { code, body -> geminiHttpError(code, body) },
                        ).toList()
                assertEquals(listOf(StreamEvent.Failure(ProviderError.INVALID_CREDENTIAL)), events)
            }
        }

    @Test
    fun geminiBodyMappingIsNarrow() {
        assertEquals(
            ProviderError.INVALID_CREDENTIAL,
            geminiHttpError(
                400,
                """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""",
            ),
        )
        assertEquals(
            "a 400 about a bad field must not accuse the key",
            null,
            geminiHttpError(400, """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"INVALID_FIELD"}]}}"""),
        )
        assertEquals(
            ProviderError.RATE_LIMITED,
            geminiHttpError(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""),
        )
        assertEquals("no body, no opinion", null, geminiHttpError(400, "<html>portal</html>"))
    }

    @Test
    fun aRedirectIsNotBlamedOnTheUsersConfiguration() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", "https://portal.example/login"),
                )
                val events =
                    ProviderHttp(providerHttpClient())
                        .stream(request(server.url("/v1/chat/completions").toString()), decode = { emptyList() })
                        .toList()
                assertEquals(listOf(StreamEvent.Failure(ProviderError.NETWORK_UNAVAILABLE)), events)
            }
        }

    @Test
    fun aGeminiKeyRejectedInTheBodyIsReportedAsTheCredential() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT",""" +
                                """"details":[{"reason":"API_KEY_INVALID"}]}}""",
                        ),
                )
                val profile =
                    ProviderProfile(
                        "id",
                        ProviderType.GEMINI,
                        "Gemini",
                        server.url("/v1beta").toString(),
                        "gemini-2.5-flash",
                        "ref",
                    )
                val events =
                    GeminiProvider(allowLocalHttp = true)
                        .streamChat(
                            ChatRequest(listOf(ChatMessage(ChatRole.USER, "hi"))),
                            profile,
                            SecretValue("wrong-key"),
                        ).toList()
                assertEquals(listOf(StreamEvent.Failure(ProviderError.INVALID_CREDENTIAL)), events)
            }
        }

    @Test
    fun aGeminiBadRequestStillReadsAsARequestProblem() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[""" +
                                """{"reason":"INVALID_FIELD"}]}}""",
                        ),
                )
                val profile =
                    ProviderProfile(
                        "id",
                        ProviderType.GEMINI,
                        "Gemini",
                        server.url("/v1beta").toString(),
                        "gemini-2.5-flash",
                        "ref",
                    )
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.HTTP)),
                    GeminiProvider(allowLocalHttp = true)
                        .streamChat(
                            ChatRequest(listOf(ChatMessage(ChatRole.USER, "hi"))),
                            profile,
                            SecretValue("key"),
                        ).toList(),
                )
            }
        }

    @Test
    fun pingFramesAreNotProgress() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"type\":\"ping\"}\n\n".repeat(400))
                        .throttleBody(40, 60, TimeUnit.MILLISECONDS)
                        .setSocketPolicy(SocketPolicy.KEEP_OPEN),
                )
                val idle =
                    ProviderHttp(providerHttpClient(), streamIdleTimeoutMs = 400)
                        .stream(request(server.url("/v1/stream").toString()), decode = { emptyList() })
                assertEquals(
                    "a keepalive-only stream must hit the idle bound",
                    StreamEvent.Failure(ProviderError.TIMEOUT),
                    withTimeout(20_000) { idle.last() },
                )
            }
        }

    @Test
    fun eventFramesDoKeepTheStreamAlive() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"text\":\"x\"}\n\n".repeat(12))
                        .throttleBody(18, 60, TimeUnit.MILLISECONDS),
                )
                val events =
                    ProviderHttp(providerHttpClient(), streamIdleTimeoutMs = 400)
                        .stream(
                            request(server.url("/v1/stream").toString()),
                            decode = { payload ->
                                if (payload.contains("\"text\"")) {
                                    listOf(StreamEvent.TextDelta("x"))
                                } else {
                                    emptyList()
                                }
                            },
                        ).toList()
                assertEquals(12, events.count { it is StreamEvent.TextDelta })
                // Nothing proves the deadline stayed quiet like naming what DID end the stream: the
                // body stops, so EOF appends PROTOCOL. Asserting only "not TIMEOUT" passed on every
                // other failure too, including the one this test exists to catch.
                assertEquals(
                    "content frames are progress; the stream ends at EOF, not at the idle bound",
                    StreamEvent.Failure(ProviderError.PROTOCOL),
                    events.last(),
                )
            }
        }
}
