package com.nirowear.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ColorScheme
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cohesive pure-OLED dark palette paired with NiroWear's teal thread accent. */
internal val NiroWearColorScheme =
    ColorScheme(
        primary = Color(0xFF7EE6CC),
        primaryDim = Color(0xFF56C2A8),
        primaryContainer = Color(0xFF0B5D51),
        onPrimary = Color(0xFF06201A),
        onPrimaryContainer = Color(0xFFE4FBF4),
        secondary = Color(0xFFA8C7FA),
        secondaryDim = Color(0xFF82ACEF),
        secondaryContainer = Color(0xFF1A3254),
        onSecondary = Color(0xFF08244F),
        onSecondaryContainer = Color(0xFFD6E5FF),
        surfaceContainerLow = Color(0xFF10141A),
        surfaceContainer = Color(0xFF161B22),
        surfaceContainerHigh = Color(0xFF1E252F),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFF9BA5AE),
        outline = Color(0xFF3A4450),
        outlineVariant = Color(0xFF232930),
        background = Color.Black,
        onBackground = Color.White,
        error = Color(0xFFFF8A80),
        onError = Color(0xFF2B0705),
    )

/**
 * Responsive layout geometry computed from screen dimensions and display shape so content maximizes
 * usable area on 384-480 px round dials as well as square Wear OS screens without bezel clipping.
 */
@Immutable
internal data class WatchMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val isRound: Boolean,
    val compactChrome: Boolean,
    val headerHeightDp: Dp,
    val headerInnerTopDp: Dp,
    val headerMaxWidthDp: Dp,
    val readerHorizontalDp: Dp,
    val listHorizontalDp: Dp,
    val menuHorizontalDp: Dp,
    val listVerticalDp: Dp,
    val composerHorizontalDp: Dp,
    val composerVerticalDp: Dp,
    val footerBottomTwoIconsDp: Dp,
    val footerBottomThreeIconsDp: Dp,
    val qrHorizontalPaddingDp: Dp,
)

internal fun chordWidthDp(
    diameterDp: Float,
    edgeOffsetDp: Float,
): Float {
    if (edgeOffsetDp <= 0f || edgeOffsetDp >= diameterDp) return 0f
    return 2f * sqrt((edgeOffsetDp * (diameterDp - edgeOffsetDp)).coerceAtLeast(0f))
}

internal fun minOffsetForWidthDp(
    diameterDp: Float,
    contentWidthDp: Float,
    extraMarginDp: Float,
): Float {
    val radius = diameterDp * 0.5f
    val halfWidth = (contentWidthDp * 0.5f).coerceAtMost(radius)
    val chordOffset = radius - sqrt((radius * radius - halfWidth * halfWidth).coerceAtLeast(0f))
    return chordOffset + extraMarginDp
}

internal fun computeWatchMetrics(
    widthDp: Int,
    heightDp: Int,
    isRound: Boolean,
): WatchMetrics {
    val safeW = widthDp.coerceAtLeast(160)
    val safeH = heightDp.coerceAtLeast(160)
    val minSide = minOf(safeW, safeH).toFloat()
    val compactChrome = safeH < 220

    if (!isRound) {
        return WatchMetrics(
            widthDp = safeW,
            heightDp = safeH,
            isRound = false,
            compactChrome = compactChrome,
            headerHeightDp = 48.dp,
            headerInnerTopDp = 10.dp,
            headerMaxWidthDp = (safeW - 24).coerceAtLeast(110).dp,
            readerHorizontalDp = 10.dp,
            listHorizontalDp = 10.dp,
            menuHorizontalDp = 12.dp,
            listVerticalDp = 12.dp,
            composerHorizontalDp = 12.dp,
            composerVerticalDp = 10.dp,
            footerBottomTwoIconsDp = 8.dp,
            footerBottomThreeIconsDp = 8.dp,
            qrHorizontalPaddingDp = 12.dp,
        )
    }

    val headerHeight = 48.dp
    val headerInnerTop = if (safeH < 200) 14f else 16f
    val chordAtHeader = chordWidthDp(minSide, headerInnerTop)
    // Add 24 dp outer horizontal padding around the inner text so the clickable pill fills cleanly
    // while text stays strictly inside the circular chord.
    val headerMaxWidth =
        ((chordAtHeader - 4f).coerceAtLeast(86f) + 24f)
            .roundToInt()
            .coerceIn(110, (safeW - 16).coerceAtLeast(110))
            .dp

    val readerHorizontal = (safeW * 0.06f).roundToInt().coerceIn(12, 18).dp
    val listHorizontal = (safeW * 0.085f).roundToInt().coerceIn(14, 24).dp
    val menuHorizontal = (safeW * 0.12f).roundToInt().coerceIn(20, 32).dp
    val listVertical = if (safeH < 200) 18.dp else 22.dp
    val composerHorizontal = if (compactChrome) 14.dp else 20.dp
    val composerVertical = if (compactChrome) 10.dp else 20.dp

    // Two 48 dp buttons + 6 dp gap = 102 dp; three buttons + 12 dp gaps = 156 dp.
    val twoIconBottom =
        ceil(minOffsetForWidthDp(minSide, 102f, 4f))
            .toInt()
            .coerceAtLeast(if (compactChrome) 12 else 16)
            .dp
    val threeIconBottom =
        ceil(minOffsetForWidthDp(minSide, 156f, 4f))
            .toInt()
            .coerceAtLeast(if (compactChrome) 28 else 35)
            .dp
    val qrPadding = (safeW * 0.10f).roundToInt().coerceIn(18, 28).dp

    return WatchMetrics(
        widthDp = safeW,
        heightDp = safeH,
        isRound = true,
        compactChrome = compactChrome,
        headerHeightDp = headerHeight,
        headerInnerTopDp = headerInnerTop.dp,
        headerMaxWidthDp = headerMaxWidth,
        readerHorizontalDp = readerHorizontal,
        listHorizontalDp = listHorizontal,
        menuHorizontalDp = menuHorizontal,
        listVerticalDp = listVertical,
        composerHorizontalDp = composerHorizontal,
        composerVerticalDp = composerVertical,
        footerBottomTwoIconsDp = twoIconBottom,
        footerBottomThreeIconsDp = threeIconBottom,
        qrHorizontalPaddingDp = qrPadding,
    )
}

@Composable
internal fun rememberWatchMetrics(): WatchMetrics {
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp
    val heightDp = config.screenHeightDp
    val isRound = config.isScreenRound
    return remember(widthDp, heightDp, isRound) {
        computeWatchMetrics(widthDp, heightDp, isRound)
    }
}

/**
 * Gently tapers wide center-aligned rows in the outer top/bottom 18% curves of a circular screen
 * while leaving vertical metrics and square watches untouched.
 */
internal fun Modifier.roundEdgeTaper(
    listState: LazyListState,
    key: Any,
    isRound: Boolean,
): Modifier =
    if (!isRound) {
        this
    } else {
        graphicsLayer {
            val info = listState.layoutInfo
            val viewportHeight = info.viewportSize.height.toFloat()
            if (viewportHeight <= 0f) return@graphicsLayer
            val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
            if (item.size > viewportHeight * 0.35f) return@graphicsLayer
            val center = item.offset + item.size * 0.5f
            val band = viewportHeight * 0.18f
            val edgeDist =
                when {
                    center < band -> ((band - center) / band).coerceIn(0f, 1f)
                    center > viewportHeight - band -> ((center - (viewportHeight - band)) / band).coerceIn(0f, 1f)
                    else -> 0f
                }
            if (edgeDist > 0f) {
                scaleX = 1f - edgeDist * 0.06f
                alpha = 1f - edgeDist * 0.12f
            }
        }
    }
