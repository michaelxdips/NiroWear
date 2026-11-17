package com.nirowear.renderer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WideInlineMathTest {
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).flatMap { root.getChild(it)?.let(::nodes).orEmpty() }

    @Test fun wideFormulaPromotesAndPansWithoutShrinkingProse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = "\\frac{a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8}{b_1+b_2+b_3+b_4+b_5+b_6+b_7+b_8}"
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 60.dp)) {
                        DocumentBlock(
                            ContentBlock.Paragraph(
                                listOf(
                                    InlineContent("Before formula."),
                                    InlineContent(source, math = true),
                                    InlineContent("After formula."),
                                ),
                            ),
                        )
                    }
                }
            }
            val end = SystemClock.uptimeMillis() + 10000
            var tree = emptyList<AccessibilityNodeInfo>()
            while (SystemClock.uptimeMillis() < end) {
                tree =
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.let(::nodes)
                        .orEmpty()
                if (tree.any { it.contentDescription?.toString() == activity.getString(com.nirowear.R.string.renderer_math_description, source) }) break
                SystemClock.sleep(100)
            }
            val math = tree.single { it.contentDescription?.toString() == activity.getString(com.nirowear.R.string.renderer_math_description, source) }
            fun visibleText(node: AccessibilityNodeInfo) = node.text?.toString()
                ?.replace("\u2068", "")?.replace("\u2069", "")
                ?.replace("\u200E", "")?.replace("\u200F", "")
            val before = tree.single { visibleText(it) == "Before formula." }
            val after = tree.single { visibleText(it) == "After formula." }

            fun bounds(node: AccessibilityNodeInfo) = android.graphics.Rect().also(node::getBoundsInScreen)
            assertTrue(bounds(before).bottom <= bounds(math).top)
            assertTrue(bounds(math).bottom <= bounds(after).top)
            var pan: AccessibilityNodeInfo? = math.parent
            while (pan != null && !pan.isScrollable) pan = pan.parent
            assertNotNull("Wide math exposes accessible horizontal pan", pan)
            assertTrue(pan!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            assertTrue("Prose remains readable", bounds(before).height() >= 28)
            val shot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(shot)
            File(instrumentation.targetContext.getExternalFilesDir(null), "wide-inline-math.png").outputStream().use {
                check(shot!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            shot!!.recycle()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
