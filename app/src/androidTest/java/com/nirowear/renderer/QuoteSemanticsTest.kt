package com.nirowear.renderer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteSemanticsTest {
    @Test fun quoteIsAnnouncedWithoutBeingAHeadingAndRetainsItsLink() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            i.runOnMainSync {
                activity.setContent {
                    androidx.wear.compose.material3.MaterialTheme {
                        DocumentBlock(ContentBlock.Quote(listOf(InlineContent("Quoted "), InlineContent("link", link = "https://example.com"))))
                    }
                }
            }
            SystemClock.sleep(800)
            fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
                if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
            // `rootInActiveWindow` returns an EMPTY tree on API 33+ unless the accessibility cache is
            // flushed first (measured: nodes=0 without, 13 with). Every read below flushes.
            var tree = emptyList<AccessibilityNodeInfo>()
            for (attempt in 1..20) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                tree = nodes(i.uiAutomation.rootInActiveWindow)
                if (tree.isNotEmpty()) break
                SystemClock.sleep(200)
            }
            val details = tree.joinToString(" | ") { node -> "text=${node.text}, desc=${node.contentDescription}, clickable=${node.isClickable}, actions=${node.actionList}" }
            val quoteLabel = activity.getString(com.nirowear.R.string.renderer_quote)
            // The label sits on the MERGED container and its text on a child node, which is what
            // `mergeDescendants = true` does. Asserting both on one node was impossible; this test had
            // never run.
            val labelled = tree.filter { it.contentDescription?.toString() == quoteLabel }
            assertTrue("Quote must expose a localized label: $details", labelled.isNotEmpty())
            assertTrue("Quote text must stay readable: $details", tree.any { it.text?.contains("Quoted") == true })
            val quote = labelled.first()
            assertFalse("Quote is not a heading", quote.isHeading)
            // The link stays reachable: inline link annotation and/or the 48 dp link action group.
            val linkGroup = activity.getString(com.nirowear.R.string.renderer_links)
            val hasLinksAffordance = tree.any { it.contentDescription?.toString() == linkGroup }
            val hasInlineLink = tree.any { it.isClickable && (it.text?.contains("link") == true || it.contentDescription?.contains("link") == true) }
            val spans =
                tree.mapNotNull { it.text as? android.text.Spanned }.flatMap { spanned ->
                    spanned.getSpans(0, spanned.length, android.text.style.ClickableSpan::class.java).toList()
                }
            assertTrue("Link must remain interactive: $details", hasLinksAffordance || hasInlineLink || spans.isNotEmpty())
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
