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
}