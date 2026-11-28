package com.nirowear.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SecretValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderValidationRaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * This test's contract is a disposable app with NO configured provider ("Editing provider B must
     * not re-target the next send" only means something when B is the only one). It used to assert
     * that precondition and fail, which made the result depend on whatever earlier classes had left
     * in DataStore — measured: `found: [239d5d95…/UI controlled provider, …]`. Purge instead of
     * assuming, through the DAO/settings API: `pm clear` kills the process instrumentation runs in.
     */
    @org.junit.Before
    fun startFromNoConfiguredProvider() =
        runBlocking {
            val app = instrumentation.targetContext.applicationContext as NiroApplication
            app.settings.state
                .first()
                .profiles
                .forEach { app.settings.delete(it.id) }
        }

    private fun find(
        value: String,
        node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
        editableOnly: Boolean = false,
    ): AccessibilityNodeInfo? {
        node ?: return null
        node.refresh()
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) {
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

    private fun fresh(
        value: String,
        editableOnly: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return find(value, editableOnly = editableOnly)
    }

    private fun swipe(up: Boolean) {
        val command = if (up) "input swipe 233 310 233 170 250" else "input swipe 233 170 233 310 250"
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .use { it.readBytes() }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun top() {
        repeat(8) { swipe(up = false) }
    }

    private fun seek(
        value: String,
        editableOnly: Boolean = false,
    ): AccessibilityNodeInfo {
        repeat(12) {
            fresh(value, editableOnly)?.let { return it }
            swipe(up = true)
        }
        error("UI element missing: $value")
    }

    private fun button(value: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = seek(value)
        while (node != null) {
            if (node.isClickable || node.className?.toString() == "android.widget.Button") return node
            node = node.parent
        }
        error("Button missing: $value")
    }

    private fun click(value: String) {
        val node = button(value)
        assertTrue("Button enabled: $value", node.isEnabled)
        assertTrue("Button activated: $value", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun enter(
        label: String,
        value: String,
    ) {
        val editor = seek(label, editableOnly = true)
        assertTrue("Editable input: $label", editor.isEditable)
        assertTrue(
            "Input changed: $label",
            editor.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            ),
        )
        instrumentation.waitForIdleSync()
        if (!editor.isPassword) {
            val end = SystemClock.uptimeMillis() + 3000
            while (SystemClock.uptimeMillis() < end && fresh(label, editableOnly = true)?.text?.toString() != value) {
                SystemClock.sleep(100)
            }
            assertEquals("Current input: $label", value, fresh(label, editableOnly = true)?.text?.toString())
        }
    }

    private fun awaitMessage(value: String) {
        // Result text sits right above "Validate · uses API" (which awaitValidationIdle just scrolled to).
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            if (fresh(value) != null) return
            SystemClock.sleep(100)
        }
        top()
        repeat(12) {
            if (fresh(value) != null) return
            swipe(up = true)
        }
        error("Validation result missing: $value")
    }

    private fun awaitValidationIdle() {
        top()
        seek("Validate · uses API")
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            if (button("Validate · uses API").isEnabled) return
            SystemClock.sleep(100)
        }
        error("Validation did not settle")
    }

    private fun success() =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
        )

    @Test fun editedNameOrModelRejectsInFlightValidationWithoutPromotingOrShowingStaleSuccess() =
        runBlocking {
            val app = instrumentation.targetContext.applicationContext as NiroApplication
            val preexisting = app.settings.state.first().profiles
            assertTrue(
                "Requires guarded disposable app with no configured providers, found: " +
                    preexisting.map { "${it.id}/${it.displayName}" },
                preexisting.isEmpty(),
            )
            MockWebServer().use { server ->
                server.start()
                val profile =
                    ProviderProfile(
                        UUID.randomUUID().toString(),
                        ProviderType.OPENAI_COMPATIBLE,
                        "Validation race fixture",
                        server.url("/v1").toString(),
                        "race-model-before",
                        UUID.randomUUID().toString(),
                    )
                var activity: Activity? = null
                var release = CountDownLatch(0)
                try {
                    app.settings.save(profile, SecretValue("controlled-validation-original"))
                    val saved =
                        app.settings.state
                            .first()
                            .profiles
                            .single { it.id == profile.id }
                    activity =
                        instrumentation.startActivitySync(
                            Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    click("Menu")
                    click("Providers / model")
                    click("Edit / replace key")
                    enter("API key", "controlled-validation-replacement")
                    val connected = "Connected. Selected model generated successfully."
                    for ((label, value) in listOf(
                        "Name" to "Validation race changed",
                        "Manual model ID" to "race-model-after",
                    )) {
                        release = CountDownLatch(1)
                        val responseRelease = release
                        server.dispatcher =
                            object : Dispatcher() {
                                override fun dispatch(request: RecordedRequest): MockResponse {
                                    check(
                                        responseRelease.await(45, TimeUnit.SECONDS),
                                    ) { "Validation response not released" }
                                    return success()
                                }
                            }
                        top()
                        click("Validate · uses API")
                        val request = server.takeRequest(8, TimeUnit.SECONDS)
                        assertNotNull("Validation request started before edit: $label", request)
                        assertEquals("POST", request!!.method)
                        assertEquals("/v1/chat/completions", request.path)
                        assertTrue(
                            "Validation uses replacement input",
                            request.getHeader("Authorization") == "Bearer controlled-validation-replacement",
                        )
                        assertTrue(
                            "Request uses original model",
                            request.body.readUtf8().contains("\"model\":\"race-model-before\""),
                        )
                        top()
                        enter(label, value)
                        top()
                        assertFalse("Save disabled while validation runs", button("Save securely").isEnabled)
                        release.countDown()
                        awaitValidationIdle()
                        awaitMessage("Inputs changed. Validate current settings again.")
                        assertNull("Stale success must not be shown: $label", fresh(connected))
                        top()
                        assertTrue("Validation settled: $label", button("Validate · uses API").isEnabled)
                        // Save is ADVISORY since 0.1.2 (`enabled = !busy && ready`), so an edited,
                        // unvalidated replacement is saveABLE by design; this used to assert it
                        // stayed disabled, which pinned the old gate and contradicted the screen's
                        // own "Saving is allowed; the first send reports any problem." The invariant
                        // that survives is that nothing was saved or promoted BEHIND the user.
                        assertTrue("Advisory save after edit: $label", button("Save securely").isEnabled)
                        assertEquals(
                            "No profile or credential promotion",
                            saved,
                            app.settings.state.first().profiles.single {
                                it.id ==
                                    profile.id
                            },
                        )
                    }
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest) = success()
                        }
                    top()
                    click("Validate · uses API")
                    awaitValidationIdle()
                    awaitMessage(connected)
                    top()
                    assertTrue("Unchanged current inputs can validate replacement", button("Save securely").isEnabled)
                    val currentRequest = server.takeRequest(8, TimeUnit.SECONDS)
                    assertNotNull("Current validation request", currentRequest)
                    assertTrue(
                        "Current validation uses edited model",
                        currentRequest!!.body.readUtf8().contains("\"model\":\"race-model-after\""),
                    )
                    assertEquals("Only explicit validation requests", 3, server.requestCount)
                } finally {
                    release.countDown()
                    try {
                        instrumentation.runOnMainSync { activity?.finish() }
                    } finally {
                        app.settings.delete(profile.id)
                    }
                }
            }
        }
}
