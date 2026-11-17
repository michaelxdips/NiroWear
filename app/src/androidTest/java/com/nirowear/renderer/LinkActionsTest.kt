package com.nirowear.renderer

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkActionsTest {
    @Test fun adjacentLinksKeepInlineTextAndExposeSeparateLargeButtons() {
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
            var tree = emptyList<AccessibilityNodeInfo>()
            while (SystemClock.uptimeMillis() < deadline) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                tree = nodes(i.uiAutomation.rootInActiveWindow)
                if (tree.any { it.text?.contains("Before") == true }) break
                SystemClock.sleep(100)
            }
            val details = tree.joinToString(" | ") { "text=${it.text} desc=${it.contentDescription} clickable=${it.isClickable}" }
            assertTrue("Inline text missing: $details", tree.any { it.text?.contains("Before") == true && it.text?.contains("after") == true })
            // The shipped design is a 48 dp button whose TEXT is the URL: `LinkActions` deliberately
            // dropped `contentDescription = "Open link <url>"` and the duplicate onClick label, because
            // they announced every link twice. This assertion pinned the removed description, so it
            // failed against correct code. Compose puts the URL on the button's child Text and the click
            // action on its parent, so walk up to the nearest clickable node.
            val urls =
                tree.mapNotNull { it.text?.toString() }
                    .filter { it.startsWith("https://example.com/") }
                    .distinct()
            assertEquals("Need one labelled action per link: $details", 2, urls.size)
            val density = activity.resources.displayMetrics.density
            val bounds =
                urls.map { url ->
                    var node = tree.firstOrNull { it.text?.toString() == url }
                    var clickable: AccessibilityNodeInfo? = null
                    while (node != null) {
                        if (node.isClickable) {
                            clickable = node
                            break
                        }
                        node = node.parent
                    }
                    assertNotNull("Link '$url' exposes no clickable action: $details", clickable)
                    Rect().also { clickable!!.getBoundsInScreen(it) }
                }
            bounds.forEach { assertTrue("Button $it below 48dp", it.width() >= 48 * density && it.height() >= 48 * density) }
            assertFalse("Button bounds overlap: $bounds", Rect.intersects(bounds[0], bounds[1]))
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
