package com.nirowear.renderer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSemanticsTest {
    @Test fun headerOnlyTableKeepsHeadingContent() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .putExtra("render_section", "Tables")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        try {
            i.runOnMainSync {
                (activity as MainActivity).setContent {
                    androidx.wear.compose.material3.MaterialTheme {
                        DocumentBlock(ContentBlock.Table(listOf(listOf(InlineContent("Visible header"))), emptyList()))
                    }
                }
            }
            SystemClock.sleep(800)

            fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
                if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
            var header: AccessibilityNodeInfo? = null
            for (attempt in 1..20) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                header =
                    nodes(
                        i.uiAutomation.rootInActiveWindow,
                    ).firstOrNull {
                        it.text?.toString()?.replace("\u2068", "")?.replace("\u2069", "")
                            ?.replace("\u200E", "")?.replace("\u200F", "") == "Visible header"
                    }
                if (header != null && header.isHeading) break
                SystemClock.sleep(200)
            }
            assertTrue("Header-only table visible with heading semantics", header != null && header.isHeading)
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
