package com.nirowear.renderer

import android.content.Intent
import android.content.res.Configuration
import android.icu.text.Bidi
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RendererReviewRegressionTest {
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    @Test fun rtlParagraphKeepsItsStrongDirectionAcrossStyledSpans() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            i.runOnMainSync {
                activity.setContent {
                    androidx.wear.compose.material3.MaterialTheme {
                        DocumentBlock(ContentBlock.Paragraph(listOf(InlineContent("12 "), InlineContent("אב"), InlineContent(" "), InlineContent("גד", bold = true))))
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            var rendered: String? = null
            while (SystemClock.uptimeMillis() < deadline) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                rendered = nodes(i.uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }.firstOrNull { it.contains("אב") && it.contains("גד") }
                if (rendered != null) break
                SystemClock.sleep(100)
            }
            assertNotNull("Paragraph absent", rendered)
            val marker = rendered!!.first()
            assertEquals("Paragraph direction marker missing", '\u200F', marker)
            val expected = Bidi("12 אב גד", Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).paraLevel
            val actual = Bidi(rendered!!, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).paraLevel
            assertEquals("Styled spans changed paragraph direction", expected, actual)
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }

    /**
     * The ROW plural is deliberately gone with the 40-row cap: rows are their own lazy items now, so
     * nothing is withheld and there is no notice to word. The CELL plural stays — cells are still
     * dropped, but by the Markdown parser when a row carries more cells than the header, not by the
     * renderer.
     */
    @Test fun droppedCellsExposeGrammaticalSingularForms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locales = listOf(Locale.ENGLISH, Locale.forLanguageTag("id"), Locale.SIMPLIFIED_CHINESE)
        for (locale in locales) {
            val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(locale)
            }).resources
            val cells = resources.getQuantityString(com.nirowear.R.plurals.renderer_hidden_cells, 1, 1)
            if (locale == Locale.SIMPLIFIED_CHINESE) {
                // No values-zh: the English "other" form is the fallback, and it carries the number.
                assertEquals("… 1 more cells were dropped: those rows have more cells than the header.", cells)
            } else if (locale == Locale.ENGLISH) {
                assertEquals("… one more cell was dropped: this row has more cells than the header.", cells)
            } else {
                assertEquals("… 1 sel dibuang: baris tersebut punya sel lebih banyak daripada judul tabel.", cells)
            }
        }
    }
}
