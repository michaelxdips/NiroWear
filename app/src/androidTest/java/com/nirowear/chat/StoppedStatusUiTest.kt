package com.nirowear.chat

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import com.nirowear.NiroApplication
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StoppedStatusUiTest {
    @Test fun persistedStatusAndRetryRemainReachableAtBothScales() =
        runBlocking {
            val i = InstrumentationRegistry.getInstrumentation()
            val app = i.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val id = "stopped-ui-${UUID.randomUUID()}"
            dao.insertConversation(Conversation(id, "Stopped status fixture", System.currentTimeMillis() + 100000))
            dao.insertMessage(StoredMessage(conversationId = id, role = "USER", rawContent = "Stopped prompt"))
            dao.insertMessage(
                StoredMessage(
                    conversationId = id,
                    role = "ASSISTANT",
                    status = "STOPPED",
                    rawContent = "Retained partial answer remains readable.",
                    providerLabel = "Local",
                    modelId = "fixture",
                ),
            )

            fun nodes(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
                if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }

            // The renderer wraps every inline run in FSI/PDI plus a direction mark, so a plain sentence
            // reads as "⁨Stopped · partial answer kept⁩". Exact equality never matched — measured.
            fun norm(s: CharSequence?): String =
                s?.toString().orEmpty()
                    .replace("\u2068", "")
                    .replace("\u2069", "")
                    .replace("\u200E", "")
                    .replace("\u200F", "")

            fun shell(command: String) {
                i.uiAutomation.executeShellCommand(command).use {
                    android.os.ParcelFileDescriptor
                        .AutoCloseInputStream(it)
                        .readBytes()
                }
            }
            try {
                for (scale in listOf("1.0", "1.3")) {
                    shell("settings put system font_scale $scale")
                    val activity =
                        i.startActivitySync(
                            Intent(i.targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    try {
                        val status = "Stopped · partial answer kept"
                        var readStatus = false
                        var readPartial = false
                        var reachedRetry = false
                        var retryBounds = "none"
                        val statusBounds = linkedSetOf<String>()
                        // 40 steps of 30 px was not enough to reach the end of the transcript at
                        // font scale 1.3: the Retry action was measured to exist and to satisfy
                        // every reachability condition (Rect(48,150 - 144,246) h=96 inside viewport
                        // Rect(0,96 - 466,262)) only after ten further large swipes. The steps stay
                        // small, because a large one can scroll the whole status past a 166 px
                        // viewport, but the budget is raised until the list bottom is reachable.
                        repeat(120) {
                            SystemClock.sleep(250)
                            i.uiAutomation.clearCache()
                            val all = nodes()
                            val viewport =
                                all
                                    .firstOrNull { it.contentDescription?.toString() == "Conversation reader" }
                                    ?.let { Rect().also(it::getBoundsInScreen) }
                            val copies = all.filter { norm(it.text) == status }
                            assertTrue("No duplicate status", copies.size <= 1)
                            copies.firstOrNull()?.let {
                                val r = Rect().also(it::getBoundsInScreen)
                                statusBounds += "status=$r viewport=$viewport"
                                // Full two-line paragraph, not clipped accessibility bounds.
                                if (viewport != null && r.top > viewport.top &&
                                    r.height() >= (if (scale == "1.0") 80 else 100) &&
                                    r.bottom <= viewport.bottom
                                ) {
                                    readStatus = true
                                }
                            }
                            if (all.any { norm(it.text) == "Retained partial answer remains readable." }) {
                                readPartial =
                                    true
                            }
                            all.firstOrNull { it.contentDescription?.toString() == "Retry response" }?.let {
                                val r = Rect().also(it::getBoundsInScreen)
                                retryBounds = "$r h=${r.height()} viewport=$viewport"
                                if (viewport != null && r.height() >= 96 && r.top >= viewport.top &&
                                    r.bottom <= viewport.bottom
                                ) {
                                    reachedRetry = true
                                }
                            }
                            // Small steps cannot skip whole status inside 166px font1.3 viewport.
                            if (!readStatus || !reachedRetry) shell("input swipe 233 220 233 190 350")
                        }
                        assertTrue("Partial content retained $scale", readPartial)
                        assertTrue("Complete status reachable $scale; $statusBounds", readStatus)
                        assertTrue("Complete Retry target reachable $scale; last=$retryBounds", reachedRetry)
                    } finally {
                        i.runOnMainSync { activity.finish() }
                    }
                }
            } finally {
                shell("settings put system font_scale 1.0")
                dao.deleteConversation(id)
            }
        }
}
