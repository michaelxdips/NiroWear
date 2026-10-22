package com.nirowear.renderer

import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.ParsedInline

/** Raw Markdown remains authoritative. Incomplete trailing syntax stays visible. */
object ResponseParser {
    private class MathInline(val source: String) : CustomNode()
    private val parser = Parser.builder()
        .extensions(listOf<Extension>(TablesExtension.create(), AutolinkExtension.create()))
        .customInlineContentParserFactory(object : InlineContentParserFactory {
            override fun getTriggerCharacters() = setOf('$', '\\')
            override fun create() = InlineContentParser { state ->
                val scanner = state.scanner()
                val start = scanner.position()
                val dollar = scanner.next('$')
                if (!dollar && !scanner.next("\\(")) return@InlineContentParser ParsedInline.none()
                if (dollar && (scanner.peek() == '$' || scanner.peek().isWhitespace())) {
                    scanner.setPosition(start)
                    return@InlineContentParser ParsedInline.none()
                }
                val contentStart = scanner.position()
                while (scanner.hasNext() && scanner.peek() != '\n') {
                    val end = scanner.position()
                    val found = if (dollar) scanner.next('$') else scanner.next("\\)")
                    if (found) {
                        val source = scanner.getSource(contentStart, end).content
                        if (source.isNotBlank() && !source.last().isWhitespace() &&
                            (!dollar || !scanner.peek().isDigit())) {
                            return@InlineContentParser ParsedInline.of(MathInline(source), scanner.position())
                        }
                        break
                    }
                    if (scanner.next('\\') && dollar) {
                        if (scanner.hasNext()) scanner.next()
                    } else scanner.next()
                }
                scanner.setPosition(start)
                ParsedInline.none()
            }
        }).build()

    // Hoisted out of the per-line loop: the pattern is constant, so building a Regex for every
    // line of every streamed answer was pure overhead. One instance, reused.
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val CONTAINER = Regex("^(?: {0,3}(?:>|[-*+]|\\d{1,9}[.)])(?: |$))+")

    /** Drops list/blockquote markers so a fenced block nested in a container still hides math. */
    private fun uncontainer(line: String): String {
        var rest = line
        while (true) {
            val match = CONTAINER.find(rest) ?: return rest
            if (match.value.isEmpty()) return rest
            rest = rest.substring(match.value.length)
        }
    }

    /** First unescaped occurrence of a closing token at or after [from]. */
    private fun closingIndex(line: String, token: String, from: Int): Int {
        var index = from
        while (index <= line.length - token.length) {
            if (line.startsWith(token, index)) {
                var slashes = 0
                var back = index - 1
                while (back >= 0 && line[back] == '\\') { slashes++; back-- }
                if (slashes % 2 == 0) return index
            }
            index++
        }
        return -1
    }

    fun parse(source: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val markdown = StringBuilder()
        var math: StringBuilder? = null
        var closing = ""
        var fence: Char? = null
        var fenceLength = 0
        fun flush() {
            appendBlocks(parser.parse(markdown.toString()), blocks)
            markdown.clear()
        }
        for (line in source.replace("\r\n", "\n").split('\n')) {
            // A display may close mid-line; whatever follows is ordinary content again, so the
            // remainder is rescanned instead of being swallowed into the equation.
            var rest: String? = line
            while (rest != null) {
                val open = math
                if (open != null) {
                    val index = closingIndex(rest, closing, 0)
                    if (index < 0) {
                        // Every consumed line is newline-terminated so an unfinished display keeps
                        // the exact source after its opening delimiter.
                        open.append(rest).append('\n')
                        rest = null
                    } else {
                        open.append(rest, 0, index).append('\n')
                        blocks += ContentBlock.Math(open.toString().trim(), true)
                        math = null
                        rest = rest.substring(index + closing.length)
                        if (rest.isBlank()) rest = null
                    }
                    continue
                }
                val container = uncontainer(rest)
                FENCE.find(container)?.let { match ->
                    val run = match.groupValues[1]
                    if (fence == null) {
                        fence = run.first()
                        fenceLength = run.length
                    } else if (run.first() == fence && run.length >= fenceLength && match.groupValues[2].isBlank()) {
                        fence = null
                    }
                }
                val trimmed = rest.trimStart()
                val opening = when {
                    fence != null -> null
                    rest.startsWith("    ") || rest.startsWith('\t') -> null
                    trimmed.startsWith("\\[") -> "\\[" to "\\]"
                    trimmed.startsWith("$$") -> "$$" to "$$"
                    else -> null
                }
                if (opening == null) {
                    markdown.appendLine(rest)
                    rest = null
                } else {
                    flush()
                    closing = opening.second
                    math = StringBuilder()
                    rest = trimmed.removePrefix(opening.first)
                }
            }
        }
        if (math != null) blocks += ContentBlock.Math(math.toString().trimEnd(), false)
        else flush()
        return blocks
    }

    private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

    private fun appendBlocks(node: Node, out: MutableList<ContentBlock>, depth: Int = 0, quoted: Boolean = false) {
        when (node) {
            is Paragraph -> out += if (quoted) ContentBlock.Quote(inlines(node)) else ContentBlock.Paragraph(inlines(node))
            is Heading -> out += ContentBlock.Heading(node.level, inlines(node))
            is FencedCodeBlock -> out += ContentBlock.Code(node.info.orEmpty(), node.literal.removeSuffix("\n"))
            is IndentedCodeBlock -> out += ContentBlock.Code("", node.literal.removeSuffix("\n"))
            is ThematicBreak -> out += ContentBlock.Divider
            is BlockQuote -> children(node).forEach { appendBlocks(it, out, depth, true) }
            is BulletList, is OrderedList -> {
                val start = (node as? OrderedList)?.markerStartNumber ?: 1
                children(node).forEachIndexed { index, item ->
                    var first = true
                    children(item).forEach { child ->
                        if (child is Paragraph) {
                            val marker = if (!first) "" else if (node is OrderedList) "${start + index}." else "•"
                            out += ContentBlock.ListEntry(marker, depth, inlines(child))
                            first = false
                        } else appendBlocks(child, out, depth + 1, quoted)
                    }
                }
            }
            is TableBlock -> {
                val rows = children(node).flatMap { children(it) }.filterIsInstance<TableRow>()
                    .map { row -> children(row).map { inlines(it) }.toList() }.toList()
                out += ContentBlock.Table(rows.firstOrNull().orEmpty(), rows.drop(1))
            }
            is HtmlBlock -> out += ContentBlock.Paragraph(listOf(InlineContent(node.literal)))
            else -> children(node).forEach { appendBlocks(it, out, depth, quoted) }
        }
    }

    private fun inlines(node: Node, style: InlineContent = InlineContent("")): List<InlineContent> =
        children(node).flatMap { child ->
            when (child) {
                is Text -> listOf(style.copy(text = child.literal))
                is MathInline -> listOf(style.copy(text = child.source, math = true))
                is Code -> listOf(style.copy(text = child.literal, code = true))
                is StrongEmphasis -> inlines(child, style.copy(bold = true))
                is Emphasis -> inlines(child, style.copy(italic = true))
                is Link -> inlines(child, style.copy(link = child.destination))
                is SoftLineBreak -> listOf(style.copy(text = " "))
                is HardLineBreak -> listOf(style.copy(text = "\n"))
                is HtmlInline -> listOf(style.copy(text = child.literal))
                else -> inlines(child, style)
            }.asSequence()
        }.toList()

}
