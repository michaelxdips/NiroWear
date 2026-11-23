package com.nirowear.chat

import com.nirowear.data.StoredMessage
import com.nirowear.renderer.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parse memo is bounded per conversation, and opening a conversation must make it the MOST
 * recent one.
 *
 * `switch` calls the production `nextRecency` and `evictStaleConversations`. It previously computed
 * the recency expression itself, so removing the move-to-end or the bound from production changed
 * nothing here — verified by mutation: with both `needsParse` and this arithmetic broken, all of
 * these tests still passed.
 */
class Task5ParseMemoTest {
    private fun msg(
        id: Long,
        conv: String,
        raw: String = "body $id",
    ) = StoredMessage(
        id = id,
        conversationId = conv,
        role = "ASSISTANT",
        rawContent = raw,
        status = "SUCCESS",
        providerProfileId = "p",
        providerLabel = "l",
        modelId = "m",
        error = null,
    )

    @Test
    fun nextRecencyMovesTheOpenedConversationToTheEnd() {
        assertEquals(listOf("B", "C", "A"), nextRecency(listOf("A", "B", "C"), "A"))
        assertEquals(listOf("A", "B"), nextRecency(listOf("A", "B"), "B"))
        assertEquals(listOf("A", "B"), nextRecency(listOf("A", "B"), null))
    }

    @Test
    fun nextRecencyIsBounded() {
        var recent = emptyList<String>()
        for (conv in listOf("A", "B", "C", "D", "E")) recent = nextRecency(recent, conv)
        assertEquals(listOf("C", "D", "E"), recent)
    }

    private fun switch(
        memo: Map<StoredMessage, List<Pair<Long, ContentBlock>>>,
        recent: List<String>,
        open: String,
        vararg rows: StoredMessage,
    ): Pair<Map<StoredMessage, List<Pair<Long, ContentBlock>>>, List<String>> {
        val next = nextRecency(recent, open)
        val grown = rows.fold(memo) { acc, m -> acc + (m to emptyList()) }
        return evictStaleConversations(grown, next) to next
    }

    @Test
    fun switchAcrossFourConversationsDropsFirst() {
        var memo = emptyMap<StoredMessage, List<Pair<Long, ContentBlock>>>()
        var recent = emptyList<String>()
        for (conv in listOf("A", "B", "C", "D")) {
            val opened = switch(memo, recent, conv, msg(conv.hashCode().toLong(), conv))
            memo = opened.first
            recent = opened.second
        }
        assertTrue("entries for A must be gone", memo.keys.none { it.conversationId == "A" })
        assertTrue("entries for D must be kept", memo.keys.any { it.conversationId == "D" })
    }

    @Test
    fun keepsAtMostThreeConversations() {
        var memo = emptyMap<StoredMessage, List<Pair<Long, ContentBlock>>>()
        var recent = emptyList<String>()
        for (conv in listOf("A", "B", "C", "D", "E")) {
            val opened = switch(memo, recent, conv, msg(conv.hashCode().toLong(), conv))
            memo = opened.first
            recent = opened.second
        }
        assertEquals(setOf("C", "D", "E"), memo.keys.map { it.conversationId }.toSet())
    }

    @Test
    fun reopeningKeepsEntryRecent() {
        var memo = emptyMap<StoredMessage, List<Pair<Long, ContentBlock>>>()
        var recent = emptyList<String>()
        for (conv in listOf("A", "B", "C")) {
            val opened = switch(memo, recent, conv, msg(conv.hashCode().toLong(), conv))
            memo = opened.first
            recent = opened.second
        }
        val reopened = switch(memo, recent, "A")
        memo = reopened.first
        val opened = switch(memo, reopened.second, "D", msg("D".hashCode().toLong(), "D"))
        memo = opened.first
        assertTrue("reopened A must survive", memo.keys.any { it.conversationId == "A" })
        assertFalse("B must be evicted", memo.keys.any { it.conversationId == "B" })
    }

    /**
     * The production effect stores `mergeParsed(parsedRows, rows, recent, id)`. It used to store
     * `evictStaleConversations(rows, ...)` — `rows` being only the OPEN conversation's messages — so
     * one entry per conversation could never be retained and the bound above was inert: History
     * A -> B -> A re-parsed all of A. `switch` here builds the memo the way production does, through
     * the production function.
     */
    @Test
    fun oneSwitchAwayKeepsThePreviousConversationMemoised() {
        val merged = mergeParsed(emptyMap(), mapOf(msg(1, "A") to emptyList()), emptyList(), "A")
        val afterB = mergeParsed(merged.first, mapOf(msg(2, "B") to emptyList()), merged.second, "B")
        assertEquals(
            "both conversations must stay memoised after one switch",
            setOf("A", "B"),
            afterB.first.keys.map { it.conversationId }.toSet(),
        )
        // Re-opening A still has its entry, so `needsParse` reports false and nothing re-parses.
        assertFalse(needsParse(listOf(msg(1, "A")), "A", afterB.first))
    }

    @Test
    fun streamingUpdatesSupersedeEarlierRawSnapshotsForSameMessage() {
        var memo = emptyMap<StoredMessage, List<Pair<Long, ContentBlock>>>()
        var recent = emptyList<String>()
        for (step in 1..50) {
            val updated = msg(id = 1L, conv = "A", raw = "delta $step")
            val merged = mergeParsed(memo, mapOf(updated to emptyList()), recent, "A")
            memo = merged.first
            recent = merged.second
        }
        assertEquals(1, memo.size)
        assertEquals("delta 50", memo.keys.single().rawContent)
    }

    @Test
    fun linearEditRemovesDeletedMessagesFromActiveConversationWhilePreservingOthers() {
        val emptyBlocks = emptyList<Pair<Long, ContentBlock>>()
        val mB = msg(id = 10L, conv = "B", raw = "other")
        val initialA = mapOf(msg(1L, "A", "u1") to emptyBlocks, msg(2L, "A", "a1") to emptyBlocks)
        val withB = mergeParsed(emptyMap(), mapOf(mB to emptyBlocks), emptyList(), "B")
        val withA = mergeParsed(withB.first, initialA, withB.second, "A")
        val editedA = mergeParsed(withA.first, mapOf(msg(1L, "A", "edited") to emptyBlocks), withA.second, "A")

        val keysA = editedA.first.keys.filter { it.conversationId == "A" }
        assertEquals(listOf("edited"), keysA.map { it.rawContent })
        assertTrue(editedA.first.keys.any { it.conversationId == "B" && it.id == 10L })
    }
}
