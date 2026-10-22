package com.nirowear.renderer

import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.noties.jlatexmath.JLatexMathDrawable

object NativeMathRenderer : MathRenderer {
    private val mutex = Mutex() // JLaTeXMath maintains shared parser/font state.

    override suspend fun render(source: String, textSizePx: Float): Drawable? = withContext(Dispatchers.Default) {
        // Untrusted model output must not access files, define macros, or allocate huge canvases.
        if (source.length > 4096 || source.count { it == '{' } > 128 ||
            Regex("\\\\(?:includegraphics|input|include|newcommand|renewcommand|def|newenvironment|rule|scalebox|resizebox)\\b").containsMatchIn(source)) return@withContext null
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
