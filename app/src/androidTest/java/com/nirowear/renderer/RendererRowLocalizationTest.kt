package com.nirowear.renderer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.historyKeysFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A table larger than one screen must render EVERY row and must not clamp.
 *
 * The old contract was the opposite: rows were capped at 40 inside a single LazyColumn item and a
 * plural notice said how many were withheld. The cap existed only because a table was one item, so a
 * 1000-row table was ~8000 Text nodes in one measuring pass. Rows are their own items now, which is
 * the upgrade path that cap's own comment named, so nothing is withheld and there is no notice left to
 * localize — this test pins that instead of the deleted string.
 */
class RendererRowLocalizationTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    private fun norm(s: CharSequence?): String =
        s?.toString().orEmpty()
            .replace("\u2068", "")
            .replace("\u2069", "")
            .replace("\u200E", "")
            .replace("\u200F", "")

    @Test fun everyTableRowIsReachableAndNothingIsWithheld() {
        val headers = listOf(listOf(InlineContent("Name")))
        // 120 rows: three times the old cap, so a clamp would be impossible to miss.
        val rows = (0 until 120).map { r -> listOf(listOf(InlineContent("row $r"))) }
        val blocks = listOf(0L to ContentBlock.Table(headers, rows))
        val keys = historyKeysFor(blocks)
        val units = renderUnitsFor(blocks, keys)

        assertEquals("one unit per row, none withheld", 120, units.size)
        assertTrue(
            "no 'rows were cut' notice may be produced any more",
            units.none { it is RenderUnit.Whole && it.block is ContentBlock.Table },
        )

        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
        try {
            i.runOnMainSync {
                activity.setContent {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(units, key = { _, unit -> unit.key }) { _, unit ->
                            RenderUnitView(unit)
                        }
                    }
                }
            }
            // Walk the list to the END and collect what was seen. If a row were dropped, the last
            // row would never appear no matter how far the list scrolls.
            val seen = linkedSetOf<String>()
            val deadline = SystemClock.uptimeMillis() + 20000
            var stuckAtEnd = 0
            while (SystemClock.uptimeMillis() < deadline) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                val tree = nodes(i.uiAutomation.rootInActiveWindow)
                tree.mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }.forEach { seen += norm(it) }
                if (seen.contains("row 119")) break
                val scroller = tree.firstOrNull { it.isScrollable }
                if (scroller == null || !scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    stuckAtEnd++
                    if (stuckAtEnd > 3) break
                }
                SystemClock.sleep(120)
            }
            assertTrue("first row must be reachable: saw=${seen.take(6)}", seen.contains("row 0"))
            assertTrue(
                "row 119 (past the old 40-row cap) must be reachable: saw ${seen.size} labels",
                seen.contains("row 119"),
            )
        } finally {
            i.runOnMainSync { activity.finish() }
            SystemClock.sleep(300)
        }
    }
}
