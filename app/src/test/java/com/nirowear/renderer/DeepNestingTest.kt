package com.nirowear.renderer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterises how deep the parser actually nests, so a recursion claim is measured rather than
 * assumed.
 *
 * An audit reported `StackOverflowError` at 3000 `*` on a 1 MB stack. That did not reproduce here
 * with bare star runs or repeated `*a*` pairs, so this test records the real block shape instead of
 * asserting a crash that may not exist. If a future change makes nesting deeper, these assertions
 * are where it shows up.
 */
class DeepNestingTest {
    @Test
    fun bareStarRunParsesToSomethingFinite() {
        val blocks = ResponseParser.parse("*".repeat(20000))
        assertTrue("must return a finite block list", blocks.isNotEmpty())
        assertTrue("must not explode into thousands of blocks; got ${blocks.size}", blocks.size < 100)
    }

    @Test
    fun repeatedEmphasisPairsParseToSomethingFinite() {
        val blocks = ResponseParser.parse((1..20000).joinToString("") { "*a*" })
        assertTrue(blocks.isNotEmpty())
        assertTrue("block count must stay bounded; got ${blocks.size}", blocks.size < 100)
    }

    @Test
    fun deepBlockquoteIsBounded() {
        val blocks = ResponseParser.parse(">".repeat(200) + " quoted")
        assertTrue(blocks.isNotEmpty())
        assertTrue("nested quotes must collapse, not fan out; got ${blocks.size}", blocks.size < 200)
    }

    @Test
    fun deepListIsBounded() {
        val blocks = ResponseParser.parse((1..120).joinToString("\n") { "  ".repeat(it) + "- item $it" })
        assertTrue(blocks.isNotEmpty())
        assertTrue("nested list must stay bounded; got ${blocks.size}", blocks.size < 400)
    }

    @Test
    fun longFlatAnswerStaysOneBlockPerParagraph() {
        val source = (1..500).joinToString("\n\n") { "Paragraph $it with some ordinary text." }
        val blocks = ResponseParser.parse(source)
        assertTrue("500 paragraphs should stay near 500 blocks; got ${blocks.size}", blocks.size in 400..600)
    }
}
