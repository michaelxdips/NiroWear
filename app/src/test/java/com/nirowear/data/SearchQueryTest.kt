package com.nirowear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun blankInputHasNoTerms() {
        assertEquals(emptyList<String>(), likeTerms(""))
        assertEquals(emptyList<String>(), likeTerms("   \n\t "))
    }

    @Test
    fun wordsSplitAndKeepOrder() {
        assertEquals(listOf("rayleigh", "scatter"), likeTerms("rayleigh scatter"))
    }

    @Test
    fun sqliteWildcardsAreEscaped() {
        // Unescaped, these would turn one query into a wildcard scan of everything.
        assertEquals(listOf("100\\%"), likeTerms("100%"))
        assertEquals(listOf("snake\\_case"), likeTerms("snake_case"))
        assertEquals(listOf("a\\\\b"), likeTerms("a\\b"))
        assertEquals(listOf("C++"), likeTerms("C++"))
    }

    @Test
    fun termCountIsBounded() {
        val many = (1..20).joinToString(" ") { "t$it" }
        val terms = likeTerms(many)
        assertEquals(8, terms.size)
        assertTrue(terms.first() == "t1" && terms.last() == "t8")
    }

    @Test
    fun snippetCentersAroundMatchAndCollapsesWhitespace() {
        val longBody = "Intro ".repeat(25) + "needle_100%   in\n\tthe   haystack " + "tail ".repeat(20)
        val snippet = extractSearchSnippet(longBody, likeTerms("needle_100%"))
        assertTrue("Expected centered snippet around match, got: $snippet", snippet.contains("needle_100% in the haystack"))
        assertTrue("Snippet must remain compact (len=${snippet.length})", snippet.length <= 82)
    }

    @Test
    fun snippetHandlesHugeBodyAndLeadingWhitespaceWithoutOomOrSpuriousEllipsis() {
        val hugeBody = "prefix ".repeat(20_000) + "deep_target_99%   found\nhere " + "suffix ".repeat(20_000)
        val deepSnippet = extractSearchSnippet(hugeBody, likeTerms("deep_target_99%"))
        assertTrue("Deep hit must start with ellipsis, got: $deepSnippet", deepSnippet.startsWith("…"))
        assertTrue("Deep hit must include match, got: $deepSnippet", deepSnippet.contains("deep_target_99% found here"))
        assertTrue("Deep hit must stay compact, len=${deepSnippet.length}", deepSnippet.length <= 82)

        val paddedShort = " ".repeat(500) + "short   answer\twith\nspaces"
        assertEquals("short answer with spaces", extractSearchSnippet(paddedShort, likeTerms("answer")))
        assertEquals("", extractSearchSnippet(" ".repeat(500), likeTerms("anything")))

        val wideSpacesBetweenWords = "alpha" + " ".repeat(500) + "needle" + " ".repeat(500) + "omega"
        assertEquals("alpha needle omega", extractSearchSnippet(wideSpacesBetweenWords, likeTerms("needle")))
        assertEquals("alpha needle omega", extractSearchSnippet(wideSpacesBetweenWords, likeTerms("missing")))
        assertEquals("", extractSearchSnippet("hello world", likeTerms("hello"), maxChars = 0))
        assertEquals(
            "truncating right at a whitespace run must not leave a trailing space",
            "a".repeat(19),
            extractSearchSnippet("a".repeat(19) + "   hello", likeTerms("missing"), maxChars = 20),
        )
        assertEquals(
            "huge maxChars must not overflow Int arithmetic",
            "hello world",
            extractSearchSnippet("hello   world", likeTerms("world"), maxChars = Int.MAX_VALUE),
        )
        assertEquals(
            "empty plain term must be ignored cleanly",
            "hello world",
            extractSearchSnippetPlain("hello   world", listOf(""), maxChars = 80),
        )
    }
}