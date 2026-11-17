package com.nirowear.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * B3-03 regression: a burst of streaming deltas must not cause one full re-parse per delta.
 *
 * This models the production shape precisely: `LaunchedEffect(storedMessages, conversationId)`
 * is a SINGLE effect that Compose cancels and restarts on each emission. It is not a fan-out
 * of independent coroutines -- an earlier version of this test launched one coroutine per
 * delta, which nothing cancels, and so could never fail. The restart is the mechanism.
 *
 * The property: with a suspension point before the work, a restart cancels the pending
 * coroutine before it parses. Without one, every superseded run completes its parse and only
 * then discards the result.
 *
 * Verified failing against the pre-fix loop (no delay) and passing against the fixed one.
 */
class StreamingParseCoalescingTest {
    private val coalesceMs = 16L

    /**
     * Window used by the collapse test.
     *
     * The property being tested is "40 deltas inside one window collapse", so the window must be
     * comfortably LONGER than the producer cadence on a loaded machine. At the production 16 ms the
     * two were the same order of magnitude: under `testDebugUnitTest` load a scheduling stall alone
     * exceeded 16 ms between deltas, every delta became its own window, and the test failed with
     * "got 34 parses for 40 deltas" while passing in isolation. 250 ms makes the collapse a property
     * of the algorithm instead of a property of the CPU that happens to be free.
     */
    private val burstWindowMs = 250L

    /** Stands in for `LaunchedEffect`: holds one job, cancels it and starts another. */
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

    private fun burst(
        count: Int,
        coalesce: Long,
        parseCount: AtomicInteger,
        lastDone: CompletableDeferred<Unit>,
        cadenceMs: Long = 0L,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = EffectHost(scope)
        val lastParseAt = AtomicLong(0L)

        repeat(count) { index ->
            host.restart {
                // The wait comes from the PRODUCTION coalesceDelayMs, not a copy of it. The
                // previous version inlined `if (coalesce > 0) delay(coalesce)`, which made this
                // suite pass even when the real function was broken: forcing coalesceDelayMs to
                // return 0 left all six tests green. Modelling an algorithm cannot detect it
                // changing; calling it can.
                val wait = coalesceDelayMs(System.currentTimeMillis(), lastParseAt.get(), coalesce)
                if (wait > 0) delay(wait)
                parseCount.incrementAndGet()
                lastParseAt.set(System.currentTimeMillis())
                if (index == count - 1) lastDone.complete(Unit)
            }
            // cadence 0 = deltas arrive back-to-back inside one window (the burst being coalesced).
            // A non-zero cadence gives each restart room to finish, which is what "no coalescing"
            // means: every emission really does pay for its own parse.
            if (cadenceMs > 0) delay(cadenceMs) else kotlinx.coroutines.yield()
        }
        lastDone.await()
        delay(coalesce * 2L + 250L)
        host.cancel()
        scope.cancel()
        parseCount.get()
    }

    @Test
    fun burstOfDeltasCollapsesToFewParses() {
        val count = 40
        val parses = burst(count, burstWindowMs, AtomicInteger(0), CompletableDeferred())
        assertTrue(
            "$count deltas must not produce $count parses; got $parses",
            parses < count,
        )
        assertTrue("coalescing should collapse a burst; got $parses parses for $count deltas", parses <= 8)
    }

    @Test
    fun preFixLoopParsesOncePerDelta() {
        // Documents the defect: with no suspension before the work, every delta parses.
        //
        // The LAST restart can be superseded by the effect leaving composition before it runs, so the
        // exact count is 39 or 40 depending on scheduling. Asserting exactly 40 made this test fail
        // about one run in four; the claim it protects is "no coalescing means ~one parse per delta",
        // not "the final one is guaranteed".
        val count = 40
        val parses = burst(count, 0L, AtomicInteger(0), CompletableDeferred(), cadenceMs = 3L)
        assertTrue("pre-fix behaviour parses every delta; got $parses of $count", parses >= count - 1)
        assertTrue("the defect is that $count deltas cost ~$count parses; got $parses", parses > 8)
    }

    @Test
    fun finalStateIsAlwaysParsed() =
        runBlocking {
            // Coalescing must not drop the final emission: the answer has to finish rendering.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = EffectHost(scope)
            val parseCount = AtomicInteger(0)

            repeat(10) { index ->
                val settled = CompletableDeferred<Unit>()
                host.restart {
                    delay(coalesceMs)
                    parseCount.incrementAndGet()
                    settled.complete(Unit)
                }
                settled.await()
                delay(coalesceMs + 8L)
            }
            host.cancel()
            scope.cancel()

            assertEquals("every settled emission must be parsed exactly once", 10, parseCount.get())
        }
}
