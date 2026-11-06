package com.nirowear.renderer

data class InlineContent(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val math: Boolean = false,
)

sealed interface ContentBlock {
    data class Paragraph(
        val content: List<InlineContent>,
    ) : ContentBlock

    data class Heading(
        val level: Int,
        val content: List<InlineContent>,
    ) : ContentBlock

    data class ListEntry(
        val marker: String,
        val depth: Int,
        val content: List<InlineContent>,
    ) : ContentBlock

    data class Quote(
        val content: List<InlineContent>,
    ) : ContentBlock

    data class Code(
        val language: String,
        val source: String,
    ) : ContentBlock

    data class Math(
        val source: String,
        val complete: Boolean,
        /**
         * List-item level this display sits inside, 0 when it is top level. Only used for left
         * padding: the formula is extracted before the list is parsed, so the bullet is gone and
         * without this the display would sit flush against the screen edge inside an indented item.
         */
        val depth: Int = 0,
    ) : ContentBlock

    data class Table(
        val headers: List<List<InlineContent>>,
        val rows: List<List<List<InlineContent>>>,
        val droppedCells: Int = 0,
    ) : ContentBlock

    data object Divider : ContentBlock
}
