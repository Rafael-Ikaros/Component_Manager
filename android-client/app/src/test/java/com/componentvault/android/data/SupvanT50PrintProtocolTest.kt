package com.componentvault.android.data

import org.junit.Test
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupvanT50PrintProtocolTest {
    @Test
    fun sendsThePrintJobOnlyAfterHandshakeAndReadyStatus() {
        val ready = reply(0x11, size = 20)
        val printing = ready.copyOf().apply { this[16] = 0x40 }
        val replies = reply(0x12) + ready + reply(0x13) + printing + printing +
            reply(0x5c) + reply(0x10) + ready
        val writes = ByteArrayOutputStream()
        var started = false
        SupvanT50PrintSession(ByteArrayInputStream(replies), writes) { true }.print(
            listOf(byteArrayOf(42)),
            beforeStart = {
                assertEquals(listOf(0x12, 0x11), writes.toByteArray().toList().chunked(16).map { it[7].toInt() })
                started = true
            },
            progress = {},
        )
        assertTrue(started)
        val sent = writes.toByteArray()
        assertEquals(0x13, sent[2 * 16 + 7].toInt())
        assertEquals(0x5c, sent[5 * 16 + 7].toInt())
        assertEquals(0x10, sent[6 * 16 + 512 + 7].toInt())
    }

    @Test
    fun mirrorsThePrintheadAxisWithoutReversingPaperFeed() {
        val width = 320
        val height = 480
        val pixels = IntArray(width * height) { -1 }
        pixels[width + 1] = 0xff000000.toInt()
        val blocks = SupvanT50PrintProtocol.encode(width, height, pixels)
        val buffers = blocks.flatMap { block ->
            assertTrue(block.size <= 4096)
            assertEquals(0x5d, block.first().toInt())
            LZMAInputStream(ByteArrayInputStream(block)).use { it.readBytes().asList().chunked(4096) }
        }

        assertEquals(0x02, buffers.first()[2].toInt())
        assertEquals(0x0c, buffers.last()[2].toInt())
        assertEquals(478, buffers.sumOf { (it[4].toInt() and 255) + (it[5].toInt() and 255) * 256 })
        assertEquals(48, buffers.first()[6].toInt())
        assertEquals(0, buffers.first()[18].toInt())
        // (1, 1) becomes (318, 1), then centering adds 32 head dots.
        assertEquals(0x40, buffers.first()[14 + 43].toInt())
        assertEquals(0, buffers.last()[14 + 77 * 48 + 43].toInt())
    }

    @Test
    fun acceptsFiftyMillimeterPaperOnlyWhenTheInkFitsTheHead() {
        val width = 400
        val height = 80
        val pixels = IntArray(width * height) { -1 }
        val ink = 0xff000000.toInt()
        fun fits() = SupvanT50PrintProtocol.fitsPrintArea(width, height) { x, y -> pixels[y * width + x] }

        pixels[width + 8] = ink
        pixels[(height - 2) * width + 391] = ink
        assertTrue(fits())
        assertTrue(SupvanT50PrintProtocol.encode(width, height, pixels).isNotEmpty())
        for ((x, y) in listOf(7 to 1, 392 to 1, 200 to 0, 200 to height - 1)) {
            val index = y * width + x
            pixels[index] = ink
            assertFalse(fits(), "Ink at ($x, $y) falls outside the print area")
            assertEquals("supvan_print_area", assertFailsWith<IllegalArgumentException> {
                SupvanT50PrintProtocol.encode(width, height, pixels)
            }.message)
            pixels[index] = -1
        }
    }

    private fun reply(command: Int, size: Int = 8): ByteArray = ByteArray(size).apply {
        this[0] = 0x7e
        this[1] = 0x5a
        this[2] = (size - 4).toByte()
        this[4] = 0x10
        this[5] = 3
        this[6] = 0x55
        this[7] = command.toByte()
    }
}
