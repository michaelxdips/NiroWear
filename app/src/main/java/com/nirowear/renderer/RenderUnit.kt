package com.nirowear.renderer

import androidx.compose.runtime.Immutable

/** Maximum code fence lines per LazyColumn item to bound each measure pass. */
internal const val CODE_LINES_PER_ITEM = 24

/** Table rows emitted per LazyColumn item so large tables pay composition cost lazily. */
internal const val TABLE_ROWS_PER_ITEM = 1

/**
 * One LazyColumn item in the reader.
 *
 * The reader's anchors, heights and scroll targets are expressed in BLOCKS, while composition has to
 * happen per ITEM. A block that is cheap to compose is one `Whole`; a table or a long code fence
 * expands into many, so the cost of a huge one is paid as the reader reaches it.
 */
@Immutable
internal sealed interface RenderUnit {
    /** Stable identity for `LazyColumn(key=)` and the height map. */
    val key: String

    /** Index into the block list this unit belongs to, so block-level bookkeeping still works. */
    val blockIndex: Int

    data class Whole(
        override val key: String,
        override val blockIndex: Int,
        val block: ContentBlock,
    ) : RenderUnit

    data class CodeChunk(
        override val key: String,
        override val blockIndex: Int,
        /** Non-null only on the first chunk, so the language label appears once. */
        val language: String?,
        val lines: List<String>,
        val text: String = lines.joinToString("\n"),
    ) : RenderUnit

    data class TableRowUnit(
        override val key: String,
        override val blockIndex: Int,
        val headers: List<List<InlineContent>>,
        /** One row: a list of cells, each cell being its own inline runs. */
        val cells: List<List<InlineContent>>,
        val rowIndex: Int,
        /** A rule is drawn between rows, never after the last one. */
        val last: Boolean,
        /**
         * Cells the Markdown parser dropped from this table, non-zero only on the LAST row.
         *
         * The notice used to live in `DocumentBlock`'s single-item table branch. Expanding rows into
         * their own units made that branch unreachable for any table with a dropped cell, so the
         * reader silently lost the "… N more cells" line — the exact silent-clamp class the notice
         * exists to prevent. The count now rides on the unit that draws last.
         */
        val droppedCells: Int = 0,
    ) : RenderUnit
}

/**
 * Expands blocks into render units.
 *
 * `keys` are the reader's per-block stable keys (`historyKeysFor`); a unit key is that key plus its
 * ordinal inside the block, so adding or removing a row never renumbers an unrelated item.
 */
internal fun renderUnitsFor(
    blocks: List<Pair<Long, ContentBlock>>,
    keys: List<String>,
    startBlockIndex: Int = 0,
): List<RenderUnit> {
    require(blocks.size == keys.size) { "one key per block" }
    val units = ArrayList<RenderUnit>(blocks.size)
    blocks.forEachIndexed { localIndex, (_, block) ->
        val index = startBlockIndex + localIndex
        val key = keys[localIndex]
        when (block) {
            is ContentBlock.Code -> {
                if (!hasMoreLinesThan(block.source, CODE_LINES_PER_ITEM)) {
                    units += RenderUnit.Whole(key, index, block)
                } else {
                    block.source.lines().chunked(CODE_LINES_PER_ITEM).forEachIndexed { chunk, part ->
                        units +=
                            RenderUnit.CodeChunk(
                                key = "$key/c$chunk",
                                blockIndex = index,
                                language = block.language.takeIf { it.isNotEmpty() && chunk == 0 },
                                lines = part,
                            )
                    }
                }
            }

            is ContentBlock.Table -> {
                if (block.rows.size <= TABLE_ROWS_PER_ITEM && block.droppedCells == 0) {
                    units += RenderUnit.Whole(key, index, block)
                } else {
                    if (block.rows.isEmpty()) {
                        // A header-only table still needs its headers drawn.
                        units += RenderUnit.Whole(key, index, block)
                    } else {
                        block.rows.forEachIndexed { row, cells ->
                            units +=
                                RenderUnit.TableRowUnit(
                                    key = "$key/r$row",
                                    blockIndex = index,
                                    headers = block.headers,
                                    cells = cells,
                                    rowIndex = row,
                                    last = row == block.rows.lastIndex,
                                    // Carried on the last row so the notice is drawn once, after it.
                                    droppedCells = if (row == block.rows.lastIndex) block.droppedCells else 0,
                                )
                        }
                    }
                }
            }

            else -> {
                units += RenderUnit.Whole(key, index, block)
            }
        }
    }
    return units
}

/**
 * The unit indices each block occupies, so block-level scroll targets and heights still resolve.
 *
 * A block is usually one unit; a long fence or a big table is many. An empty range means the block
 * produced no unit at all, which the caller must treat as "no target" rather than index 0.
 */
internal fun unitRangeByBlock(
    units: List<RenderUnit>,
    blockCount: Int,
): List<IntRange> {
    val ranges = MutableList(blockCount) { IntRange.EMPTY }
    units.forEachIndexed { unitIndex, unit ->
        if (unit.blockIndex !in 0 until blockCount) return@forEachIndexed
        val existing = ranges[unit.blockIndex]
        ranges[unit.blockIndex] =
            if (existing.isEmpty()) unitIndex..unitIndex else existing.first..unitIndex
    }
    return ranges
}

private fun hasMoreLinesThan(
    source: String,
    maxLines: Int,
): Boolean {
    var breaks = 0
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '\n') {
            if (++breaks >= maxLines) return true
        } else if (c == '\r') {
            if (i + 1 < source.length && source[i + 1] == '\n') i++
            if (++breaks >= maxLines) return true
        }
        i++
    }
    return false
}
