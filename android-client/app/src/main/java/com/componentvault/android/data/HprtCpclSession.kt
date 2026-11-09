package com.componentvault.android.data

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface HprtCpclPort {
    val isOpen: Boolean
    fun open(address: String): Int
    fun status(): Int
    fun prepare(bitmap: Bitmap, continuous: Boolean)
    fun submit(): Int
    fun abort()
    fun close()
}

internal data class HprtCpclOutcome(
    val status: PrinterProbeStatus,
    val result: M1TestPrintResult,
    val report: String,
)

/** PrinterHelper has one process-wide port and command buffer. Serialize complete jobs and cleanup. */
internal class HprtCpclSession(private val port: HprtCpclPort) {
    private var connectedAddress: String? = null

    suspend fun print(
        address: String,
        bitmap: Bitmap,
        continuous: Boolean,
        onSubmitting: suspend () -> Unit,
    ): HprtCpclOutcome = sdkLock.withLock {
        val trace = StringBuilder()
        var submitted = false
        var keepConnection = false
        fun failure(status: PrinterProbeStatus, error: String) = HprtCpclOutcome(
            status,
            if (submitted) M1TestPrintResult.Partial else M1TestPrintResult.Rejected,
            trace.toString() + "$error\n",
        )
        try {
            val result = coroutineScope {
                val worker = async(Dispatchers.IO) {
                    try {
                        val reuse = owner === this@HprtCpclSession && connectedAddress == address && port.isOpen
                        trace.appendLine("connection_reused=$reuse")
                        if (!reuse) {
                            owner?.closeOwnedPort()
                            currentCoroutineContext().ensureActive()
                            owner = this@HprtCpclSession
                            trace.appendLine("stage=sdk_open")
                            val opened = port.open(address)
                            trace.appendLine("sdk_open_result=$opened")
                            if (opened != 0) return@async failure(PrinterProbeStatus.ConnectionFailed, "error=hprt_sdk_open")
                            connectedAddress = address
                        }
                        currentCoroutineContext().ensureActive()
                        trace.appendLine("stage=sdk_status")
                        val deviceStatus = port.status()
                        trace.appendLine("sdk_printer_status=$deviceStatus")
                        if (deviceStatus != 0) {
                            val reason = when {
                                deviceStatus < 0 -> "error=hprt_status_no_response"
                                deviceStatus and 2 != 0 -> "status_bit=paper_out"
                                deviceStatus and 4 != 0 -> "status_bit=cover_open"
                                else -> "error=hprt_printer_busy"
                            }
                            return@async failure(PrinterProbeStatus.ConnectionFailed, reason)
                        }
                        currentCoroutineContext().ensureActive()
                        trace.appendLine("stage=sdk_prepare")
                        port.prepare(bitmap, continuous)
                        onSubmitting()
                        currentCoroutineContext().ensureActive()
                        submitted = true
                        trace.appendLine("stage=sdk_print")
                        val written = port.submit()
                        trace.appendLine("sdk_print_result=$written")
                        if (written <= 0) return@async failure(PrinterProbeStatus.ConnectionFailed, "error=hprt_sdk_print")
                        trace.appendLine("print_confirmation=physical_review")
                        HprtCpclOutcome(PrinterProbeStatus.Complete, M1TestPrintResult.SentUnconfirmed, trace.toString())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: SecurityException) {
                        failure(PrinterProbeStatus.PermissionRequired, "error=permission_required")
                    } catch (error: Exception) {
                        failure(PrinterProbeStatus.ConnectionFailed, "sdk_exception=${error.javaClass.simpleName}")
                    }
                }
                try {
                    withTimeoutOrNull(30_000) { worker.await() }
                } finally {
                    if (!worker.isCompleted) withContext(NonCancellable + Dispatchers.IO) {
                        worker.cancel()
                        runCatching { port.abort() }
                        worker.cancelAndJoin()
                    }
                }
            } ?: failure(PrinterProbeStatus.TimedOut, "error=hprt_sdk_timeout")
            keepConnection = result.result == M1TestPrintResult.SentUnconfirmed
            result
        } finally {
            if (!keepConnection) withContext(NonCancellable + Dispatchers.IO) { closeOwnedPort() }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        sdkLock.withLock { closeOwnedPort() }
    }

    private fun closeOwnedPort() {
        if (owner !== this) return
        runCatching { port.close() }
        connectedAddress = null
        owner = null
    }

    companion object {
        private val sdkLock = Mutex()
        private var owner: HprtCpclSession? = null
    }
}
