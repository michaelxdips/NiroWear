package com.nirowear.renderer

import android.graphics.drawable.Drawable

/** Library boundary. Null preserves readable source for unsupported/partial input. */
interface MathRenderer {
    suspend fun render(source: String, textSizePx: Float): Drawable?
}
