package com.nirowear.chat

import android.content.Intent
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Composer contract: the words an IME commits must all be there, and the IME's own Send must store
 * them.
 *
 * This is an acceptance test, not the #62 regression. Measured here on 2026-11-17: the SAME taps
 * read `Hi there ` on both `e66d5ea` and the composition-preserving build, so the reported
 * "the composer loses the earlier words" is NOT reproducible on this hardware through real typing —
 * see the #62 comment for the full evidence. What the test does pin down is the user-facing rule the
 * issue is about: two words typed with real space commits stay in the draft, and the IME's Send
 * action stores the whole prompt.
 *
 * Why REAL taps: measured limits that rule out cheaper drivers — `adb shell input text` and injected
 * key events deliver NOTHING while the keyboard window is up (field stays empty, no `onValueChange`
 * at all), and a hand-rolled `InputConnection` from `AndroidComposeView.onCreateInputConnection` is
 * not the served session (its `commitText` acts on an empty buffer: composing "hi" then committing
 * " " left " "), so it drives nothing a user ever sees.
 *
 * The tap grid is the reference watch's Gboard Wear layout (466x466, 320 dpi); the test skips with a
 * printed reason on any other device or IME rather than guessing coordinates.
 */
@RunWith(AndroidJUnit4::class)
class ComposerImeCommitTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    /** On API 34 the IME window can be the active root, so scan every window. */
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

    /** Compose's field reports as an EditText; its `text` is the field's current content. */
    private fun composerText(): String =
        scan().filter { it.isEditable || it.className == "android.widget.EditText" }
            .mapNotNull { it.text?.toString() }
            .firstOrNull()
            .orEmpty()

    private fun shell(command: String): String =
        i.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }

    /** One finger tap at a screen coordinate: the keyboard is not reachable through semantics. */
    private fun tapKey(x: Int, y: Int) {
        shell("input tap $x $y")
        SystemClock.sleep(220)
    }

    private fun keys(word: String) {
        word.forEach { tapKey(KEY[it]?.first ?: error("no key grid entry for '$it'"), KEY[it]!!.second) }
    }

    private fun imeIsTheMeasuredKeyboard(): Boolean {
        val size = i.targetContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val ime =
            runCatching {
                shell("settings get secure default_input_method")
            }.getOrDefault("")
        val ok = size == 466 to 466 && ime.contains("inputmethod.latin")
        println("IME acceptance precondition: display=$size default_ime='$ime' ok=$ok")
        return ok
    }

    private companion object {
        /**
         * Gboard Wear key centres on the 466x466 reference watch, measured from a screenshot of the
         * live keyboard (rows at y=205, 277, 342; space at 420). Not derivable from semantics: the
         * keyboard window exposes no nodes.
         */
        val KEY: Map<Char, Pair<Int, Int>> =
            mapOf(
                'q' to (42 to 205), 'w' to (84 to 205), 'e' to (122 to 205), 'r' to (163 to 205),
                't' to (210 to 205), 'y' to (254 to 205), 'u' to (299 to 205), 'i' to (340 to 205),
                'o' to (383 to 205), 'p' to (425 to 205),
                'a' to (44 to 277), 's' to (91 to 277), 'd' to (136 to 277), 'f' to (181 to 277),
                'g' to (235 to 277), 'h' to (281 to 277), 'j' to (327 to 277), 'k' to (374 to 277),
                'l' to (424 to 277),
                'z' to (83 to 342), 'x' to (129 to 342), 'c' to (177 to 342), 'v' to (234 to 342),
                'b' to (282 to 342), 'n' to (330 to 342), 'm' to (379 to 342),
                ' ' to (233 to 420),
            )

        /** The IME strip's own Send action, top-right of the suggestion strip. */
        val IME_SEND = 409 to 128

        const val DRAFT = "hi there"

        /** What the ViewModel stores: the body is trimmed, the title keeps the committed space. */
        const val ROW = "hi there"
    }

    @Test fun committedWordsSurviveAndSendKeepsBoth() {
        assumeTrue("Not the measured 466x466 Gboard watch", imeIsTheMeasuredKeyboard())
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
                            "IME commit probe",
                            server.url("/v1").toString(),
                            "ime-model",
                            token,
                        ),
                        SecretValue("controlled-ime-not-real"),
                    )
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Both words arrived.\"}}\n\n" +
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
                        awaitNode("composer") {
                            it.isEditable || it.className == "android.widget.EditText"
                        }
                        SystemClock.sleep(1200)

                        keys("hi")
                        tapKey(KEY[' ']!!.first, KEY[' ']!!.second)
                        val afterFirstWord = composerText()
                        keys("there")
                        tapKey(KEY[' ']!!.first, KEY[' ']!!.second)
                        SystemClock.sleep(400)
                        val typed = composerText()
                        println("composer after 'hi there ': '$typed' (after first word '$afterFirstWord')")
                        assertEquals(
                            "Both words must survive the space commits (after first word '$afterFirstWord')",
                            DRAFT,
                            typed.trim().lowercase(),
                        )

                        tapKey(IME_SEND.first, IME_SEND.second)
                        val started = SystemClock.uptimeMillis() + 15000
                        var row: String? = null
                        while (row == null && SystemClock.uptimeMillis() < started) {
                            row =
                                app.database
                                    .chats()
                                    .conversations()
                                    .first()
                                    .filter { it.title.trim().lowercase() == DRAFT }
                                    .firstNotNullOfOrNull { candidate ->
                                        app.database
                                            .chats()
                                            .snapshot(candidate.id)
                                            .firstOrNull { it.rawContent.trim().lowercase() == ROW }
                                            ?.rawContent
                                    }
                            if (row == null) SystemClock.sleep(200)
                        }
                        assertTrue("The IME Send action must store both words, got '$row'", row != null)
                    } finally {
                        i.runOnMainSync { activity.finish() }
                    }
                } finally {
                    // Own rows only: this title is created by this test and by nothing else.
                    app.database
                        .chats()
                        .conversations()
                        .first()
                        .filter { it.title.trim().lowercase() == DRAFT }
                        .forEach { app.database.chats().deleteConversation(it.id) }
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
        }
    }
}
