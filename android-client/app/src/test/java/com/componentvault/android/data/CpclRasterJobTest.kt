package com.componentvault.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CpclRasterJobTest {
    @Test
    fun packsEachRowMostSignificantBitFirstAndKeepsTransparentPixelsClear() {
        val pixels = IntArray(18) { -1 }
        pixels[0] = 0xFF000000.toInt()
        pixels[8] = 0xFF000000.toInt()
        pixels[9] = 0x00000000
        pixels[16] = 0xFF000000.toInt()
        val expected = "! 0 200 200 2 1\r\nCG 2 2 0 0 ".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01, 0x00) +
            "\r\nFORM\r\nPRINT\r\n".toByteArray(Charsets.US_ASCII)

        assertArrayEquals(expected, CpclRasterJob.encode(9, 2, pixels, LabelMediaType.Gap))
    }

    @Test
    fun usesOneFormCommandOnlyForGapMedia() {
        val pixels = IntArray(320 * 480) { -1 }
        val gap = CpclRasterJob.encode(320, 480, pixels, LabelMediaType.Gap)
        val continuous = CpclRasterJob.encode(320, 480, pixels, LabelMediaType.Continuous)

        assertEquals(19248, gap.size)
        val rasterEnd = continuous.size - "\r\nPRINT\r\n".length
        assertArrayEquals(gap.copyOf(rasterEnd), continuous.copyOf(rasterEnd))
        assertEquals("\r\nPRINT\r\n", continuous.copyOfRange(rasterEnd, continuous.size).toString(Charsets.US_ASCII))
        assertEquals("\r\nFORM\r\nPRINT\r\n", gap.copyOfRange(rasterEnd, gap.size).toString(Charsets.US_ASCII))
    }
}
