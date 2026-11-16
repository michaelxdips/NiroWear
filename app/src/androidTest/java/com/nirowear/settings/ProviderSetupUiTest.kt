package com.nirowear.settings

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.provider.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderSetupUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun find(
        value: String,
        node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
        editableOnly: Boolean = false,
    ): AccessibilityNodeInfo? {
        // API 33+ serves a cached tree: measured on the Wear OS 37 AVD, `rootInActiveWindow` returned an
        // EMPTY tree (0 nodes) until `clearCache()` was called, which is why every lookup here timed out
        // with "Cannot activate Save securely" against a screen that was actually on display.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        node ?: return null
        node.refresh()
        if (node.text?.toString()?.contains(value) == true || node.contentDescription?.toString() == value) {
            if (!editableOnly || node.isEditable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isEditable) return parent
                parent = parent.parent
            }
        }
        for (i in 0 until node.childCount) find(value, node.getChild(i), editableOnly)?.let { return it }
        return null
    }

    private fun await(value: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            find(value)?.let { return it }
            SystemClock.sleep(100)
        }
        error("UI element missing: $value")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .use { it.readBytes() }
        }
    }

    /** Waits for an editable field, then names it in the failure instead of throwing a bare NPE. */
    private fun awaitField(value: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            find(value, editableOnly = true)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Editable field missing: $value")
    }

    private fun scrollTo(value: String) {
        // A fixed budget of 12 swipes is not enough once a discovered model catalog sits between
        // the top and the manual field: the list grows with the number of discovered models, so
        // the distance to scroll depends on the server response, not on the screen. Raise the
        // budget and report the count, so a genuine layout regression is still visible.
        var swipes = 0
        while (swipes < 40) {
            if (find(value) != null) return
            shell("input swipe 233 310 233 170 400")
            SystemClock.sleep(400)
            swipes++
        }
        error("UI element missing after $swipes swipes: $value")
    }

    /**
     * Scrolls until the field itself is composed, not merely its label.
     *
     * Entry() draws a Text label above a BasicTextField. When the label sits at the bottom edge of
     * the viewport the label is in the tree and the field below it is not, so a scroll that stops
     * at the label leaves the editable node uncomposed. Measured: "Manual model ID" was present
     * while no editable node under it existed.
     */
    private fun scrollToField(value: String) {
        var swipes = 0
        while (swipes < 40) {
            if (find(value, editableOnly = true) != null) return
            shell("input swipe 233 310 233 170 400")
            SystemClock.sleep(400)
            swipes++
        }
        error("Editable field missing after $swipes swipes: $value")
    }

    /** Diagnostic only: one line per node so a failure says what was on screen. */
    private fun dumpTree(tag: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun collect(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
            if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { collect(n.getChild(it)) }
        collect(instrumentation.uiAutomation.rootInActiveWindow).forEach {
            android.util.Log.e("PROVSETUP", "$tag text=${it.text} desc=${it.contentDescription} clickable=${it.isClickable} enabled=${it.isEnabled} cls=${it.className}")
        }
    }

    /** Swipes back to the top: `scrollToField` only ever moves forward, so a lookup after a discovery
     * round starts below the fields it needs and never finds them. */
    private fun scrollToTop() {
        repeat(25) {
            shell("input swipe 233 170 233 310 300")
            SystemClock.sleep(150)
        }
    }

    private fun click(value: String) {
        scrollTo(value)
        SystemClock.sleep(500)
        var node: AccessibilityNodeInfo? = find(value)
        while (node != null) {
            if (node.isClickable) {
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    dumpTree("click-failed:$value")
                    error("Cannot activate $value")
                }
                SystemClock.sleep(250)
                return
            }
            node = node.parent
        }
        error("Cannot click: $value")
    }

    private fun enter(
        label: String,
        value: String,
    ): AccessibilityNodeInfo {
        SystemClock.sleep(200)
        var node = find(label, editableOnly = true)
        repeat(12) {
            if (node == null) {
                shell("input swipe 233 310 233 200 400")
                SystemClock.sleep(400)
                node = find(label, editableOnly = true)
            }
        }
        val editor = node ?: error("Editor missing: $label")
        check(
            editor.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            ),
        ) { "Cannot edit $label" }
        SystemClock.sleep(100)
        return editor
    }

    @Test fun maskedWatchSetupManualModelAndKeyboardGeneration() = setupAndGenerate(ProviderType.OPENAI_COMPATIBLE)

    @Test fun maskedOpenAiSetupAndKeyboardGeneration() = setupAndGenerate(ProviderType.OPENAI)

    @Test fun maskedGeminiSetupAndKeyboardGeneration() = setupAndGenerate(ProviderType.GEMINI)

    @Test fun maskedAnthropicSetupAndKeyboardGeneration() = setupAndGenerate(ProviderType.ANTHROPIC)

    @Test fun discoveryIgnoresChangedTrustInputsAndClearsCatalogWithoutChangingManualModel() {
        val app = instrumentation.targetContext.applicationContext as NiroApplication
        MockWebServer().use { server ->
            server.start()
            val activity =
                instrumentation.startActivitySync(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            var release = java.util.concurrent.CountDownLatch(0)

            fun top() {
                repeat(10) { shell("input swipe 233 170 233 310 250") }
                instrumentation.waitForIdleSync()
            }

            fun assertCatalogAbsent(id: String) {
                top()
                repeat(12) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    assertNull("Stale catalog entry: $id", find(id))
                    shell("input swipe 233 310 233 170 250")
                    instrumentation.waitForIdleSync()
                }
            }

            fun awaitDiscoveryIdle() {
                scrollTo("Discover models")
                val end = SystemClock.uptimeMillis() + 8000
                do {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    var node = find("Discover models")
                    while (node != null && !node.isClickable) node = node.parent
                    if (node?.isEnabled == true) return
                    SystemClock.sleep(100)
                } while (SystemClock.uptimeMillis() < end)
                error("Discovery did not settle")
            }
            try {
                click("Menu")
                click("Providers / model")
                click("Add compatible")
                enter("Base URL", server.url("/v1").toString())
                enter("Manual model ID", "manual-model-kept")
                enter("API key", "controlled-discovery-key")
                for ((index, label) in listOf("Base URL", "API key").withIndex()) {
                    val staleId = "stale-discovery-$index"
                    val freshId = "fresh-discovery-$index"
                    val started = java.util.concurrent.CountDownLatch(1)
                    release = java.util.concurrent.CountDownLatch(1)
                    val responseRelease = release
                    server.dispatcher =
                        object : okhttp3.mockwebserver.Dispatcher() {
                            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                                started.countDown()
                                check(responseRelease.await(30, java.util.concurrent.TimeUnit.SECONDS))
                                return MockResponse().setBody("""{"data":[{"id":"$staleId"}]}""")
                            }
                        }
                    top()
                    click("Discover models")
                    assertTrue("Discovery request started", started.await(8, java.util.concurrent.TimeUnit.SECONDS))
                    top()
                    enter(
                        label,
                        if (label ==
                            "Base URL"
                        ) {
                            server.url("/changed/v1").toString()
                        } else {
                            "changed-controlled-discovery-key"
                        },
                    )
                    release.countDown()
                    awaitDiscoveryIdle()
                    assertCatalogAbsent(staleId)
                    server.dispatcher =
                        object : okhttp3.mockwebserver.Dispatcher() {
                            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                                MockResponse().setBody("""{"data":[{"id":"$freshId"}]}""")
                        }
                    top()
                    click("Discover models")
                    awaitDiscoveryIdle()
                    scrollTo(freshId)
                    await(freshId)
                    top()
                    enter(
                        label,
                        if (label ==
                            "Base URL"
                        ) {
                            server.url("/next/v1").toString()
                        } else {
                            "next-controlled-discovery-key"
                        },
                    )
                    assertCatalogAbsent(freshId)
                    top()
                    scrollToField("Manual model ID")
                    assertEquals("manual-model-kept", awaitField("Manual model ID").text.toString())
                    enter("API key", if (index == 0) "controlled-discovery-key" else "next-controlled-discovery-key")
                    assertTrue(awaitField("API key").isPassword)
                    instrumentation.runOnMainSync {
                        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    }
                }
                assertEquals(4, server.requestCount)
            } finally {
                release.countDown()
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun nativeClipboardPastesIntoEverySetupFieldWithoutSaving() {
        val app = instrumentation.targetContext.applicationContext as NiroApplication
        val activity =
            instrumentation.startActivitySync(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)

        fun assertEditor(
            label: String,
            value: String?,
            password: Boolean,
        ) {
            val end = SystemClock.uptimeMillis() + 8000
            var editor: AccessibilityNodeInfo? = null
            do {
                if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                editor = find(label, editableOnly = true)
                if (editor != null && editor.isPassword == password &&
                    (value == null || editor.text?.toString() == value)
                ) {
                    break
                }
                SystemClock.sleep(100)
            } while (SystemClock.uptimeMillis() < end)
            assertNotNull("Editor present: $label", editor)
            assertEquals("Password state: $label", password, editor!!.isPassword)
            if (value != null) assertEquals("Exact native paste: $label", value, editor.text?.toString())
        }
        try {
            click("Menu")
            click("Providers / model")
            click("Add compatible")
            for ((label, value) in listOf(
                "Name" to "Dummy profile",
                "Base URL" to "https://example.com/v1",
                "Manual model ID" to "nirowear-test-model-0123456789",
                "API key" to "sk-dummy-nirowear-abcdefghijklmnopqrstuvwxyz-123456",
            )) {
                var bounds = android.graphics.Rect()
                repeat(12) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    val field = find(label, editableOnly = true)
                    if (field != null) field.getBoundsInScreen(bounds)
                    if (field == null || bounds.top < 90 || bounds.bottom > 350) {
                        shell(
                            if (field != null && bounds.top < 90) {
                                "input swipe 233 160 233 280 350"
                            } else {
                                "input swipe 233 300 233 180 350"
                            },
                        )
                    }
                }
                val field = find(label, editableOnly = true) ?: error("Editor missing: $label")
                field.getBoundsInScreen(bounds)
                assertTrue("Field not touchable: $label $bounds", bounds.top >= 90 && bounds.bottom <= 350)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                instrumentation.waitForIdleSync()
                if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                val focused = find(label, editableOnly = true) ?: error("Focused editor missing: $label")
                assertTrue("Editor focus: $label", focused.isFocused)
                instrumentation.runOnMainSync {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dummy acceptance", value))
                }
                // Android editing actions, not desktop shortcut or human long-press acceptance.
                //
                // Select-all only when the field already has text: ACTION_SET_SELECTION returns
                // false on an empty editor, which is why the original guarded it. But paste
                // INSERTS at the cursor when there is no selection, so a prefilled field produced
                // "Custom proDummy profilevider" instead of replacing. Guard on emptiness, then
                // always paste.
                val existing = focused.text?.length ?: 0
                if (existing > 0) {
                    assertTrue(
                        "Select all supported: $label",
                        focused.performAction(
                            AccessibilityNodeInfo.ACTION_SET_SELECTION,
                            Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                                    existing,
                                )
                            },
                        ),
                    )
                }
                assertTrue("Native paste supported: $label", focused.performAction(AccessibilityNodeInfo.ACTION_PASTE))
                instrumentation.waitForIdleSync()
                if (label == "API key") {
                    assertEditor(label, null, password = true)
                    click("Reveal key") // Dummy only: inspect exact value, then restore masking.
                    assertEditor(label, value, password = false)
                    click("Hide key")
                    assertEditor(label, null, password = true)
                } else {
                    assertEditor(label, value, password = false)
                }
                instrumentation.runOnMainSync {
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                shell("input keyevent KEYCODE_BACK") // Dismiss actual Wear IME before scrolling next field.
                instrumentation.waitForIdleSync()
            }
        } finally {
            instrumentation.runOnMainSync {
                clipboard.clearPrimaryClip()
                activity.finish()
            }
        }
    }

    private fun setupAndGenerate(type: ProviderType) =
        runBlocking {
            val app = instrumentation.targetContext.applicationContext as NiroApplication
            MockWebServer().use { server ->
                server.start()
                val activity =
                    instrumentation.startActivitySync(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                var profileId: String? = null
                var conversationId: String? = null
                try {
                    click("Menu")
                    click("Providers / model")
                    click(
                        when (type) {
                            ProviderType.OPENAI_COMPATIBLE -> "Add compatible"
                            ProviderType.OPENAI -> "Add OpenAI"
                            ProviderType.GEMINI -> "Add Gemini"
                            ProviderType.ANTHROPIC -> "Add Anthropic"
                        },
                    )
                    enter("Name", "UI controlled provider")
                    enter("Base URL", server.url(if (type == ProviderType.GEMINI) "/v1beta" else "/v1").toString())
                    enter("Manual model ID", "manual-ui-model")
                    enter("API key", "controlled-ui-test-not-real")
                    assertTrue(awaitField("API key").isPassword)
                    instrumentation.runOnMainSync {
                        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    }
                    server.enqueue(MockResponse().setResponseCode(404))
                    scrollTo("Discover models")
                    click("Discover models")
                    scrollTo("Models unavailable. Enter model manually; chat still allowed.")
                    await("Models unavailable. Enter model manually; chat still allowed.")
                    // A failed discovery asks the user to TYPE the model, and the manual field is what
                    // Save validates: measured on the AVD, the Save button was clickable=true
                    // enabled=false after a 404 discovery even though this test had filled the field
                    // earlier, so it must be filled again once discovery has settled.
                    scrollToTop()
                    scrollToField("Manual model ID")
                    enter("Manual model ID", "manual-ui-model")
                    // Return to Save after completed discovery, not fixed-delay tap on disabled button.
                    repeat(10) {
                        if (find("Save securely") ==
                            null
                        ) {
                            shell("input swipe 233 170 233 310 400")
                            SystemClock.sleep(400)
                        }
                    }
                    scrollTo("Save securely")
                    // Identify the row this test just created, not "whatever is selected": any
                    // profile another class left behind also becomes the selection when a selection
                    // already exists, so `.selected` reported a foreign provider's model (measured:
                    // "expected manual-ui-model but was persisted-OPENAI").
                    val before = app.settings.state.first().profiles.map { it.id }.toSet()
                    click("Save securely")
                    val profile =
                        withTimeout(8000) {
                            app.settings.state
                                .first { state -> state.profiles.any { it.id !in before } }
                                .profiles
                                .first { it.id !in before }
                        }
                    profileId = profile.id
                    assertEquals("manual-ui-model", profile.selectedModelId)
                    assertEquals(type, profile.type)
                    scrollTo("Saved securely.")
                    await("Saved securely.")
                    // Use explicit settings Back, not keyevent that may dismiss IME first.
                    repeat(10) {
                        if (find("Back") == null) {
                            shell("input swipe 233 170 233 310 400")
                            SystemClock.sleep(400)
                        }
                    }
                    click("Back")
                    await("UI controlled provider")
                    await("Keyboard input")
                    click("Menu")
                    await("Providers / model")
                    shell("input swipe 233 220 233 135 400")
                    scrollTo("New chat")
                    click("New chat")
                    await("Ask anything")
                    click("Keyboard input")
                    await("Prompt")
                    val editable = awaitEditable()
                    check(
                        editable.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "Watch keyboard prompt",
                                )
                            },
                        ),
                    )
                    if (type ==
                        ProviderType.ANTHROPIC
                    ) {
                        server.enqueue(MockResponse().setBody("""{"max_tokens":32000}"""))
                    }
                    val frames =
                        when (type) {
                            ProviderType.OPENAI_COMPATIBLE -> {
                                listOf(
                                    """{"choices":[{"delta":{"content":"Watch response verified"}}]}""",
                                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                                )
                            }

                            ProviderType.OPENAI -> {
                                listOf(
                                    """{"type":"response.output_text.delta","delta":"Watch response verified"}""",
                                    """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Watch response verified"}]}]}}""",
                                )
                            }

                            ProviderType.GEMINI -> {
                                listOf(
                                    """{"candidates":[{"content":{"parts":[{"text":"Watch response verified"}]},"finishReason":"STOP"}]}""",
                                )
                            }

                            ProviderType.ANTHROPIC -> {
                                listOf(
                                    """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
                                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Watch response verified"}}""",
                                    """{"type":"content_block_stop","index":0}""",
                                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                                    """{"type":"message_stop"}""",
                                )
                            }
                        }
                    server.enqueue(
                        MockResponse()
                            .setHeader(
                                "Content-Type",
                                "text/event-stream",
                            ).setBody(
                                frames.joinToString("") {
                                    "data: $it\n\n"
                                },
                            ),
                    )
                    click("Send")
                    try {
                        await("Watch response verified")
                    } catch (failure: IllegalStateException) {
                        shell("screencap -p /sdcard/nirowear-${type.name.lowercase()}-failure.png")
                        shell("uiautomator dump /sdcard/nirowear-${type.name.lowercase()}-failure.xml")
                        val rows =
                            app.database
                                .chats()
                                .conversations()
                                .first()
                                .firstOrNull()
                                ?.let {
                                    app.database.chats().snapshot(it.id)
                                }.orEmpty()
                        android.util.Log.e(
                            "ProviderSetupUiTest",
                            "${type.name}: requests=${server.requestCount}, rows=${rows.map {
                                "${it.role}:${it.status}:${it.rawContent.length}"
                            }}",
                        )
                        throw failure
                    }
                    SystemClock.sleep(500)
                    val answerBounds = android.graphics.Rect()
                    await("Watch response verified").getBoundsInScreen(answerBounds)
                    assertTrue(
                        "Short answer beginning hidden: $answerBounds",
                        answerBounds.top >= 84 && answerBounds.bottom <= 306,
                    )
                    val conversation =
                        app.database
                            .chats()
                            .conversations()
                            .first()
                            .first()
                    conversationId = conversation.id
                    val messages =
                        withTimeout(8000) {
                            app.database.chats().messages(conversation.id).first {
                                it.lastOrNull()?.status ==
                                    "SUCCESS"
                            }
                        }
                    assertEquals("Watch keyboard prompt", messages.first().rawContent)
                    assertEquals(profile.id, messages.last().providerProfileId)
                    shell("screencap -p /sdcard/nirowear-${type.name.lowercase()}-chat.png")
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    profileId?.let { app.settings.delete(it) }
                    conversationId?.let { app.database.chats().deleteConversation(it) }
                }
            }
        }

    private fun awaitEditable(): AccessibilityNodeInfo {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            node.refresh()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            editable(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Prompt editor missing")
    }
}
