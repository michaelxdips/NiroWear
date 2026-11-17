package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The `Stop` label must sit in the middle of its button.
 *
 * Reported by the owner as "slightly not centered" on the watch. A `Button` from Compose for Wear
 * OS Material 3 pads its content asymmetrically (the leading side reserves room for an optional
 * icon), so a text-only label hugs the start and reads off-centre on a 466 px dial. Measured with
 * node bounds rather than by eye: the label's centre is compared with the button's centre on both
 * axes.
 *
 * The turn is kept open with a response that never arrives (`SocketPolicy.NO_RESPONSE`), so `Stop`
 * exists for as long as the measurement needs.
 */
@RunWith(AndroidJUnit4::class)
class StopButtonCenterTest {
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
        val end = SystemClock.uptimeMillis() + 15000
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

    @Test fun stopLabelIsCenteredInItsButton() {
        val dm = i.targetContext.resources.displayMetrics
        assumeTrue("Needs the 466x466 reference dial", dm.widthPixels == 466 && dm.heightPixels == 466)
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
                            "stop centre probe",
                            server.url("/v1").toString(),
                            "stop-model",
                            token,
                        ),
                        SecretValue("controlled-not-real"),
                    )
                    // No bytes ever arrive: the turn stays generating, so Stop stays on screen.
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    try {
                        click("Menu")
                        click("New chat")
                        click("Keyboard input")
                        val editor =
                            awaitNode("composer") { it.isEditable || it.className == "android.widget.EditText" }
                        editor.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "ping",
                                )
                            },
                        )
                        SystemClock.sleep(500)
                        click("Send")

                        val label = awaitNode("Stop") { it.text?.toString() == "Stop" }
                        var owner: AccessibilityNodeInfo? = label
                        while (owner != null && !owner.isClickable) owner = owner.parent
                        val button = checkNotNull(owner) { "Stop has no clickable owner" }
                        val labelBounds = Rect().also(label::getBoundsInScreen)
                        val buttonBounds = Rect().also(button::getBoundsInScreen)
                        val dx = (labelBounds.exactCenterX() - buttonBounds.exactCenterX())
                        val dy = (labelBounds.exactCenterY() - buttonBounds.exactCenterY())
                        println("Stop label=$labelBounds button=$buttonBounds dx=$dx dy=$dy")
                        assertTrue("Stop label centre is $dx px off horizontally", Math.abs(dx) <= 1.0f)
                        assertTrue("Stop label centre is $dy px off vertically", Math.abs(dy) <= 1.0f)
                        click("Stop")
                    } finally {
                        i.runOnMainSync { activity.finish() }
                    }
                } finally {
                    app.database
                        .chats()
                        .conversations()
                        .first()
                        .filter { it.title.trim() == "ping" }
                        .forEach { app.database.chats().deleteConversation(it.id) }
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
        }
    }
}
