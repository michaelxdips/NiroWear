package com.nirowear.renderer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.MainActivity
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererLocalizationTest {
    private fun localized(context: Context, locale: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    @Test fun linkErrorsUseTheContextLocale() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for ((locale, badLink, noHandler) in listOf(
            Triple(Locale.ENGLISH, "Link cannot be opened", "No app available to open link"),
            Triple(Locale.forLanguageTag("id"), "Tautan tidak dapat dibuka", "Tidak ada aplikasi yang dapat membuka tautan"),
            Triple(Locale.forLanguageTag("ar"), "Link cannot be opened", "No app available to open link"),
        )) {
            val context = object : ContextWrapper(localized(base, locale)) {
                override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
            }
            assertEquals(badLink, openHttpLink(context, "https:missing-host"))
            assertEquals(noHandler, openHttpLink(context, "https://example.com"))
        }
    }

    @Test fun mathDescriptionUsesLocaleAndPreservesSource() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val source = "\\frac{α}{2} + 😀"
        try {
            for ((locale, expected) in listOf(
                Locale.ENGLISH to "Math: $source",
                Locale.forLanguageTag("id") to "Matematika: $source",
                Locale.forLanguageTag("ar") to "Math: $source",
            )) {
                val context = localized(activity, locale)
                i.runOnMainSync {
                    activity.setContent {
                        CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources) {
                            MathCanvas(GradientDrawable().apply { setSize(32, 32) }, source)
                        }
                    }
                }
                SystemClock.sleep(500)
                if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                val descriptions = nodes(i.uiAutomation.rootInActiveWindow).mapNotNull { it.contentDescription?.toString() }
                assertTrue("Missing localized math description for $locale: $descriptions", descriptions.contains(expected))
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }

    @Test fun hiddenCellNoticesUseLocaleAndQuantity() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("id"), Locale.forLanguageTag("ar"))) {
                val context = localized(activity, locale)
                for (count in listOf(0, 1, 2, 1000)) {
                    i.runOnMainSync {
                        activity.setContent {
                            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources) {
                                androidx.wear.compose.material3.MaterialTheme {
                                    DocumentBlock(ContentBlock.Table(emptyList(), emptyList(), droppedCells = count))
                                }
                            }
                        }
                    }
                    SystemClock.sleep(350)
                    if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
                    val texts = nodes(i.uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }
                        .map { it.replace("\u2068", "").replace("\u2069", "").replace("\u200E", "").replace("\u200F", "") }
                    if (count == 0) {
                        assertFalse("Zero should show no hidden-cell notice", texts.any { it.contains("cell") || it.contains("sel") })
                    } else {
                        // ASCII digits on purpose: the note falls back to English when no
                        // translation exists, and getQuantityString formatting with the
                        // READER's locale put Arabic-Indic digits inside an English sentence
                        // (#49). hiddenCellsText therefore formats with Locale.ENGLISH, and
                        // this expectation follows that decision instead of contradicting it.
                        val number =
                            NumberFormat.getIntegerInstance(Locale.ENGLISH).apply { isGroupingUsed = false }.format(count)
                        val singular = count == 1
                        val expected = if (locale == Locale.forLanguageTag("id")) {
                            if (singular) {
                                "… 1 sel dibuang: baris tersebut punya sel lebih banyak daripada judul tabel."
                            } else {
                                "… $number sel dibuang: baris tersebut punya sel lebih banyak daripada judul tabel."
                            }
                        } else if (singular) {
                            "… one more cell was dropped: this row has more cells than the header."
                        } else {
                            "… $number more cells were dropped: those rows have more cells than the header."
                        }
                        assertTrue("Missing '$expected' for $locale: $texts", texts.contains(expected))
                    }
                }
            }
        } finally {
            i.runOnMainSync { activity.finish() }
        }
    }
}
