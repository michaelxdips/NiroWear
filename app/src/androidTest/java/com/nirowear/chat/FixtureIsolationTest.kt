package com.nirowear.chat

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureIsolationTest {
    @Test fun debugFixtureSurvivesColdActivityLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture =
            context.assets
                .open("RENDER_TORTURE_FIXTURE.md")
                .bufferedReader()
                .use { it.readText() }
        assertTrue(fixture.contains("FINAL-READER-MARKER"))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { assertTrue(!it.isFinishing) }
        }
    }
}
