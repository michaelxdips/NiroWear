package com.nirowear.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NativeMathGuard` decides what untrusted assistant output may reach the typesetter. It is the one
 * part of the math path that can be pinned on the JVM, so the thresholds and every refusal reason are
 * asserted here rather than described in a comment.
 */
class NativeMathGuardTest {
    @Test
    fun refusesEveryDangerousCommandInTheList() {
        for (command in
            listOf(
                "\\includegraphics{a}", "\\includeonly{x}", "\\include{x}", "\\input{x}",
                "\\openin1=x", "\\openout1=x", "\\read1 to \\x",
                "\\renewenvironment{a}{}{}", "\\newenvironment{a}{}{}", "\\providecommand{\\a}{}",
                "\\renewcommand{\\a}{}", "\\newcommand{\\a}{}", "\\DeclareMathSizes{1}{2}{3}{4}",
                "\\jlmExternalFont{x}", "\\jlmDynamic{x}", "\\jlmXML{x}", "\\newcount\\c",
                "\\newdimen\\d", "\\gdef\\a{}", "\\edef\\a{}", "\\xdef\\a{}", "\\def\\a{}",
                "\\let\\a\\b", "\\csname a\\endcsname",
                "\\reflectbox{x}", "\\resizebox{1}{2}{x}", "\\rotatebox{1}{x}", "\\scalebox{2}{x}",
                "\\raisebox{1}{x}", "\\colorbox{red}{x}", "\\magnification=4",
            )
        ) {
            assertFalse("must refuse: $command", NativeMathGuard.isSafe(command))
        }
    }

    @Test
    fun aLegitimateMacroSharingAPrefixIsNotRefused() {
        // The refusal list was matched by prefix, so `\def` also matched `\definecolor` and the
        // formula silently degraded to source text for no reason.
        for (allowed in
            listOf(
                "\\definecolor{c}{rgb}{1,0,0}",
                "\\letter",
                "\\frac{a}{b}",
                "\\textcolor{red}{x}",
                "\\begin{matrix}a&b\\\\c&d\\end{matrix}",
            )
        ) {
            assertTrue("must admit: $allowed", NativeMathGuard.isSafe(allowed))
        }
        // The command itself is still refused when it stands alone.
        assertFalse(NativeMathGuard.isSafe("\\def\\a{1}"))
    }

    @Test
    fun spacingIsOrdinaryLayoutAndIsAdmitted() {
        // Refusing these silently degraded an honest formula to monospace source. None of them can
        // define, include or escape anything, and the renderer clamps the output bitmap.
        for (allowed in
            listOf(
                "\\hspace{1em}x",
                "\\vspace{1em}x",
                "\\hskip1em x",
                "\\vskip1em x",
                "a\\kern1em b",
                "\\rule{1pt}{1pt}",
            )
        ) {
            assertTrue("must admit: $allowed", NativeMathGuard.isSafe(allowed))
        }
    }

    @Test
    fun refusesPastTheSizeAndGroupCeilings() {
        val atLimit = "x".repeat(4096)
        assertTrue(NativeMathGuard.isSafe(atLimit))
        assertFalse("4097 characters must be refused", NativeMathGuard.isSafe("x".repeat(4097)))
        val groups = "{".repeat(128) + "}".repeat(128)
        assertTrue(NativeMathGuard.isSafe(groups))
        assertFalse(
            "129 groups must be refused",
            NativeMathGuard.isSafe("{".repeat(129) + "}".repeat(129)),
        )
    }

    @Test
    fun refusesUnbalancedGroups() {
        assertFalse(NativeMathGuard.isSafe("\\frac{a}{b"))
        assertFalse(NativeMathGuard.isSafe("a}"))
        // `\{` is an escaped literal brace: balanced on its own, which is correct — it is not a group.
        assertTrue(NativeMathGuard.isSafe("\\{"))
        assertTrue(NativeMathGuard.isSafe("\\{x\\}"))
        assertTrue(
            "a command name does not escape the brace after it",
            NativeMathGuard.isSafe("\\frac{a}{b}"),
        )
    }

    @Test
    fun anEmptyFormulaIsAdmittedButNeverRenderedAsABlock() {
        // The guard admits ""; the PARSER is what must not emit a block for it, because an empty math
        // block is an invisible stop in the document.
        assertTrue(NativeMathGuard.isSafe(""))
        assertTrue(ResponseParser.parse("\$\$\n\$\$").none { it is ContentBlock.Math })
        assertTrue(ResponseParser.parse("\\[\\]").none { it is ContentBlock.Math })
        // A display cut mid-stream before any character renders nothing, not a blank block.
        assertEquals(
            listOf("text"),
            ResponseParser
                .parse("text\n\$\$")
                .filterIsInstance<ContentBlock.Paragraph>()
                .map { block -> block.content.joinToString("") { it.text } },
        )
        assertTrue(ResponseParser.parse("text\n\$\$").none { it is ContentBlock.Math })
    }

    @Test
    fun ruleRaiseArgumentIsBoundedAndPlainAlgebraicMathPasses() {
        assertTrue("negative bounded raise is ordinary LaTeX", NativeMathGuard.isSafe("\\rule[-2pt]{10pt}{1pt}"))
        assertTrue("positive bounded raise is admitted", NativeMathGuard.isSafe("\\rule[5pt]{10pt}{1pt}"))
        assertFalse("huge positive raise must be refused", NativeMathGuard.isSafe("\\rule[99999pt]{10pt}{1pt}"))
        assertFalse("huge negative raise must be refused", NativeMathGuard.isSafe("\\rule[-99999pt]{10pt}{1pt}"))
        assertFalse("non-numeric raise must be refused", NativeMathGuard.isSafe("\\rule[abc]{10pt}{1pt}"))
        assertTrue("command-free math takes fast path and stays admitted", NativeMathGuard.isSafe("x^2 + y^2 = r^2"))
        assertFalse("command-free unbalanced braces still refused", NativeMathGuard.isSafe("x^{2 + y"))
    }
}
