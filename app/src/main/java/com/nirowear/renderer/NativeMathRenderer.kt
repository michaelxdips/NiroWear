package com.nirowear.renderer

import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable

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

    override suspend fun render(
        source: String,
        textSizePx: Float,
    ): Drawable? =
        withContext(Dispatchers.Default) {
            if (!NativeMathGuard.isSafe(source)) return@withContext null

            mutex.withLock {
                try {
                    JLatexMathDrawable
                        .builder(source)
                        .textSize(textSizePx)
                        .color(Color.WHITE)
                        .padding(4)
                        .build()
                        .takeIf {
                            // Bounds are checked after layout because the library computes them there;
                            // the guard above is what keeps the input small enough to be worth laying out.
                            it.intrinsicWidth in 1..8192 && it.intrinsicHeight in 1..2048
                        }
                } catch (_: RuntimeException) {
                    null
                } catch (_: StackOverflowError) {
                    // A pathological formula must degrade to source text, not take the app down.
                    null
                }
            }
        }
}
