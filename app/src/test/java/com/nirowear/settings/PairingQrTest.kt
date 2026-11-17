package com.nirowear.settings

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing QR, decoded from the pixels this app draws rather than from the encoder's own matrix.
 *
 * The planner under test is the one the Compose canvas calls, so a pass here means the geometry the
 * watch paints at 466 px and at 384 px is scannable. That is the whole reason the ZXing artifact is a
 * dependency: an encoder test that only re-reads its own input proves nothing about a camera.
 */
class PairingQrTest {
    /** The shape a real session produces: LAN address, ephemeral port, 22-char token segment. */
    private val url = "https://192.168.8.209:43210/AbCdEfGhIjKlMnOpQrStUv/"

    /**
     * Rasterises exactly the way the canvas does — whole pixels per module, quiet zone included —
     * then hands the bitmap to a reader as if it were a camera frame.
     */
    private fun decodeRendered(
        matrix: QrMatrix,
        availablePx: Int,
    ): String {
        val side = QrCode.sidePx(matrix, availablePx)
        assertTrue("side $side must fit in $availablePx", side in 1..availablePx)
        assertEquals("modules must land on whole pixels", 0, side % matrix.size)
        val module = side / matrix.size
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (matrix.isDark(x / module, y / module)) pixels[y * side + x] = BLACK
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun scansAtEveryWidthTheWatchUses() {
        val matrix = QrCode.matrix(url)
        // 466 and 384 are the reference dial and the smallest one the UX contract allows, each less
        // the 26 dp horizontal content padding (52 px at the 2.0 density of a 320 dpi watch). 330
        // and 248 are the canvas widths measured from the device's accessibility bounds: the QR node
        // is 346 px wide at 466 and 264 px at 384, and the 4 dp inner padding takes 8 px per side.
        for (available in listOf(362, 330, 280, 248)) {
            assertEquals("decoded at ${available}px", url, decodeRendered(matrix, available))
        }
    }

    @Test
    fun smallestDialStillGetsLegibleModules() {
        val matrix = QrCode.matrix(url)
        val side = QrCode.sidePx(matrix, 248)
        val module = side / matrix.size
        // Under ~3 px per module a camera on a real screen starts failing; 4 is the floor this
        // layout is required to keep.
        assertTrue("module ${module}px at the measured 384px canvas", module >= 4)
    }

    @Test
    fun quietZoneStaysBlank() {
        val matrix = QrCode.matrix(url)
        for (i in 0 until matrix.size) {
            for (q in 0 until QUIET_ZONE) {
                assertFalse("top quiet zone", matrix.isDark(i, q))
                assertFalse("bottom quiet zone", matrix.isDark(i, matrix.size - 1 - q))
                assertFalse("left quiet zone", matrix.isDark(q, i))
                assertFalse("right quiet zone", matrix.isDark(matrix.size - 1 - q, i))
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** Matches [QrCode]'s MARGIN: the canvas draws the plate, the matrix already includes it. */
        const val QUIET_ZONE = 4
    }
}
