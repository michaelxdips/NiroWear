package com.nirowear.renderer

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.ParsedInline

/** Raw Markdown remains authoritative. Incomplete trailing syntax stays visible. */
object ResponseParser {
    private class MathInline(
        val source: String,
    ) : CustomNode()

    private val parser =
        Parser
            .builder()
            .extensions(listOf<Extension>(TablesExtension.create(), AutolinkExtension.create()))
            .customInlineContentParserFactory(
                object : InlineContentParserFactory {
                    override fun getTriggerCharacters() = setOf('$', '\\')

                    override fun create() =
                        InlineContentParser { state ->
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
                                        (!dollar || !scanner.peek().isDigit())
                                    ) {
                                        return@InlineContentParser ParsedInline.of(
                                            MathInline(source),
                                            scanner.position(),
                                        )
                                    }
                                    break
                                }
                                if (scanner.next('\\') && dollar) {
                                    if (scanner.hasNext()) scanner.next()
                                } else {
                                    scanner.next()
                                }
                            }
                            scanner.setPosition(start)
                            ParsedInline.none()
                        }
                },
            ).build()

    // Hoisted out of the per-line loop: the pattern is constant, so building a Regex for every
    // line of every streamed answer was pure overhead. One instance, reused.
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val DEEP_QUOTE = Regex("^\\s*(?:>\\s*)+")
    private val DELIMITER_CELL = Regex(":?-{1,}:?")

    /** Block nesting allowed before the subtree is flattened. Deep enough for real answers. */
    private const val MAX_DEPTH = 24

    /**
     * Recursion cap for inline styling. Much lower than [MAX_DEPTH] because inline nesting is
     * pathological past a handful of levels, and each level costs several stack frames.
     */
    private const val MAX_INLINE_DEPTH = 32

    /** Container depth the Markdown library may build. Beyond this it flattens instead of recursing. */
    private const val MAX_CONTAINER_DEPTH = 48

    /**
     * Collapses container markers beyond [MAX_CONTAINER_DEPTH] in one line.
     *
     * CommonMark recurses once per nesting level while building its tree — before any of our code
     * runs — so 200k '>' characters in a single line overflowed the stack inside the library, and
     * `Parser.Builder` exposes no depth limit to configure. Trimming the source is the only place the
     * bound can be enforced. Past the cap the extra markers are dropped, which loses indentation no
     * real answer uses but keeps the text.
     */
    private fun capContainers(line: String): String {
        // No blanket fence exemption. It used to return the line untouched whenever it contained a
        // fence run, which disabled the cap entirely for `">".repeat(200_000) + "```"`: the library
        // then recursed 200k deep inside the library and threw StackOverflowError, an Error nothing
        // upstream catches. The cap now runs for every line; at the cap the rest of the markers is
        // collapsed by uncontainer (a loop, not recursion) and the remaining prefix becomes three
        // spaces — the legal fence indent — so a deeply quoted fence still parses as a code block.
        var index = 0
        var depth = 0
        while (index < line.length) {
            var cursor = index
            var spaces = 0
            while (cursor < line.length && line[cursor] == ' ' && spaces < 3) {
                cursor++
                spaces++
            }
            if (cursor >= line.length) break
            var end = -1
            var needsSpace = true
            when (line[cursor]) {
                // A blockquote marker is `>` and the following space is optional, so `>>>>>>>>x` is
                // deeply nested nesting and must be counted here.
                '>' -> {
                    end = cursor + 1
                    needsSpace = false
                }

                '-', '*', '+' -> {
                    end = cursor + 1
                }

                in '0'..'9' -> {
                    var digits = cursor
                    while (digits < line.length && line[digits].isDigit() && digits - cursor < 9) digits++
                    if (digits > cursor && digits < line.length &&
                        (line[digits] == '.' || line[digits] == ')')
                    ) {
                        end = digits + 1
                    }
                }
            }
            if (end < 0 || (needsSpace && end < line.length && line[end] != ' ')) break
            depth++
            index = if (end < line.length) end + 1 else line.length
            if (depth >= MAX_CONTAINER_DEPTH) {
                // Drop the REST of the markers, not just the first 48: leaving them lets the library
                // recurse exactly as far as before (`">".repeat(200_000)` is one marker run with no
                // spaces, so uncontainer cannot split it). Three spaces of indent keeps a fence run
                // that sat behind the markers parseable, which is what the deep-quote fence case
                // needs.
                return "   " + line.substring(index).trimStart('>', '-', '*', '+', ' ', '\t')
            }
        }
        return line
    }

    /**
     * Drops list/blockquote markers so a fenced block nested in a container still hides math.
     *
     * Scanned by hand rather than with `^(?:...(?: |$))+`. That regex recursed once per marker, so
     * roughly 700 quote markers in one line — a few hundred bytes of model output — threw
     * `StackOverflowError` and took the app down. A loop cannot overflow.
     */
    private fun uncontainer(line: String): String {
        var index = 0
        while (true) {
            var cursor = index
            // Up to three leading spaces belong to the container marker, not to the content.
            var spaces = 0
            while (cursor < line.length && line[cursor] == ' ' && spaces < 3) {
                cursor++
                spaces++
            }
            if (cursor >= line.length) return line.substring(index)

            var end = -1
            when (val char = line[cursor]) {
                '>', '-', '*', '+' -> {
                    end = cursor + 1
                }

                in '0'..'9' -> {
                    var digits = cursor
                    while (digits < line.length && line[digits].isDigit() && digits - cursor < 9) digits++
                    if (digits > cursor && digits < line.length &&
                        (line[digits] == '.' || line[digits] == ')')
                    ) {
                        end = digits + 1
                    }
                }
            }
            // A marker only counts when followed by a space or the end of the line.
            if (end < 0 || (end < line.length && line[end] != ' ')) return line.substring(index)
            index = if (end < line.length) end + 1 else line.length
            if (index >= line.length) return ""
        }
    }

    /** First unescaped occurrence of a closing token at or after [from]. */
    private fun closingIndex(
        line: String,
        token: String,
        from: Int,
    ): Int {
        var index = from
        while (index <= line.length - token.length) {
            if (line.startsWith(token, index)) {
                var slashes = 0
                var back = index - 1
                while (back >= 0 && line[back] == '\\') {
                    slashes++
                    back--
                }
                if (slashes % 2 == 0) return index
            }
            index++
        }
        return -1
    }

    /** Splits a GFM table row on unescaped pipes, ignoring the outer pipes. */
    private fun splitTableRow(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|")) row = row.substring(0, row.length - 1)
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < row.length) {
            val char = row[index]
            if (char == '\\' && index + 1 < row.length && row[index + 1] == '|') {
                cell.append('|')
                index += 2
            } else if (char == '|') {
                cells += cell.toString()
                cell.clear()
                index++
            } else {
                cell.append(char)
                index++
            }
        }
        cells += cell.toString()
        return cells
    }

    /**
     * Container identity of a line, used to decide whether two lines belong to the same table.
     *
     * A character-count comparison is wrong for list items: the header carries a `1. ` marker while
     * its continuation rows carry the equivalent spaces, so the removal lengths differ even though
     * CommonMark puts both in the same container. Marker characters are masked to spaces and the
     * continuation indent (up to three) is normalised, which makes "1. |a|" and "   |---|" compare
     * equal while keeping a quoted header distinct from an unquoted delimiter.
     */
    private fun containerKey(raw: String): String {
        val content = uncontainer(raw)
        val markers =
            raw
                .substring(0, raw.length - content.length)
                .map { if (it.isDigit() || it in ">.-*+)") ' ' else it }
                .joinToString("")
        val indent = content.takeWhile { it == ' ' }.length.coerceAtMost(3)
        return markers + " ".repeat(indent)
    }

    /**
     * Columns of content indent a list marker introduces on this line, or null when the line is not a
     * list item. Used only to measure indentation relative to the enclosing item.
     */
    private fun listMarkerContentIndent(line: String): Int? {
        var i = 0
        var spaces = 0
        while (i < line.length && line[i] == ' ' && spaces < 3) {
            i++
            spaces++
        }
        if (i >= line.length) return null
        if (line[i] == '-' || line[i] == '*' || line[i] == '+') {
            val end = i + 1
            return if (end < line.length && line[end] == ' ') end + 1 else null
        }
        if (line[i].isDigit()) {
            var digits = i
            while (digits < line.length && line[digits].isDigit() && digits - i < 9) digits++
            if (digits > i && digits < line.length && (line[digits] == '.' || line[digits] == ')') &&
                digits + 1 < line.length && line[digits + 1] == ' '
            ) {
                return digits + 2
            }
        }
        return null
    }

    private fun isDelimiterRow(line: String): Boolean {
        val cells = splitTableRow(line)
        return cells.isNotEmpty() &&
            cells.all { cell -> cell.trim().matches(DELIMITER_CELL) }
    }

    /**
     * Cells the GFM parser will silently drop: body rows wider than the header are clamped to the
     * header width by the library, so count the overflow here and report it explicitly instead.
     * One entry per table region, in document order — which means this scan must see exactly the
     * tables the parser sees:
     *
     * - fence state is tracked, so a table-shaped sample inside a code block (which produces no
     *   TableBlock) no longer queues a count that the NEXT real table then reports;
     * - the header and its delimiter row must carry the SAME container prefix. Stripping them
     *   independently counted regions the parser never builds (a quoted header over an unquoted
     *   delimiter is not a table), which shifted the queue by one: a table that really clamped cells
     *   reported nothing while a clean table claimed to have hidden some.
     */
    private fun scanTableOverflow(markdown: String): ArrayDeque<Int> {
        val dropped = ArrayDeque<Int>()
        val lines = markdown.split('\n')
        var index = 0
        var fence: Char? = null
        var fenceLength = 0
        while (index < lines.size) {
            val raw = lines[index]
            val line = uncontainer(raw)
            if (fence == null) {
                // OPEN detection tolerates container markers: replayFence leaves them in place when the
                // body was properly marked, so a fence can reach this point as "> ```".
                val opener = FENCE.find(raw) ?: FENCE.find(line)
                if (opener != null) {
                    fence = opener.groupValues[1].first()
                    fenceLength = opener.groupValues[1].length
                    index++
                    continue
                }
            } else {
                // CLOSE detection uses the RAW line only, exactly like CommonMark. A fence body is
                // literal, so a content line "> ```" must not close it. This used to run on the
                // stripped line, which closed a top-level block early, reopened a fence on the real
                // closer, and skipped EVERY later table — so no overflow notice was ever shown again.
                val closer = FENCE.find(raw)
                if (closer != null) {
                    val run = closer.groupValues[1]
                    if (run.first() == fence && run.length >= fenceLength && closer.groupValues[2].isBlank()) {
                        fence = null
                    }
                }
                index++
                continue
            }
            // Container identity of this line, NOT a character count. A list item's header carries a `1. `
            // marker while its continuation rows carry the resulting spaces instead, so comparing
            // removal lengths said "different container" for a table the library actually builds — the
            // scan skipped it and the overflow queue shifted by one.
            val key = containerKey(raw)
            // Four spaces of content indentation starts an INDENTED CODE BLOCK, which CommonMark
            // never turns into a table. Counting it queued a phantom that the next real table then
            // reported, so a clean table claimed hidden cells.
            val indent = line.length - line.trimStart(' ').length
            val delimiterRaw = if (index + 1 < lines.size) lines[index + 1] else ""
            val delimiter = uncontainer(delimiterRaw)
            val headerCells = if (line.contains('|')) splitTableRow(line) else emptyList()
            // GFM also requires the delimiter row to have exactly as many cells as the header; without
            // that check a header over a shorter delimiter row was counted although the library parses
            // it as a paragraph.
            if (indent >= 4 ||
                headerCells.isEmpty() ||
                containerKey(delimiterRaw) != key ||
                !isDelimiterRow(delimiter) ||
                splitTableRow(delimiter).size != headerCells.size
            ) {
                index++
                continue
            }
            val width = headerCells.size
            var total = 0
            index += 2
            while (index < lines.size) {
                val nextRaw = lines[index]
                val nextLine = uncontainer(nextRaw)
                // A row only belongs to this table if it sits in the same container.
                if (containerKey(nextRaw) != key) break
                if (nextLine.length - nextLine.trimStart(' ').length >= 4) break
                if (FENCE.find(nextRaw) != null || !nextLine.contains('|') || isDelimiterRow(nextLine)) break
                total += (splitTableRow(nextLine).size - width).coerceAtLeast(0)
                index++
            }
            dropped.addLast(total)
        }
        return dropped
    }

    fun parse(source: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val markdown = StringBuilder()
        var math: StringBuilder? = null
        var closing = ""
        var fence: Char? = null
        var fenceLength = 0
        var fenceNested = false
        var mathPrefix = ""
        // Container prefix (`"> "`, `"- "`) the display math was opened behind, kept for the whole
        // block so continuation lines do not put stray markers into the equation source.
        var mathContainer = ""
        // Content indent of the list item we are inside, 0 when not in a list.
        var listContentIndent = 0

        /**
         * Lines of the currently open fence, fed raw so CommonMark sees the exact bytes.
         * At close, the whole run is replayed stripped if a lazy (unmarked, unindented)
         * content line proved the container markers ornamental; otherwise it replays raw
         * and the library parses the quote/list natively.
         */
        val fenceLines = mutableListOf<String>()

        fun replayFence() {
            // Strip container markers only if a CONTENT line proved them ornamental. The fence lines
            // themselves must not vote: an opening "```yaml" is unmarked and unindented, so it always
            // looked "lazy" and forced stripping for every top-level fence — which rewrote body lines
            // like "- name: foo" or "+ added" into "name: foo" and "added". A YAML block, a diff or a
            // Markdown example stopped reproducing its own input. A properly container-indented body
            // is still left alone, because its lines are indented or marked.
            val lazy = fenceLines.any { !isFenceRun(it) && isLazyFenceContent(it) }
            for (index in fenceLines.indices) {
                val fenceLine = fenceLines[index]
                // A fence-looking line is stripped only at the BOUNDARIES — the opener or closer of a
                // nested fence — never in the middle. Stripping a "> ```" that is part of the code
                // SAMPLE produced a bare "```", which closed the block it belonged to: the sample then
                // rendered as an empty block plus prose, and a following $$ line was torn out as
                // display math. The lazy case still needs the boundary strip, because "> ```" around
                // an unmarked body has to become a plain fence for the library to see.
                val boundary = index == 0 || index == fenceLines.lastIndex
                val replay =
                    when {
                        isFenceRun(fenceLine) && !boundary -> fenceLine
                        lazy -> stripForReplay(fenceLine)
                        else -> fenceLine
                    }
                markdown.appendLine(replay)
            }
            fenceLines.clear()
        }

        fun flush() {
            // An unclosed fence has no close to decide on; replay raw so streaming code
            // stays visible and the library parses any containers natively.
            for (index in fenceLines.indices) markdown.appendLine(fenceLines[index])
            fenceLines.clear()
            val rendered = markdown.toString()
            appendBlocks(parser.parse(rendered), blocks, scanTableOverflow(rendered))
            markdown.clear()
        }
        for (line in source.replace("\r\n", "\n").split('\n')) {
            // Bound nesting before the Markdown library recurses on it.
            val bounded = capContainers(line)
            // A display may close mid-line; whatever follows is ordinary content again, so the
            // remainder is rescanned instead of being swallowed into the equation.
            var rest: String? = bounded
            while (rest != null) {
                val open = math
                if (open != null) {
                    // A quoted display's continuation line still carries "> "; strip exactly the
                    // prefix the block was opened behind, so the equation reads "x + y" and not
                    // "> x + y" (which typeset as literal greater-than glyphs).
                    val body =
                        if (mathContainer.isNotEmpty() && rest.startsWith(mathContainer)) {
                            rest.substring(mathContainer.length)
                        } else {
                            rest
                        }
                    val index = closingIndex(body, closing, 0)
                    if (index < 0) {
                        // Every consumed line is newline-terminated so an unfinished display keeps
                        // the exact source after its opening delimiter. Later lines arrive raw, so
                        // the opening line's container prefix must not leak into the equation.
                        mathPrefix = ""
                        open.append(body).append('\n')
                        rest = null
                    } else {
                        open.append(body, 0, index).append('\n')
                        blocks += ContentBlock.Math(open.toString().trim(), true)
                        math = null
                        mathContainer = ""
                        rest = mathPrefix + body.substring(index + closing.length)
                        mathPrefix = ""
                        if (rest.isBlank()) rest = null
                    }
                    continue
                }
                // Tracks an open fence through container markers: `uncontainer` strips them
                // for detection only, the lines themselves buffer raw in `fenceLines`.
                val container = uncontainer(rest)
                val inFence = fence != null
                // A hostile `>` run without spaces is not a container uncontainer() strips, so a
                // fence buried behind one was never detected. Only the fence hunt gets this
                // fallback; quote parsing still sees the raw line.
                val fenced =
                    if (FENCE.find(container) == null &&
                        (rest.contains("```") || rest.contains("~~~"))
                    ) {
                        container.replaceFirst(DEEP_QUOTE, "")
                    } else {
                        container
                    }
                // Close detection runs on the STRIPPED line only for a fence opened behind a
                // container, and on the raw line otherwise: a top-level fence body is literal, so a
                // content line "> ```" must not close it, while a fence inside a quote or list closes
                // on its marker line even when that line's prefix differs from the opener's
                // ("> - ```" opens, ">   ```" closes).
                //
                // `fenced`, not `container`: a GLUED quote run (">```", ">>```") is not something
                // uncontainer() strips — it needs the DEEP_QUOTE fallback. Using `container` here made
                // a quoted code block open and never close, so the block rendered empty and its body
                // spilled out as prose.
                val fenceBody = if (fenceNested) fenced else rest
                if (fence != null) {
                    FENCE.find(fenceBody)?.let { match ->
                        val run = match.groupValues[1]
                        if (run.first() == fence && run.length >= fenceLength && match.groupValues[2].isBlank()) {
                            fence = null
                            fenceNested = false
                        }
                    }
                } else {
                    FENCE.find(fenced)?.let { match ->
                        val run = match.groupValues[1]
                        fence = run.first()
                        fenceLength = run.length
                        // Nested only when stripping CHANGED the line. Testing DEEP_QUOTE's output
                        // without that check made this true for every fence (replaceFirst returns the
                        // input unchanged when it matches nothing, and FENCE matches that line), so the
                        // raw-line branch was dead code.
                        val deep = rest.replaceFirst(DEEP_QUOTE, "")
                        fenceNested = uncontainer(rest) != rest || (deep != rest && FENCE.find(deep) != null)
                    }
                }
                val trimmed = container.trimStart()
                // Indentation is measured RELATIVE to the enclosing list item. Four spaces is an
                // indented code block at top level, but inside a list item whose content indent is 3
                // the same four columns are ordinary prose — so `    $$x$$` under "1. item" used to
                // render as the literal text "$$x$$" instead of a formula.
                val contentLeading = container.length - container.trimStart(' ').length
                val markerIndent = listMarkerContentIndent(rest)
                if (markerIndent != null) {
                    listContentIndent = markerIndent
                } else if (container.isNotBlank() && contentLeading < listContentIndent) {
                    // A less-indented content line ends the list.
                    listContentIndent = 0
                }
                val relativeIndent =
                    if (listContentIndent > 0) (contentLeading - listContentIndent).coerceAtLeast(0) else contentLeading
                val indented =
                    relativeIndent >= 4 || rest.startsWith('\t') || container.startsWith('\t')
                var opening =
                    when {
                        fence != null -> null
                        indented -> null
                        trimmed.startsWith("\\[") -> "\\[" to "\\]"
                        trimmed.startsWith("$$") -> "$$" to "$$"
                        else -> null
                    }
                if (opening == null && fence == null && !indented) {
                    // Mid-line `$$` preceded by whitespace opens display math; glued to a word
                    // (`a$$x$$b`) both dollars stay literal, like currency.
                    val mid = findMidlineDisplay(container)
                    if (mid >= 0) {
                        val prefix = rest.substring(0, rest.length - container.length)
                        // The prose before the opener belongs to the answer. This used to be thrown
                        // away: "The area is $$A=\pi r^2$$ where r" lost "The area is" entirely,
                        // because flush() only emits what was already buffered and the current line
                        // had not been appended yet.
                        val before = prefix + container.substring(0, mid)
                        opening = "$$" to "$$"
                        if (before.isNotBlank()) markdown.appendLine(before)
                        flush()
                        closing = "$$"
                        math = StringBuilder()
                        mathPrefix = prefix
                        mathContainer = prefix
                        rest = container.substring(mid + 2)
                        continue
                    }
                }
                if (opening == null) {
                    val fenceOpen = FENCE.find(fenced) != null && fence == null
                    if (inFence || fence != null || fenceOpen) {
                        // A fence run buffers raw; at close the whole run replays stripped
                        // only if a lazy (unmarked, unindented) content line proved the
                        // container markers ornamental. Otherwise it replays raw and the
                        // library parses the quote/list natively.
                        fenceLines.add(rest)
                        if (fence == null) replayFence()
                    } else {
                        // Outside a fence the raw line is fed so quotes and lists survive.
                        markdown.appendLine(rest)
                    }
                    rest = null
                } else {
                    flush()
                    closing = opening.second
                    math = StringBuilder()
                    mathPrefix = rest.substring(0, rest.length - container.length)
                    mathContainer = mathPrefix
                    rest = trimmed.removePrefix(opening.first)
                }
            }
        }
        if (math != null) {
            blocks += ContentBlock.Math(math.toString().trimEnd(), false)
        } else {
            flush()
        }
        return blocks
    }

    private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

    /** True when the line is itself a fence run, possibly behind container markers. */
    private fun isFenceRun(line: String): Boolean {
        val stripped = uncontainer(line)
        if (FENCE.find(stripped) != null) return true
        return line.contains("```") || line.contains("~~~")
    }

    /** A content line that cannot continue a container: unindented, unmarked, non-blank. */
    private fun isLazyFenceContent(line: String): Boolean {
        if (line.isBlank()) return false
        val first = line[0]
        if (first == ' ' || first == '\t') return false
        return uncontainer(line) == line
    }

    /** Strips container markers for a replayed fence line, including `>` runs without spaces. */
    private fun stripForReplay(line: String): String {
        val stripped = uncontainer(line)
        if (FENCE.find(stripped) != null) return stripped
        if (line.contains("```") || line.contains("~~~")) {
            val deep = stripped.replaceFirst(DEEP_QUOTE, "")
            if (FENCE.find(deep) != null) return deep
        }
        return stripped
    }

    /** First `$$` preceded by whitespace (mid-line display open), or -1. Skips code spans. */
    private fun findMidlineDisplay(container: String): Int {
        var index = 0
        var code = false
        while (index < container.length) {
            if (container[index] == '`') {
                code = !code
                index++
                continue
            }
            if (!code &&
                container[index] == '$' &&
                index + 1 < container.length &&
                container[index + 1] == '$' &&
                index > 0 &&
                container[index - 1].isWhitespace()
            ) {
                return index
            }
            index++
        }
        return -1
    }

    /**
     * Collects the text under a node without recursing per level, so a pathologically nested subtree
     * still yields its content instead of being dropped when the depth cap is hit.
     */
    private fun flattenText(root: Node): String {
        val builder = StringBuilder()
        val pending = ArrayDeque<Node>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node is Text) builder.append(node.literal)
            if (node is Code) builder.append(node.literal)
            if (node is MathInline) builder.append(node.source)
            // Block nodes carry their text on the node itself and have no Text children, so a code
            // block or HTML block inside a flattened (depth-capped) subtree used to vanish entirely
            // instead of degrading to plain text.
            if (node is FencedCodeBlock) builder.append(node.literal).append(' ')
            if (node is IndentedCodeBlock) builder.append(node.literal).append(' ')
            if (node is HtmlBlock) builder.append(node.literal).append(' ')
            if (node is SoftLineBreak || node is HardLineBreak) builder.append(' ')
            // Children pushed in reverse so removeLast() yields document order; Code and
            // MathInline carry text the old Text-only version silently dropped.
            var child = node.firstChild
            var count = 0
            while (child != null) {
                count++
                child = child.next
            }
            val ordered = arrayOfNulls<Node>(count)
            child = node.firstChild
            var slot = count - 1
            while (child != null) {
                ordered[slot--] = child
                child = child.next
            }
            ordered.forEach { pending.addLast(it!!) }
        }
        return builder.toString().trim()
    }

    private fun appendBlocks(
        node: Node,
        out: MutableList<ContentBlock>,
        dropped: ArrayDeque<Int> = ArrayDeque(),
        depth: Int = 0,
        quoted: Boolean = false,
    ) {
        // Nesting is bounded by the document, so a hostile 2000-level blockquote would recurse until
        // the stack dies. Past this depth the remaining subtree is flattened into one paragraph
        // instead of descending, which keeps the text and stops the crash.
        if (depth > MAX_DEPTH) {
            val text = flattenText(node)
            if (text.isNotBlank()) out += ContentBlock.Paragraph(listOf(InlineContent(text)))
            return
        }
        when (node) {
            is Paragraph -> {
                out +=
                    if (quoted) ContentBlock.Quote(inlines(node)) else ContentBlock.Paragraph(inlines(node))
            }

            is Heading -> {
                out += ContentBlock.Heading(node.level, inlines(node))
            }

            is FencedCodeBlock -> {
                out += ContentBlock.Code(node.info.orEmpty(), node.literal.removeSuffix("\n"))
            }

            is IndentedCodeBlock -> {
                out += ContentBlock.Code("", node.literal.removeSuffix("\n"))
            }

            is ThematicBreak -> {
                out += ContentBlock.Divider
            }

            is BlockQuote -> {
                children(node).forEach { appendBlocks(it, out, dropped, depth + 1, true) }
            }

            is BulletList, is OrderedList -> {
                val start = (node as? OrderedList)?.markerStartNumber ?: 1
                children(node).forEachIndexed { index, item ->
                    var first = true
                    children(item).forEach { child ->
                        if (child is Paragraph) {
                            val marker =
                                if (!first) {
                                    ""
                                } else if (node is OrderedList) {
                                    "${start + index}."
                                } else {
                                    "•"
                                }
                            out += ContentBlock.ListEntry(marker, depth, inlines(child))
                            first = false
                        } else {
                            // Deliberate: an item whose content is a block rather than a paragraph
                            // (fence, quote, table) emits no marker of its own, so the reader gets no
                            // lone bullet above a code block. Pinned for BOTH the bulleted and the
                            // ordered form by
                            // containerFencesKeepLiteralMathAndDoNotHideFollowingDisplay. An audit
                            // flagged this as a numbering bug; the golden case is the contract, so the
                            // behaviour stays and the reason is recorded here.
                            appendBlocks(child, out, dropped, depth + 1, quoted)
                        }
                    }
                }
            }

            is TableBlock -> {
                val rows =
                    children(node)
                        .flatMap { children(it) }
                        .filterIsInstance<TableRow>()
                        .map { row -> children(row).map { inlines(it) }.toList() }
                        .toList()
                val overflow = if (dropped.isNotEmpty()) dropped.removeFirst() else 0
                out += ContentBlock.Table(rows.firstOrNull().orEmpty(), rows.drop(1), overflow)
            }

            is HtmlBlock -> {
                out += ContentBlock.Paragraph(listOf(InlineContent(node.literal)))
            }

            else -> {
                children(node).forEach { appendBlocks(it, out, dropped, depth, quoted) }
            }
        }
    }

    /**
     * Inline runs, flattened to styled text.
     *
     * `depth` exists because this function is recursive and had no bound. `appendBlocks` was
     * already capped at [MAX_DEPTH], but a 6 KB run of `*` or `_` nests emphasis thousands deep and
     * blew the stack with `StackOverflowError` — which is an `Error`, not an `Exception`, so nothing
     * in the app caught it and the process died. Verified at a 1 MB stack (the Android worker
     * default): 2000 stars survive, 3000 throw.
     *
     * At the cap the remaining subtree is emitted as plain text rather than dropped, so a
     * pathological answer degrades instead of vanishing or crashing.
     */
    private fun inlines(
        node: Node,
        style: InlineContent = InlineContent(""),
        depth: Int = 0,
    ): List<InlineContent> =
        if (depth >= MAX_INLINE_DEPTH) {
            listOf(style.copy(text = flattenText(node)))
        } else {
            children(node)
                .flatMap { child ->
                    when (child) {
                        is Text -> listOf(style.copy(text = child.literal))
                        is MathInline -> listOf(style.copy(text = child.source, math = true))
                        is Code -> listOf(style.copy(text = child.literal, code = true))
                        is StrongEmphasis -> inlines(child, style.copy(bold = true), depth + 1)
                        is Emphasis -> inlines(child, style.copy(italic = true), depth + 1)
                        is Link -> inlines(child, style.copy(link = child.destination), depth + 1)
                        is SoftLineBreak -> listOf(style.copy(text = " "))
                        is HardLineBreak -> listOf(style.copy(text = "\n"))
                        is HtmlInline -> listOf(style.copy(text = child.literal))
                        else -> inlines(child, style, depth + 1)
                    }.asSequence()
                }.toList()
        }
}
