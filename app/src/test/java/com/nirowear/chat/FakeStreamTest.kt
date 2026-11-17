package com.nirowear.chat

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeStreamTest {
    @Test fun preservesRawTextAndUnicodeAtChunkBoundary() =
        runBlocking {
            val source = "x".repeat(31) + "𝑥" + "\n\\[\\frac{1}{2}\\]"
            val chunks = fakeStream(source).toList()
            assertEquals(source, chunks.joinToString(""))
            assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
            assertTrue(chunks.map { it.length }.distinct().size > 1)
        }

    @Test fun interruptionRetainsPrefixWithoutManufacturingCompletion() =
        runBlocking {
            val source = "**unfinished bold " + "content ".repeat(20)
            val partial = fakeStream(source).take(2).toList().joinToString("")
            assertTrue(source.startsWith(partial))
            assertTrue(partial.length < source.length)
            assertTrue(partial.startsWith("**"))
        }
}
