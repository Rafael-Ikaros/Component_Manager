package com.componentvault.android.data

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Test

class HmA300eCpclProtocolTest {
    @Test
    fun packsRawMsbFirstWithWhiteTailAndExactCrLfFraming() {
        val pixels = IntArray(9) { 0xffffffff.toInt() }.apply { this[0] = 0xff000000.toInt(); this[8] = 0xff000000.toInt() }
        val parts = HmA300eCpclProtocol.parts(9, 1, pixels, continuous = false)
        assertEquals("! 0 200 200 1 1\r\nPAGE-WIDTH 9\r\nCG 2 1 0 0 ", parts[0].bytes.toString(Charsets.US_ASCII))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x80.toByte()), parts[1].bytes)
        assertEquals("\r\n", parts[2].bytes.toString(Charsets.US_ASCII))
        assertEquals("FORM\r\n", parts[3].bytes.toString(Charsets.US_ASCII))
        assertEquals("PRINT\r\n", parts.last().bytes.toString(Charsets.US_ASCII))
    }

    @Test
    fun continuousOmitsFormAndPreservesBinaryNewlinesAndNulls() {
        val pixels = IntArray(16) { 0xffffffff.toInt() }.apply { this[4] = 0xff000000.toInt(); this[6] = 0xff000000.toInt() }
        val parts = HmA300eCpclProtocol.parts(16, 1, pixels, continuous = true)
        assertContentEquals(byteArrayOf(0x0a, 0), parts[1].bytes)
        assertFalse(parts.any { it.isFormFeed })
        assertEquals("PRINT\r\n", parts.last().bytes.toString(Charsets.US_ASCII))
    }

    @Test
    fun boundsUsePrintHeadAndApplicationLabelLimit() {
        assertEquals(72, HmA300eCpclProtocol.parts(576, 1, IntArray(576), false)[1].bytes.size)
        assertFailsWith<IllegalArgumentException> { HmA300eCpclProtocol.parts(577, 1, IntArray(577), false) }
        assertFailsWith<IllegalArgumentException> { HmA300eCpclProtocol.parts(1, 2401, IntArray(2401), false) }
        assertEquals(M1TestPrintResult.Rejected, failedPrintResult(0))
        assertEquals(M1TestPrintResult.Partial, failedPrintResult(1))
        assertEquals(M1TestPrintResult.Partial, interruptedPrintResult(1))
    }
}
