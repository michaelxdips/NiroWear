package com.nirowear.chat

import com.nirowear.historyKeysFor
import com.nirowear.renderer.ContentBlock
import com.nirowear.renderer.InlineContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Characterisation test for the reader list contract (Task 4.1).
 *
 * The reader renders one entry per (messageId, block) pair, in order, and the anchor logic indexes
 * into that flat list. `historyKeysFor` is the LazyColumn key source: keys must be unique per block,
 * stable for the same input, and numbered per message rather than globally.
 *
 * This pins the real production function. An earlier draft of this test built its own list and
 * asserted on it, which could never fail — the defect class this campaign exists to remove.
 */
class ReaderListContractTest {
    private fun block(label: String) =
        ContentBlock.Heading(4, listOf(InlineContent(label)))

    private fun history(vararg spec: Pair<Long, String>) = spec.map { (id, label) -> id to block(label) }

    @Test
    fun keysAreNumberedPerMessageNotGlobally() {
        val keys = historyKeysFor(history(1L to "a1", 1L to "a2", 2L to "b1"))
        assertEquals(listOf("1:0", "1:1", "2:0"), keys)
    }

    @Test
    fun keysAreUniqueForEveryBlock() {
        val keys = historyKeysFor(history(1L to "a1", 1L to "a2", 1L to "a3", 2L to "b1"))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun keyOrderFollowsRenderOrder() {
        val keys = historyKeysFor(history(2L to "b1", 1L to "a1"))
        assertEquals(listOf("2:0", "1:0"), keys)
    }

    @Test
    fun sameInputGivesSameKeys() {
        val input = history(1L to "a1", 2L to "b1")
        assertEquals(historyKeysFor(input), historyKeysFor(input))
    }

    @Test
    fun differentMessagesDoNotCollideAtTheSameIndex() {
        val a = historyKeysFor(history(1L to "x"))[0]
        val b = historyKeysFor(history(2L to "x"))[0]
        assertNotEquals(a, b)
    }

    @Test
    fun emptyHistoryHasNoKeys() {
        assertEquals(emptyList<String>(), historyKeysFor(emptyList()))
    }
}
