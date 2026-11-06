package com.nirowear.renderer

import android.graphics.drawable.Drawable

/** Library boundary. Null preserves readable source for unsupported/partial input. */
interface MathRenderer {
    suspend fun render(
        source: String,
        textSizePx: Float,
    ): Drawable?

    /**
     * An already-typeset result for this source and size, or null.
     *
     * The reader shows the raw LaTeX while [render] is in flight, so without a synchronous hit every
     * scroll-out/scroll-in re-typeset each formula and flashed its source for a frame. Default null
     * keeps the seam honest for any renderer that has no cache.
     */
    fun cached(
        source: String,
        textSizePx: Float,
    ): Drawable? = null
}
