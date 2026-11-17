package com.nirowear.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ResponseLimitTest {
    @Test fun endlessSmallFramesStopWithExplicitFailureAndKeepVisiblePrefix() =
        runBlocking {
            for (type in ProviderType.entries) {
                MockWebServer().use { server ->
                    server.start()
                    val provider: LlmProvider =
                        when (type) {
                            ProviderType.OPENAI -> OpenAiProvider(allowLocalHttp = true)
                            ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(allowLocalHttp = true)
                            ProviderType.GEMINI -> GeminiProvider(allowLocalHttp = true)
                            ProviderType.ANTHROPIC -> AnthropicProvider(allowLocalHttp = true)
                        }
                    val chunk = "x".repeat(65536)

                    fun frame(json: String) = "data: $json\n\n"
                    val body =
                        buildString {
                            if (type == ProviderType.ANTHROPIC) {
                                append(
                                    frame("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""),
                                )
                                append(
                                    frame(
                                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                                    ),
                                )
                            }
                            repeat(17) {
                                append(
                                    frame(
                                        when (type) {
                                            ProviderType.OPENAI -> """{"type":"response.output_text.delta","delta":"$chunk"}"""
                                            ProviderType.OPENAI_COMPATIBLE -> """{"choices":[{"index":0,"delta":{"content":"$chunk"}}]}"""
                                            ProviderType.GEMINI -> """{"candidates":[{"content":{"parts":[{"text":"$chunk"}]}}]}"""
                                            ProviderType.ANTHROPIC -> """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$chunk"}}"""
                                        },
                                    ),
                                )
                            }
                        }
                    if (type == ProviderType.ANTHROPIC) server.enqueue(MockResponse().setBody("{}"))
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
                    val profile =
                        ProviderProfile("limit", type, "Limit test", server.url("/v1").toString(), "test", "test")
                    val events =
                        provider
                            .streamChat(
                                ChatRequest(listOf(ChatMessage(ChatRole.USER, "Probe"))),
                                profile,
                                SecretValue("synthetic-limit-not-real"),
                            ).toList()
                    val prefix = events.filterIsInstance<StreamEvent.TextDelta>().sumOf { it.text.length }
                    assertEquals("$type must bound accumulated visible text", 1048576, prefix)
                    val failure = events.last() as StreamEvent.Failure
                    assertEquals(
                        "Size failure must be explicit, not protocol EOF",
                        ProviderError.RESPONSE_TOO_LARGE,
                        failure.error,
                    )
                    assertFalse(events.contains(StreamEvent.Completed))
                    assertEquals(if (type == ProviderType.ANTHROPIC) 2 else 1, server.requestCount)
                }
            }
        }
}
