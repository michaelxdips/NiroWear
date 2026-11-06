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
 * The parse memo keeps raw content plus the block tree per message, so it must not grow across
 * every conversation ever opened. The open screen only shows one conversation, and the memo is
 * rebuilt from the live Room list on each switch, so keeping the last few is enough.
 */
internal const val MAX_RETAINED_CONVERSATIONS = 3

/**
 * Drops memo entries whose conversation fell out of the retained set. Pure so it is testable
 * without Compose; the caller owns the recency list.
 */
internal fun evictStaleConversations(
    previous: Map<StoredMessage, List<Pair<Long, ContentBlock>>>,
    retained: Collection<String>,
): Map<StoredMessage, List<Pair<Long, ContentBlock>>> {
    if (previous.keys.all { it.conversationId in retained }) return previous
    return previous.filterKeys { it.conversationId in retained }
}

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

/**
 * Recency list with [id] moved to the end and bounded to [MAX_RETAINED_CONVERSATIONS].
 *
 * Extracted from the reader effect so the eviction order is testable on the JVM. The previous test
 * computed this expression itself and fed the result back in, so dropping the move-to-end here (or
 * the bound) changed nothing in any suite — the production line was untested.
 */
internal fun nextRecency(
    recent: List<String>,
    id: String?,
): List<String> {
    if (id == null) return recent
    return (recent - id + id).takeLast(MAX_RETAINED_CONVERSATIONS)
}

/**
 * The memo update the reader effect performs: MERGE the fresh parse into what is already memoised,
 * then bound the result by recency.
 *
 * The effect used to store `rows` alone — the open conversation's messages — so the eviction filter
 * could never keep another conversation and the recency list was inert, which meant History
 * A -> B -> A re-parsed all of A on `Dispatchers.Default` while the user waited. Extracted here so
 * the merge itself is pinned by a test instead of only the pieces around it.
 */
internal fun mergeParsed(
    previous: Map<StoredMessage, List<Pair<Long, ContentBlock>>>,
    parsed: Map<StoredMessage, List<Pair<Long, ContentBlock>>>,
    recent: List<String>,
    open: String?,
): Pair<Map<StoredMessage, List<Pair<Long, ContentBlock>>>, List<String>> {
    val next = nextRecency(recent, open)
    return evictStaleConversations(previous + parsed, next) to next
}
