package com.componentvault.android.data

import android.graphics.Bitmap
import android.os.Bundle
import com.dothantech.lpapi.LPAPI
import com.dothantech.printer.IDzPrinter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class DetongerPrinterInfo(val model: String, val widthDots: Int, val dpi: Int)
internal enum class DetongerEvent { Connected, Disconnected, Success, Failed }
internal interface DetongerP1Port {
    fun open(name: String, address: String): Boolean
    fun info(): DetongerPrinterInfo?
    fun print(bitmap: Bitmap, continuous: Boolean): Boolean
    fun close()
}

internal data class DetongerP1Outcome(
    val status: PrinterProbeStatus,
    val result: M1TestPrintResult,
    val report: String,
    val batchReady: Boolean = false,
)

/** A fresh callback owner per label prevents a late SDK event from completing another job. */
internal class DetongerP1Session(
    private val createPort: ((DetongerEvent) -> Unit) -> DetongerP1Port = { DetongerSdkPort(it) },
    private val timeoutMs: Long = 45_000,
) {
    suspend fun print(name: String, address: String, bitmap: Bitmap, paper: M1TestPaperProfile,
                      continuous: Boolean, onSubmitting: () -> Unit): DetongerP1Outcome = sdkLock.withLock {
        val connected = CompletableDeferred<Boolean>()
        val printed = CompletableDeferred<Boolean>()
        val trace = StringBuffer("transport=detonger_lpapi_spp\nsdk_version=2026-10-21\n")
        var submitted = false
        var port: DetongerP1Port? = null
        fun failure(status: PrinterProbeStatus, reason: String) = DetongerP1Outcome(status,
            if (submitted) M1TestPrintResult.Partial else M1TestPrintResult.Rejected,
            "$trace$reason\n")
        try {
            LabelPrinterProfile.DetongerP1.validate(paper)
            require(bitmap.width == M1ComponentLabelRenderer.dots(paper.widthMm) &&
                bitmap.height == M1ComponentLabelRenderer.dots(paper.heightMm))
            withTimeoutOrNull(timeoutMs) {
                val sdk = createPort { event ->
                    trace.append("sdk_event=$event\n")
                    when (event) {
                        DetongerEvent.Connected -> connected.complete(true)
                        DetongerEvent.Disconnected -> { connected.complete(false); printed.complete(false) }
                        DetongerEvent.Success -> printed.complete(true)
                        DetongerEvent.Failed -> printed.complete(false)
                    }
                }.also { port = it }
                if (!sdk.open(name, address) || !connected.await())
                    return@withTimeoutOrNull failure(PrinterProbeStatus.ConnectionFailed, "error=p1_connect_failed")
                val info = sdk.info()
                    ?: return@withTimeoutOrNull failure(PrinterProbeStatus.ConnectionFailed, "error=p1_no_info")
                trace.append("device_model=${info.model}\ndevice_width_dots=${info.widthDots}\ndevice_dpi=${info.dpi}\n")
                // Do not send a P1 job to a different model or silently scale the QR raster.
                if (!info.model.matches(Regex("(?i)P1(?:[- ].*)?")) || info.dpi != 203 || bitmap.width > info.widthDots)
                    return@withTimeoutOrNull failure(PrinterProbeStatus.Unsupported, "error=p1_device_mismatch")
                onSubmitting()
                submitted = true
                if (!sdk.print(bitmap, continuous))
                    return@withTimeoutOrNull failure(PrinterProbeStatus.ConnectionFailed, "error=p1_submit_failed")
                if (!printed.await())
                    return@withTimeoutOrNull failure(PrinterProbeStatus.ConnectionFailed, "error=p1_print_failed")
                DetongerP1Outcome(PrinterProbeStatus.Complete, M1TestPrintResult.SentUnconfirmed,
                    "${trace}print_confirmation=sdk_success\n", batchReady = true)
            } ?: failure(PrinterProbeStatus.TimedOut, "error=p1_timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            failure(PrinterProbeStatus.PermissionRequired, "error=permission_required")
        } catch (error: Exception) {
            failure(PrinterProbeStatus.ConnectionFailed, "sdk_exception=${error.javaClass.simpleName}")
        } finally {
            // The SDK owns the bitmap until its worker has stopped; caller recycles it afterwards.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { port?.close() } }
        }
    }

    companion object { private val sdkLock = Mutex() }
}

private class DetongerSdkPort(onEvent: (DetongerEvent) -> Unit) : DetongerP1Port {
    private val api = LPAPI.Factory.createInstance(object : LPAPI.Callback {
        override fun onStateChange(address: IDzPrinter.PrinterAddress?, state: IDzPrinter.PrinterState?) {
            when (state) {
                IDzPrinter.PrinterState.Connected, IDzPrinter.PrinterState.Connected2 -> onEvent(DetongerEvent.Connected)
                IDzPrinter.PrinterState.Disconnected -> onEvent(DetongerEvent.Disconnected)
                else -> Unit
            }
        }
        override fun onPrintProgress(address: IDzPrinter.PrinterAddress?, data: IDzPrinter.PrintData?,
                                     progress: IDzPrinter.PrintProgress?, extra: Any?) {
            when (progress) {
                IDzPrinter.PrintProgress.Success -> onEvent(DetongerEvent.Success)
                IDzPrinter.PrintProgress.Failed -> onEvent(DetongerEvent.Failed)
                else -> Unit
            }
        }
        override fun onProgressInfo(info: IDzPrinter.ProgressInfo?, extra: Any?) = Unit
        override fun onPrinterDiscovery(address: IDzPrinter.PrinterAddress?, extra: Any?) = Unit
    })

    override fun open(name: String, address: String) = api.openPrinterByAddress(
        IDzPrinter.PrinterAddress(name, address, IDzPrinter.AddressType.SPP))
    override fun info() = api.printerInfo?.let { DetongerPrinterInfo(it.deviceName, it.deviceWidth, it.deviceDPI) }
    override fun print(bitmap: Bitmap, continuous: Boolean) = api.printBitmap(bitmap, Bundle().apply {
        putInt(IDzPrinter.PrintParamName.PRINT_COPIES, 1)
        putInt(IDzPrinter.PrintParamName.PRINT_DIRECTION, 0) // Rotation and offsets are already in the shared raster.
        putInt(IDzPrinter.PrintParamName.GAP_TYPE, if (continuous) IDzPrinter.PrintParamValue.GAP_NONE else IDzPrinter.PrintParamValue.GAP_GAP)
        // Request centered narrow labels; user calibration remains in the shared raster.
        putInt(IDzPrinter.PrintParamName.PRINT_ALIGNMENT, IDzPrinter.PrintParamValue.PRINT_ALIGNMENT_CENTER)
    })
    override fun close() { api.cancel(); api.quit() }
}
