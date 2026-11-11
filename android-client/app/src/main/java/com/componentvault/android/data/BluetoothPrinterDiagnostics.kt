package com.componentvault.android.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PrinterDeviceCandidate(
    val device: BluetoothDevice,
    val name: String?,
    val address: String,
    val type: Int,
    val paired: Boolean,
    val advertisedServices: List<String> = emptyList(),
)

internal enum class PrinterProbeStatus {
    Idle, Scanning, ScanComplete, Connecting, Printing, Complete, BluetoothOff, Unsupported,
    PermissionRequired, ScanFailed, ConnectionFailed, TimedOut, Stopped,
}

internal enum class M1TestPrintResult { None, SentUnconfirmed, SentConnectionLost, Partial, Interrupted, Rejected }

internal data class M1PostPrintModelConfirmation(
    val firstReply: M1QueryReply,
    val firstResult: M1SppProtocol.ModelResult,
    val drainedReply: M1QueryReply?,
    val retryReply: M1QueryReply?,
    val retryResult: M1SppProtocol.ModelResult?,
) {
    val result: M1SppProtocol.ModelResult get() = retryResult ?: firstResult
}

internal fun confirmPostPrintModel(
    query: () -> M1QueryReply,
    drain: () -> M1QueryReply,
): M1PostPrintModelConfirmation {
    val firstReply = query()
    val firstResult = strictModelResult(firstReply)
    if (firstResult == M1SppProtocol.ModelResult.Matched) {
        return M1PostPrintModelConfirmation(firstReply, firstResult, null, null, null)
    }
    val drainedReply = drain()
    val retryReply = query()
    return M1PostPrintModelConfirmation(
        firstReply = firstReply,
        firstResult = firstResult,
        drainedReply = drainedReply,
        retryReply = retryReply,
        retryResult = strictModelResult(retryReply),
    )
}

private fun strictModelResult(reply: M1QueryReply): M1SppProtocol.ModelResult =
    if (reply.overflowed) M1SppProtocol.ModelResult.Mismatch else M1SppProtocol.parseModelReply(reply.reply)

internal fun m1BatchReady(postFeed: M1QueryReply, confirmation: M1PostPrintModelConfirmation): Boolean {
    val replies = listOfNotNull(postFeed, confirmation.firstReply, confirmation.drainedReply, confirmation.retryReply)
    val status = postFeed.classifyStatus()
    return confirmation.result == M1SppProtocol.ModelResult.Matched &&
        replies.sumOf { it.asyncPrintFinishCount } > 0 &&
        replies.none { it.overflowed || it.asyncStatusCodes.any { code -> code != 0 } } &&
        status != M1SppProtocol.StatusResult.Invalid &&
        (status !is M1SppProtocol.StatusResult.Received || status.code == 0)
}

/** Builds the shareable portion of a probe report without device identity or label data. */
internal fun buildPrinterProbeReportHeader(
    sdk: Int,
    type: Int,
    paired: Boolean,
    advertisedServices: List<String>,
): String = buildString {
    appendLine("android_sdk=$sdk")
    appendLine("device_type=$type (0=unknown, 1=Classic, 2=BLE, 3=dual)")
    appendLine("paired=$paired")
    advertisedServices.forEach { appendLine("advertised_service=$it") }
}

/** Foreground-only Bluetooth diagnostics and the shared, verified M1 single-label transport. */
@SuppressLint("MissingPermission") // The UI requests permissions; revocation is also handled at every entry point.
internal class BluetoothPrinterDiagnostics(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanCallback: ScanCallback? = null
    private var stopScanTask: Runnable? = null
    private var timeoutTask: Runnable? = null
    private var gatt: BluetoothGatt? = null
    @Volatile private var sppSession: SppSession? = null
    @Volatile private var generation = 0
    private val sppOwnershipLock = Any()
    private val m1Connections = ReusableResourceSlot<M1Connection> { it.close() }
    private var m1IdleCloseTask: Runnable? = null
    private val hprtPort: HprtCpclPort? by lazy { createHprtCpclPort(this.context) }
    private var hprtSession: HprtCpclSession? = null
    private var hprtOperation: Deferred<HprtCpclOutcome>? = null
    private var p1Operation: Deferred<DetongerP1Outcome>? = null
    val hprtSdkAvailable get() = hprtPort != null

    var candidates by mutableStateOf<List<PrinterDeviceCandidate>>(emptyList())
        private set
    var status by mutableStateOf(PrinterProbeStatus.Idle)
        private set
    var report by mutableStateOf("")
        private set
    var printResult by mutableStateOf(M1TestPrintResult.None)
        private set
    // A processing event plus a clean model reply allows the queue to send its next label.
    // This is protocol readiness, not a claim that a physical label has been inspected.
    var canContinueBatch by mutableStateOf(false)
        private set
    val busy get() = status in setOf(PrinterProbeStatus.Scanning, PrinterProbeStatus.Connecting, PrinterProbeStatus.Printing)

    fun refreshPaired() {
        if (busy) return
        candidates = emptyList()
        try {
            val bluetooth = adapter ?: run { status = PrinterProbeStatus.Unsupported; return }
            if (!bluetooth.isEnabled) { status = PrinterProbeStatus.BluetoothOff; return }
            bluetooth.bondedDevices.forEach { addCandidate(it, emptyList()) }
            status = PrinterProbeStatus.ScanComplete
        } catch (_: SecurityException) {
            permissionDenied()
        }
    }

    fun scan() {
        stop()
        printResult = M1TestPrintResult.None
        candidates = emptyList()
        report = ""
        val currentGeneration = generation
        try {
            val bluetooth = adapter ?: run { status = PrinterProbeStatus.Unsupported; return }
            if (!bluetooth.isEnabled) { status = PrinterProbeStatus.BluetoothOff; return }
            bluetooth.bondedDevices.forEach { addCandidate(it, emptyList()) }
            val scanner = bluetooth.bluetoothLeScanner
            if (scanner == null) { status = PrinterProbeStatus.Unsupported; return }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (generation == currentGeneration && scanCallback === this && status == PrinterProbeStatus.Scanning) {
                            try {
                                addCandidate(result.device, result.scanRecord?.serviceUuids.orEmpty().map { it.toString() })
                            } catch (_: SecurityException) { permissionDenied() }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (generation == currentGeneration && scanCallback === this) {
                            stopScan()
                            status = PrinterProbeStatus.ScanFailed
                            report = "scan_error=$errorCode"
                        }
                    }
                }
            }
            scanCallback = callback
            status = PrinterProbeStatus.Scanning
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            stopScanTask = Runnable {
                if (generation == currentGeneration && status == PrinterProbeStatus.Scanning) {
                    stopScan()
                    status = PrinterProbeStatus.ScanComplete
                }
            }.also { handler.postDelayed(it, 10_000) }
        } catch (_: SecurityException) {
            permissionDenied()
        } catch (_: IllegalStateException) {
            stop()
            status = PrinterProbeStatus.BluetoothOff
        }
    }

    private fun addCandidate(device: BluetoothDevice, advertised: List<String>) {
        val address = device.address
        val previous = candidates.firstOrNull { it.address == address }
        val candidate = PrinterDeviceCandidate(
            device, device.name, address, device.type, device.bondState == BluetoothDevice.BOND_BONDED,
            (previous?.advertisedServices.orEmpty() + advertised).distinct().sorted(),
        )
        // Keep a bounded, stable list while nearby devices advertise repeatedly.
        candidates = if (previous != null) candidates.map { if (it.address == address) candidate else it }
        else if (candidates.size < 80) candidates + candidate else candidates
    }

    fun inspect(candidate: PrinterDeviceCandidate) {
        stop()
        printResult = M1TestPrintResult.None
        val currentGeneration = generation
        report = buildPrinterProbeReportHeader(
            sdk = Build.VERSION.SDK_INT,
            type = candidate.type,
            paired = candidate.paired,
            advertisedServices = candidate.advertisedServices,
        )
        try {
            if (adapter?.isEnabled != true) { status = PrinterProbeStatus.BluetoothOff; return }
            candidate.device.uuids.orEmpty().forEach { report += "cached_service=$it\n" }
            if (candidate.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                report += "probe=cached_classic_services_only; print_protocol=unknown\n"
                status = PrinterProbeStatus.Complete
                return
            }
            status = PrinterProbeStatus.Connecting
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(connection: BluetoothGatt, code: Int, newState: Int) {
                    handler.post {
                        if (generation != currentGeneration || gatt !== connection) return@post
                        try {
                            when {
                                code != BluetoothGatt.GATT_SUCCESS -> finish(PrinterProbeStatus.ConnectionFailed, "gatt_error=$code")
                                newState == BluetoothProfile.STATE_CONNECTED -> {
                                    if (!connection.discoverServices()) finish(PrinterProbeStatus.ConnectionFailed, "service_discovery=not_started")
                                }
                                newState == BluetoothProfile.STATE_DISCONNECTED -> finish(PrinterProbeStatus.ConnectionFailed, "connection=disconnected")
                            }
                        } catch (_: SecurityException) { permissionDenied() }
                    }
                }

                override fun onServicesDiscovered(connection: BluetoothGatt, code: Int) {
                    handler.post {
                        if (generation != currentGeneration || gatt !== connection) return@post
                        if (code != BluetoothGatt.GATT_SUCCESS) {
                            finish(PrinterProbeStatus.ConnectionFailed, "service_error=$code")
                            return@post
                        }
                        report += buildString {
                            connection.services.forEach { service ->
                                appendLine("service=${service.uuid}")
                                service.characteristics.forEach { characteristic ->
                                    appendLine("  characteristic=${characteristic.uuid} properties=${characteristic.properties}")
                                }
                            }
                        }
                        finish(PrinterProbeStatus.Complete, "probe=services_only; print_protocol=unknown")
                    }
                }
            }
            gatt = candidate.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) { finish(PrinterProbeStatus.ConnectionFailed, "connection=not_started"); return }
            timeoutTask = Runnable {
                if (generation == currentGeneration) finish(PrinterProbeStatus.TimedOut, "connection=timeout")
            }.also { handler.postDelayed(it, 15_000) }
        } catch (_: SecurityException) {
            permissionDenied()
        } catch (_: IllegalStateException) {
            finish(PrinterProbeStatus.ConnectionFailed, "connection=unavailable")
        }
    }

    fun inspectM1Spp(candidate: PrinterDeviceCandidate) {
        prepareM1Operation(candidate.address)
        printResult = M1TestPrintResult.None
        val currentGeneration = generation
        report = buildString {
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("transport=spp")
            appendLine("probe=m1_model_status")
            appendLine("print_tested=false")
            appendLine("paired=${candidate.paired}")
            appendLine("device_type=${candidate.type}")
        }
        if (!candidate.paired || candidate.type !in setOf(
                BluetoothDevice.DEVICE_TYPE_CLASSIC,
                BluetoothDevice.DEVICE_TYPE_DUAL,
            )
        ) {
            finish(PrinterProbeStatus.Unsupported, "error=unsupported_device")
            return
        }
        try {
            if (adapter?.isEnabled != true) {
                finish(PrinterProbeStatus.BluetoothOff, "error=bluetooth_off")
                return
            }
            status = PrinterProbeStatus.Connecting
            val session = SppSession(currentGeneration, candidate.address)
            sppSession = session
            Thread({ runM1SppProbe(candidate, session) }, "m1-spp-probe").start()
        } catch (_: SecurityException) {
            permissionDenied()
        } catch (_: RuntimeException) {
            finish(PrinterProbeStatus.ConnectionFailed, "error=worker_start")
        }
    }

    /** Copies [bitmap] before returning; the caller may recycle it immediately after this call. */
    fun printM1Test(
        candidate: PrinterDeviceCandidate,
        bitmap: Bitmap,
        paperProfile: M1TestPaperProfile? = null,
        feedMode: M1FeedMode = M1FeedMode.Label,
    ) = printM1Bitmap(candidate, bitmap, paperProfile, feedMode, "m1_single_test_print")

    /** Production labels always use the tested, normal label-end command. */
    fun printM1Label(candidate: PrinterDeviceCandidate, bitmap: Bitmap, paper: M1TestPaperProfile) =
        printM1Bitmap(candidate, bitmap, paper, M1FeedMode.Label, "m1_component_label")

    /** A successful SDK write leaves the label pending physical verification. */
    suspend fun printDetongerP1Label(candidate: PrinterDeviceCandidate, bitmap: Bitmap,
                                    paper: M1TestPaperProfile, continuous: Boolean) {
        stopInternal(recordPrintInterruption = false)
        printResult = M1TestPrintResult.None
        canContinueBatch = false
        report = "transport=detonger_lpapi_spp\n"
        if (!candidate.paired || candidate.type !in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL)) {
            finish(PrinterProbeStatus.Unsupported, "error=p1_unsupported_device")
            printResult = M1TestPrintResult.Rejected
            return
        }
        try {
            if (adapter?.isEnabled != true) {
                finish(PrinterProbeStatus.BluetoothOff, "error=bluetooth_off")
                printResult = M1TestPrintResult.Rejected
                return
            }
        } catch (_: SecurityException) {
            finish(PrinterProbeStatus.PermissionRequired, "error=permission_required")
            printResult = M1TestPrintResult.Rejected
            return
        }
        status = PrinterProbeStatus.Connecting
        coroutineScope {
            val attempt = async(start = CoroutineStart.LAZY) {
                DetongerP1Session().print(candidate.name.orEmpty(), candidate.address, bitmap, paper, continuous) {
                    status = PrinterProbeStatus.Printing
                }
            }
            p1Operation = attempt
            try {
                val outcome = attempt.await()
                report = outcome.report
                status = outcome.status
                printResult = outcome.result
                canContinueBatch = outcome.batchReady
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            } finally {
                if (p1Operation === attempt) p1Operation = null
            }
        }
    }

    suspend fun printHmA300eLabel(
        candidate: PrinterDeviceCandidate,
        bitmap: Bitmap,
        paper: M1TestPaperProfile,
        continuous: Boolean,
    ) {
        if (hprtSession == null) stopInternal(recordPrintInterruption = false)
        printResult = M1TestPrintResult.None
        canContinueBatch = false
        report = "transport=hprt_sdk_spp\nprobe=hma300e_cpcl_label\nsdk_version=1.22.05\n" +
            "media=${if (continuous) "continuous" else "gap"}\n" +
            "paper_width_mm=${paper.widthMm}\npaper_height_mm=${paper.heightMm}\n" +
            "bitmap_width_dots=${bitmap.width}\nbitmap_height_dots=${bitmap.height}\n"
        val port = hprtPort
        if (port == null) {
            finish(PrinterProbeStatus.Unsupported, "error=hprt_sdk_missing")
            printResult = M1TestPrintResult.Rejected
            return
        }
        if (!candidate.paired || candidate.type !in setOf(
                BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL,
            )
        ) {
            finish(PrinterProbeStatus.Unsupported, "error=hprt_unsupported_device")
            printResult = M1TestPrintResult.Rejected
            return
        }
        val bluetoothEnabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            finish(PrinterProbeStatus.PermissionRequired, "error=permission_required")
            printResult = M1TestPrintResult.Rejected
            return
        }
        if (!bluetoothEnabled) {
            finish(PrinterProbeStatus.BluetoothOff, "error=bluetooth_off")
            printResult = M1TestPrintResult.Rejected
            return
        }
        val session = hprtSession ?: HprtCpclSession(port).also { hprtSession = it }
        status = PrinterProbeStatus.Connecting
        coroutineScope {
            val attempt = async(start = CoroutineStart.LAZY) {
                session.print(candidate.address, bitmap, continuous) {
                    withContext(Dispatchers.Main.immediate) { status = PrinterProbeStatus.Printing }
                }
            }
            hprtOperation = attempt
            try {
                val outcome = attempt.await()
                report += outcome.report
                report += "print_result=${outcome.result.name.lowercase()}\n"
                status = outcome.status
                printResult = outcome.result
                // Manual review can outlast an idle timer. Keep the SDK port for the active queue.
                if (outcome.result != M1TestPrintResult.SentUnconfirmed) {
                    hprtSession = null
                }
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            } finally {
                if (hprtOperation === attempt) hprtOperation = null
            }
        }
    }

    private fun releaseHprtSession() {
        hprtOperation?.cancel()
        hprtOperation = null
        val session = hprtSession
        hprtSession = null
        if (session != null) hprtCleanup.launch { session.disconnect() }
    }

    private fun printM1Bitmap(
        candidate: PrinterDeviceCandidate,
        bitmap: Bitmap,
        paperProfile: M1TestPaperProfile?,
        feedMode: M1FeedMode,
        probeName: String,
    ) {
        prepareM1Operation(candidate.address)
        printResult = M1TestPrintResult.None
        canContinueBatch = false
        report = buildString {
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("transport=spp")
            appendLine("probe=$probeName")
            appendLine("print_stage=preflight")
            appendLine("paired=${candidate.paired}")
            appendLine("device_type=${candidate.type}")
            appendLine("bitmap_width_dots=${bitmap.width}")
            appendLine("bitmap_height_dots=${bitmap.height}")
            appendLine("feed_mode=${feedMode.name.lowercase()}")
            paperProfile?.let { profile ->
                appendLine("paper_width_mm=${profile.widthMm}")
                appendLine("paper_height_mm=${profile.heightMm}")
                appendLine("rotation_degrees=${profile.rotationDegrees}")
                appendLine("offset_x_mm=${profile.offsetXmm}")
                appendLine("offset_y_mm=${profile.offsetYmm}")
            }
        }
        if (!candidate.paired || candidate.type !in setOf(
                BluetoothDevice.DEVICE_TYPE_CLASSIC,
                BluetoothDevice.DEVICE_TYPE_DUAL,
            )
        ) {
            report += "form_feed_commands=0\n"
            report += "short_feed_commands=0\n"
            finish(PrinterProbeStatus.Unsupported, "error=unsupported_device")
            printResult = M1TestPrintResult.Rejected
            return
        }
        if (paperProfile != null &&
            (paperProfile.widthMm !in 20f..48f || paperProfile.heightMm !in 10f..100f ||
                paperProfile.offsetXmm !in -48f..48f || paperProfile.offsetYmm !in -100f..100f)
        ) {
            finish(PrinterProbeStatus.Unsupported, "error=unsupported_m1_paper")
            printResult = M1TestPrintResult.Rejected
            return
        }
        val frames = try {
            M1SppPrintProtocol.frames(bitmap)
        } catch (_: RuntimeException) {
            report += "form_feed_commands=0\n"
            report += "short_feed_commands=0\n"
            finish(PrinterProbeStatus.ConnectionFailed, "error=invalid_bitmap")
            printResult = M1TestPrintResult.Rejected
            return
        }
        try {
            if (adapter?.isEnabled != true) {
                report += "form_feed_commands=0\n"
                report += "short_feed_commands=0\n"
                finish(PrinterProbeStatus.BluetoothOff, "error=bluetooth_off")
                printResult = M1TestPrintResult.Rejected
                return
            }
            status = PrinterProbeStatus.Connecting
            val session = SppSession(generation, candidate.address, isPrint = true)
            sppSession = session
            Thread({ runM1TestPrint(candidate, frames, feedMode, session) }, "m1-spp-test-print").start()
        } catch (_: SecurityException) {
            report += "form_feed_commands=0\n"
            report += "short_feed_commands=0\n"
            finish(PrinterProbeStatus.PermissionRequired, "error=permission_required")
            printResult = M1TestPrintResult.Rejected
        } catch (_: RuntimeException) {
            report += "form_feed_commands=0\n"
            report += "short_feed_commands=0\n"
            finish(PrinterProbeStatus.ConnectionFailed, "error=worker_start")
            printResult = M1TestPrintResult.Rejected
        }
    }

    private fun prepareM1Operation(address: String) {
        releaseHprtSession()
        stopScan()
        timeoutTask?.let(handler::removeCallbacks)
        timeoutTask = null
        val connection = gatt
        gatt = null
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
        val previous = synchronized(sppOwnershipLock) {
            generation++
            m1IdleCloseTask?.let(handler::removeCallbacks)
            m1IdleCloseTask = null
            sppSession.also {
                sppSession = null
                it?.connection?.let(m1Connections::closeIfOwned)
                if (m1Connections.get(address) == null) m1Connections.closeCurrent()
            }
        }
        previous?.let(::cancelSppDeadline)
        previous?.sockets?.cancel()
        if (previous != null) {
            if (previous.isPrint && busy) {
                appendPrintProgress(previous)
                val interrupted = interruptedPrintResult(previous.bytesAttempted)
                report += "print_tested=${interrupted.name.lowercase()}\n"
                report += "print_result=${interrupted.name.lowercase()}\n"
                printResult = interrupted
            }
        }
    }

    private fun acquireM1Connection(
        candidate: PrinterDeviceCandidate,
        session: SppSession,
    ): M1Connection? {
        synchronized(sppOwnershipLock) {
            val retained = m1Connections.getIfCurrent(candidate.address) { isCurrentSpp(session) }
            if (retained == null && !isCurrentSpp(session)) return null
            retained?.let {
                if (retained.socket.isConnected && isCurrentSpp(session)) {
                    session.connection = retained
                    postSpp(session) {
                        report += "connection_reused=true\n"
                        report += "stage=connected\n"
                    }
                    return retained
                }
                m1Connections.closeIfOwned(retained)
            }
        }

        val socket = candidate.device.createInsecureRfcommSocketToServiceRecord(M1SppProtocol.sppUuid)
        if (!session.sockets.attach(socket) || !isCurrentSpp(session)) {
            session.sockets.cancel()
            return null
        }
        runCatching { adapter?.cancelDiscovery() }
        scheduleSppDeadline(session, CONNECT_TIMEOUT_MS, "connect")
        socket.connect()
        cancelSppDeadline(session)
        val connection = M1Connection(socket, socket.inputStream, socket.outputStream)
        synchronized(sppOwnershipLock) {
            session.sockets.releaseCurrent()
            if (!m1Connections.installIfCurrent(candidate.address, connection) { isCurrentSpp(session) }) {
                return null
            }
            session.connection = connection
        }
        postSpp(session) {
            report += "connection_reused=false\n"
            report += "stage=connected\n"
        }
        return connection
    }

    private fun completeM1Operation(session: SppSession, result: PrinterProbeStatus, detail: String) {
        synchronized(sppOwnershipLock) {
            if (!isCurrentSpp(session)) return
            sppSession = null
        }
        report += "$detail\n"
        cancelSppDeadline(session)
        status = result
        val connection = session.connection ?: return
        lateinit var idleClose: Runnable
        idleClose = Runnable {
            synchronized(sppOwnershipLock) {
                if (m1IdleCloseTask === idleClose && sppSession == null && m1Connections.closeIfOwned(connection)) {
                    m1IdleCloseTask = null
                    report += "connection_idle_closed=true\n"
                }
            }
        }
        m1IdleCloseTask = idleClose
        handler.postDelayed(idleClose, M1_IDLE_TIMEOUT_MS)
    }

    private fun runM1TestPrint(
        candidate: PrinterDeviceCandidate,
        frames: List<M1PrintFrame>,
        feedMode: M1FeedMode,
        session: SppSession,
    ) {
        var keepConnection = false
        try {
            val connection = acquireM1Connection(candidate, session) ?: return
            val output = connection.output
            val input = connection.input
            scheduleSppDeadline(session, MODEL_STAGE_TIMEOUT_MS, "model_query")
            output.write(M1SppProtocol.queryModel)
            output.flush()
            var modelQueryReply = readM1QueryReply(input, MODEL_QUERY_TIMEOUT_MS, session)
            var modelAsyncFrames = modelQueryReply.asyncStatusCodes.size
            if (modelQueryReply.reply.isEmpty() && !modelQueryReply.overflowed && isCurrentSpp(session)) {
                output.write(M1SppProtocol.queryModelFallback)
                output.flush()
                modelQueryReply = readM1QueryReply(input, FALLBACK_QUERY_TIMEOUT_MS, session)
                modelAsyncFrames += modelQueryReply.asyncStatusCodes.size
            }
            cancelSppDeadline(session)
            val modelReply = modelQueryReply.reply
            val modelResult = if (modelQueryReply.overflowed) {
                M1SppProtocol.ModelResult.Mismatch
            } else M1SppProtocol.parseModelReply(modelReply)
            postSpp(session) {
                report += "model_query_stage=complete\n"
                report += "query_model=${modelResult.name.lowercase()}\n"
                report += "model_reply_bytes=${modelReply.size}\n"
                report += "model_async_frames=$modelAsyncFrames\n"
                report += "model_reply_overflow=${modelQueryReply.overflowed}\n"
                if (modelResult == M1SppProtocol.ModelResult.Matched) report += "model=M1\n"
            }
            if (modelResult != M1SppProtocol.ModelResult.Matched) {
                rejectPrint(session, when {
                    modelQueryReply.overflowed -> "model_reply_too_large"
                    modelReply.isEmpty() -> "model_no_response"
                    else -> "model_unrecognized_response"
                })
                return
            }

            scheduleSppDeadline(session, STATUS_STAGE_TIMEOUT_MS, "status_query")
            output.write(M1SppProtocol.queryStatus)
            output.flush()
            val statusReply = readM1QueryReply(input, STATUS_QUERY_TIMEOUT_MS, session)
            cancelSppDeadline(session)
            val statusResult = statusReply.classifyStatus()
            val statusCode = (statusResult as? M1SppProtocol.StatusResult.Received)?.code
            postSpp(session) {
                report += "status_query_stage=complete\n"
                report += "status_reply_bytes=${statusReply.reply.size}\n"
                report += "status_async_frames=${statusReply.asyncStatusCodes.size}\n"
                report += "status_reply_overflow=${statusReply.overflowed}\n"
                if (statusResult is M1SppProtocol.StatusResult.Received) {
                    report += "status_code=${statusResult.code}\n"
                    report += "status_source=${statusResult.source.name.lowercase()}\n"
                    M1SppProtocol.statusBits(statusResult.code).forEach { report += "status_bit=$it\n" }
                }
            }
            if (statusCode != 0) {
                rejectPrint(session, when (statusResult) {
                    M1SppProtocol.StatusResult.NoResponse -> "status_no_response"
                    M1SppProtocol.StatusResult.Invalid -> "status_invalid"
                    is M1SppProtocol.StatusResult.Received -> "status_not_ready"
                })
                return
            }

            postSpp(session) { status = PrinterProbeStatus.Printing; report += "stage=sending\n" }
            scheduleSppDeadline(session, PRINT_STAGE_TIMEOUT_MS, "print_write")
            postSpp(session) {
                report += "alignment=right\n"
                report += "raw_frame_budget_bytes=${M1SppPrintProtocol.MAX_RAW_CHUNK_BYTES}\n"
            }
            M1SppPrintProtocol.printParts(frames, feedMode).forEach { part ->
                if (!isCurrentSpp(session)) return
                if (!writePrintPart(output, part.bytes, session)) return
                if (part.isImageFrame) session.framesSent++
                if (part.isFormFeed) session.formFeedCommands++
                if (part.isShortFeed) session.shortFeedCommands++
            }
            session.printWriteComplete = true
            cancelSppDeadline(session)
            val postFeedReply = readM1QueryReply(input, POST_FEED_READ_TIMEOUT_MS, session, fullWindow = true)
            val postFeedStatus = postFeedReply.classifyStatus()
            val confirmation = confirmPostPrintModel(
                query = {
                    scheduleSppDeadline(session, MODEL_STAGE_TIMEOUT_MS, "post_print_model_query")
                    output.write(M1SppProtocol.queryModel)
                    output.flush()
                    readM1QueryReply(input, POST_PRINT_MODEL_TIMEOUT_MS, session, fullWindow = true).also {
                        cancelSppDeadline(session)
                    }
                },
                drain = {
                    readM1QueryReply(input, POST_PRINT_DRAIN_TIMEOUT_MS, session, fullWindow = true)
                },
            )
            val postPrintModelResult = confirmation.result
            keepConnection = postPrintModelResult == M1SppProtocol.ModelResult.Matched
            postSpp(session) {
                appendPrintProgress(session)
                canContinueBatch = m1BatchReady(postFeedReply, confirmation)
                report += "batch_ready=$canContinueBatch\n"
                report += "post_feed_reply_bytes=${postFeedReply.reply.size}\n"
                report += "post_feed_processing_events=${postFeedReply.asyncPrintFinishCount}\n"
                if (postFeedStatus is M1SppProtocol.StatusResult.Received) {
                    report += "post_feed_status_code=${postFeedStatus.code}\n"
                    report += "post_feed_status_source=${postFeedStatus.source.name.lowercase()}\n"
                    M1SppProtocol.statusBits(postFeedStatus.code).forEach { report += "post_feed_status_bit=$it\n" }
                }
                report += "post_print_model_first=${confirmation.firstResult.name.lowercase()}\n"
                report += "post_print_model_first_reply_bytes=${confirmation.firstReply.reply.size}\n"
                report += "post_print_model_first_async_frames=${confirmation.firstReply.asyncStatusCodes.size}\n"
                report += "post_print_model_first_processing_events=${confirmation.firstReply.asyncPrintFinishCount}\n"
                confirmation.drainedReply?.let {
                    report += "post_print_model_drain_reply_bytes=${it.reply.size}\n"
                    report += "post_print_model_drain_async_frames=${it.asyncStatusCodes.size}\n"
                    report += "post_print_model_drain_processing_events=${it.asyncPrintFinishCount}\n"
                }
                confirmation.retryResult?.let {
                    report += "post_print_model_retry=${it.name.lowercase()}\n"
                    report += "post_print_model_retry_reply_bytes=${confirmation.retryReply?.reply?.size ?: 0}\n"
                    report += "post_print_model_retry_async_frames=${confirmation.retryReply?.asyncStatusCodes?.size ?: 0}\n"
                    report += "post_print_model_retry_processing_events=${confirmation.retryReply?.asyncPrintFinishCount ?: 0}\n"
                }
                report += "post_print_model=${postPrintModelResult.name.lowercase()}\n"
                report += "print_tested=sent_unconfirmed\n"
                printResult = if (postPrintModelResult == M1SppProtocol.ModelResult.Matched) {
                    completeM1Operation(session, PrinterProbeStatus.Complete, "print_result=sent_unconfirmed")
                    M1TestPrintResult.SentUnconfirmed
                } else {
                    val result = postPrintModelUnconfirmedResult(session.bytesSent)
                    report += "print_result=${result.name.lowercase()}\n"
                    finish(
                        PrinterProbeStatus.Complete,
                        "warning=post_print_model_unconfirmed",
                    )
                    result
                }
            }
        } catch (_: SecurityException) {
            postSpp(session) {
                appendPrintProgress(session)
                finish(PrinterProbeStatus.PermissionRequired, "error=permission_required")
                printResult = failedPrintResult(session.bytesAttempted)
            }
        } catch (_: java.io.IOException) {
            postSpp(session) {
                appendPrintProgress(session)
                val result = if (session.printWriteComplete) {
                    M1TestPrintResult.SentConnectionLost
                } else failedPrintResult(session.bytesAttempted)
                report += "print_tested=${result.name.lowercase()}\n"
                finish(
                    PrinterProbeStatus.ConnectionFailed,
                    if (result == M1TestPrintResult.SentConnectionLost) {
                        "error=post_print_connection_lost"
                    } else "print_result=${result.name.lowercase()}",
                )
                printResult = result
            }
        } catch (_: Exception) {
            postSpp(session) {
                appendPrintProgress(session)
                val result = failedPrintResult(session.bytesAttempted)
                report += "print_tested=${result.name.lowercase()}\n"
                finish(PrinterProbeStatus.ConnectionFailed, "print_result=${result.name.lowercase()}")
                printResult = result
            }
        } finally {
            cancelSppDeadline(session)
            session.sockets.closeCurrent()
            if (!keepConnection) session.connection?.let(m1Connections::closeIfOwned)
        }
    }

    private fun writePrintPart(output: java.io.OutputStream, bytes: ByteArray, session: SppSession): Boolean {
        if (!isCurrentSpp(session)) return false
        session.writeStarted = true
        session.bytesAttempted += bytes.size
        var offset = 0
        while (offset < bytes.size && isCurrentSpp(session)) {
            val count = minOf(PRINT_WRITE_CHUNK_BYTES, bytes.size - offset)
            output.write(bytes, offset, count)
            output.flush()
            offset += count
            session.bytesSent += count
        }
        session.writeStarted = false
        return offset == bytes.size
    }

    private fun appendPrintProgress(session: SppSession) {
        report += "frames_sent=${session.framesSent}\n"
        report += "bytes_attempted=${session.bytesAttempted}\n"
        report += "bytes_sent_confirmed=${session.bytesSent}\n"
        report += "form_feed_commands=${session.formFeedCommands}\n"
        report += "short_feed_commands=${session.shortFeedCommands}\n"
        report += "bytes_count=completed_writes_only\n"
        if (session.writeStarted) report += "write_inflight=uncertain\n"
    }

    private fun rejectPrint(session: SppSession, error: String) {
        postSpp(session) {
            appendPrintProgress(session)
            report += "print_tested=not_sent\n"
            finish(PrinterProbeStatus.ConnectionFailed, "error=$error")
            printResult = M1TestPrintResult.Rejected
        }
    }

    private fun runM1SppProbe(candidate: PrinterDeviceCandidate, session: SppSession) {
        var keepConnection = false
        try {
            val connection = acquireM1Connection(candidate, session) ?: return
            val output = connection.output
            val input = connection.input
            scheduleSppDeadline(session, MODEL_STAGE_TIMEOUT_MS, "model_query")
            output.write(M1SppProtocol.queryModel)
            output.flush()
            var modelQueryReply = readM1QueryReply(input, MODEL_QUERY_TIMEOUT_MS, session)
            var modelAsyncFrames = modelQueryReply.asyncStatusCodes.size
            if (modelQueryReply.reply.isEmpty() && !modelQueryReply.overflowed && isCurrentSpp(session)) {
                output.write(M1SppProtocol.queryModelFallback)
                output.flush()
                modelQueryReply = readM1QueryReply(input, FALLBACK_QUERY_TIMEOUT_MS, session)
                modelAsyncFrames += modelQueryReply.asyncStatusCodes.size
            }
            cancelSppDeadline(session)
            val modelReply = modelQueryReply.reply
            val modelResult = if (modelQueryReply.overflowed) {
                M1SppProtocol.ModelResult.Mismatch
            } else M1SppProtocol.parseModelReply(modelReply)
            postSpp(session) {
                report += "model_query_stage=complete\n"
                report += "query_model=${modelResult.name.lowercase()}\n"
                report += "model_reply_bytes=${modelReply.size}\n"
                report += "model_async_frames=$modelAsyncFrames\n"
                report += "model_reply_overflow=${modelQueryReply.overflowed}\n"
                if (modelResult == M1SppProtocol.ModelResult.Matched) report += "model=M1\n"
            }
            if (modelResult != M1SppProtocol.ModelResult.Matched) {
                val detail = when {
                    modelQueryReply.overflowed -> "error=model_reply_too_large"
                    modelResult == M1SppProtocol.ModelResult.NoResponse -> "error=model_no_response"
                    else -> "error=model_unrecognized_response"
                }
                val terminalStatus = if (modelResult == M1SppProtocol.ModelResult.NoResponse) {
                    PrinterProbeStatus.TimedOut
                } else PrinterProbeStatus.ConnectionFailed
                postSpp(session) { finish(terminalStatus, detail) }
                return
            }

            scheduleSppDeadline(session, STATUS_STAGE_TIMEOUT_MS, "status_query")
            output.write(M1SppProtocol.queryStatus)
            output.flush()
            val statusReply = readM1QueryReply(input, STATUS_QUERY_TIMEOUT_MS, session)
            cancelSppDeadline(session)
            val statusResult = statusReply.classifyStatus()
            if (statusResult is M1SppProtocol.StatusResult.Received) keepConnection = true
            postSpp(session) {
                report += "status_query_stage=complete\n"
                report += "status_reply_bytes=${statusReply.reply.size}\n"
                report += "status_async_frames=${statusReply.asyncStatusCodes.size}\n"
                report += "status_reply_overflow=${statusReply.overflowed}\n"
                when (statusResult) {
                    M1SppProtocol.StatusResult.NoResponse ->
                        finish(PrinterProbeStatus.TimedOut, "status_query=no_response")
                    M1SppProtocol.StatusResult.Invalid ->
                        finish(PrinterProbeStatus.ConnectionFailed, "status_query=invalid")
                    is M1SppProtocol.StatusResult.Received -> {
                        report += "status_result=received\n"
                        report += "status_code=${statusResult.code}\n"
                        report += "status_source=${statusResult.source.name.lowercase()}\n"
                        M1SppProtocol.statusBits(statusResult.code).forEach { report += "status_bit=$it\n" }
                        completeM1Operation(session, PrinterProbeStatus.Complete, "status_query=received")
                    }
                }
            }
        } catch (_: SecurityException) {
            postSpp(session) { permissionDenied() }
        } catch (_: Exception) {
            postSpp(session) { finish(PrinterProbeStatus.ConnectionFailed, "error=connection_or_io") }
        } finally {
            cancelSppDeadline(session)
            session.sockets.closeCurrent()
            if (!keepConnection) session.connection?.let(m1Connections::closeIfOwned)
        }
    }

    private fun readM1QueryReply(
        input: java.io.InputStream,
        timeoutMs: Long,
        session: SppSession,
        fullWindow: Boolean = false,
    ): M1QueryReply {
        val demultiplexer = session.connection?.queryReplyDemultiplexer ?: session.queryReplyDemultiplexer
        val buffer = ByteArray(256)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var replyQuietSince = 0L
        while (System.nanoTime() < deadline && isCurrentSpp(session)) {
            val available = input.available()
            if (available > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) break
                demultiplexer.append(buffer, count)
                if (demultiplexer.hasReply()) replyQuietSince = System.nanoTime()
            } else {
                if (fullWindow) {
                    Thread.sleep(READ_POLL_MS)
                    continue
                }
                if (demultiplexer.hasReply() && System.nanoTime() - replyQuietSince >= READ_QUIET_MS * 1_000_000) break
                Thread.sleep(READ_POLL_MS)
            }
        }
        return demultiplexer.take()
    }


    private fun scheduleSppDeadline(session: SppSession, delayMs: Long, stage: String) {
        cancelSppDeadline(session)
        lateinit var deadline: Runnable
        deadline = Runnable {
            if (session.deadline === deadline && isCurrentSpp(session)) {
                session.deadline = null
                session.sockets.closeCurrent()
                if (session.isPrint) {
                    appendPrintProgress(session)
                    report += "print_tested=${interruptedPrintResult(session.bytesAttempted).name.lowercase()}\n"
                }
                finish(PrinterProbeStatus.TimedOut, "error=${stage}_timeout")
                if (session.isPrint) {
                    printResult = interruptedPrintResult(session.bytesAttempted)
                }
            }
        }
        session.deadline = deadline
        handler.postDelayed(deadline, delayMs)
    }

    private fun cancelSppDeadline(session: SppSession) {
        session.deadline?.let(handler::removeCallbacks)
        session.deadline = null
    }

    private fun isCurrentSpp(session: SppSession): Boolean =
        sessionMayDeliver(session.generation, generation, sppSession === session)

    private fun postSpp(session: SppSession, block: () -> Unit) {
        handler.post { if (isCurrentSpp(session)) block() }
    }

    fun permissionDenied() {
        stop()
        status = PrinterProbeStatus.PermissionRequired
    }

    private fun finish(result: PrinterProbeStatus, detail: String) {
        report += "$detail\n"
        stopInternal(recordPrintInterruption = false)
        status = result
    }

    private fun stopScan() {
        stopScanTask?.let(handler::removeCallbacks)
        stopScanTask = null
        scanCallback?.let { callback -> runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) } }
        scanCallback = null
    }

    fun stop() = stopInternal(recordPrintInterruption = true)

    private fun stopInternal(recordPrintInterruption: Boolean) {
        if (recordPrintInterruption && p1Operation != null && busy) {
            printResult = if (status == PrinterProbeStatus.Printing) M1TestPrintResult.Partial else M1TestPrintResult.Interrupted
            report += "error=p1_cancelled\n"
        }
        p1Operation?.cancel()
        p1Operation = null
        if (recordPrintInterruption && hprtOperation != null && busy) {
            printResult = if (status == PrinterProbeStatus.Printing) M1TestPrintResult.Partial else M1TestPrintResult.Interrupted
            report += "error=hprt_cancelled\n"
        }
        releaseHprtSession()
        val session = synchronized(sppOwnershipLock) {
            generation++
            m1IdleCloseTask?.let(handler::removeCallbacks)
            m1IdleCloseTask = null
            sppSession.also {
                sppSession = null
                m1Connections.closeCurrent()
            }
        }
        stopScan()
        timeoutTask?.let(handler::removeCallbacks)
        timeoutTask = null
        val connection = gatt
        gatt = null
        runCatching { connection?.disconnect() }
        runCatching { connection?.close() }
        if (recordPrintInterruption && session?.isPrint == true && busy) {
            appendPrintProgress(session)
            val interrupted = interruptedPrintResult(session.bytesAttempted)
            report += "print_tested=${interrupted.name.lowercase()}\n"
            report += "print_result=${interrupted.name.lowercase()}\n"
            printResult = interrupted
        }
        session?.let(::cancelSppDeadline)
        session?.sockets?.cancel()
        if (busy) {
            status = PrinterProbeStatus.Stopped
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MODEL_QUERY_TIMEOUT_MS = 5_000L
        private const val FALLBACK_QUERY_TIMEOUT_MS = 1_000L
        private const val STATUS_QUERY_TIMEOUT_MS = 3_000L
        private const val MODEL_STAGE_TIMEOUT_MS = 7_000L
        private const val STATUS_STAGE_TIMEOUT_MS = 4_000L
        private const val PRINT_STAGE_TIMEOUT_MS = 15_000L
        private const val POST_FEED_READ_TIMEOUT_MS = 1_000L
        private const val POST_PRINT_MODEL_TIMEOUT_MS = MODEL_QUERY_TIMEOUT_MS
        private const val POST_PRINT_DRAIN_TIMEOUT_MS = 250L
        private const val M1_IDLE_TIMEOUT_MS = 60_000L
        private val hprtCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val PRINT_WRITE_CHUNK_BYTES = 1_024
        private const val READ_QUIET_MS = 150L
        private const val READ_POLL_MS = 20L
        private const val MAX_REPLY_BYTES = 512

        private class SppSession(
            val generation: Int,
            val address: String,
            val isPrint: Boolean = false,
        ) {
            val sockets = CancelableResourceSlot<BluetoothSocket> { socket -> runCatching { socket.close() } }
            val queryReplyDemultiplexer = M1QueryReplyDemultiplexer()
            @Volatile var connection: M1Connection? = null
            @Volatile var deadline: Runnable? = null
            @Volatile var framesSent = 0
            @Volatile var bytesSent = 0
            @Volatile var bytesAttempted = 0
            @Volatile var writeStarted = false
            @Volatile var printWriteComplete = false
            @Volatile var formFeedCommands = 0
            @Volatile var shortFeedCommands = 0
        }

        private class M1Connection(
            val socket: BluetoothSocket,
            val input: java.io.InputStream,
            val output: java.io.OutputStream,
        ) {
            val queryReplyDemultiplexer = M1QueryReplyDemultiplexer()
            fun close() = runCatching { socket.close() }.let { Unit }
        }

        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
