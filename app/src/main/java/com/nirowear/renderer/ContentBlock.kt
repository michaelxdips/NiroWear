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
    ) : ContentBlock

    data class Table(
        val headers: List<List<InlineContent>>,
        val rows: List<List<List<InlineContent>>>,
    ) : ContentBlock

    data object Divider : ContentBlock
}
