package com.componentvault.android.data

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/** Wire layout verified against the supvan-cups T50 SPP protocol and raster reference. */
internal object SupvanT50PrintProtocol {
    const val CheckDevice = 0x12
    const val InquiryStatus = 0x11
    const val StartPrint = 0x13
    const val BufferFull = 0x10
    const val NextCompressedBlock = 0x5c
    const val FrameSize = 512
    private const val PayloadSize = 500
    private const val HeadDots = 384
    private const val RowBytes = HeadDots / 8
    private const val BufferSize = 4096
    private const val RasterStart = 14
    private const val MarginRows = 1
    private const val Density = 4

    fun command(code: Int, parameter: Int = 0, secondParameter: Int = 0): ByteArray =
        byteArrayOf(0x7e, 0x5a, 12, 0, 0x10, 1, 0xaa.toByte(), code.toByte(), 0, 0, 0, 1, 0, 0, 0, 0).apply {
            putWord(12, parameter)
            putWord(14, secondParameter)
            putWord(8, (10 until size).sumOf { unsigned(it) })
        }

    fun frames(block: ByteArray): List<ByteArray> {
        require(block.isNotEmpty() && block.size <= BufferSize)
        val count = (block.size + PayloadSize - 1) / PayloadSize
        return List(count) { index ->
            ByteArray(FrameSize).apply {
                this[0] = 0x7e
                this[1] = 0x5a
                putWord(2, FrameSize - 4)
                this[4] = 0x10
                this[5] = 2
                this[6] = 0xaa.toByte()
                this[7] = 0xbb.toByte()
                this[10] = index.toByte()
                this[11] = count.toByte()
                val start = index * PayloadSize
                block.copyInto(this, 12, start, minOf(start + PayloadSize, block.size))
                putWord(8, (10 until size).sumOf { unsigned(it) })
            }
        }
    }

    fun fitsPrintArea(width: Int, height: Int, pixelAt: (x: Int, y: Int) -> Int): Boolean {
        if (width !in 1..400 || height !in 80..2400) return false
        val left = (HeadDots - width) / 2
        // Source X is mirrored, so the right edge of the head sets the left ink margin.
        val firstPrintableX = (width + left - HeadDots).coerceAtLeast(0)
        val pastPrintableX = (width + left).coerceAtMost(width)
        for (y in 0 until height) {
            if (y < MarginRows || y >= height - MarginRows) {
                for (x in 0 until width) if (hasInk(pixelAt(x, y))) return false
            } else {
                for (x in 0 until firstPrintableX) if (hasInk(pixelAt(x, y))) return false
                for (x in pastPrintableX until width) if (hasInk(pixelAt(x, y))) return false
            }
        }
        return true
    }

    private fun hasInk(pixel: Int): Boolean {
        val luminance = (pixel ushr 16 and 255) * 299 + (pixel ushr 8 and 255) * 587 + (pixel and 255) * 114
        return pixel ushr 24 >= 128 && luminance < 128_000
    }

    fun encode(width: Int, height: Int, pixels: IntArray): List<ByteArray> {
        require(pixels.size == width * height)
        require(fitsPrintArea(width, height) { x, y -> pixels[y * width + x] }) { "supvan_print_area" }
        val raster = ByteArray(height * RowBytes)
        val left = (HeadDots - width) / 2
        // The T50 Pro mirrors the printhead axis relative to the editor.
        for (y in 0 until height) {
            val sourceRow = y * width
            for (x in 0 until width) {
                val pixel = pixels[sourceRow + (width - 1 - x)]
                if (!hasInk(pixel)) continue
                val dot = x + left
                val index = y * RowBytes + dot / 8
                raster[index] = (raster.unsigned(index) or (1 shl (dot % 8))).toByte()
            }
        }

        // Leave enough room for LZMA overhead on a dense buffer.
        val rowsPerBuffer = 80
        val lastRow = height - MarginRows
        val buffers = (MarginRows until lastRow step rowsPerBuffer).map { firstRow ->
            val endRow = minOf(firstRow + rowsPerBuffer, lastRow)
            val rows = endRow - firstRow
            ByteArray(BufferSize).apply {
                val first = if (firstRow == MarginRows) 0x02 else 0
                val last = if (endRow == lastRow) 0x0c else 0
                this[2] = (first or last).toByte()
                this[3] = (0x40 or (Density shl 2)).toByte()
                putWord(4, rows)
                this[6] = RowBytes.toByte()
                putWord(8, MarginRows)
                putWord(10, MarginRows)
                this[12] = Density.toByte()
                raster.copyInto(this, RasterStart, firstRow * RowBytes, endRow * RowBytes)
                val checksum = (2 until RasterStart).sumOf { unsigned(it) } +
                    (255 until RasterStart + rows * RowBytes step 256).sumOf { unsigned(it) }
                putWord(0, checksum)
            }
        }

        return buildList {
            var first = 0
            while (first < buffers.size) {
                var count = minOf(4, buffers.size - first)
                while (true) {
                    val block = compress(buffers.subList(first, first + count))
                    if (block.size <= BufferSize) {
                        add(block)
                        first += count
                        break
                    }
                    require(count > 1) { "supvan_block_too_large" }
                    count--
                }
            }
        }
    }

    private fun compress(buffers: List<ByteArray>): ByteArray {
        val options = LZMA2Options().apply { dictSize = 8192 }
        val output = ByteArrayOutputStream()
        // The known size constructor emits an LZMA-Alone header without an end marker.
        LZMAOutputStream(output, options, (buffers.size * BufferSize).toLong()).use { compressed ->
            buffers.forEach { compressed.write(it) }
        }
        return output.toByteArray()
    }

    private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.putWord(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }
}

internal class SupvanT50PrintSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val active: () -> Boolean,
) {
    private data class Status(val material: Int, val device: Int, val flags: Int, val missing: Int) {
        val bufferFull get() = material and 1 != 0
        val busy get() = device and 4 != 0
        val printing get() = flags and 0x40 != 0

        fun checkReady() {
            if (flags and 8 != 0) throw IOException("cover_open")
            if (material and 4 != 0 || missing and 1 != 0) throw IOException("paper_out")
            if (material and 0x3a != 0 || device and 8 != 0) throw IOException("supvan_printer_error")
        }
    }

    fun print(blocks: List<ByteArray>, beforeStart: () -> Unit, progress: (String) -> Unit) {
        exchange(SupvanT50PrintProtocol.CheckDevice)
        progress("supvan_handshake=ok")
        awaitStatus(6_000) { !it.busy && !it.printing }
        progress("supvan_status=ready")

        beforeStart()
        exchange(SupvanT50PrintProtocol.StartPrint)
        awaitStatus(6_000) { it.printing }
        blocks.forEachIndexed { index, block ->
            awaitStatus(15_000, 20) { !it.bufferFull }
            val frames = SupvanT50PrintProtocol.frames(block)
            exchange(SupvanT50PrintProtocol.NextCompressedBlock, SupvanT50PrintProtocol.FrameSize, frames.size)
            frames.forEachIndexed { frameIndex, frame ->
                for (offset in frame.indices step 128) {
                    ensureActive()
                    Thread.sleep(10)
                    output.write(frame, offset, 128)
                }
                output.flush()
                // The final data ACK can arrive during the following command.
                if (frameIndex < frames.lastIndex) readFrame(deadline(2_000))
            }
            Thread.sleep(20)
            exchange(SupvanT50PrintProtocol.BufferFull, block.size, if (blocks.size == 1) 15 else 10)
            progress("supvan_blocks_committed=${index + 1}/${blocks.size}")
        }
        awaitStatus(30_000) { !it.printing && !it.busy && !it.bufferFull }
        progress("supvan_status=finished")
    }

    private fun awaitStatus(timeout: Long, interval: Long = 100, done: (Status) -> Boolean) {
        val end = deadline(timeout)
        do {
            val response = exchange(SupvanT50PrintProtocol.InquiryStatus)
            if (response.size < 20) throw IOException("supvan_invalid_status")
            val status = Status(response[14].toInt(), response[15].toInt(), response[16].toInt(), response[17].toInt())
            status.checkReady()
            if (done(status)) return
            Thread.sleep(interval)
        } while (System.nanoTime() < end)
        throw SocketTimeoutException("supvan_status_timeout")
    }

    private fun exchange(code: Int, parameter: Int = 0, secondParameter: Int = 0): ByteArray {
        ensureActive()
        output.write(SupvanT50PrintProtocol.command(code, parameter, secondParameter))
        output.flush()
        val end = deadline(2_000)
        while (true) {
            val frame = readFrame(end)
            if (frame.size >= 8 && frame[4] == 0x10.toByte() && (frame[7].toInt() and 0xff) == code) return frame
        }
    }

    private fun readFrame(end: Long): ByteArray {
        var previous = -1
        while (true) {
            val next = readByte(end)
            if (previous == 0x7e && next == 0x5a) break
            previous = next
        }
        val low = readByte(end)
        val high = readByte(end)
        val size = 4 + low + (high shl 8)
        if (size !in 6..512) throw IOException("supvan_invalid_frame")
        return ByteArray(size).apply {
            this[0] = 0x7e
            this[1] = 0x5a
            this[2] = low.toByte()
            this[3] = high.toByte()
            for (index in 4 until size) this[index] = readByte(end).toByte()
        }
    }

    private fun readByte(end: Long): Int {
        while (System.nanoTime() < end) {
            ensureActive()
            if (input.available() > 0) {
                val value = input.read()
                if (value < 0) throw IOException("supvan_connection_closed")
                return value
            }
            Thread.sleep(5)
        }
        throw SocketTimeoutException("supvan_reply_timeout")
    }

    private fun deadline(timeoutMs: Long): Long = System.nanoTime() + timeoutMs * 1_000_000

    private fun ensureActive() {
        if (!active()) throw IOException("supvan_cancelled")
    }
}
