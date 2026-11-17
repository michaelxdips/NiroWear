package com.nirowear.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Task 5: the pairing body read must be bounded. A lying Content-Length must not drive a blind
 * 16 KiB allocation, and a trickling sender must not hold the single worker past the wall clock.
 */
class Task5ReadBoundTest {
    @Test
    fun readExactly_respectsStreamEnd() {
        val declared = 16384
        val stream = ByteArrayInputStream(ByteArray(100))
        assertNull(PairingServer.readBounded(stream, declared, System.currentTimeMillis() + 5_000))
    }

    @Test
    fun readExactly_rejectsDeclaredOverCapWithoutAllocating() {
        val stream = ByteArrayInputStream(ByteArray(100))
        assertNull(PairingServer.readBounded(stream, Int.MAX_VALUE, System.currentTimeMillis() + 5_000))
    }

    @Test(timeout = 10_000)
    fun readExactly_deadlineInsideLoopStopsSlowSender() {
        val trickle =
            object : InputStream() {
                private var n = 0

                // Fast enough to make progress, slow enough to still be mid-body at the deadline:
                // the wall clock must stop the loop even though bytes keep arriving.
                override fun read(): Int {
                    if (n++ >= 10_000) return -1
                    Thread.sleep(20)
                    return 1
                }

                // Bulk-read returns one byte per call, like a socket with little data available
                // (the base-class implementation would block until the buffer is full).
                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (n++ >= 10_000) return -1
                    Thread.sleep(20)
                    b[off] = 1
                    return 1
                }
            }
        val start = System.currentTimeMillis()
        assertNull(PairingServer.readBounded(trickle, 10_000, start + 400))
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun readExactly_readsFullBodyBeforeDeadline() {
        val data = ByteArray(512) { it.toByte() }
        val out = PairingServer.readBounded(ByteArrayInputStream(data), 512, System.currentTimeMillis() + 5_000)
        assertArrayEquals(data, out)
    }
}
