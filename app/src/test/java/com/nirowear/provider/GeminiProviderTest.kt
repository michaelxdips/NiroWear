package com.nirowear.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiProviderTest {
    private val profile =
        ProviderProfile(
            "id",
            ProviderType.GEMINI,
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            "selected",
            "ref",
        )
    private val secret = SecretValue("gemini-controlled-key")
    private val chat =
        ChatRequest(
            listOf(
                ChatMessage(ChatRole.USER, "First"),
                ChatMessage(ChatRole.ASSISTANT, "Old"),
                ChatMessage(ChatRole.USER, "Next"),
            ),
            "Instruction",
        )

    private fun chunk(
        text: String,
        finish: String? = null,
    ) = """{"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"$text"}]}${finish?.let {
        ",\"finishReason\":\"$it\""
    } ?: ""}}]}"""

    private fun sse(vararg data: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            data.joinToString("") {
                "data: $it\n\n"
            },
        )

    @Test fun paginatedCapabilityFilteredDiscoveryAndNativeRequest() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1beta").toString())
                val provider = GeminiProvider(allowLocalHttp = true)
                server.enqueue(
                    MockResponse().setBody(
                        """{"models":[{"name":"models/embed","supportedGenerationMethods":["embedContent"]},{"name":"models/selected","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next"}""",
                    ),
                )
                server.enqueue(
                    MockResponse().setBody(
                        """{"models":[{"name":"models/second","supportedGenerationMethods":["generateContent"]}]}""",
                    ),
                )
                assertEquals(ModelDiscoveryResult.Models(listOf("second", "selected")), provider.listModels(p, secret))
                assertEquals("/v1beta/models?pageSize=1000", server.takeRequest().path)
                assertEquals("/v1beta/models?pageSize=1000&pageToken=next", server.takeRequest().path)
                server.enqueue(sse(chunk("α "), chunk("answer", "STOP")))
                assertEquals(
                    listOf(StreamEvent.TextDelta("α "), StreamEvent.TextDelta("answer"), StreamEvent.Completed),
                    provider.streamChat(chat, p, secret).toList(),
                )
                val wire = server.takeRequest()
                assertEquals("/v1beta/models/selected:streamGenerateContent?alt=sse", wire.path)
                assertEquals("gemini-controlled-key", wire.getHeader("x-goog-api-key"))
                assertNull(wire.getHeader("Authorization"))
                val body = Json.parseToJsonElement(wire.body.readUtf8()).jsonObject
                assertEquals(
                    listOf("user", "model", "user"),
                    body.getValue("contents").jsonArray.map {
                        it.jsonObject
                            .getValue("role")
                            .jsonPrimitive.content
                    },
                )
                assertEquals(
                    "Instruction",
                    body
                        .getValue(
                            "systemInstruction",
                        ).jsonObject
                        .getValue("parts")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content,
                )
            }
        }

    @Test fun malformedDiscoveryEntryDoesNotDiscardValidModels() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1beta").toString())
                val overlong = "x".repeat(257)
                server.enqueue(
                    MockResponse().setBody(
                        """{"models":[{"name":"models/alpha","supportedGenerationMethods":["generateContent"]},{"no_name":true},{"name":null},{"name":""},{"name":"models/$overlong","supportedGenerationMethods":["generateContent"]},{"name":"models/beta","supportedGenerationMethods":["generateContent"]}],"_y":0}""",
                    ),
                )
                assertEquals(
                    ModelDiscoveryResult.Models(listOf("alpha", "beta")),
                    GeminiProvider(allowLocalHttp = true).listModels(p, secret),
                )
            }
        }

    @Test fun consecutiveSameRoleHistoryIsMergedOnTheWire() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1beta").toString())
                // Failed turn before any output drops the empty assistant row, leaving two user turns.
                val adjacent =
                    ChatRequest(
                        listOf(ChatMessage(ChatRole.USER, "first"), ChatMessage(ChatRole.USER, "second")),
                        "Instruction",
                    )
                server.enqueue(sse(chunk("answer", "STOP")))
                assertEquals(
                    StreamEvent.Completed,
                    GeminiProvider(allowLocalHttp = true).streamChat(adjacent, p, secret).last(),
                )
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val contents = body.getValue("contents").jsonArray
                assertEquals(
                    listOf("user"),
                    contents.map {
                        it.jsonObject
                            .getValue("role")
                            .jsonPrimitive.content
                    },
                )
                assertEquals(
                    "first\nsecond",
                    contents[0]
                        .jsonObject
                        .getValue("parts")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content,
                )
            }
        }

    @Test fun truncatedMalformedBlockedAndErrorsNoReplay() =
        runBlocking {
            MockWebServer().use { server ->
                val p = profile.copy(baseUrl = server.url("/v1beta").toString())
                val provider = GeminiProvider(allowLocalHttp = true)
                for (data in listOf(
                    chunk("partial"),
                    "{",
                )) {
                    server.enqueue(sse(data))
                    assertTrue(provider.streamChat(chat, p, secret).last() is StreamEvent.Failure)
                }
                server.enqueue(sse(chunk("partial", "MAX_TOKENS")))
                assertEquals(
                    "hitting the output cap is a documented terminal state, not an interruption, and it " +
                        "must be DISCLOSED: Truncated, not Completed",
                    StreamEvent.Truncated,
                    provider.streamChat(chat, p, secret).last(),
                )
                server.enqueue(sse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
                assertEquals(
                    ProviderError.PROMPT_BLOCKED,
                    (provider.streamChat(chat, p, secret).last() as StreamEvent.Failure).error,
                )
                for ((code, error) in mapOf(
                    401 to ProviderError.INVALID_CREDENTIAL,
                    403 to ProviderError.PERMISSION_DENIED,
                    404 to ProviderError.MODEL_UNAVAILABLE,
                    429 to ProviderError.RATE_LIMITED,
                    503 to ProviderError.SERVER,
                )) {
                    server.enqueue(MockResponse().setResponseCode(code).setBody("gemini-controlled-key"))
                    assertEquals(StreamEvent.Failure(error), provider.streamChat(chat, p, secret).last())
                }
                assertEquals(9, server.requestCount)
                server.enqueue(sse(chunk("retry", "STOP")))
                assertEquals(StreamEvent.Completed, provider.streamChat(chat, p, secret).last())
            }
        }

    @Test fun abortingFinishReasonsAreNotCleanCompletions() {
        // These finishReasons ABORT a generation part-way. They used to fall into the generic branch and
        // were reported as Completed, so an answer Gemini itself called malformed, escalated or
        // policy-limited was stored SUCCESS with no note — the undisclosed-cut class Truncated exists
        // to remove. The decoder is stateful per collection, so a fresh one is used per case.
        for (reason in
            listOf(
                "MALFORMED_RESPONSE",
                "MALFORMED_FUNCTION_CALL",
                "MISSING_THOUGHT_SIGNATURE",
                "UNEXPECTED_TOOL_CALL",
                "TOO_MANY_TOOL_CALLS",
                "ESCALATION",
                "PUP_LIMITED_DISABLED",
            )
        ) {
            assertEquals(
                "finishReason $reason must not report a clean completion",
                listOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure(ProviderError.PROTOCOL)),
                GeminiStreamDecoder().accept(chunk("partial", reason)),
            )
        }
        // Policy reasons keep their own meaning, and keep the text when there is some.
        for (reason in listOf("IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION")) {
            assertEquals(
                "finishReason $reason must be a policy block",
                listOf(StreamEvent.Failure(ProviderError.PROMPT_BLOCKED)),
                GeminiStreamDecoder().accept(chunk("", reason)),
            )
        }
        // With text already emitted the policy reason is a CUT, not a finished answer: reporting
        // Completed hid the truncation behind a plausible ending (the undisclosed-cut class).
        for (reason in
            listOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY")
        ) {
            assertEquals(
                "finishReason $reason with text must disclose the cut",
                listOf(StreamEvent.TextDelta("partial"), StreamEvent.Truncated),
                GeminiStreamDecoder().accept(chunk("partial", reason)),
            )
        }
        // An unknown reason is a terminal state of unknown cause. It used to report Completed, so a
        // cut answer of the next API version's new reason was indistinguishable from a finished one.
        for (reason in listOf("LANGUAGE", "OTHER", "IMAGE_OTHER", "NO_IMAGE", "SOMETHING_NEW")) {
            assertEquals(
                "finishReason $reason must not claim a clean completion",
                listOf(StreamEvent.TextDelta("partial"), StreamEvent.Truncated),
                GeminiStreamDecoder().accept(chunk("partial", reason)),
            )
            assertEquals(
                "finishReason $reason with no text is not a completion either",
                listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
                GeminiStreamDecoder().accept(chunk("", reason)),
            )
        }
    }

    @Test fun thoughtTextHiddenAndPaginationLoopRejected() =
        runBlocking {
            val decoder = GeminiStreamDecoder()
            assertEquals(
                // Work, but no content: a thinking part must not leak into portable text and must
                // still count as progress against the idle deadline.
                listOf(StreamEvent.Working),
                decoder.accept("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private"}]}}]}"""),
            )
            assertEquals(
                listOf(StreamEvent.TextDelta("answer"), StreamEvent.Completed),
                decoder.accept(chunk("answer", "STOP")),
            )
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setBody("""{"nextPageToken":"loop"}""")) }
                assertEquals(
                    ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL),
                    GeminiProvider(
                        allowLocalHttp = true,
                    ).listModels(profile.copy(baseUrl = server.url("/v1beta").toString()), secret),
                )
            }
        }

    @Test fun cancellationClosesNativeHttp() =
        runBlocking {
            MockWebServer().use { server ->
                val client = providerHttpClient()
                server.enqueue(
                    sse(
                        chunk("partial"),
                    ).setBody(
                        "data: ${chunk("partial")}\n\n" + " ".repeat(16384),
                    ).throttleBody(1024, 1, TimeUnit.SECONDS),
                )
                val started = CompletableDeferred<Unit>()
                val job =
                    launch {
                        GeminiProvider(
                            client,
                            true,
                        ).streamChat(chat, profile.copy(baseUrl = server.url("/v1beta").toString()), secret).collect {
                            started.complete(Unit)
                        }
                    }
                withTimeout(10000) { started.await() }
                job.cancelAndJoin()
                withTimeout(3000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
                assertEquals(0, client.dispatcher.runningCallsCount())
            }
        }

    @Test fun jsonNullOptionalFieldsDoNotAbortStream() {
        val decoder = GeminiStreamDecoder()
        assertEquals(
            listOf(StreamEvent.TextDelta("ok"), StreamEvent.Completed),
            decoder.accept(
                """{"error":null,"promptFeedback":null,"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""",
            ),
        )
    }
}
