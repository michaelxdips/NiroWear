package com.nirowear.renderer

/**
 * Code lines rendered as one LazyColumn item.
 *
 * A code block used to be ONE item holding one `Text` of the whole fence, so a 5000-line fence from a
 * model was a single measuring pass over 5000 lines — the same ANR shape `MAX_TABLE_ROWS` exists to
 * prevent, but with no cap and no notice. Splitting at 24 lines bounds each pass while keeping the
 * block readable: every chunk still scrolls horizontally as one unit, and nothing is dropped.
 */
internal const val CODE_LINES_PER_ITEM = 24

/**
 * Table rows rendered as one LazyColumn item.
 *
 * Rows used to be capped at 40 inside a single item, so row 41 did not exist and the reader was told
 * so. Rows are now their own items — the upgrade path the cap's own comment named — which lets the
 * cap go: the cost is paid lazily, one row at a time.
 */
internal const val TABLE_ROWS_PER_ITEM = 1

/**
 * One LazyColumn item in the reader.
 *
 * The reader's anchors, heights and scroll targets are expressed in BLOCKS, while composition has to
 * happen per ITEM. A block that is cheap to compose is one `Whole`; a table or a long code fence
 * expands into many, so the cost of a huge one is paid as the reader reaches it.
 */
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
): List<RenderUnit> {
    require(blocks.size == keys.size) { "one key per block" }
    val units = ArrayList<RenderUnit>(blocks.size)
    blocks.forEachIndexed { index, (_, block) ->
        val key = keys[index]
        when (block) {
            is ContentBlock.Code -> {
                val lines = block.source.lines()
                if (lines.size <= CODE_LINES_PER_ITEM) {
                    units += RenderUnit.Whole(key, index, block)
                } else {
                    lines.chunked(CODE_LINES_PER_ITEM).forEachIndexed { chunk, part ->
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
