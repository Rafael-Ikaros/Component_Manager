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
    var selectedProfileId by mutableStateOf(preferences.getString("printer_profile", "m1").orEmpty().let {
        runCatching { LabelPrinterProfile.fromId(it).id }.getOrDefault("m1")
    })
        private set
    var selectedMediaType by mutableStateOf(preferences.getString("media_type", "gap").orEmpty().let {
        runCatching { LabelMediaType.fromId(it).id }.getOrDefault("gap")
    })
        private set
    fun selectProfile(id: String) {
        if (running || working || closed || queue.items.isNotEmpty()) return
        selectedProfileId = LabelPrinterProfile.fromId(id).id
        preferences.edit().putString("printer_profile", id).apply()
    }
    fun selectMedia(id: String) {
        if (running || working || closed || queue.items.isNotEmpty()) return
        selectedMediaType = LabelMediaType.fromId(id).id
        preferences.edit().putString("media_type", id).apply()
    }
    private var stopRequested = false
    private var closed = false

    fun selectDevice(address: String) {
        if (running || working || closed) return
        selectedAddress = address
        preferences.edit().putString("device_address", address).apply()
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
            releaseFinishedHprtQueue()
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
            releaseFinishedHprtQueue()
        }
    }

    private fun releaseFinishedHprtQueue() {
        if (queue.printerProfileId == LabelPrinterProfile.HmA300eCpcl.id &&
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
        if (queue.printerProfileId == LabelPrinterProfile.HmA300eCpcl.id && !transport.hprtSdkAvailable) {
            error = "printer_hprt_sdk_missing"
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
                        if (queue.printerProfileId == LabelPrinterProfile.M1.id)
                            transport.printM1Label(candidate, bitmap, queue.paper)
                        else transport.printHmA300eLabel(candidate, bitmap, queue.paper,
                            queue.mediaType == LabelMediaType.Continuous.id)
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
