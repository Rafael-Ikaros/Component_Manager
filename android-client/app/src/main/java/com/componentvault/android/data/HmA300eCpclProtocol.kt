package com.componentvault.android.data

import android.graphics.Bitmap

/** Experimental CPCL raster path; command structure checked against HPRT SDK V1.22.05,
 * but not verified with a physical HM-A300E.
 * HPRT declares CPCL and 72 mm/8 dots per mm. CG framing follows Zebra's CPCL reference.
 * No model/status query, permanent media configuration, or print-completion acknowledgment is assumed.
 */
internal object HmA300eCpclProtocol {
    const val MAX_WIDTH_DOTS = 576
    // Application label limit (300 mm), not a claim about printer buffer capacity.
    const val MAX_HEIGHT_DOTS = 2400

    fun parts(bitmap: Bitmap, continuous: Boolean): List<M1PrintPart> {
        require(bitmap.width in 1..MAX_WIDTH_DOTS && bitmap.height in 1..MAX_HEIGHT_DOTS)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return parts(bitmap.width, bitmap.height, pixels, continuous)
    }

    fun parts(width: Int, height: Int, argb: IntArray, continuous: Boolean): List<M1PrintPart> {
        require(width in 1..MAX_WIDTH_DOTS) { "unsupported_width" }
        require(height in 1..MAX_HEIGHT_DOTS && argb.size == width * height) { "invalid_pixels" }
        val rowBytes = (width + 7) / 8
        val raw = ByteArray(rowBytes * height)
        argb.forEachIndexed { index, color ->
            val alpha = color ushr 24 and 255
            val luminance = (color ushr 16 and 255) * 299 +
                (color ushr 8 and 255) * 587 + (color and 255) * 114
            if (alpha >= 128 && luminance < 128_000) {
                val x = index % width
                val offset = index / width * rowBytes + x / 8
                raw[offset] = (raw[offset].toInt() or (0x80 ushr (x % 8))).toByte()
            }
        }
        fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
        return buildList {
            // CPCL's documented 200-dpi session selector; dimensions remain physical 203-dpi dots.
            add(M1PrintPart(ascii("! 0 200 200 $height 1\r\nPAGE-WIDTH $width\r\nCG $rowBytes $height 0 0 ")))
            add(M1PrintPart(raw, isImageFrame = true))
            add(M1PrintPart(ascii("\r\n")))
            if (!continuous) add(M1PrintPart(ascii("FORM\r\n"), isFormFeed = true))
            add(M1PrintPart(ascii("PRINT\r\n")))
        }
    }
}
