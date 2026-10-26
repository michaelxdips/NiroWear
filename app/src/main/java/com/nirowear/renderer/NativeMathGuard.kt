package com.nirowear.renderer

/**
 * Admission check for untrusted LaTeX before it reaches the typesetter.
 *
 * Split out of [NativeMathRenderer] so the rules are unit-testable on the JVM: the real parser needs
 * an Android `Context` and cannot run off-device.
 *
 * The contract is deliberately conservative. Anything that can define, redefine, include a file or
 * force an unbounded box is refused, and the caller falls back to readable source text. A refused
 * formula is a cosmetic loss; an admitted hostile one is a crash or a huge allocation.
 */
internal object NativeMathGuard {
    // Longest names first: the regex engine takes the first alternative that matches at a position,
    // so `newenvironment` would otherwise match inside `renewenvironment` and leave the `re`
    // unmatched, letting the whole command through.
    private val FORBIDDEN =
        Regex(
            "\\\\(?:" +
                listOf(
                    // file access
                    "includegraphics",
                    "includeonly",
                    "include",
                    "input",
                    "openin",
                    "openout",
                    "read",
                    // definitions and redefinitions, longest first
                    "renewenvironment",
                    "newenvironment",
                    "providecommand",
                    "renewcommand",
                    "newcommand",
                    "DeclareMathSizes",
                    "jlmExternalFont",
                    "jlmDynamic",
                    "jlmXML",
                    "newcount",
                    "newdimen",
                    "gdef",
                    "edef",
                    "xdef",
                    "def",
                    "let",
                    "csname",
                    // boxes and transforms that can force arbitrary size or layout
                    "reflectbox",
                    "resizebox",
                    "rotatebox",
                    "scalebox",
                    "raisebox",
                    "colorbox",
                    "hspace",
                    "vspace",
                    "hskip",
                    "vskip",
                    "kern",
                    "rule",
                    "magnification",
                ).joinToString("|") + ")",
        )

    private const val MAX_SOURCE = 4096
    private const val MAX_GROUPS = 128

    /** True only when the source is safe to hand to the typesetter. */
    fun isSafe(source: String): Boolean {
        if (source.length > MAX_SOURCE) return false
        if (source.count { it == '{' } > MAX_GROUPS) return false
        if (FORBIDDEN.containsMatchIn(source)) return false
        return balanced(source)
    }

    /**
     * Counts real group delimiters.
     *
     * Only a small set of characters is actually escaped by a backslash in LaTeX: a literal brace
     * (`\{`), a backslash (`\\`), and the formatting commands. A backslash before an ordinary letter
     * starts a command name (`\frac`), not an escape, so the following brace is a real delimiter.
     * The previous check skipped the character after every backslash, which let `\{` and `\\{`
     * blind the counter.
     */
    private fun balanced(source: String): Boolean {
        var depth = 0
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (char == '\\' && index + 1 < source.length) {
                when (source[index + 1]) {
                    '{', '}', '\\', '%', '$', '&', '#', '_' -> index += 2
                    else -> index += 1 // a command name; the next character stands on its own
                }
                continue
            }
            if (char == '{') depth++
            if (char == '}') {
                depth--
                if (depth < 0) return false
            }
            index++
        }
        return depth == 0
    }
}
