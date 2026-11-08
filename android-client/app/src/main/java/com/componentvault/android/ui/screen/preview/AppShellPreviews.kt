package com.componentvault.android.ui.screen.preview

import androidx.compose.runtime.Composable
import com.componentvault.android.ui.screen.ComponentVaultAppShellContent
import com.componentvault.android.ui.screen.InventoryDestination

@InventoryShellPhonePreview
@Composable
private fun AppShellPhoneInventoryPreview() {
    PreviewHost {
        ComponentVaultAppShellContent(
            uiState = InventoryPreviewData.inventoryState(),
            destination = InventoryDestination.Inventory,
            layoutMode = CompactPreviewLayout,
            onDestinationChange = {},
            onQueryChange = {},
            onStockFilterChange = {},
            onCategoryChange = {},
            onLocationChange = {},
            onSortChange = {},
            onSelectComponent = {},
            onOpenComponentDetail = {},
            onAddComponent = {},
            onImportComponent = {},
            onGenerateLabel = {},
            onEditComponent = {},
            onRequestDeleteComponent = {},
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
            onRecordMovement = {},
            onOpenLowStockInventory = {},
            onSelectOverviewComponent = {},
            onOpenMovements = {},
            onSelectMovement = {},
            onOpenMovementDetail = {},
            onOpenSettingsHome = {},
            onOpenSyncSettings = {},
        )
    }
}

@InventoryShellTabletPreview
@Composable
private fun AppShellTabletOverviewPreview() {
    PreviewHost {
        ComponentVaultAppShellContent(
            uiState = InventoryPreviewData.overviewState(),
            destination = InventoryDestination.Overview,
            layoutMode = ExpandedPreviewLayout,
            onDestinationChange = {},
            onQueryChange = {},
            onStockFilterChange = {},
            onCategoryChange = {},
            onLocationChange = {},
            onSortChange = {},
            onSelectComponent = {},
            onOpenComponentDetail = {},
            onAddComponent = {},
            onImportComponent = {},
            onGenerateLabel = {},
            onEditComponent = {},
            onRequestDeleteComponent = {},
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
            onRecordMovement = {},
            onOpenLowStockInventory = {},
            onSelectOverviewComponent = {},
            onOpenMovements = {},
            onSelectMovement = {},
            onOpenMovementDetail = {},
            onOpenSettingsHome = {},
            onOpenSyncSettings = {},
        )
    }
}
