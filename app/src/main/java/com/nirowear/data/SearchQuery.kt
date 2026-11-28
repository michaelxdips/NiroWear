package com.nirowear.data

/**
 * Turn what someone types into LIKE operands.
 *
 * SQLite's LIKE treats `%` and `_` as wildcards, so a search for `100%` or `snake_case` would match
 * far more than the user asked for; both are escaped here and the query supplies `ESCAPE '\'`.
 * Every whitespace-separated term is kept and the caller ANDs them, which is what a person means by
 * typing two words. At most 8 terms: a watch keyboard will not produce more, and an unbounded list
 * turns one query into an arbitrarily long WHERE clause.
 *
 * Returns an empty list for blank input, which the caller renders as "no results".
 */
internal fun likeTerms(input: String): List<String> =
    input
        .split(' ', '\t', '\n', '\r')
        .filter { it.isNotEmpty() }
        .take(8)
        .map { term ->
            buildString {
                for (char in term) {
                    if (char == '\\' || char == '%' || char == '_') append('\\')
                    append(char)
                }
            }
        }

internal fun unescapeLikeTerms(terms: List<String>): List<String> =
    terms
        .map { escaped ->
            buildString(escaped.length) {
                var i = 0
                while (i < escaped.length) {
                    if (escaped[i] == '\\' && i + 1 < escaped.length) i++
                    append(escaped[i])
                    i++
                }
            }
        }.filter { it.isNotEmpty() }

/**
 * Collapses whitespace and returns up to [maxChars] centered on the first matching term so
 * `searchMessages` never keeps multi-MB message bodies in memory for UI rendering.
 */
internal fun extractSearchSnippet(
    rawContent: String,
    terms: List<String>,
    maxChars: Int = 80,
): String = extractSearchSnippetPlain(rawContent, unescapeLikeTerms(terms), maxChars)

private fun firstTermIndex(
    text: String,
    plainTerms: List<String>,
    startIndex: Int = 0,
): Int? {
    var best = Int.MAX_VALUE
    for (plain in plainTerms) {
        if (plain.isEmpty()) continue
        val hit = text.indexOf(plain, startIndex = startIndex, ignoreCase = true)
        if (hit in 0 until best) best = hit
    }
    return if (best == Int.MAX_VALUE) null else best
}

private fun findWindowStart(
    raw: String,
    firstNonWs: Int,
    hitIndex: Int,
    backBudget: Int,
): Int {
    var i = hitIndex
    var count = 0
    var inWs = false
    while (i > firstNonWs && count < backBudget) {
        i--
        if (raw[i].isWhitespace()) {
            if (!inWs) {
                inWs = true
                count++
            }
        } else {
            inWs = false
            count++
        }
    }
    return i
}

private fun collapseForward(
    raw: String,
    start: Int,
    maxCollapsed: Int,
    trackIndex: Int = -1,
): Pair<String, Int> {
    val out = StringBuilder(maxCollapsed.coerceAtMost((raw.length - start).coerceAtLeast(0)))
    var i = start
    while (i < raw.length && raw[i].isWhitespace()) i++
    var pendingSpace = false
    var tracked = -1
    while (i < raw.length && out.length < maxCollapsed) {
        if (i == trackIndex && tracked < 0) {
            if (pendingSpace && out.isNotEmpty()) {
                if (out.length + 1 >= maxCollapsed) {
                    tracked = out.length
                    break
                }
                out.append(' ')
                pendingSpace = false
            }
            tracked = out.length
        }
        val c = raw[i]
        if (c.isWhitespace()) {
            pendingSpace = true
        } else {
            if (pendingSpace && out.isNotEmpty()) {
                if (out.length + 1 >= maxCollapsed) break
                out.append(' ')
            }
            pendingSpace = false
            out.append(c)
        }
        i++
    }
    return out.toString() to tracked
}

internal fun extractSearchSnippetPlain(
    rawContent: String,
    plainTerms: List<String>,
    maxChars: Int = 80,
): String {
    if (maxChars <= 0) return ""
    var firstNonWs = 0
    while (firstNonWs < rawContent.length && rawContent[firstNonWs].isWhitespace()) firstNonWs++
    if (firstNonWs >= rawContent.length) return ""
    val rawHit = firstTermIndex(rawContent, plainTerms, startIndex = firstNonWs)
    if (rawHit == null) {
        return collapseForward(rawContent, firstNonWs, maxChars).first
    }
    val windowStart = findWindowStart(rawContent, firstNonWs, rawHit, backBudget = maxChars)
    val hasLeadingContent = windowStart > firstNonWs
    val doubleBudget = if (maxChars > Int.MAX_VALUE / 2) Int.MAX_VALUE else maxChars * 2
    val (collapsed, hitInCollapsed) =
        collapseForward(rawContent, windowStart, maxCollapsed = doubleBudget, trackIndex = rawHit)
    if (!hasLeadingContent && collapsed.length <= maxChars) return collapsed
    if (!hasLeadingContent && (hitInCollapsed < 0 || hitInCollapsed + maxChars / 2 < maxChars)) {
        return collapsed.take(maxChars).trimEnd()
    }
    val start =
        if (hitInCollapsed < 0) {
            0
        } else {
            (hitInCollapsed - maxChars / 3).coerceIn(0, (collapsed.length - maxChars).coerceAtLeast(0))
        }
    val end = (start + maxChars).coerceAtMost(collapsed.length)
    val core = collapsed.substring(start, end).trim()
    return if (hasLeadingContent || start > 0) "…$core" else core
}
