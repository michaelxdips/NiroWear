package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import com.nirowear.provider.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Touch navigation/restore; text injection is not IME acceptance. Owns only UUID rows/profile. */
class ReaderNavigationTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    /**
     * Forward extent below which a drag is not expected to move the list at all.
     *
     * Measured on the AVD: remaining = 18 px stayed at `index=3;offset=0` after a 90 px drag, while
     * remaining = 62 px moved the same list 82 px. Compose spends the drag's touch slop (8 dp = 16 px
     * at this density) before scrolling and snaps back when the range is shorter than that, so a
     * residue smaller than the slop plus a margin is not a swipe the list can honour.
     */
    private val MIN_SCROLLABLE_PX = 40

    private fun nodes(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (n == null) return emptyList()
        n.refresh()
        return listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    }

    /**
     * Text with the bidi controls the renderer deliberately wraps every inline run in.
     *
     * `DocumentBlock` isolates each run with FSI/PDI (and prepends a direction mark), so the node text
     * for a plain sentence is "⁨Measure answer 1⁩". Exact string equality therefore never matched —
     * measured on the AVD: the dump showed `‎⁨Measure answer 1⁩` while the comparison used the bare
     * string, which is why six reader tests reported "Missing …" for text that was on screen.
     */
    private fun norm(text: CharSequence?): String =
        text
            ?.toString()
            .orEmpty()
            .replace("\u2068", "")
            .replace("\u2069", "")
            .replace("\u200E", "")
            .replace("\u200F", "")

    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return nodes().firstOrNull { norm(it.text) == norm(label) || norm(it.contentDescription) == norm(label) }
    }

    private fun await(label: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            find(label)?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("failure")
        android.util.Log.e("ReaderNavigationTest", nodes().joinToString(" | ") { it.text?.toString().orEmpty() })
        error("Missing $label")
    }

    /**
     * Finds a node whose text still STARTS WITH [prefix].
     *
     * The anchor captured mid-stream is a paragraph that keeps growing, so its text is never equal
     * to the captured snapshot again. Comparing positions of a still-streaming block therefore has
     * to match by prefix; exact equality waits 10 s and then fails with a message that quotes the
     * whole half-written paragraph.
     */
    private fun awaitPrefix(prefix: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
            nodes().firstOrNull { norm(it.text).startsWith(norm(prefix)) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing text starting with: ${prefix.take(60)}")
    }

    private fun shell(command: String) =
        i.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }

    private fun swipe(up: Boolean = true) {
        shell("input swipe 233 ${if (up) 220 else 135} 233 ${if (up) 135 else 220} 350")
        SystemClock.sleep(300)
    }

    /**
     * Accessibility click on the label's clickable ancestor. A coordinate tap cannot reach a control
     * that the full-screen IME covers, but the semantics node is still in the app tree, so
     * ACTION_CLICK is the only host-side path that stays valid with the keyboard up.
     */
    private fun clickNode(label: String): Boolean {
        repeat(20) {
            var n = find(label)
            while (n != null) {
                if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                n = n.parent
            }
            SystemClock.sleep(150)
        }
        return false
    }

    private fun tap(
        label: String,
        scroll: Boolean = false,
    ) {
        i.waitForIdleSync()
        SystemClock.sleep(700)
        var scrollAttempt = 0
        repeat(if (scroll) 30 else 10) {
            var n = find(label)
            while (n != null && !n.isClickable && n.parent != null) n = n.parent
            val r = n?.let { Rect().also(it::getBoundsInScreen) }
            val reader =
                if (label ==
                    "Retry response"
                ) {
                    find("Conversation reader")?.let { Rect().also(it::getBoundsInScreen) }
                } else {
                    null
                }
            // Tap the row's CENTRE inside the dial, not "the row's top edge is below the header".
            // A row taller than the visible band has a container top above 60 while its centre is
            // perfectly touchable — that rule rejected "Reader history 5" (container 30..134) on
            // screen and inside the dial. Clipped-at-the-top rows are still handled: the search
            // above scrolls the other way when centreY falls outside the band.
            val band = r != null && r.centerY() >= 70 && r.centerY() <= 400
            if (r != null && (band || label == "Menu") && r.bottom <= 410 &&
                (label != "Retry response" || reader?.contains(r) == true)
            ) {
                if (label.startsWith("Reader")) screenshot("before-tap-${label.replace(' ', '-')}")
                android.util.Log.e("ReaderNavigationTest", "Tap $label at $r")
                shell("input tap ${r.centerX()} ${r.centerY()}")
                SystemClock.sleep(400)
                if (label == "Menu" && find("Navigation menu") == null) return@repeat
                return
            }
            if (scroll) {
                // A missing row gives no direction hint. Sweep forward first (the reader's usual
                // case: the answer continues below), then reverse. Sweeping ONLY forward could never
                // find a row that sits above the current position — which is exactly what the
                // History list produces now that it keeps its offset (measured: "No safe touch
                // target: Reader history 5"). A row clipped under the header is reachable backwards
                // only.
                when {
                    r != null && r.centerY() < 70 -> swipe(up = false)
                    r != null -> swipe(up = true)
                    // Missing node: rewind to the TOP first, then sweep forward. The History list
                    // keeps its offset now, so it can start anywhere below the rows this test wants;
                    // an unknown offset has no direction, and "top then down" is the only order that
                    // is bounded by the list length in both phases.
                    scrollAttempt < 15 -> swipe(up = false)
                    else -> swipe(up = true)
                }
                scrollAttempt++
            } else {
                SystemClock.sleep(300)
            }
        }
        screenshot("failure-tap-${label.replace(' ', '-')}")
        android.util.Log.e(
            "ReaderNavigationTest",
            nodes().joinToString(" | ") {
                "${it.text} desc=${it.contentDescription} ${Rect().also(it::getBoundsInScreen)}"
            },
        )
        error("No safe touch target: $label")
    }

    private fun history() {
        tap("Menu")
        await("History")
        tap("History", true)
        // History title and Back scroll offscreen when its saved list offset is restored.
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (find("Conversation reader") == null && find("Navigation menu") == null &&
                nodes().any { !it.text.isNullOrEmpty() }
            ) {
                break
            }
            SystemClock.sleep(100)
        }
        assertNull("History replaces reader", find("Conversation reader"))
        assertNull("History closes navigation menu", find("Navigation menu"))
        assertTrue("History content visible", nodes().any { !it.text.isNullOrEmpty() })
    }

    private fun screenshot(name: String) {
        val dir = java.io.File(i.targetContext.getExternalFilesDir(null), "reader-sprint").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { image ->
            java.io.File(dir, "$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun measuredAnswersKeepBeginningAndReachFinalLineAndRetry() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val ids = mutableListOf<String>()
            var activity: android.app.Activity? = null
            try {
                activity =
                    i.startActivitySync(
                        Intent(i.targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                for (lines in listOf(1, 3, 2, 4, 5, 6, 7, 0, -1)) {
                    val id = "measured-reader-${UUID.randomUUID()}"
                    ids += id
                    val title = "Measured $lines"
                    val answer =
                        when (lines) {
                            -1 -> "```\nLine 1 readable.\n```"
                            0 -> "Line 1 readable.\n\nLine 2 readable."
                            else -> (1..lines).joinToString("  \n") { "Line $it readable." }
                        }
                    dao.insertConversation(Conversation(id, title, System.currentTimeMillis() + lines))
                    dao.insertMessage(
                        StoredMessage(conversationId = id, role = "USER", rawContent = "Measure answer $lines"),
                    )
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = id,
                            role = "ASSISTANT",
                            rawContent = answer,
                            status = "SUCCESS",
                            providerLabel = "Measured fixture",
                            modelId = "local",
                        ),
                    )
                    history()
                    tap(title, true)
                    await("Measure answer $lines")
                    tap("Jump to latest")
                    val end = SystemClock.uptimeMillis() + 10000
                    var metrics = ""
                    while (SystemClock.uptimeMillis() < end) {
                        metrics = await("Conversation reader").stateDescription?.toString().orEmpty()
                        if (!metrics.contains("unknown") && metrics.substringAfter("answer=").isNotEmpty()) break
                        SystemClock.sleep(100)
                    }
                    val viewport = metrics.substringAfter("viewport=").substringBefore(';').toInt()
                    val heights =
                        metrics
                            .substringAfter("answer=")
                            .substringBefore(';')
                            .split(',')
                            .map(String::toInt)
                    val height = heights.sum() + 24 * (heights.size - 1)
                    // Fixed fixture sized against pre-H1 218px viewport; H1 intentionally costs 12px.
                    if (lines ==
                        -1
                    ) {
                        assertTrue(
                            "Historical quarter fixture must measure 22–30% of 218px: $height/$viewport",
                            height.toFloat() / 218 in 0.22f..0.30f,
                        )
                    }
                    if (lines == 0) {
                        assertTrue(
                            "Half-height case must measure 45–55%: $height/$viewport",
                            height.toFloat() / viewport in 0.45f..0.55f,
                        )
                    }
                    java.io
                        .File(
                            i.targetContext.getExternalFilesDir(null),
                            "reader-measurements-$lines.txt",
                        ).writeText(
                            "case=$lines height=$height viewport=$viewport ratio=${height.toFloat() / viewport}\n",
                        )
                    val reader = Rect().also(await("Conversation reader")::getBoundsInScreen)
                    val prose = nodes().first { norm(it.text).startsWith("Line 1 readable.") == true }
                    val bounds = Rect().also(prose::getBoundsInScreen)
                    android.util.Log.i(
                        "ReaderMeasurement",
                        "lines=$lines height=$height viewport=$viewport ratio=${height.toFloat() / viewport} prose=$bounds reader=$reader",
                    )
                    if (height <= viewport - 48) {
                        assertTrue(
                            "Fitting answer begins inside viewport: $metrics $bounds $reader",
                            bounds.top >= reader.top,
                        )
                        assertTrue(
                            "Fitting answer fully visible: $metrics $bounds $reader",
                            bounds.bottom <= reader.bottom,
                        )
                    }
                    screenshot("measured-$lines-latest")

                    fun retryBounds(): Rect? {
                        // Scrolling can leave cached parent bounds behind current pixels/list offset.
                        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                        var node = find("Retry response") ?: return null
                        while (!node.isClickable) node = node.parent ?: return null
                        return Rect().also(node::getBoundsInScreen)
                    }
                    for (step in 0..8) {
                        val action = retryBounds()
                        val readerNode = await("Conversation reader")
                        val viewportBounds = Rect().also(readerNode::getBoundsInScreen)
                        android.util.Log.i(
                            "ReaderMeasurement",
                            "Retry case=$lines step=$step action=$action reader=$viewportBounds ${readerNode.stateDescription}",
                        )
                        screenshot("measured-$lines-retry-step-$step")
                        if (action != null && action.top >= viewportBounds.top &&
                            action.bottom <= viewportBounds.bottom
                        ) {
                            break
                        }
                        val beforeSwipe = readerNode.stateDescription.toString()
                        val beforePosition = beforeSwipe.substringAfter(";index=").substringBefore(";forward=")
                        // A 1-2 px residue (the footer's own height is part of the list padding, so the
                        // extent it adds is smaller than the drag's touch slop) still reports
                        // `forward=true`, and NO drag can consume it — measured: index=3;offset=0 with
                        // remaining=2 before and after. Only demand movement the list can deliver.
                        val remaining = beforeSwipe.substringAfter(";remaining=").substringBefore(";").toIntOrNull() ?: 0
                        if (remaining < MIN_SCROLLABLE_PX) break
                        // Drag inside the top half of the reader, never the bottom edge: the Retry /
                        // Latest actions are drawn in a BottomCenter overlay that covers the list's
                        // footer padding, so a drag begun there is consumed by the buttons and the
                        // list never moves (measured: remaining=62 px, index=3;offset=0 unchanged).
                        val fromY = viewportBounds.top + 110
                        val toY = viewportBounds.top + 20
                        shell("input swipe 233 $fromY 233 $toY 350")
                        SystemClock.sleep(300)
                        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                        val afterSwipe = await("Conversation reader").stateDescription.toString()
                        val afterPosition = afterSwipe.substringAfter(";index=").substringBefore(";forward=")
                        android.util.Log.i(
                            "ReaderMeasurement",
                            "Swipe case=$lines step=$step before=$beforeSwipe after=$afterSwipe",
                        )
                        assertNotEquals(
                            "Every valid reader swipe with forward extent moves list",
                            beforePosition,
                            afterPosition,
                        )
                        // A reader that is already within a screen of its END cannot stay detached:
                        // the scroll-end effect re-decides `following` by position, and at the bottom
                        // that answer is "yes, follow". Measured here: remaining=62 px inside a 206 px
                        // viewport, so the swipe moved the list and the app correctly re-attached.
                        // Detachment is only meaningful while more than a viewport is left below.
                        if (remaining > viewportBounds.height()) {
                            assertTrue(
                                "Completed manual swipe remains detached: $afterSwipe",
                                afterSwipe.contains(";following=false"),
                            )
                        }
                    }
                    // The Retry row is a LazyColumn item AFTER the answer. When the answer already
                    // fills the viewport (measured: remaining = -14 px) it has never been composed,
                    // so a plain await() can only time out. Scroll until it exists.
                    var retryNode = find("Retry response")
                    for (attempt in 0 until 10) {
                        if (retryNode != null) break
                        shell("input swipe 233 190 233 110 350")
                        SystemClock.sleep(300)
                        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                        retryNode = find("Retry response")
                    }
                    await("Retry response")
                    val retry = checkNotNull(retryBounds())
                    val currentReader = Rect().also(await("Conversation reader")::getBoundsInScreen)
                    screenshot("measured-$lines-retry")
                    // Retry lives in the FLOATING footer column, not inside the list: the list's own
                    // bounds include `footerHeight + 6dp` of bottom padding precisely so content can
                    // scroll clear of it, which is why "inside the reader bounds" could never pass
                    // (measured: retry 224-320, reader 96-302). On a ROUND 466 px dial the meaningful
                    // requirement is that every corner of the 96 px target is inside the visible
                    // circle — that is what makes it both readable and touchable.
                    fun onDial(r: Rect): Boolean {
                        val cx = 233f
                        val cy = 233f
                        val radius = 233f
                        return listOf(
                            r.left to r.top,
                            r.right to r.top,
                            r.left to r.bottom,
                            r.right to r.bottom,
                        ).all { (x, y) ->
                            val dx = x - cx
                            val dy = y - cy
                            (dx * dx + dy * dy) <= radius * radius
                        }
                    }
                    assertTrue(
                        "Retry fully reachable on the dial: $retry $currentReader",
                        onDial(retry),
                    )
                    assertEquals("Retry transparent target width", 96, retry.width())
                    assertEquals("Retry transparent target height", 96, retry.height())
                    assertNull("No visible Retry label", nodes().firstOrNull { norm(it.text) == "Retry" })
                    val finalLine =
                        when (lines) {
                            -1 -> 1
                            0 -> 2
                            else -> lines
                        }
                    val endProse = nodes().first { norm(it.text).contains("Line $finalLine readable.") == true }
                    assertTrue(
                        "Final prose line reachable: $lines",
                        Rect().also(endProse::getBoundsInScreen).bottom <= currentReader.bottom,
                    )
                }
            } finally {
                activity?.let { i.runOnMainSync { it.finish() } }
                ids.forEach { dao.deleteConversation(it) }
            }
        }

    @Test fun actualWearImeSubmitsMultiWordPromptAndBackKeepsChat() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val token = UUID.randomUUID().toString()
            val previous =
                app.settings.state
                    .first()
                    .selectedId
            var activity: android.app.Activity? = null
            var conversation: String? = null
            MockWebServer().use { server ->
                server.start()
                try {
                    app.settings.save(
                        ProviderProfile(
                            token,
                            ProviderType.OPENAI_COMPATIBLE,
                            "IME probe",
                            server.url("/v1").toString(),
                            "ime-model",
                            token,
                        ),
                        SecretValue("controlled-ime-not-real"),
                    )
                    activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    tap("Menu")
                    tap("New chat", true)
                    tap("Keyboard input")
                    val editor = nodes().first { it.isEditable }
                    val r = Rect().also(editor::getBoundsInScreen)
                    shell("input tap ${r.centerX()} ${r.centerY()}")
                    val end = SystemClock.uptimeMillis() + 10000
                    var ime = ""
                    while (SystemClock.uptimeMillis() < end) {
                        ime = shell("dumpsys input_method").toString(Charsets.UTF_8)
                        if (ime.contains("mInputShown=true")) break
                        SystemClock.sleep(100)
                    }
                    assertTrue("Actual system IME opens", ime.contains("mInputShown=true"))
                    val focusEnd = SystemClock.uptimeMillis() + 5000
                    while (SystemClock.uptimeMillis() < focusEnd &&
                        nodes().none { it.isEditable && it.isFocused }
                    ) {
                        shell("input tap ${r.centerX()} ${r.centerY()}")
                        SystemClock.sleep(300)
                    }
                    assertTrue("Composer field has input focus", nodes().any { it.isEditable && it.isFocused })
                    screenshot("actual-ime-open")
                    // MEASURED LIMIT, not a guess. With the soft keyboard window up, `adb shell input
                    // text` and injected key events both deliver NOTHING: the field is a focused
                    // android.widget.EditText, `dumpsys input_method` reports mInputShown=true and
                    // mServedView=AndroidComposeView, mCurrentFocus is MainActivity — and the text
                    // still reads "". Dismiss the IME and the very same command lands ("Hidden test").
                    // So injection is swallowed by the IME window, not by the composer.
                    //
                    // This method therefore proves what an emulator CAN prove: the real system IME is
                    // requested and shown for this screen, the field takes input focus, the composer
                    // accepts a multi-word prompt through the platform input path, Back keeps the
                    // draft, and Send stores it. It does NOT prove a finger typing on a real watch —
                    // that stays owner-tested, and is stated as such in the README.
                    shell("input keyevent KEYCODE_BACK")
                    SystemClock.sleep(500)
                    assertTrue(
                        "Back dismissed the IME",
                        !shell("dumpsys input_method").toString(Charsets.UTF_8).contains("mInputShown=true"),
                    )
                    // The composer keeps focus after the IME is dismissed, which is what lets the
                    // prompt below land without a second tap.
                    shell("input text Wear%smulti%sword%sprompt")
                    SystemClock.sleep(300)
                    screenshot("actual-ime-entered")
                    // Read EVERY editable node: the first one is not necessarily the composer (the
                    // keyboard entry screen also exposes fields), and reading [0] reported "" for a
                    // prompt that had in fact been typed.
                    val typed =
                        nodes()
                            .filter { it.isEditable }
                            .mapNotNull { it.text?.toString() }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                    assertEquals("Wear multi word prompt", typed)
                    screenshot("actual-ime-dismissed")
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Wear IME response verified.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                    )
                    tap("Send")
                    await("Wear IME response verified.")
                    conversation =
                        app.database
                            .chats()
                            .conversations()
                            .first()
                            .first { it.title == "Wear multi word prompt" }
                            .id
                    assertEquals(
                        "Wear multi word prompt",
                        app.database
                            .chats()
                            .snapshot(conversation)
                            .first()
                            .rawContent,
                    )
                    screenshot("actual-ime-submitted")
                    tap("Keyboard input")
                    val cancel = Rect().also(nodes().first { it.isEditable }::getBoundsInScreen)
                    shell("input tap ${cancel.centerX()} ${cancel.centerY()}")
                    SystemClock.sleep(500)
                    shell("input keyevent KEYCODE_BACK")
                    await("Prompt") // First Back dismisses IME, not editor/navigation.
                    shell("input keyevent KEYCODE_BACK")
                    await("Wear IME response verified.")
                    assertEquals(
                        2,
                        app.database
                            .chats()
                            .snapshot(conversation)
                            .size,
                    )
                    screenshot("actual-ime-cancelled")
                } finally {
                    activity?.let { i.runOnMainSync { it.finish() } }
                    conversation?.let { app.database.chats().deleteConversation(it) }
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
        }

    @Test fun editAndRetryInvalidateRemovedContinuationThroughRealTouch() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val token = UUID.randomUUID().toString()
            val previous =
                app.settings.state
                    .first()
                    .selectedId
            val id = "visual-edit-$token"
            var activity: android.app.Activity? = null
            MockWebServer().use { server ->
                server.start()

                fun response(text: String) =
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: " +
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "choices",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put(
                                                    "delta",
                                                    kotlinx.serialization.json.buildJsonObject {
                                                        put("content", kotlinx.serialization.json.JsonPrimitive(text))
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            } + "\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                    )
                try {
                    app.settings.save(
                        ProviderProfile(
                            token,
                            ProviderType.OPENAI_COMPATIBLE,
                            "Visual edit probe",
                            server.url("/v1").toString(),
                            "visual-model",
                            token,
                        ),
                        SecretValue("controlled-reader-probe-not-real"),
                    )
                    dao.insertConversation(Conversation(id, "Visual edit", System.currentTimeMillis()))
                    dao.insertMessage(
                        StoredMessage(conversationId = id, role = "USER", rawContent = "Original first prompt"),
                    )
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = id,
                            role = "ASSISTANT",
                            status = "SUCCESS",
                            rawContent =
                                (1..12).joinToString(
                                    "\n\n",
                                ) { "Removed continuation $it. Old reading position must not survive editing." },
                        ),
                    )
                    dao.insertMessage(
                        StoredMessage(conversationId = id, role = "USER", rawContent = "Removed second prompt"),
                    )
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = id,
                            role = "ASSISTANT",
                            status = "SUCCESS",
                            rawContent = "Removed second answer.",
                        ),
                    )
                    activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    history()
                    tap("Visual edit", true)
                    await("Original first prompt")
                    repeat(5) { swipe() }
                    screenshot("edit-old-anchor")
                    assertTrue(nodes().any { norm(it.text).startsWith("Removed continuation") == true })
                    history()
                    tap("Visual edit", true)
                    repeat(12) { swipe(false) }
                    if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                    val original = Rect().also(await("Original first prompt")::getBoundsInScreen)
                    shell(
                        "input swipe ${original.centerX()} ${original.centerY()} ${original.centerX()} ${original.centerY()} 1200",
                    )
                    await("Edit & resend")
                    screenshot("edit-editor")
                    val field = nodes().first { it.isEditable }
                    assertTrue(
                        field.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "Edited first prompt",
                                )
                            },
                        ),
                    ) // Editor text injection; long press/navigation/send are actual touch.
                    val replacement =
                        (1..9).joinToString(
                            "\n\n",
                        ) { "Edited continuation $it. New content remains readable." }
                    server.enqueue(response(replacement))
                    assertTrue("Edit & resend is not clickable through semantics", clickNode("Edit & resend"))
                    await("Retry response")
                    val finish = SystemClock.uptimeMillis() + 10000
                    while (dao.snapshot(id).last().status == "STREAMING" &&
                        SystemClock.uptimeMillis() < finish
                    ) {
                        kotlinx.coroutines.delay(100)
                    }
                    val edited = dao.snapshot(id)
                    assertEquals(listOf("Edited first prompt", replacement), edited.map { it.rawContent })
                    assertEquals("SUCCESS", edited.last().status)
                    assertFalse(nodes().any { norm(it.text).startsWith("Removed") == true })
                    screenshot("edit-new-tail")
                    repeat(3) { swipe(false) }
                    val anchor =
                        nodes()
                            .first {
                                norm(it.text).startsWith(
                                    "Edited continuation",
                                ) == true
                            }.text
                            .toString()
                    history()
                    // Editing a chat's FIRST message retitles the conversation to that message
                    // (ChatDatabase.retitle, by design), so the row is no longer called "Visual
                    // edit" here — measured: the History row read "Edited first prompt" while this
                    // lookup still asked for the old title. Read the title the database actually has.
                    val retitled =
                        dao
                            .conversations()
                            .first()
                            .first { it.id == id }
                            .title
                    tap(retitled, true)
                    await(anchor)
                    assertFalse(nodes().any { norm(it.text).startsWith("Removed") == true })
                    screenshot("edit-return-new-anchor")
                    tap("Jump to latest")
                    server.enqueue(response("Retried visible replacement. Final line remains reachable."))
                    tap("Retry response", true)
                    await("Retried visible replacement. Final line remains reachable.")
                    val retryEnd = SystemClock.uptimeMillis() + 10000
                    while (dao.snapshot(id).last().status == "STREAMING" &&
                        SystemClock.uptimeMillis() < retryEnd
                    ) {
                        kotlinx.coroutines.delay(100)
                    }
                    val retried = dao.snapshot(id)
                    assertEquals(2, retried.size)
                    assertEquals("SUCCESS", retried.last().status)
                    assertEquals("Edited first prompt", retried.first().rawContent)
                    assertFalse(nodes().any { norm(it.text).startsWith("Edited continuation") == true })
                    assertEquals(2, server.requestCount)
                    val request = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
                    assertTrue(request.contains("Edited first prompt"))
                    assertFalse(request.contains("Removed continuation"))
                    screenshot("edit-retry-new-content")
                } finally {
                    activity?.let { i.runOnMainSync { it.finish() } }
                    dao.deleteConversation(id)
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
        }

    @Test fun historyDuringActiveGenerationPreservesDetachedReaderAndSingleRequest() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val token = UUID.randomUUID().toString()
            val previous =
                app.settings.state
                    .first()
                    .selectedId
            val other = "active-history-other-$token"
            var conversation: String? = null
            var activity: android.app.Activity? = null
            MockWebServer().use { server ->
                server.start()
                try {
                    app.settings.save(
                        ProviderProfile(
                            token,
                            ProviderType.OPENAI_COMPATIBLE,
                            "Active History probe",
                            server.url("/v1").toString(),
                            "history-model",
                            token,
                        ),
                        SecretValue("controlled-reader-probe-not-real"),
                    )
                    dao.insertConversation(Conversation(other, "Other History fixture", System.currentTimeMillis() - 1))
                    dao.insertMessage(StoredMessage(conversationId = other, role = "USER", rawContent = "Other prompt"))
                    val text =
                        (1..25).joinToString("\n\n") {
                            "History stream paragraph $it. Preserve detached reading while opening History."
                        }

                    fun frame(value: String) =
                        "data: " +
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "choices",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put(
                                                    "delta",
                                                    kotlinx.serialization.json.buildJsonObject {
                                                        put("content", kotlinx.serialization.json.JsonPrimitive(value))
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            } + "\n\n"
                    val body = frame(text)
                    // Hold HTTP open without a semantic completion until user Stop cancels it.
                    // One outstanding byte avoids a duration-dependent race with History navigation.
                    server.enqueue(
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(body)
                            .setHeader("Content-Length", body.toByteArray().size + 1),
                    )
                    activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    tap("Menu")
                    tap("New chat", true)
                    tap("Keyboard input")
                    val field = nodes().first { it.isEditable }
                    assertTrue(
                        field.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "History active prompt",
                                )
                            },
                        ),
                    )
                    tap("Send")
                    await("Stop")
                    await("History stream paragraph 25. Preserve detached reading while opening History.")
                    conversation =
                        dao
                            .conversations()
                            .first()
                            .first { it.title == "History active prompt" }
                            .id
                    shell("input swipe 233 105 233 285 500")
                    i.waitForIdleSync()
                    android.util.Log.e(
                        "ReaderNavigationTest",
                        "History detach: ${await("Conversation reader").stateDescription}",
                    )
                    await("Jump to latest")
                    if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                    val before = await("Conversation reader").stateDescription.toString()
                    screenshot("history-generating-detached")
                    history()
                    screenshot("history-generating-open")
                    var otherNode = find("Other History fixture")
                    repeat(8) {
                        if (otherNode == null) {
                            swipe()
                            otherNode = find("Other History fixture")
                        }
                    }
                    var clickable = checkNotNull(otherNode)
                    while (!clickable.isClickable) clickable = checkNotNull(clickable.parent)
                    assertFalse("Other conversation disabled during generation", clickable.isEnabled)
                    assertEquals("STREAMING", dao.snapshot(conversation).last().status)
                    shell("input keyevent KEYCODE_BACK") // History returns directly to reader.
                    await("Stop")
                    await("Jump to latest")
                    if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                    val after = await("Conversation reader").stateDescription.toString()
                    assertEquals(
                        before.substringAfter(";index=").substringBefore(";forward="),
                        after.substringAfter(";index=").substringBefore(";forward="),
                    )
                    assertTrue(after.contains(";following=false"))
                    screenshot("history-generating-return")
                    assertEquals(1, server.requestCount)
                    tap("Stop")
                    await("Keyboard input")
                    val stopped = SystemClock.uptimeMillis() + 10000
                    while (dao.snapshot(conversation).last().status == "STREAMING" &&
                        SystemClock.uptimeMillis() < stopped
                    ) {
                        kotlinx.coroutines.delay(100)
                    }
                    assertEquals("STOPPED", dao.snapshot(conversation).last().status)
                    assertEquals(1, server.requestCount)
                    screenshot("history-generating-stopped")
                } finally {
                    activity?.let { i.runOnMainSync { it.finish() } }
                    conversation?.let { dao.deleteConversation(it) }
                    dao.deleteConversation(other)
                    app.settings.delete(token)
                    previous?.let { app.settings.select(it) }
                }
            }
        }

    @Test fun historyRestoresReaderAndOwnListThenNewChatAndFollowUp() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val token = UUID.randomUUID().toString()
            val a = "reader-a-$token"
            val b = "reader-b-$token"
            val historyIds = (0..7).map { "reader-history-$it-$token" }
            val oldSelection =
                app.settings.state
                    .first()
                    .selectedId
            MockWebServer().use { server ->
                server.start()
                val profile =
                    ProviderProfile(
                        token,
                        ProviderType.OPENAI_COMPATIBLE,
                        "Reader probe",
                        server.url("/v1").toString(),
                        "reader-model",
                        token,
                    )
                var activity: android.app.Activity? = null
                try {
                    app.settings.save(profile, SecretValue("controlled-reader-probe-not-real"))
                    dao.insertConversation(Conversation(a, "Reader A", System.currentTimeMillis() + 2))
                    dao.insertConversation(Conversation(b, "Reader B", System.currentTimeMillis() + 1))
                    dao.insertMessage(StoredMessage(conversationId = a, role = "USER", rawContent = "Reader A prompt"))
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = a,
                            role = "ASSISTANT",
                            rawContent =
                                (1..35).joinToString("\n\n") {
                                    "Reader paragraph $it. Stable position, full prose stays readable while navigating between conversations."
                                },
                            status = "SUCCESS",
                            providerProfileId = token,
                            providerLabel = "Original provenance",
                            modelId = "original-model",
                        ),
                    )
                    dao.insertMessage(StoredMessage(conversationId = b, role = "USER", rawContent = "Reader B prompt"))
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = b,
                            role = "ASSISTANT",
                            rawContent = "Reader B answer.",
                            status = "SUCCESS",
                            providerProfileId = token,
                            providerLabel = "Original provenance",
                            modelId = "original-model",
                        ),
                    )
                    historyIds.forEachIndexed { index, id ->
                        dao.insertConversation(
                            Conversation(
                                id,
                                "Reader history $index",
                                System.currentTimeMillis() - 1000 - index,
                            ),
                        )
                        dao.insertMessage(
                            StoredMessage(conversationId = id, role = "USER", rawContent = "History fixture $index"),
                        )
                    }
                    activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    SystemClock.sleep(1000)
                    history()
                    tap("Reader A", true)
                    await("Reader A prompt")
                    repeat(7) { swipe() }
                    val anchor =
                        nodes().first {
                            norm(it.text).startsWith("Reader paragraph") == true &&
                                Rect().also(it::getBoundsInScreen).top >= 84
                        }
                    val text = anchor.text.toString()
                    val before = Rect().also(anchor::getBoundsInScreen).top
                    screenshot("reader-before-history")
                    history()
                    tap("Reader B", true)
                    await("Reader B prompt")
                    tap("Jump to latest")
                    await("Reader B answer.")
                    val shortAnswer = Rect().also(await("Reader B answer.")::getBoundsInScreen)
                    assertTrue(
                        "Latest keeps short answer readable: $shortAnswer",
                        shortAnswer.top >= 84 && shortAnswer.bottom <= 306,
                    )
                    screenshot("reader-b")
                    history()
                    tap("Reader A", true)
                    val restored = await(text)
                    assertTrue(
                        "Restore near saved semantic block",
                        kotlin.math.abs(Rect().also(restored::getBoundsInScreen).top - before) <= 32,
                    )
                    screenshot("reader-restored")
                    repeat(3) { cycle ->
                        history()
                        tap("Reader B", true)
                        await("Reader B answer.")
                        history()
                        tap("Reader A", true)
                        assertTrue(
                            "A/B/A cycle $cycle retains anchor",
                            kotlin.math.abs(Rect().also(await(text)::getBoundsInScreen).top - before) <= 32,
                        )
                    }
                    i.runOnMainSync { activity!!.recreate() }
                    SystemClock.sleep(1500)
                    val recreated = await(text)
                    assertTrue(
                        "Activity recreation restores anchor",
                        kotlin.math.abs(Rect().also(recreated::getBoundsInScreen).top - before) <= 32,
                    )
                    screenshot("reader-recreated")
                    history()
                    repeat(8) { swipe() }
                    val historyText =
                        nodes()
                            .first {
                                norm(it.text).startsWith("Reader history") == true &&
                                    Rect().also(it::getBoundsInScreen).top >= 60
                            }.text
                            .toString()
                    val historyTop = Rect().also(await(historyText)::getBoundsInScreen).top
                    screenshot("history-scrolled")
                    // NOT tap(..., scroll = true): the scrolling search moved the list itself, so the
                    // offset compared below belonged to a different scroll than the one measured.
                    // The row is already on screen here; a bare tap leaves the position untouched.
                    tap(historyText)
                    SystemClock.sleep(500)
                    history()
                    val historyRestored = Rect().also(await(historyText)::getBoundsInScreen).top
                    assertTrue(
                        "History list offset retained: was $historyTop, restored $historyRestored",
                        kotlin.math.abs(historyRestored - historyTop) <= 16,
                    )
                    screenshot("history-restored")
                    repeat(20) { swipe(false) }
                    // History keeps its offset now, so New chat may sit above or below the restored
                    // position; the scrolling lookup sweeps both directions.
                    tap("New chat", true)
                    await("Ask anything")
                    await("Use keyboard or voice to start.")
                    screenshot("history-new-chat")
                    history()
                    tap("Reader A", true)
                    await(text)
                    val original = dao.snapshot(a)
                    assertEquals("original-model", original.last().modelId)
                    tap("Keyboard input")
                    val field =
                        nodes().first { node ->
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                        }
                    assertTrue(
                        field.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "Reader follow up",
                                )
                            },
                        ),
                    )
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Reader follow-up answer.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                    )
                    tap("Send")
                    await("Reader follow-up answer.")
                    val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                    assertTrue(request.body.readUtf8().contains("Reader A prompt"))
                    val rows = dao.snapshot(a)
                    assertEquals(4, rows.size)
                    assertEquals("Reader follow up", rows[2].rawContent)
                    assertEquals("original-model", rows[1].modelId)
                    assertEquals("reader-model", rows[3].modelId)
                    screenshot("reader-follow-up")
                } finally {
                    activity?.let { i.runOnMainSync { it.finish() } }
                    dao.deleteConversation(a)
                    dao.deleteConversation(b)
                    historyIds.forEach { dao.deleteConversation(it) }
                    app.settings.delete(profile.id)
                    oldSelection?.let { app.settings.select(it) }
                }
            }
        }

    @Test fun wideMathAndCodeUseHorizontalPanInsideSingleVerticalReader() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val fixture =
                app.assets
                    .open("RENDER_TORTURE_FIXTURE.md")
                    .bufferedReader()
                    .use { it.readText() }
            val prefixes =
                listOf(
                    "Long fraction:",
                    "Nested radicals:",
                    "Long indices:",
                    "Vector equation:",
                    "Function signature:",
                    "Wide matrix:",
                )
            val formulas =
                fixture
                    .lines()
                    .filter { line -> prefixes.any(line::startsWith) }
                    .map { it.substringAfter('$').substringBefore('$') }
            assertEquals(6, formulas.size)
            val ids = formulas.indices.map { "reader-gesture-${UUID.randomUUID()}" }
            var activity: android.app.Activity? = null
            try {
                formulas.forEachIndexed { index, source ->
                    dao.insertConversation(
                        Conversation(ids[index], "Gesture $index", System.currentTimeMillis() + index),
                    )
                    dao.insertMessage(
                        StoredMessage(conversationId = ids[index], role = "USER", rawContent = "Gesture prompt $index"),
                    )
                    dao.insertMessage(
                        StoredMessage(
                            conversationId = ids[index],
                            role = "ASSISTANT",
                            status = "SUCCESS",
                            providerLabel = "Gesture fixture",
                            modelId = "local",
                            rawContent =
                                "Before formula $index.\n\n\\[\n$source\n\\]\n\nAfter formula $index.\n\n" +
                                    "```kotlin\n    val longLine = \"START-${"abcdefghij".repeat(
                                        22,
                                    )}-END\"\n    println(longLine)\n```\n\n" +
                                    (1..6).joinToString("\n\n") {
                                        "Tail paragraph $it. Vertical scrolling remains owned by conversation, not code or math."
                                    },
                        ),
                    )
                }
                activity =
                    i.startActivitySync(
                        Intent(i.targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                SystemClock.sleep(1500)
                formulas.forEachIndexed { index, source ->
                    history()
                    for (step in 0..20) {
                        if (find("Back") != null) break
                        swipe(false)
                    }
                    tap("Gesture $index", true)
                    val label = "Math: $source"
                    for (step in 0..16) {
                        val math = find(label)
                        val bounds = math?.let { Rect().also(it::getBoundsInScreen) }
                        if (bounds == null || bounds.top >= 215 || bounds.bottom <= 100) {
                            shell("input swipe 233 200 233 160 500")
                            SystemClock.sleep(300)
                        } else {
                            break
                        }
                    }
                    val math = await(label)
                    var pan: AccessibilityNodeInfo? = math.parent
                    while (pan != null && !pan.isScrollable) pan = pan.parent
                    assertNotNull("Horizontal math owner", pan)
                    val bounds = Rect().also(pan!!::getBoundsInScreen)
                    val y = bounds.centerY().coerceIn(100, 220)

                    fun pixels(): IntArray {
                        SystemClock.sleep(350)
                        val image = i.uiAutomation.takeScreenshot()
                        val crop =
                            Rect(
                                bounds.left.coerceAtLeast(0),
                                bounds.top.coerceAtLeast(84),
                                bounds.right.coerceAtMost(466),
                                bounds.bottom.coerceAtMost(234),
                            )
                        val result = IntArray(crop.width() * crop.height())
                        image.getPixels(result, 0, crop.width(), crop.left, crop.top, crop.width(), crop.height())
                        image.recycle()
                        return result
                    }
                    val start = pixels()
                    screenshot("reader-math-$index-start")
                    shell("input swipe 330 $y 120 $y 600")
                    assertFalse("Touch horizontally pans math $index", start.contentEquals(pixels()))
                    repeat(8) { shell("input swipe 330 $y 120 $y 350") }
                    screenshot("reader-math-$index-end")
                    val top = Rect().also(await(label)::getBoundsInScreen).top
                    shell("input swipe 233 $y 233 ${y - 65} 500")
                    SystemClock.sleep(400)
                    val after = find(label)?.let { Rect().also(it::getBoundsInScreen).top }
                    assertTrue("Vertical swipe over math moves conversation $index", after == null || after < top - 10)
                    shell("input swipe 300 210 230 140 500")
                    SystemClock.sleep(400)
                    assertNotNull(find("Menu"))
                    assertNull(find("History"))
                    screenshot("reader-math-$index-diagonal")
                }
                val codeText = "    val longLine = \"START-${"abcdefghij".repeat(22)}-END\"\n    println(longLine)"
                var code: AccessibilityNodeInfo? = null
                for (step in 0..20) {
                    code = find(codeText)
                    val r = code?.let { Rect().also(it::getBoundsInScreen) }
                    if (r != null && r.top >= 84 && r.bottom <= 230) break
                    if (r != null && r.top < 84) {
                        swipe(false)
                        continue
                    }
                    swipe()
                }
                assertNotNull("Code reachable after math", code)
                val before = Rect().also(code!!::getBoundsInScreen)
                screenshot("reader-code-start")
                shell("input swipe 330 ${before.centerY()} 120 ${before.centerY()} 600")
                repeat(40) { shell("input swipe 330 ${before.centerY()} 120 ${before.centerY()} 250") }
                screenshot("reader-code-end")
                val current = Rect().also(await(codeText)::getBoundsInScreen)
                val tail =
                    nodes()
                        .first {
                            norm(it.text).startsWith(
                                "Tail paragraph 1.",
                            ) == true
                        }.text
                        .toString()
                val tailTop = Rect().also(await(tail)::getBoundsInScreen).top
                val touchY = current.centerY().coerceIn(140, 220)
                shell("input swipe 233 $touchY 233 ${touchY - 65} 500")
                SystemClock.sleep(400)
                val moved = find(tail)?.let { Rect().also(it::getBoundsInScreen).top }
                screenshot("reader-code-vertical")
                android.util.Log.e(
                    "ReaderNavigationTest",
                    "Code vertical before=$before current=$current moved=$moved touchY=$touchY",
                )
                assertTrue("Vertical swipe over code moves reader", moved == null || moved < tailTop - 10)
            } finally {
                activity?.let { i.runOnMainSync { it.finish() } }
                ids.forEach { dao.deleteConversation(it) }
            }
        }

    @Test fun mixedHttpStreamDetachedViewportSurvivesProtocolFailureAndLinearRetry() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val token = UUID.randomUUID().toString()
            val previous =
                app.settings.state
                    .first()
                    .selectedId
            val fixture =
                app.assets
                    .open("RENDER_TORTURE_FIXTURE.md")
                    .bufferedReader()
                    .use { it.readText() }
            var conversation: String? = null
            var activity: android.app.Activity? = null
            MockWebServer().use { server ->
                server.start()
                val profile =
                    ProviderProfile(
                        token,
                        ProviderType.OPENAI_COMPATIBLE,
                        "Mixed reader probe",
                        server.url("/v1").toString(),
                        "mixed-model",
                        token,
                    )
                try {
                    app.settings.save(profile, SecretValue("controlled-mixed-reader-not-real"))
                    activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    SystemClock.sleep(1500)
                    tap("Menu")
                    tap("New chat", true)
                    await("Ask anything")
                    await("Use keyboard or voice to start.")
                    tap("Keyboard input")
                    val field =
                        nodes().first { node ->
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                        }
                    assertTrue(
                        field.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "Stream mixed reader fixture",
                                )
                            },
                        ),
                    )
                    val chunks = mutableListOf<String>()
                    val sizes = intArrayOf(32, 1, 47, 7, 53)
                    var offset = 0
                    while (offset < fixture.length) {
                        var end = (offset + sizes[chunks.size % sizes.size]).coerceAtMost(fixture.length)
                        if (end < fixture.length && fixture[end - 1].isHighSurrogate() &&
                            fixture[end].isLowSurrogate()
                        ) {
                            end++
                        }
                        chunks += fixture.substring(offset, end)
                        offset = end
                    }
                    val frames =
                        chunks.joinToString("") { text ->
                            "data: " +
                                kotlinx.serialization.json.buildJsonObject {
                                    put(
                                        "choices",
                                        kotlinx.serialization.json.buildJsonArray {
                                            add(
                                                kotlinx.serialization.json.buildJsonObject {
                                                    put("index", kotlinx.serialization.json.JsonPrimitive(0))
                                                    put(
                                                        "delta",
                                                        kotlinx.serialization.json.buildJsonObject {
                                                            put(
                                                                "content",
                                                                kotlinx.serialization.json.JsonPrimitive(text),
                                                            )
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                } + "\n\n"
                        }
                    // Clean EOF without semantic completion must be ERROR, never SUCCESS.
                    server.enqueue(
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(frames)
                            .throttleBody(256, 1, TimeUnit.SECONDS),
                    )
                    tap("Send")
                    await("Stop")
                    SystemClock.sleep(6000)
                    // Swipe until the control appears rather than assuming three swipes reach a
                    // scroll position where following is considered broken. Three swipes passed in
                    // isolation (71.1s) and failed in-suite (28.1s) with "Missing Jump to latest",
                    // because how far the transcript has streamed by now depends on the device load.
                    var detached = 0
                    while (detached < 12 && find("Jump to latest") == null) {
                        swipe(false)
                        detached++
                    }
                    await("Jump to latest")
                    screenshot("mixed-detached")
                    val anchor =
                        nodes().first { node ->
                            val r = Rect().also(node::getBoundsInScreen)
                            !node.text.isNullOrEmpty() &&
                                node.text?.toString() !in setOf("Keyboard input", "Voice input", "Latest", "Stop") &&
                                r.top >= 84 &&
                                r.top < 290
                        }
                    val text = anchor.text.toString()
                    val top = Rect().also(anchor::getBoundsInScreen).top
                    screenshot("mixed-detached")
                    SystemClock.sleep(1200)
                    val diagnostic =
                        dao
                            .conversations()
                            .first()
                            .first {
                                it.title == "Stream mixed reader fixture"
                            }.let { dao.snapshot(it.id).last() }
                    android.util.Log.e(
                        "ReaderNavigationTest",
                        "Mixed status=${diagnostic.status} error=${diagnostic.error} length=${diagnostic.rawContent.length} frames=${frames.length}",
                    )
                    assertEquals(
                        "Still streaming during detach check",
                        "STREAMING",
                        dao
                            .conversations()
                            .first()
                            .first {
                                it.title ==
                                    "Stream mixed reader fixture"
                            }.let { dao.snapshot(it.id).last().status },
                    )
                    assertEquals(
                        "Incoming chunks do not move detached viewport",
                        top,
                        Rect().also(awaitPrefix(text)::getBoundsInScreen).top,
                    )
                    screenshot("mixed-detached-later")
                    conversation =
                        dao
                            .conversations()
                            .first()
                            .first { it.title == "Stream mixed reader fixture" }
                            .id
                    val end = SystemClock.uptimeMillis() + 60000
                    while (dao.snapshot(conversation).last().status == "STREAMING" &&
                        SystemClock.uptimeMillis() < end
                    ) {
                        kotlinx.coroutines.delay(200)
                    }
                    val partial = dao.snapshot(conversation)
                    assertEquals("ERROR", partial.last().status)
                    assertEquals(fixture, partial.last().rawContent)
                    assertEquals(
                        "Failure retains detached viewport",
                        top,
                        Rect().also(awaitPrefix(text)::getBoundsInScreen).top,
                    )
                    screenshot("mixed-error-detached")
                    tap("Jump to latest")
                    await("Retry response")
                    screenshot("mixed-error-tail")
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Explicit retry replaces failed answer.\"}}]}\n\n" +
                                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                        ),
                    )
                    tap("Retry response")
                    await("Explicit retry replaces failed answer.")
                    val rows = dao.snapshot(conversation)
                    assertEquals(2, rows.size)
                    assertEquals("SUCCESS", rows.last().status)
                    assertEquals("Explicit retry replaces failed answer.", rows.last().rawContent)
                    screenshot("mixed-retry")
                } finally {
                    activity?.let { i.runOnMainSync { it.finish() } }
                    conversation?.let { dao.deleteConversation(it) }
                    app.settings.delete(profile.id)
                    previous?.let { app.settings.select(it) }
                }
            }
        }
}
