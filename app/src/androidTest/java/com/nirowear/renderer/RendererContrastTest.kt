package com.nirowear.renderer

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import com.nirowear.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererContrastTest {
    @Test fun actualReaderColorsMeetContrastTargets() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val latch = CountDownLatch(1)
        var content = Color.Unspecified
        try {
            i.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        val color = LocalContentColor.current
                        LaunchedEffect(color) { content = color; latch.countDown() }
                    }
                }
            }
            assertTrue("Theme color unavailable", latch.await(10, TimeUnit.SECONDS))
            fun luminance(color: Color): Double {
                fun linear(x: Float): Double = if (x <= 0.04045f) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
                return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
            }
            fun contrast(foreground: Color, background: Color): Double {
                val a = luminance(foreground); val b = luminance(background)
                return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
            }
            val colors = listOf(
                "text" to content,
                "inline code" to Color.White,
                "link" to Color(0xFFA8C7FA),
                "code language" to Color(0xFFAACCCC),
                "math" to Color.White,
                "quote bar" to Color(0xFF99AABB),
                "divider" to Color(0xFF5F646C),
            )
            val details = mutableListOf<String>()
            for ((name, color) in colors) {
                val ratio = contrast(color, Color.Black)
                details.add("$name=${color} background=black ratio=$ratio")
                val required = if (name == "quote bar" || name == "divider") 3.0 else 4.5
                if (name == "divider") assertEquals("divider ratio must match the shipped colour", ratio, contrast(Color(0xFF5F646C), Color.Black), 1e-9)
                assertTrue("$name contrast $ratio below $required", ratio >= required)
            }
            val inlineCode = contrast(Color.White, Color(0xFF25282D))
            details.add("inline-code-background ratio=$inlineCode")
            assertTrue("Inline code contrast too low", inlineCode >= 4.5)
            i.sendStatus(0, android.os.Bundle().apply { putString("contrast", details.joinToString("; ")) })
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
