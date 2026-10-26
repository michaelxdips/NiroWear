package com.nirowear.chat

import com.nirowear.data.StoredMessage
import com.nirowear.renderer.ContentBlock

/**
 * B3-03: coalescing decisions, extracted from the Compose effect so they can be tested on the JVM.
 *
 * The renderer only ever needs the LATEST accumulated text, so intermediate snapshots are waste.
 * But the wait must be a throttle, not a debounce: a debounce starves under a fast stream because
 * every arrival cancels the pending run before it parses.
 *
 * True when any message in this conversation has no memoised parse yet.
 */
internal fun needsParse(
    messages: List<StoredMessage>,
    conversationId: String?,
    previous: Map<StoredMessage, List<Pair<Long, ContentBlock>>>,
): Boolean = messages.any { it.conversationId == conversationId && previous[it] == null }

/**
 * Milliseconds to wait before parsing. 0 means "parse now".
 *
 * A negative elapsed time means the clock moved backwards (wall clock adjustment, NTP step). The
 * plan's original snippet clamped only the final subtraction, which returned a spurious 116 ms
 * wait for (now=900, last=1000, window=16) — its own test asserted 0 and failed. Treating a
 * backwards clock as "overdue" is both simpler and the safe direction: parse now, never invent a
 * wait from a timestamp that cannot be trusted.
 */
internal fun coalesceDelayMs(
    nowMs: Long,
    lastParseAtMs: Long,
    windowMs: Long,
): Long {
    val elapsed = nowMs - lastParseAtMs
    if (elapsed < 0L) return 0L
    return if (elapsed >= windowMs) 0L else windowMs - elapsed
}
