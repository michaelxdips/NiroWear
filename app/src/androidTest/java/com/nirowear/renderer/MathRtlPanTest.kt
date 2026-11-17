package com.nirowear.renderer

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathRtlPanTest {
    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    @Test fun displayAndFallbackMathExposeScrollActionsInBothDirections() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val source = "\\frac{" + "x+".repeat(100) + "x}{y}"
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                for (complete in listOf(true, false)) {
                    i.runOnMainSync {
                        activity.setContent {
                            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                androidx.wear.compose.material3.MaterialTheme { MathContent(source, complete) }
                            }
                        }
                    }
                    val deadline = SystemClock.uptimeMillis() + 10000
                    var scroller: AccessibilityNodeInfo? = null
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                        val tree = nodes(i.uiAutomation.rootInActiveWindow)
                        val rendered = if (complete) {
                            tree.any { it.contentDescription?.toString() == activity.getString(com.nirowear.R.string.renderer_math_description, source) }
                        } else {
                            tree.any { it.text?.toString() == source }
                        }
                        if (rendered) scroller = tree.firstOrNull { node ->
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                        }
                        if (scroller != null) break
                        SystemClock.sleep(100)
                    }
                    assertNotNull("Missing pan action for $direction complete=$complete", scroller)
                    assertTrue("Could not pan $direction complete=$complete", scroller!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                }
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
