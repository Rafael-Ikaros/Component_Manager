package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.componentvault.android.data.ComponentImportParser
import com.componentvault.android.data.CategoryLearningStore
import com.componentvault.android.data.AppDiagnostics
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.ImportLookupRequestGate
import com.componentvault.android.data.LcscDomesticBlockedException
import com.componentvault.android.data.LcscDomesticCatalog
import com.componentvault.android.data.LcscDomesticProduct
import com.componentvault.android.data.LcscPublicCatalog
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportFieldOrigin
import com.componentvault.android.model.ComponentImportLearningMatchType
import com.componentvault.android.model.ComponentOfficialLookupOutcome
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.LocalComponentSku
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.model.isJlcSource
import com.componentvault.android.model.referenceDisplayName
import com.componentvault.android.model.withOfficialMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private enum class ImportScannerMode {
    Qr,
    SupplierText,
}

@Composable
internal fun JlcImportSurface(
    layoutMode: InventoryLayoutMode,
    syncConfiguration: SyncConfiguration,
    appPreferences: AppPreferences,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    onCreateStorageLocation: (String, String, (OperationResult) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSaveImportedComponent: (ComponentDraft, ComponentImportCandidate, (OperationResult) -> Unit) -> Unit,
    onOpenFullEditor: (ComponentDraft, ComponentImportCandidate) -> Unit,
    onLocationCodeScan: (String) -> Unit = {},
    onManualEntry: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val repository = remember(context) { InventoryRepository(context) }
    val categoryLearningStore = remember(context) { CategoryLearningStore(context) }
    val strings = vaultStrings()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val lookupGate = remember { ImportLookupRequestGate() }
    val lookupMutex = remember { Mutex() }

    var rawInput by remember { mutableStateOf("") }
    var partNumberInput by rememberSaveable { mutableStateOf("") }
    var domesticResults by remember { mutableStateOf<List<LcscDomesticProduct>>(emptyList()) }
    var domesticSearchInProgress by remember { mutableStateOf(false) }
    var domesticSearchMessage by remember { mutableStateOf<String?>(null) }
    var domesticSearchIsError by remember { mutableStateOf(false) }
    var domesticSearchJob by remember { mutableStateOf<Job?>(null) }
    var selectedDomesticSku by remember { mutableStateOf<String?>(null) }
    var baseCandidate by remember { mutableStateOf<ComponentImportCandidate?>(null) }
    var displayedCandidate by remember { mutableStateOf<ComponentImportCandidate?>(null) }
    var scannerMode by rememberSaveable { mutableStateOf<ImportScannerMode?>(null) }
    var sku by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var location by remember(appPreferences.suggestedImportLocation) {
        mutableStateOf(appPreferences.suggestedImportLocation)
    }
    var availableLocations by remember(storageLocations) { mutableStateOf(storageLocations.filterNot { it.deleted }) }
    var locationMenuExpanded by remember { mutableStateOf(false) }
    var creatingLocation by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf("") }
    var locationCreationBusy by remember { mutableStateOf(false) }
    var locationCreationError by remember { mutableStateOf<String?>(null) }
    var minStockText by remember(appPreferences.defaultImportMinStock) {
        mutableStateOf(appPreferences.defaultImportMinStock.toString())
    }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    var lookupIsError by remember { mutableStateOf(false) }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.takeIf { it.isNotBlank() }?.let { errorDialogMessage = it }
    }
    var lookupInProgress by remember { mutableStateOf(false) }
    var showLookupProgress by remember { mutableStateOf(false) }
    var lookupGeneration by remember { mutableIntStateOf(0) }
    var learningMatchType by remember { mutableStateOf<ComponentImportLearningMatchType?>(null) }
    var skuEdited by remember { mutableStateOf(false) }
    var nameEdited by remember { mutableStateOf(false) }
    var categoryEdited by remember { mutableStateOf(false) }
    var packageEdited by remember { mutableStateOf(false) }
    var modelEdited by remember { mutableStateOf(false) }
    var brandEdited by remember { mutableStateOf(false) }
    var skuOrigin by remember { mutableStateOf(ComponentImportFieldOrigin.Parsed) }
    var nameOrigin by remember { mutableStateOf(ComponentImportFieldOrigin.Parsed) }
    var categoryOrigin by remember { mutableStateOf(ComponentImportFieldOrigin.Parsed) }
    var packageOrigin by remember { mutableStateOf(ComponentImportFieldOrigin.Parsed) }
    var modelOrigin by remember { mutableStateOf(ComponentImportFieldOrigin.Parsed) }
    var brandOrigin by remember { mutableStateOf(ComponentImportFieldOrigin.Parsed) }

    LaunchedEffect(lookupInProgress) {
        showLookupProgress = false
        if (lookupInProgress) {
            delay(300)
            showLookupProgress = true
        }
    }

    fun applyDisplayedCandidate(
        candidate: ComponentImportCandidate,
        preserveUserEdits: Boolean,
    ) {
        displayedCandidate = candidate
        rawInput = candidate.rawPayload
        if (!preserveUserEdits || !skuEdited) {
            sku = candidate.sku
            skuOrigin = candidate.fieldOrigins.sku
        }
        if (!preserveUserEdits || !nameEdited) {
            name = candidate.name
            nameOrigin = candidate.fieldOrigins.name
        }
        if (!preserveUserEdits || !categoryEdited) {
            category = candidate.category
            categoryOrigin = candidate.fieldOrigins.category
        }
        if (!preserveUserEdits || !packageEdited) {
            packageName = candidate.packageName
            packageOrigin = candidate.fieldOrigins.packageName
        }
        if (!preserveUserEdits || !modelEdited) {
            model = candidate.model.orEmpty()
            modelOrigin = candidate.fieldOrigins.model
        }
        if (!preserveUserEdits || !brandEdited) {
            brand = candidate.brand.orEmpty()
            brandOrigin = candidate.fieldOrigins.brand
        }
        if (!preserveUserEdits) {
            quantityText = (candidate.suggestedQuantity ?: 1).coerceAtLeast(1).toString()
            candidate.notes.firstOrNull {
                it.startsWith("Warehouse location: ") || it.startsWith("仓位：")
            }?.let { note ->
                location = note
                    .removePrefix("Warehouse location: ")
                    .removePrefix("仓位：")
                    .trim()
            }
            candidate.notes.firstOrNull {
                it.startsWith("Minimum stock: ") || it.startsWith("最低库存：")
            }?.let { note ->
                note
                    .removePrefix("Minimum stock: ")
                    .removePrefix("最低库存：")
                    .trim()
                    .toIntOrNull()
                    ?.let { parsedMinStock ->
                    minStockText = parsedMinStock.toString()
                }
            }
            skuEdited = false
            nameEdited = false
            categoryEdited = false
            packageEdited = false
            modelEdited = false
            brandEdited = false
        }
        feedbackMessage = null
    }

    fun setBaseCandidate(candidate: ComponentImportCandidate) {
        domesticSearchJob?.cancel()
        selectedDomesticSku = null
        val attempt = lookupGate.begin(candidate.rawPayload, displayedCandidate != null)
        lookupGeneration = attempt.generation
        baseCandidate = candidate
        if (attempt.replaceDisplayedCandidate) learningMatchType = null
        val willLookup = candidate.sourceType != com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel &&
            appPreferences.enablePublicJlcLookup && LcscPublicCatalog.normalizeSku(candidate.sku) != null
        lookupMessage = if (willLookup) strings.importer.lookupLoading else context.getString(com.componentvault.android.R.string.importer_scan_recognized)
        lookupIsError = false
        lookupInProgress = willLookup
        if (attempt.replaceDisplayedCandidate) applyDisplayedCandidate(candidate, preserveUserEdits = false)
        else feedbackMessage = null
    }

    fun applyDomesticProduct(product: LcscDomesticProduct) {
        val currentQuantity = quantityText
        val candidate = categoryLearningStore.apply(
            ComponentImportParser.parseScannedQr(requireNotNull(product.metadata.sku))
                .withOfficialMetadata(product.metadata),
        )
        lookupGeneration = lookupGate.begin(candidate.rawPayload, displayedCandidate != null).generation
        baseCandidate = candidate
        selectedDomesticSku = product.metadata.sku
        applyDisplayedCandidate(candidate, preserveUserEdits = displayedCandidate != null)
        quantityText = currentQuantity
        domesticSearchMessage = context.getString(com.componentvault.android.R.string.import_domestic_selected)
        lookupInProgress = false
        lookupMessage = domesticSearchMessage
        lookupIsError = false
    }

    fun applyInternationalFallback(skuValue: String) {
        val currentQuantity = quantityText
        val candidate = ComponentImportParser.parseScannedQr(skuValue)
        setBaseCandidate(candidate)
        quantityText = currentQuantity
    }

    fun buildQuickSaveDraftOrNull(): ComponentDraft? {
        val candidate = displayedCandidate
        if (candidate == null) {
            feedbackMessage = strings.importer.parseFirstError
            return null
        }

        val quantity = quantityText.toIntOrNull()
        val minStock = minStockText.toIntOrNull()
        if (name.isBlank() || category.isBlank() || packageName.isBlank() ||
            availableLocations.none { it.id == location }) {
            feedbackMessage = strings.forms.componentRequiredFields
            return null
        }
        if (quantity == null || minStock == null) {
            feedbackMessage = strings.forms.invalidQuantityMinStock
            return null
        }
        if (quantity < 0 || minStock < 0) {
            feedbackMessage = strings.forms.componentNonNegative
            return null
        }
        feedbackMessage = null
        sku = LocalComponentSku.ensure(sku)
        return candidate.toComponentDraft(
            quantity = quantity,
            location = location,
            minStock = minStock,
            skuOverride = sku,
            nameOverride = name,
            categoryOverride = category,
            packageNameOverride = packageName,
            modelOverride = model.blankToNull(),
            brandOverride = brand.blankToNull(),
        )
    }

    fun buildEditorDraftOrNull(): ComponentDraft? {
        val candidate = displayedCandidate
        if (candidate == null) {
            feedbackMessage = strings.importer.parseFirstError
            return null
        }

        feedbackMessage = null
        sku = LocalComponentSku.ensure(sku)
        return candidate.toComponentDraft(
            quantity = quantityText.toIntOrNull()?.takeIf { it >= 0 }
                ?: (candidate.suggestedQuantity ?: 0).coerceAtLeast(0),
            location = location.ifBlank { appPreferences.suggestedImportLocation },
            minStock = minStockText.toIntOrNull()?.takeIf { it >= 0 }
                ?: appPreferences.defaultImportMinStock.coerceAtLeast(0),
            skuOverride = sku,
            nameOverride = name,
            categoryOverride = category,
            packageNameOverride = packageName,
            modelOverride = model.blankToNull(),
            brandOverride = brand.blankToNull(),
        )
    }

    fun buildPreviewDraft(): ComponentDraft? {
        val candidate = displayedCandidate ?: return null
        val quantity = quantityText.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val minStock = minStockText.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return candidate.toComponentDraft(
            quantity = quantity,
            location = location.ifBlank { appPreferences.suggestedImportLocation },
            minStock = minStock,
            skuOverride = sku.ifBlank { candidate.sku },
            nameOverride = name.ifBlank { candidate.name.ifBlank { candidate.referenceDisplayName().orEmpty() } },
            categoryOverride = category.ifBlank { candidate.category },
            packageNameOverride = packageName.ifBlank { candidate.packageName },
            modelOverride = model.blankToNull() ?: candidate.model,
            brandOverride = brand.blankToNull() ?: candidate.brand,
        )
    }

    when (scannerMode) {
        ImportScannerMode.Qr -> {
            BackHandler(onBack = { scannerMode = null })
            JlcQrScannerSurface(
                onDismiss = { scannerMode = null },
                onScanResult = { result ->
                    scannerMode = null
                    if (com.componentvault.android.data.LocationQrCodec.isLocationCode(result)) {
                        onLocationCodeScan(result)
                    } else {
                        runCatching { ComponentImportParser.parseScannedQr(result) }
                            .onSuccess(::setBaseCandidate)
                            .onFailure {
                                AppDiagnostics.record("scanner_choice", "valid" to false, "type" to it.javaClass)
                                rawInput = result
                                feedbackMessage = it.message ?: strings.importer.scannerFailedDescription
                            }
                    }
                },
                scannerMode = JlcQrScannerMode.InventoryLookup,
            )
            return
        }

        ImportScannerMode.SupplierText -> {
            BackHandler(onBack = { scannerMode = null })
            ImportTextScannerSurface(
                onDismiss = { scannerMode = null },
                preferredOcrEngineMode = appPreferences.ocrEngineMode,
                onOcrScanned = { result ->
                    scannerMode = null
                    runCatching { ComponentImportParser.parseSupplierOcr(result) }
                        .onSuccess(::setBaseCandidate)
                        .onFailure {
                            rawInput = result.fullText
                            feedbackMessage = it.message ?: strings.importer.supplierScanFailedDescription
                        }
                },
            )
            return
        }

        null -> Unit
    }

    LaunchedEffect(
        baseCandidate?.rawPayload,
        lookupGeneration,
        appPreferences.enableLocalAutoRecognition,
        appPreferences.preferAggressiveAutoRecognition,
        appPreferences.enableLocalImportLearning,
        appPreferences.enablePublicJlcLookup,
    ) {
        val base = baseCandidate ?: return@LaunchedEffect
        val candidate = lookupGate.candidateForLookup(base, displayedCandidate)
        val generation = lookupGeneration
        if (candidate.sku.equals(selectedDomesticSku, ignoreCase = true)) return@LaunchedEffect
        delay(250)
        if (!lookupGate.isCurrent(generation)) return@LaunchedEffect
        lookupInProgress = candidate.sourceType != com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel &&
            appPreferences.enablePublicJlcLookup && LcscPublicCatalog.normalizeSku(candidate.sku) != null
        val resolution = lookupMutex.withLock {
            if (!lookupGate.isCurrent(generation)) null else repository.enrichImportCandidate(
                candidate = candidate,
                appPreferences = appPreferences,
                syncConfiguration = syncConfiguration,
            )
        } ?: return@LaunchedEffect
        if (!lookupGate.isCurrent(generation)) return@LaunchedEffect
        // A response for the scanned SKU must not enrich a different user-entered SKU.
        if (skuEdited && !sku.trim().equals(candidate.sku, ignoreCase = true)) {
            lookupInProgress = false
            return@LaunchedEffect
        }
        learningMatchType = resolution.learningMatch?.matchedBy
        applyDisplayedCandidate(
            candidate = categoryLearningStore.apply(resolution.candidate),
            preserveUserEdits = true,
        )

        val lookupResult = resolution.officialLookupResult
        if (lookupResult == null) {
            lookupInProgress = false
            lookupIsError = false
            lookupMessage = context.getString(com.componentvault.android.R.string.importer_scan_recognized)
            return@LaunchedEffect
        }

        when (lookupResult.outcome) {
            ComponentOfficialLookupOutcome.Success -> {
                lookupInProgress = false
                lookupIsError = false
                lookupMessage = lookupResult.message ?: strings.importer.lookupSuccess
            }

            ComponentOfficialLookupOutcome.NoMatch -> {
                lookupInProgress = false
                lookupIsError = false
                lookupMessage = lookupResult.message ?: strings.importer.lookupNoMatch
                errorDialogMessage = lookupMessage
            }

            ComponentOfficialLookupOutcome.NotConfigured -> {
                lookupInProgress = false
                lookupIsError = false
                lookupMessage = lookupResult.message ?: strings.importer.lookupNotConfigured
            }

            ComponentOfficialLookupOutcome.Failed -> {
                lookupInProgress = false
                lookupIsError = true
                lookupMessage = lookupResult.message ?: strings.importer.lookupFailed(strings.importer.exportGenericError)
                errorDialogMessage = lookupMessage
            }
        }
    }

    AdaptiveFormSurface(
        title = strings.importer.title,
        layoutMode = layoutMode,
        onDismiss = onDismiss,
        onSave = {
            baseCandidate?.let { sourceCandidate ->
                buildQuickSaveDraftOrNull()?.let { draft ->
                    onSaveImportedComponent(draft, sourceCandidate) { result ->
                        if (result.isSuccess && categoryEdited) {
                            displayedCandidate?.let { categoryLearningStore.learn(it, draft.category) }
                        } else if (!result.isSuccess) feedbackMessage = result.message
                    }
                }
            }
        },
    ) {
        if (baseCandidate == null && onManualEntry != null) item {
            OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_manual_entry))
            }
        }
        item {
            SectionPane(
                title = strings.importer.subtitle,
                supporting = strings.importer.supportedFormatHint,
            ) {
                FilledTonalButton(
                    onClick = { scannerMode = ImportScannerMode.Qr },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.importer.actionScanQr)
                }
                if (showLookupProgress) {
                    Row(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = strings.importer.lookupLoading,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else if (!lookupInProgress) lookupMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (lookupIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = partNumberInput,
                    onValueChange = {
                        domesticSearchJob?.cancel()
                        domesticSearchJob = null
                        domesticSearchInProgress = false
                        partNumberInput = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_search_label)) },
                    placeholder = { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_search_placeholder)) },
                )
                FilledTonalButton(
                    onClick = {
                        val keyword = partNumberInput.trim()
                        if (keyword.isBlank()) return@FilledTonalButton
                        LcscPublicCatalog.normalizeSku(keyword)?.let { normalized ->
                            applyInternationalFallback(normalized)
                            domesticSearchMessage = context.getString(com.componentvault.android.R.string.import_catalog_searching)
                            return@FilledTonalButton
                        }
                        domesticSearchInProgress = true
                        domesticSearchMessage = if (appPreferences.appLanguage == com.componentvault.android.model.AppLanguage.English) {
                            context.getString(com.componentvault.android.R.string.catalog_keyword_domestic_only)
                        } else null
                        domesticSearchIsError = false
                        domesticResults = emptyList()
                        domesticSearchJob?.cancel()
                        domesticSearchJob = coroutineScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.searchDomesticCatalog(keyword)
                                }
                            }.onSuccess { results ->
                                AppDiagnostics.record("lookup_domestic", "success" to true, "count" to results.size)
                                if (keyword != partNumberInput.trim()) return@onSuccess
                                domesticSearchInProgress = false
                                val normalized = LcscPublicCatalog.normalizeSku(keyword)
                                val exact = normalized?.let { LcscDomesticCatalog.exactMatch(it, results) }
                                if (exact != null) {
                                    domesticResults = listOf(exact)
                                    applyDomesticProduct(exact)
                                } else {
                                    domesticResults = results
                                    domesticSearchMessage = if (results.isEmpty()) {
                                        context.getString(com.componentvault.android.R.string.import_catalog_empty)
                                    } else {
                                        context.getString(com.componentvault.android.R.string.import_catalog_choose, results.size)
                                    }
                                    normalized?.let(::applyInternationalFallback)
                                }
                            }.onFailure { error ->
                                if (error is CancellationException || keyword != partNumberInput.trim()) return@onFailure
                                AppDiagnostics.record("lookup_domestic", "success" to false, "type" to error.javaClass)
                                domesticSearchInProgress = false
                                domesticSearchIsError = true
                                domesticSearchMessage = when (error) {
                                    is LcscDomesticBlockedException -> context.getString(com.componentvault.android.R.string.import_catalog_blocked)
                                    is com.componentvault.android.data.LcscDomesticRateLimitedException -> context.getString(com.componentvault.android.R.string.catalog_failure_rate_limited)
                                    is com.componentvault.android.data.LcscDomesticCoolingDownException -> context.getString(com.componentvault.android.R.string.catalog_failure_cooling_down)
                                    else -> context.getString(com.componentvault.android.R.string.catalog_keyword_failed)
                                }
                                LcscPublicCatalog.normalizeSku(keyword)?.let(::applyInternationalFallback)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !domesticSearchInProgress,
                ) {
                    Row(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (domesticSearchInProgress) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            if (domesticSearchInProgress) {
                                androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_searching)
                            } else {
                                androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_search_action)
                            },
                        )
                    }
                }
                domesticSearchMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (domesticSearchIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (domesticSearchIsError) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.catalog_browser_session_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        repository.retryDomesticCatalogNow()
                        val keyword = partNumberInput.trim()
                        if (keyword.isNotEmpty()) {
                            domesticSearchInProgress = true
                            domesticSearchIsError = false
                            domesticSearchMessage = context.getString(com.componentvault.android.R.string.import_catalog_searching)
                            domesticSearchJob?.cancel()
                            domesticSearchJob = coroutineScope.launch {
                                runCatching { withContext(Dispatchers.IO) { repository.searchDomesticCatalog(keyword) } }
                                    .onSuccess { results ->
                                        domesticSearchInProgress = false
                                        domesticResults = results
                                        LcscPublicCatalog.normalizeSku(keyword)?.let { normalized ->
                                            LcscDomesticCatalog.exactMatch(normalized, results)?.let(::applyDomesticProduct)
                                                ?: applyInternationalFallback(normalized)
                                        }
                                        domesticSearchMessage = if (results.isEmpty()) context.getString(com.componentvault.android.R.string.import_catalog_empty)
                                        else context.getString(com.componentvault.android.R.string.import_catalog_choose, results.size)
                                    }.onFailure { error ->
                                        if (error is CancellationException) return@onFailure
                                        domesticSearchInProgress = false; domesticSearchIsError = true
                                        domesticSearchMessage = error.message ?: context.getString(com.componentvault.android.R.string.catalog_keyword_failed)
                                    }
                            }
                        }
                    }) { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.catalog_retry_domestic)) }
                    LcscDomesticCatalog.searchUrl(partNumberInput)?.let { searchUrl ->
                        TextButton(onClick = { uriHandler.openUri(searchUrl) }) {
                            Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_open_browser))
                        }
                    }
                }
                domesticResults.forEach { product ->
                    val metadata = product.metadata
                    SectionPane(
                        title = listOfNotNull(metadata.sku, metadata.model).joinToString(" · "),
                        supporting = metadata.name.orEmpty(),
                    ) {
                        metadata.brand?.let { ValueBlock(strings.importer.vendorLabel, it) }
                        metadata.categoryPath?.let { ValueBlock(strings.common.fieldCategory, it) }
                        metadata.packageName?.let { ValueBlock(strings.common.fieldPackage, it) }
                        metadata.imageUrl?.let { ValueBlock(
                            androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_image_source),
                            it,
                        ) }
                        product.parameters.entries.take(8).forEach { (key, value) -> ValueBlock(key, value) }
                        FilledTonalButton(
                            onClick = { applyDomesticProduct(product) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_use_candidate))
                        }
                        metadata.officialUrl?.let { url ->
                            TextButton(onClick = { uriHandler.openUri(url) }) {
                                Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_open_domestic_product))
                            }
                        }
                        product.datasheetUrl?.let { url ->
                            TextButton(onClick = { uriHandler.openUri(url) }) {
                                Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_catalog_open_datasheet))
                            }
                        }
                    }
                }
                Text(
                    androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_ocr_secondary_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { scannerMode = ImportScannerMode.SupplierText },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.importer.actionScanSupplierText)
                }
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = { rawInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.importer.rawInputLabel) },
                    placeholder = { Text(strings.importer.rawInputPlaceholder) },
                    minLines = 6,
                )
                OutlinedButton(
                    onClick = {
                        runCatching { ComponentImportParser.parseJlcText(rawInput) }
                            .onSuccess { candidate -> AppDiagnostics.record("scanner_choice", "valid" to true);setBaseCandidate(candidate) }
                            .onFailure { AppDiagnostics.record("scanner_choice", "valid" to false, "type" to it.javaClass);feedbackMessage = it.message ?: strings.importer.parseFirstError }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.importer.actionParseText)
                }
                OutlinedButton(
                    onClick = {
                        runCatching { ComponentImportParser.parseSupplierText(rawInput) }
                            .onSuccess(::setBaseCandidate)
                            .onFailure { feedbackMessage = it.message ?: strings.importer.parseSupplierTextError }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.importer.actionParseSupplierText)
                }
            }
        }

        displayedCandidate?.let { candidate ->
            val referenceName = candidate.referenceDisplayName()?.takeIf { reference ->
                !reference.equals(name.trim(), ignoreCase = true)
            }
            item {
                SectionPane(
                    title = strings.importer.enrichmentTitle,
                    supporting = strings.importer.enrichmentSubtitle,
                ) {
                    ValueBlock(label = strings.importer.sourceLabel, value = candidate.sourceLabel)
                    candidate.vendor?.takeIf { it.isNotBlank() }?.let {
                        ValueBlock(label = strings.importer.vendorLabel, value = it)
                    }
                    candidate.modelFamily?.takeIf { it.isNotBlank() }?.let {
                        ValueBlock(label = strings.importer.modelFamilyLabel, value = it)
                    }
                    candidate.recognitionConfidence?.takeIf { it.isNotBlank() }?.let {
                        ValueBlock(label = strings.importer.recognitionConfidenceLabel, value = it)
                    }
                    candidate.matchedBy?.takeIf { it.isNotBlank() }?.let {
                        ValueBlock(label = strings.importer.matchedByLabel, value = it)
                    }
                    Text(
                        text = when (learningMatchType) {
                            ComponentImportLearningMatchType.Sku -> strings.importer.learningMatchSku
                            ComponentImportLearningMatchType.Mpn -> strings.importer.learningMatchMpn
                            null -> strings.importer.localRulesOnly
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (candidate.sourceType != com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel) {
                        val serverMessage = when {
                            lookupInProgress -> null
                            appPreferences.enablePublicJlcLookup ->
                                lookupMessage ?: strings.importer.lookupOnlyFillsMissing
                            else -> strings.importer.serverLookupDisabled
                        }
                        serverMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lookupIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        LcscPublicCatalog.domesticSearchUrl(sku)?.let { domesticUrl ->
                            TextButton(onClick = {
                                runCatching { uriHandler.openUri(domesticUrl) }
                                    .onFailure { feedbackMessage = it.message }
                            }) {
                                Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_open_domestic_product))
                            }
                        }
                        LcscPublicCatalog.productUrl(sku)?.let { productUrl ->
                            TextButton(onClick = {
                                runCatching { uriHandler.openUri(productUrl) }
                                    .onFailure { feedbackMessage = it.message }
                            }) {
                                Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_open_public_product))
                            }
                        }
                    }
                }
            }
            item {
                SectionPane(
                    title = strings.importer.recognizedTitle,
                    supporting = strings.importer.recognizedSubtitle,
                ) {
                    ImportOriginField(
                        value = sku,
                        onValueChange = {
                            sku = it
                            skuEdited = true
                            skuOrigin = ComponentImportFieldOrigin.User
                        },
                        label = strings.common.fieldSku,
                        origin = skuOrigin,
                        strings = strings,
                    )
                    ImportOriginField(
                        value = name,
                        onValueChange = {
                            name = it
                            nameEdited = true
                            nameOrigin = ComponentImportFieldOrigin.User
                        },
                        label = strings.common.fieldName,
                        origin = nameOrigin,
                        strings = strings,
                    )
                    if (referenceName != null) {
                        ValueBlock(
                            label = strings.importer.referenceNameLabel,
                            value = referenceName,
                        )
                        OutlinedButton(
                            onClick = {
                                name = referenceName
                                nameEdited = true
                                nameOrigin = ComponentImportFieldOrigin.User
                                feedbackMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.importer.actionUseReferenceName)
                        }
                    }
                    if (name.isBlank()) {
                        Text(
                            text = strings.importer.nameConfirmationHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ImportOriginField(
                        value = category,
                        onValueChange = {
                            category = it
                            categoryEdited = true
                            categoryOrigin = ComponentImportFieldOrigin.User
                        },
                        label = strings.common.fieldCategory,
                        origin = categoryOrigin,
                        strings = strings,
                    )
                    ImportOriginField(
                        value = packageName,
                        onValueChange = {
                            packageName = it
                            packageEdited = true
                            packageOrigin = ComponentImportFieldOrigin.User
                        },
                        label = strings.common.fieldPackage,
                        origin = packageOrigin,
                        strings = strings,
                    )
                    ImportOriginField(
                        value = model,
                        onValueChange = {
                            model = it
                            modelEdited = true
                            modelOrigin = ComponentImportFieldOrigin.User
                        },
                        label = strings.importer.modelLabel,
                        origin = modelOrigin,
                        strings = strings,
                    )
                    ImportOriginField(
                        value = brand,
                        onValueChange = {
                            brand = it
                            brandEdited = true
                            brandOrigin = ComponentImportFieldOrigin.User
                        },
                        label = strings.importer.brandLabel,
                        origin = brandOrigin,
                        strings = strings,
                    )
                }
            }
            item {
                SectionPane(
                    title = strings.importer.importDetailTitle,
                    supporting = strings.importer.quantityHint,
                ) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.common.fieldQuantity) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Box {
                        OutlinedButton(
                            onClick = { locationMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val selected = availableLocations.firstOrNull { it.id == location }
                            Text(selected?.name ?: androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_select_location))
                        }
                        DropdownMenu(
                            expanded = locationMenuExpanded,
                            onDismissRequest = { locationMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 280.dp),
                        ) {
                            availableLocations.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                    onClick = { location = option.id; locationMenuExpanded = false },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            newLocationName = ""
                            locationCreationError = null
                            creatingLocation = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_create_location)) }
                    OutlinedTextField(
                        value = minStockText,
                        onValueChange = { minStockText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.common.fieldMinimumStock) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(
                        onClick = {
                            baseCandidate?.let { sourceCandidate ->
                                buildEditorDraftOrNull()?.let { draft ->
                                    onOpenFullEditor(draft, sourceCandidate)
                                }
                            }
                        },
                    ) {
                        Text(strings.importer.actionOpenFullEditor)
                    }
                }
            }
            item {
                SectionPane(title = strings.importer.importPreviewTitle) {
                    Text(
                        text = buildPreviewDraft()?.description.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!feedbackMessage.isNullOrBlank()) {
            item {
                Text(
                    text = feedbackMessage.orEmpty(),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    errorDialogMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { errorDialogMessage = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.import_error_title)) },
            text = { Text(error) },
            confirmButton = { Button(onClick = { errorDialogMessage = null }) {
                Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.action_close))
            } },
        )
    }
    if (creatingLocation) {
        AlertDialog(
            onDismissRequest = { if (!locationCreationBusy) creatingLocation = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_create_location)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newLocationName,
                        onValueChange = { newLocationName = it; locationCreationError = null },
                        label = { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.importer_location_name)) },
                        singleLine = true,
                    )
                    locationCreationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !locationCreationBusy && newLocationName.isNotBlank(),
                    onClick = {
                        val name = newLocationName.trim()
                        val duplicate = availableLocations.firstOrNull {
                            it.name.equals(name, ignoreCase = true)
                        }
                        if (duplicate != null) {
                            locationCreationError = context.getString(com.componentvault.android.R.string.importer_location_duplicate)
                        } else {
                            val id = "LOC-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
                            locationCreationBusy = true
                            onCreateStorageLocation(id, name) { result ->
                                locationCreationBusy = false
                                if (result.isSuccess) {
                                    location = id
                                    creatingLocation = false
                                } else locationCreationError = result.message
                            }
                        }
                    },
                ) { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.action_save)) }
            },
            dismissButton = {
                TextButton(enabled = !locationCreationBusy, onClick = { creatingLocation = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ImportOriginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    origin: ComponentImportFieldOrigin,
    strings: ComponentVaultStrings,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        supportingText = {
            Text(strings.importer.fieldOrigin(origin))
        },
    )
}

private fun String.blankToNull(): String? = takeIf { it.isNotBlank() }
