package com.nirowear.renderer

import com.nirowear.historyKeysFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A large table and a long code fence must NOT compose as one item.
 *
 * Both used to be a single LazyColumn item: a 1000-row table measured ~8000 Text nodes in one pass and
 * a code fence had no cap at all. The reader now expands them into per-row / per-chunk units
 * ([renderUnitsFor]), so nothing is withheld and the cost is paid as the reader reaches each item.
 * These tests pin the expansion arithmetic — the reader's scroll anchors depend on it.
 */
class RenderUnitTest {
    private fun blocks(vararg block: ContentBlock) = block.mapIndexed { i, b -> i.toLong() to b }

    @Test
    fun smallBlocksStayOneUnitEach() {
        val blocks =
            blocks(
                ContentBlock.Paragraph(listOf(InlineContent("one"))),
                ContentBlock.Code("kotlin", "val a = 1\nval b = 2"),
                ContentBlock.Divider,
            )
        val keys = historyKeysFor(blocks)
        val units = renderUnitsFor(blocks, keys)
        assertEquals("short blocks must not be split", 3, units.size)
        assertTrue(units.all { it is RenderUnit.Whole })
        assertEquals(listOf("0:0", "1:0", "2:0"), units.map { it.key })
    }

    @Test
    fun longCodeFenceBecomesManyChunksAndKeepsEveryLine() {
        val lines = (1..200).map { "line $it" }
        val blocks = blocks(ContentBlock.Code("kotlin", lines.joinToString("\n")))
        val keys = historyKeysFor(blocks)
        val units = renderUnitsFor(blocks, keys).filterIsInstance<RenderUnit.CodeChunk>()

        assertEquals("200 lines at 24 per item", 9, units.size)
        assertEquals(
            "no line may be dropped",
            lines,
            units.flatMap { it.lines },
        )
        assertEquals("the language label appears once", listOf("kotlin"), units.mapNotNull { it.language })
        assertEquals("every chunk belongs to block 0", setOf(0), units.map { it.blockIndex }.toSet())
        assertEquals("unit keys are unique", units.size, units.map { it.key }.toSet().size)
    }

    @Test
    fun tableGetsOneUnitPerRowAndKeepsEveryRow() {
        val headers = listOf(listOf(InlineContent("H")))
        val rows = (0 until 120).map { r -> listOf(listOf(InlineContent("row $r"))) }
        val blocks = blocks(ContentBlock.Table(headers, rows))
        val keys = historyKeysFor(blocks)
        val units = renderUnitsFor(blocks, keys).filterIsInstance<RenderUnit.TableRowUnit>()

        assertEquals("one item per row, no cap", 120, units.size)
        assertEquals("no row withheld", (0 until 120).toList(), units.map { it.rowIndex })
        assertEquals("only the last row omits the rule", 1, units.count { it.last })
        assertTrue("the last row is the one flagged", units.last().last)
    }

    @Test
    fun headerOnlyTableStaysOneUnit() {
        val blocks = blocks(ContentBlock.Table(listOf(listOf(InlineContent("H"))), emptyList()))
        val keys = historyKeysFor(blocks)
        val units = renderUnitsFor(blocks, keys)
        assertEquals("a header-only table still draws its headers", 1, units.size)
        assertTrue(units.single() is RenderUnit.Whole)
    }

    @Test
    fun unitRangesMapBlocksBackToTheirItems() {
        val blocks =
            blocks(
                ContentBlock.Paragraph(listOf(InlineContent("intro"))),
                ContentBlock.Code("k", (1..60).joinToString("\n") { "l$it" }),
                ContentBlock.Paragraph(listOf(InlineContent("outro"))),
            )
        val keys = historyKeysFor(blocks)
        val units = renderUnitsFor(blocks, keys)
        val ranges = unitRangeByBlock(units, blocks.size)

        assertEquals(0..0, ranges[0])
        assertEquals("60 lines -> 3 chunks", 1..3, ranges[1])
        assertEquals(4..4, ranges[2])
        assertEquals("every unit is covered", units.size, ranges.sumOf { it.count() })
    }

    @Test
    fun codeLineCountBoundaryHandlesLfCrlfAndCr() {
        for (sep in listOf("\n", "\r\n", "\r")) {
            val exact24 = (1..CODE_LINES_PER_ITEM).joinToString(sep) { "line $it" }
            val b24 = blocks(ContentBlock.Code("kt", exact24))
            val u24 = renderUnitsFor(b24, historyKeysFor(b24))
            assertEquals("24 lines with sep must stay Whole", 1, u24.size)
            assertTrue(u24.single() is RenderUnit.Whole)

            val over25 = (1..(CODE_LINES_PER_ITEM + 1)).joinToString(sep) { "line $it" }
            val b25 = blocks(ContentBlock.Code("kt", over25))
            val u25 = renderUnitsFor(b25, historyKeysFor(b25)).filterIsInstance<RenderUnit.CodeChunk>()
            assertEquals("25 lines with sep must split into 2 chunks", 2, u25.size)
            assertEquals(CODE_LINES_PER_ITEM, u25[0].lines.size)
            assertEquals(1, u25[1].lines.size)
        }
    }
}
