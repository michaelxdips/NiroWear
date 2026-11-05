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
