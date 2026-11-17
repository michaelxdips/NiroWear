package com.nirowear.renderer

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import com.nirowear.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererHighContrastTest {
    @Test fun themeContentColorRemainsReadableWithHighContrastSetting() {
        val i = InstrumentationRegistry.getInstrumentation()
        val enabled = Settings.Secure.getInt(i.targetContext.contentResolver, "high_text_contrast_enabled", 0)
        // Setting is device state; measure theme contrast regardless and record it.
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val latch = CountDownLatch(1)
        var color = Color.Unspecified
        try {
            i.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        val content = LocalContentColor.current
                        LaunchedEffect(content) { color = content; latch.countDown() }
                    }
                }
            }
            assertTrue("Theme color unavailable", latch.await(10, TimeUnit.SECONDS))
            fun linear(x: Float): Double = if (x <= 0.04045f) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            val luminance = 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
            val ratio = (luminance + 0.05) / 0.05
            i.sendStatus(0, android.os.Bundle().apply { putString("highContrast", "enabled=$enabled color=$color background=black ratio=$ratio") })
            assertTrue("High-contrast theme text contrast $ratio below 4.5", ratio >= 4.5)
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
