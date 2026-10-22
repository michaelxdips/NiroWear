package com.nirowear.renderer

import android.graphics.drawable.Drawable

// ponytail: native library proof debug-only; resolve license before shipping typesetting.
object NativeMathRenderer : MathRenderer {
    override suspend fun render(source: String, textSizePx: Float): Drawable? = null
}
