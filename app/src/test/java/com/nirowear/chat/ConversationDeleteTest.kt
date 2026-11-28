package com.nirowear.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDeleteTest {
    @Test
    fun blockedWhileInitializingGeneratingUnsavedOrFailed() {
        assertEquals(
            DeleteDecision.BLOCKED,
            decideConversationDelete(
                initFailed = true,
                generating = false,
                unsaved = false,
                readyCompleted = true,
                activeConversationId = "c1",
                targetId = "c1",
            ),
        )
        assertEquals(
            DeleteDecision.BLOCKED,
            decideConversationDelete(
                initFailed = false,
                generating = true,
                unsaved = false,
                readyCompleted = true,
                activeConversationId = "c1",
                targetId = "c2",
            ),
        )
        assertEquals(
            DeleteDecision.BLOCKED,
            decideConversationDelete(
                initFailed = false,
                generating = false,
                unsaved = true,
                readyCompleted = true,
                activeConversationId = "c1",
                targetId = "c2",
            ),
        )
        assertEquals(
            DeleteDecision.BLOCKED,
            decideConversationDelete(
                initFailed = false,
                generating = false,
                unsaved = false,
                readyCompleted = false,
                activeConversationId = "c1",
                targetId = "c2",
            ),
        )
    }

    @Test
    fun deletingOpenConversationClearsActiveChat() {
        assertEquals(
            DeleteDecision.DELETE_AND_CLEAR_ACTIVE,
            decideConversationDelete(
                initFailed = false,
                generating = false,
                unsaved = false,
                readyCompleted = true,
                activeConversationId = "c1",
                targetId = "c1",
            ),
        )
    }

    @Test
    fun deletingBackgroundConversationKeepsActiveChat() {
        assertEquals(
            DeleteDecision.DELETE_KEEP_ACTIVE,
            decideConversationDelete(
                initFailed = false,
                generating = false,
                unsaved = false,
                readyCompleted = true,
                activeConversationId = "c1",
                targetId = "c2",
            ),
        )
        assertEquals(
            DeleteDecision.DELETE_KEEP_ACTIVE,
            decideConversationDelete(
                initFailed = false,
                generating = false,
                unsaved = false,
                readyCompleted = true,
                activeConversationId = null,
                targetId = "c2",
            ),
        )
    }
}
