package com.nirowear.renderer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ResponseParserTest {
    private val fixture = File("../fixtures/RENDER_TORTURE_FIXTURE.md").readText()

    @Test fun fixturePreservesSemanticContent() {
        val blocks = ResponseParser.parse(fixture)
        assertTrue(blocks.any { it is ContentBlock.Heading })
        assertTrue(blocks.any { it is ContentBlock.Quote })
        assertEquals(
            6,
            blocks.filterIsInstance<ContentBlock.ListEntry>().count {
                it.content
                    .firstOrNull()
                    ?.text
                    ?.contains("item") ==
                    true ||
                    it.content
                        .firstOrNull()
                        ?.text
                        ?.contains("bullet") == true
            },
        )
        val math = blocks.filterIsInstance<ContentBlock.Math>()
        assertEquals(8, math.size)
        assertTrue(math.all { it.complete })
        assertTrue(math.any { it.source == "\\begin{aligned}a&=b+c\\\\d&=e-f\\end{aligned}" })
        assertTrue(
            blocks
                .filterIsInstance<ContentBlock.Code>()
                .single()
                .source
                .contains("    client.newCall"),
        )
        val tables = blocks.filterIsInstance<ContentBlock.Table>()
        assertEquals(2, tables.size)
        assertEquals(
            listOf(
                "Top",
                "TOP-01",
                "First row remains associated",
                "Top",
                "TOP-02",
                "Every cell survives vertical reading",
            ),
            tables
                .first()
                .rows
                .flatten()
                .map { cell -> cell.joinToString("") { it.text } },
        )
        val table = tables.last()
        assertEquals(4, table.headers.size)
        assertEquals(3, table.rows.size)
        assertTrue(
            blocks.filterIsInstance<ContentBlock.Paragraph>().flatMap { it.content }.any {
                it.link?.startsWith("https://example.com") ==
                    true
            },
        )
    }

    @Test fun everyStreamingPrefixParses() {
        for (end in 0..fixture.length) ResponseParser.parse(fixture.take(end))
        assertFalse((ResponseParser.parse("\\[\\frac{1").single() as ContentBlock.Math).complete)
        assertTrue(ResponseParser.parse("```kotlin\n\\[not math").single() is ContentBlock.Code)
        assertEquals(
            "α العربية 中文",
            (ResponseParser.parse("α العربية 中文").single() as ContentBlock.Paragraph).content.single().text,
        )
    }

    @Test fun formattingAndCodeStayDistinct() {
        val content =
            (
                ResponseParser
                    .parse(
                        "**bold** *italic* `x\$y\$` [link](https://example.com) \$x^2\$",
                    ).single() as ContentBlock.Paragraph
            ).content
        assertTrue(content.any { it.bold && it.text == "bold" })
        assertTrue(content.any { it.italic && it.text == "italic" })
        assertTrue(content.any { it.code && it.text == "x\$y\$" && !it.math })
        assertTrue(content.any { it.math && it.text == "x^2" })
    }

    @Test fun indentedCodeKeepsMathDelimitersAndWhitespace() {
        for (source in listOf("    \\[x^2\\]", "    \$\$x^2\$\$", "\t\\[x^2\\]")) {
            val block = ResponseParser.parse(source).single()
            assertTrue("Indented Markdown code must not become math: $source", block is ContentBlock.Code)
            assertEquals(source.trimStart(), (block as ContentBlock.Code).source)
        }
    }

    @Test fun escapesCurrencyAndParenthesizedMath() {
        val spans =
            (
                ResponseParser
                    .parse(
                        "Cost \\$5 and \\$10. Math \\(x_1\\) and \$\\frac{1}{2}\$.",
                    ).single() as ContentBlock.Paragraph
            ).content
        assertEquals(listOf("x_1", "\\frac{1}{2}"), spans.filter { it.math }.map { it.text })
        val currency = (ResponseParser.parse("Cost \$5 and \$10.").single() as ContentBlock.Paragraph).content
        assertTrue(currency.none { it.math })
        assertEquals("Cost \$5 and \$10.", currency.joinToString("") { it.text })
    }

    @Test fun trailingBackslashDoesNotCrossNewlineIntoInlineMath() {
        val dollarNewline = ResponseParser.parse("\$x \\\ny\$")
        assertTrue(
            dollarNewline.filterIsInstance<ContentBlock.Paragraph>().flatMap { it.content }.none { it.math },
        )
        val parenNewline = ResponseParser.parse("\\(x \\\ny\\)")
        assertTrue(
            parenNewline.filterIsInstance<ContentBlock.Paragraph>().flatMap { it.content }.none { it.math },
        )
    }
}
