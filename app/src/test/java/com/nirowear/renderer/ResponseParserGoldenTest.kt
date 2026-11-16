package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserGoldenTest {
    private val delimiters = listOf("\\[" to "\\]", "$$" to "$$")

    @Test fun adjacentDisplaysRemainSeparateBlocks() {
        for ((firstOpen, firstClose) in delimiters) {
            for ((secondOpen, secondClose) in delimiters) {
                assertEquals(
                    listOf(ContentBlock.Math("a", true), ContentBlock.Math("b", true)),
                    ResponseParser.parse("${firstOpen}a$firstClose ${secondOpen}b$secondClose"),
                )
            }
        }
    }

    @Test fun firstClosingDelimiterKeepsSameLineProseAndFollowingParagraph() {
        for ((open, close) in delimiters) {
            for (body in listOf("a", "\n  a & b\n  c & d\n")) {
                assertEquals(
                    listOf(
                        paragraph("Before"),
                        ContentBlock.Math(body.trim(), true),
                        ContentBlock.Paragraph(listOf(InlineContent("suffix", bold = true))),
                        paragraph("Tail"),
                    ),
                    ResponseParser.parse("Before\n\n$open$body$close **suffix**\n\nTail"),
                )
            }
        }
    }

    @Test fun streamingDisplaysKeepExactSourceAndCompletion() {
        for ((open, close) in delimiters) {
            // A display that has received no character yet is NOT a block: it rendered as nothing at
            // all, an invisible stop in the document that only a scroll motion revealed.
            assertEquals(emptyList<ContentBlock>(), ResponseParser.parse(open))
            assertEquals(listOf(ContentBlock.Math("a", false)), ResponseParser.parse("${open}a"))
            assertEquals(
                listOf(ContentBlock.Math("a${close.take(1)}", false)),
                ResponseParser.parse("${open}a${close.take(1)}"),
            )
            assertEquals(listOf(ContentBlock.Math("a", true)), ResponseParser.parse("${open}a$close"))
            assertEquals(
                listOf(ContentBlock.Math("a", true), ContentBlock.Math("b", false)),
                ResponseParser.parse("${open}a$close ${open}b"),
            )
            assertEquals(
                listOf(paragraph("Before"), ContentBlock.Math("\n  x & y  \n\nTail", false)),
                ResponseParser.parse("Before\n\n$open\n  x & y  \n\nTail"),
            )
        }
    }

    @Test fun escapedClosingDelimitersRemainInMathSource() {
        for ((open, close) in delimiters) {
            val escapedClose = "\\$close"
            assertEquals(
                listOf(ContentBlock.Math("a$escapedClose b", true)),
                ResponseParser.parse("${open}a$escapedClose b$close"),
            )
            assertEquals(
                listOf(ContentBlock.Math("a$escapedClose", false)),
                ResponseParser.parse("${open}a$escapedClose"),
            )
        }
    }

    @Test fun multilineDisplaysNormalizeCrLfWithoutLosingInternalWhitespace() {
        for ((open, close) in delimiters) {
            assertEquals(
                listOf(ContentBlock.Math("a  \n\n  b", true), paragraph("Tail")),
                ResponseParser.parse("$open\r\na  \r\n\r\n  b\r\n$close\r\n\r\nTail"),
            )
        }
    }

    @Test fun containerFencesKeepLiteralMathAndDoNotHideFollowingDisplay() {
        val literal = "\\[\n  x & y\n\\]\n\$\$not math\$\$\n\\(inline\\)"
        val fences =
            listOf(
                "```text\n$literal\n```",
                "~~~text\n$literal\n~~~",
                "   ```text\n${literal.prependIndent("   ")}\n   ```",
                "- ```text\n${literal.prependIndent("  ")}\n  ```",
                "1. ```text\n${literal.prependIndent("   ")}\n   ```",
                "> ```text\n${literal.prependIndent("> ")}\n> ```",
                "> - ```text\n${literal.prependIndent(">   ")}\n>   ```",
            )
        for (fence in fences) {
            assertEquals(
                listOf(ContentBlock.Code("text", literal), ContentBlock.Math("outside", true)),
                ResponseParser.parse("$fence\n\n\\[outside\\]"),
            )
        }
        assertEquals(
            listOf(ContentBlock.Code("text", literal)),
            ResponseParser.parse("- ```text\n${literal.prependIndent("  ")}"),
        )
    }

    @Test fun shorterOrDifferentFencesStayInsideCode() {
        val literal = "\$\$a\$\$\n```\n\\[b\\]\n~~~\n\\(c\\)"
        assertEquals(
            listOf(ContentBlock.Code("text", literal), ContentBlock.Math("outside", true)),
            ResponseParser.parse("````text\n$literal\n````\n\n\$\$outside\$\$"),
        )
    }

    @Test fun inlineMathCurrencyAndCodeKeepDistinctGoldenSpans() {
        val content =
            (
                ResponseParser
                    .parse(
                        "Cost \\$5 and \\$10; \$x\$ then \\(y\\), `\$z\$ \\[w\\]`.",
                    ).single() as ContentBlock.Paragraph
            ).content
        assertEquals("Cost \$5 and \$10; x then y, \$z\$ \\[w\\].", content.joinToString("") { it.text })
        assertEquals(
            listOf(InlineContent("x", math = true), InlineContent("y", math = true)),
            content.filter { it.math },
        )
        assertEquals(listOf(InlineContent("\$z\$ \\[w\\]", code = true)), content.filter { it.code })
        assertEquals(listOf(paragraph("Cost \$5 and \$10.")), ResponseParser.parse("Cost \$5 and \$10."))
        assertEquals(
            listOf(ContentBlock.Paragraph(listOf(InlineContent("\\[x\\] \$\$y\$\$", code = true)))),
            ResponseParser.parse("`\\[x\\] \$\$y\$\$`"),
        )
    }

    @Test fun indentedCodeKeepsDelimiterSource() {
        for ((open, close) in delimiters) {
            assertEquals(
                listOf(ContentBlock.Code("", "$open\n  x\n$close")),
                ResponseParser.parse("    $open\n      x\n    $close"),
            )
        }
    }

    private fun paragraph(text: String) = ContentBlock.Paragraph(listOf(InlineContent(text)))
    @Test fun midlineDisplayKeepsTheProseBeforeIt() {
        // Regression: the opener's line prefix used to be discarded, so "The area is $$A$$ where"
        // rendered only the formula and " where" — the sentence subject vanished.
        assertEquals(
            listOf(
                paragraph("The area is"),
                ContentBlock.Math("A=\\pi r^2", true),
                paragraph("where r is the radius."),
            ),
            ResponseParser.parse("The area is \$\$A=\\pi r^2\$\$ where r is the radius."),
        )
    }

    @Test fun quotedDisplayMathDoesNotKeepQuoteMarkers() {
        // Regression: continuation lines of a display opened behind "> " kept their marker, so the
        // equation source was "> x + y" and JLaTeXMath typeset a literal greater-than glyph.
        assertEquals(
            listOf(ContentBlock.Math("x + y", true)),
            ResponseParser.parse("> \$\$\n> x + y\n> \$\$"),
        )
    }

    @Test fun codeBlockKeepsItsOwnListMarkers() {
        // Regression: replay decided "strip container markers" from the OPENING fence line, which is
        // unmarked for every top-level fence, so a body line lost its "- " / "+ " and a YAML block or
        // a diff stopped reproducing its input.
        assertEquals(
            listOf(ContentBlock.Code("yaml", "- name: foo\n  value: 1")),
            ResponseParser.parse("```yaml\n- name: foo\n  value: 1\n```"),
        )
        assertEquals(
            listOf(ContentBlock.Code("diff", "- removed\n+ added")),
            ResponseParser.parse("```diff\n- removed\n+ added\n```"),
        )
    }

    @Test fun topLevelFenceIsNotClosedByQuoteMarkedContent() {
        // A fence body is literal, so a line that only LOOKS like a closing fence after stripping
        // "> " must stay content.
        assertEquals(
            listOf(ContentBlock.Code("md", "> ```python\n> print(1)\n> ```")),
            ResponseParser.parse("```md\n> ```python\n> print(1)\n> ```\n```"),
        )
    }
    @Test fun fencedTableSampleDoesNotStealTheRealTablesCellCount() {
        // Regression: the overflow scan was fence-blind, so a table-shaped sample inside a fence
        // queued a count that the next real table then reported.
        val src = "```\n| a | b |\n|---|---|\n| 1 | 2 | 3 |\n```\n\n| H1 | H2 |\n|---|---|\n| 1 | 2 |\n"
        val blocks = ResponseParser.parse(src)
        val tables = blocks.filterIsInstance<ContentBlock.Table>()
        assertEquals("only the real table is a table", 1, tables.size)
        assertEquals("the real table dropped nothing", 0, tables.single().droppedCells)
    }

    @Test fun quotedTableIsCountedNotSkipped() {
        // Regression: markers were not stripped for detection, so a quoted table was not seen at all
        // and the overflow it DID cause was reported against the following table.
        val src = "> | a | b |\n> |---|---|\n> | 1 | 2 | 3 |\n\n| H1 | H2 |\n|---|---|\n| 1 | 2 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("both tables parse", 2, tables.size)
        assertEquals("quoted table reports its own overflow", 1, tables[0].droppedCells)
        assertEquals("following table reports none", 0, tables[1].droppedCells)
    }


    @Test fun deeplyNestedCodeBlockDegradesToTextNotToNothing() {
        // Regression: flattenText only read Text/Code/MathInline nodes, and a block node carries its
        // text on itself — so a fenced block past the depth cap vanished instead of degrading.
        val nested = (1..30).joinToString("\n") { ">".repeat(it) + " x" }
        val src = "$nested\n" + ">".repeat(30) + " ```\nCODEKEPT\n```"
        val text = ResponseParser.parse(src).toString()
        assertTrue("the code text must survive the depth cap, got: $text", text.contains("CODEKEPT"))
    }
    @Test fun gluedQuoteFenceStillCloses() {
        // Regression: close detection used the uncontainer()-stripped line, which cannot strip a GLUED
        // quote run (">```"). The fence opened and never closed, so the block rendered EMPTY and its
        // body spilled out as prose.
        for (open in listOf(">```", ">>```", ">>>```", "> ```")) {
            val close = open.substringBefore("```") + "```"
            val src = "$open\ncode\n$close\n\nAfter."
            val blocks = ResponseParser.parse(src)
            assertEquals(
                "form $open must produce one code block carrying its body, got: $blocks",
                listOf(ContentBlock.Code("", "code"), ContentBlock.Paragraph(listOf(InlineContent("After.")))),
                blocks,
            )
        }
    }

    @Test fun quotedHeaderOverUnquotedDelimiterIsNotATable() {
        // Regression: header and delimiter were uncontainer()'d independently, so this pair counted as
        // a table region the parser never builds. The queue then shifted and a table that really lost
        // cells reported none.
        val src = "| a | b |\n> |---|---|\n| 1 | 2 |\n\n| H | K |\n|---|---|\n| 1 | 2 | 3 | 4 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("only the real table parses", 1, tables.size)
        assertEquals("the real table must report both clamped cells", 2, tables.single().droppedCells)
    }

    @Test fun unquotedHeaderOverQuotedDelimiterIsNotATable() {
        val src = "> | a | b |\n|---|---|\n| 1 | 2 | 3 |\n\n| H | K |\n|---|---|\n| 1 | 2 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals("the clean table hid nothing", 0, tables.single().droppedCells)
    }

    @Test fun fenceBodyMayContainAFenceLookingLine() {
        // A top-level code block is literal. A body line that only LOOKS like a fence after container
        // stripping must not close it, and the $$ line after it must stay code rather than being torn
        // out as display math.
        val src = "```md\n> ```\n\$\$x\$\$\n```\n\nTail."
        val blocks = ResponseParser.parse(src)
        assertEquals(
            "the whole sample must stay one code block, got: $blocks",
            listOf(
                ContentBlock.Code("md", "> ```\n\$\$x\$\$"),
                ContentBlock.Paragraph(listOf(InlineContent("Tail."))),
            ),
            blocks,
        )
    }
    @Test fun codeBlockContainingAFenceLineDoesNotStopOverflowCounting() {
        // Regression: the overflow scan closed fences on the container-stripped line while the parser
        // closes them on the raw line. A "> ```" inside a code block therefore closed the scan's fence
        // early, reopened one on the real closer, and skipped EVERY later table — so a table that
        // really clamped cells showed no "cells hidden" line at all.
        val src =
            "```md\n> ```\n```\n\n" +
                "| H | K |\n|---|---|\n| 1 | 2 |\n\n" +
                "| A | B |\n|---|---|\n| 1 | 2 | 3 | 4 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("both real tables must parse", 2, tables.size)
        assertEquals("first table hid nothing", 0, tables[0].droppedCells)
        assertEquals("second table clamped two cells", 2, tables[1].droppedCells)
    }

    @Test fun indentedTableSampleIsNotCountedAsATable() {
        // Regression: four-space indentation is an INDENTED CODE BLOCK in CommonMark, never a table.
        // Counting it queued a phantom that the next real table reported, so a clean table claimed to
        // have hidden cells after displaying all of them.
        val src = "    | a | b |\n    |---|---|\n    | 1 | 2 |\n\n| H | K |\n|---|---|\n| 1 | 2 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("only the clean table parses", 1, tables.size)
        assertEquals("the clean table hid nothing", 0, tables.single().droppedCells)
    }

    @Test fun headerWiderThanDelimiterIsNotATable() {
        // Regression: GFM builds no table when the delimiter row has fewer cells than the header, but
        // the scan queued a count for it and the next real table consumed the phantom.
        val src = "| a | b | c |\n|---|---|\n| 1 | 2 |\n\n| H | K |\n|---|---|\n| 1 | 2 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("only the well-formed table parses", 1, tables.size)
        assertEquals("the well-formed table hid nothing", 0, tables.single().droppedCells)
    }
    @Test fun listNestedTableIsCounted() {
        // Regression: the scan compared container prefixes by CHARACTER COUNT, so a list item's header
        // (`1. ` marker) and its space-indented continuation rows looked like different containers. The
        // table the library DOES build was skipped and the overflow queue shifted by one, hiding a
        // clamped cell without any notice.
        val src = "1. | a | b |\n   |---|---|\n   | 1 | 2 | 3 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("the list-nested table parses", 1, tables.size)
        assertEquals("its clamped cell must be reported", 1, tables.single().droppedCells)
    }

    @Test fun listNestedTableDoesNotStealTheNextTablesCount() {
        val src =
            "- | a | b |\n  |---|---|\n  | 1 | 2 | 3 |\n\n" +
                "> | a | b |\n> |---|---|\n> | 1 | 2 | 3 | 4 |\n\n" +
                "| H | K |\n|---|---|\n| 1 | 2 |\n"
        val tables = ResponseParser.parse(src).filterIsInstance<ContentBlock.Table>()
        assertEquals("three tables parse", 3, tables.size)
        assertEquals("list table reports its own", 1, tables[0].droppedCells)
        assertEquals("quoted table reports its own", 2, tables[1].droppedCells)
        assertEquals("clean table reports nothing", 0, tables[2].droppedCells)
    }
    @Test fun indentedDisplayMathInsideAListIsMath() {
        // Regression: indentation was measured absolutely, so inside a list item whose content indent is
        // 3, four columns of indent is ordinary prose — but it was read as an indented code block and the
        // formula rendered as the literal text "$$x$$".
        val blocks = ResponseParser.parse("1. text\n\n    \$\$x + y\$\$\n")
        assertTrue("a formula in a list must be typeset, got: $blocks", blocks.any { it is ContentBlock.Math })
    }

    @Test fun indentedDisplayMathAtTopLevelStaysLiteralCode() {
        // The counterpart: with no list context, four spaces IS an indented code block.
        val blocks = ResponseParser.parse("    \$\$x + y\$\$\n")
        assertFalse("top-level indented math is code, got: $blocks", blocks.any { it is ContentBlock.Math })
    }}
