package com.componentvault.android.model

enum class MovementQuickAction(
    val movementType: String,
) {
    Inbound("inbound"),
    Outbound("outbound"),
    Adjustment("adjustment"),
}

enum class MovementScanMatchStatus {
    Idle,
    Matched,
    InvalidLabel,
    NotFound,
    Ambiguous,
}

data class MovementScanResolutionUiState(
    val matchStatus: MovementScanMatchStatus = MovementScanMatchStatus.Idle,
    val rawValue: String = "",
    val parsedSku: String = "",
    val parsedName: String = "",
    val parsedPackageName: String = "",
    val parsedLocation: String = "",
    val matchedComponent: ComponentRecord? = null,
)

enum class MovementBatchStage {
    Scanning,
    Review,
}

data class MovementBatchQueueItemUiState(
    val componentId: String,
    val componentName: String,
    val componentSku: String,
    val category: String,
    val packageName: String,
    val location: String,
    val currentStock: Int,
    val minStock: Int,
    val locationQuantities: Map<String, Int> = emptyMap(),
    val locationNames: Map<String, String> = emptyMap(),
    val scanCount: Int = 1,
    val movementType: String = MovementQuickAction.Inbound.movementType,
    val quantityText: String = "1",
    val purchaseAmount: String = "",
    val reason: String = "",
    val note: String = "",
    val errorMessage: String? = null,
    val isDirty: Boolean = false,
)

data class MovementBatchSessionUiState(
    val defaultMovementType: String = MovementQuickAction.Inbound.movementType,
    val stage: MovementBatchStage = MovementBatchStage.Review,
    val queuedItems: List<MovementBatchQueueItemUiState> = emptyList(),
    val totalScans: Int = 0,
    val lastQueuedComponentName: String = "",
    val lastQueuedComponentSku: String = "",
) {
    val isActive: Boolean
        get() = queuedItems.isNotEmpty() || totalScans > 0
}

data class MovementScanUiState(
    val isScannerVisible: Boolean = false,
    val isResolving: Boolean = false,
    val resolution: MovementScanResolutionUiState = MovementScanResolutionUiState(),
    val scanSessionToken: Int = 0,
)
