package com.componentvault.android.data

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DetongerP1SessionTest {
    private val paper = M1TestPaperProfile(40f, 60f)
    private fun bitmap() = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
    private class Port(val event: (DetongerEvent) -> Unit) : DetongerP1Port {
        var model = "P1"
        var widthDots = 384
        var dpi = 203
        var connected = true
        var result: DetongerEvent? = DetongerEvent.Success
        var submitted = false
        var closed = false
        var continuousPaper = false
        override fun open(name: String, address: String): Boolean {
            event(if (connected) DetongerEvent.Connected else DetongerEvent.Disconnected)
            return true
        }
        override fun info() = DetongerPrinterInfo(model, widthDots, dpi)
        override fun print(bitmap: Bitmap, continuous: Boolean): Boolean {
            submitted = true
            continuousPaper = continuous
            result?.let(event)
            return true
        }
        override fun close() { closed = true }
    }

    @Test fun onlySdkSuccessAllowsNextLabel() = runBlocking {
        lateinit var port: Port
        val session = DetongerP1Session({ Port(it).also { port = it } })
        val image = bitmap()
        try {
            val result = session.print("P1-test", "address", image, paper, true) {}
            assertEquals(M1TestPrintResult.SentUnconfirmed, result.result)
            assertTrue(result.batchReady)
            assertTrue(port.continuousPaper)
            assertTrue(port.closed)
        } finally { image.recycle() }
    }

    @Test fun wrongModelOrFailedConnectionNeverSubmits() = runBlocking {
        for (connected in listOf(true, false)) {
            lateinit var port: Port
            val session = DetongerP1Session({ Port(it).also { port = it; it.model = "DP23"; it.connected = connected } })
            val image = bitmap()
            try {
                val result = session.print("P1-test", "address", image, paper, false) {}
                assertEquals(M1TestPrintResult.Rejected, result.result)
                assertFalse(port.submitted)
                assertTrue(port.closed)
            } finally { image.recycle() }
        }
    }

    @Test fun failureOrTimeoutAfterSubmissionRequiresReview() = runBlocking {
        for (event in listOf(DetongerEvent.Failed, DetongerEvent.Disconnected, null)) {
            lateinit var port: Port
            val session = DetongerP1Session({ Port(it).also { port = it; it.result = event } }, timeoutMs = 100)
            val image = bitmap()
            try {
                val result = session.print("P1-test", "address", image, paper, false) {}
                assertEquals(M1TestPrintResult.Partial, result.result)
                assertFalse(result.batchReady)
                assertTrue(port.closed)
            } finally { image.recycle() }
        }
    }

    @Test fun insufficientHeadDotsOrDifferentDpiNeverScalesOrSubmits() = runBlocking {
        for ((width, dpi) in listOf(200 to 203, 384 to 300)) {
            lateinit var port: Port
            val session = DetongerP1Session({ Port(it).also { port = it; it.widthDots = width; it.dpi = dpi } })
            val image = bitmap()
            try {
                val result = session.print("P1-test", "address", image, paper, false) {}
                assertEquals(M1TestPrintResult.Rejected, result.result)
                assertFalse(port.submitted)
                assertTrue(port.closed)
            } finally { image.recycle() }
        }
    }

    @Test fun cancellationClosesTheSdkBeforeReturningBitmapOwnership() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        lateinit var port: Port
        val session = DetongerP1Session({ Port(it).also { port = it; it.result = null } })
        val image = bitmap()
        try {
            val job = launch { session.print("P1-test", "address", image, paper, false) { submitted.complete(Unit) } }
            submitted.await()
            job.cancelAndJoin()
            assertTrue(port.closed)
        } finally { image.recycle() }
    }
}
