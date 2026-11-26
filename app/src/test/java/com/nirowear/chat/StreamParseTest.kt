package com.nirowear.chat

import com.nirowear.data.StoredMessage
import com.nirowear.renderer.ContentBlock
import com.nirowear.renderer.ResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confinedPrefixCacheSurvivesRepeatedLocaleClearWithoutConcurrentModification() =
        runBlocking {
            val parseDispatcher = Dispatchers.Default.limitedParallelism(1)
            val streamPrefixCache = HashMap<Long, ResponseParser.IncrementalParseState>()
            val prefixSample = "Alpha section.\n\nBeta section.\n\nGamma paragraph with enough characters to exceed the incremental prefix threshold safely."
            val jobs =
                (1..200).map { step ->
                    async(Dispatchers.Default) {
                        val localeChanged = step % 3 == 0
                        withContext(parseDispatcher) {
                            if (localeChanged) streamPrefixCache.clear()
                            val activeIds = setOf(1L, 2L, 3L)
                            streamPrefixCache.keys.retainAll(activeIds)
                            val id = (step % 3).toLong() + 1L
                            val raw = prefixSample + "\n\nDelta #$step."
                            val (nextPrefix, blocks) = ResponseParser.parseIncremental(streamPrefixCache[id], raw)
                            if (nextPrefix != null) {
                                streamPrefixCache[id] = nextPrefix
                            } else {
                                streamPrefixCache.remove(id)
                            }
                            blocks
                        }
                    }
                }
            val results = jobs.awaitAll()
            assertEquals(200, results.size)
            assertTrue(results.all { it.isNotEmpty() })
        }
}
