package com.nirowear.renderer

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

class ListMarkerTest {
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    @Test fun twoAndThreeDigitMarkersRemainVisibleAtLargeFontScale() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            for (marker in listOf("12.", "123.")) {
                i.runOnMainSync {
                    activity.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(activity.resources.displayMetrics.density, 2f)) {
                            androidx.wear.compose.material3.MaterialTheme {
                                Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 60.dp)) {
                                    DocumentBlock(ContentBlock.ListEntry(marker, 0, listOf(InlineContent("item"))))
                                }
                            }
                        }
                    }
                }
                val deadline = SystemClock.uptimeMillis() + 5000
                var node: AccessibilityNodeInfo? = null
                while (SystemClock.uptimeMillis() < deadline) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                    node = nodes(i.uiAutomation.rootInActiveWindow).firstOrNull {
                        it.text?.toString()?.replace("\u2068", "")?.replace("\u2069", "")
                            ?.replace("\u200E", "")?.replace("\u200F", "")
                            ?.contains(marker) == true
                    }
                    if (node != null) break
                    SystemClock.sleep(100)
                }
                assertTrue("Marker $marker missing", node != null)
                val bounds = Rect().also(node!!::getBoundsInScreen)
                assertTrue("Marker $marker clipped: $bounds", bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.right <= 466)
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
