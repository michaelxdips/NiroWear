package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.*
import org.junit.Test

class RoundNavigationTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun find(
        label: String,
        n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow,
    ): AccessibilityNodeInfo? {
        n ?: return null
        if (n.text?.toString()?.contains(label) == true || n.contentDescription?.toString() == label) return n
        for (index in 0 until n.childCount) find(label, n.getChild(index))?.let { return it }
        return null
    }

    private fun await(label: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            find(label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $label")
    }

    private fun click(label: String) {
        var n: AccessibilityNodeInfo? = await(label)
        while (n != null) {
            if (n.isClickable) {
                assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            n = n.parent
        }
        error("Not clickable $label")
    }

    @Test fun openingMenuAndNewChatResetReaderScroll() {
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .putExtra("render_section", "Full fixture")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )

        fun swipe() =
            i.uiAutomation.executeShellCommand("input swipe 233 280 233 140 350").use {
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(it)
                    .readBytes()
            }
        try {
            repeat(4) { swipe() }
            click("Menu")
            val provider = await("Providers / model")
            val bounds = Rect().also(provider::getBoundsInScreen)
            assertTrue("First menu action visible", bounds.top >= 84 && bounds.bottom <= 306)
            repeat(3) { swipe() }
            click("Menu") // Close scrolled menu.
            click("Menu") // Opening always returns first action.
            await("Providers / model")
            swipe()
            click("New chat")
            val empty = await("Ask anything")
            assertTrue("Empty first line not clipped", Rect().also(empty::getBoundsInScreen).top >= 84)
            assertNotNull(await("Keyboard input"))
            assertNotNull(await("Voice input"))
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
