package com.nirowear.renderer

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTouchTargetTest {
    @Test fun adjacentShortLinksProvideDistinctClickableSpans() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
            if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
        try {
            i.runOnMainSync {
                activity.setContent {
                    androidx.wear.compose.material3.MaterialTheme {
                        DocumentBlock(ContentBlock.Paragraph(listOf(
                            InlineContent("Before "), InlineContent("A", link = "https://example.com/a"),
                            InlineContent("B", link = "https://example.com/b"), InlineContent(" after"),
                        )))
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            var node: AccessibilityNodeInfo? = null
            while (SystemClock.uptimeMillis() < deadline) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                node = nodes(i.uiAutomation.rootInActiveWindow).firstOrNull { it.text?.contains("Before") == true }
                if (node != null) break
                SystemClock.sleep(100)
            }
            assertTrue("Text node missing", node != null)
            val text = node!!.text
            val spans = (text as? android.text.Spanned)?.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).orEmpty()
            val rect = Rect().also(node::getBoundsInScreen)
            val info = spans.joinToString { span -> "[${(text as android.text.Spanned).getSpanStart(span)},${text.getSpanEnd(span)})" }
            i.sendStatus(0, android.os.Bundle().apply { putString("linkTargets", "textBounds=$rect density=${activity.resources.displayMetrics.density} spanRanges=$info text=$text") })
            assertTrue("Need two distinct accessible spans: $info", spans.size == 2)
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
