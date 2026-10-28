package com.componentvault.android.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.BatchJlcCommitPlanner
import com.componentvault.android.data.BatchJlcDraft
import com.componentvault.android.data.BatchJlcDraftCodec
import com.componentvault.android.data.BatchJlcDraftStore
import com.componentvault.android.data.BatchJlcRow
import com.componentvault.android.data.BatchJlcStatus
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.LcscPublicCatalog
import com.componentvault.android.data.LcscOrderWorkbookParser
import com.componentvault.android.data.ComponentImportParser
import com.componentvault.android.data.toBatchDescription
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportSourceType
import com.componentvault.android.model.ComponentOfficialLookupOutcome
import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.model.withOfficialMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private enum class BatchPage { Capture, Review, Pending }
private enum class ProcessScope { Captured, Pending }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchJlcInboundScreen(
    appPreferences: AppPreferences,
    syncConfiguration: SyncConfiguration,
    defaultLocation: String,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repository = remember(context) { InventoryRepository(context) }
    val store = remember(context) { BatchJlcDraftStore(context) }
    val scannerSettingsStore = remember(context) { BatchScannerSettingsStore(context) }
    val scope = rememberCoroutineScope()
    val saveMutex = remember { Mutex() }
    val saveRevision = remember { AtomicLong() }
    var draft by remember { mutableStateOf(BatchJlcDraft()) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var locations by remember { mutableStateOf<List<StorageLocationRecord>>(emptyList()) }
    var page by rememberSaveable { mutableStateOf(BatchPage.Capture) }
    var scanner by rememberSaveable { mutableStateOf(false) }
    var scannerSettings by remember { mutableStateOf(scannerSettingsStore.load()) }
    var showScannerSettings by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<Job?>(null) }
    var processJob by remember { mutableStateOf<Job?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var globalLocation by rememberSaveable { mutableStateOf(defaultLocation) }
    var processedCount by remember { mutableStateOf(0) }
    var processTotal by remember { mutableStateOf(0) }
    var summaryExpanded by rememberSaveable { mutableStateOf(false) }
    fun save(value: BatchJlcDraft) {
        draft = value
        val revision = saveRevision.incrementAndGet()
        saving = true
        saveFailed = false
        pendingSave = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    saveMutex.withLock {
                        if (revision == saveRevision.get()) store.save(value)
                    }
                }
            } catch (_: Exception) {
                if (revision == saveRevision.get()) {
                    saveFailed = true
                    scanner = false
                    dialogMessage = context.getString(R.string.batch_save_failed)
                }
            } finally {
                if (revision == saveRevision.get()) saving = false
            }
        }
    }
    fun update(row: BatchJlcRow) = save(draft.copy(rows = draft.rows.map { if (it.id == row.id) row else it }))
    val orderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            processing = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(LcscOrderWorkbookParser::parse)
                        ?: error("无法读取所选对账单。")
                }
                val receipts = repository.loadBatchJlcReceipts(draft.sessionId)
                val known = draft.rows.mapTo(mutableSetOf()) { it.id }
                var skipped = 0
                val added = imported.rows.mapNotNull { row ->
                    if (row.id in receipts || !known.add(row.id)) {
                        skipped++
                        null
                    } else {
                        val location = globalLocation.ifBlank { defaultLocation }
                        val complete = location.isNotBlank() && row.name.isNotBlank() &&
                            row.category.isNotBlank() && row.packageName.isNotBlank()
                        row.copy(
                            location = location,
                            status = if (complete) BatchJlcStatus.Ready else BatchJlcStatus.Pending,
                            error = if (complete) "" else context.getString(R.string.batch_complete_required),
                        )
                    }
                }
                require(draft.rows.size + added.size <= BatchJlcDraftCodec.MaxRows) {
                    "批量草稿最多 500 条，请先处理现有记录。"
                }
                if (added.isNotEmpty()) {
                    save(draft.copy(rows = draft.rows + added))
                    page = if (added.any { it.status == BatchJlcStatus.Ready }) BatchPage.Review else BatchPage.Pending
                }
                message = context.getString(
                    R.string.lcsc_order_import_result,
                    added.size, skipped, imported.mergedLines, imported.skippedFees,
                )
            } catch (error: Exception) {
                message = error.message ?: context.getString(R.string.lcsc_order_import_failed)
            } finally {
                processing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            draft = withContext(Dispatchers.IO) { store.loadOrThrow() }
            loaded = true
        } catch (_: Exception) {
            loadFailed = true
            message = context.getString(R.string.batch_load_failed)
        }
        locations = repository.loadStorageLocations()
        if (!loadFailed) {
            val receipts = repository.loadBatchJlcReceipts(draft.sessionId)
            if (receipts.isNotEmpty()) {
                save(draft.copy(rows = draft.rows.map { if (it.id in receipts) it.copy(status = BatchJlcStatus.Committed) else it }))
            }
        }
    }

    suspend fun processRows(processScope: ProcessScope, expectedGeneration: Int) {
        processing = true
        try {
            val existing = repository.loadComponents().filterNot { it.deleted }.associateBy { it.sku.uppercase() }
            val metadataCache = mutableMapOf<String, ComponentOfficialMetadata?>()
            val lookupNotices = mutableMapOf<String, String>()
            val target = draft.rows.filter {
                it.status == if (processScope == ProcessScope.Captured) BatchJlcStatus.Captured else BatchJlcStatus.Pending
            }
            processTotal = target.size
            processedCount = 0
            for (original in target) {
                if (expectedGeneration != generation) return
                val manualSku = LcscPublicCatalog.normalizeSku(original.sku)
                val parsed = if (manualSku != null) {
                    ComponentImportCandidate(
                        sourceType = ComponentImportSourceType.JlcText,
                        rawPayload = "",
                        sourceLabel = "manual_batch",
                        sku = manualSku,
                    )
                } else try {
                    ComponentImportParser.parseScannedQr(original.raw)
                } catch (error: Exception) {
                    update(original.copy(status = BatchJlcStatus.Pending, error = error.message ?: context.getString(R.string.batch_parse_failed)))
                    processedCount++
                    continue
                }
                val sku = (manualSku ?: parsed.sku).uppercase()
                val quantity = original.quantityText.takeIf { it.toIntOrNull()?.let { value -> value > 0 } == true }
                    ?: parsed.suggestedQuantity?.takeIf { it > 0 }?.toString().orEmpty()
                val location = original.location.ifBlank { globalLocation.ifBlank { defaultLocation } }
                val local = existing[sku]
                if (local != null) {
                    update(original.copy(
                        status = if (quantity.isNotBlank() && location.isNotBlank()) BatchJlcStatus.Ready else BatchJlcStatus.Pending,
                        sku = sku, name = local.name, category = local.category, packageName = local.packageName,
                        quantityText = quantity, location = location,
                        error = if (quantity.isBlank()) context.getString(R.string.batch_positive_quantity) else "",
                    ))
                    processedCount++
                    continue
                }
                if (original.id.startsWith("order-")) {
                    val complete = quantity.isNotBlank() && location.isNotBlank() &&
                        original.name.isNotBlank() && original.category.isNotBlank() && original.packageName.isNotBlank()
                    update(original.copy(
                        status = if (complete) BatchJlcStatus.Ready else BatchJlcStatus.Pending,
                        sku = sku, quantityText = quantity, location = location,
                        error = if (complete) "" else context.getString(R.string.batch_complete_required),
                    ))
                    processedCount++
                    continue
                }
                val request = parsed.copy(sku = sku)
                val metadata = if (metadataCache.containsKey(sku)) metadataCache[sku] else {
                    val result = repository.enrichImportCandidate(request, appPreferences, syncConfiguration)
                    result.officialLookupResult?.message?.takeIf(String::isNotBlank)?.let { lookupNotices[sku] = it }
                    val value = result.officialLookupResult
                        ?.takeIf { it.outcome == ComponentOfficialLookupOutcome.Success }?.metadata
                    metadataCache[sku] = value
                    value
                }
                if (expectedGeneration != generation) return
                val candidate = metadata?.let(request::withOfficialMetadata) ?: request
                val name = original.name.ifBlank { candidate.name }
                val category = original.category.ifBlank { candidate.category }
                val packageName = original.packageName.ifBlank { candidate.packageName }
                val complete = quantity.isNotBlank() && location.isNotBlank() && name.isNotBlank() &&
                    category.isNotBlank() && packageName.isNotBlank()
                val ready = complete && metadata != null
                update(original.copy(
                    status = if (ready) BatchJlcStatus.Ready else BatchJlcStatus.Pending,
                    sku = sku, name = name, category = category, packageName = packageName,
                    description = metadata?.toBatchDescription().orEmpty(), quantityText = quantity,
                    location = location,
                    error = when {
                        !complete -> context.getString(R.string.batch_complete_required)
                        metadata == null -> context.getString(R.string.batch_catalog_review)
                        else -> ""
                    },
                ))
                processedCount++
            }
            page = if (draft.rows.any { it.status == BatchJlcStatus.Ready }) BatchPage.Review else BatchPage.Pending
            val pendingCount = draft.rows.count { it.status == BatchJlcStatus.Pending }
            lookupNotices.values.distinct().takeIf { it.isNotEmpty() }?.let { notices ->
                message = notices.joinToString("\n")
            }
            if (pendingCount > 0) dialogMessage = context.getString(R.string.batch_pending_dialog, pendingCount)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            message = context.getString(R.string.batch_process_failed)
        } finally {
            processing = false
        }
    }

    val busy = processing || committing || saving || loadFailed
    fun requestDismiss() {
        scope.launch {
            pendingSave?.join()
            if (!saveFailed) onDismiss() else dialogMessage = context.getString(R.string.batch_save_failed)
        }
    }
    if (scanner && loaded && !loadFailed) {
        JlcQrScannerSurface(
            onDismiss = { scanner = false }, onScanResult = {},
            scannerMode = JlcQrScannerMode.BatchContinuous,
            continuousInitialCount = draft.rows.size,
            continuousIntervalMs = scannerSettings.intervalMs,
            continuousSoundEnabled = scannerSettings.soundEnabled,
            continuousVibrationEnabled = scannerSettings.vibrationEnabled,
            onContinuousResults = { values ->
                var ack = ContinuousCaptureAck(draft.rows.size, 0)
                val known = draft.rows.mapTo(mutableSetOf()) { it.raw.trim() }
                runCatching { BatchJlcDraftCodec.add(draft, values) }
                    .onSuccess { (next, duplicates) ->
                        save(next)
                        ack = ContinuousCaptureAck(
                            actualCount = next.rows.size,
                            duplicateCount = duplicates,
                            acceptedValues = values.filter { known.add(it.trim()) },
                        )
                        message = context.getString(R.string.batch_collected, next.rows.size, duplicates)
                    }.onFailure { message = it.message.orEmpty() }
                ack
            },
        )
        return
    }
    val selectedReady = draft.rows.filter { it.status == BatchJlcStatus.Ready && it.selected }
    val planResult = remember(selectedReady) { runCatching { BatchJlcCommitPlanner.plan(selectedReady, emptySet()) } }
    val visibleRows = draft.rows.filter {
        it.status == if (page == BatchPage.Review) BatchJlcStatus.Ready else BatchJlcStatus.Pending
    }
    val reviewSummary = planResult.getOrNull()?.groupBy { it.sku to it.location }.orEmpty()
    val reviewSkuCount = reviewSummary.keys.map { it.first }.distinct().size

    fun retryPending() {
        val token = generation
        processJob = scope.launch { processRows(ProcessScope.Pending, token) }
    }
    fun commitSelected() {
        if (committing) return
        committing = true
        scope.launch {
            try {
                val result = repository.commitBatchJlc(draft.sessionId, selectedReady)
                message = result.message
                if (!result.isSuccess) dialogMessage = result.message
                if (result.isSuccess) {
                    val receipts = repository.loadBatchJlcReceipts(draft.sessionId)
                    save(draft.copy(rows = draft.rows.map {
                        if (it.id in receipts) it.copy(status = BatchJlcStatus.Committed) else it
                    }))
                    onCommitted()
                }
            } finally { committing = false }
        }
    }

    SecondaryPageScaffold(
        title = stringResource(R.string.batch_title),
        onBack = ::requestDismiss,
        navigationEnabled = !processing && !committing,
        bottomBar = {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (page) {
                BatchPage.Pending -> OutlinedButton(
                    onClick = ::retryPending,
                    enabled = visibleRows.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.batch_retry_pending)) }
                BatchPage.Review -> Button(
                    onClick = ::commitSelected,
                    enabled = selectedReady.isNotEmpty() && planResult.isSuccess && !busy && !saveFailed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (selectedReady.any { it.id.startsWith("order-") }) R.string.lcsc_order_commit_action else R.string.batch_commit)) }
                BatchPage.Capture -> Unit
            }
            TextButton(onClick = { confirmClear = true }, enabled = !processing && !committing,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.batch_clear)) }
            if (saveFailed) OutlinedButton(onClick = { save(draft) }, enabled = !saving,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.batch_retry_save)) }
        }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("batch_inbound_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchPage.entries.forEach { candidate ->
                        item(key = candidate.name) {
                            val count = draft.rows.count { it.status == when (candidate) {
                                BatchPage.Capture -> BatchJlcStatus.Captured
                                BatchPage.Review -> BatchJlcStatus.Ready
                                BatchPage.Pending -> BatchJlcStatus.Pending
                            } }
                            FilterChip(selected = page == candidate, onClick = { page = candidate }, enabled = !busy,
                                label = { Text(stringResource(candidate.labelRes(), count)) })
                        }
                    }
                }
            }
            if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.primary) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.catalog_browser_session_notice), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        repository.retryDomesticCatalogNow()
                        val retryScope = if (page == BatchPage.Capture) ProcessScope.Captured else ProcessScope.Pending
                        processJob = scope.launch { processRows(retryScope, generation) }
                    }, enabled = !processing && !committing) { Text(stringResource(R.string.catalog_retry_domestic)) }
                    draft.rows.asSequence().mapNotNull { LcscPublicCatalog.domesticSearchUrl(it.sku) }.firstOrNull()?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) {
                            Text(stringResource(R.string.catalog_open_domestic_browser))
                        }
                    }
                }
            }
            if (processing || committing) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (committing) R.string.batch_committing else R.string.batch_processing))
                        if (processing && processTotal > 0) {
                            LinearProgressIndicator(
                                progress = { processedCount.toFloat() / processTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("$processedCount / $processTotal", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (processing) OutlinedButton(onClick = { processJob?.cancel() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
            item {
                if (busy) {
                    Text(locations.firstOrNull { it.id == globalLocation }?.name
                        ?: stringResource(R.string.batch_default_location))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.batch_default_location), style = MaterialTheme.typography.labelMedium)
                        ImportLocationPicker(
                            value = globalLocation,
                            onValueChange = { selected ->
                                globalLocation = selected
                                scope.launch { locations = repository.loadStorageLocations() }
                            },
                            storageLocations = locations,
                        )
                    }
                }
            }
            when (page) {
                BatchPage.Capture -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { scanner = true },
                                enabled = loaded && !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.batch_scan)) }
                            OutlinedButton(
                                onClick = { showScannerSettings = true },
                                enabled = !busy,
                            ) { Text(stringResource(R.string.batch_scanner_settings)) }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { orderPicker.launch(arrayOf("*/*")) },
                            enabled = loaded && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.lcsc_order_import_action)) }
                    }
                    item { OutlinedButton(onClick = {
                        val token = generation
                        processJob = scope.launch { processRows(ProcessScope.Captured, token) }
                    }, enabled = draft.rows.any { it.status == BatchJlcStatus.Captured } && !busy,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.batch_finish_capture)) } }
                    item { Text(stringResource(R.string.batch_saved_count, draft.rows.size)) }
                }
                BatchPage.Review, BatchPage.Pending -> {
                    if (page == BatchPage.Review) {
                        item(key = "review-summary-control") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.batch_layout_summary_count, selectedReady.size, reviewSkuCount))
                                TextButton(onClick = { summaryExpanded = !summaryExpanded }) {
                                    Text(stringResource(
                                        if (summaryExpanded) R.string.batch_layout_summary_collapse
                                        else R.string.batch_layout_summary_expand,
                                    ))
                                }
                            }
                        }
                        if (summaryExpanded) {
                            reviewSummary.forEach { (key, group) ->
                                val sum = group.fold(0) { total, item -> Math.addExact(total, item.quantity) }
                                item(key = "summary-${key.first}-${key.second}") {
                                    Text("${key.first} · ${key.second}: +$sum")
                                }
                            }
                        }
                        planResult.exceptionOrNull()?.let {
                            item { Text(stringResource(R.string.batch_invalid_selection), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    items(visibleRows, key = { it.id }) { row ->
                        BatchRowCard(row, page == BatchPage.Review, locations, !busy, { changed ->
                            generation++
                            update(changed)
                        }) {
                            generation++
                            save(draft.copy(rows = draft.rows.filterNot { it.id == row.id }))
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.batch_clear_title)) },
        text = { Text(stringResource(R.string.batch_clear_detail)) },
        confirmButton = { Button(onClick = {
            generation++
            val fresh = BatchJlcDraft()
            draft = fresh; loadFailed = false; loaded = true; saveRevision.incrementAndGet()
            saving = true
            pendingSave = scope.launch {
                try { withContext(Dispatchers.IO) { saveMutex.withLock { store.clear(); store.save(fresh) } } }
                catch (_: Exception) { saveFailed = true; dialogMessage = context.getString(R.string.batch_save_failed) }
                finally { saving = false }
            }
            confirmClear = false; page = BatchPage.Capture
        }) { Text(stringResource(R.string.batch_clear_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (showScannerSettings) BatchScannerSettingsDialog(
        settings = scannerSettings,
        onDismiss = { showScannerSettings = false },
        onSave = { value ->
            scannerSettings = value.normalized()
            scannerSettingsStore.save(scannerSettings)
            showScannerSettings = false
        },
    )
    dialogMessage?.let { detail -> AlertDialog(
        onDismissRequest = { dialogMessage = null },
        title = { Text(stringResource(R.string.batch_attention_title)) }, text = { Text(detail) },
        confirmButton = { Button(onClick = { dialogMessage = null; page = BatchPage.Pending }) {
            Text(stringResource(R.string.batch_open_pending))
        } },
        dismissButton = { TextButton(onClick = { dialogMessage = null }) { Text(stringResource(R.string.action_close)) } },
    ) }
}

@Composable
private fun BatchScannerSettingsDialog(
    settings: BatchScannerSettings,
    onDismiss: () -> Unit,
    onSave: (BatchScannerSettings) -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_scanner_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.batch_scanner_interval))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButton(
                        onClick = { draft = draft.copy(intervalMs = draft.intervalMs - BATCH_SCAN_INTERVAL_STEP_MS).normalized() },
                        enabled = draft.intervalMs > MIN_BATCH_SCAN_INTERVAL_MS,
                    ) { Text("−") }
                    Text(
                        stringResource(R.string.batch_scanner_interval_value, draft.intervalMs / 1_000.0),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    OutlinedButton(
                        onClick = { draft = draft.copy(intervalMs = draft.intervalMs + BATCH_SCAN_INTERVAL_STEP_MS).normalized() },
                        enabled = draft.intervalMs < MAX_BATCH_SCAN_INTERVAL_MS,
                    ) { Text("+") }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = draft.soundEnabled,
                        onCheckedChange = { draft = draft.copy(soundEnabled = it) },
                    )
                    Text(
                        stringResource(R.string.batch_scanner_sound),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = draft.vibrationEnabled,
                        onCheckedChange = { draft = draft.copy(vibrationEnabled = it) },
                    )
                    Text(
                        stringResource(R.string.batch_scanner_vibration),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun BatchPage.labelRes() = when (this) {
    BatchPage.Capture -> R.string.batch_tab_capture
    BatchPage.Review -> R.string.batch_tab_review
    BatchPage.Pending -> R.string.batch_tab_pending
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSelector(locations: List<StorageLocationRecord>, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it && enabled }) {
        OutlinedTextField(locations.firstOrNull { it.id == value }?.name ?: value, {}, readOnly = true, enabled = enabled,
            label = { Text(stringResource(R.string.batch_default_location)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            locations.forEach { location -> DropdownMenuItem(
                text = { Text(location.name) }, onClick = { onChange(location.id); expanded = false },
            ) }
        }
    }
}

@Composable
private fun BatchRowCard(row: BatchJlcRow, ready: Boolean, locations: List<StorageLocationRecord>, enabled: Boolean,
    onUpdate: (BatchJlcRow) -> Unit, onRemove: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { Checkbox(row.selected, { onUpdate(row.copy(selected = it)) }, enabled = ready && enabled)
            Text(row.sku.ifBlank { stringResource(R.string.batch_unrecognized) }, style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(
            R.string.batch_row_summary, row.quantityText,
            locations.firstOrNull { it.id == row.location }?.name ?: row.location,
        ))
        if (row.error.isNotBlank()) Text(row.error, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { editing = true },
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag("batch_edit_${row.id}"),
            ) {
                Text(stringResource(R.string.action_edit))
            }
            TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.movements_batch_remove_action))
            }
        }
    } }
    if (editing) BatchEditDialog(row, locations, onDismiss = { editing = false }) { changed -> onUpdate(changed); editing = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchEditDialog(row: BatchJlcRow, locations: List<StorageLocationRecord>, onDismiss: () -> Unit, onSave: (BatchJlcRow) -> Unit) {
    var sku by rememberSaveable(row.id) { mutableStateOf(row.sku) }; var quantity by rememberSaveable(row.id) { mutableStateOf(row.quantityText) }
    var location by rememberSaveable(row.id) { mutableStateOf(row.location) }; var name by rememberSaveable(row.id) { mutableStateOf(row.name) }
    var category by rememberSaveable(row.id) { mutableStateOf(row.category) }; var packageName by rememberSaveable(row.id) { mutableStateOf(row.packageName) }
    val valid = LcscPublicCatalog.normalizeSku(sku) != null && quantity.toIntOrNull()?.let { it > 0 } == true &&
        location.isNotBlank() && name.isNotBlank() && category.isNotBlank() && packageName.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (row.id.startsWith("order-")) R.string.lcsc_order_review_title else R.string.batch_edit_title)) }, text = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .imePadding().testTag("batch_edit_fields"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(sku, { sku = it.uppercase() }, label = { Text(stringResource(R.string.batch_sku)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(quantity, { quantity = it }, label = { Text(stringResource(if (row.id.startsWith("order-")) R.string.lcsc_order_quantity else R.string.batch_quantity)) }, modifier = Modifier.fillMaxWidth())
            LocationSelector(locations, location, true) { location = it }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(category, { category = it }, label = { Text(stringResource(R.string.field_category)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(packageName, { packageName = it }, label = { Text(stringResource(R.string.field_package)) }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = {
        val skuChanged = !sku.equals(row.sku, true)
        val savedName = if (skuChanged && name == row.name) "" else name
        val savedCategory = if (skuChanged && category == row.category) "" else category
        val savedPackage = if (skuChanged && packageName == row.packageName) "" else packageName
        val savedValid = !skuChanged && valid
        onSave(row.copy(sku = sku, quantityText = quantity, location = location,
            name = savedName, category = savedCategory, packageName = savedPackage,
            description = if (skuChanged) "" else row.description,
            status = if (savedValid) BatchJlcStatus.Ready else BatchJlcStatus.Pending,
            error = ""))
    }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
