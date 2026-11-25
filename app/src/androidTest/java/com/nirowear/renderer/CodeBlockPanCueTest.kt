package com.nirowear.renderer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.nirowear.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeBlockPanCueTest {
    private val i = InstrumentationRegistry.getInstrumentation()

    private fun nodes(root: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private fun freshNodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return nodes()
    }

    private fun shell(cmd: String): String =
        i.uiAutomation.executeShellCommand(cmd).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }

    private fun awaitNode(
        what: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            freshNodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $what")
    }

    private fun countSlateCuePixels(
        bitmap: Bitmap,
        bounds: Rect,
        rightEdge: Boolean,
    ): Int {
        val xStart = if (rightEdge) (bounds.right - 8).coerceAtLeast(bounds.left) else bounds.left
        val xEnd = if (rightEdge) bounds.right.coerceAtMost(bitmap.width) else (bounds.left + 8).coerceAtMost(bitmap.width)
        val yStart = (bounds.top + 8).coerceAtLeast(0)
        val yEnd = (bounds.bottom - 8).coerceAtMost(bitmap.height)
        var count = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val px = bitmap.getPixel(x, y)
                val r = AndroidColor.red(px)
                val g = AndroidColor.green(px)
                val b = AndroidColor.blue(px)
                if (b - r >= 18 && b >= 140 && g >= 115) {
                    count++
                }
            }
        }
        return count
    }

    private fun savePng(
        bitmap: Bitmap,
        name: String,
    ) {
        val dir = i.targetContext.getExternalFilesDir(null) ?: return
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun narrowCodeDrawsNoCueAndWideCodeTogglesEdgeCuesWithPanAndVerticalScroll() {
        val narrowSource = "val x = 1"
        val wideSource = "val longCode = \"START-${"abcdefghij".repeat(12)}-END\""
        val chunkSource = "    println(\"CHUNK-${"1234567890".repeat(8)}-TAIL\")"

        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
        try {
            i.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item { DocumentBlock(ContentBlock.Code("", narrowSource)) }
                                item { DocumentBlock(ContentBlock.Code("", wideSource)) }
                                item { RenderUnitView(RenderUnit.CodeChunk("chunk-0", 0, null, listOf(chunkSource))) }
                                item { Text("Tail marker after code") }
                            }
                        }
                    }
                }
            }

            val narrowNode = awaitNode("narrow code") { it.text?.toString() == narrowSource }
            val wideNode = awaitNode("wide code") { it.text?.toString() == wideSource }
            val chunkNode = awaitNode("chunk code") { it.text?.toString() == chunkSource }

            val narrowBounds = Rect().also(narrowNode::getBoundsInScreen)
            val wideBounds = Rect().also(wideNode::getBoundsInScreen)
            val chunkBounds = Rect().also(chunkNode::getBoundsInScreen)

            fun findHorizontalScroller(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                var p: AccessibilityNodeInfo? = n
                while (p != null) {
                    val hasHorizAction =
                        p.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id ||
                                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                        }
                    if (hasHorizAction && Rect().also(p::getBoundsInScreen).height() < 160) {
                        return p
                    }
                    p = p.parent
                }
                return null
            }

            // Narrow block must not expose horizontal forward scroll or any edge cue.
            assertEquals(
                "Narrow block must not scroll horizontally",
                null,
                findHorizontalScroller(narrowNode),
            )

            // Wide block and RenderUnit.CodeChunk must scroll horizontally forward.
            assertNotNull("Wide code should expose horizontal forward scroll", findHorizontalScroller(wideNode))
            assertNotNull("CodeChunk should expose horizontal forward scroll", findHorizontalScroller(chunkNode))

            SystemClock.sleep(300)
            val startShot = i.uiAutomation.takeScreenshot()
            try {
                savePng(startShot, "m4-code-start")
                assertEquals(
                    "Narrow code left strip must have 0 cue pixels",
                    0,
                    countSlateCuePixels(startShot, narrowBounds, rightEdge = false),
                )
                assertEquals(
                    "Narrow code right strip must have 0 cue pixels",
                    0,
                    countSlateCuePixels(startShot, narrowBounds, rightEdge = true),
                )
                val wideRightStart = countSlateCuePixels(startShot, wideBounds, rightEdge = true)
                val wideLeftStart = countSlateCuePixels(startShot, wideBounds, rightEdge = false)
                val chunkRightStart = countSlateCuePixels(startShot, chunkBounds, rightEdge = true)
                assertTrue("Wide code must draw right pan cue before scroll (got $wideRightStart px)", wideRightStart >= 12)
                assertEquals("Wide code must not draw left cue before scroll", 0, wideLeftStart)
                assertTrue("CodeChunk must draw right pan cue before scroll (got $chunkRightStart px)", chunkRightStart >= 12)

                // Crop pixels before pan to prove visual movement too
                val beforeCrop = IntArray(wideBounds.width() * wideBounds.height())
                startShot.getPixels(
                    beforeCrop,
                    0,
                    wideBounds.width(),
                    wideBounds.left,
                    wideBounds.top,
                    wideBounds.width(),
                    wideBounds.height(),
                )

                // Swipe horizontally to pan wide code all the way to the end
                repeat(12) {
                    shell("input swipe 360 ${wideBounds.centerY()} 90 ${wideBounds.centerY()} 300")
                    SystemClock.sleep(120)
                }
                SystemClock.sleep(300)

                val endShot = i.uiAutomation.takeScreenshot()
                try {
                    savePng(endShot, "m4-code-end")
                    val afterCrop = IntArray(wideBounds.width() * wideBounds.height())
                    endShot.getPixels(
                        afterCrop,
                        0,
                        wideBounds.width(),
                        wideBounds.left,
                        wideBounds.top,
                        wideBounds.width(),
                        wideBounds.height(),
                    )
                    assertFalse("Horizontal pan must change code pixels", beforeCrop.contentEquals(afterCrop))

                    val wideLeftEnd = countSlateCuePixels(endShot, wideBounds, rightEdge = false)
                    val wideRightEnd = countSlateCuePixels(endShot, wideBounds, rightEdge = true)
                    assertTrue("Wide code scrolled to end must show left cue (got $wideLeftEnd px)", wideLeftEnd >= 12)
                    assertEquals("Wide code scrolled to end must hide right cue", 0, wideRightEnd)
                } finally {
                    endShot.recycle()
                }
            } finally {
                startShot.recycle()
            }

            // Vertical scroll must still advance the LazyColumn past the code block
            val yTouch = wideBounds.centerY().coerceIn(100, 360)
            shell("input swipe 233 $yTouch 233 ${yTouch - 110} 400")
            SystemClock.sleep(400)
            val afterWideTop = freshNodes().firstOrNull { it.text?.toString() == wideSource }?.let { Rect().also(it::getBoundsInScreen).top }
            assertTrue(
                "Vertical swipe over wide code must scroll reader (was=${wideBounds.top}, after=$afterWideTop)",
                afterWideTop == null || afterWideTop < wideBounds.top - 20,
            )
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
