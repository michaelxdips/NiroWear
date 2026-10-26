package com.nirowear.renderer

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

@Composable
fun rememberMath(
    source: String,
    complete: Boolean = true,
): Drawable? {
    val px = with(LocalDensity.current) { 18.sp.toPx() }
    return produceState<Drawable?>(null, source, complete, px) {
        value = if (complete) NativeMathRenderer.render(source, px) else null
    }.value
}

@Composable
fun MathContent(
    source: String,
    complete: Boolean,
) {
    val drawable = rememberMath(source, complete)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (drawable == null) {
            Text(source, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        } else {
            MathCanvas(drawable, source)
        }
    }
}

@Composable
fun MathCanvas(
    drawable: Drawable,
    source: String,
) {
    val density = LocalDensity.current
    Canvas(
        Modifier
            .size(
                with(density) { drawable.intrinsicWidth.toDp() },
                with(density) { drawable.intrinsicHeight.toDp() },
            ).semantics { contentDescription = "Math: $source" },
    ) {
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
