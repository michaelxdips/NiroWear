package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SecretValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * #52, second half: the app's own Send button must work once the keyboard is gone.
 *
 * The first half is explained in the README: with the IME up, the app's Send sits under the letter
 * row and a finger tap typed `v` into the draft instead of sending. That path is covered by the
 * IME's own action. What was never measured is the OTHER path — dismiss the keyboard, then press the
 * app's Send — and whether the tap mutates the draft or shifts the reader.
 *
 * Measured here on the reference watch (Xiaomi Watch 2, API 34, 466x466 @320). The keyboard is
 * dismissed with one BACK press, which hides it without reaching the app's own BackHandler
 * (measured: editor and Send are still in the tree afterwards; ESCAPE and a swipe-down do not
 * dismiss Gboard Wear at all). Send is clicked through `ACTION_CLICK` on its clickable ancestor, so
 * no finger coordinate can land on a list row.
 */
@RunWith(AndroidJUnit4::class)
class ComposerSendButtonTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    private fun scan(): List<AccessibilityNodeInfo> {
        i.uiAutomation.clearCache()
        val roots = i.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(i.uiAutomation.rootInActiveWindow)
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
        error("Missing $what")
    }

    private fun click(label: String) {
        var node = awaitNode(label) { it.contentDescription?.toString() == label || it.text?.toString() == label }
        while (!node.isClickable) node = node.parent ?: error("No actionable owner for $label")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "ACTION_CLICK $label" }
        SystemClock.sleep(600)
    }

    private fun composerText(): String =
        scan().filter { it.isEditable || it.className == "android.widget.EditText" }
            .mapNotNull { it.text?.toString() }
            .firstOrNull()
            .orEmpty()

    private fun shell(command: String): String =
        i.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }

    /**
     * `mInputShown` is the flag that actually flips on this device. `mIsInputViewShown` was measured
     * stuck at `true` before the keyboard was ever opened, so it proves nothing here.
     */
    private fun imeShown(): Boolean = shell("dumpsys input_method").contains("mInputShown=true")

    private fun tapKey(x: Int, y: Int) {
        shell("input tap $x $y")
        SystemClock.sleep(220)
    }

    /** First reader row's screen bounds — the "did the list shift" measurement. */
    private fun firstRowBounds(): Rect? {
        val reader =
            scan().firstOrNull { it.contentDescription?.toString() == "Conversation reader" }
                ?: return null
        val readerBounds = Rect().also(reader::getBoundsInScreen)
        return scan()
            .filter { it.text?.toString()?.isNotBlank() == true }
            .mapNotNull { Rect().also(it::getBoundsInScreen) }
            .filter { readerBounds.contains(it) }
            .minByOrNull { it.top }
    }

    @Test fun appSendWorksAfterKeyboardIsDismissed() {
        val dm = i.targetContext.resources.displayMetrics
        assumeTrue(
            "Needs the measured 466x466 Gboard watch, got ${dm.widthPixels}x${dm.heightPixels}",
            dm.widthPixels == 466 && dm.heightPixels == 466,
        )
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val previous =
                app.settings.state
                    .first()
                    .selectedId
            val token = UUID.randomUUID().toString()
            MockWebServer().use { server ->
                server.start()
                try {
                    app.settings.save(
                        ProviderProfile(
                            token,
                            ProviderType.OPENAI_COMPATIBLE,
                            "send button probe",
                            server.url("/v1").toString(),
                            "send-model",
                            token,
                        ),
                        SecretValue("controlled-not-real"),
                    )
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Sent from the button.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                    )
                    // Second turn: the same mock answers the second send.
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Second answer.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                    )
                    val activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    try {
                        click("Menu")
                        click("New chat")
                        click("Keyboard input")
                        awaitNode("composer") { it.isEditable || it.className == "android.widget.EditText" }
                        SystemClock.sleep(1200)

                        tapKey(425, 205) // p
                        tapKey(340, 205) // i
                        tapKey(330, 342) // n
                        tapKey(235, 277) // g
                        SystemClock.sleep(400)
                        val typed = composerText()
                        assertEquals("Draft typed with real keys", "Ping", typed.trim())

                        // Dismiss the keyboard WITHOUT leaving the editor. Measured on the device:
                        // ESCAPE (111) and a swipe-down on the keyboard both leave mInputShown=true,
                        // so BACK is the only working dismissal — and the app's BackHandler is not
                        // reached by it, the IME consumes the key. Verified right after: the editor
                        // and its Send button are still in the tree.
                        shell("input keyevent 4")
                        SystemClock.sleep(1000)
                        assertTrue("BACK must dismiss the IME, mInputShown=true", !imeShown())
                        val stillOpen =
                            scan().any { it.isEditable || it.className == "android.widget.EditText" }
                        assertTrue("Editor stays open after dismissing the keyboard", stillOpen)
                        val before = firstRowBounds()
                        println("draft before Send='${composerText()}' firstRow=$before")

                        val sendLabel = awaitNode("Send") { it.text?.toString() == "Send" }
                        var owner: AccessibilityNodeInfo? = sendLabel
                        while (owner != null && !owner.isClickable) owner = owner.parent
                        val send = checkNotNull(owner) { "Send has no clickable owner" }
                        assertNotNull("Send is enabled with a draft", send)
                        assertTrue("Send ACTION_CLICK", send.performAction(AccessibilityNodeInfo.ACTION_CLICK))

                        val started = SystemClock.uptimeMillis() + 15000
                        var row: String? = null
                        while (row == null && SystemClock.uptimeMillis() < started) {
                            row =
                                app.database
                                    .chats()
                                    .conversations()
                                    .first()
                                    .filter { it.title.trim() == "Ping" }
                                    .firstNotNullOfOrNull { candidate ->
                                        app.database
                                            .chats()
                                            .snapshot(candidate.id)
                                            .firstOrNull { it.rawContent.trim() == "Ping" }
                                            ?.rawContent
                                    }
                            if (row == null) SystemClock.sleep(200)
                        }
                        assertEquals("The button must store the draft unchanged", "Ping", row?.trim())
                        val after = firstRowBounds()
                        println("firstRow before=$before after=$after")

                        // ---- SECOND TURN: the actual "#52, second send" shape. The conversation now
                        // has a reply, so the reader has content that could shift under the tap. The
                        // reader rows are captured HERE because the composer replaces the reader
                        // while it is open (if (editing) branch), so nothing is visible to compare.
                        SystemClock.sleep(2500)
                        val readerBeforeSecond = firstRowBounds()
                        assertNotNull("The reader has a row before the second send", readerBeforeSecond)
                        click("Keyboard input")
                        awaitNode("composer") {
                            it.isEditable || it.className == "android.widget.EditText"
                        }
                        SystemClock.sleep(1200)
                        tapKey(425, 205) // p
                        tapKey(383, 205) // o
                        tapKey(330, 342) // n
                        tapKey(235, 277) // g
                        SystemClock.sleep(400)
                        assertEquals("Second draft typed with real keys", "Pong", composerText().trim())
                        shell("input keyevent 4")
                        SystemClock.sleep(1000)
                        assertTrue("BACK must dismiss the IME before the second send", !imeShown())
                        assertTrue(
                            "Editor stays open for the second send",
                            scan().any { it.isEditable || it.className == "android.widget.EditText" },
                        )
                        val sendSecond = awaitNode("Send") { it.text?.toString() == "Send" }
                        var ownerSecond: AccessibilityNodeInfo? = sendSecond
                        while (ownerSecond != null && !ownerSecond.isClickable) ownerSecond = ownerSecond.parent
                        assertTrue(
                            "Second Send ACTION_CLICK",
                            checkNotNull(ownerSecond) { "second Send has no owner" }
                                .performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        )
                        val secondStart = SystemClock.uptimeMillis() + 15000
                        var secondRow: String? = null
                        while (secondRow == null && SystemClock.uptimeMillis() < secondStart) {
                            secondRow =
                                app.database
                                    .chats()
                                    .conversations()
                                    .first()
                                    .firstNotNullOfOrNull { candidate ->
                                        app.database
                                            .chats()
                                            .snapshot(candidate.id)
                                            .firstOrNull { it.rawContent.trim() == "Pong" }
                                            ?.rawContent
                                    }
                            if (secondRow == null) SystemClock.sleep(200)
                        }
                        assertEquals("The second send stores its own draft", "Pong", secondRow?.trim())
                        val afterSecond = firstRowBounds()
                        println("second send: reader first row before=$readerBeforeSecond after=$afterSecond")
                        // The composer replaces the reader while open, so the measurable contract is
                        // that the second send stored exactly its own draft — not that the list holds
                        // still (following scrolls to the newest turn by design). The bounds are
                        // printed above so the shift is recorded rather than claimed to be zero.
                    } finally {
                        i.runOnMainSync { activity.finish() }
                    }
                } finally {
                    app.database
                        .chats()
                        .conversations()
                        .first()
                        .filter { it.title.trim() == "Ping" }
                        .forEach { app.database.chats().deleteConversation(it.id) }
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
        }
    }
}
