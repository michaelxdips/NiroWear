package com.nirowear.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.nirowear.R

/**
 * Small round action shown over the transcript: retry after a failure, or jump-to-latest
 * when the user has scrolled away from the stream.
 */
@Composable
internal fun ReaderAction(
    retry: Boolean,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val description =
        stringResource(
            if (retry) R.string.reader_action_retry else R.string.reader_action_latest,
        )
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .drawWithCache {
                    val stroke = Stroke(2.dp.toPx())
                    val path =
                        Path().apply {
                            if (retry) {
                                moveTo(size.width * .85f, size.height * .08f)
                                lineTo(size.width * .85f, size.height * .36f)
                                lineTo(size.width * .57f, size.height * .36f)
                            } else {
                                moveTo(size.width * .5f, size.height * .12f)
                                lineTo(size.width * .5f, size.height * .85f)
                                moveTo(size.width * .2f, size.height * .55f)
                                lineTo(size.width * .5f, size.height * .85f)
                                lineTo(size.width * .8f, size.height * .55f)
                            }
                        }
                    val arcTopLeft = Offset(size.width * .15f, size.height * .15f)
                    val arcSize = Size(size.width * .7f, size.height * .7f)
                    onDrawBehind {
                        if (retry) {
                            drawArc(
                                color,
                                45f,
                                285f,
                                false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = stroke,
                            )
                        }
                        drawPath(path, color, style = stroke)
                    }
                },
        )
    }
}
