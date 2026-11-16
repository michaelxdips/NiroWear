package com.nirowear.chat

import com.nirowear.data.StoredMessage
import com.nirowear.renderer.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3-03 coalescing logic, extracted so it can be tested without Compose or a device.
 *
 * The rule that matters: a throttle, never a debounce. A debounce returns a positive delay for
 * every arriving delta and therefore starves under a fast stream (measured: 0 parses over 300 ms
 * at a 4 ms cadence). coalesceDelayMs must return 0 once the window has elapsed.
 */
class StreamParseTest {
    private fun msg(id: Long, content: String) = StoredMessage(
        id = id,
        conversationId = "c",
        role = "ASSISTANT",
        rawContent = content,
        status = "STREAMING",
        providerProfileId = "p",
        providerLabel = "l",
        modelId = "m",
        error = null,
    )

    @Test
    fun needsParseWhenARowIsAbsentFromTheMemo() {
        val messages = listOf(msg(1, "a"))
        assertTrue(needsParse(messages, "c", emptyMap()))
    }

    @Test
    fun doesNotNeedParseWhenEveryRowIsMemoised() {
        val m = msg(1, "a")
        val memo = mapOf<StoredMessage, List<Pair<Long, ContentBlock>>>(m to emptyList())
        assertFalse(needsParse(listOf(m), "c", memo))
    }

    @Test
    fun ignoresRowsFromOtherConversations() {
        val other = msg(1, "a").copy(conversationId = "other")
        assertFalse(needsParse(listOf(other), "c", emptyMap()))
    }

    @Test
    fun noWaitWhenTheWindowHasAlreadyElapsed() {
        assertEquals(0L, coalesceDelayMs(nowMs = 1_000L, lastParseAtMs = 0L, windowMs = 16L))
    }

    @Test
    fun waitsTheRemainderInsideTheWindow() {
        assertEquals(10L, coalesceDelayMs(nowMs = 1_006L, lastParseAtMs = 1_000L, windowMs = 16L))
    }

    @Test
    fun neverReturnsNegative() {
        assertEquals(0L, coalesceDelayMs(nowMs = 900L, lastParseAtMs = 1_000L, windowMs = 16L))
    }
}
