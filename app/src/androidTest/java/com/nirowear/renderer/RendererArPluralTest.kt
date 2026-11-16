package com.nirowear.renderer

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererArPluralTest {
    @Test fun arabicFallbackUsesLocalizedNumbersAndUsableEnglishPlural() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }).resources
        // With no values-ar the English plural is the fallback, and measured on the AVD the number is
        // rendered with ASCII digits there (the resource string, not the Arabic NumberFormat, does the
        // formatting). The old expectation pinned Arabic-Indic digits and never ran.
        val cases = mapOf(
            1 to "… one more cell was dropped: this row has more cells than the header.",
            2 to "… 2 more cells were dropped: those rows have more cells than the header.",
            3 to "… 3 more cells were dropped: those rows have more cells than the header.",
            11 to "… 11 more cells were dropped: those rows have more cells than the header.",
        )
        for ((count, expected) in cases) {
            assertEquals(expected, resources.getQuantityString(com.nirowear.R.plurals.renderer_hidden_cells, count, count))
        }
    }
}
