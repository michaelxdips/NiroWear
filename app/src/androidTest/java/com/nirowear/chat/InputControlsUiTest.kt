package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate C focused acceptance: Keyboard/Voice are stable icon-only controls with
 * 48dp-class transparent targets, Role.Button semantics and "Keyboard input" /
 * "Voice input" content descriptions. No visible text label in idle state, no
 * overlapping targets, and both targets remain inside the circular safe area.
 */
class InputControlsUiTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (n == null) return emptyList()
        n.refresh()
        return listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    }

    private fun find(desc: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return nodes().firstOrNull { it.contentDescription?.toString() == desc }
    }

    private fun await(desc: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            find(desc)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing control: $desc")
    }

    @Test fun iconOnlyInputPairSemanticsLayoutAndClearance() {
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        try {
            // This geometry contract is new-chat idle, not a restored detached reader with Latest.
            fun click(desc: String) {
                var node = await(desc)
                while (!node.isClickable) node = node.parent ?: error("No actionable owner: $desc")
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            click("Menu")
            val end = SystemClock.uptimeMillis() + 10000
            var newChat: AccessibilityNodeInfo? = null
            while (newChat == null && SystemClock.uptimeMillis() < end) {
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                newChat = nodes().firstOrNull { it.text?.toString() == "New chat" }
                if (newChat == null) SystemClock.sleep(100)
            }
            var owner = checkNotNull(newChat) { "Missing New chat" }
            while (!owner.isClickable) owner = owner.parent ?: error("No New chat owner")
            assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Conversation reader")
            i.waitForIdleSync()
            assertNull("New-chat idle has no Latest", find("Jump to latest"))

            fun actionable(desc: String): AccessibilityNodeInfo {
                var node = await(desc)
                while (!node.isClickable) node = node.parent ?: error("No actionable owner: $desc")
                val target = Rect().also(node::getBoundsInScreen)
                assertEquals("Description and click bounds match", target, Rect().also(await(desc)::getBoundsInScreen))
                assertTrue("Button role in target", nodes(node).any { it.className == "android.widget.Button" })
                return node
            }
            val keyboard = actionable("Keyboard input")
            val voice = actionable("Voice input")
            // Role.Button + clickable.
            assertTrue("Keyboard is clickable", keyboard.isClickable)
            assertTrue("Voice is clickable", voice.isClickable)
            // No visible text label in idle presentation.
            assertFalse(
                "No 'Keyboard' text label",
                nodes().any { it.text?.toString() == "Keyboard" },
            )
            assertFalse(
                "No 'Voice' text label",
                nodes().any { it.text?.toString() == "Voice" },
            )
            // 48dp-class touch targets (48dp = 144px at 320dpi/233dp width; allow the class range).
            val kr = Rect().also(keyboard::getBoundsInScreen)
            val vr = Rect().also(voice::getBoundsInScreen)
            // 48dp-class targets; density 2.0 on 466px/233dp means 48dp = 96px.
            assertTrue("Keyboard target ${kr.width()}px >= 88px", kr.width() >= 88 && kr.height() >= 88)
            assertTrue("Voice target ${vr.width()}px >= 88px", vr.width() >= 88 && vr.height() >= 88)
            // Stable centered pair in the bottom safe area, no overlap.
            assertTrue("Keyboard bottom in footer area", kr.top > 300)
            assertTrue("Voice bottom in footer area", vr.top > 300)
            val overlap = android.graphics.Rect().apply { set(kr) }
            val overlaps = overlap.setIntersect(kr, vr)
            assertFalse("Targets must not overlap", overlaps)
            assertTrue("Keyboard left of Voice", kr.left < vr.left)
            // Circular safe-area clearance for a 466x466 round display.
            for (r in listOf(kr, vr)) {
                val corners =
                    listOf(
                        intArrayOf(r.left, r.top),
                        intArrayOf(r.right, r.top),
                        intArrayOf(r.left, r.bottom),
                        intArrayOf(r.right, r.bottom),
                    )
                for ((cx, cy) in corners) {
                    val dx = (cx - 233).toDouble()
                    val dy = (cy - 233).toDouble()
                    assertTrue(
                        "Corner ($cx,$cy) inside circular safe area",
                        dx * dx + dy * dy < 233.0 * 233.0,
                    )
                }
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
