package com.nirowear.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Square module grid, quiet zone included, row-major. */
internal class QrMatrix(
    val size: Int,
    private val dark: BooleanArray,
) {
    fun isDark(
        x: Int,
        y: Int,
    ): Boolean = dark[y * size + x]
}

/**
 * The pairing QR, drawn from the same capability URL the phone page is served from.
 *
 * Deliberately not hand-rolled: a QR encoder is a spec-sized piece of code, and a bug in it produces
 * a code that looks plausible and scans nowhere. ZXing core is a pure-JVM artifact (no Android
 * dependency, ~340 KB, Apache-2.0) and — the reason it is worth the dependency — its READER lets the
 * test decode the pixels this module actually renders, so the claim "this QR scans" is measured
 * rather than asserted.
 *
 * Rendering is integer-scaled on purpose: [sidePx] chooses a whole number of pixels per module, so
 * the drawn code never lands on half pixels, which is what turns a scannable code into a blurry one.
 */
internal object QrCode {
    /** Quiet zone in modules, per the QR spec (4 on every side). */
    private const val MARGIN = 4

    private val HINTS =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to MARGIN,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )

    fun matrix(text: String): QrMatrix {
        // Width and height 0 make ZXing choose the smallest matrix for the content; combined with
        // the MARGIN hint the result already includes the quiet zone.
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, HINTS)
        val size = bits.width
        val dark = BooleanArray(size * size) { i -> bits.get(i % size, i / size) }
        return QrMatrix(size, dark)
    }

    /** Largest whole-module side that fits [availablePx], never larger than the space it is given. */
    fun sidePx(
        matrix: QrMatrix,
        availablePx: Int,
    ): Int {
        if (availablePx <= 0) return 0
        val module = availablePx / matrix.size
        return module * matrix.size
    }
}
