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

private val SEARCH_WHITESPACE_RUN = Regex("\\s+")

/**
 * Collapses whitespace and returns up to [maxChars] centered on the first matching term so
 * `searchMessages` never keeps multi-MB message bodies in memory for UI rendering.
 */
internal fun extractSearchSnippet(
    rawContent: String,
    terms: List<String>,
    maxChars: Int = 80,
): String {
    val collapsed = rawContent.trim().replace(SEARCH_WHITESPACE_RUN, " ")
    if (collapsed.length <= maxChars) return collapsed
    val firstHit =
        terms
            .map { escaped ->
                buildString {
                    var i = 0
                    while (i < escaped.length) {
                        if (escaped[i] == '\\' && i + 1 < escaped.length) i++
                        append(escaped[i])
                        i++
                    }
                }
            }.filter { it.isNotEmpty() }
            .mapNotNull { plain ->
                collapsed.indexOf(plain, ignoreCase = true).takeIf { it >= 0 }
            }.minOrNull()
    if (firstHit == null || firstHit + maxChars / 2 < maxChars) {
        return collapsed.take(maxChars)
    }
    val start = (firstHit - maxChars / 3).coerceIn(0, (collapsed.length - maxChars).coerceAtLeast(0))
    val end = (start + maxChars).coerceAtMost(collapsed.length)
    val core = collapsed.substring(start, end).trim()
    return if (start > 0) "…$core" else core
}
