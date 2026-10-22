package com.nirowear.chat

/** Small saved-instance anchor. No content duplication or provider-private state. */
data class ReaderAnchor(val messageId: Long, val block: Int, val index: Int, val offset: Int, val contentHash: Int, val following: Boolean) {
    fun resolve(blocks: List<Pair<Long, Any>>, hashes: Map<Long, Int>): Pair<Int, Int> {
        val positions = blocks.indices.filter { blocks[it].first == messageId }
        return if (positions.isNotEmpty()) {
            positions[block.coerceIn(0, positions.lastIndex)] to if (hashes[messageId] == contentHash) offset.coerceAtLeast(0) else 0
        } else {
            // Changed/deleted answer: nearest surviving message before anchor, never vanished tail.
            (blocks.indexOfLast { it.first < messageId }.coerceAtLeast(0)) to 0
        }
    }
}
