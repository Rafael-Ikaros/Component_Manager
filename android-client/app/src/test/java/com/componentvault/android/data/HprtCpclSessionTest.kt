package com.componentvault.android.data

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HprtCpclSessionTest {
    @Test
    fun secondLabelReusesOpenSdkPortAndWriteRequiresPhysicalReview() = runBlocking {
        val port = FakePort()
        val session = HprtCpclSession(port)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val first = session.print("printer", bitmap, continuous = false) {}
            val second = session.print("printer", bitmap, continuous = true) {}
            assertEquals(M1TestPrintResult.SentUnconfirmed, first.result)
            assertEquals(M1TestPrintResult.SentUnconfirmed, second.result)
            assertEquals(listOf("open", "status", "gap", "print", "status", "continuous", "print"), port.calls)
        } finally {
            session.disconnect()
        }
        assertEquals("close", port.calls.last())
    }

    @Test
    fun paperOutPreventsSubmission() = runBlocking {
        val port = FakePort(printerStatus = 2)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val result = HprtCpclSession(port).print("printer", bitmap, continuous = false) {
            error("Paper-out job reached submission")
        }
        assertEquals(M1TestPrintResult.Rejected, result.result)
        assertTrue("status_bit=paper_out" in result.report)
        assertEquals(listOf("open", "status", "close"), port.calls)
    }

    @Test
    fun failedWriteRemainsUncertainAndClosesPort() = runBlocking {
        val port = FakePort(writeResult = -1)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val result = HprtCpclSession(port).print("printer", bitmap, continuous = false) {}
        assertEquals(LabelPrintItemState.Uncertain, labelPrintOutcomeState(result.result, batchReady = false))
        assertEquals(listOf("open", "status", "gap", "print", "close"), port.calls)
    }

    @Test
    fun cancellationBeforeSubmissionDiscardsPreparedLabel() = runBlocking {
        val port = FakePort()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val session = HprtCpclSession(port)
        assertFailsWith<CancellationException> {
            session.print("printer", bitmap, continuous = false) { throw CancellationException() }
        }
        assertEquals(listOf("open", "status", "gap", "close"), port.calls)
    }

    private class FakePort(private val printerStatus: Int = 0, private val writeResult: Int = 50) : HprtCpclPort {
        val calls = mutableListOf<String>()
        override var isOpen = false
            private set

        override fun open(address: String): Int {
            calls += "open"
            isOpen = true
            return 0
        }

        override fun status(): Int {
            calls += "status"
            return printerStatus
        }

        override fun prepare(bitmap: Bitmap, continuous: Boolean) {
            calls += if (continuous) "continuous" else "gap"
        }

        override fun submit(): Int {
            calls += "print"
            return writeResult
        }

        override fun abort() = Unit

        override fun close() {
            calls += "close"
            isOpen = false
        }
    }
}
