package com.nirowear.renderer

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.nirowear.R

/**
 * The renderer the app draws with.
 *
 * Typed as [MathRenderer] on purpose: ARCHITECTURE.md requires the UI not to bind to one math
 * library, and this is that seam. The interface previously had one implementation, no type
 * reference, and a direct call to the singleton — so the abstraction was decorative.
 */
private val mathRenderer: MathRenderer = NativeMathRenderer

@Composable
fun rememberMath(
    source: String,
    complete: Boolean = true,
): Drawable? {
    val px = with(LocalDensity.current) { 18.sp.toPx() }
    // Seed from the cache: `render` always starts at null, so a formula that was already typeset
    // showed its raw LaTeX for one frame every time it scrolled back into view.
    return produceState<Drawable?>(
        initialValue = mathRenderer.cached(source, px),
        source,
        complete,
        px,
    ) {
        value = if (complete) mathRenderer.render(source, px) else null
    }.value
}

@Composable
fun MathContent(
    source: String,
    complete: Boolean,
    depth: Int = 0,
) {
    val drawable = rememberMath(source, complete)
    // A display inside a list item keeps the item's indentation. The bullet itself is gone — the
    // formula is extracted before the list is parsed — but a flush-left formula under an indented
    // paragraph reads as a different document.
    Column(Modifier.padding(start = (depth.coerceIn(0, 4) * 16).dp)) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (drawable == null) {
                Text(source, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            } else {
                MathCanvas(drawable, source)
            }
        }
    }
}

@Composable
fun MathCanvas(
    drawable: Drawable,
    source: String,
) {
    val density = LocalDensity.current
    val description = LocalResources.current.getString(R.string.renderer_math_description, source)
    Canvas(
        Modifier
            .size(
                with(density) { drawable.intrinsicWidth.toDp() },
                with(density) { drawable.intrinsicHeight.toDp() },
            ).semantics { contentDescription = description },
    ) {
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
