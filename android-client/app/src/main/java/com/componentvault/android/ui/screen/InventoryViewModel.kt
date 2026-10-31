package com.componentvault.android.ui.screen

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.componentvault.android.R
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.BatchTransferLine
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomReleasePreview
import com.componentvault.android.data.bom.BomReleaseResult
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.InventoryFiltersUiState
import com.componentvault.android.model.InventoryScreenUiState
import com.componentvault.android.model.InventorySortOption
import com.componentvault.android.model.InventoryStockFilter
import com.componentvault.android.model.InventoryUiState
import com.componentvault.android.model.MovementBatchQueueItemUiState
import com.componentvault.android.model.MovementBatchSessionUiState
import com.componentvault.android.model.MovementBatchStage
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.MovementQuickAction
import com.componentvault.android.model.MovementScanResolutionUiState
import com.componentvault.android.model.MovementScanUiState
import com.componentvault.android.model.MovementsUiState
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StockMovementRecord
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.StorageLocationSaveIntent
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.SyncConfiguration
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

internal data class InventoryBackupUiState(
    val loading:Boolean=false,
    val preview:InventoryRepository.WorkbookPreview?=null,
    val message:String="",
)

class InventoryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
    private val repository = InventoryRepository(application)
    private val backupOperations = InventoryBackupOperations(application, repository)
    private val bomImportOperations = BomImportOperations(repository)
    var startupFailure by mutableStateOf<String?>(null)
        private set
    private var lastInventoryDiagnostic: List<Pair<String, Any?>>? = null
    private var initialLoadComplete = false
    private var movementBatchCommitInProgress = false
    private var allComponentsCache: List<ComponentRecord> = emptyList()
    private var allMovementsCache: List<StockMovementRecord> = emptyList()
    private var issuedQuantitiesCache: Map<String, Long> = emptyMap()
    private var storageLocationsCache: List<StorageLocationRecord> = emptyList()
    private var allocationsCache: List<ComponentAllocationRecord> = emptyList()
    private fun inventoryProjector() = InventoryStateProjector(
        allComponentsCache,
        allMovementsCache,
        issuedQuantitiesCache,
        storageLocationsCache,
        allocationsCache,
    )
    private fun movementBatchCoordinator() = MovementBatchCoordinator(
        getApplication(), allComponentsCache, storageLocationsCache, allocationsCache,
    )
    private val defaultSyncMessage = application.getString(R.string.sync_no_sync_yet)
    private val defaultLastSyncedAt = application.getString(R.string.sync_never)
    internal var backupUiState by mutableStateOf(InventoryBackupUiState())
        private set

    var uiState by mutableStateOf(
        InventoryUiState(
            syncConfiguration = SyncConfiguration(
                deviceId = "",
                serverBaseUrl = "",
                apiToken = "",
                autoSyncEnabled = false,
                lastSyncedAt = defaultLastSyncedAt,
                lastSyncMessage = defaultSyncMessage,
            ),
            statusMessage = defaultSyncMessage,
        ),
    )
        private set

    fun refreshSyncConfiguration() {
        uiState = uiState.copy(syncConfiguration = repository.loadSyncConfiguration())
    }

    private val stopObservingSync = repository.observeSyncStatus(::refreshSyncConfiguration)
    private var activeSyncJob: kotlinx.coroutines.Job? = null

    init {
        savedStateHandle.get<String>(MOVEMENT_BATCH_DRAFT_KEY)?.let { saved ->
            runCatching { MovementBatchDraftCodec.decode(saved) }.onSuccess { session ->
                uiState = uiState.copy(movements = uiState.movements.copy(batchSession = session))
            }.onFailure { savedStateHandle.remove<String>(MOVEMENT_BATCH_DRAFT_KEY) }
        }
        refresh()
    }

    private fun saveMovementBatchDraft() {
        val session = uiState.movements.batchSession
        if (session.queuedItems.isEmpty()) savedStateHandle.remove<String>(MOVEMENT_BATCH_DRAFT_KEY)
        else savedStateHandle[MOVEMENT_BATCH_DRAFT_KEY] = MovementBatchDraftCodec.encode(session)
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                reloadState(uiState.statusMessage.takeIf { it.isNotBlank() })
                startupFailure = null
                if (!initialLoadComplete) {
                    initialLoadComplete = true
                    maybeAutoSyncOnLaunch()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                startupFailure = startupFailureDetails(error)
            }
        }
    }

    fun onBatchJlcCommitted(){viewModelScope.launch{reloadState("批量入库已完成。");if(uiState.appPreferences.syncAfterLocalChanges)runSyncInternal()}}

    fun updateComponentQuery(query: String) {
        updateInventoryFilters(uiState.inventory.filters.copy(query = query))
    }

    fun setLowStockOnly(enabled: Boolean) {
        updateInventoryFilters(
            uiState.inventory.filters.copy(
                stockFilter = if (enabled) {
                    InventoryStockFilter.LowStock
                } else {
                    InventoryStockFilter.All
                },
            ),
        )
    }

    fun updateCategoryFilter(category: String?) {
        updateInventoryFilters(uiState.inventory.filters.copy(category = category))
    }

    fun updateLocationFilter(location: String?) {
        updateInventoryFilters(uiState.inventory.filters.copy(location = location))
    }

    fun focusScannedLocation(locationId: String) {
        updateInventoryFilters(InventoryFiltersUiState(location = locationId))
    }

    fun focusScannedComponent(sku: String) {
        updateInventoryFilters(InventoryFiltersUiState(query = sku))
    }

    fun updateSort(sort: InventorySortOption) {
        updateInventoryFilters(uiState.inventory.filters.copy(sort = sort))
    }

    fun focusLowStockInventory(componentId: String? = null) {
        updateInventoryFilters(
            uiState.inventory.filters.copy(stockFilter = InventoryStockFilter.LowStock),
        )
        if (componentId != null) {
            selectComponent(componentId)
        }
    }

    fun selectComponent(componentId: String?) {
        val selectedComponent = allComponentsCache.firstOrNull { it.id == componentId }
        val selectedId = selectedComponent?.id
        uiState = uiState.copy(
            inventory = uiState.inventory.copy(
                list = uiState.inventory.list.copy(selectedComponentId = selectedId),
                detail = inventoryProjector().detail(selectedComponent),
            ),
        )
    }

    fun saveComponent(
        draft: ComponentDraft,
        onComplete: (OperationResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.saveComponent(draft)
            reloadState(
                statusMessage = result.message,
                preferredSelectedComponentId = result.entityId,
            )
            if (result.isSuccess) {
                result.entityId?.let(::revealSavedComponent)
            }
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) {
                runSyncInternal()
            }
        }
    }

    fun exportInventoryBackup(uri: Uri) {
        viewModelScope.launch {
            backupUiState = InventoryBackupUiState(loading = true)
            try {
                backupOperations.export(uri)
                backupUiState = InventoryBackupUiState(message = "备份已导出。")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                backupUiState = InventoryBackupUiState()
                throw cancelled
            } catch (error: Exception) {
                backupUiState = InventoryBackupUiState(message = error.message ?: "备份导出失败。")
            }
        }
    }

    fun previewInventoryBackup(uri: Uri) {
        viewModelScope.launch {
            backupUiState = InventoryBackupUiState(loading = true)
            try {
                backupUiState = InventoryBackupUiState(preview = backupOperations.preview(uri))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                backupUiState = InventoryBackupUiState()
                throw cancelled
            } catch (error: Exception) {
                backupUiState = InventoryBackupUiState(message = error.message ?: "备份预览失败。")
            }
        }
    }

    fun confirmInventoryRestore() {
        val preview = backupUiState.preview ?: return
        viewModelScope.launch {
            backupUiState = backupUiState.copy(loading = true)
            try {
                val result = backupOperations.restore(preview)
                backupUiState = InventoryBackupUiState(message = result.message)
                if (result.isSuccess) refresh()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                backupUiState = InventoryBackupUiState()
                throw cancelled
            }
        }
    }

    fun clearInventoryBackupState() { backupUiState = InventoryBackupUiState() }

    fun saveImportedComponent(
        draft: ComponentDraft,
        onComplete: (OperationResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.saveImportedComponent(
                draft = draft,
                sourceCandidate = null,
                appPreferences = uiState.appPreferences,
            )
            if (result.isSuccess && uiState.appPreferences.rememberLastImportLocation) {
                repository.rememberLastImportLocation(draft.location)
            }
            reloadState(
                statusMessage = result.message,
                preferredSelectedComponentId = result.entityId,
            )
            if (result.isSuccess) {
                result.entityId?.let(::revealSavedComponent)
            }
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) {
                runSyncInternal()
            }
        }
    }

    internal fun findExistingImportTarget(
        sku: String,
        onError: (OperationResult) -> Unit,
        onComplete: (com.componentvault.android.data.ExistingImportTarget?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { repository.findExistingImportTarget(sku) }
                .onSuccess(onComplete)
                .onFailure { onError(OperationResult(false, it.message ?: "无法检查已有料号。")) }
        }
    }

    internal fun appendImportedStock(
        target: com.componentvault.android.data.ExistingImportTarget,
        quantity: Int,
        locationId: String,
        onComplete: (OperationResult) -> Unit,
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.appendImportedStock(target, quantity, locationId)
            reloadState(statusMessage = result.message, preferredSelectedComponentId = result.entityId)
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) runSyncInternal()
        }
    }

    fun saveImportedComponent(
        draft: ComponentDraft,
        sourceCandidate: ComponentImportCandidate?,
        onComplete: (OperationResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.saveImportedComponent(
                draft = draft,
                sourceCandidate = sourceCandidate,
                appPreferences = uiState.appPreferences,
            )
            if (result.isSuccess && uiState.appPreferences.rememberLastImportLocation) {
                repository.rememberLastImportLocation(draft.location)
            }
            reloadState(
                statusMessage = result.message,
                preferredSelectedComponentId = result.entityId,
            )
            if (result.isSuccess) {
                result.entityId?.let(::revealSavedComponent)
            }
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) {
                runSyncInternal()
            }
        }
    }

    fun deleteSelectedComponent() {
        val componentId = uiState.inventory.list.selectedComponentId
        if (componentId == null) {
            uiState = uiState.copy(statusMessage = getApplication<Application>().getString(R.string.sync_select_component_first))
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.softDeleteComponent(componentId)
            reloadState(result.message)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) {
                runSyncInternal()
            }
        }
    }

    internal fun transferComponentsBatch(
        sourceLocationId: String,
        destinationLocationId: String,
        lines: List<BatchTransferLine>,
        onComplete: (OperationResult) -> Unit,
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.transferComponentsBatch(sourceLocationId, destinationLocationId, lines)
            reloadState(result.message)
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) runSyncInternal()
        }
    }

    fun recordMovement(
        draft: MovementEntryDraft,
        onComplete: (OperationResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.recordMovement(draft)
            reloadState(
                statusMessage = result.message,
                preferredSelectedComponentId = if (result.isSuccess) {
                    draft.componentId
                } else {
                    null
                },
            )
            if (result.isSuccess) {
                selectComponent(draft.componentId)
            }
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) {
                runSyncInternal()
            }
        }
    }

    fun saveStorageLocation(
        id: String,
        name: String,
        intent: StorageLocationSaveIntent,
        onComplete: (OperationResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = repository.saveStorageLocation(id, name, intent)
            reloadState(result.message)
            onComplete(result)
        }
    }

    fun deleteStorageLocation(id: String, onComplete: (OperationResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.deleteStorageLocation(id)
            reloadState(result.message)
            onComplete(result)
        }
    }

    fun selectMovement(movementId: String?) {
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = uiState.movements.scan,
                batchSession = uiState.movements.batchSession,
                preferredSelectedMovementId = movementId,
            ),
        )
    }

    fun startMovementScan(action: MovementQuickAction) {
        if (!uiState.movements.batchSession.isActive) {
            uiState = uiState.copy(movements = uiState.movements.copy(
                batchSession = MovementBatchSessionUiState(defaultMovementType = action.movementType),
            ))
        }
        openMovementScanner()
    }

    fun openMovementScanner() {
        val nextBatchSession = uiState.movements.batchSession
            .takeIf { it.isActive }
            ?.copy(stage = MovementBatchStage.Scanning)
            ?: uiState.movements.batchSession.copy(stage = MovementBatchStage.Scanning)
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = MovementScanUiState(
                    isScannerVisible = true,
                    scanSessionToken = uiState.movements.scan.scanSessionToken + 1,
                ),
                batchSession = nextBatchSession,
            ),
        )
    }

    fun dismissMovementScanner() {
        val currentScanState = uiState.movements.scan
        if (!currentScanState.isScannerVisible) {
            return
        }
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = currentScanState.copy(
                    isScannerVisible = false,
                    isResolving = false,
                ),
                batchSession = uiState.movements.batchSession.copy(
                    stage = MovementBatchStage.Review,
                ),
            ),
        )
    }

    fun clearMovementScanState() {
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = uiState.movements.scan.copy(
                    isResolving = false,
                    resolution = MovementScanResolutionUiState(),
                ),
                batchSession = uiState.movements.batchSession,
            ),
        )
    }

    fun discardMovementBatchSession() {
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = MovementScanUiState(
                    scanSessionToken = uiState.movements.scan.scanSessionToken,
                ),
                batchSession = MovementBatchSessionUiState(),
            ),
        )
        saveMovementBatchDraft()
    }

    fun updateMovementBatchItemMovementType(
        componentId: String,
        movementType: String,
    ) {
        updateMovementBatchItem(componentId) { item ->
            item.copy(
                movementType = movementType,
                errorMessage = null,
            )
        }
    }

    fun updateMovementBatchItemQuantity(
        componentId: String,
        quantityText: String,
    ) {
        updateMovementBatchItem(componentId) { item ->
            item.copy(
                quantityText = quantityText,
                errorMessage = null,
                isDirty = true,
            )
        }
    }

    fun updateMovementBatchItemReason(
        componentId: String,
        reason: String,
    ) {
        updateMovementBatchItem(componentId) { item ->
            item.copy(
                reason = reason,
                errorMessage = null,
            )
        }
    }

    fun updateMovementBatchItemLocation(componentId: String, location: String) {
        updateMovementBatchItem(componentId) { item ->
            if (location in item.locationQuantities) item.copy(location = location, errorMessage = null)
            else item
        }
    }

    fun updateMovementBatchItemNote(
        componentId: String,
        note: String,
    ) {
        updateMovementBatchItem(componentId) { item ->
            item.copy(
                note = note,
                errorMessage = null,
            )
        }
    }

    fun removeMovementBatchItem(componentId: String) {
        val remainingItems = uiState.movements.batchSession.queuedItems.filterNot { it.componentId == componentId }
        if (remainingItems.isEmpty()) {
            discardMovementBatchSession()
            return
        }
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = uiState.movements.scan,
                batchSession = uiState.movements.batchSession.copy(
                    queuedItems = remainingItems,
                    stage = MovementBatchStage.Review,
                ),
            ),
        )
        saveMovementBatchDraft()
    }

    fun commitMovementBatch(
        onComplete: (OperationResult) -> Unit = {},
    ) {
        if (movementBatchCommitInProgress) return
        val validation = movementBatchCoordinator().validate(uiState.movements.batchSession)
        if (!validation.isValid) {
            uiState = uiState.copy(
                movements = buildMovementsUiState(
                    scanState = uiState.movements.scan.copy(
                        isScannerVisible = false,
                        isResolving = false,
                    ),
                    batchSession = validation.session,
                ),
                statusMessage = validation.message,
            )
            saveMovementBatchDraft()
            onComplete(OperationResult(isSuccess = false, message = validation.message))
            return
        }

        val drafts = validation.drafts
        movementBatchCommitInProgress = true
        // Do not restore a review during an in-flight write: its outcome may be unknown.
        savedStateHandle.remove<String>(MOVEMENT_BATCH_DRAFT_KEY)
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                movements = buildMovementsUiState(
                    scanState = uiState.movements.scan.copy(
                        isScannerVisible = false,
                        isResolving = false,
                        resolution = MovementScanResolutionUiState(),
                    ),
                    batchSession = validation.session.copy(stage = MovementBatchStage.Review),
                ),
            )
            val result = try {
                repository.recordMovementsBatch(drafts)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                uiState = uiState.copy(isBusy = false)
                throw cancelled
            } finally {
                movementBatchCommitInProgress = false
            }
            if (result.isSuccess) {
                uiState = uiState.copy(
                    movements = buildMovementsUiState(
                        scanState = MovementScanUiState(
                            scanSessionToken = uiState.movements.scan.scanSessionToken,
                        ),
                        batchSession = MovementBatchSessionUiState(),
                    ),
                )
            } else {
                saveMovementBatchDraft()
            }
            reloadState(
                statusMessage = result.message,
                preferredSelectedComponentId = if (result.isSuccess) {
                    drafts.lastOrNull()?.componentId
                } else {
                    null
                },
            )
            onComplete(result)
            if (result.isSuccess && uiState.appPreferences.syncAfterLocalChanges) {
                runSyncInternal()
            }
        }
    }

    fun resolveMovementComponentFromLabel(rawValue: String) {
        val scanState = uiState.movements.scan
        if (!scanState.isScannerVisible || scanState.isResolving) return
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = scanState.copy(
                    isResolving = true,
                    resolution = MovementScanResolutionUiState(),
                ),
                batchSession = uiState.movements.batchSession,
            ),
        )
        viewModelScope.launch {
            val resolution = repository.resolveComponentByScannedLabel(rawValue)
            if (uiState.movements.scan.scanSessionToken != scanState.scanSessionToken ||
                !uiState.movements.scan.isResolving
            ) return@launch
            val matchedComponent = resolution.matchedComponent
            if (matchedComponent != null) {
                uiState = uiState.copy(
                    movements = buildMovementsUiState(
                        scanState = uiState.movements.scan.copy(
                            isScannerVisible = false,
                            isResolving = false,
                            resolution = MovementScanResolutionUiState(),
                        ),
                        batchSession = movementBatchCoordinator().enqueue(
                            session = uiState.movements.batchSession,
                            component = matchedComponent,
                        ).copy(stage = MovementBatchStage.Review),
                    ),
                )
                saveMovementBatchDraft()
            } else {
                uiState = uiState.copy(
                    movements = buildMovementsUiState(
                        scanState = uiState.movements.scan.copy(
                            isScannerVisible = false,
                            isResolving = false,
                            resolution = resolution,
                        ),
                        batchSession = uiState.movements.batchSession.copy(
                            stage = MovementBatchStage.Review,
                        ),
                    ),
                )
            }
        }
    }

    fun saveSyncConfiguration(
        serverBaseUrl: String,
        apiToken: String,
        autoSyncEnabled: Boolean,
        externalServerBaseUrl: String,
    ) {
        val result = repository.saveSyncConfiguration(
            serverBaseUrl = serverBaseUrl,
            apiToken = apiToken,
            autoSyncEnabled = autoSyncEnabled,
            externalServerBaseUrl = externalServerBaseUrl,
        )
        uiState = uiState.copy(
            syncConfiguration = repository.loadSyncConfiguration(),
            statusMessage = result.message,
        )
    }

    fun saveAppPreferences(preferences: AppPreferences) {
        val result = repository.saveAppPreferences(preferences)
        uiState = uiState.copy(
            appPreferences = repository.loadAppPreferences(),
            statusMessage = result.message,
        )
    }

    fun testConnection(serverBaseUrl: String, apiToken: String, externalServerBaseUrl: String) {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.testConnection(serverBaseUrl, apiToken, externalServerBaseUrl)
            uiState = uiState.copy(
                syncConfiguration = repository.loadSyncConfiguration(),
                statusMessage = result.message,
                isBusy = false,
            )
        }
    }

    override fun onCleared() {
        stopObservingSync()
        activeSyncJob?.cancel()
        repository.cancelSyncRequests()
        super.onCleared()
    }

    fun runSync() {
        if (activeSyncJob?.isActive == true) {
            activeSyncJob?.cancel()
            repository.cancelSyncRequests()
            return
        }
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            runSyncInternal()
        }
    }

    fun clearImportLearningMappings() {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.clearImportLearningMappings()
            reloadState(result.message)
        }
    }

    private suspend fun runSyncInternal() {
        if (activeSyncJob != null) return
        activeSyncJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        var message: String? = null
        try {
            val result = repository.runSync()
            message = result.message.takeUnless { result.isSuccess }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                try { reloadState(message) }
                finally {
                    activeSyncJob = null
                    uiState = uiState.copy(isBusy = false)
                }
            }
        }
    }

    private suspend fun reloadState(
        statusMessage: String? = null,
        preferredSelectedComponentId: String? = null,
    ) {
        allComponentsCache = repository.loadComponents()
        allMovementsCache = repository.loadMovements()
        issuedQuantitiesCache = repository.loadIssuedQuantities()
        storageLocationsCache = repository.loadStorageLocations()
        allocationsCache = repository.loadAllocations()
        val appPreferences = repository.loadAppPreferences()
        val syncConfiguration = repository.loadSyncConfiguration()
        val importLearningSummary = repository.loadImportLearningSummary()
        val dashboardSnapshot = repository.loadDashboardSnapshot()
        uiState = uiState.copy(
            overview = inventoryProjector().overview(dashboardSnapshot),
            availableComponents = allComponentsCache,
            inventory = buildInventoryScreenUiState(
                preferredSelectedComponentId = preferredSelectedComponentId,
            ),
            movements = buildMovementsUiState(
                scanState = uiState.movements.scan,
                batchSession = uiState.movements.batchSession,
            ),
            importLearningSummary = importLearningSummary,
            appPreferences = appPreferences,
            syncConfiguration = syncConfiguration,
            statusMessage = statusMessage ?: syncConfiguration.lastSyncMessage,
            isBusy = false,
        )
        val diagnostic = inventoryDiagnosticFields(uiState.inventory) + listOf(
            "available" to uiState.availableComponents.size,
            "cache" to allComponentsCache.size,
        )
        if (diagnostic != lastInventoryDiagnostic) {
            lastInventoryDiagnostic = diagnostic
            com.componentvault.android.data.AppDiagnostics.record("inventory_publish", *diagnostic.toTypedArray())
        }
    }

    private fun maybeAutoSyncOnLaunch() {
        viewModelScope.launch {
            val syncConfiguration = repository.loadSyncConfiguration()
            if (
                syncConfiguration.autoSyncEnabled &&
                syncConfiguration.serverBaseUrl.isNotBlank() &&
                syncConfiguration.apiToken.isNotBlank()
            ) {
                uiState = uiState.copy(isBusy = true)
                runSyncInternal()
            }
        }
    }

    private fun updateInventoryFilters(filters: InventoryFiltersUiState) {
        uiState = uiState.copy(
            inventory = buildInventoryScreenUiState(filters = filters),
        )
    }

    private fun buildMovementsUiState(
        scanState: MovementScanUiState = uiState.movements.scan,
        batchSession: MovementBatchSessionUiState = uiState.movements.batchSession,
        preferredSelectedMovementId: String? = uiState.movements.selectedMovementId,
    ): MovementsUiState {
        val selectedMovementId = preferredSelectedMovementId?.takeIf { selectedId ->
            allMovementsCache.any { it.id == selectedId }
        }
        return MovementsUiState(
            items = allMovementsCache,
            activeComponentIds = allComponentsCache.mapTo(mutableSetOf()) { it.id },
            componentCount = allComponentsCache.size,
            selectedMovementId = selectedMovementId,
            scan = scanState,
            batchSession = movementBatchCoordinator().reconcile(batchSession),
        )
    }

    private fun buildInventoryScreenUiState(
        filters: InventoryFiltersUiState = uiState.inventory.filters,
        preferredSelectedComponentId: String? = null,
    ): InventoryScreenUiState = inventoryProjector().inventory(
        filters = filters,
        selectedComponentId = preferredSelectedComponentId ?: uiState.inventory.list.selectedComponentId,
    )

    private fun revealSavedComponent(componentId: String) {
        val filters = inventoryProjector().filtersRevealing(componentId, uiState.inventory.filters)
        uiState = uiState.copy(
            inventory = buildInventoryScreenUiState(
                filters = filters,
                preferredSelectedComponentId = componentId,
            ),
        )
    }

    private fun updateMovementBatchItem(
        componentId: String,
        transform: (MovementBatchQueueItemUiState) -> MovementBatchQueueItemUiState,
    ) {
        uiState = uiState.copy(
            movements = buildMovementsUiState(
                scanState = uiState.movements.scan,
                batchSession = movementBatchCoordinator().update(
                    session = uiState.movements.batchSession,
                    componentId = componentId,
                    transform = transform,
                ),
            ),
        )
        saveMovementBatchDraft()
    }

    private suspend fun <T> completeImportPreview(
        onComplete: (Result<T>) -> Unit,
        operation: suspend () -> T,
    ) {
        val result = try {
            Result.success(operation())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        onComplete(result)
    }

    fun listBomSheets(bytes: ByteArray, onComplete: (Result<List<BomSheet>>) -> Unit) {
        viewModelScope.launch {
            completeImportPreview(onComplete) { bomImportOperations.listSheets(bytes) }
        }
    }

    fun inspectBom(bytes: ByteArray, fileName: String, sheetName: String?, onComplete: (Result<com.componentvault.android.data.bom.BomTableInspection>) -> Unit) {
        viewModelScope.launch {
            completeImportPreview(onComplete) { bomImportOperations.inspect(bytes, fileName, sheetName) }
        }
    }

    fun previewBom(
        bytes: ByteArray,
        fileName: String,
        projectName: String,
        productionSets: Int,
        sheetName: String?,
        mapping: com.componentvault.android.data.bom.BomColumnMapping? = null,
        selections: Map<String, String> = emptyMap(),
        searchQueries: Map<String, String> = emptyMap(),
        excludedKeys: Set<String> = emptySet(),
        onComplete: (Result<BomReleasePreview>) -> Unit,
    ) {
        viewModelScope.launch {
            completeImportPreview(onComplete) {
                bomImportOperations.preview(
                    bytes, fileName, projectName, productionSets, sheetName,
                    mapping, selections, searchQueries, excludedKeys,
                )
            }
        }
    }

    internal fun projectMaterialComponents(): List<ComponentRecord> = allComponentsCache.filterNot { it.deleted }

    fun commitBomRelease(
        preview: BomReleasePreview,
        releaseId: String,
        batchId: String,
        onComplete: (BomReleaseResult) -> Unit,
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.commitBomRelease(preview, releaseId, batchId)
            reloadState(result.message)
            if (result.outcome == com.componentvault.android.data.bom.BomReleaseOutcome.APPLIED) {
                if (uiState.appPreferences.syncAfterLocalChanges) runSyncInternal()
            }
            onComplete(result)
        }
    }

    fun previewBomPreset(
        parsed: BomParseResult,
        selections: Map<String, String>,
        searchQueries: Map<String, String>,
        excludedKeys: Set<String>,
        onComplete: (Result<BomReleasePreview>) -> Unit,
    ) {
        viewModelScope.launch {
            completeImportPreview(onComplete) {
                bomImportOperations.previewPreset(parsed, selections, searchQueries, excludedKeys)
            }
        }
    }

    fun previewComponentHub(
        bytes: ByteArray,
        skipDuplicates: Boolean,
        onComplete: (Result<com.componentvault.android.data.bom.ComponentHubParseResult>) -> Unit,
    ) {
        viewModelScope.launch {
            completeImportPreview(onComplete) {
                bomImportOperations.previewComponentHub(bytes, skipDuplicates)
            }
        }
    }

    fun importComponentHub(
        preview: com.componentvault.android.data.bom.ComponentHubParseResult,
        onComplete: (com.componentvault.android.data.bom.ComponentHubImportResult) -> Unit,
    ) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val result = repository.importComponentHub(preview)
            reloadState(result.message)
            if (result.outcome == com.componentvault.android.data.bom.ComponentHubImportOutcome.APPLIED) {
                if (uiState.appPreferences.syncAfterLocalChanges) runSyncInternal()
            }
            onComplete(result)
        }
    }

    companion object {
        private const val MOVEMENT_BATCH_DRAFT_KEY = "movement_batch_review_draft"
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                    return InventoryViewModel(application, extras.createSavedStateHandle()) as T
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return InventoryViewModel(application) as T
                }
            }
    }
}
