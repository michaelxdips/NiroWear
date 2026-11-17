package com.nirowear.renderer

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererArPluralTest {
    @Test fun arabicFallbackUsesAsciiDigitsInTheEnglishPlural() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }).resources
        // No values-ar exists, so the English plural is the fallback. The sentence is English, so its
        // count must be ASCII: `getQuantityString` alone formats with the READER's locale and produced
        // "٢ more cells were dropped" on the physical watch (#49). This asserts the helper the UI
        // actually calls, not the platform formatter.
        val cases = mapOf(
            1 to "… one more cell was dropped: this row has more cells than the header.",
            2 to "… 2 more cells were dropped: those rows have more cells than the header.",
            3 to "… 3 more cells were dropped: those rows have more cells than the header.",
            11 to "… 11 more cells were dropped: those rows have more cells than the header.",
        )
        for ((count, expected) in cases) {
            assertEquals(expected, hiddenCellsText(resources, count))
        }
    }
}
