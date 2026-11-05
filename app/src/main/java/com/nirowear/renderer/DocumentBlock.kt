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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.nirowear.R

// FSI (U+2068) / PDI (U+2069) isolate mixed-direction inline spans.
private const val BIDI_ISOLATE_OPEN = "\u2068"
private const val BIDI_ISOLATE_CLOSE = "\u2069"

// A paragraph direction marker keeps the paragraph's own direction even when
// every visible character is hidden inside FSI/PDI isolates.
private fun paragraphDirectionMarker(content: List<InlineContent>): String {
    val text = content.joinToString("") { it.text }
    if (text.isEmpty()) return ""
    var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        val direction = Character.getDirectionality(point)
        if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        ) {
            return "\u200F"
        }
        if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return "\u200E"
        }
        index += Character.charCount(point)
    }
    return ""
}

/**
 * True when a link target can actually be opened by the system handler.
 *
 * Uses `java.net.URI`, not `android.net.Uri`: this is a pure decision, it has to be unit-testable on
 * the JVM, and the Android stub throws for every URI method there. A target that does not parse is
 * not openable, which is the safe direction — the renderer then does not paint it as tappable.
 */
internal fun isOpenableLink(target: String): Boolean =
    try {
        val uri = java.net.URI(target)
        uri.scheme?.lowercase() in setOf("https", "http") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    } catch (_: java.net.URISyntaxException) {
        false
    }

internal fun openHttpLink(
    context: android.content.Context,
    target: String,
): String? {
    if (!isOpenableLink(target)) return context.getString(R.string.renderer_link_cannot_open)
    val uri = target.toUri()
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        null
    } catch (
        _: ActivityNotFoundException,
    ) {
        context.getString(R.string.renderer_link_no_app)
    } catch (_: SecurityException) {
        context.getString(R.string.renderer_link_cannot_open)
    }
}

/**
 * Rows a single table may render. A table composes inside one LazyColumn item, so its cost is paid
 * in full on every measure; 40 rows is already more than fits on a 466 px watch face.
 */
private const val MAX_TABLE_ROWS = 40

/** ponytail: 6 link buttons per paragraph; the rest stay inline in the text. */
private const val MAX_LINK_ACTIONS = 6

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
            // 16dp per level, and a FIXED marker column. 8dp per level with an intrinsic-width marker
            // put a depth-1 child bullet to the LEFT of its parent's text column (parent text starts at
            // marker + 6dp ≈ 12dp for "•", the child bullet at 8dp), so nesting read backwards. The
            // fixed column also lines up an item's continuation paragraph, which carries no marker.
            val depthPadding = (block.depth.coerceAtMost(4) * 16).dp
            Row(
                modifier = Modifier.padding(start = depthPadding).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    block.marker,
                    fontSize = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.widthIn(min = 22.dp),
                )
                RichText(block.content, modifier = Modifier.weight(1f))
            }
        }

        is ContentBlock.Quote -> {
            val quoteLabel = LocalResources.current.getString(R.string.renderer_quote)
            // One label, on the merged quote. The decorative bar used to carry its OWN
            // contentDescription, so TalkBack stopped on a 2dp bar labelled "Quote" and then announced
            // the same label again on the text node next to it. The label stays on the parent because
            // QuoteSemanticsTest requires a localized label on the node that holds the text.
            Row(
                // IntrinsicSize.Min so the bar spans the WHOLE quote: a fixed 24dp bar covered only the
                // first line of any multi-line quote, and a multi-paragraph quote showed detached
                // dashes.
                Modifier
                    .height(IntrinsicSize.Min)
                    .semantics(mergeDescendants = true) { contentDescription = quoteLabel },
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF99AABB))
                        .clearAndSetSemantics {},
                )
                RichText(
                    block.content,
                    Modifier.padding(start = 8.dp),
                )
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
            // A table is ONE LazyColumn item, so every row composes and measures in a single pass.
            // A 1000x4 table from a model becomes ~8000 Text nodes at once — seconds of main-thread
            // work and an ANR candidate on a watch. Cap the rendered rows and say so explicitly;
            // silently dropping rows would read as "the table ended here", which is worse.
            val shown = block.rows.take(MAX_TABLE_ROWS)
            val hidden = block.rows.size - shown.size
            val resources = LocalResources.current
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (block.rows.isEmpty()) block.headers.forEach { RichText(it, heading = 4) }
                if (hidden > 0) {
                    RichText(
                        listOf(
                            InlineContent(
                                resources.getQuantityString(R.plurals.renderer_hidden_rows, hidden, hidden),
                            ),
                        ),
                        // NOT a heading: level 4 is bold at body size, so the truncation note looked
                        // like one more table header instead of a note about missing rows.
                        heading = null,
                    )
                }
                // The rows follow the notice on purpose: at 40 rows (~70dp each) the notice sat ten
                // screens below the header, so a reader saw a table that simply ended.
                shown.forEachIndexed { rowIndex, cells ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cells.forEachIndexed { index, cell ->
                            val label = block.headers.getOrNull(index).orEmpty()
                            // Heading semantics only on the FIRST row's labels. The label is repeated
                            // per row so a watch reader keeps the column association, but marking
                            // every repetition as a heading gave TalkBack ~120 heading stops for a
                            // 40x3 table, all reading "Name / Price / Qty".
                            if (label.isNotEmpty()) RichText(label, heading = if (rowIndex == 0) 4 else null)
                            RichText(cell)
                        }
                    }
                    // No rule after the LAST row: a trailing rule read as a table that continued below.
                    if (rowIndex < shown.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF5F646C)))
                    }
                }
                if (block.droppedCells > 0) {
                    RichText(
                        listOf(
                            InlineContent(
                                resources.getQuantityString(
                                    R.plurals.renderer_hidden_cells,
                                    block.droppedCells,
                                    block.droppedCells,
                                ),
                            ),
                        ),
                        heading = null,
                    )
                }
            }
        }

        ContentBlock.Divider -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF5F646C)))
        }
    }
}

@Composable
private fun RichText(
    content: List<InlineContent>,
    modifier: Modifier = Modifier,
    heading: Int? = null,
) {
    val links = content.mapNotNull { it.link }.filter { isOpenableLink(it) }.distinct()
    val linkActions = links.isNotEmpty()
    if (content.none { it.math }) {
        if (linkActions) {
            Column {
                InlineRichText(content, List(content.size) { null }, heading, modifier)
                LinkActions(links)
            }
            return
        }
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

            // 3 and 4 used to share the `else` branch, so an H3 was visually identical to body text
            // and an H4 differed from it only by weight.
            3 -> 17.sp

            4 -> 15.sp

            else -> 16.sp
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.toPx() }
        // ponytail: memoize per (content, maxWidth, drawables); recompute only when spans, width,
        // heading size or a typeset formula arrives.
        //
        // `math` is a KEY, not just a captured value. rememberMath is a produceState that yields null
        // on its first composition, so on the first pass every formula was measured from its raw
        // LaTeX source (never what gets drawn) and that wrong width was cached for the life of the
        // item. A formula wider than the screen stayed inline with no pan affordance, and a narrow one
        // was hoisted onto its own row for nothing. Keying on the drawables recomputes once the real
        // widths exist.
        val wide =
            remember(content, width, heading, math) {
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
            }
        if (wide.isEmpty()) {
            if (linkActions) {
                Column {
                    InlineRichText(content, math, heading)
                    LinkActions(links)
                }
            } else {
                InlineRichText(content, math, heading)
            }
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
                if (linkActions) LinkActions(links)
            }
        }
    }
}

@Composable
private fun LinkActions(links: List<String>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val groupLabel = resources.getString(R.string.renderer_links)
    // Same bound as the table: one 48dp full-width Button per distinct link means a paragraph with
    // twenty links stacked ~960dp of buttons into a single lazy item, several screens of scrolling on
    // a 466px display, after the reader had already traversed the same links inline. The remainder is
    // still reachable inside the text itself where the link is inline.
    val shown = links.take(MAX_LINK_ACTIONS)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = false) { contentDescription = groupLabel },
    ) {
        shown.forEach { link ->
            val label = resources.getString(R.string.renderer_open_link, link)
            Button(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = label
                            onClick(label) {
                                openHttpLink(context, link)?.let {
                                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                }
                                true
                            }
                        },
                onClick = {
                    openHttpLink(context, link)?.let {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(link, maxLines = 2) }
        }
        if (links.size > shown.size) {
            Text(
                resources.getQuantityString(
                    R.plurals.renderer_hidden_links,
                    links.size - shown.size,
                    links.size - shown.size,
                ),
                fontSize = 12.sp,
            )
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
            append(paragraphDirectionMarker(content))
            content.forEachIndexed { index, span ->
                if (math[index] != null) {
                    appendInlineContent("math$index", span.text)
                    return@forEachIndexed
                }
                // Paint a link only when it can actually be opened. Relative links, bare domains,
                // mailto: and javascript: used to render blue and underlined with no click action,
                // so the reader was told "this is tappable" and nothing happened.
                val openable = span.link?.let { isOpenableLink(it) } == true
                val style =
                    SpanStyle(
                        fontWeight = if (span.bold || heading != null) FontWeight.Bold else null,
                        fontStyle = if (span.italic || span.math) FontStyle.Italic else null,
                        fontFamily = if (span.code) FontFamily.Monospace else null,
                        background = if (span.code) Color(0xFF25282D) else Color.Unspecified,
                        color = if (openable) Color(0xFFA8C7FA) else Color.Unspecified,
                        textDecoration = if (openable) TextDecoration.Underline else null,
                    )
                withStyle(style) {
                    val link = span.link
                    // Isolate mixed-direction URLs and code from surrounding text.
                    val isolated = BIDI_ISOLATE_OPEN + span.text + BIDI_ISOLATE_CLOSE
                    if (openable && link != null) {
                        // ponytail: inline links have no guaranteed 48dp target; add
                        // separate accessible actions when they preserve paragraph flow.
                        withLink(
                            LinkAnnotation.Clickable(link) {
                                openHttpLink(
                                    context,
                                    link,
                                )?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            },
                        ) { append(isolated) }
                    } else {
                        append(isolated)
                    }
                }
            }
        }
    val size =
        when (heading) {
            1 -> 20.sp

            2 -> 18.sp

            // 3 and 4 used to share the `else` branch, so an H3 was visually identical to body text
            // and an H4 differed from it only by weight.
            3 -> 17.sp

            4 -> 15.sp

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
