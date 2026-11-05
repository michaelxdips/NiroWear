package com.nirowear.chat

/**
 * Turn what someone types into an FTS5 MATCH expression.
 *
 * Raw input cannot be used: FTS5 treats `"`, `(`, `*`, `-`, `:` and the bare words AND/OR/NOT as
 * query syntax, so a search for `C++ (draft)` threw a syntax error and an empty result screen.
 * Every whitespace-separated token is emitted as a quoted phrase (an implicit AND), with an internal
 * quote doubled and a trailing `*` added so a prefix still matches the word being typed.
 *
 * Returns null when there is nothing searchable, which the caller renders as "no results".
 */
internal fun ftsQuery(input: String): String? {
    val terms =
        input
            .split(' ', '\t', '\n', '\r')
            .map { it.replace("\"", "\"\"") }
            .filter { it.isNotEmpty() }
            .take(8) // ponytail: 8 terms; raise only if a watch user needs longer queries.
    if (terms.isEmpty()) return null
    return terms.joinToString(" ") { "\"$it\"*" }
}
