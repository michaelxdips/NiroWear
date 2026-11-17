package com.nirowear.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class NativeNetworkTest {
    @Test fun disconnectedNativeStreamsKeepPartialNeverReplay() =
        runBlocking {
            for (type in listOf(ProviderType.OPENAI, ProviderType.GEMINI, ProviderType.ANTHROPIC)) {
                MockWebServer().use { server ->
                    val provider =
                        when (type) {
                            ProviderType.OPENAI -> OpenAiProvider(allowLocalHttp = true)
                            ProviderType.GEMINI -> GeminiProvider(allowLocalHttp = true)
                            else -> AnthropicProvider(allowLocalHttp = true)
                        }
                    val frame =
                        when (type) {
                            ProviderType.OPENAI -> {
                                """{"type":"response.output_text.delta","delta":"partial"}"""
                            }

                            ProviderType.GEMINI -> {
                                """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""
                            }

                            else -> {
                                listOf(
                                    """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
                                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
                                ).joinToString("\n\ndata: ")
                            }
                        }
                    if (type ==
                        ProviderType.ANTHROPIC
                    ) {
                        server.enqueue(MockResponse().setBody("""{"max_tokens":32000}"""))
                    }
                    server.enqueue(
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "data: $frame\n\n" + ":" + " ".repeat(32768),
                            ).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                    )
                    val profile =
                        ProviderProfile(
                            "id",
                            type,
                            "Network test",
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
                            "selected",
                            "ref",
                        )
                    val events =
                        provider
                            .streamChat(
                                ChatRequest(listOf(ChatMessage(ChatRole.USER, "Prompt"))),
                                profile,
                                SecretValue("network-controlled-key"),
                            ).toList()
                    assertEquals(StreamEvent.TextDelta("partial"), events.first())
                    assertTrue(events.last() is StreamEvent.Failure)
                    assertFalse(events.contains(StreamEvent.Completed))
                    assertEquals(if (type == ProviderType.ANTHROPIC) 2 else 1, server.requestCount)
                }
            }
        }
}
