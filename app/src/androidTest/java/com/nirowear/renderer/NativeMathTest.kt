package com.nirowear.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeMathTest {
    @Test fun fixtureMathDrawsOnWearRuntime() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture =
                context.assets
                    .open("RENDER_TORTURE_FIXTURE.md")
                    .bufferedReader()
                    .use { it.readText() }
            val blocks = ResponseParser.parse(fixture)
            val inline =
                blocks
                    .filterIsInstance<ContentBlock.Paragraph>()
                    .flatMap { it.content }
                    .filter { it.math }
                    .map { it.text }
            assertTrue("Permanent wide inline coverage", inline.size >= 8)
            val formulas =
                blocks.filterIsInstance<ContentBlock.Math>().map { it.source } + inline +
                    listOf(
                        "E=mc^2",
                        "\\begin{aligned}a&=b+c\\\\d&=e-f\\end{aligned}",
                        "\\sum_{n=1}^{\\infty}\\frac{1}{n^2}=\\frac{\\pi^2}{6}",
                    )
            for (source in formulas) {
                val drawable = NativeMathRenderer.render(source, 36f)
                assertNotNull(source, drawable)
                drawable!!
                val bitmap =
                    Bitmap.createBitmap(
                        drawable.intrinsicWidth,
                        drawable.intrinsicHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(source, pixels.any { it != 0 })
                bitmap.recycle()
            }
            assertNull(NativeMathRenderer.render("\\includegraphics{/sdcard/private}", 36f))
            assertNull(NativeMathRenderer.render("\\frac{1", 36f))
        }
}
