package com.nirowear.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

/**
 * The small round action the reader shows over the transcript: a retry arrow after a failure, or a
 * jump-to-latest arrow when the user has scrolled away from the stream.
 *
 * Moved verbatim out of MainActivity (Task 4.1). The body is byte-identical apart from indentation;
 * this is a move, not a rewrite.
 */
@Composable
internal fun ReaderAction(
    retry: Boolean,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = if (retry) "Retry response" else "Jump to latest" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val path = Path()
            if (retry) {
                drawArc(
                    color,
                    45f,
                    285f,
                    false,
                    topLeft = Offset(size.width * .15f, size.height * .15f),
                    size = Size(size.width * .7f, size.height * .7f),
                    style = Stroke(stroke),
                )
                path.moveTo(size.width * .85f, size.height * .08f)
                path.lineTo(size.width * .85f, size.height * .36f)
                path.lineTo(size.width * .57f, size.height * .36f)
            } else {
                path.moveTo(size.width * .5f, size.height * .12f)
                path.lineTo(size.width * .5f, size.height * .85f)
                path.moveTo(size.width * .2f, size.height * .55f)
                path.lineTo(size.width * .5f, size.height * .85f)
                path.lineTo(size.width * .8f, size.height * .55f)
            }
            drawPath(path, color, style = Stroke(stroke))
        }
    }
}
