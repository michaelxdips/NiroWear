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
        val activity = launch()
        try {
            click("Keyboard input")
            val editor = nodes().first { it.isEditable }
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
            i.runOnMainSync { activity.finish() }
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
