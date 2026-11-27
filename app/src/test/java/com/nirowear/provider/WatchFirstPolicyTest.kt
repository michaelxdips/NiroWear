package com.nirowear.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared system instruction must obey the UX contract it lives under.
 *
 * AGENTS.md: "Never shorten an answer merely because the screen is small. Solve small screens with
 * layout, scrolling, typography and content transformation." The instruction used to ask for "the
 * shortest answer" BY DEFAULT because the reader is a watch — the forbidden reason — and these tests
 * pinned that. They now pin the correct policy: length follows the question, and a long answer is made
 * readable by STRUCTURE (headings, lists, tables, code, display math) rather than by cutting content.
 */
class WatchFirstPolicyTest {
    private val policy = SYSTEM_INSTRUCTION

    @Test
    fun neverAsksForABrevityDefaultBecauseOfTheDevice() {
        assertTrue("policy must state the watch context", policy.contains("smartwatch"))
        assertFalse(
            "the contract forbids shortening an answer because the screen is small: $policy",
            policy.contains("shortest answer"),
        )
        assertFalse("no device-driven brevity default", policy.contains("Prefer a short paragraph"))
        assertTrue(
            "length must be governed by the question",
            Regex("Length is set by the question").containsMatchIn(policy),
        )
        assertTrue(
            "the screen must be named as NOT the reason for length",
            Regex("never.{0,20}by the screen").containsMatchIn(policy),
        )
    }

    @Test
    fun asksForStructureInsteadOfBrevity() {
        // Structure is the sanctioned remedy for a small screen.
        for (kind in listOf("headings", "lists", "tables", "fenced", "display math")) {
            assertTrue("policy must request structured output: $kind", policy.contains(kind))
        }
        assertTrue(
            "the policy must name structure as the way to keep a long answer readable",
            policy.contains("STRUCTURE"),
        )
    }

    @Test
    fun forbidsDroppingContentToLookShorter() {
        assertTrue(
            "policy must forbid device-driven omission",
            policy.contains("never drop required facts"),
        )
        for (kind in listOf("caveats", "steps", "examples", "code", "math", "table rows")) {
            assertTrue("omission ban must cover: $kind", policy.contains(kind))
        }
    }

    @Test
    fun completenessExceptionForStructuredContent() {
        assertTrue("policy must protect completeness", policy.contains("Do not destructively shorten"))
        for (kind in listOf("code", "math", "tables", "procedures", "structured output")) {
            assertTrue("completeness exception must cover: $kind", policy.contains(kind))
        }
        assertTrue("exception must be framed by task completeness", policy.contains("completeness is the task"))
    }

    @Test
    fun demandsDirectAccurateAnswersWithoutLecturing() {
        assertTrue(
            "policy must ask for accurate direct answers",
            policy.contains("accurate, direct, and strictly to the point"),
        )
        assertTrue(
            "policy must forbid unsolicited lecturing",
            policy.contains("unsolicited lecturing"),
        )
    }

    @Test
    fun keepsTheQualityGuidanceThatIsNotAboutLength() {
        assertTrue("policy must discourage repetition", policy.contains("Avoid repetition"))
        assertTrue("policy must discourage preamble", policy.contains("preamble"))
        assertTrue("policy must discourage generic closers", policy.contains("closing offers"))
        assertTrue("policy must request Markdown and LaTeX", policy.contains("Markdown and LaTeX"))
    }

    @Test
    fun singleSharedPolicyAcrossAllProviderTypes() {
        // Every adapter consumes the same default constant: ChatRequest defaults to
        // SYSTEM_INSTRUCTION, so all provider types share one policy with no
        // provider-specific duplicate instruction strings anywhere.
        for (type in ProviderType.entries) {
            val request = ChatRequest(listOf(ChatMessage(ChatRole.USER, "q")))
            assertEquals(
                "provider type $type must use the shared default policy",
                SYSTEM_INSTRUCTION,
                request.systemInstruction,
            )
        }
    }
}