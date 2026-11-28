package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that fast paths in [ResponseParser.parse] and [ResponseParser.parseIncremental] produce
 * bit-identical [ContentBlock] lists to clean stateless parsing across adversarial and streaming inputs.
 */
class ResponseParserStreamingPerfTest {
    private fun assertStreamingParity(
        label: String,
        fullText: String,
        stepBytes: Int,
    ) {
        var state: ResponseParser.IncrementalParseState? = null
        var pos = 0
        while (pos < fullText.length) {
            pos = (pos + stepBytes).coerceAtMost(fullText.length)
            val prefix = fullText.substring(0, pos)
            val (nextState, incBlocks) = ResponseParser.parseIncremental(state, prefix)
            state = nextState
            val fullBlocks = ResponseParser.parse(prefix)
            assertEquals("Mismatch for $label at prefix length $pos", fullBlocks, incBlocks)
        }
    }

    @Test
    fun mixedFourKbStreamingMatchesFullParseAtEveryStep() {
        val doc =
            buildString {
                append("# Overview\n\n")
                append("Intro prose with **bold**, *italic*, `code`, inline math \$E=mc^2\$, and [link](https://example.com).\n\n")
                repeat(18) { idx ->
                    append("## Section $idx\n\n")
                    append("Paragraph $idx describes the streaming parser pipeline over chunks without HTML or ref defs.\n\n")
                    append("```kotlin\nfun tick$idx(x: Int) = x * 2\n```\n\n")
                    append("$$\nx_{$idx} = \\frac{a + b}{2}\n$$\n\n")
                }
                append("| Col A | Col B | DroppedC |\n|---|---|---|\n| r1a | r1b | extra1 |\n| r2a | r2b |\n\n")
                repeat(20) { i ->
                    append("- Bullet item $i with `inline` span\n")
                }
            }
        assertTrue("Expected > 4000 chars, got ${doc.length}", doc.length > 4000)
        assertStreamingParity("mixed-32", doc, 32)
        assertStreamingParity("mixed-97", doc, 97)
        val (cachedState, cachedBlocks) = ResponseParser.parseIncremental(null, doc)
        assertTrue("Incremental parse state should activate on >4KB structured doc", cachedState != null && cachedState.prefix.isNotEmpty())
        assertEquals(ResponseParser.parse(doc), cachedBlocks)
    }

    @Test
    fun mixedDocWithTableAndConsecutiveBulletsAdvancesPrefixAndMatchesFullParse() {
        val mixedCore =
            buildString {
                append("## Architectural Summary\n\n")
                append("Streaming Markdown uses CommonMark plus \$E=mc^2\$ and links [NiroWear](https://nirowear.example).\n\n")
                append(
                    "```kotlin\nfun streamTick(bytes: Int): Long {\n    val t0 = System.nanoTime()\n" +
                        "    return (bytes * 16L) + t0\n}\n```\n\n",
                )
                append("$$\n\\sum_{k=1}^{n} \\frac{1}{k^2} = \\frac{\\pi^2}{6}\n$$\n\n")
                append("| Stage | Cost | Owner | ExtraColumn |\n|---|---|---|---|\n")
                append("| Pre-scan | low | parser | alpha-cell |\n")
                append("| CommonMark | mid | library | beta-cell | clamped |\n")
                append("| Overflow | check | table | gamma-cell |\n\n")
                var i = 0
                while (length < 4096) {
                    append("- Item ${i++}: observed streaming chunk behaviour with **bold** and `inline` spans.\n")
                }
            }
        val doc = mixedCore.take(4096)
        assertStreamingParity("docMixed-32", doc, 32)
        var state: ResponseParser.IncrementalParseState? = null
        var pos = 0
        while (pos < doc.length) {
            pos = (pos + 32).coerceAtMost(doc.length)
            val (nextState, blocks) = ResponseParser.parseIncremental(state, doc.substring(0, pos))
            state = nextState
            assertEquals(ResponseParser.parse(doc.substring(0, pos)), blocks)
        }
        assertTrue(
            "Expected incremental prefix >3000 chars, got ${state?.prefix?.length}",
            (state?.prefix?.length ?: 0) > 3000,
        )
    }

    @Test
    fun adversarialBulletsOrderedListsAndDeepContainersStayExact() {
        val doc =
            buildString {
                append("- Top bullet zero\n  indented continuation line\n- Top bullet one\n- ---\n- - nested bullet\n")
                append("- > quoted inside bullet\n- Item math \$\$x=1\$\$\n- Last plain item\n\n")
                append("1. Ordered alpha\n2. Ordered beta\n3. Ordered gamma\n\n")
                append(">".repeat(60) + " deep container tail")
            }
        assertStreamingParity("adversarial-edges", doc, 17)
        assertEquals(ResponseParser.parse(doc), ResponseParser.parseIncremental(null, doc).second)
    }

    @Test
    fun lateReferenceLinkDefinitionsPreserveBitExactParity() {
        val doc =
            buildString {
                append("Read the [reference guide][ref] carefully.\n\n")
                repeat(12) { i ->
                    append("Paragraph $i filler text to cross incremental minimum thresholds cleanly.\n\n")
                }
                append("[ref]: https://example.org/reference\n")
            }
        assertStreamingParity("late-ref-def", doc, 41)
    }

    @Test
    fun lazyBlockquoteHtmlAndListContinuationsStayExact() {
        val doc =
            buildString {
                repeat(8) { i ->
                    append("Top block $i that crosses incremental prefix sizing cleanly.\n\n")
                }
                append("> First quoted line\nlazy second quoted line\n\n")
                append("1. Ordered one\n\n   continuation paragraph inside item\n\n2. Ordered two\n\n")
                append("<pre>\nblock line 1\n\nblock line 2\n</pre>\n\n")
                append("Final trailing prose line.\n")
            }
        assertStreamingParity("lazy-html-list", doc, 29)
    }

    @Test
    fun nonMonotonicEditAndCrlfInputsInvalidateSafely() {
        val initial = "A".repeat(450) + "\n\nFirst tail paragraph"
        val (s1, b1) = ResponseParser.parseIncremental(null, initial)
        assertEquals(ResponseParser.parse(initial), b1)

        val edited = "Edited start " + "B".repeat(450) + "\r\n\r\nSecond paragraph\r\n"
        val (_, b2) = ResponseParser.parseIncremental(s1, edited)
        assertEquals(ResponseParser.parse(edited), b2)
    }

    @Test
    fun multipleMathBlocksOnOneLineWithUnclosedTailPreserveParity() {
        val doc1 =
            buildString {
                append("\$\$a + b\$\$ \$\$unclosed_first\n")
                append("x_1 + x_2 + " + "y + ".repeat(120) + "z\n\n")
                append("second_line_inside_math + " + "w + ".repeat(120) + "q\n")
                append("\$\$\n\n")
                append("Trailing prose after multi-line math.\n")
            }
        assertStreamingParity("multi-math-open-line", doc1, 31)

        val doc2 =
            buildString {
                append("\$\$\nalpha\n\$\$ \$\$unclosed_second\n")
                append("m_1 + " + "n + ".repeat(120) + "p\n\n")
                append("still_in_second + " + "k + ".repeat(120) + "j\n")
                append("\$\$\n\n")
                append("After second math.\n")
            }
        assertStreamingParity("multi-math-close-reopen", doc2, 37)

        val doc3 =
            buildString {
                append("Lead text \$\$a\$\$ \$\$unclosed_mid\n")
                append("u_1 + " + "v + ".repeat(120) + "w\n\n")
                append("still_mid + " + "r + ".repeat(120) + "s\n")
                append("\$\$\n\n")
                append("After mid-line math.\n")
            }
        assertStreamingParity("multi-math-midline-reopen", doc3, 29)
    }

    @Test
    fun gluedQuoteFenceAndHtmlTypeOneToFiveBlocksPreserveParity() {
        val gluedFence =
            buildString {
                append(">```kotlin\n")
                append("val alpha = " + "1 + ".repeat(110) + "0\n\n")
                append("val beta = " + "2 + ".repeat(110) + "0\n")
                append(">```\n\n")
                append("After glued quoted fence.\n")
            }
        assertStreamingParity("glued-quote-fence", gluedFence, 33)

        val htmlBlocks =
            buildString {
                append("Intro paragraph " + "word ".repeat(90) + "end.\n\n")
                append("<textarea>\nline 1 " + "a ".repeat(200) + "\n\nline 2 " + "b ".repeat(200) + "\n</textarea>\n\n")
                append("<?xml version=\"1.0\"?>\npi line 1\n\npi line 2\n?>\n\n")
                append("Trailing prose after HTML blocks.\n")
            }
        assertStreamingParity("html-textarea-pi", htmlBlocks, 43)
    }

    @Test
    fun closingMathFollowedByFenceOrHtmlPreservesParity() {
        val mathThenFence =
            buildString {
                append("\$\$\nalpha + beta\n\$\$ ```kotlin\n")
                append("val first = " + "1 + ".repeat(110) + "0\n\n")
                append("val second = " + "2 + ".repeat(110) + "0\n")
                append("```\n\n")
                append("Trailing prose after math+fence.\n")
            }
        assertStreamingParity("math-then-fence", mathThenFence, 31)

        val inlineCloseThenHtml =
            buildString {
                append("\$\$x = 1\$\$ <pre>\n")
                append("raw line 1 " + "a ".repeat(180) + "\n\n")
                append("raw line 2 " + "b ".repeat(180) + "\n")
                append("</pre>\n\n")
                append("After math+html.\n")
            }
        assertStreamingParity("math-then-html", inlineCloseThenHtml, 37)
    }

    @Test
    fun multilineAndQuotedReferenceDefinitionsPreserveParityWithoutDisablingCodeCaching() {
        val multilineRef =
            buildString {
                append("See [multi line] for details.\n\n")
                repeat(10) { i ->
                    append("Body paragraph $i filler text to cross incremental prefix sizing cleanly.\n\n")
                }
                append("[multi\nline]: https://example.org/multiline\n")
            }
        assertStreamingParity("multiline-ref-def", multilineRef, 37)

        val quotedRef =
            buildString {
                append("Read [quoted-ref] below.\n\n")
                repeat(10) { i ->
                    append("Section $i filler text to cross incremental prefix sizing cleanly.\n\n")
                }
                append("> [quoted-ref]: https://example.org/quoted\n")
            }
        assertStreamingParity("quoted-ref-def", quotedRef, 41)

        val codeWithBracketColon =
            buildString {
                repeat(10) { i ->
                    append("Paragraph $i explains `val entry = map[key]: Int` cleanly.\n\n")
                }
                append("Final trailing paragraph.\n")
            }
        val (state, blocks) = ResponseParser.parseIncremental(null, codeWithBracketColon)
        assertEquals(ResponseParser.parse(codeWithBracketColon), blocks)
        assertTrue("Non-reference `]:` inside code must not disable incremental caching", state != null && state.prefix.isNotEmpty())
    }

    @Test
    fun tableAndIndentedCodeUnclosedDollarsAndNestedBracketMathPreserveParity() {
        val tableUnclosedDollar =
            buildString {
                append("| Col1 | Col2 |\n|---|---|\n| cell \$\$unclosed | value |\n\n")
                repeat(8) { i ->
                    append("Filler paragraph $i to cross incremental prefix sizing cleanly.\n\n")
                }
                append("\$\$\nfirst_line + a\n\n")
                append("second_line + " + "c + ".repeat(120) + "d\n\n")
                append("third_line + e\n\$\$\n\n")
                append("Trailing prose.\n")
            }
        assertStreamingParity("table-unclosed-dollar", tableUnclosedDollar, 31)

        val indentedCodeDollar =
            buildString {
                append("    val price = prefix \$\$unclosed\n\n")
                repeat(8) { i ->
                    append("Filler paragraph $i to cross incremental prefix sizing cleanly.\n\n")
                }
                append("\$\$\nfirst_line + a\n\n")
                append("second_line + " + "c + ".repeat(120) + "d\n\n")
                append("third_line + e\n\$\$\n\n")
                append("Trailing prose.\n")
            }
        assertStreamingParity("indented-code-dollar", indentedCodeDollar, 37)

        val nestedListBracketMath =
            buildString {
                append("- outer item\n  - inner item\n    \\[\n")
                append("    x_1 + a\n\n")
                append("x_2 + " + "c + ".repeat(120) + "d\n\n")
                append("    x_3 + e\n    \\]\n\n")
                append("Trailing prose after nested bracket math.\n")
            }
        assertStreamingParity("nested-list-bracket-math", nestedListBracketMath, 29)

        val midlineMathThenFence =
            buildString {
                append("See \$\$x = 1\$\$ ```kotlin\n")
                append("val first = 1\n\n")
                append("val second = " + "2 + ".repeat(120) + "0\n\n")
                append("val third = 3\n")
                append("```\n\n")
                append("Trailing prose after midline math+fence.\n")
            }
        assertStreamingParity("midline-math-then-fence", midlineMathThenFence, 31)
    }

    @Test
    fun yamlListInsideNestedListFenceDoesNotSwallowFollowingDisplayMath() {
        val doc =
            buildString {
                append("- outer\n")
                append("  - inner\n")
                append("    ```yaml\n")
                append("    - build\n")
                append("    ```\n\n")
                append("\$\$\nE = mc^2\n\$\$\n")
            }
        val blocks = ResponseParser.parse(doc)
        assertTrue(
            "Display math after nested-list YAML fence must parse as ContentBlock.Math, got: $blocks",
            blocks.any { it is ContentBlock.Math && it.source == "E = mc^2" && it.complete },
        )
        assertStreamingParity("nested-list-yaml-fence", doc, 19)
    }

    @Test
    fun randomizedMarkdownLatexFuzzMatchesFullParseAtAllSteps() {
        val rng = java.util.Random(0x5EED_42L)
        val fragments =
            listOf(
                "Plain prose paragraph with **bold**, *italic*, `code`, and \$x^2+y^2=1\$.\n\n",
                "## Heading title\n\n",
                "- Bullet one\n- Bullet two\n- Bullet three\n\n",
                "1. First item\n   continued line\n2. Second item\n\n",
                "- outer\n  - inner\n    ```yaml\n    - step\n    ```\n\n",
                "- item with fence\n\n    ```kotlin\n    val p = 1\n\n    val q = 2\n\n    val r = 3\n    ```\n\n",
                "- outer\n  - inner\n    \\[\n    a + b\n\n    c + d\n\n    e + f\n    \\]\n\n",
                "> Quoted line\n> second line\n\n",
                ">```kotlin\nval x = 1\n\nval y = 2\n>```\n\n",
                "```kotlin\nval a = 1\n\nval b = 2\n```\n\n",
                "~~~\nline one\n\nline two\n~~~\n\n",
                "\$\$\n\\frac{a}{b}\n\n+ c\n\$\$\n\n",
                "\\[\nx + y\n\n= z\n\\]\n\n",
                "Inline lead \$\$a+b\$\$ tail prose.\n\n",
                "Lead \$\$a\$\$ ```py\nx = 1\n\ny = 2\n```\n\n",
                "\$\$x\$\$ <pre>\nraw 1\n\nraw 2\n</pre>\n\n",
                "| H1 | H2 |\n|---|---|\n| v1 \$\$unclosed | v2 |\n| v3 | v4 | extra |\n\n",
                "    indented code \$\$unclosed\n\n",
                "<textarea>\nraw a\n\nraw b\n</textarea>\n\n",
                "<?xml?>\npi 1\n\npi 2\n?>\n\n",
                "See [ref link][r1] here.\n\n",
                "[r1]: https://example.org/r1\n\n",
                "> [r2]: https://example.org/r2\n\n",
                "Code `map[k]: Int` in prose.\n\n",
                "Long filler " + "word ".repeat(85) + "end.\n\n",
            )
        repeat(200) { trial ->
            val count = 6 + rng.nextInt(10)
            val doc =
                buildString {
                    repeat(count) { append(fragments[rng.nextInt(fragments.size)]) }
                }
            val step = 23 + rng.nextInt(71)
            assertStreamingParity("fuzz-$trial", doc, step)
        }
    }
}
