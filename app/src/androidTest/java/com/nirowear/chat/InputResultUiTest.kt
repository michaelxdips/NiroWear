package com.nirowear.chat

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.ComposerDraft
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Activity-result fixtures prove app handling, not real microphone recognition. */
class InputResultUiTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

    private fun find(label: String): AccessibilityNodeInfo? {
        i.uiAutomation.clearCache()
        return nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }

    private fun await(label: String): AccessibilityNodeInfo {
        repeat(60) {
            find(label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $label")
    }

    /**
     * The compose field is editable, but on API 34 the IME window can be the active root, so a single
     * `rootInActiveWindow` snapshot finds nothing and `first { it.isEditable }` throws. Scan every
     * window and wait, exactly as a screen reader would.
     */
    private fun awaitEditable(): AccessibilityNodeInfo {
        repeat(100) {
            i.uiAutomation.clearCache()
            val roots =
                i.uiAutomation.windows.mapNotNull { it.root } +
                    listOfNotNull(i.uiAutomation.rootInActiveWindow)
            roots.asSequence().flatMap { nodes(it).asSequence() }
                .firstOrNull { it.isEditable }
                ?.let { return it }
            SystemClock.sleep(100)
        }
        error("No editable field appeared in any window")
    }

    /** Coordinate tap: the icon boxes are clickable Compose boxes whose semantics node exposes no
     *  ACTION_CLICK, so the accessibility action is not a valid trigger here. */
    private fun click(label: String) {
        val bounds = android.graphics.Rect().also(await(label)::getBoundsInScreen)
        i.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use {
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(it)
                .readBytes()
        }
        SystemClock.sleep(500)
    }

    private fun launch() =
        i.startActivitySync(
            Intent(i.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

    @Test fun unsentKeyboardDraftSurvivesBackAndReentry() {
        // Snapshot whatever this device already had; the draft is durable, so this test must not
        // decide what the NEXT test starts with.
        val prior = runBlocking { ComposerDraft.read(i.targetContext) }
        val activity = launch()
        try {
            click("Keyboard input")
            val editor = awaitEditable()
            assertTrue(
                editor.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            "Retained unsent draft",
                        )
                    },
                ),
            )
            i.runOnMainSync { activity.onBackPressed() }
            click("Keyboard input")
            await("Retained unsent draft")
        } finally {
            // Restore the snapshot exactly; an emptied key stays emptied.
            runBlocking { ComposerDraft.write(i.targetContext, prior.orEmpty()) }
            i.runOnMainSync { activity.finish() }
        }
    }

    /**
     * The draft must survive an explicit composer exit and a FRESH Activity, not just the same
     * Activity reopening: a new Activity has no `rememberSaveable` state, so the text can only come
     * back from the persisted slot. The composer is observed to CLOSE — its entry action reappears —
     * before the store is read, because the save is ordered before the close and reading first would
     * race it. The stored fixture is restored in the outer finally even when the first stage fails.
     */
    @Test fun unsentDraftSurvivesExplicitExitAndFreshRelaunch(): Unit =
        runBlocking {
            val prior = ComposerDraft.read(i.targetContext)
            val text = "Recovered after relaunch"
            try {
                ComposerDraft.write(i.targetContext, "")
                val first = launch()
                try {
                    click("Keyboard input")
                    val editor = awaitEditable()
                    assertTrue(
                        editor.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    text,
                                )
                            },
                        ),
                    )
                    i.runOnMainSync { first.onBackPressed() }
                    // A failed save leaves the editor open, so this await is the close assertion.
                    await("Keyboard input")
                    assertEquals("Draft must be on disk once the editor closed", text, ComposerDraft.read(i.targetContext))
                } finally {
                    i.runOnMainSync { first.finish() }
                }
                val second = launch()
                try {
                    click("Keyboard input")
                    await(text)
                } finally {
                    i.runOnMainSync { second.finish() }
                }
            } finally {
                // Restore the pre-test fixture even when the first stage failed.
                ComposerDraft.write(i.targetContext, prior.orEmpty())
            }
        }

    @Test fun delegatedResultsRemainEditableNeverAutoSubmitAndCancellationKeepsDraft() =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val before =
                app.database
                    .chats()
                    .conversations()
                    .first()
            val activity = launch()
            try {
                for ((code, data) in listOf(
                    Activity.RESULT_CANCELED to null,
                    Activity.RESULT_OK to
                        Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf()),
                    Activity.RESULT_OK to
                        Intent().putStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS,
                            arrayListOf(" "),
                        ),
                    Activity.RESULT_OK to null,
                )) {
                    val monitor =
                        i.addMonitor(
                            IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                            Instrumentation.ActivityResult(code, data),
                            true,
                        )
                    try {
                        click("Voice input")
                        await("Keyboard input")
                        assertNull(find("Send"))
                    } finally {
                        i.removeMonitor(monitor)
                    }
                }
                val monitor =
                    i.addMonitor(
                        IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                        Instrumentation.ActivityResult(
                            Activity.RESULT_OK,
                            Intent().putStringArrayListExtra(
                                RecognizerIntent.EXTRA_RESULTS,
                                arrayListOf("Recognized editable fixture"),
                            ),
                        ),
                        true,
                    )
                try {
                    click("Voice input")
                    await("Recognized editable fixture")
                    assertTrue(await("Send").isEnabled)
                    assertEquals(
                        "Recognition must not submit",
                        before,
                        app.database
                            .chats()
                            .conversations()
                            .first(),
                    )
                } finally {
                    i.removeMonitor(monitor)
                }
            } finally {
                i.runOnMainSync { activity.finish() }
            }
        }
}
