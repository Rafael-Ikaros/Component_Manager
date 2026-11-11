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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
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
import com.componentvault.android.data.LabelPaperPresetStore
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
    val freeLabel: FreeLabel? = null,
)
private data class PrintPreview(
    val request: PreviewRequest? = null,
    val bitmap: Bitmap? = null,
    val error: String? = null,
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
    val paperStore = remember(context) {
        LabelPaperPresetStore(context.getSharedPreferences("label_paper_settings", android.content.Context.MODE_PRIVATE))
    }
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
    var calibrationExpanded by rememberSaveable { mutableStateOf(false) }
    var editingSku by rememberSaveable { mutableStateOf<String?>(initialSeed?.sku) }
    var selectedElementId by rememberSaveable { mutableStateOf<String?>(
        if (freeTemplate == FreeLabelTemplate.Qr) "qr" else if (freeMode) "text" else null
    ) }
    var invalidGeometryFields by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var draftDesignJson by rememberSaveable { mutableStateOf("{}") }
    var pendingTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTextTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPaperSize by remember { mutableStateOf<M1TestPaperProfile?>(null) }
    val draftDesigns = remember(draftDesignJson) { readLabelDrafts(draftDesignJson) }
    var templateId by rememberSaveable(selectedInitialTemplateId) { mutableStateOf(selectedInitialTemplateId) }
    var textTemplateId by rememberSaveable(initialTextTemplateId) { mutableStateOf(initialTextTemplateId) }
    var widthText by rememberSaveable { mutableStateOf("40") }
    var heightText by rememberSaveable { mutableStateOf("60") }
    var rotationText by rememberSaveable { mutableStateOf("0") }
    var offsetXText by rememberSaveable { mutableStateOf("0") }
    var offsetYText by rememberSaveable { mutableStateOf("0") }
    var restoredPaper by rememberSaveable { mutableStateOf(false) }
    fun applyPaper(value: M1TestPaperProfile) {
        widthText = value.widthMm.toString()
        heightText = value.heightMm.toString()
        rotationText = value.rotationDegrees.toString()
        offsetXText = value.offsetXmm.toString()
        offsetYText = value.offsetYmm.toString()
    }
    var confirmNewQueue by remember { mutableStateOf(false) }
    var reviewItemId by remember { mutableStateOf<String?>(null) }
    var previewQueueItemId by remember { mutableStateOf<String?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var leaveAfterStop by remember { mutableStateOf(false) }
    var showPrintView by rememberSaveable { mutableStateOf(false) }
    var sheetPanel by remember { mutableStateOf<String?>(null) }
    var advancedGeometry by rememberSaveable { mutableStateOf(false) }

    fun requestBack() {
        if (controller.running) {
            leaveAfterStop = true
            confirmStop = true
        } else if (showPrintView && controller.queue.items.isEmpty()) showPrintView = false
        else onDismiss()
    }

    LaunchedEffect(controller) { controller.load() }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (BluetoothPrinterDiagnostics.permissions().all { grants[it] == true }) {
            controller.transport.refreshPaired()
        } else {
            localMessage = context.getString(R.string.bluetooth_label_print_permission)
        }
    }
    fun refreshDevices() {
        val permissions = BluetoothPrinterDiagnostics.permissions()
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            controller.transport.refreshPaired()
        } else {
            permissionRequest.launch(permissions)
        }
    }
    val activeProfile = LabelPrinterProfile.fromId(if (controller.queue.items.isNotEmpty()) controller.queue.printerProfileId else controller.selectedProfileId)
    val paper = remember(widthText, heightText, rotationText, offsetXText, offsetYText, activeProfile) {
        runCatching {
            M1TestPaperProfile(
                widthMm = widthText.toFloat(),
                heightMm = heightText.toFloat(),
                rotationDegrees = rotationText.toInt(),
                offsetXmm = offsetXText.toFloat(),
                offsetYmm = offsetYText.toFloat(),
            )
        }.getOrNull()
    }
    val paperFitsPrinter = paper?.let { runCatching { activeProfile.validate(it) }.isSuccess } == true
    val printerPaperHint = stringResource(R.string.label_printer_paper_range, activeProfile.displayName,
        activeProfile.widthRange.start.toString(), activeProfile.widthRange.endInclusive.toString(),
        activeProfile.heightRange.start.toString(), activeProfile.heightRange.endInclusive.toString())
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
    val queue = controller.queue
    val hasQueue = queue.items.isNotEmpty()
    LaunchedEffect(controller.loaded, hasQueue) {
        if (controller.loaded && hasQueue) showPrintView = true
    }
    LaunchedEffect(controller.loaded, hasQueue) {
        if (controller.loaded && !hasQueue && !restoredPaper) {
            val lastPaper = paperStore.last()
            val selectedPaper = initialPaper ?: lastPaper ?: queue.paper
            widthText = selectedPaper.widthMm.toString()
            heightText = selectedPaper.heightMm.toString()
            rotationText = selectedPaper.rotationDegrees.toString()
            offsetXText = selectedPaper.offsetXmm.toString()
            offsetYText = selectedPaper.offsetYmm.toString()
            if (initialSeed == null && initialPaper == null) {
                templateId = queue.templateId
                textTemplateId = queue.textTemplateId
            }
            restoredPaper = true
        }
    }
    val editable = !hasQueue && controller.loaded && !controller.running && !controller.working
    LaunchedEffect(paper, editable, restoredPaper) {
        if (restoredPaper && editable && paper != null) paperStore.saveLast(paper)
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
        previewPaper, printDesigns, previewFreeLabel)
    var preview by remember { mutableStateOf(PrintPreview()) }
    LaunchedEffect(previewRequest) {
        if ((previewSeed != null || previewFreeLabel != null) && previewPaper != null) {
            var generated: Bitmap? = null
            preview = try {
                val bitmap = withContext(Dispatchers.Default) {
                    M1ComponentLabelRenderer.render(previewSeed, previewTemplate, previewTextTemplate, previewPaper,
                        printDesigns[previewSeed?.sku ?: "__free__"], previewFreeLabel).also {
                        generated = it
                    }
                    previewSeeds.drop(1).forEach { seed ->
                        currentCoroutineContext().ensureActive()
                        M1ComponentLabelRenderer.render(seed, previewTemplate, previewTextTemplate, previewPaper,
                            printDesigns[seed.sku]).recycle()
                    }
                    generated!!
                }
                val result = PrintPreview(request = previewRequest, bitmap = bitmap)
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

    val selectedCandidate = controller.transport.candidates.firstOrNull {
        it.paired && it.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL) &&
            it.address == controller.selectedAddress
    }
    val blankQr = previewFreeLabel != null && activeDesign?.elements?.any {
        it.type == LabelElementType.Qr && it.text.isBlank()
    } == true
    val previewError = preview.error.takeIf { preview.request == previewRequest }
    val printerAvailable = activeProfile != LabelPrinterProfile.HmA300eCpcl || controller.transport.hprtSdkAvailable
    val canCreate = printerAvailable && editable && (if (draftFreeLabel != null) validFree
        else validSelection && printDesigns.size == draftSelections.size) &&
        paper != null && paperFitsPrinter && invalidGeometryFields.isEmpty() && !blankQr &&
        preview.request == previewRequest && preview.bitmap != null && preview.error == null
    val selectedElement = activeDesign?.elements?.firstOrNull { it.id == selectedElementId }
    fun elementCaption(element: LabelElement, limit: Int): String = element.text.take(limit).ifBlank {
        context.getString(if (element.type == LabelElementType.Text) R.string.label_ui_empty_text
            else R.string.label_ui_empty_qr)
    }

    SecondaryPageScaffold(
        title = stringResource(if (showPrintView) R.string.label_ui_print_ready else R.string.bluetooth_label_print_title),
        onBack = ::requestBack,
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showPrintView && !hasQueue && !printerAvailable) {
                    Text(
                        stringResource(R.string.label_hm_sdk_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showPrintView && !hasQueue && paper != null && !paperFitsPrinter) {
                    Text(printerPaperHint, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showPrintView) {
                        Button(onClick = { showPrintView = true }, enabled = controller.loaded && !controller.working,
                            modifier = Modifier.weight(1f).testTag("label_print_open")) {
                            Text(stringResource(R.string.label_ui_print))
                        }
                    } else if (!hasQueue) {
                        OutlinedButton(onClick = { showPrintView = false }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.label_ui_edit))
                        }
                        Button(onClick = { paper?.let { sheet ->
                            if (draftFreeLabel != null && activeDesign != null)
                                controller.createFree(draftFreeLabel, freeCopies.toInt(), sheet, activeDesign)
                            else controller.create(draftSelections, templateId, textTemplateId, sheet, printDesigns)
                        } }, enabled = canCreate, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.bluetooth_label_print_create))
                        }
                    } else if (controller.running) {
                        OutlinedButton(onClick = controller::pause, enabled = !controller.pauseRequested,
                            modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bluetooth_label_print_pause)) }
                        Button(onClick = { leaveAfterStop = false; confirmStop = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.bluetooth_label_print_stop))
                        }
                    } else {
                        Button(onClick = { selectedCandidate?.let(controller::start) },
                            enabled = printerAvailable && !controller.working && selectedCandidate != null &&
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
        if (!showPrintView) {
            Column(Modifier.padding(padding).fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(previewFreeLabel?.title ?: previewSeed?.let { it.name.ifBlank { it.sku } }
                        ?: stringResource(R.string.bluetooth_label_print_title),
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { sheetPanel = "paper" }) {
                        Text(paper?.let { context.getString(R.string.label_ui_paper_summary,
                            it.widthMm.toString(), it.heightMm.toString(), it.rotationDegrees.toString()) }
                            ?: stringResource(R.string.label_ui_paper))
                    }
                    IconButton(onClick = { sheetPanel = "export" }) {
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
                        "paper" to R.string.label_ui_paper,
                        (if (previewFreeLabel != null) "add" else "components") to
                            (if (previewFreeLabel != null) R.string.label_ui_add else R.string.label_ui_components)
                    ).forEach { (panel, title) ->
                        TextButton(onClick = {
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
        } else {
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                localMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (!controller.loaded || controller.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!hasQueue) {
                    if (!canCreate && printerAvailable) {
                        Text(
                            previewError ?: stringResource(R.string.label_ui_print_blocked),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (previewFreeLabel != null) {
                        OutlinedTextField(freeTitle, { freeTitle = it.take(80) },
                            label = { Text(stringResource(R.string.label_tool_title_field)) },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(freeCopies, { freeCopies = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(R.string.bluetooth_label_print_copies)) },
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.bluetooth_label_print_count,
                            draftSelections.sumOf { it.second }))
                        draftSelections.forEach { (seed, count) -> Text("${seed.name} · ${seed.sku} · $count") }
                        TextButton(onClick = { sheetPanel = "components" }) {
                            Text(stringResource(R.string.label_editor_change_components))
                        }
                    }
                } else {
                    val sent = queue.items.count { it.state == LabelPrintItemState.Sent }
                    val skipped = queue.items.count { it.state == LabelPrintItemState.Skipped }
                    Text(stringResource(R.string.bluetooth_label_print_progress, sent + skipped, queue.items.size),
                        style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = {
                        (sent + skipped).toFloat() / queue.items.size
                    }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("print_queue_list")) {
                        items(queue.items, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth().testTag("print_queue_item_${item.id}")
                                .clickable { previewQueueItemId = item.id },
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.freeLabel?.title ?: item.seed?.name.orEmpty())
                                    Text("${item.seed?.sku ?: stringResource(R.string.label_tool_free)} · " +
                                        "${item.copyNumber}/${item.copies} · ${labelStateText(item.state, context)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (item.detail.isNotBlank()) Text(printErrorText(item.detail, context))
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
                    TextButton(onClick = { confirmNewQueue = true }, enabled = !controller.running && !controller.working) {
                        Text(stringResource(R.string.bluetooth_label_print_new_queue))
                    }
                }
                if (previewPaper != null && (previewSeed != null || previewFreeLabel != null)) {
                    Text(stringResource(R.string.label_ui_paper_summary,
                        previewPaper.widthMm.toString(), previewPaper.heightMm.toString(),
                        previewPaper.rotationDegrees.toString()),
                        style = MaterialTheme.typography.bodyMedium)
                    LabelEditorCanvas(if (preview.error == null && preview.request == previewRequest)
                        preview.bitmap else null,
                        activeDesign, previewPaper, selectedElementId, false, {}, { _, _, _ -> }, 210.dp)
                }
                LabelRasterExportActions(preview.bitmap, previewPaper,
                    if (hasQueue) queue.items.count { if (previewFreeLabel != null)
                        it.freeLabel == previewFreeLabel else it.seed?.sku == previewSeed?.sku }.coerceAtLeast(1)
                    else if (previewFreeLabel != null) freeCopies.toIntOrNull() ?: 1
                    else draftSelections.firstOrNull { it.first.sku == previewSeed?.sku }?.second ?: 1,
                    previewFreeLabel?.title ?: previewSeed?.sku.orEmpty(),
                    preview.request == previewRequest && preview.error == null && preview.bitmap != null,
                    { localMessage = it })
                PrintSection(stringResource(R.string.bluetooth_label_print_device)) {
                    Text(stringResource(R.string.label_printer_profile))
                    LabelPrinterProfile.entries.forEach { profile ->
                        FilterChip(selected = activeProfile == profile,
                            onClick = { controller.selectProfile(profile.id) }, enabled = editable,
                            label = { Text(profile.displayName) })
                    }
                    if (activeProfile == LabelPrinterProfile.HmA300eCpcl) {
                        if (printerAvailable || hasQueue) {
                            Text(stringResource(if (printerAvailable) R.string.label_hm_experimental else R.string.label_hm_sdk_missing))
                        }
                    }
                    if (activeProfile == LabelPrinterProfile.DetongerP1) Text(stringResource(R.string.label_p1_hint))
                    if (activeProfile != LabelPrinterProfile.M1) {
                        LabelMediaType.entries.forEach { media ->
                            FilterChip(selected = (if (hasQueue) queue.mediaType else controller.selectedMediaType) == media.id,
                                onClick = { controller.selectMedia(media.id) }, enabled = editable,
                                label = { Text(stringResource(if (media == LabelMediaType.Gap)
                                    R.string.label_media_gap else R.string.label_media_continuous)) })
                        }
                    }
                    OutlinedButton(onClick = ::refreshDevices, enabled = !controller.running && !controller.working) {
                        Text(stringResource(R.string.bluetooth_label_print_refresh_devices))
                    }
                    if (controller.transport.candidates.isEmpty())
                        Text(stringResource(R.string.bluetooth_label_print_pair_hint))
                    controller.transport.candidates.filter { it.paired &&
                        it.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL)
                    }.forEach { candidate ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !controller.running && !controller.working) {
                            controller.selectDevice(candidate.address)
                        }) {
                            RadioButton(controller.selectedAddress == candidate.address,
                                onClick = { controller.selectDevice(candidate.address) },
                                enabled = !controller.running && !controller.working)
                            Column {
                                Text(candidate.name ?: stringResource(R.string.bluetooth_label_print_unknown_device))
                                Text("…${candidate.address.takeLast(5)}")
                            }
                        }
                    }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                        Text(stringResource(R.string.bluetooth_label_print_pair_settings))
                    }
                    TextButton(onClick = { troubleshootingExpanded = !troubleshootingExpanded }) {
                        Text(stringResource(R.string.label_editor_troubleshooting))
                    }
                    if (troubleshootingExpanded) {
                        if (activeProfile != LabelPrinterProfile.M1 && controller.transport.report.isNotBlank()) {
                            SelectionContainer {
                                Text(controller.transport.report, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(controller.transport.report))
                        }, enabled = controller.transport.report.isNotBlank()) {
                            Text(stringResource(R.string.bluetooth_label_print_copy_report))
                        }
                    }
                    if (controller.pauseRequested) Text(stringResource(R.string.bluetooth_label_print_pausing))
                }
            }
        }
    }

    if (sheetPanel != null) ModalBottomSheet(
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
                                selectedElement.xMm, editable,
                                { recordGeometryValidity("${prefix}x", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(xMm = value))) }
                            }
                            LabelElementNumberField("${prefix}y", stringResource(R.string.label_editor_y),
                                selectedElement.yMm, editable,
                                { recordGeometryValidity("${prefix}y", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(yMm = value))) }
                            }
                            LabelElementNumberField("${prefix}width", stringResource(R.string.label_editor_width),
                                selectedElement.widthMm, editable,
                                { recordGeometryValidity("${prefix}width", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(widthMm = value))) }
                            }
                            LabelElementNumberField("${prefix}height", stringResource(R.string.label_editor_height),
                                selectedElement.heightMm, editable,
                                { recordGeometryValidity("${prefix}height", it) }) { value ->
                                activeDesign?.let { updateDesign(it.withElement(selectedElement.copy(heightMm = value))) }
                            }
                        }
                    }
                }
                "paper" -> {
                    Text(stringResource(R.string.label_ui_paper), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.label_editor_paper_range), style = MaterialTheme.typography.bodySmall)
                    LabelPaperPresets(paperStore, paper, editable) { selectedPaper ->
                        if (draftDesigns.isNotEmpty() &&
                            (selectedPaper.widthMm != paper?.widthMm || selectedPaper.heightMm != paper?.heightMm))
                            pendingPaperSize = selectedPaper
                        else applyPaper(selectedPaper)
                    }
                    if (activeProfile == LabelPrinterProfile.HmA300eCpcl) Text(stringResource(R.string.label_hm_printable_width))
                    LabelPaperChoices(paper?.widthMm, paper?.heightMm, editable, isM1 = activeProfile == LabelPrinterProfile.M1) { size ->
                        if (paper?.let { it.widthMm == size.widthMm && it.heightMm == size.heightMm } != true) {
                            if (draftDesigns.isNotEmpty()) pendingPaperSize = (paper ?: M1TestPaperProfile(40f, 60f))
                                .copy(widthMm = size.widthMm, heightMm = size.heightMm)
                            else {
                                widthText = size.widthMm.toInt().toString()
                                heightText = size.heightMm.toInt().toString()
                            }
                        }
                    }
                    Text(stringResource(R.string.label_ui_custom_size), style = MaterialTheme.typography.titleSmall)
                    PrintNumberField(stringResource(R.string.bluetooth_label_print_width), widthText, { widthText = it }, editable)
                    PrintNumberField(stringResource(R.string.bluetooth_label_print_height), heightText, { heightText = it }, editable)
                    if (previewFreeLabel == null) {
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
                    TextButton(onClick = { calibrationExpanded = !calibrationExpanded }) {
                        Text(stringResource(R.string.label_editor_calibration))
                    }
                    if (calibrationExpanded) {
                        Text(stringResource(R.string.bluetooth_label_print_rotation),
                            style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            M1TestPaperProfile.Rotations.sorted().forEach { degrees ->
                                FilterChip(selected = rotationText == degrees.toString(),
                                    onClick = { rotationText = degrees.toString() },
                                    label = { Text("$degrees°") }, enabled = editable)
                            }
                        }
                        Text(stringResource(R.string.m1_paper_rotation_hint),
                            style = MaterialTheme.typography.bodySmall)
                        PrintNumberField(stringResource(R.string.bluetooth_label_print_offset_x), offsetXText,
                            { offsetXText = it }, editable)
                        PrintNumberField(stringResource(R.string.bluetooth_label_print_offset_y), offsetYText,
                            { offsetYText = it }, editable)
                    }
                    if (paper == null) Text(stringResource(R.string.bluetooth_label_print_paper_invalid),
                        color = MaterialTheme.colorScheme.error)
                    else if (!paperFitsPrinter) Text(printerPaperHint, color = MaterialTheme.colorScheme.error)
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
                "export" -> LabelRasterExportActions(preview.bitmap, previewPaper,
                    if (previewFreeLabel != null) freeCopies.toIntOrNull() ?: 1
                    else draftSelections.firstOrNull { it.first.sku == previewSeed?.sku }?.second ?: 1,
                    previewFreeLabel?.title ?: previewSeed?.sku.orEmpty(),
                    preview.request == previewRequest && preview.error == null && preview.bitmap != null,
                    { localMessage = it })
            }
            if (sheetPanel != "content") TextButton(onClick = { sheetPanel = null },
                modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.label_ui_close))
            }
            }
        }
    }

    controller.error?.let { error ->
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
    if (pendingPaperSize != null) AlertDialog(
        onDismissRequest = { pendingPaperSize = null },
        title = { Text(stringResource(R.string.label_ui_change_paper_title)) },
        text = { Text(stringResource(R.string.label_ui_change_paper_warning)) },
        confirmButton = { TextButton(onClick = {
            pendingPaperSize?.let { size ->
                applyPaper(size)
                draftDesignJson = "{}"
                selectedElementId = null
                invalidGeometryFields = arrayListOf()
            }
            pendingPaperSize = null
        }) { Text(stringResource(R.string.label_editor_reset_confirm)) } },
        dismissButton = { TextButton(onClick = { pendingPaperSize = null }) {
            Text(stringResource(R.string.bluetooth_label_print_cancel))
        } },
    )
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

private fun labelStateText(state: LabelPrintItemState, context: android.content.Context): String = context.getString(when (state) {
    LabelPrintItemState.Pending -> R.string.bluetooth_label_print_state_pending
    LabelPrintItemState.Sending -> R.string.bluetooth_label_print_state_sending
    LabelPrintItemState.Sent -> R.string.bluetooth_label_print_state_sent
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
    "printer_no_response" -> R.string.bluetooth_label_print_error_no_response
    "printer_hprt_sdk_missing" -> R.string.label_hm_sdk_missing
    else -> R.string.bluetooth_label_print_error_not_ready
})

@Composable
private fun PrintSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PrintNumberField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
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
    onInvalidChange: (String?) -> Unit,
    onChange: (Float) -> Unit,
) {
    var raw by rememberSaveable(fieldId) { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (raw.toFloatOrNull() != value) raw = value.toString() }
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
