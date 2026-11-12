package com.componentvault.android.data

import android.graphics.Bitmap

/** A single CPCL label using the documented binary CG raster representation. */
internal object CpclRasterJob {
    // Limits of the current 203-dpi renderer and label editor.
    private const val MAX_WIDTH_DOTS = 576
    private const val MAX_HEIGHT_DOTS = 2400

    fun encode(bitmap: Bitmap, media: LabelMediaType): ByteArray {
        validateDimensions(bitmap.width, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return encode(bitmap.width, bitmap.height, pixels, media)
    }

    fun encode(width: Int, height: Int, pixels: IntArray, media: LabelMediaType): ByteArray {
        validateDimensions(width, height)
        require(pixels.size == width * height)
        val rowBytes = (width + 7) / 8
        val raster = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val luminance = (color ushr 16 and 255) * 299 +
                    (color ushr 8 and 255) * 587 + (color and 255) * 114
                if (color ushr 24 >= 128 && luminance < 128_000) {
                    val index = y * rowBytes + x / 8
                    raster[index] = (raster[index].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        // The CPCL header selects 200 dpi, while the renderer uses the physical dot count.
        val header = "! 0 200 200 $height 1\r\nCG $rowBytes $height 0 0 ".toByteArray(Charsets.US_ASCII)
        val trailer = (if (media == LabelMediaType.Gap) "\r\nFORM\r\nPRINT\r\n" else "\r\nPRINT\r\n")
            .toByteArray(Charsets.US_ASCII)
        return header + raster + trailer
    }

    private fun validateDimensions(width: Int, height: Int) {
        require(width in 1..MAX_WIDTH_DOTS && height in 1..MAX_HEIGHT_DOTS)
    }
}
