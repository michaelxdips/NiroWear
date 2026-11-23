package com.nirowear.chat

import com.nirowear.data.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Verifies P1 (`retryHistoryReads`): bounded retry recovery, terminal fallback without crashing passive
 * UI collectors, startup rethrow when `catchTerminal = false`, and immediate cancellation propagation.
 */
class HistoryFlowRetryTest {
    @Test
    fun recoversAfterTransientFailuresBeforeBudgetExhaustion() =
        runBlocking {
            var attempts = 0
            var retries = 0
            var exhausted = 0
            val result =
                flow {
                    attempts++
                    if (attempts < 3) throw IOException("transient $attempts")
                    emit(listOf(Conversation(id = "c1", title = "Recovered", updatedAt = 1L)))
                }.retryHistoryReads(
                    maxRetries = 3,
                    backoffMs = { 0L },
                    onRetrying = { retries++ },
                    onExhausted = { exhausted++ },
                    catchTerminal = true,
                ).first()

            assertEquals(listOf("c1"), result.map { it.id })
            assertEquals(2, retries)
            assertEquals(0, exhausted)
        }

    @Test
    fun exhaustedPassiveCollectorEmitsEmptyListAfterSignallingError() =
        runBlocking {
            var retries = 0
            var exhausted = 0
            val result =
                flow<List<Conversation>> { throw IOException("persistent sqlite failure") }
                    .retryHistoryReads(
                        maxRetries = 3,
                        backoffMs = { 0L },
                        onRetrying = { retries++ },
                        onExhausted = { exhausted++ },
                        catchTerminal = true,
                    ).first()

            assertEquals(emptyList<Conversation>(), result)
            assertEquals(3, retries)
            assertTrue(exhausted >= 1)
        }

    @Test(expected = IOException::class)
    fun startupInitReadRethrowsWhenCatchTerminalDisabled() =
        runBlocking {
            flow<List<Conversation>> { throw IOException("init failure") }
                .retryHistoryReads(
                    maxRetries = 2,
                    backoffMs = { 0L },
                    onRetrying = {},
                    onExhausted = {},
                    catchTerminal = false,
                ).first()
            Unit
        }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverSwallowedOrRetried() =
        runBlocking {
            var retries = 0
            flow<List<Conversation>> { throw CancellationException("cancelled") }
                .retryHistoryReads(
                    maxRetries = 3,
                    backoffMs = { 0L },
                    onRetrying = { retries++ },
                    onExhausted = {},
                    catchTerminal = true,
                ).first()
            Unit
        }
}
