package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display math and tables inside nested containers, measured on the parser.
 *
 * Both cases were reproduced before the fix:
 * - depth-four bullet + `$$x^2$$`: the marker at six columns was not measurable (the scan capped
 *   leading spaces at three), so `listContentIndent` stayed at the depth-two value, the formula
 *   measured as four-column-indented code, and the reader showed the literal `$x^2$` runs.
 * - depth-two bullet + `$$x$$` then prose: the math flush ends the document chunk, so the following
 *   four-column-indented line was parsed in a fresh document where four columns mean an indented code
 *   block — prose rendered as monospace code.
 */
class RendererNestedListMathTest {
    private fun inline(c: List<InlineContent>) = c.joinToString("") { it.text }

    @Test
    fun displayMathSurvivesFourLevelsOfNesting() {
        val blocks = ResponseParser.parse("- a\n  - b\n    - c\n      - d\n\n        \$\$x^2\$\$\n")
        val math = blocks.filterIsInstance<ContentBlock.Math>().single()
        assertEquals("x^2", math.source)
        assertTrue(
            "the formula must not survive as literal dollars: " + blocks.filterIsInstance<ContentBlock.ListEntry>(),
            blocks.none { it is ContentBlock.ListEntry && inline(it.content).contains('$') },
        )
    }

    @Test
    fun proseAfterDisplayMathInAListIsNotACodeBlock() {
        val blocks = ResponseParser.parse("- a\n  - b\n    \$\$x\$\$\n    tail\n")
        assertEquals("x", blocks.filterIsInstance<ContentBlock.Math>().single().source)
        assertTrue("prose must not become a code block", blocks.none { it is ContentBlock.Code })
        assertEquals(
            "the line after the formula is still a paragraph",
            listOf("tail"),
            blocks.filterIsInstance<ContentBlock.Paragraph>().map { inline(it.content) },
        )
        // A code block that is genuinely nested in the item (four columns past the item indent)
        // must still read as code: the dedent removes only the item's own indent.
        val withCode = ResponseParser.parse("- a\n  - b\n    \$\$x\$\$\n        code\n")
        assertTrue(
            "a block four columns past the item indent is still code",
            withCode.filterIsInstance<ContentBlock.Code>().any { it.source.trim() == "code" },
        )
    }

    @Test
    fun aDisplayInsideAListKeepsTheItemsLevel() {
        // The formula is extracted before the list is parsed, so the bullet is gone; without a level
        // the display sat flush against the screen edge inside an indented item.
        assertEquals(
            0,
            ResponseParser.parse("- a\n\n  \$\$x\$\$\n").filterIsInstance<ContentBlock.Math>().single().depth,
        )
        assertEquals(
            1,
            ResponseParser.parse("- a\n  - b\n\n    \$\$x\$\$\n").filterIsInstance<ContentBlock.Math>().single().depth,
        )
        assertEquals(
            2,
            ResponseParser.parse("- a\n  - b\n    - c\n\n      \$\$x\$\$\n")
                .filterIsInstance<ContentBlock.Math>().single().depth,
        )
        assertEquals(
            "a top-level display has no indent",
            0,
            ResponseParser.parse("\$\$x\$\$").filterIsInstance<ContentBlock.Math>().single().depth,
        )
    }

    @Test
    fun tablesInsideQuotesAndListsReportTheirClampedCells() {
        // The overflow scan had to learn the same container rules as the parser; measured before the
        // fix: both of these reported droppedCells = 0, so the third cell vanished silently.
        val quoted = ResponseParser.parse("> | a | b |\n> |---|---|\n> | 1 | 2 | 3 |\n")
        assertEquals(1, quoted.filterIsInstance<ContentBlock.Table>().single().droppedCells)
        val listed = ResponseParser.parse("- item\n\n  | a | b |\n  |---|---|\n  | 1 | 2 | 3 |\n")
        assertEquals(1, listed.filterIsInstance<ContentBlock.Table>().single().droppedCells)
        val nested = ResponseParser.parse("- a\n  - b\n\n    | x | y |\n    |---|---|\n    | 1 | 2 | 3 |\n")
        assertEquals(1, nested.filterIsInstance<ContentBlock.Table>().single().droppedCells)
    }
}
