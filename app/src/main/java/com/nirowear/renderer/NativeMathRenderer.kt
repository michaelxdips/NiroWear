package com.nirowear.renderer

import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Safety: assistant output is untrusted. File-inclusion macros, macro definitions and pathological
 * nesting are refused before the parser sees them, and the result is bounds-checked so a hostile
 * formula cannot allocate an enormous canvas.
 */
object NativeMathRenderer : MathRenderer {
    private val mutex = Mutex() // JLaTeXMath maintains shared parser/font state.

    private val forbidden = Regex(
        "\\\\(?:includegraphics|input|include|newcommand|renewcommand|def|newenvironment|rule|scalebox|resizebox)\\b"
    )

    override suspend fun render(source: String, textSizePx: Float): Drawable? = withContext(Dispatchers.Default) {
        // Untrusted model output must not access files, define macros, or allocate huge canvases.
        if (source.length > 4096 || source.count { it == '{' } > 128 || forbidden.containsMatchIn(source)) {
            return@withContext null
        }
        var depth = 0
        source.forEachIndexed { index, char ->
            if (index == 0 || source[index - 1] != '\\') {
                if (char == '{') depth++
                if (char == '}') depth--
                if (depth < 0) return@withContext null
            }
        }
        if (depth != 0) return@withContext null

        mutex.withLock {
            try {
                JLatexMathDrawable.builder(source).textSize(textSizePx).color(Color.WHITE)
                    .padding(4).build().takeIf {
                        it.intrinsicWidth in 1..8192 && it.intrinsicHeight in 1..2048
                    }
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
