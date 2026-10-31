package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.componentvault.android.R
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.componentvault.android.data.ComponentImportParser
import com.componentvault.android.data.LabelPrintQueueCodec
import com.componentvault.android.data.LocationQrCodec
import com.componentvault.android.data.M1TestPaperProfile
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentLabelSeed
import com.componentvault.android.model.InventoryStockFilter
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.toLabelSeed
import org.json.JSONObject

private val LabelSeedSaver = Saver<ComponentLabelSeed?, String>(
    save = { seed -> seed?.let { LabelPrintQueueCodec.encodeSeed(it).toString() } ?: "" },
    restore = { json -> json.takeIf(String::isNotEmpty)?.let { LabelPrintQueueCodec.decodeSeed(JSONObject(it)) } },
)

@Composable
fun ComponentVaultApp(
    viewModel: InventoryViewModel,
) {
    val uiState = viewModel.uiState
    val layoutMode = rememberInventoryLayoutMode()
    val strings = runtimeComponentVaultStrings()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val shellStateHolder = rememberSaveableStateHolder()
    val componentSaveCoordinator = remember(viewModel) {
        ComponentSaveCoordinator(
            saveComponent = viewModel::saveComponent,
            saveImportedComponent = { draft, candidate, complete ->
                viewModel.saveImportedComponent(draft, candidate, complete)
            },
            findExistingImportTarget = viewModel::findExistingImportTarget,
            appendImportedStock = viewModel::appendImportedStock,
        )
    }
    var lastHandledStatusMessage by rememberSaveable { mutableStateOf(uiState.statusMessage) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage
        if (message.isNotBlank() && message != lastHandledStatusMessage) {
            lastHandledStatusMessage = message
            snackbarHostState.showSnackbar(message)
        }
    }

    var destination by rememberSaveable { mutableStateOf(InventoryDestination.Overview) }
    var compactDetailComponentId by rememberSaveable { mutableStateOf<String?>(null) }
    var compactMovementDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSettingsSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var componentEditorVisible by rememberSaveable { mutableStateOf(false) }
    var componentEditorTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var movementEditorVisible by rememberSaveable { mutableStateOf(false) }
    var movementBatchReviewVisible by rememberSaveable { mutableStateOf(false) }
    var movementScanModeVisible by rememberSaveable { mutableStateOf(false) }
    var lastObservedBatchScans by rememberSaveable {
        mutableIntStateOf(uiState.movements.batchSession.totalScans)
    }
    var movementEditorTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var movementEditorInitialType by rememberSaveable { mutableStateOf<String?>(null) }
    var movementEditorAllowManualSelection by rememberSaveable { mutableStateOf(true) }
    var importSurfaceVisible by rememberSaveable { mutableStateOf(false) }
    var bomImportVisible by rememberSaveable { mutableStateOf(false) }
    var bomImportInitialMode by rememberSaveable { mutableStateOf<BomImportMode?>(null) }
    var storageLocationsVisible by rememberSaveable { mutableStateOf(false) }
    var inventoryScannerVisible by rememberSaveable { mutableStateOf(false) }
    var inventoryScanMessage by remember { mutableStateOf<String?>(null) }
    var inventoryBackupVisible by rememberSaveable { mutableStateOf(false) }
    var batchJlcVisible by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAddEntrySheet by rememberSaveable { mutableStateOf(false) }
    var componentEditorInitialDraft by rememberSaveable(stateSaver = ComponentDraftSaver) { mutableStateOf<ComponentDraft?>(null) }
    var componentEditorImportCandidate by rememberSaveable(stateSaver = ComponentImportCandidateSaver) { mutableStateOf<ComponentImportCandidate?>(null) }
    var labelPrintSeed by rememberSaveable(stateSaver = LabelSeedSaver) { mutableStateOf<ComponentLabelSeed?>(null) }
    var labelPrintVisible by rememberSaveable { mutableStateOf(false) }
    var labelToolPickerVisible by rememberSaveable { mutableStateOf(false) }
    var labelToolTemplate by rememberSaveable { mutableStateOf(LabelToolTemplate.Component) }
    var labelToolPaper by remember { mutableStateOf<M1TestPaperProfile?>(null) }
    var labelPrintFromToolPicker by rememberSaveable { mutableStateOf(false) }
    var locationLabelTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(inventoryScanMessage) {
        inventoryScanMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            inventoryScanMessage = null
        }
    }

    LaunchedEffect(uiState.movements.batchSession.totalScans) {
        val totalScans = uiState.movements.batchSession.totalScans
        if (totalScans > lastObservedBatchScans &&
            uiState.movements.batchSession.queuedItems.isNotEmpty() &&
            !uiState.movements.scan.isScannerVisible
        ) {
            movementBatchReviewVisible = true
        }
        lastObservedBatchScans = totalScans
    }

    val editingComponent = uiState.availableComponents.firstOrNull { it.id == componentEditorTargetId }
    val compactDetailComponent = uiState.inventory.detail.component
        ?.takeIf { it.id == compactDetailComponentId }
        ?: uiState.availableComponents.firstOrNull { it.id == compactDetailComponentId }
    val compactMovementDetail = uiState.movements.items.firstOrNull { it.id == compactMovementDetailId }
    val compactDetailMovements = if (compactDetailComponent == null) {
        emptyList()
    } else {
        uiState.movements.items.filter { it.componentId == compactDetailComponent.id }.take(5)
    }
    val selectedSettingsSection = SettingsSection.entries.firstOrNull { it.name == selectedSettingsSectionName }

    LaunchedEffect(uiState.inventory.list.selectedComponentId) {
        if (compactDetailComponentId != null) {
            compactDetailComponentId = uiState.inventory.list.selectedComponentId
        }
    }

    fun resetSecondaryRoutes() {
        compactDetailComponentId = null
        compactMovementDetailId = null
        selectedSettingsSectionName = null
    }

    fun openSettingsRoute(section: SettingsSection? = null) {
        destination = InventoryDestination.Settings
        compactDetailComponentId = null
        compactMovementDetailId = null
        selectedSettingsSectionName = section?.name
    }

    fun closeComponentEditor() {
        componentEditorVisible = false
        componentEditorTargetId = null
        componentEditorInitialDraft = null
        componentEditorImportCandidate = null
    }

    fun closeMovementEditor() {
        movementEditorVisible = false
        movementEditorTargetId = null
        movementEditorInitialType = null
        movementEditorAllowManualSelection = true
    }

    fun closeImportSurface() {
        importSurfaceVisible = false
    }

    fun applyScannedLocation(raw: String) {
        inventoryScannerVisible = false
        closeImportSurface()
        val locationId = LocationQrCodec.decode(raw)
        val location = uiState.inventory.storageLocations.firstOrNull {
            it.id == locationId && !it.deleted
        }
        if (locationId == null) {
            inventoryScanMessage = context.getString(R.string.location_qr_invalid)
        } else if (location == null) {
            inventoryScanMessage = context.getString(R.string.location_qr_unknown)
        } else {
            destination = InventoryDestination.Inventory
            resetSecondaryRoutes()
            viewModel.focusScannedLocation(location.id)
        }
    }

    fun openComponentEditor(
        componentId: String?,
        initialDraft: ComponentDraft? = null,
        importCandidate: ComponentImportCandidate? = null,
    ) {
        componentEditorTargetId = componentId
        componentEditorInitialDraft = initialDraft
        componentEditorImportCandidate = importCandidate
        componentEditorVisible = true
    }

    fun openMovementEditor(
        componentId: String?,
        initialMovementType: String? = null,
        allowManualSelection: Boolean = true,
    ) {
        movementEditorTargetId = componentId
        movementEditorInitialType = initialMovementType
        movementEditorAllowManualSelection = allowManualSelection
        movementEditorVisible = true
    }

    fun openCompactMovementDetail(movementId: String) {
        viewModel.selectMovement(movementId)
        if (!layoutMode.showsListDetail) {
            compactMovementDetailId = movementId
        }
    }

    fun revealSavedComponent(result: OperationResult) {
        result.entityId?.let { componentId ->
            viewModel.selectComponent(componentId)
            if (!layoutMode.showsListDetail) {
                compactDetailComponentId = componentId
            }
        }
    }

    fun submitComponentEditorDraft(
        draft: ComponentDraft,
        generateLabelAfterSave: Boolean,
        onComplete: (OperationResult) -> Unit,
    ) {
        componentSaveCoordinator.submitEditorDraft(
            draft = draft,
            importedDraft = editingComponent == null && componentEditorInitialDraft != null,
            importCandidate = componentEditorImportCandidate,
            onComplete = onComplete,
            onSaved = { result ->
                closeComponentEditor()
                revealSavedComponent(result)
                if (generateLabelAfterSave) {
                    labelPrintSeed = draft.toLabelSeed()
                    labelPrintVisible = true
                }
            },
        )
    }

    fun submitImportedDraft(
        draft: ComponentDraft,
        sourceCandidate: ComponentImportCandidate,
        onComplete: (OperationResult) -> Unit,
    ) {
        componentSaveCoordinator.submitImportedDraft(draft, sourceCandidate, onComplete) { result ->
            closeImportSurface()
            revealSavedComponent(result)
            labelPrintSeed = draft.toLabelSeed()
            labelPrintVisible = true
        }
    }

    BackHandler(enabled = movementEditorVisible && !layoutMode.prefersDialogForms) {
        closeMovementEditor()
    }
    BackHandler(enabled = importSurfaceVisible && !layoutMode.prefersDialogForms) {
        closeImportSurface()
    }
    BackHandler(enabled = destination == InventoryDestination.Settings) {
        focusManager.clearFocus()
        keyboardController?.hide()
        if (selectedSettingsSection != null && !layoutMode.showsListDetail) {
            selectedSettingsSectionName = null
        } else {
            destination = InventoryDestination.Inventory
            resetSecondaryRoutes()
        }
    }
    BackHandler(enabled = destination == InventoryDestination.Inventory && compactDetailComponentId != null && !layoutMode.showsListDetail) {
        compactDetailComponentId = null
    }
    BackHandler(enabled = destination == InventoryDestination.Movements && compactMovementDetailId != null && !layoutMode.showsListDetail) {
        compactMovementDetailId = null
    }

    ProvideComponentVaultStrings(strings) {
        if (movementScanModeVisible) {
            MovementScanModeDialog(
                onDismiss = { movementScanModeVisible = false },
                onSelect = { action ->
                    movementScanModeVisible = false
                    viewModel.startMovementScan(action)
                },
            )
        }
        when {
            inventoryScannerVisible -> {
                JlcQrScannerSurface(
                    onDismiss = { inventoryScannerVisible = false },
                    onScanResult = { raw ->
                        inventoryScannerVisible = false
                        if (LocationQrCodec.isLocationCode(raw)) {
                            applyScannedLocation(raw)
                        } else {
                            val candidate = runCatching { ComponentImportParser.parseScannedQr(raw) }.getOrNull()
                            val component = candidate?.let { match -> uiState.availableComponents.firstOrNull {
                                it.sku.equals(match.sku, ignoreCase = true) && !it.deleted
                            } }
                            if (component == null) {
                                inventoryScanMessage = context.getString(
                                    if (candidate == null) R.string.location_qr_unrecognized
                                    else R.string.location_qr_component_unknown,
                                )
                            } else {
                                destination = InventoryDestination.Inventory
                                resetSecondaryRoutes()
                                viewModel.focusScannedComponent(component.sku)
                                viewModel.selectComponent(component.id)
                                if (!layoutMode.showsListDetail) compactDetailComponentId = component.id
                            }
                        }
                    },
                    title = context.getString(R.string.location_qr_scan_title),
                    scanningHint = context.getString(R.string.location_qr_scan_hint),
                    scannerMode = JlcQrScannerMode.InventoryLookup,
                )
            }

            uiState.movements.scan.isScannerVisible -> {
                JlcQrScannerSurface(
                    onDismiss = {
                        viewModel.dismissMovementScanner()
                        movementBatchReviewVisible = uiState.movements.batchSession.queuedItems.isNotEmpty()
                    },
                    onScanResult = viewModel::resolveMovementComponentFromLabel,
                    scanSessionToken = uiState.movements.scan.scanSessionToken,
                    title = strings.movements.quickScanAction,
                    permissionTitle = strings.movements.scannerPermissionTitle,
                    permissionDescription = strings.movements.scannerPermissionDescription,
                    permissionDeniedTitle = strings.movements.scannerPermissionDeniedTitle,
                    permissionDeniedDescription = strings.movements.scannerPermissionDeniedDescription,
                    startingMessage = strings.movements.scannerStarting,
                    scanningHint = if (uiState.movements.batchSession.queuedItems.isNotEmpty()) {
                        strings.movements.batchScannerHint(
                            uiState.movements.batchSession.queuedItems.size,
                            uiState.movements.batchSession.totalScans,
                        )
                    } else {
                        strings.movements.scannerScanningHint
                    },
                    failedTitle = strings.movements.scannerFailedTitle,
                    failedDescription = strings.movements.scannerFailedDescription,
                    returnActionLabel = if (uiState.movements.batchSession.queuedItems.isNotEmpty()) {
                        strings.movements.batchReviewAction
                    } else {
                        strings.common.actionBack
                    },
                    scannerMode = JlcQrScannerMode.MovementSmallLabel,
                )
            }

            movementBatchReviewVisible && uiState.movements.batchSession.queuedItems.isNotEmpty() -> {
                MovementBatchReviewRoute(
                    uiState = uiState.movements,
                    onDismiss = { movementBatchReviewVisible = false },
                    onScanMovementLabel = {
                        movementBatchReviewVisible = false
                        viewModel.openMovementScanner()
                    },
                    onCommitMovementBatch = {
                        viewModel.commitMovementBatch { result ->
                            if (result.isSuccess) movementBatchReviewVisible = false
                        }
                    },
                    onDiscardMovementBatch = {
                        viewModel.discardMovementBatchSession()
                        movementBatchReviewVisible = false
                    },
                    onUpdateMovementBatchItemMovementType = viewModel::updateMovementBatchItemMovementType,
                    onUpdateMovementBatchItemQuantity = viewModel::updateMovementBatchItemQuantity,
                    onUpdateMovementBatchItemReason = viewModel::updateMovementBatchItemReason,
                    onUpdateMovementBatchItemNote = viewModel::updateMovementBatchItemNote,
                    onUpdateMovementBatchItemLocation = viewModel::updateMovementBatchItemLocation,
                    onRemoveMovementBatchItem = viewModel::removeMovementBatchItem,
                )
            }

            componentEditorVisible && !layoutMode.prefersDialogForms -> Unit

            movementEditorVisible && !layoutMode.prefersDialogForms -> {
                MovementEditorSurface(
                    components = uiState.availableComponents,
                    selectedComponentId = movementEditorTargetId ?: uiState.inventory.list.selectedComponentId,
                    layoutMode = layoutMode,
                    initialMovementType = movementEditorInitialType,
                    allowManualComponentSelection = movementEditorAllowManualSelection,
                    onDismiss = ::closeMovementEditor,
                    onSave = { draft, onComplete ->
                        viewModel.recordMovement(draft) { result ->
                            onComplete(result)
                            if (result.isSuccess) {
                                closeMovementEditor()
                            }
                        }
                    },
                    storageLocations = uiState.inventory.storageLocations,
                )
            }

            importSurfaceVisible && !layoutMode.prefersDialogForms -> {
                JlcImportSurface(
                    layoutMode = layoutMode,
                    syncConfiguration = uiState.syncConfiguration,
                    appPreferences = uiState.appPreferences,
                    storageLocations = uiState.inventory.storageLocations,
                    onCreateStorageLocation = { id, name, complete ->
                        viewModel.saveStorageLocation(id, name, com.componentvault.android.model.StorageLocationSaveIntent.Create, complete)
                    },
                    onDismiss = ::closeImportSurface,
                    onLocationCodeScan = ::applyScannedLocation,
                    onSaveImportedComponent = { draft, sourceCandidate, onComplete ->
                        submitImportedDraft(
                            draft = draft,
                            sourceCandidate = sourceCandidate,
                            onComplete = onComplete,
                        )
                    },
                    onOpenFullEditor = { draft, sourceCandidate ->
                        closeImportSurface()
                        openComponentEditor(
                            componentId = null,
                            initialDraft = draft,
                            importCandidate = sourceCandidate,
                        )
                    },
                )
            }

            bomImportVisible -> {
                BomImportScreen(
                    viewModel = viewModel,
                    onDismiss = { bomImportVisible = false },
                    initialMode = bomImportInitialMode,
                )
            }

            storageLocationsVisible -> {
                StorageLocationsScreen(
                    locations = uiState.inventory.storageLocations,
                    onDismiss = { storageLocationsVisible = false },
                    onSave = viewModel::saveStorageLocation,
                    onDelete = viewModel::deleteStorageLocation,
                    onPrintLabel = { location ->
                        locationLabelTargetId = location.id
                        storageLocationsVisible = false
                        labelPrintSeed = null
                        labelToolTemplate = LabelToolTemplate.Qr
                        labelPrintFromToolPicker = false
                        labelPrintVisible = true
                    },
                )
            }

            inventoryBackupVisible -> InventoryBackupScreen(
                viewModel = viewModel,
                components = uiState.availableComponents,
                onDismiss = { inventoryBackupVisible = false; viewModel.clearInventoryBackupState() },
            )

            batchJlcVisible -> BatchJlcInboundScreen(
                appPreferences=uiState.appPreferences,
                syncConfiguration=uiState.syncConfiguration,
                defaultLocation=uiState.appPreferences.suggestedImportLocation,
                onDismiss={batchJlcVisible=false},
                onCommitted=viewModel::onBatchJlcCommitted,
            )

            labelToolPickerVisible -> LabelToolTemplatePicker(
                onSelect = { template, paper ->
                    locationLabelTargetId = null
                    labelToolTemplate = template
                    labelToolPaper = paper
                    labelPrintFromToolPicker = true
                    labelToolPickerVisible = false
                    labelPrintVisible = true
                },
                onBack = { labelToolPickerVisible = false; labelPrintFromToolPicker = false },
            )

            labelPrintVisible -> BluetoothLabelPrintScreen(
                components = uiState.availableComponents,
                initialSeed = labelPrintSeed,
                initialToolTemplate = if (labelPrintSeed != null) LabelToolTemplate.Component else labelToolTemplate,
                initialPaper = if (labelPrintFromToolPicker) labelToolPaper else null,
                initialFreeTitle = locationLabelTargetId?.let { id ->
                    uiState.inventory.storageLocations.firstOrNull { it.id == id }?.let { location ->
                        context.getString(R.string.location_qr_label_description, location.name, location.id)
                    }
                },
                initialFreeQrPayload = locationLabelTargetId?.let(LocationQrCodec::encode),
                onDismiss = {
                    labelPrintVisible = false
                    labelPrintSeed = null
                    if (locationLabelTargetId != null) storageLocationsVisible = true
                    locationLabelTargetId = null
                    if (labelPrintFromToolPicker) labelToolPickerVisible = true
                },
            )

            destination == InventoryDestination.Inventory && compactDetailComponentId != null && !layoutMode.showsListDetail -> {
                InventoryDetailRoute(
                    component = compactDetailComponent,
                    recentMovements = compactDetailMovements,
                    issuedQuantity = uiState.inventory.detail.issuedQuantity,
                    allocations = uiState.inventory.detail.allocations,
                    storageLocations = uiState.inventory.storageLocations,
                    onDismiss = { compactDetailComponentId = null },
                    onEditComponent = { componentId -> openComponentEditor(componentId) },
                    onGenerateLabel = { component ->
                        labelPrintSeed = component.toLabelSeed()
                        labelPrintVisible = true
                    },
                    onRequestDeleteComponent = { componentId ->
                        viewModel.selectComponent(componentId)
                        showDeleteConfirmation = true
                    },
                    onRecordMovement = { componentId -> openMovementEditor(componentId) },
                    onAddComponent = { showAddEntrySheet = true },
                )
            }

            destination == InventoryDestination.Movements && compactMovementDetailId != null && !layoutMode.showsListDetail -> {
                MovementDetailRoute(
                    movement = compactMovementDetail,
                    onDismiss = { compactMovementDetailId = null },
                )
            }

            else -> {
                shellStateHolder.SaveableStateProvider("main_shell") {
                    ComponentVaultAppShell(
                        uiState = uiState,
                        destination = destination,
                        layoutMode = layoutMode,
                        onDestinationChange = { nextDestination ->
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            destination = nextDestination
                            resetSecondaryRoutes()
                        },
                        onQueryChange = viewModel::updateComponentQuery,
                        onStockFilterChange = { stockFilter ->
                            viewModel.setLowStockOnly(stockFilter == InventoryStockFilter.LowStock)
                        },
                        onCategoryChange = viewModel::updateCategoryFilter,
                        onLocationChange = viewModel::updateLocationFilter,
                        onScanInventory = { inventoryScannerVisible = true },
                        onSortChange = viewModel::updateSort,
                        onSelectComponent = viewModel::selectComponent,
                        onOpenComponentDetail = { componentId ->
                            viewModel.selectComponent(componentId)
                            if (!layoutMode.showsListDetail) {
                                compactDetailComponentId = componentId
                            }
                        },
                        onAddComponent = { showAddEntrySheet = true },
                        onImportComponent = { importSurfaceVisible = true },
                        onGenerateLabel = { componentId ->
                            uiState.availableComponents.firstOrNull { it.id == componentId }?.let { component ->
                                labelPrintSeed = component.toLabelSeed()
                                labelPrintVisible = true
                            }
                        },
                        onOpenBluetoothPrint = {
                            locationLabelTargetId = null
                            labelPrintSeed = null
                            labelPrintFromToolPicker = false
                            labelToolPickerVisible = true
                        },
                        onEditComponent = { componentId ->
                            viewModel.selectComponent(componentId)
                            openComponentEditor(componentId)
                        },
                        onRequestDeleteComponent = { componentId ->
                            viewModel.selectComponent(componentId)
                            showDeleteConfirmation = true
                        },
                        onBatchTransfer = viewModel::transferComponentsBatch,
                        onQuickMovement = viewModel::recordMovement,
                        onScanMovementLabel = {
                            destination = InventoryDestination.Movements
                            movementBatchReviewVisible = false
                            if (uiState.movements.batchSession.isActive) viewModel.openMovementScanner()
                            else movementScanModeVisible = true
                        },
                        onRetryMovementScan = viewModel::openMovementScanner,
                        onDismissMovementScanResult = viewModel::clearMovementScanState,
                        onDiscardMovementBatch = {
                            viewModel.discardMovementBatchSession()
                            movementBatchReviewVisible = false
                        },
                        onOpenMovementBatchReview = { movementBatchReviewVisible = true },
                        onUpdateMovementBatchItemMovementType = viewModel::updateMovementBatchItemMovementType,
                        onUpdateMovementBatchItemQuantity = viewModel::updateMovementBatchItemQuantity,
                        onUpdateMovementBatchItemReason = viewModel::updateMovementBatchItemReason,
                        onUpdateMovementBatchItemNote = viewModel::updateMovementBatchItemNote,
                        onRemoveMovementBatchItem = viewModel::removeMovementBatchItem,
                        onSearchInventoryBySku = { sku ->
                            destination = InventoryDestination.Inventory
                            compactDetailComponentId = null
                            compactMovementDetailId = null
                            selectedSettingsSectionName = null
                            viewModel.updateComponentQuery(sku)
                        },
                        onRecordMovement = { componentId ->
                            componentId?.let(viewModel::selectComponent)
                            openMovementEditor(componentId)
                        },
                        onOpenLowStockInventory = {
                            destination = InventoryDestination.Inventory
                            compactDetailComponentId = null
                            compactMovementDetailId = null
                            selectedSettingsSectionName = null
                            viewModel.focusLowStockInventory()
                        },
                        onSelectOverviewComponent = { componentId ->
                            destination = InventoryDestination.Inventory
                            compactMovementDetailId = null
                            selectedSettingsSectionName = null
                            viewModel.focusLowStockInventory(componentId)
                            if (!layoutMode.showsListDetail) {
                                compactDetailComponentId = componentId
                            }
                        },
                        onOpenMovements = { movementId ->
                            destination = InventoryDestination.Movements
                            compactDetailComponentId = null
                            selectedSettingsSectionName = null
                            viewModel.selectMovement(movementId)
                            compactMovementDetailId = movementId?.takeIf { !layoutMode.showsListDetail }
                        },
                        onSelectMovement = { movementId ->
                            viewModel.selectMovement(movementId)
                        },
                        onOpenMovementDetail = ::openCompactMovementDetail,
                        onOpenSettingsHome = { openSettingsRoute() },
                        onOpenSyncSettings = { openSettingsRoute(SettingsSection.Sync) },
                        selectedSettingsSection = selectedSettingsSection,
                        onBackFromSettingsSection = { selectedSettingsSectionName = null },
                        settingsContent = { padding ->
                            SettingsContent(
                                contentPadding = padding,
                                syncConfiguration = uiState.syncConfiguration,
                                appPreferences = uiState.appPreferences,
                                storageLocations = uiState.inventory.storageLocations,
                                importLearningSummary = uiState.importLearningSummary,
                                isBusy = uiState.isBusy,
                                statusMessage = "",
                                layoutMode = layoutMode,
                                selectedSection = selectedSettingsSection,
                                onSelectSection = { section -> selectedSettingsSectionName = section?.name },
                                onSaveSyncSettings = viewModel::saveSyncConfiguration,
                                onSaveAppPreferences = viewModel::saveAppPreferences,
                                onTestConnection = viewModel::testConnection,
                                onSyncNow = viewModel::runSync,
                                onClearImportLearningMappings = viewModel::clearImportLearningMappings,
                                onManageLocations = { storageLocationsVisible = true },
                                onBackupRestore = { inventoryBackupVisible = true },
                            )
                        },
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }

        ComponentEditorRoute(
            visible = componentEditorVisible,
            scannerVisible = uiState.movements.scan.isScannerVisible,
            existing = editingComponent,
            initialDraft = componentEditorInitialDraft,
            availableCategories = uiState.inventory.availableCategories,
            storageLocations = uiState.inventory.storageLocations,
            layoutMode = layoutMode,
            onDismiss = ::closeComponentEditor,
            onSave = { draft, onComplete ->
                submitComponentEditorDraft(draft, generateLabelAfterSave = false, onComplete)
            },
            onSaveAndGenerateLabel = { draft, onComplete ->
                submitComponentEditorDraft(draft, generateLabelAfterSave = true, onComplete)
            },
        )

        if (!uiState.movements.scan.isScannerVisible && layoutMode.prefersDialogForms) {

            if (movementEditorVisible) {
                MovementEditorSurface(
                    components = uiState.availableComponents,
                    selectedComponentId = movementEditorTargetId ?: uiState.inventory.list.selectedComponentId,
                    layoutMode = layoutMode,
                    initialMovementType = movementEditorInitialType,
                    allowManualComponentSelection = movementEditorAllowManualSelection,
                    onDismiss = ::closeMovementEditor,
                    onSave = { draft, onComplete ->
                        viewModel.recordMovement(draft) { result ->
                            onComplete(result)
                            if (result.isSuccess) {
                                closeMovementEditor()
                            }
                        }
                    },
                    storageLocations = uiState.inventory.storageLocations,
                )
            }

            if (importSurfaceVisible) {
                JlcImportSurface(
                    layoutMode = layoutMode,
                    syncConfiguration = uiState.syncConfiguration,
                    appPreferences = uiState.appPreferences,
                    storageLocations = uiState.inventory.storageLocations,
                    onCreateStorageLocation = { id, name, complete ->
                        viewModel.saveStorageLocation(id, name, com.componentvault.android.model.StorageLocationSaveIntent.Create, complete)
                    },
                    onDismiss = ::closeImportSurface,
                    onLocationCodeScan = ::applyScannedLocation,
                    onSaveImportedComponent = { draft, sourceCandidate, onComplete ->
                        submitImportedDraft(
                            draft = draft,
                            sourceCandidate = sourceCandidate,
                            onComplete = onComplete,
                        )
                    },
                    onOpenFullEditor = { draft, sourceCandidate ->
                        closeImportSurface()
                        openComponentEditor(
                            componentId = null,
                            initialDraft = draft,
                            importCandidate = sourceCandidate,
                        )
                    },
                )
            }

        }

        if (showAddEntrySheet) {
            AddComponentEntrySheet(
                onDismiss = { showAddEntrySheet = false },
                onImportComponent = {
                    showAddEntrySheet = false
                    importSurfaceVisible = true
                },
                onAddComponent = {
                    showAddEntrySheet = false
                    openComponentEditor(null)
                },
                onImportBom = {
                    showAddEntrySheet = false
                    bomImportInitialMode = BomImportMode.Bom
                    bomImportVisible = true
                },
                onImportMigration = {
                    showAddEntrySheet = false
                    bomImportInitialMode = BomImportMode.Migration
                    bomImportVisible = true
                },
                onBatchJlc = {
                    showAddEntrySheet = false
                    batchJlcVisible = true
                },
            )
        }

        if (showDeleteConfirmation) {
            DeleteComponentConfirmationDialog(
                onDismiss = { showDeleteConfirmation = false },
                onConfirm = {
                    viewModel.deleteSelectedComponent()
                    showDeleteConfirmation = false
                    compactDetailComponentId = null
                },
            )
        }
        SingleAppendConfirmationDialog(
            coordinator = componentSaveCoordinator,
            storageLocations = uiState.inventory.storageLocations,
            onSaved = { result ->
                closeImportSurface()
                revealSavedComponent(result)
            },
        )
    }
}

// Keep one composition slot for the editor when its adaptive presentation changes.
@Composable
internal fun ComponentEditorRoute(
    visible: Boolean,
    scannerVisible: Boolean,
    existing: ComponentRecord?,
    initialDraft: ComponentDraft?,
    availableCategories: List<String>,
    storageLocations: List<StorageLocationRecord>,
    layoutMode: InventoryLayoutMode,
    onDismiss: () -> Unit,
    onSave: (ComponentDraft, (OperationResult) -> Unit) -> Unit,
    onSaveAndGenerateLabel: (ComponentDraft, (OperationResult) -> Unit) -> Unit,
) {
    if (visible && !scannerVisible) {
        ComponentEditorSurface(
            existing = existing,
            initialDraft = initialDraft,
            availableCategories = availableCategories,
            storageLocations = storageLocations,
            layoutMode = layoutMode,
            onDismiss = onDismiss,
            onSave = onSave,
            onSaveAndGenerateLabel = onSaveAndGenerateLabel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddComponentEntrySheet(
    onDismiss: () -> Unit,
    onImportComponent: () -> Unit,
    onAddComponent: () -> Unit,
    onImportBom: () -> Unit,
    onImportMigration: () -> Unit,
    onBatchJlc: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.import_menu_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ImportMenuRow(R.string.import_menu_single, R.string.import_menu_single_hint, onImportComponent)
            ImportMenuRow(R.string.import_menu_batch, R.string.import_menu_batch_hint, onBatchJlc)
            ImportMenuRow(R.string.import_menu_bom, R.string.import_menu_bom_hint, onImportBom)
            ImportMenuRow(R.string.import_menu_migration, R.string.import_menu_migration_hint, onImportMigration)
            ImportMenuRow(R.string.import_menu_manual, R.string.import_menu_manual_hint, onAddComponent)
        }
    }
}

@Composable
private fun ImportMenuRow(title: Int, description: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
