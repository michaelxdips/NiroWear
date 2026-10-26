package com.nirowear.renderer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.wear.compose.material3.Text

internal fun openHttpLink(
    context: android.content.Context,
    target: String,
): String? {
    val uri = target.toUri()
    if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
        return "Link cannot be opened"
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        null
    } catch (
        _: ActivityNotFoundException,
    ) {
        "No app available to open link"
    } catch (_: SecurityException) {
        "Link cannot be opened"
    }
}

@Composable
fun DocumentBlock(block: ContentBlock) {
    when (block) {
        is ContentBlock.Paragraph -> {
            RichText(block.content)
        }

        is ContentBlock.Heading -> {
            RichText(block.content, heading = block.level)
        }

        is ContentBlock.ListEntry -> {
            Row(Modifier.padding(start = (block.depth.coerceAtMost(3) * 8).dp)) {
                Text(block.marker, modifier = Modifier.width(24.dp), fontSize = 16.sp)
                RichText(block.content, modifier = Modifier.weight(1f))
            }
        }

        is ContentBlock.Quote -> {
            Row {
                Box(Modifier.width(2.dp).height(24.dp).background(Color(0xFF99AABB)))
                RichText(block.content, Modifier.padding(start = 8.dp))
            }
        }

        is ContentBlock.Code -> {
            Column {
                if (block.language.isNotEmpty()) Text(block.language, fontSize = 12.sp, color = Color(0xFFAACCCC))
                Text(
                    block.source,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                )
            }
        }

        is ContentBlock.Math -> {
            MathContent(block.source, block.complete)
        }

        is ContentBlock.Table -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (block.rows.isEmpty()) block.headers.forEach { RichText(it, heading = 4) }
                block.rows.forEach { cells ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cells.forEachIndexed { index, cell ->
                            val label = block.headers.getOrNull(index).orEmpty()
                            if (label.isNotEmpty()) RichText(label, heading = 4)
                            RichText(cell)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                }
            }
        }

        ContentBlock.Divider -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
        }
    }
}

@Composable
private fun RichText(
    content: List<InlineContent>,
    modifier: Modifier = Modifier,
    heading: Int? = null,
) {
    if (content.none { it.math }) {
        InlineRichText(content, List(content.size) { null }, heading, modifier)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val math = content.map { if (it.math) rememberMath(it.text) else null }
    val fallbackSize =
        when (heading) {
            1 -> 20.sp
            2 -> 18.sp
            else -> 16.sp
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.toPx() }
        val wide =
            content.indices.filter { index ->
                content[index].math && (
                    math[index]?.intrinsicWidth?.toFloat()
                        ?: measurer
                            .measure(
                                content[index].text,
                                TextStyle(
                                    fontSize = fallbackSize,
                                    fontStyle = FontStyle.Italic,
                                    fontWeight =
                                        if (content[index].bold ||
                                            heading != null
                                        ) {
                                            FontWeight.Bold
                                        } else {
                                            null
                                        },
                                ),
                                softWrap = false,
                            ).size.width
                            .toFloat()
                ) >
                    width
            }
        if (wide.isEmpty()) {
            InlineRichText(content, math, heading)
        } else {
            Column {
                // ponytail: intact formula pan; upgrade to math-aware wrapping when renderer supports width.
                var start = 0
                wide.forEach { index ->
                    if (start <
                        index
                    ) {
                        InlineRichText(content.subList(start, index), math.subList(start, index), heading)
                    }
                    MathContent(content[index].text, complete = true)
                    start = index + 1
                }
                if (start <
                    content.size
                ) {
                    InlineRichText(content.subList(start, content.size), math.subList(start, content.size), heading)
                }
            }
        }
    }
}

@Composable
private fun InlineRichText(
    content: List<InlineContent>,
    math: List<Drawable?>,
    heading: Int?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val inline =
        math
            .mapIndexedNotNull { index, drawable ->
                drawable?.let {
                    "math$index" to
                        InlineTextContent(
                            Placeholder(
                                with(density) { it.intrinsicWidth.toSp() },
                                with(density) { it.intrinsicHeight.toSp() },
                                PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) { MathCanvas(drawable, content[index].text) }
                }
            }.toMap()
    val text =
        buildAnnotatedString {
            content.forEachIndexed { index, span ->
                if (math[index] != null) {
                    appendInlineContent("math$index", span.text)
                    return@forEachIndexed
                }
                val style =
                    SpanStyle(
                        fontWeight = if (span.bold || heading != null) FontWeight.Bold else null,
                        fontStyle = if (span.italic || span.math) FontStyle.Italic else null,
                        fontFamily = if (span.code) FontFamily.Monospace else null,
                        background = if (span.code) Color(0xFF25282D) else Color.Unspecified,
                        color = if (span.link != null) Color(0xFFA8C7FA) else Color.Unspecified,
                        textDecoration = if (span.link != null) TextDecoration.Underline else null,
                    )
                withStyle(style) {
                    val link = span.link
                    val uri = link?.toUri()
                    if (link != null && uri?.scheme?.lowercase() in setOf("https", "http")) {
                        withLink(
                            LinkAnnotation.Clickable(link) {
                                openHttpLink(
                                    context,
                                    link,
                                )?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            },
                        ) { append(span.text) }
                    } else {
                        append(span.text)
                    }
                }
            }
        }
    val size =
        when (heading) {
            1 -> 20.sp
            2 -> 18.sp
            else -> 16.sp
        }
    if (math.any { it != null }) {
        // Compose foundation text supplies inline native math placeholders.
        androidx.compose.foundation.text.BasicText(
            text,
            modifier =
                modifier.fillMaxWidth().semantics {
                    if (heading !=
                        null
                    ) {
                        heading()
                    }
                },
            inlineContent = inline,
            style = TextStyle(color = Color.White, fontSize = size, lineHeight = 22.sp),
        )
    } else {
        Text(
            text,
            modifier =
                modifier.fillMaxWidth().semantics {
                    if (heading !=
                        null
                    ) {
                        heading()
                    }
                },
            fontSize = size,
            lineHeight = 22.sp,
        )
    }
}
