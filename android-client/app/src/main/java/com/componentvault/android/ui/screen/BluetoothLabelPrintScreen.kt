package com.componentvault.android.ui.screen

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.componentvault.android.R
import com.componentvault.android.data.BluetoothPrinterDiagnostics
import com.componentvault.android.data.ComponentLabelTemplate
import com.componentvault.android.data.ComponentTextLabelTemplate
import com.componentvault.android.data.LabelPrintController
import com.componentvault.android.data.LabelPrintOffset
import com.componentvault.android.data.LabelPrintQueue
import com.componentvault.android.data.FreeLabel
import com.componentvault.android.data.FreeLabelTemplate
import com.componentvault.android.data.LabelDesign
import com.componentvault.android.data.LabelElement
import com.componentvault.android.data.LabelElementType
import com.componentvault.android.data.LabelPrintItemState
import com.componentvault.android.data.M1ComponentLabelRenderer
import com.componentvault.android.data.LabelPrinterProfile
import com.componentvault.android.data.LabelMediaType
import com.componentvault.android.data.M1TestPaperProfile
import com.componentvault.android.data.PrinterDeviceCandidate
import com.componentvault.android.data.SupvanT50PrintProtocol
import com.componentvault.android.model.ComponentLabelSeed
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.toLabelSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class PrintableComponent(val id: String, val seed: ComponentLabelSeed)
private data class PreviewRequest(
    val seeds: List<ComponentLabelSeed>,
    val templateId: String,
    val textTemplateId: String,
    val paper: M1TestPaperProfile?,
    val designs: Map<String, LabelDesign>,
    val freeLabel: FreeLabel?,
    val validateSupvanArea: Boolean,
)
private data class PrintPreview(
    val request: PreviewRequest? = null,
    val bitmap: Bitmap? = null,
    val error: String? = null,
    val fitsPrintArea: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BluetoothLabelPrintScreen(
    components: List<ComponentRecord>,
    initialSeed: ComponentLabelSeed?,
    initialTemplateId: String = ComponentLabelTemplate.default.id,
    initialTextTemplateId: String = ComponentTextLabelTemplate.default.id,
    initialToolTemplate: LabelToolTemplate = LabelToolTemplate.Component,
    initialPaper: M1TestPaperProfile? = null,
    initialFreeTitle: String? = null,
    initialFreeQrPayload: String? = null,
    initialComponentIds: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    val freeMode = initialToolTemplate !in setOf(
        LabelToolTemplate.Component, LabelToolTemplate.ComponentCompact, LabelToolTemplate.ComponentText)
    val selectedInitialTemplateId = when (initialToolTemplate) {
        LabelToolTemplate.ComponentCompact -> ComponentLabelTemplate.Qr10x40.id
        LabelToolTemplate.ComponentText -> ComponentLabelTemplate.TextOnly.id
        else -> initialTemplateId
    }
    val freeTemplate = when (initialToolTemplate) {
        LabelToolTemplate.Qr -> FreeLabelTemplate.Qr
        LabelToolTemplate.Free -> FreeLabelTemplate.Free
        else -> FreeLabelTemplate.Text
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) { LabelPrintController(context) }
    val paperStore = remember(context) { com.componentvault.android.data.LabelPaperPresetStore(context) }
    val choices = remember(components, initialSeed, freeMode) {
        if (freeMode) emptyList<PrintableComponent>() else buildList {
            if (initialSeed != null) add(PrintableComponent("initial:${initialSeed.sku}", initialSeed))
            components.filterNot(ComponentRecord::deleted).filter { initialSeed == null || it.sku != initialSeed.sku }
                .forEach { add(PrintableComponent(it.id, it.toLabelSeed())) }
        }
    }
    val initiallySelected = remember(choices, initialSeed, initialComponentIds) {
        choices.filter { it.id in initialComponentIds || it.id == "initial:${initialSeed?.sku}" }.map { it.id }
    }
    val selected = rememberSaveable(initiallySelected, saver = mapSaver(
        save = { it.toMap() },
        restore = { saved -> mutableStateMapOf<String, Boolean>().apply {
            saved.forEach { (key, value) -> put(key, value as Boolean) }
        } },
    )) {
        mutableStateMapOf<String, Boolean>().apply { initiallySelected.forEach { put(it, true) } }
    }
    val copies = rememberSaveable(saver = mapSaver(
        save = { it.toMap() },
        restore = { saved -> mutableStateMapOf<String, String>().apply {
            saved.forEach { (key, value) -> put(key, value as String) }
        } },
    )) { mutableStateMapOf<String, String>() }
    var search by rememberSaveable { mutableStateOf("") }
    var troubleshootingExpanded by remember { mutableStateOf(false) }
    var freeTitle by rememberSaveable { mutableStateOf(initialFreeTitle.takeIf {
        freeTemplate == FreeLabelTemplate.Qr
    } ?: context.getString(when (initialToolTemplate) {
        LabelToolTemplate.Qr -> R.string.label_tool_qr
        LabelToolTemplate.Text -> R.string.label_tool_text
        else -> R.string.label_tool_free
    })) }
    var freeCopies by rememberSaveable { mutableStateOf("1") }
    var editingSku by rememberSaveable { mutableStateOf<String?>(initialSeed?.sku) }
    var selectedElementId by rememberSaveable { mutableStateOf<String?>(
        if (freeTemplate == FreeLabelTemplate.Qr) "qr" else if (freeMode) "text" else null
    ) }
    var invalidGeometryFields by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var draftDesignJson by rememberSaveable { mutableStateOf("{}") }
    var pendingTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTextTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPaper by remember { mutableStateOf<Pair<M1TestPaperProfile, LabelMediaType>?>(null) }
    val draftDesigns = remember(draftDesignJson) { readLabelDrafts(draftDesignJson) }
    var templateId by rememberSaveable(selectedInitialTemplateId) { mutableStateOf(selectedInitialTemplateId) }
    var textTemplateId by rememberSaveable(initialTextTemplateId) { mutableStateOf(initialTextTemplateId) }
    var widthText by rememberSaveable { mutableStateOf("40") }
    var heightText by rememberSaveable { mutableStateOf("60") }
    var rotationText by rememberSaveable { mutableStateOf("0") }
    var restoredPaper by rememberSaveable { mutableStateOf(false) }
    var confirmNewQueue by remember { mutableStateOf(false) }
    var reviewItemId by remember { mutableStateOf<String?>(null) }
    var previewQueueItemId by remember { mutableStateOf<String?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var leaveAfterStop by remember { mutableStateOf(false) }
    val startsInConfirmation = (initialSeed != null || initialComponentIds.isNotEmpty()) && !freeMode
    var showPrintView by rememberSaveable(startsInConfirmation) { mutableStateOf(startsInConfirmation) }
    var editingFromConfirmation by rememberSaveable { mutableStateOf(false) }
    var hasShownQueue by rememberSaveable { mutableStateOf(false) }
    val queue = controller.queue
    val hasQueue = queue.items.isNotEmpty() &&
        (!startsInConfirmation || hasShownQueue || !controller.canReplaceCompletedQueue)
    var sheetPanel by remember { mutableStateOf<String?>(null) }
    var advancedGeometry by rememberSaveable { mutableStateOf(false) }

    fun requestBack() {
        if (controller.running) {
            leaveAfterStop = true
            confirmStop = true
        } else if (!hasQueue && !showPrintView && editingFromConfirmation) {
            editingFromConfirmation = false
            showPrintView = true
        } else if (!hasQueue && showPrintView && !startsInConfirmation) {
            if (controller.working) controller.stop()
            showPrintView = false
        } else {
            if (controller.working) controller.stop()
            onDismiss()
        }
    }

    LaunchedEffect(controller) { controller.load() }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (BluetoothPrinterDiagnostics.permissions().all { grants[it] == true }) {
            localMessage = null
            controller.transport.refreshPaired()
        } else {
            localMessage = context.getString(R.string.bluetooth_label_print_permission)
        }
    }
    fun refreshDevices() {
        localMessage = null
        val permissions = BluetoothPrinterDiagnostics.permissions()
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            controller.transport.refreshPaired()
        } else {
            permissionRequest.launch(permissions)
        }
    }
    val activeProfile = if (hasQueue) LabelPrinterProfile.fromId(queue.printerProfileId)
        else controller.profileForNewQueue ?: LabelPrinterProfile.GenericCpcl
    val printOffset = controller.printOffset
    val paper = remember(widthText, heightText, rotationText, printOffset) {
        runCatching {
            M1TestPaperProfile(
                widthMm = widthText.toFloat(),
                heightMm = heightText.toFloat(),
                rotationDegrees = rotationText.toInt(),
                offsetXmm = printOffset.xMm,
                offsetYmm = printOffset.yMm,
            )
        }.getOrNull()
    }
    val compatiblePaper = paper?.let { runCatching { activeProfile.validate(it) }.isSuccess } == true
    val draftSelections = choices.mapNotNull { choice ->
        if (selected[choice.id] != true) return@mapNotNull null
        val count = (copies[choice.id] ?: "1").toIntOrNull() ?: 0
        choice.seed to count
    }
    val validSelection = draftSelections.isNotEmpty() &&
        draftSelections.all { it.second in 1..99 } && draftSelections.sumOf { it.second } <= 500
    val draftFreeLabel = if (freeMode) FreeLabel(freeTitle.trim(), freeTemplate) else null
    val validFree = draftFreeLabel != null && draftFreeLabel.title.isNotBlank() &&
        (freeCopies.toIntOrNull() ?: 0) in 1..99
    LaunchedEffect(controller.loaded, hasQueue) {
        if (controller.loaded && hasQueue) {
            hasShownQueue = true
            showPrintView = true
        }
    }
    LaunchedEffect(controller.loaded, hasQueue) {
        if (controller.loaded && !hasQueue && !restoredPaper) {
            val rememberedPaper = paperStore.last()
            val selectedPaper = initialPaper ?: rememberedPaper ?: if (initialSeed != null)
                queue.paper.copy(rotationDegrees = 0) else queue.paper
            // Opening a page must not overwrite the selected printer's calibration.
            // Only explicitly applying a paper preset writes its offsets (see applyPaper).
            widthText = selectedPaper.widthMm.toString()
            heightText = selectedPaper.heightMm.toString()
            rotationText = selectedPaper.rotationDegrees.toString()
            restoredPaper = true
        }
    }
    val editable = !hasQueue && controller.loaded && restoredPaper && !controller.running && !controller.working
    LaunchedEffect(paper, editable) {
        if (editable && paper != null) paperStore.saveLast(paper)
    }
    val previewQueueItem = if (hasQueue) queue.items.firstOrNull { it.id == previewQueueItemId }
        ?: queue.items.firstOrNull() else null
    val previewFreeLabel = previewQueueItem?.freeLabel ?: if (!hasQueue) draftFreeLabel else null
    val previewSeeds = if (hasQueue) {
        previewQueueItem?.seed?.let(::listOf).orEmpty()
    } else {
        draftSelections.map { it.first }.distinct().let { seeds ->
            val focused = seeds.firstOrNull { it.sku == editingSku } ?: seeds.firstOrNull()
            if (focused == null) emptyList() else listOf(focused) + seeds.filterNot { it.sku == focused.sku }
        }
    }
    val previewSeed = previewSeeds.firstOrNull()
    val previewPaper = if (hasQueue) queue.paper else paper
    val previewTemplate = if (hasQueue) ComponentLabelTemplate.fromId(queue.templateId)
        else ComponentLabelTemplate.fromId(templateId)
    val previewTextTemplate = if (hasQueue) ComponentTextLabelTemplate.fromId(queue.textTemplateId)
        else ComponentTextLabelTemplate.fromId(textTemplateId)
    val activeDesign = if (previewPaper != null && (previewSeed != null || previewFreeLabel != null)) {
        if (hasQueue) previewQueueItem?.design
        else if (previewFreeLabel != null) draftDesigns["__free__"]
            ?: runCatching {
                LabelDesign.free(freeTemplate, previewPaper).let { design ->
                    if (freeTemplate == FreeLabelTemplate.Qr && initialFreeQrPayload != null)
                        design.withText("text", initialFreeTitle.orEmpty())
                            .withQrPayload("qr", initialFreeQrPayload)
                    else design
                }
            }.getOrNull()
        else requireNotNull(previewSeed).let { component ->
            draftDesigns[component.sku]
                ?: runCatching { LabelDesign.default(component, previewTemplate, previewTextTemplate, previewPaper) }.getOrNull()
        }
    } else null
    fun updateDesign(design: LabelDesign) {
        val key = if (previewFreeLabel != null) "__free__" else previewSeed?.sku ?: return
        draftDesignJson = writeLabelDrafts(draftDesigns + (key to design))
    }
    fun recordGeometryValidity(field: String, invalid: String?) {
        invalidGeometryFields = ArrayList(invalidGeometryFields.toMutableSet().apply {
            if (invalid != null) add(field) else remove(field)
        })
    }
    val printDesigns = if (hasQueue) queue.items.mapNotNull { item ->
        item.design?.let { design -> (item.seed?.sku ?: "__free__") to design }
    }.toMap()
        else if (previewFreeLabel != null) activeDesign?.let { mapOf("__free__" to it) }.orEmpty()
        else draftSelections.mapNotNull { (seed, _) ->
            val design = draftDesigns[seed.sku]
                ?: paper?.let { runCatching { LabelDesign.default(seed, previewTemplate, previewTextTemplate, it) }.getOrNull() }
            design?.let { seed.sku to it }
        }.toMap()
    val previewRequest = PreviewRequest(previewSeeds, previewTemplate.id, previewTextTemplate.id,
        previewPaper, printDesigns, previewFreeLabel,
        validateSupvanArea = !hasQueue && activeProfile == LabelPrinterProfile.SupvanT50Pro)
    var preview by remember { mutableStateOf(PrintPreview()) }
    LaunchedEffect(previewRequest) {
        if ((previewSeed != null || previewFreeLabel != null) && previewPaper != null) {
            var generated: Bitmap? = null
            preview = try {
                val result = withContext(Dispatchers.Default) {
                    var fitsPrintArea = true
                    fun checkPrintArea(bitmap: Bitmap) {
                        if (previewRequest.validateSupvanArea && fitsPrintArea) {
                            fitsPrintArea = SupvanT50PrintProtocol.fitsPrintArea(bitmap.width, bitmap.height, bitmap::getPixel)
                        }
                    }
                    M1ComponentLabelRenderer.render(previewSeed, previewTemplate, previewTextTemplate, previewPaper,
                        printDesigns[previewSeed?.sku ?: "__free__"], previewFreeLabel).also {
                        generated = it
                        checkPrintArea(it)
                    }
                    previewSeeds.drop(1).forEach { seed ->
                        currentCoroutineContext().ensureActive()
                        val bitmap = M1ComponentLabelRenderer.render(seed, previewTemplate, previewTextTemplate,
                            previewPaper, printDesigns[seed.sku])
                        try {
                            checkPrintArea(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    PrintPreview(request = previewRequest, bitmap = generated, fitsPrintArea = fitsPrintArea)
                }
                generated = null
                result
            } catch (error: Exception) {
                generated?.recycle()
                if (error is CancellationException) throw error
                PrintPreview(request = previewRequest, bitmap = preview.bitmap,
                    error = error.message ?: context.getString(R.string.bluetooth_label_print_preview_unavailable))
            }
        } else preview = PrintPreview()
    }
    // Once published to Compose, the RenderThread may retain this bitmap past disposal.
    // Let GC release displayed previews; only recycle temporary, never-published rasters.

    val pairedPrinters = controller.transport.candidates.filter {
        it.paired && it.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL)
    }
    val pairedByAddress = pairedPrinters.associateBy { it.address }
    val savedPrinters = controller.savedPrinters
    val savedAddresses = savedPrinters.mapTo(mutableSetOf()) { it.address }
    val selectedPairedPrinter = pairedByAddress[controller.selectedAddress]
    val selectedCandidate = selectedPairedPrinter?.takeIf(controller::canStartWithDevice)
    val selectedSavedPrinter = savedPrinters.firstOrNull { it.address == controller.selectedAddress }
    val needsProtocol = controller.profileForNewQueue == null
    LaunchedEffect(selectedPairedPrinter) {
        selectedPairedPrinter?.let(controller::restoreSavedPrinter)
    }
    LaunchedEffect(showPrintView, hasQueue) {
        if ((showPrintView || hasQueue) && BluetoothPrinterDiagnostics.permissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) controller.transport.refreshPaired()
    }
    val blankQr = previewFreeLabel != null && activeDesign?.elements?.any {
        it.type == LabelElementType.Qr && it.text.isBlank()
    } == true
    val previewError = preview.takeIf { it.request == previewRequest }?.let {
        it.error ?: if (it.fitsPrintArea) null else context.getString(R.string.label_supvan_print_area)
    }
    val canCreate = editable && (if (draftFreeLabel != null) validFree
        else validSelection && printDesigns.size == draftSelections.size) &&
        !needsProtocol && compatiblePaper && invalidGeometryFields.isEmpty() && !blankQr &&
        preview.request == previewRequest && preview.bitmap != null && previewError == null
    val selectedElement = activeDesign?.elements?.firstOrNull { it.id == selectedElementId }
    fun elementCaption(element: LabelElement, limit: Int): String = element.text.take(limit).ifBlank {
        context.getString(if (element.type == LabelElementType.Text) R.string.label_ui_empty_text
            else R.string.label_ui_empty_qr)
    }

    fun applyPaper(value: M1TestPaperProfile, media: LabelMediaType) {
        widthText = value.widthMm.toString()
        heightText = value.heightMm.toString()
        rotationText = value.rotationDegrees.toString()
        controller.savePrintOffset(LabelPrintOffset(value.offsetXmm, value.offsetYmm))
        controller.selectMedia(media.id)
    }

    fun paperSummary(value: M1TestPaperProfile): String = context.getString(
        R.string.label_ui_paper_summary,
        value.widthMm.toString(), value.heightMm.toString(), value.rotationDegrees.toString(),
    )

    val singleComponent = choices.singleOrNull { selected[it.id] == true }
    val printCount = if (draftFreeLabel != null) freeCopies.toIntOrNull() ?: 0
        else draftSelections.sumOf { it.second }
    val media = when {
        hasQueue -> LabelMediaType.fromId(queue.mediaType)
        activeProfile.supportsContinuous -> LabelMediaType.fromId(controller.selectedMediaType)
        else -> LabelMediaType.Gap
    }
    val mediaName = stringResource(if (media == LabelMediaType.Gap) R.string.label_media_gap else R.string.label_media_continuous)
    val unknownPrinter = stringResource(R.string.bluetooth_label_print_unknown_device)
    val printerSummary = when {
        needsProtocol -> {
            val name = selectedPairedPrinter?.name.orEmpty().ifBlank { unknownPrinter }
            "$name · ${stringResource(R.string.label_flow_choose_protocol)}"
        }
        selectedCandidate != null ->
            "${selectedCandidate.name.orEmpty().ifBlank { unknownPrinter }} · ${labelPrinterProtocolName(activeProfile)}"
        selectedSavedPrinter != null ->
            "${selectedSavedPrinter.name.ifBlank { unknownPrinter }} · ${stringResource(R.string.label_flow_printer_unavailable)}"
        else -> stringResource(R.string.label_flow_pick_printer)
    }

    if (sheetPanel == "paper" && !hasQueue) {
        LabelPaperPickerSheet(
            paper = paper ?: queue.paper,
            media = media,
            profile = activeProfile,
            onApply = { next, nextMedia ->
                sheetPanel = null
                if (paper != null && (paper.widthMm != next.widthMm || paper.heightMm != next.heightMm) &&
                    draftDesigns.isNotEmpty()
                ) pendingPaper = next to nextMedia
                else applyPaper(next, nextMedia)
            },
            onDismiss = { sheetPanel = null },
        )
        return
    }

    SecondaryPageScaffold(
        title = stringResource(when {
            hasQueue -> R.string.label_flow_queue_title
            showPrintView -> R.string.label_confirmation_title
            else -> R.string.bluetooth_label_print_title
        }),
        onBack = ::requestBack,
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasQueue && !showPrintView) {
                        Button(onClick = {
                            editingFromConfirmation = false
                            showPrintView = true
                        }, enabled = controller.loaded && !controller.working,
                            modifier = Modifier.weight(1f).testTag("label_print_open")) {
                            Text(stringResource(R.string.label_ui_print))
                        }
                    } else if (!hasQueue) {
                        Button(onClick = { paper?.let { sheet ->
                            if (draftFreeLabel != null && activeDesign != null)
                                controller.createFree(draftFreeLabel, freeCopies.toInt(), sheet, activeDesign,
                                    printWith = selectedCandidate)
                            else controller.create(draftSelections, templateId, textTemplateId, sheet, printDesigns,
                                printWith = selectedCandidate)
                        } }, enabled = canCreate, modifier = Modifier.weight(1f).testTag("label_print_submit")) {
                            Text(stringResource(if (selectedCandidate != null) R.string.label_flow_print_now
                                else R.string.bluetooth_label_print_create))
                        }
                    } else if (controller.running) {
                        OutlinedButton(onClick = controller::pause, enabled = !controller.pauseRequested,
                            modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bluetooth_label_print_pause)) }
                        Button(onClick = { leaveAfterStop = false; confirmStop = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.bluetooth_label_print_stop))
                        }
                    } else {
                        Button(onClick = { selectedCandidate?.let(controller::start) },
                            enabled = !controller.working && selectedCandidate != null &&
                                queue.items.any { it.state == LabelPrintItemState.Pending } &&
                                queue.items.none { it.state in listOf(LabelPrintItemState.Uncertain,
                                    LabelPrintItemState.Failed, LabelPrintItemState.Sending) },
                            modifier = Modifier.weight(1f).testTag("print_queue_start")) {
                            Text(stringResource(R.string.bluetooth_label_print_start))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!hasQueue && !showPrintView) {
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(previewFreeLabel?.title ?: previewSeed?.let { it.name.ifBlank { it.sku } }
                        ?: stringResource(R.string.bluetooth_label_print_title),
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { sheetPanel = "paper" }, enabled = editable,
                        modifier = Modifier.testTag("label_editor_paper")) {
                        Text(paper?.let(::paperSummary) ?: stringResource(R.string.label_ui_paper))
                    }
                    IconButton(onClick = { localMessage = null; sheetPanel = "export" }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.label_ui_export))
                    }
                }
                if (!controller.loaded || controller.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (previewPaper != null && (previewSeed != null || previewFreeLabel != null)) {
                        LabelEditorCanvas(if (preview.error == null && preview.request == previewRequest)
                            preview.bitmap else null,
                            activeDesign, previewPaper, selectedElementId, editable,
                            onSelect = { selectedElementId = it; invalidGeometryFields = arrayListOf() },
                            onMove = { id, x, y ->
                                val design = activeDesign ?: return@LabelEditorCanvas
                                val moved = design.moved(id, x, y, previewPaper)
                                updateDesign(moved)
                                val before = design.elements.firstOrNull { it.id == id }
                                val after = moved.elements.firstOrNull { it.id == id }
                                val prefix = "${previewSeed?.sku ?: "__free__"}:$id:"
                                invalidGeometryFields = ArrayList(invalidGeometryFields.filterNot {
                                    (it == "${prefix}x" && before?.xMm != after?.xMm) ||
                                        (it == "${prefix}y" && before?.yMm != after?.yMm)
                                })
                            })
                    } else Text(stringResource(R.string.label_ui_no_component),
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (blankQr && (previewError == null || previewError.contains("请输入二维码内容")))
                    Text(stringResource(R.string.label_ui_enter_qr),
                    color = MaterialTheme.colorScheme.primary)
                else previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("content" to R.string.label_ui_content,
                        "position" to R.string.label_ui_position,
                        "templates" to R.string.label_flow_templates,
                        (if (previewFreeLabel != null) "add" else "components") to
                            (if (previewFreeLabel != null) R.string.label_ui_add else R.string.label_ui_components)
                    ).filterNot { (panel, _) -> freeMode && panel == "templates" }.forEach { (panel, title) ->
                        TextButton(enabled = editable, onClick = {
                            if (panel == "content") {
                                if (selectedElementId == null) selectedElementId = activeDesign?.elements?.firstOrNull()?.id
                            }
                            sheetPanel = panel
                        }, modifier = Modifier.weight(1f)) { Text(stringResource(title)) }
                    }
                }
                Text(selectedElement?.let { elementCaption(it, 48) }
                    ?: stringResource(R.string.label_ui_select_element),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
        } else if (!hasQueue) {
            Column(
                Modifier.padding(padding).consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                localMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (controller.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                previewFreeLabel?.title ?: previewSeed?.let { it.name.ifBlank { it.sku } }
                                    ?: stringResource(R.string.bluetooth_label_print_title),
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(onClick = {
                                editingFromConfirmation = true
                                showPrintView = false
                            }, enabled = editable,
                                modifier = Modifier.testTag("label_print_edit")) {
                                Text(stringResource(R.string.label_confirmation_edit))
                            }
                        }
                        if (previewPaper != null && (previewSeed != null || previewFreeLabel != null)) {
                            LabelEditorCanvas(if (preview.error == null && preview.request == previewRequest)
                                preview.bitmap else null,
                                activeDesign, previewPaper, null, false, {}, { _, _, _ -> }, 170.dp)
                        }
                    }
                }
                if (controller.loaded && !controller.working && !canCreate) {
                    val problem = when {
                        needsProtocol -> stringResource(R.string.label_flow_choose_protocol)
                        paper == null -> stringResource(R.string.bluetooth_label_print_paper_invalid)
                        !compatiblePaper -> stringResource(R.string.label_printer_paper_range,
                            labelPrinterProtocolName(activeProfile), activeProfile.widthRange.start.toString(),
                            activeProfile.widthRange.endInclusive.toString(), activeProfile.heightRange.start.toString(),
                            activeProfile.heightRange.endInclusive.toString())
                        previewError != null -> previewError
                        blankQr -> stringResource(R.string.label_ui_enter_qr)
                        draftFreeLabel != null && !validFree || draftFreeLabel == null && !validSelection ->
                            stringResource(R.string.bluetooth_label_print_count_invalid)
                        invalidGeometryFields.isNotEmpty() -> stringResource(R.string.label_ui_print_blocked)
                        else -> null
                    }
                    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
                }
                Card {
                    LabelSettingsRow(
                        stringResource(R.string.bluetooth_label_print_device),
                        printerSummary,
                        testTag = "label_print_device", enabled = editable,
                    ) { localMessage = null; sheetPanel = "devices" }
                    LabelSettingsRow(
                        stringResource(R.string.label_ui_paper),
                        paper?.let(::paperSummary)?.let { if (activeProfile.supportsContinuous) "$it · $mediaName" else it },
                        testTag = "label_print_paper", enabled = editable,
                    ) { sheetPanel = "paper" }
                    LabelSettingsRow(
                        stringResource(R.string.bluetooth_label_print_copies),
                        stringResource(R.string.bluetooth_label_print_count, printCount),
                        testTag = "label_print_copies", enabled = editable,
                    ) { sheetPanel = if (freeMode || singleComponent != null) "copies" else "components" }
                    LabelSettingsRow(
                        stringResource(R.string.label_editor_calibration),
                        stringResource(R.string.label_confirmation_offset_summary, printOffset.xMm, printOffset.yMm),
                        testTag = "label_print_calibration", enabled = editable,
                    ) { sheetPanel = "calibration" }
                }
                if (!freeMode && singleComponent != null) TextButton(
                    onClick = { sheetPanel = "components" }, enabled = editable,
                    modifier = Modifier.testTag("label_print_batch"),
                ) {
                    Text(stringResource(R.string.label_confirmation_batch))
                }
            }
        } else {
            Column(
                Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val completed = queue.items.count {
                    it.state == LabelPrintItemState.Sent || it.state == LabelPrintItemState.Skipped
                }
                Text(stringResource(R.string.bluetooth_label_print_progress, completed, queue.items.size),
                    style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { completed.toFloat() / queue.items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(paperSummary(queue.paper), style = MaterialTheme.typography.bodyMedium)
                if (needsProtocol) Text(stringResource(R.string.label_flow_choose_protocol))
                if (controller.pauseRequested) Text(stringResource(R.string.bluetooth_label_print_pausing))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("print_queue_list")) {
                    items(queue.items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().testTag("print_queue_item_${item.id}")
                                .clickable {
                                    previewQueueItemId = item.id
                                    localMessage = null
                                    sheetPanel = "queue_preview"
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.freeLabel?.title ?: item.seed?.name.orEmpty())
                                Text("${item.seed?.sku ?: stringResource(R.string.label_tool_free)} · " +
                                    "${item.copyNumber}/${item.copies} · ${labelStateText(item.state, activeProfile.requiresPhysicalReview, context)}",
                                    style = MaterialTheme.typography.bodySmall)
                                if (item.detail.isNotBlank() && item.detail != "print_check_label") {
                                    Text(printErrorText(item.detail, context), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!controller.running && !controller.working &&
                                item.state in listOf(LabelPrintItemState.Uncertain, LabelPrintItemState.Failed)) {
                                TextButton(onClick = { reviewItemId = item.id }) {
                                    Text(stringResource(R.string.bluetooth_label_print_review))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { localMessage = null; sheetPanel = "devices" },
                        enabled = !controller.running && !controller.working,
                        modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_flow_printer_details))
                    }
                    TextButton(onClick = { confirmNewQueue = true }, enabled = !controller.running && !controller.working,
                        modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.bluetooth_label_print_new_queue))
                    }
                }
            }
        }
    }

    if (sheetPanel != null && sheetPanel != "paper") ModalBottomSheet(
        onDismissRequest = { sheetPanel = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            if (sheetPanel == "content") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_ui_content), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { sheetPanel = null }, modifier = Modifier.testTag("label_content_done")) {
                    Text(stringResource(R.string.label_ui_close))
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sheetPanel == "position")
                Text(stringResource(R.string.label_ui_position), style = MaterialTheme.typography.titleLarge)
            if ((sheetPanel == "content" || sheetPanel == "position") && activeDesign != null)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeDesign.elements.forEach { element ->
                        FilterChip(selected = selectedElementId == element.id,
                            onClick = { selectedElementId = element.id; invalidGeometryFields = arrayListOf() },
                            label = { Text(elementCaption(element, 16)) },
                            modifier = Modifier.testTag("label_select_${element.id}"))
                    }
                }
            when (sheetPanel) {
                "content" -> {
                    if (selectedElement == null) Text(stringResource(R.string.label_ui_select_element),
                        style = MaterialTheme.typography.bodySmall)
                    else if (selectedElement.type == LabelElementType.Text) {
                        OutlinedTextField(selectedElement.text,
                            { activeDesign?.let { design -> updateDesign(design.withText(selectedElement.id, it)) } },
                            label = { Text(stringResource(R.string.label_editor_text)) },
                            modifier = Modifier.fillMaxWidth(), enabled = editable, maxLines = 3)
                    } else if (previewFreeLabel != null) {
                        OutlinedTextField(selectedElement.text,
                            { activeDesign?.let { design -> updateDesign(design.withQrPayload(selectedElement.id, it)) } },
                            label = { Text(stringResource(R.string.label_tool_qr_payload)) },
                            modifier = Modifier.fillMaxWidth(), enabled = editable, maxLines = 3)
                    } else Text(stringResource(R.string.label_editor_qr_identity),
                        style = MaterialTheme.typography.bodySmall)
                }
                "position" -> {
                    Text(stringResource(R.string.label_editor_preview_hint),
                        style = MaterialTheme.typography.bodySmall)
                    if (selectedElement == null) Text(stringResource(R.string.label_ui_select_element))
                    else {
                        TextButton(onClick = { advancedGeometry = !advancedGeometry }) {
                            Text(stringResource(R.string.label_ui_advanced))
                        }
                        if (advancedGeometry) {
                            val prefix = "${previewSeed?.sku ?: "__free__"}:${selectedElement.id}:"
                            LabelElementNumberField("${prefix}x", stringResource(R.string.label_editor_x),
                                selectedElement.xMm, editable, "${prefix}x" in invalidGeometryFields,
                                { recordGeometryValidity("${prefix}x", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(xMm = value))) }
                            }
                            LabelElementNumberField("${prefix}y", stringResource(R.string.label_editor_y),
                                selectedElement.yMm, editable, "${prefix}y" in invalidGeometryFields,
                                { recordGeometryValidity("${prefix}y", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(yMm = value))) }
                            }
                            LabelElementNumberField("${prefix}width", stringResource(R.string.label_editor_width),
                                selectedElement.widthMm, editable, "${prefix}width" in invalidGeometryFields,
                                { recordGeometryValidity("${prefix}width", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(widthMm = value))) }
                            }
                            LabelElementNumberField("${prefix}height", stringResource(R.string.label_editor_height),
                                selectedElement.heightMm, editable, "${prefix}height" in invalidGeometryFields,
                                { recordGeometryValidity("${prefix}height", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(heightMm = value))) }
                            }
                        }
                    }
                }
                "templates" -> {
                    Text(stringResource(R.string.label_flow_templates), style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComponentLabelTemplate.entries.forEach { template ->
                            FilterChip(templateId == template.id, onClick = {
                                if (template.id != templateId) {
                                    if (draftDesigns.isNotEmpty()) pendingTemplateId = template.id
                                    else templateId = template.id
                                }
                            }, label = { Text(labelTemplateText(template, context)) }, enabled = editable)
                        }
                    }
                    if (ComponentLabelTemplate.fromId(templateId) == ComponentLabelTemplate.TextOnly)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComponentTextLabelTemplate.entries.forEach { template ->
                                FilterChip(textTemplateId == template.id, onClick = {
                                    if (template.id != textTemplateId) {
                                        if (draftDesigns.isNotEmpty()) pendingTextTemplateId = template.id
                                        else textTemplateId = template.id
                                    }
                                }, label = { Text(context.getString(when (template) {
                                    ComponentTextLabelTemplate.NameSku -> R.string.bluetooth_label_print_text_name_sku
                                    ComponentTextLabelTemplate.NamePackageSku -> R.string.bluetooth_label_print_text_name_package_sku
                                    ComponentTextLabelTemplate.NameModel -> R.string.bluetooth_label_print_text_name_model
                                })) }, enabled = editable)
                            }
                        }
                }
                "devices" -> {
                    Text(stringResource(R.string.bluetooth_label_print_device), style = MaterialTheme.typography.titleLarge)
                    localMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (savedPrinters.isNotEmpty()) {
                        Text(stringResource(R.string.label_flow_saved_printers),
                            style = MaterialTheme.typography.titleSmall)
                        savedPrinters.forEach { printer ->
                            val paired = pairedByAddress[printer.address]
                            val protocol = labelPrinterProtocolName(LabelPrinterProfile.fromId(printer.profileId))
                            val available = paired != null && controller.canSelectDevice(paired)
                            val supportingText = when {
                                paired == null -> "$protocol · ${stringResource(R.string.label_flow_printer_unavailable)}"
                                !available -> "$protocol · ${stringResource(R.string.label_flow_printer_incompatible)}"
                                else -> protocol
                            }
                            LabelPrinterRow(
                                title = printer.name.ifBlank { unknownPrinter },
                                subtitle = supportingText,
                                selected = controller.selectedAddress == printer.address,
                                enabled = available,
                                onSelect = { paired?.let(controller::selectDevice) },
                                onForget = if (hasQueue && controller.selectedAddress == printer.address) null
                                    else ({ controller.forgetPrinter(printer.address) }),
                            )
                        }
                    }
                    val otherPrinters = pairedPrinters.filterNot { it.address in savedAddresses }
                    if (otherPrinters.isNotEmpty()) {
                        Text(stringResource(R.string.label_flow_other_printers),
                            style = MaterialTheme.typography.titleSmall)
                        otherPrinters.forEach { candidate ->
                            val available = controller.canSelectDevice(candidate)
                            LabelPrinterRow(
                                title = candidate.name.orEmpty().ifBlank { unknownPrinter },
                                subtitle = if (available) "…${candidate.address.takeLast(5)}"
                                    else stringResource(R.string.label_flow_printer_incompatible),
                                selected = controller.selectedAddress == candidate.address,
                                enabled = available,
                                onSelect = { controller.selectDevice(candidate) },
                            )
                        }
                    } else if (pairedPrinters.isEmpty()) {
                        Text(stringResource(R.string.bluetooth_label_print_pair_hint))
                    }
                    if (selectedSavedPrinter != null || selectedPairedPrinter != null) {
                        Text(stringResource(R.string.label_printer_profile))
                        if (needsProtocol) Text(stringResource(R.string.label_flow_choose_protocol))
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabelPrinterProfile.selectable.forEach { profile ->
                                FilterChip(
                                    selected = controller.selectedProfile == profile,
                                    onClick = { controller.selectProfile(profile, selectedPairedPrinter) },
                                    enabled = controller.canSelectProfile(profile),
                                    label = { Text(labelPrinterProtocolName(profile)) },
                                    modifier = Modifier.testTag("label_print_protocol_${profile.id}"),
                                )
                            }
                        }
                        when (controller.selectedProfile?.configurable) {
                            LabelPrinterProfile.DetongerP1 -> Text(stringResource(R.string.label_p1_hint))
                            LabelPrinterProfile.SupvanT50Pro -> Text(stringResource(R.string.label_supvan_test_hint))
                            LabelPrinterProfile.GenericCpcl -> Text(stringResource(R.string.label_hm_experimental))
                            else -> Unit
                        }
                    }
                    OutlinedButton(onClick = ::refreshDevices) {
                        Text(stringResource(R.string.bluetooth_label_print_refresh_devices))
                    }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                        Text(stringResource(R.string.bluetooth_label_print_pair_settings))
                    }
                    TextButton(onClick = { troubleshootingExpanded = !troubleshootingExpanded }) {
                        Text(stringResource(R.string.label_editor_troubleshooting))
                    }
                    if (troubleshootingExpanded) {
                        if (controller.transport.report.isNotBlank()) SelectionContainer {
                            Text(controller.transport.report, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(controller.transport.report))
                        }, enabled = controller.transport.report.isNotBlank()) {
                            Text(stringResource(R.string.bluetooth_label_print_copy_report))
                        }
                    }
                }
                "copies" -> {
                    Text(stringResource(R.string.bluetooth_label_print_copies), style = MaterialTheme.typography.titleLarge)
                    if (freeMode) OutlinedTextField(freeTitle, { freeTitle = it.take(80) },
                        label = { Text(stringResource(R.string.label_tool_title_field)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    else singleComponent?.let { Text(it.seed.name.ifBlank { it.seed.sku }) }
                    val value = if (freeMode) freeCopies else singleComponent?.let { copies[it.id] } ?: "1"
                    LabelCopyStepper(value) { next ->
                        if (freeMode) freeCopies = next
                        else singleComponent?.let { copies[it.id] = next }
                    }
                }
                "calibration" -> LabelCalibrationFields(printOffset) { offset ->
                    controller.savePrintOffset(offset)
                    sheetPanel = null
                }
                "queue_preview" -> {
                    val label = previewFreeLabel?.title ?: previewSeed?.name.orEmpty()
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    if (previewPaper != null) {
                        LabelEditorCanvas(if (preview.error == null && preview.request == previewRequest)
                            preview.bitmap else null,
                            activeDesign, previewPaper, null, false, {}, { _, _, _ -> }, 210.dp)
                    }
                    localMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    LabelRasterExportActions(
                        preview.bitmap,
                        previewPaper,
                        queue.items.count { item ->
                            if (previewFreeLabel != null) item.freeLabel == previewFreeLabel
                            else item.seed?.sku == previewSeed?.sku
                        }.coerceAtLeast(1),
                        previewFreeLabel?.title ?: previewSeed?.sku.orEmpty(),
                        preview.request == previewRequest && preview.error == null && preview.bitmap != null,
                        { localMessage = it },
                    )
                }
                "components" -> {
                    Text(stringResource(R.string.label_ui_components), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(search, { search = it },
                        label = { Text(stringResource(R.string.bluetooth_label_print_search)) },
                        modifier = Modifier.fillMaxWidth())
                    val visible = choices.filter { search.isBlank() || it.seed.name.contains(search, true) ||
                        it.seed.sku.contains(search, true) }
                    if (visible.isEmpty()) Text(stringResource(R.string.bluetooth_label_print_empty))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(visible, key = PrintableComponent::id) { choice ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(selected[choice.id] == true, { selected[choice.id] = it }, enabled = editable)
                                Column(Modifier.weight(1f).clickable(enabled = editable) {
                                    selected[choice.id] = selected[choice.id] != true
                                }) {
                                    Text(choice.seed.name.ifBlank { choice.seed.sku })
                                    Text(choice.seed.sku, style = MaterialTheme.typography.bodySmall)
                                }
                                if (selected[choice.id] == true) OutlinedTextField(
                                    copies[choice.id] ?: "1", { copies[choice.id] = it.filter(Char::isDigit).take(2) },
                                    label = { Text(stringResource(R.string.bluetooth_label_print_copies)) },
                                    modifier = Modifier.weight(0.38f), enabled = editable)
                            }
                        }
                    }
                }
                "add" -> {
                    Text(stringResource(R.string.label_ui_add), style = MaterialTheme.typography.titleLarge)
                    if (activeDesign != null && previewPaper != null) {
                        TextButton(onClick = {
                            val id = (1..16).map { "text$it" }.first { candidate ->
                                activeDesign.elements.none { it.id == candidate }
                            }
                            updateDesign(activeDesign.copy(elements = activeDesign.elements +
                                LabelElement(id, LabelElementType.Text, "", 1f, 1f,
                                    minOf(20f, previewPaper.widthMm - 2f), minOf(5f, previewPaper.heightMm - 2f))))
                            selectedElementId = id; sheetPanel = null
                        }, enabled = editable && activeDesign.elements.size < 16) {
                            Text(stringResource(R.string.label_tool_add_text))
                        }
                        if (activeDesign.elements.none { it.type == LabelElementType.Qr })
                            TextButton(onClick = {
                                val side = minOf(previewPaper.widthMm - 2f, previewPaper.heightMm * 0.55f)
                                updateDesign(activeDesign.copy(elements = activeDesign.elements + LabelElement(
                                    "qr", LabelElementType.Qr, "", 1f + (previewPaper.widthMm - 2f - side) / 2f,
                                    previewPaper.heightMm - 1f - side, side, side)))
                                selectedElementId = "qr"; sheetPanel = null
                            }, enabled = editable && activeDesign.elements.size < 16) {
                                Text(stringResource(R.string.label_tool_add_qr))
                            }
                        if (selectedElement != null && activeDesign.elements.size > 1) {
                            TextButton(onClick = {
                                updateDesign(activeDesign.copy(elements = activeDesign.elements.filterNot {
                                    it.id == selectedElement.id
                                }))
                                val prefix = "__free__:${selectedElement.id}:"
                                invalidGeometryFields = ArrayList(invalidGeometryFields.filterNot { it.startsWith(prefix) })
                                selectedElementId = null; sheetPanel = null
                            }, enabled = editable) { Text(stringResource(R.string.label_tool_remove_element)) }
                        }
                    }
                }
                "export" -> {
                    localMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    LabelRasterExportActions(
                        preview.bitmap,
                        previewPaper,
                        if (previewFreeLabel != null) freeCopies.toIntOrNull() ?: 1
                        else draftSelections.firstOrNull { it.first.sku == previewSeed?.sku }?.second ?: 1,
                        previewFreeLabel?.title ?: previewSeed?.sku.orEmpty(),
                        preview.request == previewRequest && preview.error == null && preview.bitmap != null,
                        { localMessage = it },
                    )
                }
            }
            if (sheetPanel != "content" && sheetPanel != "calibration") TextButton(onClick = { sheetPanel = null },
                modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.label_ui_close))
            }
            }
        }
    }

    controller.error?.takeUnless { it == "print_check_label" }?.let { error ->
        AlertDialog(
            onDismissRequest = controller::clearError,
            title = { Text(stringResource(R.string.bluetooth_label_print_error_title)) },
            text = { Text(printErrorText(error, context)) },
            confirmButton = {
                if (error == "queue_load_failed") {
                    TextButton(onClick = { controller.clear() }) {
                        Text(stringResource(R.string.bluetooth_label_print_clear_corrupt))
                    }
                } else {
                    TextButton(onClick = controller::clearError) { Text(stringResource(R.string.bluetooth_label_print_dismiss_error)) }
                }
            },
            dismissButton = {
                TextButton(onClick = controller::clearError) { Text(stringResource(R.string.bluetooth_label_print_cancel)) }
            },
        )
    }
    pendingPaper?.let { (value, mediaType) ->
        AlertDialog(
            onDismissRequest = { pendingPaper = null },
            title = { Text(stringResource(R.string.label_ui_change_paper_title)) },
            text = { Text(stringResource(R.string.label_ui_change_paper_warning)) },
            confirmButton = { TextButton(onClick = {
                applyPaper(value, mediaType)
                draftDesignJson = "{}"
                selectedElementId = null
                invalidGeometryFields = arrayListOf()
                pendingPaper = null
            }) { Text(stringResource(R.string.label_editor_reset_confirm)) } },
            dismissButton = { TextButton(onClick = { pendingPaper = null }) {
                Text(stringResource(R.string.bluetooth_label_print_cancel))
            } },
        )
    }
    if (pendingTemplateId != null || pendingTextTemplateId != null) AlertDialog(
        onDismissRequest = { pendingTemplateId = null; pendingTextTemplateId = null },
        title = { Text(stringResource(R.string.label_editor_reset_title)) },
        text = { Text(stringResource(R.string.label_editor_reset_warning)) },
        confirmButton = { TextButton(onClick = {
            pendingTemplateId?.let { templateId = it }
            pendingTextTemplateId?.let { textTemplateId = it }
            draftDesignJson = "{}"
            selectedElementId = null
            invalidGeometryFields = arrayListOf()
            pendingTemplateId = null
            pendingTextTemplateId = null
        }) { Text(stringResource(R.string.label_editor_reset_confirm)) } },
        dismissButton = { TextButton(onClick = { pendingTemplateId = null; pendingTextTemplateId = null }) {
            Text(stringResource(R.string.bluetooth_label_print_cancel))
        } },
    )
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.bluetooth_label_print_stop)) },
        text = { Text(stringResource(R.string.bluetooth_label_print_stop_warning)) },
        confirmButton = { TextButton(onClick = {
            controller.stop()
            confirmStop = false
            if (leaveAfterStop) onDismiss()
            leaveAfterStop = false
        }) { Text(stringResource(R.string.bluetooth_label_print_stop)) } },
        dismissButton = { TextButton(onClick = { confirmStop = false; leaveAfterStop = false }) {
            Text(stringResource(R.string.bluetooth_label_print_cancel))
        } },
    )
    if (confirmNewQueue) AlertDialog(
        onDismissRequest = { confirmNewQueue = false },
        title = { Text(stringResource(R.string.bluetooth_label_print_new_queue)) },
        text = { Text(stringResource(R.string.bluetooth_label_print_replace_warning)) },
        confirmButton = { TextButton(onClick = { controller.clear(); confirmNewQueue = false }) {
            Text(stringResource(R.string.bluetooth_label_print_clear_confirm))
        } },
        dismissButton = { TextButton(onClick = { confirmNewQueue = false }) { Text(stringResource(R.string.bluetooth_label_print_cancel)) } },
    )
    val reviewItem = queue.items.firstOrNull { it.id == reviewItemId }
    if (reviewItem != null) AlertDialog(
        onDismissRequest = { reviewItemId = null },
        title = { Text(stringResource(R.string.bluetooth_label_print_review)) },
        text = { Text(stringResource(R.string.bluetooth_label_print_review_warning,
            reviewItem.freeLabel?.title ?: reviewItem.seed?.sku.orEmpty())) },
        confirmButton = {
            Column {
                TextButton(onClick = { controller.resolve(reviewItem.id, LabelPrintItemState.Pending); reviewItemId = null }) {
                    Text(stringResource(R.string.bluetooth_label_print_retry))
                }
                TextButton(onClick = { controller.resolve(reviewItem.id, LabelPrintItemState.Sent); reviewItemId = null }) {
                    Text(stringResource(R.string.bluetooth_label_print_mark_printed))
                }
                TextButton(onClick = { controller.resolve(reviewItem.id, LabelPrintItemState.Skipped); reviewItemId = null }) {
                    Text(stringResource(R.string.bluetooth_label_print_skip))
                }
            }
        },
        dismissButton = { TextButton(onClick = { reviewItemId = null }) { Text(stringResource(R.string.bluetooth_label_print_cancel)) } },
    )
}

private fun labelStateText(state: LabelPrintItemState, manualConfirmation: Boolean, context: android.content.Context): String = context.getString(when (state) {
    LabelPrintItemState.Pending -> R.string.bluetooth_label_print_state_pending
    LabelPrintItemState.Sending -> R.string.bluetooth_label_print_state_sending
    LabelPrintItemState.Sent -> if (manualConfirmation) R.string.label_flow_print_confirmed
        else R.string.bluetooth_label_print_state_sent
    LabelPrintItemState.Uncertain -> R.string.bluetooth_label_print_state_uncertain
    LabelPrintItemState.Failed -> R.string.bluetooth_label_print_state_failed
    LabelPrintItemState.Skipped -> R.string.bluetooth_label_print_state_skipped
})

private fun labelTemplateText(template: ComponentLabelTemplate, context: android.content.Context): String = context.getString(when (template) {
    ComponentLabelTemplate.Qr10x40 -> R.string.bluetooth_label_print_compact_qr
    ComponentLabelTemplate.Qr30x40 -> R.string.bluetooth_label_print_full_qr
    else -> R.string.bluetooth_label_print_text_only
})

private fun printErrorText(code: String, context: android.content.Context): String = context.getString(when (code) {
    "queue_load_failed" -> R.string.bluetooth_label_print_error_load
    "queue_save_failed", "queue_save_or_print_failed" -> R.string.bluetooth_label_print_error_save
    "queue_invalid_selection" -> R.string.bluetooth_label_print_count_invalid
    "queue_review_required", "print_check_label" -> R.string.bluetooth_label_print_error_review
    "label_does_not_fit" -> R.string.bluetooth_label_print_error_fit
    "printer_paper_out" -> R.string.bluetooth_label_print_error_paper_out
    "printer_cover_open" -> R.string.bluetooth_label_print_error_cover_open
    "printer_locate_failed" -> R.string.bluetooth_label_print_error_locate
    "printer_bluetooth_off" -> R.string.bluetooth_label_print_error_bluetooth_off
    "printer_permission_required" -> R.string.bluetooth_label_print_permission
    "printer_not_m1" -> R.string.bluetooth_label_print_error_not_m1
    "printer_profile_mismatch" -> R.string.label_flow_printer_incompatible
    "printer_supvan_print_area" -> R.string.label_supvan_print_area
    "printer_profile_required" -> R.string.label_flow_choose_protocol
    "printer_no_response" -> R.string.bluetooth_label_print_error_no_response
    "printer_hprt_sdk_missing" -> R.string.label_hm_sdk_missing
    else -> R.string.bluetooth_label_print_error_not_ready
})

@Composable
private fun labelPrinterProtocolName(profile: LabelPrinterProfile): String = when (profile.configurable) {
    LabelPrinterProfile.M1 -> "M1"
    LabelPrinterProfile.DetongerP1 -> "德佟 P1"
    LabelPrinterProfile.SupvanT50Pro -> "SUPVAN T50 Pro"
    else -> stringResource(R.string.label_cpcl_generic)
}

@Composable
private fun LabelPrinterRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { RadioButton(selected, onClick = null, enabled = enabled) },
        trailingContent = {
            if (onForget != null) TextButton(onClick = onForget) {
                Text(stringResource(R.string.label_flow_forget_printer))
            }
        },
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect,
        ),
    )
}

@Composable
private fun LabelCopyStepper(value: String, onChange: (String) -> Unit) {
    val count = value.toIntOrNull()
    val fewer = stringResource(R.string.label_confirmation_fewer_copies)
    val more = stringResource(R.string.label_confirmation_more_copies)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange(((count ?: 1) - 1).coerceAtLeast(1).toString()) },
            enabled = (count ?: 1) > 1,
            modifier = Modifier.testTag("label_copies_decrease").semantics { contentDescription = fewer },
        ) { Text("−") }
        OutlinedTextField(
            value, { onChange(it.filter(Char::isDigit).take(2)) },
            label = { Text(stringResource(R.string.bluetooth_label_print_copies)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.weight(1f).testTag("label_copies_value"),
        )
        OutlinedButton(
            onClick = { onChange(((count ?: 0) + 1).coerceIn(1, LabelPrintQueue.MaxCopies).toString()) },
            enabled = (count ?: 0) < LabelPrintQueue.MaxCopies,
            modifier = Modifier.testTag("label_copies_increase").semantics { contentDescription = more },
        ) { Text("+") }
    }
}

@Composable
private fun LabelCalibrationFields(offset: LabelPrintOffset, onApply: (LabelPrintOffset) -> Unit) {
    var horizontal by rememberSaveable(offset.xMm) { mutableStateOf(offset.xMm.toString()) }
    var vertical by rememberSaveable(offset.yMm) { mutableStateOf(offset.yMm.toString()) }
    val updated = remember(horizontal, vertical) {
        runCatching {
            LabelPrintOffset(
                horizontal.trim().replace(',', '.').replace('−', '-').toFloat(),
                vertical.trim().replace(',', '.').replace('−', '-').toFloat(),
            )
        }.getOrNull()
    }
    Text(stringResource(R.string.label_editor_calibration), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.label_confirmation_calibration_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        horizontal, { horizontal = it.take(12) },
        label = { Text(stringResource(R.string.bluetooth_label_print_offset_x)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("label_calibration_x"),
    )
    OutlinedTextField(
        vertical, { vertical = it.take(12) },
        label = { Text(stringResource(R.string.bluetooth_label_print_offset_y)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("label_calibration_y"),
    )
    if (updated == null) Text(
        stringResource(R.string.label_confirmation_invalid_offset), color = MaterialTheme.colorScheme.error,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            horizontal = LabelPrintOffset.Default.xMm.toString()
            vertical = LabelPrintOffset.Default.yMm.toString()
        }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.label_confirmation_default_offset))
        }
        Button(onClick = { updated?.let(onApply) }, enabled = updated != null,
            modifier = Modifier.weight(1f).testTag("label_calibration_apply")) {
            Text(stringResource(R.string.label_confirmation_apply_offset))
        }
    }
}

@Composable
private fun LabelSettingsRow(
    title: String,
    summary: String?,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { summary?.let { Text(it) } },
        modifier = Modifier.fillMaxWidth().testTag(testTag).clickable(enabled = enabled, onClick = onClick),
    )
}

private fun LabelDesign.withElement(replacement: LabelElement): LabelDesign =
    copy(elements = elements.map { if (it.id == replacement.id) replacement else it })

@Composable
private fun LabelElementNumberField(
    fieldId: String,
    label: String,
    value: Float,
    enabled: Boolean,
    invalid: Boolean,
    onInvalidChange: (String?) -> Unit,
    onChange: (Float) -> Unit,
) {
    var raw by rememberSaveable(fieldId) { mutableStateOf(if (invalid) "" else value.toString()) }
    LaunchedEffect(value, invalid) {
        if (!invalid && raw.toFloatOrNull() != value) raw = value.toString()
    }
    OutlinedTextField(
        value = raw,
        onValueChange = { input ->
            raw = input
            val parsed = input.toFloatOrNull()?.takeIf { it.isFinite() }
            if (parsed == null) onInvalidChange(fieldId)
            else {
                onInvalidChange(null)
                onChange(parsed)
            }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = invalid,
        singleLine = true,
    )
}

private fun readLabelDrafts(encoded: String): Map<String, LabelDesign> = runCatching {
    val root = JSONObject(encoded)
    buildMap {
        root.keys().forEach { sku ->
            val items = root.getJSONArray(sku)
            val elements = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                LabelElement(
                    id = item.getString("id"),
                    type = LabelElementType.valueOf(item.getString("type")),
                    text = item.getString("text"),
                    xMm = item.getDouble("x").toFloat(),
                    yMm = item.getDouble("y").toFloat(),
                    widthMm = item.getDouble("width").toFloat(),
                    heightMm = item.getDouble("height").toFloat(),
                )
            }
            put(sku, LabelDesign(elements))
        }
    }
}.getOrDefault(emptyMap())

private fun writeLabelDrafts(drafts: Map<String, LabelDesign>): String = JSONObject().apply {
    drafts.forEach { (sku, design) ->
        put(sku, JSONArray().apply {
            design.elements.forEach { element ->
                put(JSONObject().apply {
                    put("id", element.id)
                    put("type", element.type.name)
                    put("text", element.text)
                    put("x", element.xMm.toDouble())
                    put("y", element.yMm.toDouble())
                    put("width", element.widthMm.toDouble())
                    put("height", element.heightMm.toDouble())
                })
            }
        })
    }
}.toString()
