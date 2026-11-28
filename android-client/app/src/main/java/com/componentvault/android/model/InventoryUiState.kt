package com.componentvault.android.model

data class InventoryUiState(
    val overview: OverviewUiState = OverviewUiState(),
    val availableComponents: List<ComponentRecord> = emptyList(),
    val inventory: InventoryScreenUiState = InventoryScreenUiState(),
    val movements: MovementsUiState = MovementsUiState(),
    val importLearningSummary: ImportLearningSummary = ImportLearningSummary(),
    val appPreferences: AppPreferences = AppPreferences(),
    val syncConfiguration: SyncConfiguration = SyncConfiguration(
        deviceId = "",
        serverBaseUrl = "",
        apiToken = "",
        autoSyncEnabled = false,
        lastSyncedAt = "",
        lastSyncMessage = "",
    ),
    val isBusy: Boolean = false,
    val statusMessage: String = "",
)

data class OverviewUiState(
    val componentCount: Int = 0,
    val totalUnits: Int = 0,
    val lowStockCount: Int = 0,
    val movementCount: Int = 0,
    val lowStockItems: List<InventoryListItemUiState> = emptyList(),
    val recentMovements: List<StockMovementRecord> = emptyList(),
)

data class InventoryScreenUiState(
    val totalComponentCount: Int = 0,
    val filters: InventoryFiltersUiState = InventoryFiltersUiState(),
    val availableCategories: List<String> = emptyList(),
    val availableLocations: List<String> = emptyList(),
    val list: InventoryListUiState = InventoryListUiState(),
    val detail: InventoryDetailUiState = InventoryDetailUiState(),
    val storageLocations: List<StorageLocationRecord> = emptyList(),
    val allocations: List<ComponentAllocationRecord> = emptyList(),
    val isLoaded: Boolean = true,
)

data class InventoryFiltersUiState(
    val query: String = "",
    val stockFilter: InventoryStockFilter = InventoryStockFilter.All,
    val category: String? = null,
    val location: String? = null,
    val sort: InventorySortOption = InventorySortOption.UpdatedNewest,
)

data class InventoryListUiState(
    val items: List<InventoryListItemUiState> = emptyList(),
    val selectedComponentId: String? = null,
)

data class InventoryListItemUiState(
    val id: String,
    val name: String,
    val sku: String,
    val category: String,
    val packageName: String,
    val location: String,
    val quantity: Int,
    val minStock: Int,
    val isLowStock: Boolean,
    val updatedAt: String,
    val productImageUrl: String? = null,
    val description: String = "",
    val specificationSummary: String? = null,
    val issuedQuantity: Long = 0,
    val inStockLocationIds: List<String> = emptyList(),
)

data class InventoryDetailUiState(
    val component: ComponentRecord? = null,
    val recentMovements: List<StockMovementRecord> = emptyList(),
    val issuedQuantity: Long = 0,
    val allocations: List<ComponentAllocationRecord> = emptyList(),
)

data class MovementsUiState(
    val items: List<StockMovementRecord> = emptyList(),
    val activeComponentIds: Set<String>? = null,
    val componentCount: Int = 0,
    val selectedMovementId: String? = null,
    val scan: MovementScanUiState = MovementScanUiState(),
    val batchSession: MovementBatchSessionUiState = MovementBatchSessionUiState(),
)

enum class InventoryStockFilter {
    All,
    LowStock,
}

enum class InventorySortOption {
    UpdatedNewest,
    Name,
    QuantityLowToHigh,
    QuantityHighToLow,
}
