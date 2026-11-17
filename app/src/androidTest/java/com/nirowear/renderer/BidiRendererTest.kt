package com.nirowear.renderer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class BidiRendererTest {
    @Test fun mixedDirectionSpansUseIsolatesWithoutArtificialParagraphBreaks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val spans = listOf(
            InlineContent("Hello "),
            InlineContent("مرحبا! "),
            InlineContent("קישור", link = "https://example.com/path"),
            InlineContent(" 😀 "),
            InlineContent("x=1", code = true),
            InlineContent(" "),
            InlineContent("\\notacommand", math = true),
            InlineContent(" end."),
        )
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    androidx.wear.compose.material3.MaterialTheme {
                        DocumentBlock(ContentBlock.Paragraph(spans))
                    }
                }
            }
            fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
                if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
            var actual: String? = null
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                actual = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                    .mapNotNull { it.text?.toString() }
                    .firstOrNull { it.contains("Hello ") && it.contains("end.") }
                if (actual != null) break
                SystemClock.sleep(100)
            }
            assertNotNull("Rendered paragraph not found in accessibility tree", actual)
            val text = actual!!
            assertFalse("Renderer inserted U+2028", text.contains('\u2028'))
            assertFalse("Renderer inserted U+2029", text.contains('\u2029'))
            val expected = "\u200E" + spans.joinToString("") { "\u2068" + it.text + "\u2069" }
            val rendered = text.replace("\u200E", "").replace("\u200F", "")
            assertEquals("Span text, isolates, and original order", expected, text)
            assertEquals("Marker must not change span order", expected.replace("\u200E", "").replace("\u200F", ""), rendered)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
