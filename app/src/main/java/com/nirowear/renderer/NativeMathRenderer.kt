package com.nirowear.renderer

import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.sqrt

/**
 * Native LaTeX typesetting via JLaTeXMath.
 *
 * Licensing: JLaTeXMath is GPL v2-or-later **with an independent-module linking exception**
 * ("permission to link this library with independent modules to produce an executable, regardless
 * of the license terms of these independent modules, and to copy and distribute the resulting
 * executable under terms of your choice"). That exception is what makes it usable from this
 * MIT-licensed application. The GPL text, the exception, the bundled-font notices and the
 * corresponding-source offer ship in `assets/legal/` and are surfaced in-app.
 *
 * Safety: assistant output is untrusted, so admission is decided by [NativeMathGuard] before the
 * parser sees anything, and the result is bounds-checked. A refused formula falls back to readable
 * source text rather than rendering blank.
 */
object NativeMathRenderer : MathRenderer {
    private val mutex = Mutex() // JLaTeXMath maintains shared parser/font state.

    /**
     * Last results, most recently used last. Small on purpose: each entry is a bitmap, and a watch
     * gives us a few megabytes at most. Eight covers the formulas on one screen, which is what the
     * scroll-out/scroll-in flash needs; anything older re-typesets once.
     *
     * ponytail: 8 entries, in-memory only. Add a disk cache only if re-typesetting a long answer's
     * formulas after a process restart is proven to matter.
     */
    private const val CACHE_ENTRIES = 8

    // Bitmap bounds. Width alone is NOT the risk: a wide formula is exactly what the contract wants
    // panned rather than shrunk, so a bound below what a real 200-term fraction typesets at 18 sp
    // turned "pan it" into "refuse it" and dropped the answer back to raw LaTeX (measured: a 100-term
    // \frac needs >4096 px and lost its pan action). Width stays generous; HEIGHT and TOTAL PIXELS
    // are the actual allocation bound (~12 MB at 4 bpp), which is what no watch will happily hold.
    private const val MAX_WIDTH = 16_384
    private const val MAX_HEIGHT = 1024
    private const val MAX_PIXELS = 3_000_000L

    /** The last rung of the math contract ("modest scaling") — never smaller than this. */
    private const val MIN_SCALE = 0.5f

    private val cache =
        object : LinkedHashMap<Pair<String, Float>, Drawable>(CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Float>, Drawable>) =
                size > CACHE_ENTRIES
        }

    override fun cached(
        source: String,
        textSizePx: Float,
    ): Drawable? = synchronized(cache) { cache[source to textSizePx] }

    override suspend fun render(
        source: String,
        textSizePx: Float,
    ): Drawable? =
        withContext(Dispatchers.Default) {
            if (!NativeMathGuard.isSafe(source)) return@withContext null
            cached(source, textSizePx)?.let { return@withContext it }

            mutex.withLock {
                val first = layout(source, textSizePx) ?: return@withLock null
                if (fits(first)) return@withLock remember(source, textSizePx, first)
                // Outside the bound on any axis. The math contract's order is pan, then MODEST scaling,
                // and only then give up — refusing outright showed source text for a formula the
                // reader could have panned at a smaller size. The ratio takes the WORST axis and the
                // area into account, so a tall matrix is bounded too and not just a wide one.
                val ratio =
                    minOf(
                        MAX_WIDTH.toFloat() / first.intrinsicWidth,
                        MAX_HEIGHT.toFloat() / first.intrinsicHeight,
                        sqrt(MAX_PIXELS.toFloat() / (first.intrinsicWidth.toLong() * first.intrinsicHeight)),
                    )
                // Below the readability floor there is nothing left to scale TO, so an over-bound
                // formula goes straight to readable source text instead of a second allocation that
                // cannot fit either.
                if (ratio <= MIN_SCALE) return@withLock null
                val scaledSize = textSizePx * ratio.coerceIn(MIN_SCALE, 1f)
                if (scaledSize >= textSizePx) return@withLock null
                layout(source, scaledSize)?.takeIf { fits(it) }?.let { remember(source, textSizePx, it) }
            }
        }

    /** One typeset attempt. Exceptions are the library's; they mean "no rendering", not "crash". */
    private fun layout(
        source: String,
        textSizePx: Float,
    ): Drawable? =
        try {
            JLatexMathDrawable
                .builder(source)
                .textSize(textSizePx)
                .color(Color.WHITE)
                .padding(4)
                .build()
        } catch (_: RuntimeException) {
            null
        } catch (_: StackOverflowError) {
            // A pathological formula must degrade to source text, not take the app down.
            null
        } catch (_: OutOfMemoryError) {
            // Bounds are checked AFTER layout, so the allocation happens first: a formula that lays
            // out inside the new, watch-sized bounds can still ask for a bitmap the watch cannot give.
            // An Error escaping `produceState` kills the process, which is far worse than showing the
            // source text.
            null
        }

    private fun fits(drawable: Drawable): Boolean {
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        return width in 1..MAX_WIDTH &&
            height in 1..MAX_HEIGHT &&
            width.toLong() * height <= MAX_PIXELS
    }

    private fun remember(
        source: String,
        textSizePx: Float,
        drawable: Drawable,
    ): Drawable {
        synchronized(cache) { cache[source to textSizePx] = drawable }
        return drawable
    }
}
