package com.nirowear.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CompatibleProviderTest {
    private val profile =
        ProviderProfile("id", ProviderType.OPENAI_COMPATIBLE, "Test", "https://example.com/v1", "manual-model", "ref")

    @Test
    fun aBodyCodeReachesTheCompatibleAdapter() =
        runBlocking {
            // The adapter read the body for a Gemini-style error and threw it away for this shape, so a
            // 400 that named the key read as "Provider rejected request. Check configuration.".
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1").toString())
                server.enqueue(
                    MockResponse().setResponseCode(400).setBody("""{"error":{"code":"invalid_api_key"}}"""),
                )
                assertEquals(
                    listOf(StreamEvent.Failure(ProviderError.INVALID_CREDENTIAL)),
                    OpenAiCompatibleProvider(allowLocalHttp = true)
                        .streamChat(ChatRequest(listOf(ChatMessage(ChatRole.USER, "hi"))), p, SecretValue("key"))
                        .toList(),
                )
            }
        }

    @Test fun endpointsAndRedaction() {
        for (base in listOf(
            " https://example.com ",
            "https://example.com/",
            "https://example.com/v1/",
            "https://example.com/v1/v1",
        )) {
            assertEquals(
                "https://example.com/v1/chat/completions",
                Endpoints.compatible(base, "chat/completions").toString(),
            )
        }
        assertEquals(
            "https://example.com/proxy/chat/completions",
            Endpoints.compatible("https://example.com/proxy", "chat/completions").toString(),
        )
        for (base in listOf(
            "ftp://example.com",
            "http://example.com",
            "https://u:p@example.com",
            "https://example.com?key=test",
        )) {
            assertTrue(runCatching { Endpoints.normalize(base) }.isFailure)
        }
        assertEquals("[REDACTED]", redactHeaders(mapOf("X-Goog-Api-Key" to "test"))["X-Goog-Api-Key"])
        assertEquals(
            mapOf(
                "Authorization" to "[REDACTED]",
                "x-api-key" to "[REDACTED]",
                "x-goog-api-key" to "[REDACTED]",
                "anthropic-version" to "2023-06-01",
            ),
            redactHeaders(
                mapOf(
                    "Authorization" to "Bearer controlled",
                    "x-api-key" to "controlled",
                    "x-goog-api-key" to "controlled",
                    "anthropic-version" to "2023-06-01",
                ),
            ),
        )
        assertEquals("[REDACTED]", SecretValue("test-value").toString())
    }

    @Test fun controlledStreamingAndListingFailureStillGenerates() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val config = profile.copy(baseUrl = server.url("/v1").toString())
                val provider = OpenAiCompatibleProvider(allowLocalHttp = true)
                val secret = SecretValue("test-not-real-credential")
                server.enqueue(MockResponse().setResponseCode(404))
                assertTrue(provider.listModels(config, secret) is ModelDiscoveryResult.Unavailable)
                server.enqueue(
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"α hello\"},\"finish_reason\":null}]}\n\n" +
                            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                    ),
                )
                val events =
                    provider
                        .streamChat(
                            ChatRequest(listOf(ChatMessage(ChatRole.USER, "Hello"))),
                            config,
                            secret,
                        ).toList()
                assertEquals(listOf(StreamEvent.TextDelta("α hello"), StreamEvent.Completed), events)
                server.takeRequest()
                val request = server.takeRequest()
                assertEquals("/v1/chat/completions", request.path)
                assertTrue(request.body.readUtf8().contains("manual-model"))
                assertEquals("Bearer test-not-real-credential", request.getHeader("Authorization"))
            }
        }

    @Test fun truncatedStreamKeepsPartialWithoutSuccessOrReplay() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"),
                )
                val events =
                    OpenAiCompatibleProvider(allowLocalHttp = true)
                        .streamChat(
                            ChatRequest(listOf(ChatMessage(ChatRole.USER, "Hello"))),
                            profile.copy(baseUrl = server.url("/").toString()),
                            SecretValue("test"),
                        ).toList()
                assertEquals(StreamEvent.TextDelta("partial"), events.first())
                assertTrue(events.last() is StreamEvent.Failure)
                assertFalse(events.contains(StreamEvent.Completed))
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun emptySemanticStopIsNotSuccessfulAnswer() {
        val events = CompatibleStreamDecoder().accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(listOf(StreamEvent.Failure(ProviderError.PROTOCOL)), events)
        val decoder = CompatibleStreamDecoder()
        assertEquals(
            listOf(StreamEvent.TextDelta("visible")),
            decoder.accept("""{"choices":[{"delta":{"content":"visible"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Completed),
            decoder.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
        )
    }

    @Test fun outputCapAndPolicyCutsAreDisclosed() {
        // `length` is the documented "hit the output cap" reason, i.e. the answer was CUT. This
        // adapter completed it silently, which made it the only provider that never disclosed a cut
        // (Anthropic max_tokens, Gemini MAX_TOKENS and Responses max_output_tokens all say so).
        assertEquals(
            listOf(StreamEvent.TextDelta("partial"), StreamEvent.Truncated),
            CompatibleStreamDecoder().accept("""{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
            CompatibleStreamDecoder().accept("""{"choices":[{"delta":{},"finish_reason":"length"}]}"""),
        )
        // A policy finish after visible text is a cut too: the text is kept and the note says it may
        // be incomplete, instead of presenting it as a finished answer.
        assertEquals(
            listOf(StreamEvent.TextDelta("partial"), StreamEvent.Truncated),
            CompatibleStreamDecoder()
                .accept("""{"choices":[{"delta":{"content":"partial"},"finish_reason":"content_filter"}]}"""),
        )
    }

    @Test fun cancellingCollectionCancelsUnderlyingCall() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" + " ".repeat(16384))
                        .throttleBody(1024, 1, TimeUnit.SECONDS),
                )
                val provider = OpenAiCompatibleProvider(client, true)
                val started = CompletableDeferred<Unit>()
                val job =
                    launch {
                        provider
                            .streamChat(
                                ChatRequest(listOf(ChatMessage(ChatRole.USER, "Hello"))),
                                profile.copy(baseUrl = server.url("/").toString()),
                                SecretValue("test"),
                            ).collect { started.complete(Unit) }
                    }
                withTimeout(10000) { started.await() }
                job.cancelAndJoin()
                withTimeout(3000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
                assertEquals(0, client.dispatcher.runningCallsCount())
            }
        }
    @Test
    fun rejectsAFullEndpointPastedAsBase() {
        // Regression: only chat/completions and a trailing models/responses were rejected, so the URL
        // from a provider's own cURL example was accepted and the adapter appended its path again —
        // "/v1/messages/messages" — which 404s, and 404 maps to "Model unavailable. Choose another
        // model.", so a URL typo read as a model problem.
        for (base in
            listOf(
                "https://api.anthropic.com/v1/messages",
                "https://api.openai.com/v1/chat/completions",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
                "https://api.openai.com/v1/embeddings",
                "https://example.com/v1/models/some-id",
            )
        ) {
            assertTrue("must be rejected as a base: $base", runCatching { Endpoints.normalize(base) }.isFailure)
        }
    }

    @Test
    fun acceptsTheRealBasesPeopleActuallyUse() {
        // The guard must not reject a legitimate base. These are the documented bases of the providers a
        // compatible endpoint is commonly pointed at, including a gateway path and Azure's
        // deployment-form URL, whose last segment is neither "v1" nor a resource name.
        for (base in
            listOf(
                "https://api.anthropic.com/v1",
                "https://api.openai.com/v1",
                "https://generativelanguage.googleapis.com/v1beta",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "https://openrouter.ai/api/v1",
                "https://api.groq.com/openai/v1",
                "https://api.together.xyz/v1",
                "https://api.mistral.ai/v1",
                "https://x.openai.azure.com/openai/deployments/my-deploy",
                "https://gateway.example.com/chat/v1",
                "https://gateway.example.com/model/v1",
            )
        ) {
            assertTrue("must be accepted: $base", runCatching { Endpoints.normalize(base) }.isSuccess)
        }
        // Localhost over plain HTTP is allowed only when the caller opts in.
        assertTrue(
            "local http base must pass when allowed",
            runCatching { Endpoints.normalize("http://localhost:11434/v1", allowLocalHttp = true) }.isSuccess,
        )
        assertTrue(
            "local http base must fail when not allowed",
            runCatching { Endpoints.normalize("http://localhost:11434/v1") }.isFailure,
        )
    }
}
