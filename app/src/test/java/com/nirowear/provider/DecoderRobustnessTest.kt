package com.nirowear.provider

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for four decoder defects found by audit. Each one describes a real payload that
 * a compliant or merely sloppy BYOK endpoint can send, and asserts the answer survives it.
 *
 * Every test here failed before its fix.
 */
class DecoderRobustnessTest {
    @Test
    fun doneAfterTextIsSuccessNotProtocolError() {
        val decoder = CompatibleStreamDecoder()
        decoder.accept("""{"choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}""")
        val events = decoder.accept("[DONE]")
        assertEquals(
            "a gateway that ends with [DONE] and no finish_reason chunk is legal; it must succeed",
            listOf(StreamEvent.Completed),
            events,
        )
    }

    @Test
    fun doneWithNoTextIsStillAFailure() {
        val decoder = CompatibleStreamDecoder()
        val events = decoder.accept("[DONE]")
        assertEquals(
            "success requires text; an empty stream ending in [DONE] is a real failure",
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL)),
            events,
        )
    }

    @Test
    fun explicitNullToolCallsDoesNotKillTheStream() {
        val decoder = CompatibleStreamDecoder()
        // A hand-rolled gateway serialises absent optionals as nulls. Legal JSON, and the old
        // `delta["tool_calls"] == null` check threw on it.
        val events = decoder.accept(
            """{"choices":[{"index":0,"delta":{"content":"ok","tool_calls":null,"function_call":null},"finish_reason":null}]}""",
        )
        assertTrue(
            "text from a chunk carrying explicit nulls must still render; got $events",
            events.any { it is StreamEvent.TextDelta && it.text == "ok" },
        )
    }

    @Test
    fun explicitNullFinishReasonIsNotAFailure() {
        val decoder = CompatibleStreamDecoder()
        val events = decoder.accept(
            """{"choices":[{"index":0,"delta":{"content":"a"},"finish_reason":null}]}""",
        )
        assertTrue("no failure for a normal mid-stream chunk", events.none { it is StreamEvent.Failure })
    }

    // ---- Anthropic: non-text blocks must be skipped, not fatal -------------------------------

    @Test
    fun anthropicThinkingBlockIsSkippedAndTextStillCompletes() {
        val decoder = AnthropicStreamDecoder()
        decoder.accept("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        // A thinking block arrives first (extended thinking enabled on a gateway).
        decoder.accept(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        )
        decoder.accept(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
        )
        decoder.accept("""{"type":"content_block_stop","index":0}""")
        decoder.accept(
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        )
        val deltas = decoder.accept(
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}""",
        )
        decoder.accept("""{"type":"content_block_stop","index":1}""")
        val stop = decoder.accept(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
        ) + decoder.accept("""{"type":"message_stop"}""")

        assertTrue(
            "the text block after a thinking block must render; got $deltas",
            deltas.any { it is StreamEvent.TextDelta && it.text == "answer" },
        )
        assertTrue(
            "message_stop still proves success; got $stop",
            stop.contains(StreamEvent.Completed),
        )
    }

    @Test
    fun anthropicToolUseBlockDoesNotAbort() {
        val decoder = AnthropicStreamDecoder()
        decoder.accept("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        val start = decoder.accept(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"x","name":"y"}}""",
        )
        assertTrue(
            "a tool_use block is unsupported but must not throw; got $start",
            start.none { it is StreamEvent.Failure },
        )
    }

    @Test
    fun jsonNullIsDistinctFromKotlinNull() {
        assertFalse(JsonNull == null)
        assertTrue(JsonNull is kotlinx.serialization.json.JsonElement)
    }

    @Test
    fun compatibleDecoderIgnoresExplicitJsonNullErrorAndMapsRateLimit() {
        val okDecoder = CompatibleStreamDecoder()
        val delta = okDecoder.accept("""{"error":null,"choices":[{"delta":{"content":"Hi"}}]}""")
        val done = okDecoder.accept("[DONE]")
        assertEquals(listOf(StreamEvent.TextDelta("Hi")), delta)
        assertEquals(listOf(StreamEvent.Completed), done)

        val errDecoder = CompatibleStreamDecoder()
        val failed = errDecoder.accept("""{"error":{"code":"rate_limit_exceeded"}}""")
        assertEquals(listOf(StreamEvent.Failure(ProviderError.RATE_LIMITED)), failed)
    }
}
