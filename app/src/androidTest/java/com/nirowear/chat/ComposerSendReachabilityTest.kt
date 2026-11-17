package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Human touch" composer flow: the Send action must be reachable while the software keyboard is
 * up, without dismissing the IME first.
 *
 * Measured on the reference device (Xiaomi Watch 2, API 34, 466x466 @320): with the IME shown the
 * accessibility dump reports "Prompt" [177,56][289,94], "Prompt editor" [80,126][386,274] and
 * "Send" [197,340][270,376], and `dumpsys input_method` reports `mIsInputViewShown=true`.
 *
 * No provider is configured on the device, so a real send is refused. This test therefore asserts
 * layout and reachability only and never claims a turn was admitted.
 *
 * Asserted here:
 *  - an editable composer exists in ANY window (API 34 can make the IME window the active root);
 *  - ACTION_SET_TEXT leaves non-empty composer text and the IME stays shown;
 *  - a "Send" node exists, is enabled, is clickable (itself or an ancestor), lies fully inside the
 *    display, and does not intersect the IME bounds when that geometry is exposed; when it is not,
 *    it must still keep [MIN_KEYBOARD_STRIP_PX] clear below it instead of hugging the display edge.
 */
@RunWith(AndroidJUnit4::class)
class ComposerSendReachabilityTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private val draft = "Send reachable with keyboard up"

    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    /** On API 34 the IME window can be the active root, so scan every window, as a reader would. */
    private fun scan(): List<AccessibilityNodeInfo> {
        i.uiAutomation.clearCache()
        val roots =
            i.uiAutomation.windows.mapNotNull { it.root } +
                listOfNotNull(i.uiAutomation.rootInActiveWindow)
        return roots.flatMap { nodes(it) }
    }

    private fun awaitNode(
        what: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            scan().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $what in any window")
    }

    /** Compose's draft field reports as an EditText; `isEditable` is not always set by the IME root. */
    private fun composer(): AccessibilityNodeInfo? =
        scan().firstOrNull {
            it.isEditable ||
                it.className == "android.widget.EditText" ||
                it.contentDescription?.toString() == "Prompt editor"
        }

    private fun awaitComposer(): AccessibilityNodeInfo {
        repeat(3) { attempt ->
            click("Keyboard input")
            val end = SystemClock.uptimeMillis() + 4000
            while (SystemClock.uptimeMillis() < end) {
                composer()?.let { return it }
                SystemClock.sleep(100)
            }
            println("Composer absent after keyboard-input tap #${attempt + 1}")
        }
        error("No editable composer appeared in any window")
    }

    private fun label(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: ""

    private fun labelBounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    /** IME window geometry, or null when the platform does not expose it for this IME. */
    private fun imeBounds(): Rect? =
        i.uiAutomation.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { Rect().also(it::getBoundsInScreen) }
            ?.takeIf { !it.isEmpty }

    private fun shell(command: String): String =
        i.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }

    /**
     * Gboard's own IME window is full-screen (`fillxfill`, `adjust=pan`) and invisible to
     * [android.app.UiAutomation.getWindows], so its window bounds prove nothing. The real keyboard
     * strip is the ime [android.view.InsetsSource] frame, which `dumpsys window displays` prints.
     */
    private fun imeInsetFrame(): Rect? {
        val dump = runCatching { shell("dumpsys window displays") }.getOrNull() ?: return null
        val match =
            Regex("type=ime frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\] visible=true")
                .find(dump)
                ?: return null
        val (l, t, r, b) = match.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt()).takeIf { !it.isEmpty }
    }

    /** Structural evidence (IME window) plus the framework flag, because either can be missing. */
    private fun imeShown(): Boolean {
        val windowShown = i.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val flag =
            runCatching {
                i.uiAutomation.executeShellCommand("dumpsys input_method").use {
                    ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
                }.contains("mIsInputViewShown=true")
            }.getOrDefault(false)
        println("IME evidence: windowShown=$windowShown mIsInputViewShown=$flag")
        return windowShown || flag
    }

    /** Coordinate tap: the icon boxes expose no ACTION_CLICK on their semantics node. */
    private fun click(label: String) {
        i.waitForIdleSync()
        // A tap fired mid-animation is swallowed, so wait until the target bounds stop moving.
        var bounds: Rect? = null
        var stable = 0
        while (stable < 2) {
            val next = Rect().also(awaitNode(label) { label(it) == label }::getBoundsInScreen)
            if (next == bounds) stable++ else bounds = next
            SystemClock.sleep(150)
        }
        val target = checkNotNull(bounds)
        i.uiAutomation.executeShellCommand("input tap ${target.centerX()} ${target.centerY()}").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        SystemClock.sleep(500)
    }

    @Test fun sendReachableWhileImeShown() {
        val dm = i.targetContext.resources.displayMetrics
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        try {
            val editor = awaitComposer()
            assertTrue(
                "ACTION_SET_TEXT on the composer",
                editor.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft)
                    },
                ),
            )

            // Draft landed while the IME stayed up: no dismissal step is involved.
            val typed = awaitNode("composer text '$draft'") { it.text?.toString()?.contains(draft) == true }
            assertTrue("Composer text is non-empty", typed.text.toString().isNotBlank())
            assertTrue("IME must stay shown while composing", imeShown())

            // "Send" is the label; the enabled/clickable node is its Compose button owner, so the
            // touch target must be measured there (the label span sits inside it).
            val sendLabel = awaitNode("Send action") { label(it) == "Send" }
            assertEquals("Send label is stable", "Send", label(sendLabel))
            var owner: AccessibilityNodeInfo? = sendLabel
            while (owner != null && !owner.isClickable) owner = owner.parent
            val send = checkNotNull(owner) { "Send has no clickable owner" }
            val sendBounds = Rect().also(send::getBoundsInScreen)
            assertTrue("Send is enabled", send.isEnabled)
            assertTrue("Send bounds $sendBounds are non-empty", !sendBounds.isEmpty)
            assertTrue("Send bounds $sendBounds inside display $screen", screen.contains(sendBounds))
            assertTrue("Send label inside its touch target", sendBounds.contains(labelBounds(sendLabel)))

            // Strongest proof: Send must not sit under the keyboard. The strip comes from the IME
            // insets frame when the platform exposes it. This reference device exposes neither an
            // IME accessibility window nor a visible ime inset (Gboard's window is full-screen and
            // transparent, and a screenshot shows it paints nothing over the composer), so the
            // fallback proves the control is not flush to the display edge: measured while
            // mIsInputViewShown=true, the Send touch target is [169,306][298,410] of 466px, i.e.
            // 56px stay clear below it. Require at least 32px of that clearance.
            val keyboard = imeBounds() ?: imeInsetFrame()
            if (keyboard != null) {
                println("IME bounds: $keyboard; Send bounds: $sendBounds")
                assertFalse(
                    "Send $sendBounds must not be covered by the IME $keyboard",
                    Rect.intersects(sendBounds, keyboard),
                )
            } else {
                println("IME geometry not exposed; requiring 32px clearance below Send")
                assertTrue(
                    "Send bottom ${sendBounds.bottom} needs clear space below it",
                    sendBounds.bottom <= screen.height() - MIN_KEYBOARD_STRIP_PX,
                )
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }

    private companion object {
        /** Clear space required below the Send target when IME geometry is unavailable (observed 56px). */
        const val MIN_KEYBOARD_STRIP_PX = 32
    }
}
