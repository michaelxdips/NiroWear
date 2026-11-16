package com.nirowear.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wire-level role alternation: Bedrock/Vertex and GenerateContent reject consecutive same-role turns. */
class AlternatingRolesTest {
    @Test fun consecutiveSameRoleTurnsMergeAndKeepOrder() {
        val merged =
            listOf(
                ChatMessage(ChatRole.USER, "first"),
                ChatMessage(ChatRole.USER, "second"),
                ChatMessage(ChatRole.ASSISTANT, "answer"),
                ChatMessage(ChatRole.ASSISTANT, "more"),
                ChatMessage(ChatRole.USER, "third"),
            ).withAlternatingRoles()

        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), merged.map { it.role })
        assertEquals("first\nsecond", merged[0].rawContent)
        assertEquals("answer\nmore", merged[1].rawContent)
        assertEquals("third", merged[2].rawContent)
    }

    @Test fun alreadyAlternatingHistoryIsUnchanged() {
        val history =
            listOf(
                ChatMessage(ChatRole.USER, "u1"),
                ChatMessage(ChatRole.ASSISTANT, "a1"),
                ChatMessage(ChatRole.USER, "u2"),
            )
        assertEquals(history, history.withAlternatingRoles())
    }

    @Test fun emptyAndSingleTurnHistoriesAreSafe() {
        assertEquals(emptyList<ChatMessage>(), emptyList<ChatMessage>().withAlternatingRoles())
        val single = listOf(ChatMessage(ChatRole.USER, "only"))
        assertEquals(single, single.withAlternatingRoles())
    }

    @Test fun failedTurnLeavesAdjacentUsersThatMergeToValidWireShape() {
        // An ERROR-before-output assistant row is empty and is dropped from context, leaving two users.
        val context =
            listOf(ChatMessage(ChatRole.USER, "first question"), ChatMessage(ChatRole.USER, "second question"))
        val wire = context.withAlternatingRoles()
        assertEquals(1, wire.size)
        assertEquals(ChatRole.USER, wire.single().role)
    }
}
