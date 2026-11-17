package com.nirowear.renderer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile-input regression tests for the renderer.
 *
 * Assistant output is untrusted, and `ResponseParser.parse` runs inside a coroutine with no
 * try/catch at the call site, so a `StackOverflowError` escaping here kills the app. Each case below
 * is a few hundred bytes of Markdown that a provider could return.
 */
class HostileMarkdownTest {
    private fun parses(input: String): Boolean {
        // A StackOverflowError is an Error, not an Exception: catching only Exception would let the
        // real defect pass this test.
        return try {
            ResponseParser.parse(input)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Test
    fun deepContainerMarkersDoNotOverflow() {
        // Repeated list/quote markers used to recurse once per marker inside the container regex.
        assertTrue("quote markers", parses("> ".repeat(2000) + "x"))
        assertTrue("ordered markers", parses("1. ".repeat(1500) + "x"))
        assertTrue("bullet markers", parses("- ".repeat(2000) + "x"))
        assertTrue("mixed markers", parses("> 1. - ".repeat(600) + "x"))
    }

    @Test
    fun deepContainerMarkersInsideFenceDoNotOverflow() {
        // The fence must hide math, but it must not let a hostile line reach the container scan
        // unguarded.
        assertTrue(parses("```\n" + "> ".repeat(2000) + "x\n```"))
    }

    @Test
    fun deeplyNestedBlockquotesDoNotOverflow() {
        val nested = (0 until 2000).joinToString("\n") { ">".repeat(it + 1) + " x" }
        assertTrue(parses(nested))
    }

    @Test
    fun longLineStaysLinear() {
        assertTrue(parses("-".repeat(1_000_000)))
        assertTrue(parses(">".repeat(200_000)))
    }

    @Test
    fun unbalancedLatexIsRejectedByGuard() {
        // `\{` is a literal brace, not a group delimiter, so it is legitimately balanced. The case
        // that must be refused is `\\{`, which is a line break followed by a real group.
        assertTrue(NativeMathGuard.isSafe("\\frac{1}{2}"))
        assertTrue(NativeMathGuard.isSafe("\\{"))
        assertTrue(!NativeMathGuard.isSafe("\\\\{"))
        assertTrue(!NativeMathGuard.isSafe("\\renewenvironment{x}{y}{z}"))
        assertTrue(!NativeMathGuard.isSafe("\\newenvironment{x}{y}{z}"))
        assertTrue(!NativeMathGuard.isSafe("\\includegraphics{/sdcard/x}"))
        assertTrue(!NativeMathGuard.isSafe("\\def\\x{y}"))
        assertTrue(!NativeMathGuard.isSafe("\\frac{1"))
    }
}
