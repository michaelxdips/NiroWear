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
}
