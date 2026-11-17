package com.nirowear.chat

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.*
import org.junit.Test

class StreamingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun find(
        text: String,
        node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) find(text, node.getChild(index))?.let { return it }
        return null
    }

    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI text not found: $text")
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        error("UI text not clickable: $text")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .use { it.readBytes() }
        }
    }

    @Test fun scrollingUpShowsLatestAndStopKeepsPartialAnswer() {
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .putExtra("render_section", "Full fixture")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        try {
            click("RenderLab")
            // Scroll until the button is actually composed, instead of assuming 7 swipes is enough.
            // LazyColumn only composes visible items, and the starting offset depends on what the
            // previous test left behind; a fixed count passed in isolation and failed in-suite.
            var swipes = 0
            while (swipes < 30) {
                instrumentation.uiAutomation.clearCache()
                if (find("Stream fixture") != null) break
                shell("input swipe 233 290 233 110 200")
                SystemClock.sleep(300)
                swipes++
            }
            check(find("Stream fixture") != null) { "Stream fixture never became visible after $swipes swipes" }
            click("Stream fixture")
            await("Stop")
            SystemClock.sleep(3500)
            shell("input swipe 233 120 233 290 500")
            await("Jump to latest")
            SystemClock.sleep(500)
            assertNotNull(find("Jump to latest"))
            click("Jump to latest")
            SystemClock.sleep(300)
            click("Stop")
            // Footer is lazy content; Stop need not expose off-screen text.
            repeat(4) {
                if (find("Stopped · partial answer kept") == null) shell("input swipe 233 280 233 140 250")
            }
            await("Stopped · partial answer kept")
            assertNotNull(find("Keyboard input"))
            assertNotNull(find("Voice input"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
