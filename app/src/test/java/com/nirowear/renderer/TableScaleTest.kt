package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A large table must parse into ONE block (the renderer caps what it draws), and the cap must be a
 * documented number rather than an accident.
 *
 * The renderer composes a table inside a single LazyColumn item, so a 1000-row table measured
 * ~8000 Text nodes in one pass — an ANR candidate on a watch. `DocumentBlock` now renders at most
 * `MAX_TABLE_ROWS` and states how many were withheld. This test pins the parser side so the two
 * cannot drift apart silently.
 */
class TableScaleTest {
    private fun table(rows: Int, cols: Int = 4): String =
        buildString {
            append("| ").append((1..cols).joinToString(" | ") { "H$it" }).append(" |\n")
            append("|").append(" --- |".repeat(cols)).append("\n")
            repeat(rows) { r ->
                append("| ").append((1..cols).joinToString(" | ") { "r$r c$it" }).append(" |\n")
            }
        }

    @Test
    fun thousandRowTableIsOneBlock() {
        val blocks = ResponseParser.parse(table(1000))
        val tables = blocks.filterIsInstance<ContentBlock.Table>()
        assertEquals("a table must stay one block so the renderer can cap it", 1, tables.size)
        assertEquals("the parser must not drop rows; the renderer decides", 1000, tables[0].rows.size)
    }

    @Test
    fun tableKeepsItsHeaderAndColumnCount() {
        val tables = ResponseParser.parse(table(5, cols = 3)).filterIsInstance<ContentBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(3, tables[0].headers.size)
        assertEquals(5, tables[0].rows.size)
        assertTrue("cells must be preserved", tables[0].rows[0].size == 3)
    }

    @Test
    fun wideRowIsClampedToTheHeaderWidthNotSilentlyExtended() {
        // GFM clamps body cells to the header count; pin the actual behaviour rather than assume.
        val src = """
            | A | B |
            | --- | --- |
            | 1 | 2 | 3 |
        """.trimIndent()
        val t = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>().first()
        assertTrue("row must not exceed the header width; got ${t.rows[0].size}", t.rows[0].size <= 2)
    }
}
