package com.nirowear.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAnchorTest {
    @Test fun semanticAnchorSurvivesInsertedBlocksAndDeletedContinuation() {
        val a = ReaderAnchor(20, 1, 31, 42, false)
        assertEquals(3 to 31, a.resolve(listOf(10L to "a", 10L to "b", 20L to "a", 20L to "b"), mapOf(20L to 42)))
        assertEquals(0 to 0, a.resolve(listOf(10L to "a"), emptyMap()))
        assertEquals(1 to 0, a.resolve(listOf(10L to "a", 20L to "new"), mapOf(20L to 99)))
        assertEquals(1 to 31, a.resolve(listOf(10L to "a", 20L to "a"), mapOf(20L to 42)))
    }
}
