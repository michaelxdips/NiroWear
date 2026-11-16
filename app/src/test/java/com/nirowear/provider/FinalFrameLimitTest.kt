package com.nirowear.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FinalFrameLimitTest {
    @Test fun longMatchingNativeFinalEventCompletesButOversizeIsNotConnectivityError() =
        runBlocking {
            MockWebServer().use { server ->
                val profile =
                    ProviderProfile("frame", ProviderType.OPENAI, "Frame", server.url("/v1").toString(), "test", "test")
                val provider = OpenAiProvider(allowLocalHttp = true)
                val text = "x".repeat(300000)
                val body =
                    buildString {
                        text.chunked(60000).forEach {
                            append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"$it\"}\n\n")
                        }
                        append(
                            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"$text\"}]}]}}\n\n",
                        )
                    }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
                val events =
                    provider
                        .streamChat(
                            ChatRequest(listOf(ChatMessage(ChatRole.USER, "Probe"))),
                            profile,
                            SecretValue("synthetic-frame-not-real"),
                        ).toList()
                assertEquals(text, events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
                assertEquals(StreamEvent.Completed, events.last())
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: " + "x".repeat(6 * MAX_RESPONSE_CHARS + 256 * 1024 + 1) + "\n\n"),
                )
                val failure =
                    provider
                        .streamChat(
                            ChatRequest(listOf(ChatMessage(ChatRole.USER, "Probe"))),
                            profile,
                            SecretValue("synthetic-frame-not-real"),
                        ).toList()
                        .last() as StreamEvent.Failure
                // The positive outcome, not "anything but a connectivity error": the oversized frame is
                // the size bound firing, and NETWORK_UNAVAILABLE would be a different defect wearing the
                // same green. Measured against this tree, the branch that reaches here is the one in
                // ProviderHttp.readUtf8LineStrict whose EOF proves the frame consumed its whole budget.
                assertEquals(ProviderError.RESPONSE_TOO_LARGE, failure.error)
                assertEquals(2, server.requestCount)
            }
        }
}
