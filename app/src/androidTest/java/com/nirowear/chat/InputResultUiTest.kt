package com.nirowear.chat

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Activity-result fixtures prove app handling, not real microphone recognition. */
class InputResultUiTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val dao get() = (i.targetContext.applicationContext as NiroApplication).database.chats()

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
        val prior = runBlocking { dao.readComposerDraft() }
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
            runBlocking { dao.saveComposerDraft(prior.orEmpty()) }
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
            val prior = dao.readComposerDraft()
            val text = "Recovered after relaunch"
            try {
                dao.saveComposerDraft("")
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
                    assertEquals("Draft must be on disk once the editor closed", text, dao.readComposerDraft())
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
                dao.saveComposerDraft(prior.orEmpty())
            }
        }

    private fun setDraftText(text: String) {
        assertTrue(
            awaitEditable().performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                },
            ),
        )
    }

    @Test fun failedUserInsertDoesNotConsumeSavedDraft(): Unit =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val db = app.database.openHelper.writableDatabase
            val prior = dao.readComposerDraft()
            val text = "Unsent after rejected insert"
            val activity = launch()
            try {
                dao.saveComposerDraft(text)
                db.execSQL(
                    "CREATE TRIGGER i4_reject_user BEFORE INSERT ON messages " +
                        "WHEN NEW.role = 'USER' AND NEW.rawContent = '$text' " +
                        "BEGIN SELECT RAISE(ABORT, 'i4 rejected insert'); END",
                )
                click("Keyboard input")
                assertEquals(text, awaitEditable().text.toString())
                var sendOwner = await("Send")
                while (!sendOwner.isClickable) sendOwner = checkNotNull(sendOwner.parent)
                assertTrue(sendOwner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Keyboard input")
                assertEquals("Failed USER insert must leave the exit-saved draft intact", text, dao.readComposerDraft())
                val dao = app.database.chats()
                assertTrue(dao.conversations().first().none { chat -> dao.snapshot(chat.id).any { it.rawContent == text } })
            } finally {
                i.runOnMainSync { activity.finish() }
                db.execSQL("DROP TRIGGER IF EXISTS i4_reject_user")
                dao.saveComposerDraft(prior.orEmpty())
            }
        }

    @Test fun recreationDoesNotRestoreOverAnEmptiedField(): Unit =
        runBlocking {
            val prior = dao.readComposerDraft()
            val activity = launch()
            val monitor = i.addMonitor(MainActivity::class.java.name, null, false)
            var recreated: Activity? = null
            try {
                dao.saveComposerDraft("Previously saved draft")
                click("Keyboard input")
                assertEquals("Previously saved draft", awaitEditable().text.toString())
                setDraftText("")
                awaitEditable()
                i.runOnMainSync { activity.recreate() }
                recreated = checkNotNull(i.waitForMonitorWithTimeout(monitor, 6000))
                click("Keyboard input")
                val text = awaitEditable().text?.toString().orEmpty()
                assertTrue("Recreation restored erased text: $text", text.isEmpty())
            } finally {
                i.removeMonitor(monitor)
                i.runOnMainSync { (recreated ?: activity).finish() }
                dao.saveComposerDraft(prior.orEmpty())
            }
        }

    @Test fun backCommitSurvivesCancellationBeforeUiResumes(): Unit =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val prior = dao.readComposerDraft()
            val queued = LinkedBlockingQueue<Runnable>()
            val dispatcher =
                object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        queued.add(block)
                    }
                }
            try {
                dao.saveComposerDraft("Old saved draft", "before")
                val vm = withContext(Dispatchers.Main) { ChatViewModel(app).also { it.loadComposerDraft() } }
                val back =
                    withContext(Dispatchers.Main) {
                        CoroutineScope(dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                            vm.saveComposerDraft("Committed on Back")
                        }
                    }
                val resume = checkNotNull(queued.poll(10, TimeUnit.SECONDS)) { "Room did not resume Back" }
                assertEquals("Committed on Back", dao.readComposerDraft())
                back.cancel()
                i.runOnMainSync(resume)
                while (!back.isCompleted) {
                    i.runOnMainSync(checkNotNull(queued.poll(10, TimeUnit.SECONDS)))
                }
                val restored =
                    withContext(Dispatchers.Main) {
                        vm.loadComposerDraft()
                        vm.composerField = TextFieldValue("Next live edit")
                        vm.composerLive = true
                        val state = SavedStateHandle(mapOf("composer" to vm.composerTaskSnapshot()))
                        ChatViewModel(app, state).also { it.loadComposerDraft() }.composerField.text
                    }
                assertEquals("Next live edit", restored)
            } finally {
                dao.saveComposerDraft(prior.orEmpty())
            }
        }

    @Test fun taskSnapshotYieldsToNewerDurableDraftAndCommittedSend(): Unit =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val prior = dao.readComposerDraft()
            val id = "i4-task-snapshot-fixture"
            suspend fun restore(snapshot: Bundle): String =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val vm = ChatViewModel(app, androidx.lifecycle.SavedStateHandle(mapOf("composer" to snapshot)))
                    vm.loadComposerDraft()
                    vm.composerField.text
                }
            try {
                dao.saveComposerDraft("Old saved draft", "before")
                val snapshot = Bundle().apply {
                    putString("text", "Latest pending edit")
                    putString("revision", "before")
                }
                assertEquals("Latest pending edit", restore(snapshot))
                dao.saveComposerDraft("Latest pending edit", "send")
                assertEquals("Latest pending edit", restore(snapshot))
                dao.ensureConversation(com.nirowear.data.Conversation(id, "I4 fixture", 1))
                dao.recordFailure(id, "Latest pending edit", null, "fixture", consumeComposerDraft = true)
                assertEquals("", restore(snapshot))
            } finally {
                dao.deleteConversation(id)
                dao.saveComposerDraft(prior.orEmpty())
            }
        }

    @Test fun failedRestoreKeepsRecognizedSpeechForNextEntry(): Unit =
        runBlocking {
            val app = i.targetContext.applicationContext as NiroApplication
            val db = app.database.openHelper.writableDatabase
            val prior = dao.readComposerDraft()
            val activity = launch()
            val monitor =
                i.addMonitor(
                    IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    Instrumentation.ActivityResult(
                        Activity.RESULT_OK,
                        Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("continued by voice")),
                    ),
                    true,
                )
            try {
                dao.saveComposerDraft("Saved prompt")
                db.execSQL("ALTER TABLE composer_draft RENAME TO i4_unreadable_draft")
                try {
                    click("Voice input")
                    await("Draft not saved. Try again.")
                    assertNull(find("Send"))
                } finally {
                    db.execSQL("ALTER TABLE i4_unreadable_draft RENAME TO composer_draft")
                }
                click("Keyboard input")
                assertEquals("Saved prompt continued by voice", awaitEditable().text.toString())
                assertEquals("Saved prompt", dao.readComposerDraft())
            } finally {
                i.removeMonitor(monitor)
                i.runOnMainSync { activity.finish() }
                dao.saveComposerDraft(prior.orEmpty())
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
