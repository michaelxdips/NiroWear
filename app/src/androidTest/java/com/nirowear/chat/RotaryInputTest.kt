package com.nirowear.chat

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RotaryInputTest {
    @Test fun rotarySourceMovesReaderAndLeavesTouchNavigationAvailable() =
        runBlocking {
            val i = InstrumentationRegistry.getInstrumentation()
            val dao = (i.targetContext.applicationContext as NiroApplication).database.chats()
            val id = "rotary-${UUID.randomUUID()}"
            dao.insertConversation(Conversation(id, "Rotary fixture", System.currentTimeMillis() + 100000))
            dao.insertMessage(
                StoredMessage(
                    conversationId = id,
                    role = "ASSISTANT",
                    rawContent = (1..80).joinToString("\n\n") { "Rotary paragraph $it." },
                ),
            )
            val activity =
                i.startActivitySync(
                    Intent(i.targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )

            fun reader(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? =
                if (n == null) {
                    null
                } else if (n.contentDescription?.toString() == "Conversation reader") {
                    n
                } else {
                    (0 until n.childCount).firstNotNullOfOrNull { reader(n.getChild(it)) }
                }

            fun state(): String {
                i.uiAutomation.clearCache()
                return reader()?.stateDescription?.toString().orEmpty()
            }

            fun find(label: String): AccessibilityNodeInfo? {
                i.uiAutomation.clearCache()

                fun scan(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
                    if (n == null) {
                        null
                    } else if (n.contentDescription?.toString() == label || n.text?.toString() == label) {
                        n
                    } else {
                        (0 until n.childCount).firstNotNullOfOrNull { scan(n.getChild(it)) }
                    }
                return scan(i.uiAutomation.rootInActiveWindow)
            }

            fun tap(label: String) {
                val end = SystemClock.uptimeMillis() + 10000
                while (SystemClock.uptimeMillis() < end) {
                    var node = find(label)
                    while (node != null && !node.isClickable && node.parent != null) node = node.parent
                    if (node != null) {
                        val r = android.graphics.Rect().also(node::getBoundsInScreen)
                        i.uiAutomation.executeShellCommand("input tap ${r.centerX()} ${r.centerY()}").close()
                        SystemClock.sleep(700)
                        return
                    }
                    i.uiAutomation.executeShellCommand("input swipe 233 250 233 170 300").close()
                    SystemClock.sleep(300)
                }
                error("Missing $label")
            }
            try {
                // Open the seeded conversation through History so the reader owns real content.
                tap("Menu")
                tap("History")
                tap("Rotary fixture")
                val end = SystemClock.uptimeMillis() + 15000
                while (SystemClock.uptimeMillis() < end && state().isEmpty()) SystemClock.sleep(100)
                val before = state()
                assertTrue("Reader metrics expected: '$before'", before.contains("index="))
                val handled = mutableListOf<Boolean>()
                repeat(8) {
                    i.runOnMainSync {
                        val pointer =
                            MotionEvent.PointerProperties().apply {
                                this.id = 0
                                toolType =
                                    MotionEvent.TOOL_TYPE_UNKNOWN
                            }
                        // Scroll forward: the reader may already be at the top after another test.
                        val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_SCROLL, 12f) }
                        val time = SystemClock.uptimeMillis()
                        val event =
                            MotionEvent.obtain(
                                time,
                                time,
                                MotionEvent.ACTION_SCROLL,
                                1,
                                arrayOf(pointer),
                                arrayOf(coords),
                                0,
                                0,
                                1f,
                                1f,
                                0,
                                0,
                                InputDevice.SOURCE_ROTARY_ENCODER,
                                0,
                            )
                        try {
                            handled += activity.dispatchGenericMotionEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                    SystemClock.sleep(100)
                }
                assertTrue("Rotary encoder events must be consumed by the reader", handled.any { it })
                assertNotEquals(
                    "Rotary must move the reading position; before=$before after=${state()}",
                    before,
                    state(),
                )
                i.uiAutomation.executeShellCommand("input swipe 233 300 233 160 300").close()
                SystemClock.sleep(400)
                assertNotNull(reader())
            } finally {
                i.runOnMainSync { activity.finish() }
                dao.deleteConversation(id)
            }
        }
}
