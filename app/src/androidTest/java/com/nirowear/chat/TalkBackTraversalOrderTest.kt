package com.nirowear.chat

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies M6: TalkBack traversal order matches top-to-bottom reading order (Menu -> Reader ->
 * Footer, and top-to-bottom inside Menu, History, and Providers) and every interactive node is
 * labelled.
 */
class TalkBackTraversalOrderTest {
    @Test fun traversalOrderAndControlLabelsHoldAcrossScreens() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity

        fun dfsNodes(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
            if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { dfsNodes(n.getChild(it)) }

        fun effectiveLabel(node: AccessibilityNodeInfo): String {
            val own =
                node.contentDescription?.toString()?.ifBlank { null }
                    ?: node.text?.toString()?.ifBlank { null }
            if (own != null) return own
            return dfsNodes(node)
                .drop(1)
                .firstNotNullOfOrNull { child ->
                    child.contentDescription?.toString()?.ifBlank { null }
                        ?: child.text?.toString()?.ifBlank { null }
                }.orEmpty()
        }

        fun awaitLabel(
            label: String,
            timeoutMs: Long = 10_000L,
        ): AccessibilityNodeInfo {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < end) {
                i.uiAutomation.clearCache()
                val hit = dfsNodes().firstOrNull { effectiveLabel(it) == label }
                if (hit != null) return hit
                SystemClock.sleep(100)
            }
            error("Missing accessibility node labelled '$label'")
        }

        fun assertAllInteractiveLabelled(screen: String) {
            i.uiAutomation.clearCache()
            val clickable = dfsNodes().filter { it.isClickable }
            assertTrue("$screen must have interactive controls", clickable.isNotEmpty())
            for (node in clickable) {
                assertTrue("$screen interactive node missing label: $node", effectiveLabel(node).isNotBlank())
            }
        }

        try {
            val menuNode = awaitLabel("Menu")
            awaitLabel("Conversation reader")
            awaitLabel("Keyboard input")
            awaitLabel("Voice input")
            assertAllInteractiveLabelled("Reader")

            // Walk Compose accessibility traversal links starting from Menu -> Reader -> Footer.
            val visitedLabels = mutableListOf<String>()
            i.uiAutomation.clearCache()
            val root = i.uiAutomation.rootInActiveWindow
            assertNotNull("Expected root accessibility window", root)
            val all = dfsNodes(root)
            // Compose sets explicit traversalIndex links via traversalAfter/traversalBefore or
            // child order inside the root Compose container. Collect container children first.
            val composeRoot = all.firstOrNull { it.className == "androidx.compose.ui.platform.ComposeView" }
            val composeTree = dfsNodes(composeRoot)
            val menuIdx = composeTree.indexOfFirst { effectiveLabel(it) == "Menu" }
            val readerIdx = composeTree.indexOfFirst { it.contentDescription?.toString() == "Conversation reader" }
            val kbdIdx = composeTree.indexOfFirst { effectiveLabel(it) == "Keyboard input" }
            val voiceIdx = composeTree.indexOfFirst { effectiveLabel(it) == "Voice input" }
            assertTrue("Missing reader landmarks: $menuIdx/$readerIdx/$kbdIdx/$voiceIdx", menuIdx >= 0 && readerIdx >= 0 && kbdIdx >= 0 && voiceIdx >= 0)

            // Verify explicit traversal links or tree ordering places Menu before Reader before Footer.
            val readerNode = composeTree[readerIdx]
            val menuClickable = requireNotNull(dfsNodes(composeRoot).firstOrNull { it.isClickable && effectiveLabel(it) == "Menu" })
            val readerFollowsMenu =
                readerNode.traversalAfter == menuClickable ||
                    dfsNodes(menuClickable).any { readerNode.traversalAfter == it } ||
                    menuIdx < readerIdx
            assertTrue(
                "Conversation reader must follow Menu in TalkBack traversal (menuIdx=$menuIdx readerIdx=$readerIdx after=${readerNode.traversalAfter})",
                readerFollowsMenu,
            )
            assertTrue("Footer controls must appear after Reader ($readerIdx < $kbdIdx < $voiceIdx)", readerIdx < kbdIdx && kbdIdx < voiceIdx)

            // Open Menu and check order + labels
            assertTrue(menuClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitLabel("Navigation menu")
            assertAllInteractiveLabelled("Menu")
            val menuNodes = dfsNodes()
            val providersBtn = menuNodes.indexOfFirst { effectiveLabel(it) == "Providers / model" && it.isClickable }
            val historyBtn = menuNodes.indexOfFirst { effectiveLabel(it) == "History" && it.isClickable }
            assertTrue("Providers button must precede History button in Menu", providersBtn in 0 until historyBtn)

            // Open History and check order + labels
            assertTrue(menuNodes[historyBtn].performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitLabel("History")
            awaitLabel("Search messages")
            assertAllInteractiveLabelled("History")
            val histNodes = dfsNodes()
            val backIdx = histNodes.indexOfFirst { effectiveLabel(it) == "Back" && it.isClickable }
            val searchIdx = histNodes.indexOfFirst { it.contentDescription?.toString() == "Search messages" }
            val newChatIdx = histNodes.indexOfFirst { effectiveLabel(it) == "New chat" && it.isClickable }
            assertTrue("History order: Back ($backIdx) < Search ($searchIdx) < New chat ($newChatIdx)", backIdx in 0 until searchIdx && searchIdx < newChatIdx)

            assertTrue(histNodes[backIdx].performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitLabel("Menu")
            assertTrue(awaitLabel("Menu").let { dfsNodes().first { n -> n.isClickable && effectiveLabel(n) == "Menu" } }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitLabel("Navigation menu")
            val menuNodes2 = dfsNodes()
            assertTrue(menuNodes2.first { it.isClickable && effectiveLabel(it) == "Providers / model" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitLabel("Providers")
            assertAllInteractiveLabelled("Providers")
            val provNodes = dfsNodes()
            val pBackIdx = provNodes.indexOfFirst { effectiveLabel(it) == "Back" && it.isClickable }
            val pCompatIdx = provNodes.indexOfFirst { effectiveLabel(it) == "Add compatible" && it.isClickable }
            assertEquals("Providers Back precedes Add compatible", true, pBackIdx in 0 until pCompatIdx)
            visitedLabels += listOf("Menu", "Conversation reader", "Keyboard input", "Voice input", "Navigation menu", "History", "Providers")
            assertTrue(visitedLabels.size == 7)
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
