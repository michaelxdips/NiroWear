package com.nirowear.renderer

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MathPanEndpointTest {
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).flatMap { root.getChild(it)?.let(::nodes).orEmpty() }

    @Test fun wideFixturesHaveDistinctEndpointsAndFittingFormulasStayNatural() {
        val i = InstrumentationRegistry.getInstrumentation()

        fun freshNodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
            return i.uiAutomation.rootInActiveWindow
                ?.let(::nodes)
                .orEmpty()
        }
        val fixture =
            i.targetContext.assets
                .open("RENDER_TORTURE_FIXTURE.md")
                .bufferedReader()
                .use { it.readText() }
        val formulas =
            fixture
                .lines()
                .filter { line ->
                    line.startsWith("Long fraction:") ||
                        line.startsWith("Nested radicals:") ||
                        line.startsWith("Long indices:") || line.startsWith("Vector equation:") ||
                        line.startsWith("Function signature:") ||
                        line.startsWith("Wide matrix:")
                }.map { it.substringAfter('$').substringBefore('$') }
        assertEquals(6, formulas.size)
        val activity =
            i.startActivitySync(
                Intent(i.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
        try {
            formulas.forEachIndexed { index, source ->
                android.util.Log.i("MathPanEndpointTest", "Fixture $index: $source")
                i.runOnMainSync {
                    activity.setContent {
                        key(source) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 60.dp)) {
                                DocumentBlock(
                                    ContentBlock.Paragraph(
                                        listOf(
                                            InlineContent("Before formula."),
                                            InlineContent(source, math = true),
                                            InlineContent("After formula."),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
                val deadline = SystemClock.uptimeMillis() + 10000
                var math: AccessibilityNodeInfo? = null
                while (SystemClock.uptimeMillis() < deadline) {
                    math = freshNodes().find { it.contentDescription?.toString() == activity.getString(com.nirowear.R.string.renderer_math_description, source) }
                    if (math != null) break
                    SystemClock.sleep(100)
                }
                if (math == null) {
                    val dir = File(i.targetContext.getExternalFilesDir(null), "math-node-failure").apply { mkdirs() }
                    val roots = i.uiAutomation.windows.mapNotNull { it.root }
                    File(dir, "fixture-$index.txt").writeText(
                        "Fixture $index: $source\n" +
                            roots.flatMap(::nodes).joinToString("\n") {
                                "package=${it.packageName} text=${it.text} description=${it.contentDescription} bounds=${Rect().also(
                                    it::getBoundsInScreen,
                                )}"
                            },
                    )
                    i.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(dir, "fixture-$index.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                assertNotNull("Missing Math accessibility node for fixture $index: $source", math)
                val bounds = Rect().also(math!!::getBoundsInScreen)
                var pan = math.parent
                while (pan != null && !pan.isScrollable) pan = pan.parent
                assertNotNull(pan)

                fun capture(stage: String): IntArray {
                    SystemClock.sleep(400)
                    var screenshot = i.uiAutomation.takeScreenshot()
                    val deadline = SystemClock.uptimeMillis() + 3000
                    while (screenshot == null && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(100)
                        screenshot = i.uiAutomation.takeScreenshot()
                    }
                    val bitmap = checkNotNull(screenshot) { "Screenshot unavailable for formula $index $stage" }
                    File(i.targetContext.getExternalFilesDir(null), "math-pan-$index-$stage.png").outputStream().use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                    val pixels = IntArray(bounds.width() * bounds.height())
                    bitmap.getPixels(
                        pixels,
                        0,
                        bounds.width(),
                        bounds.left,
                        bounds.top,
                        bounds.width(),
                        bounds.height(),
                    )
                    bitmap.recycle()
                    return pixels
                }
                val start = capture("start")
                val natural =
                    runBlocking {
                        NativeMathRenderer.render(
                            source,
                            android.util.TypedValue.applyDimension(
                                android.util.TypedValue.COMPLEX_UNIT_SP,
                                18f,
                                i.targetContext.resources.displayMetrics,
                            ),
                        )
                    }
                assertNotNull(natural)
                if (natural!!.intrinsicWidth <= 306) {
                    assertFalse(
                        "Fitting formula has no pan range",
                        pan!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                    )
                    // Fitting formula has no horizontal range. Record natural-layout evidence.
                    capture("fitting")
                    return@forEachIndexed
                }
                val touchBounds = Rect().also(pan!!::getBoundsInScreen)
                i.uiAutomation
                    .executeShellCommand(
                        "input swipe ${touchBounds.centerX() + 40} ${touchBounds.centerY()} ${touchBounds.centerX() - 40} ${touchBounds.centerY()} 600",
                    ).use {
                        android.os.ParcelFileDescriptor
                            .AutoCloseInputStream(it)
                            .readBytes()
                    }
                val middle = capture("middle")
                assertFalse("Touch pan moves formula $index", start.contentEquals(middle))
                repeat(12) {
                    val current = freshNodes().find { it.isScrollable }
                    current?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(100)
                }
                val end = capture("end")
                val endpoint = freshNodes().find { it.isScrollable }
                assertFalse("End reached", endpoint!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                assertFalse("Formula $index start/end pixels differ", start.contentEquals(end))
                // First page can reach endpoint for moderately wide formulas; never invent a third position.
                if (!middle.contentEquals(end)) assertFalse(start.contentEquals(middle))
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
