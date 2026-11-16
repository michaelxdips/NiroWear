package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fence/overflow measurements from the 2026-11-06 renderer audit. Every expectation below
 * was measured on the parser before the fix; each case produced a user-visible lie on the watch (a
 * code sample rewritten, a formula torn out of a sample, or a clamped table reported as complete).
 */
class RendererFenceAndOverflowTest {
    private fun text(c: List<InlineContent>) = c.joinToString("") { it.text }

    @Test
    fun quotedFenceDoesNotSwallowTheNextTablesOverflowCount() {
        // The overflow scan opened a fence on a properly marked quoted fence and could never close it
        // (open tolerated the stripped line, close did not), so every later table queued nothing: the
        // third cell vanished with no "… 1 more cell" line.
        val blocks = ResponseParser.parse("> ```\n> code\n> ```\n\n| a | b |\n|---|---|\n| 1 | 2 | 3 |\n")
        assertEquals("code", (blocks[0] as ContentBlock.Code).source)
        val table = blocks.filterIsInstance<ContentBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.headers.map { text(it) })
        assertEquals(1, table.droppedCells)
    }

    @Test
    fun aTopLevelFenceBodyKeepsItsListMarkers() {
        // One unmarked interior line voted "lazy" and the whole body was container-stripped, so a
        // shell/YAML sample rendered as different code than the model sent.
        val blocks = ResponseParser.parse("```\nsteps:\n- build\n- test\n```\n")
        assertEquals("steps:\n- build\n- test", (blocks.single() as ContentBlock.Code).source)
    }

    @Test
    fun aFenceIndentedInsideAListIsStillAFence() {
        // Fence detection was anchored on the raw line (at most three columns), while indentation for
        // the math test is measured relative to the list item. The fence was therefore invisible, its
        // `$$x$$` body line was torn out as display math, and the sample rendered as an empty code
        // block, a formula and a stray "```".
        val blocks = ResponseParser.parse("- a\n  - b\n    ```\n    \$\$x\$\$\n    ```\n")
        assertEquals(listOf(0, 1), blocks.filterIsInstance<ContentBlock.ListEntry>().map { it.depth })
        assertTrue("no display math may be extracted from a fence body", blocks.none { it is ContentBlock.Math })
        assertEquals("\$\$x\$\$", blocks.filterIsInstance<ContentBlock.Code>().single().source)
    }

    @Test
    fun tablesInsideListItemsReportTheirClampedCells() {
        // The scan compared ABSOLUTE indentation, so a table indented past the item's content indent
        // looked like an indented code block, and containerKey clamped the indent to three columns so a
        // four-wide marker never matched its own continuation rows. Both dropped the queue entry: the
        // table was clamped by the library with no notice, and the next table claimed its count.
        val wideMarker = ResponseParser.parse("10. | a | b |\n    |---|---|\n    | 1 | 2 | 3 |\n")
        assertEquals(1, wideMarker.filterIsInstance<ContentBlock.Table>().single().droppedCells)
        val indented = ResponseParser.parse("- a\n\n    | a | b |\n    |---|---|\n    | 1 | 2 | 3 |\n")
        assertEquals(1, indented.filterIsInstance<ContentBlock.Table>().single().droppedCells)
    }

    @Test
    fun displayMathInsideATableRowDoesNotShredTheTable() {
        // Measured: the mid-line `$$` hunt fired inside a row, so the renderer emitted a blank one-cell
        // table, the formula, and a stray "| y |" paragraph — the row's real content destroyed and the
        // clamped-cell count wrong. A row is cell text: the table must survive.
        val blocks = ResponseParser.parse("| a | b |\n|---|---|\n| \$\$x^2\$\$ | y |\n")
        val table = blocks.filterIsInstance<ContentBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.headers.map { text(it) })
        assertEquals(1, table.rows.size)
        assertTrue("no display math may be torn out of a row", blocks.none { it is ContentBlock.Math })
        assertTrue("no row tail may become a paragraph", blocks.none { it is ContentBlock.Paragraph })
        assertTrue("the cell keeps the formula", text(table.rows.single()[0]).contains("x^2"))
    }

    @Test
    fun anHtmlBlockDoesNotQueueAPhantomOverflowCount() {
        // The scan tracked fences but not HTML blocks, while the library builds no table inside one: the
        // phantom count was popped by the next REAL table, which then claimed hidden cells it never had.
        val blocks =
            ResponseParser.parse(
                "<div>\n| a | b |\n|---|---|\n| 1 | 2 | 3 |\n</div>\n\n| H | K |\n|---|---|\n| 1 | 2 |\n",
            )
        assertEquals(
            "the clean table hid nothing",
            0,
            blocks.filterIsInstance<ContentBlock.Table>().single().droppedCells,
        )
    }

    @Test
    fun aProseLineWithBackticksIsNotAFence() {
        // CommonMark forbids a backtick in a backtick fence's info string. Opening a fence there
        // suppressed display-math extraction for the rest of the chunk, so a formula a few lines later
        // rendered as literal dollars inside prose.
        val blocks = ResponseParser.parse("\$\$E=mc^2\$\$\n\n```npm i``` runs it.\n\n\$\$a^2+b^2=c^2\$\$\n")
        assertEquals(
            listOf("E=mc^2", "a^2+b^2=c^2"),
            blocks.filterIsInstance<ContentBlock.Math>().map { it.source },
        )
        assertTrue(blocks.none { it is ContentBlock.Code })
    }

    @Test
    fun aMidlineDisplayAfterAListFormulaIsTopLevel() {
        // The mid-line branch reused the previous display's list level and dedent, so a top-level
        // formula was indented as if it were inside the list and the lines after it kept losing their
        // indent.
        val blocks =
            ResponseParser.parse("- a\n  - b\n\n    \$\$x\$\$\nArea: \$\$A=\\pi r^2\$\$\n")
        val mid = blocks.filterIsInstance<ContentBlock.Math>().first { it.source == "A=\\pi r^2" }
        assertEquals("a mid-line display is top-level prose", 0, mid.depth)
    }

    @Test
    fun aListTableIndentedDeeperThanItsMarkerStillReportsClampedCells() {
        // Measured: the header's `1. ` masked to three columns while its rows were indented four, so
        // the keys differed, the scan skipped the region and the row's extra cell was dropped with no
        // "… 1 more cell" line. Four columns under a three-wide marker is still the same list item
        // (only marker-width + 4 starts an indented code block), and the library does build the table.
        for (source in
            listOf(
                "1. | a | b |\n    |---|---|\n    | 1 | 2 | 3 |\n",
                "- | a | b |\n   |---|---|\n   | 1 | 2 | 3 |\n",
            )) {
            assertEquals(source, 1, ResponseParser.parse(source).filterIsInstance<ContentBlock.Table>().single().droppedCells)
        }
    }

    @Test
    fun aQuoteHeaderOverAListDelimiterIsNotATable() {
        // containerKey masked quote markers to spaces, so a quoted header and a list delimiter compared
        // equal: the scan queued a count for a table the library never builds, and the real table after
        // it reported zero hidden cells.
        val blocks =
            ResponseParser.parse("> | a | b |\n- |---|---|\n\n| x | y |\n|---|---|\n| 1 | 2 | 3 |\n")
        assertTrue("no table may be built from a quote header over a list delimiter", blocks.size >= 3)
        assertEquals(1, blocks.filterIsInstance<ContentBlock.Table>().single().droppedCells)
    }
}
