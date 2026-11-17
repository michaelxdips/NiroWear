package com.nirowear.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards B3-03 against the failure mode a naive debounce has: starvation.
 *
 * A delay-before-work coalescer drops every superseded run. If deltas keep arriving faster than the
 * window, a debounce cancels every run before it parses, so nothing renders while the stream is
 * live -- the answer appears blank until the provider pauses. Measured against a 4 ms cadence with
 * a 16 ms window: 0 parses in 400 ms.
 *
 * The production cadence is not under our control, so the implementation must guarantee progress
 * rather than wait for quiet. These tests model the throttle form: `lastParseAt` is remembered
 * across restarts, so the wait is skipped when a parse is already overdue.
 */
class StreamingParseStarvationTest {
    private val coalesceMs = 16L

    private class EffectHost(
        private val scope: CoroutineScope,
    ) {
        private var job: Job? = null

        fun restart(block: suspend () -> Unit) {
            job?.cancel()
            job = scope.launch { block() }
        }

        fun cancel() {
            job?.cancel()
        }
    }

    /**
     * The fixed production shape: throttle, remembering the last parse across restarts.
     *
     * The wait comes from the PRODUCTION `coalesceDelayMs`, not a copy of it. The previous version
     * inlined `if (since < coalesceMs) delay(coalesceMs - since)`, which meant this suite passed
     * even when the real function was broken — proven by forcing `coalesceDelayMs` to return 0 and
     * watching all of these tests stay green. A test that models the algorithm instead of calling
     * it cannot detect the algorithm changing.
     */
    private suspend fun throttledRun(
        scope: CoroutineScope,
        host: EffectHost,
        lastParseAt: AtomicLong,
        parseCount: AtomicInteger,
        durationMs: Long,
        deltaIntervalMs: Long,
    ) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < durationMs) {
            host.restart {
                val wait = coalesceDelayMs(System.currentTimeMillis(), lastParseAt.get(), coalesceMs)
                if (wait > 0) delay(wait)
                parseCount.incrementAndGet()
                lastParseAt.set(System.currentTimeMillis())
            }
            delay(deltaIntervalMs)
        }
    }

    @Test
    fun continuousFastDeltasStillProduceParses() =
        runBlocking {
            val parseCount = AtomicInteger(0)
            val lastParseAt = AtomicLong(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = EffectHost(scope)

            // A stream that never goes quiet: a delta every 4 ms for 400 ms, four times faster
            // than the coalescing window.
            throttledRun(scope, host, lastParseAt, parseCount, 400L, 4L)
            host.cancel()
            scope.cancel()

            val parses = parseCount.get()
            println("throttle, continuous-fast: $parses parses over 400ms at 4ms/delta")
            assertTrue(
                "a continuous stream must still render progressively; got $parses. Zero means the " +
                    "coalescer starved and the answer would stay blank until the stream paused.",
                parses > 0,
            )
        }

    @Test
    fun progressRateIsUsefulNotMerelyNonZero() =
        runBlocking {
            // With a 16 ms throttle and a 4 ms cadence over 480 ms, a correct implementation yields
            // roughly duration/window parses (~30). Assert a floor well above "barely alive".
            val parseCount = AtomicInteger(0)
            val lastParseAt = AtomicLong(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = EffectHost(scope)

            throttledRun(
                scope,
                lastParseAt = lastParseAt,
                host = host,
                parseCount = parseCount,
                durationMs = 480L,
                deltaIntervalMs = 4L,
            )
            host.cancel()
            scope.cancel()

            val parses = parseCount.get()
            println("throttle rate: $parses parses over 480ms (ideal ~${480 / coalesceMs})")
            assertTrue("continuous stream produced no parses", parses > 0)
            assertTrue(
                "progress must keep up with a fast stream, not stall at one or two parses; got $parses",
                parses >= 5,
            )
        }

    @Test
    fun debounceFormStarvationIsReproducible() =
        runBlocking {
            // Documents WHY the throttle form is required. The debounce shape (no remembered
            // last-parse time) starves under the same input, which is what the first version of
            // this fix did.
            val window = 300L
            val debounceParses = countDebounceParses(window)

            // Measure the throttle form over the SAME window in the SAME run, so the assertion is a
            // comparison instead of an absolute number. The absolute bound was load-fragile: it
            // asserted `<= 2`, and one scheduling stall under full-suite load let a third delay
            // complete, failing the suite for a reason unrelated to the algorithm (measured: 3).
            val throttleCount = AtomicInteger(0)
            val throttleLast = AtomicLong(0)
            val throttleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val throttleHost = EffectHost(throttleScope)
            throttledRun(throttleScope, throttleHost, throttleLast, throttleCount, window, 4L)
            throttleHost.cancel()
            throttleScope.cancel()
            val throttleParses = throttleCount.get()

            println(
                "starvation over ${window}ms at 4ms/delta: debounce=$debounceParses throttle=$throttleParses",
            )
            // The property worth pinning: the debounce makes an order of magnitude LESS progress than
            // the throttle on identical input. Both numbers move together under load, so the
            // comparison stays meaningful on a busy machine.
            assertTrue(
                "the throttle form must actually make progress; got $throttleParses",
                throttleParses >= 5,
            )
            assertTrue(
                "the debounce form must starve relative to the throttle; " +
                    "debounce=$debounceParses throttle=$throttleParses. If these are comparable the " +
                    "test no longer documents the defect it exists to record",
                debounceParses * 4 <= throttleParses,
            )
        }

    /** The naive debounce shape: every restart cancels the pending window before it can fire. */
    private suspend fun countDebounceParses(window: Long): Int {
        val parseCount = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = EffectHost(scope)

        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < window) {
            host.restart {
                delay(coalesceMs)
                parseCount.incrementAndGet()
            }
            delay(4L)
        }
        host.cancel()
        scope.cancel()

        println("debounce, continuous-fast: ${parseCount.get()} parses over ${window}ms at 4ms/delta")
        return parseCount.get()
    }
}
