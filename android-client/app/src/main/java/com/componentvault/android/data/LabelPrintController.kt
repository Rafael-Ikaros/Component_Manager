package com.componentvault.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.componentvault.android.model.ComponentLabelSeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One foreground queue, one transport, one in-flight label. No automatic replay of uncertain writes. */
internal class LabelPrintController(context: Context) {
    private val storageGeneration = storageAccess.claim()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = LabelPrintQueueStore(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE)
    private val printerPreferences = LabelPrinterPreferences(preferences)
    val transport = BluetoothPrinterDiagnostics(context.applicationContext)
    var queue by mutableStateOf(LabelPrintQueue())
        private set
    var loaded by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var pauseRequested by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedAddress by mutableStateOf(preferences.getString("device_address", "").orEmpty())
        private set
    var savedPrinters by mutableStateOf(printerPreferences.load())
        private set
    var selectedProfileId by mutableStateOf(preferences.getString("printer_profile", null).orEmpty().let {
        runCatching { LabelPrinterProfile.fromId(it).configurable.id }.getOrElse {
            if (selectedAddress.isNotBlank()) LabelPrinterProfile.M1.id else LabelPrinterProfile.GenericCpcl.id
        }
    })
        private set
    var selectedMediaType by mutableStateOf(preferences.getString("media_type", "gap").orEmpty().let {
        runCatching { LabelMediaType.fromId(it).id }.getOrDefault("gap")
    })
        private set
    fun selectProfile(id: String) {
        if (running || working || closed || queue.items.isNotEmpty()) return
        val profile = LabelPrinterProfile.fromId(id).configurable
        selectedProfileId = profile.id
        if (!profile.usesCpcl) selectedMediaType = LabelMediaType.Gap.id
        savePrinterSelection()
    }
    fun selectMedia(id: String) {
        if (running || working || closed || queue.items.isNotEmpty()) return
        selectedMediaType = LabelMediaType.fromId(id).id
        savePrinterSelection()
    }
    private var stopRequested = false
    private var closed = false

    private fun profileFor(candidate: PrinterDeviceCandidate): LabelPrinterProfile {
        val stored = savedPrinters.firstOrNull { it.address == candidate.address }
        return when {
            stored != null -> LabelPrinterProfile.fromId(stored.profileId)
            candidate.address == selectedAddress -> LabelPrinterProfile.fromId(selectedProfileId)
            candidate.name.equals("M1", ignoreCase = true) ||
                candidate.name?.startsWith("M1-", ignoreCase = true) == true -> LabelPrinterProfile.M1
            else -> LabelPrinterProfile.GenericCpcl
        }
    }

    fun canSelectDevice(candidate: PrinterDeviceCandidate): Boolean =
        !running && !working && !closed &&
            (queue.items.isEmpty() || LabelPrinterProfile.fromId(queue.printerProfileId).usesCpcl == profileFor(candidate).usesCpcl)

    fun selectDevice(candidate: PrinterDeviceCandidate): Boolean {
        if (!canSelectDevice(candidate)) return false
        val stored = savedPrinters.firstOrNull { it.address == candidate.address }
        val profile = profileFor(candidate)
        val media = if (candidate.address == selectedAddress) selectedMediaType else stored?.mediaType
        selectedAddress = candidate.address
        selectedProfileId = profile.id
        selectedMediaType = if (profile.usesCpcl) media ?: LabelMediaType.Gap.id else LabelMediaType.Gap.id
        rememberPrinter(candidate)
        return true
    }

    fun restoreSavedPrinter(candidate: PrinterDeviceCandidate) {
        if (candidate.address == selectedAddress && savedPrinters.none { it.address == selectedAddress }) {
            rememberPrinter(candidate)
        }
    }

    fun forgetPrinter(address: String) {
        if (running || working || closed || (address == selectedAddress && queue.items.isNotEmpty())) return
        savedPrinters = savedPrinters.filterNot { it.address == address }
        if (selectedAddress == address) {
            selectedAddress = ""
            selectedProfileId = LabelPrinterProfile.GenericCpcl.id
            selectedMediaType = LabelMediaType.Gap.id
        }
        savePrinterSelection()
    }

    private fun rememberPrinter(candidate: PrinterDeviceCandidate) {
        val printer = SavedLabelPrinter(candidate.address, candidate.name.orEmpty(), selectedProfileId, selectedMediaType)
        savedPrinters = listOf(printer) + savedPrinters.filterNot { it.address == candidate.address }
        savePrinterSelection()
    }

    private fun savePrinterSelection() {
        savedPrinters = savedPrinters.map {
            if (it.address == selectedAddress) it.copy(profileId = selectedProfileId, mediaType = selectedMediaType)
            else it
        }
        printerPreferences.save(savedPrinters, selectedAddress, selectedProfileId, selectedMediaType)
    }

    fun load() {
        if (loaded || working || closed) return
        working = true
        scope.launch {
            try {
                queue = withContext(Dispatchers.IO) { storageAccess.access(storageGeneration) { store.load() } }
                loaded = true
                error = null
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                error = "queue_load_failed"
            } finally {
                working = false
            }
        }
    }

    fun create(
        selections: List<Pair<ComponentLabelSeed, Int>>,
        templateId: String,
        textTemplateId: String,
        paper: M1TestPaperProfile,
        designs: Map<String, LabelDesign> = emptyMap(),
    ) {
        if (!loaded || working || running || closed) return
        if (queue.items.isNotEmpty()) {
            error = "queue_review_required"
            return
        }
        val next = try {
            LabelPrintQueue.create(selections, templateId, textTemplateId, paper, designs, selectedProfileId, selectedMediaType)
        } catch (_: IllegalArgumentException) {
            error = "queue_invalid_selection"
            return
        }
        mutate { persist(next) }
    }

    fun createFree(freeLabel: FreeLabel, copies: Int, paper: M1TestPaperProfile,
                   design: LabelDesign) {
        if (!loaded || working || running || closed) return
        if (queue.items.isNotEmpty()) {
            error = "queue_review_required"
            return
        }
        val next = try {
            LabelPrintQueue.createFree(freeLabel, copies, paper, design, selectedProfileId, selectedMediaType)
        } catch (_: IllegalArgumentException) {
            error = "queue_invalid_selection"
            return
        }
        mutate { persist(next) }
    }

    fun clear() {
        if (working || running || closed) return
        mutate {
            // Clear label snapshots but retain the last explicitly chosen paper and templates.
            persist(queue.copy(items = emptyList()))
            releaseFinishedCpclQueue()
            loaded = true
        }
    }

    fun resolve(id: String, state: LabelPrintItemState) {
        if (!loaded || working || running || closed) return
        val current = queue.items.firstOrNull { it.id == id } ?: return
        if (current.state !in setOf(LabelPrintItemState.Failed, LabelPrintItemState.Uncertain)) return
        if (state !in setOf(LabelPrintItemState.Pending, LabelPrintItemState.Sent, LabelPrintItemState.Skipped)) return
        mutate {
            persist(queue.update(id, state))
            releaseFinishedCpclQueue()
        }
    }

    private fun releaseFinishedCpclQueue() {
        if (LabelPrinterProfile.fromId(queue.printerProfileId).usesCpcl &&
            queue.items.all { it.state == LabelPrintItemState.Sent || it.state == LabelPrintItemState.Skipped }
        ) transport.stop()
    }

    private fun mutate(block: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                block()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                error = "queue_save_failed"
            } finally {
                working = false
            }
        }
    }

    private suspend fun persist(next: LabelPrintQueue) {
        // Publish only after the disk write, and never send before the Sending checkpoint is durable.
        withContext(Dispatchers.IO) { storageAccess.access(storageGeneration) { store.save(next) } }
        queue = next
    }

    fun start(candidate: PrinterDeviceCandidate) {
        if (!loaded || working || running || closed || transport.busy) return
        if (!canSelectDevice(candidate)) {
            error = "printer_profile_mismatch"
            return
        }
        if (queue.items.any { it.state in setOf(LabelPrintItemState.Uncertain, LabelPrintItemState.Failed, LabelPrintItemState.Sending) }) {
            error = "queue_review_required"
            return
        }
        if (queue.items.none { it.state == LabelPrintItemState.Pending }) return
        running = true
        pauseRequested = false
        stopRequested = false
        error = null
        scope.launch {
            var sendingId: String? = null
            try {
                while (!stopRequested && !pauseRequested) {
                    val item = queue.items.firstOrNull { it.state == LabelPrintItemState.Pending } ?: break
                    var rendered: android.graphics.Bitmap? = null
                    val bitmap = try {
                        withContext(Dispatchers.Default) {
                            M1ComponentLabelRenderer.render(
                                item.seed,
                                ComponentLabelTemplate.fromId(queue.templateId),
                                ComponentTextLabelTemplate.fromId(queue.textTemplateId),
                                queue.paper,
                                item.design,
                                item.freeLabel,
                            ).also { rendered = it }
                        }
                    } catch (exception: CancellationException) {
                        rendered?.recycle()
                        throw exception
                    } catch (exception: IllegalArgumentException) {
                        persist(queue.update(item.id, LabelPrintItemState.Failed, "label_does_not_fit"))
                        error = "label_does_not_fit"
                        break
                    }
                    try {
                        if (stopRequested || pauseRequested) break
                        persist(queue.update(item.id, LabelPrintItemState.Sending))
                        sendingId = item.id
                        if (stopRequested || pauseRequested) {
                            persist(queue.update(item.id, LabelPrintItemState.Pending))
                            sendingId = null
                            break
                        }
                        val profile = LabelPrinterProfile.fromId(queue.printerProfileId)
                        val media = LabelMediaType.fromId(queue.mediaType)
                        when {
                            profile == LabelPrinterProfile.M1 -> transport.printM1Label(candidate, bitmap, queue.paper)
                            profile == LabelPrinterProfile.HmA300eCpcl && transport.hprtSdkAvailable ->
                                transport.printHmA300eLabel(candidate, bitmap, queue.paper, media == LabelMediaType.Continuous)
                            else -> transport.printCpclLabel(candidate, bitmap, media)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    val result = snapshotFlow { transport.printResult to transport.busy }
                        .first { (result, busy) -> result != M1TestPrintResult.None && !busy }.first
                    val state = labelPrintOutcomeState(result, transport.canContinueBatch)
                    val detail = when (state) {
                        LabelPrintItemState.Sent -> ""
                        LabelPrintItemState.Uncertain -> "print_check_label"
                        else -> printerProblem(transport.report)
                    }
                    persist(queue.update(item.id, state, detail))
                    sendingId = null
                    if (state != LabelPrintItemState.Sent) {
                        error = detail
                        break
                    }
                }
            } catch (exception: Exception) {
                transport.stop()
                if (exception !is CancellationException) error = "queue_save_or_print_failed"
            } finally {
                // A disposed screen or process interruption may occur after bytes were written.
                // Persist uncertainty rather than putting that item back into the automatic queue.
                sendingId?.let { id ->
                    val recovered = queue.update(id, LabelPrintItemState.Uncertain, "print_check_label")
                    queue = recovered
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { storageAccess.access(storageGeneration) { store.save(recovered) } }
                    }
                }
                running = false
                pauseRequested = false
            }
        }
    }

    fun pause() {
        if (running) pauseRequested = true
    }

    fun stop() {
        stopRequested = true
        transport.stop()
    }

    fun clearError() { error = null }

    fun close() {
        if (closed) return
        closed = true
        stop()
        scope.cancel()
    }

    companion object {
        // A reopened screen takes ownership; an older finally block cannot resurrect its queue.
        private val storageAccess = LabelPrintQueueAccess()
    }
}

internal fun labelPrintOutcomeState(result: M1TestPrintResult, batchReady: Boolean): LabelPrintItemState = when (result) {
    M1TestPrintResult.SentUnconfirmed -> if (batchReady) LabelPrintItemState.Sent else LabelPrintItemState.Uncertain
    M1TestPrintResult.SentConnectionLost, M1TestPrintResult.Partial -> LabelPrintItemState.Uncertain
    M1TestPrintResult.Interrupted, M1TestPrintResult.Rejected -> LabelPrintItemState.Failed
    M1TestPrintResult.None -> LabelPrintItemState.Uncertain
}

private fun printerProblem(report: String): String = when {
    "status_bit=paper_out" in report -> "printer_paper_out"
    "status_bit=cover_open" in report -> "printer_cover_open"
    "status_bit=locate_failed" in report -> "printer_locate_failed"
    "error=bluetooth_off" in report -> "printer_bluetooth_off"
    "error=permission_required" in report -> "printer_permission_required"
    "error=unsupported_device" in report || "error=model_unrecognized_response" in report -> "printer_not_m1"
    "error=model_no_response" in report -> "printer_no_response"
    "error=hprt_sdk_missing" in report -> "printer_hprt_sdk_missing"
    "error=hprt_status_no_response" in report -> "printer_no_response"
    else -> "printer_not_ready"
}
