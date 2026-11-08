package com.componentvault.android.ui.screen.preview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.componentvault.android.ui.screen.MovementDetailRoute
import com.componentvault.android.ui.screen.MovementsContent

@InventoryPhonePreview
@Composable
private fun MovementsPhonePreview() {
    PreviewHost {
        val uiState = InventoryPreviewData.movementsState()
        MovementsContent(
            contentPadding = PaddingValues(),
            uiState = uiState.movements,
            statusMessage = uiState.statusMessage,
            layoutMode = CompactPreviewLayout,
            onSelectMovement = {},
            onOpenMovementDetail = {},
            onScanMovementLabel = {},
            onRetryMovementScan = {},
            onDismissMovementScanResult = {},
            onDiscardMovementBatch = {},
            onOpenMovementBatchReview = {},
            onUpdateMovementBatchItemMovementType = { _, _ -> },
            onUpdateMovementBatchItemQuantity = { _, _ -> },
            onUpdateMovementBatchItemReason = { _, _ -> },
            onUpdateMovementBatchItemNote = { _, _ -> },
            onUpdateMovementBatchItemPurchaseAmount = { _, _ -> },
            onRemoveMovementBatchItem = {},
            onSearchInventoryBySku = {},
            onImportComponent = {},
            onRecordMovement = {},
        )
    }
}

@InventoryTabletPreview
@Composable
private fun MovementsTabletPreview() {
    PreviewHost {
        val uiState = InventoryPreviewData.movementsState()
        MovementsContent(
            contentPadding = PaddingValues(),
            uiState = uiState.movements,
            statusMessage = uiState.statusMessage,
            layoutMode = ExpandedPreviewLayout,
            onSelectMovement = {},
            onOpenMovementDetail = {},
            onScanMovementLabel = {},
            onRetryMovementScan = {},
            onDismissMovementScanResult = {},
            onDiscardMovementBatch = {},
            onOpenMovementBatchReview = {},
            onUpdateMovementBatchItemMovementType = { _, _ -> },
            onUpdateMovementBatchItemQuantity = { _, _ -> },
            onUpdateMovementBatchItemReason = { _, _ -> },
            onUpdateMovementBatchItemNote = { _, _ -> },
            onUpdateMovementBatchItemPurchaseAmount = { _, _ -> },
            onRemoveMovementBatchItem = {},
            onSearchInventoryBySku = {},
            onImportComponent = {},
            onRecordMovement = {},
        )
    }
}

@InventoryPhonePreview
@InventoryZhCnPreview
@InventoryLargeFontPreview
@Composable
private fun MovementsBatchReviewPhonePreview() {
    PreviewHost(strings = PreviewComponentVaultStrings.ZhCn) {
        val uiState = InventoryPreviewData.movementsBatchReviewState()
        MovementsContent(
            contentPadding = PaddingValues(),
            uiState = uiState.movements,
            statusMessage = uiState.statusMessage,
            layoutMode = CompactPreviewLayout,
            onSelectMovement = {},
            onOpenMovementDetail = {},
            onScanMovementLabel = {},
            onRetryMovementScan = {},
            onDismissMovementScanResult = {},
            onDiscardMovementBatch = {},
            onOpenMovementBatchReview = {},
            onUpdateMovementBatchItemMovementType = { _, _ -> },
            onUpdateMovementBatchItemQuantity = { _, _ -> },
            onUpdateMovementBatchItemReason = { _, _ -> },
            onUpdateMovementBatchItemNote = { _, _ -> },
            onUpdateMovementBatchItemPurchaseAmount = { _, _ -> },
            onRemoveMovementBatchItem = {},
            onSearchInventoryBySku = {},
            onImportComponent = {},
            onRecordMovement = {},
        )
    }
}

@InventoryPhonePreview
@Composable
private fun MovementsNotFoundPhonePreview() {
    PreviewHost {
        val uiState = InventoryPreviewData.movementsNotFoundState()
        MovementsContent(
            contentPadding = PaddingValues(),
            uiState = uiState.movements,
            statusMessage = uiState.statusMessage,
            layoutMode = CompactPreviewLayout,
            onSelectMovement = {},
            onOpenMovementDetail = {},
            onScanMovementLabel = {},
            onRetryMovementScan = {},
            onDismissMovementScanResult = {},
            onDiscardMovementBatch = {},
            onOpenMovementBatchReview = {},
            onUpdateMovementBatchItemMovementType = { _, _ -> },
            onUpdateMovementBatchItemQuantity = { _, _ -> },
            onUpdateMovementBatchItemReason = { _, _ -> },
            onUpdateMovementBatchItemNote = { _, _ -> },
            onUpdateMovementBatchItemPurchaseAmount = { _, _ -> },
            onRemoveMovementBatchItem = {},
            onSearchInventoryBySku = {},
            onImportComponent = {},
            onRecordMovement = {},
        )
    }
}

@InventoryNarrowPhonePreview
@Composable
private fun MovementDetailPhonePreview() {
    PreviewHost {
        MovementDetailRoute(
            movement = InventoryPreviewData.movementsState().movements.items.first(),
            onDismiss = {},
        )
    }
}
