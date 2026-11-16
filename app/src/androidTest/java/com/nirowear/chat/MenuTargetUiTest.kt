package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.*
import org.junit.Test

class MenuTargetUiTest {
    @Test fun menuRoleBoundsAndExpandedStripTouch() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )

        fun nodes(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
            if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

        fun find(desc: String): AccessibilityNodeInfo {
            val end = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < end) {
                i.uiAutomation.clearCache()
                nodes().firstOrNull { it.contentDescription?.toString() == desc }?.let { return it }
                SystemClock.sleep(100)
            }
            error("Missing $desc")
        }
        try {
            val menu = find("Menu")
            val r = Rect().also(menu::getBoundsInScreen)
            assertTrue(r.height() >= (48 * i.targetContext.resources.displayMetrics.density).toInt())
            assertTrue(
                nodes().any { it.className == "android.widget.Button" && Rect().also(it::getBoundsInScreen) == r },
            )
            for (x in listOf(r.left + 12, r.right - 12)) {
                i.uiAutomation.executeShellCommand("input tap $x ${r.bottom - 4}").close()
                find("Navigation menu")
                i.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                find("Conversation reader")
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
