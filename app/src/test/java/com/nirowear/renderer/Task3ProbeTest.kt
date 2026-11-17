package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharpened Task 3 probes: each prints actual parse output and asserts the REAL
 * defect, so a vacuous pass is impossible. Local-only, never committed.
 */
class Task3ProbeTest {
    private fun show(label: String, blocks: List<ContentBlock>) {
        println("### $label")
        blocks.forEachIndexed { i, b -> println("BLOCK$i=[$b]") }
    }

    @Test fun displayMathInQuote_isMathBlock() {
        val blocks = ResponseParser.parse("> \\[x^2\\]")
        show("quote-math", blocks)
        val math = blocks.filterIsInstance<ContentBlock.Math>()
        assertEquals("quoted display must be a Math block, got: $blocks", 1, math.size)
        assertTrue(math.single().complete)
        assertTrue(math.single().source.contains("x^2"))
    }

    @Test fun displayMathInList_isMathBlock() {
        val blocks = ResponseParser.parse("- \\[x^2\\]")
        show("list-math", blocks)
        assertTrue("listed display must be a Math block, got: $blocks", blocks.any { it is ContentBlock.Math })
    }

    @Test fun fenceInContainer_singleCodeBlock() {
        val blocks = ResponseParser.parse("> ```\ncode\n> ```\ntext")
        show("fence-container", blocks)
        val codes = blocks.filterIsInstance<ContentBlock.Code>()
        assertEquals("container fence must be ONE code block, got: $blocks", 1, codes.size)
        assertEquals("code", codes.single().source)
    }

    @Test fun flatten_keepsOrderAndNodes() {
        val deep = "> ".repeat(30) + "a `b` \\(c\\) d"
        val blocks = ResponseParser.parse(deep)
        show("deep-flatten", blocks)
        val text = blocks.joinToString(" ") { it.toString() }
        assertTrue("order must be document order, got: $text", text.indexOf("a") < text.indexOf("d"))
        assertTrue("code span b must survive, got: $text", text.contains("b"))
        assertTrue("math span c must survive, got: $text", text.contains("c"))
        assertFalse("reversed order proves stack bug, got: $text", text.contains("d a"))
    }

    @Test fun capContainers_neverRewritesFenceBytes() {
        val src = ">".repeat(60) + " ```kotlin" + "\ncode\n```"
        val blocks = ResponseParser.parse(src)
        show("cap-fence", blocks)
        val codes = blocks.filterIsInstance<ContentBlock.Code>()
        assertEquals("fence line must survive 60-deep containers, got: $blocks", 1, codes.size)
        assertEquals("kotlin", codes.single().language)
        assertEquals("code", codes.single().source)
    }

    @Test fun wideRow_reportsDroppedCells() {
        val row = (1..30).joinToString("|", "|", "|") { "c$it" }
        val blocks = ResponseParser.parse("|h|\n|---|\n$row")
        show("wide-row", blocks)
        val table = blocks.filterIsInstance<ContentBlock.Table>().first()
        assertEquals("dropped count must be explicit, got: $table", 29, table.droppedCells)
    }

    @Test fun midlineDollars_noStrayDollar() {
        val blocks = ResponseParser.parse("cost \$\$x\$\$ here")
        show("midline-dollars", blocks)
        assertTrue("mid-line \$\$ after space opens display math, got: $blocks", blocks.any { it is ContentBlock.Math })
        assertFalse("no stray dollar may leak into prose, got: $blocks", blocks.toString().contains("\$"))
        val strict = ResponseParser.parse("a\$\$x\$\$b")
        show("midword-dollars", strict)
        assertTrue("mid-word \$\$ stays literal, got: $strict", strict.none { it is ContentBlock.Math })
    }
}
