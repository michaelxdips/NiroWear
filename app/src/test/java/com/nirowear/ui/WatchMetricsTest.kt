package com.nirowear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMetricsTest {
    private val roundDiameters = listOf(192, 208, 225, 233, 240, 256)

    @Test
    fun headerAndFooterFitInsideCircularChordAcrossAllRoundWatchSizes() {
        for (d in roundDiameters) {
            val m = computeWatchMetrics(widthDp = d, heightDp = d, isRound = true)
            assertTrue("isRound must hold for $d", m.isRound)
            assertTrue("headerHeight >= 48dp for $d", m.headerHeightDp.value >= 48f)

            val headerTextMax = m.headerMaxWidthDp.value - 24f
            val headerChord = chordWidthDp(d.toFloat(), m.headerInnerTopDp.value)
            assertTrue(
                "Header text ($headerTextMax dp) exceeds chord ($headerChord dp) on ${d}dp dial",
                headerTextMax <= headerChord,
            )

            val twoChord = chordWidthDp(d.toFloat(), m.footerBottomTwoIconsDp.value)
            assertTrue(
                "2-icon footer (102dp) exceeds chord ($twoChord dp) at bottom=${m.footerBottomTwoIconsDp} on ${d}dp",
                102f <= twoChord,
            )

            val threeChord = chordWidthDp(d.toFloat(), m.footerBottomThreeIconsDp.value)
            assertTrue(
                "3-icon footer (156dp) exceeds chord ($threeChord dp) at bottom=${m.footerBottomThreeIconsDp} on ${d}dp",
                156f <= threeChord,
            )
        }
    }

    @Test
    fun reference466PxDialMaximizesReadingWidthAndSavesBottomSpace() {
        val m = computeWatchMetrics(widthDp = 233, heightDp = 233, isRound = true)
        assertFalse(m.compactChrome)
        assertEquals(14f, m.readerHorizontalDp.value, 0.01f)
        assertEquals(48f, m.headerHeightDp.value, 0.01f)
        assertEquals(16f, m.footerBottomTwoIconsDp.value, 0.01f)
        assertEquals(35f, m.footerBottomThreeIconsDp.value, 0.01f)
    }

    @Test
    fun squareWatchesUseTightMarginsInsteadOfCircularPadding() {
        val square = computeWatchMetrics(widthDp = 180, heightDp = 201, isRound = false)
        assertFalse(square.isRound)
        assertTrue(square.compactChrome)
        assertEquals(10f, square.readerHorizontalDp.value, 0.01f)
        assertEquals(8f, square.footerBottomTwoIconsDp.value, 0.01f)
        assertEquals(8f, square.footerBottomThreeIconsDp.value, 0.01f)
        assertEquals(12f, square.qrHorizontalPaddingDp.value, 0.01f)
    }
}
