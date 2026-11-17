package com.nirowear.renderer

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTapAndPanTest {
    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    @Test fun adjacentInlineLinksExposeSeparated48DpTargets() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
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
            var tree = emptyList<AccessibilityNodeInfo>()
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                tree = nodes(i.uiAutomation.rootInActiveWindow)
                if (tree.any { it.text?.contains("Before") == true }) break
                SystemClock.sleep(100)
            }
            // The shipped `LinkActions` is a 48 dp button whose TEXT is the URL; the "Open link <url>"
            // description and its duplicate action were removed on purpose (they announced every link
            // twice). This filter pinned that removed action, so it matched nothing against correct code.
            val urls =
                tree.mapNotNull { it.text?.toString() }
                    .filter { it.startsWith("https://example.com/") }
                    .distinct()
            val details = tree.joinToString(" | ") { node -> "text=${node.text}, desc=${node.contentDescription}, clickable=${node.isClickable}, actions=${node.actionList}" }
            assertEquals("Each link should expose a distinct 48dp target: $details", 2, urls.size)
            val minPixels = 48 * activity.resources.displayMetrics.density
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
                    assertNotNull("Link '$url' exposes no clickable target: $details", clickable)
                    Rect().also { clickable!!.getBoundsInScreen(it) }
                }
            bounds.forEach { assertTrue("Link bounds $it below 48dp ($minPixels px)", it.width() >= minPixels && it.height() >= minPixels) }
            assertFalse("Adjacent target bounds overlap: $bounds", Rect.intersects(bounds[0], bounds[1]))
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }

    @Test fun wideCodeHasAccessibleHorizontalScrollInLtrAndRtl() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                i.runOnMainSync {
                    activity.setContent {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            androidx.wear.compose.material3.MaterialTheme {
                                DocumentBlock(ContentBlock.Code("", "START_" + "x".repeat(250) + "_END"))
                            }
                        }
                    }
                }
                val deadline = SystemClock.uptimeMillis() + 10000
                var scroller: AccessibilityNodeInfo? = null
                while (SystemClock.uptimeMillis() < deadline) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                    scroller = nodes(i.uiAutomation.rootInActiveWindow).firstOrNull { node ->
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                    }
                    if (scroller != null) break
                    SystemClock.sleep(100)
                }
                assertNotNull("Missing horizontal scroll action for $direction", scroller)
                assertTrue("Scroll forward failed for $direction", scroller!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
