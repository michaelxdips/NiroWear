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
    //
    // The trailing `(?![a-zA-Z])` is a NAME boundary, not decoration: without it `\def` matched
    // `\definecolor`, `\let` matched any word starting with "let", and a legitimate macro was refused
    // for sharing a prefix with a hostile one.
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
                    //
                    // Spacing commands (\hspace, \vspace, \hskip, \vskip, \kern) are NOT here: they are
                    // ordinary layout in real formulas, they define and include nothing, and refusing
                    // them silently degraded an honest formula to monospace source.
                    //
                    // \rule is not listed here either — it is an honest punctuation glyph in a real
                    // formula — but it takes EXPLICIT width/height dimensions, so it is bounded by
                    // [MAX_RULE_DIMENSION] below instead: `\rule{8000pt}{100pt}` asked the typesetter
                    // for a canvas no watch can allocate, before any bitmap bound could apply.
                    "reflectbox",
                    "resizebox",
                    "rotatebox",
                    "scalebox",
                    "raisebox",
                    "colorbox",
                    "magnification",
                ).joinToString("|") + ")(?![a-zA-Z])",
        )

    private const val MAX_SOURCE = 4096
    private const val MAX_GROUPS = 128

    /**
     * Longest side a `\rule` may request, in its own units.
     *
     * The reference viewport is 466 px ≈ 240 pt; 600 leaves headroom for a full-width rule without
     * admitting a dimension the typesetter would turn into a canvas the watch cannot allocate.
     */
    private const val MAX_RULE_DIMENSION = 600.0

    private val RULE_NAME = Regex("\\\\rule(?![a-zA-Z])")

    private val RULE =
        Regex(
            "\\\\rule(?![a-zA-Z])\\s*(?:\\[[^\\]]*\\])?\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}",
        )

    private val DIMENSION =
        Regex("\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:pt|pc|bp|mm|cm|in|em|ex|px|sp|dp)?\\s*")

    /**
     * Bounds `\rule`'s explicit dimensions.
     *
     * A `\rule` whose two size groups are not plain bounded numbers is refused: the guard cannot
     * prove the result fits, and a refusal costs only the typeset form (the source text is shown).
     */
    private fun ruleSizesAreBounded(source: String): Boolean {
        if (!RULE_NAME.containsMatchIn(source)) return true
        val matches = RULE.findAll(source).toList()
        if (matches.size != RULE_NAME.findAll(source).count()) return false
        return matches.all { match ->
            match.groupValues.drop(1).all { raw ->
                val value =
                    DIMENSION
                        .matchEntire(raw)
                        ?.groupValues
                        ?.get(1)
                        ?.toDoubleOrNull()
                value != null && value in 0.0..MAX_RULE_DIMENSION
            }
        }
    }

    /** True only when the source is safe to hand to the typesetter. */
    fun isSafe(source: String): Boolean {
        if (source.length > MAX_SOURCE) return false
        if (source.count { it == '{' } > MAX_GROUPS) return false
        if (FORBIDDEN.containsMatchIn(source)) return false
        if (!ruleSizesAreBounded(source)) return false
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
