package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import com.componentvault.android.model.InventorySortOption
import com.componentvault.android.model.InventoryStockFilter
import com.componentvault.android.model.InventoryUiState

@Composable
internal fun ComponentVaultAppShell(
    uiState: InventoryUiState,
    destination: InventoryDestination,
    layoutMode: InventoryLayoutMode,
    onDestinationChange: (InventoryDestination) -> Unit,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onScanInventory: () -> Unit = {},
    onSortChange: (InventorySortOption) -> Unit,
    onSelectComponent: (String) -> Unit,
    onOpenComponentDetail: (String) -> Unit,
    onAddComponent: () -> Unit,
    onImportComponent: () -> Unit,
    onGenerateLabel: (String) -> Unit,
    onOpenBluetoothPrint: () -> Unit = {},
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onBatchTransfer: BatchTransferAction? = null,
    onQuickMovement: QuickMovementAction? = null,
    onScanMovementLabel: () -> Unit,
    onRetryMovementScan: () -> Unit,
    onDismissMovementScanResult: () -> Unit,
    onDiscardMovementBatch: () -> Unit,
    onOpenMovementBatchReview: () -> Unit,
    onUpdateMovementBatchItemMovementType: (String, String) -> Unit,
    onUpdateMovementBatchItemQuantity: (String, String) -> Unit,
    onUpdateMovementBatchItemReason: (String, String) -> Unit,
    onUpdateMovementBatchItemNote: (String, String) -> Unit,
    onRemoveMovementBatchItem: (String) -> Unit,
    onSearchInventoryBySku: (String) -> Unit,
    onRecordMovement: (String?) -> Unit,
    onOpenLowStockInventory: () -> Unit,
    onSelectOverviewComponent: (String) -> Unit,
    onOpenMovements: (String?) -> Unit,
    onSelectMovement: (String) -> Unit,
    onOpenMovementDetail: (String) -> Unit,
    onOpenSettingsHome: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    selectedSettingsSection: SettingsSection?,
    onBackFromSettingsSection: () -> Unit,
    settingsContent: @Composable (PaddingValues) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    ComponentVaultAppShellContent(
        uiState = uiState,
        destination = destination,
        layoutMode = layoutMode,
        onDestinationChange = onDestinationChange,
        onQueryChange = onQueryChange,
        onStockFilterChange = onStockFilterChange,
        onCategoryChange = onCategoryChange,
        onLocationChange = onLocationChange,
        onScanInventory = onScanInventory,
        onSortChange = onSortChange,
        onSelectComponent = onSelectComponent,
        onOpenComponentDetail = onOpenComponentDetail,
        onAddComponent = onAddComponent,
        onImportComponent = onImportComponent,
        onGenerateLabel = onGenerateLabel,
        onOpenBluetoothPrint = onOpenBluetoothPrint,
        onEditComponent = onEditComponent,
        onRequestDeleteComponent = onRequestDeleteComponent,
        onBatchTransfer = onBatchTransfer,
        onQuickMovement = onQuickMovement,
        onScanMovementLabel = onScanMovementLabel,
        onRetryMovementScan = onRetryMovementScan,
        onDismissMovementScanResult = onDismissMovementScanResult,
        onDiscardMovementBatch = onDiscardMovementBatch,
        onOpenMovementBatchReview = onOpenMovementBatchReview,
        onUpdateMovementBatchItemMovementType = onUpdateMovementBatchItemMovementType,
        onUpdateMovementBatchItemQuantity = onUpdateMovementBatchItemQuantity,
        onUpdateMovementBatchItemReason = onUpdateMovementBatchItemReason,
        onUpdateMovementBatchItemNote = onUpdateMovementBatchItemNote,
        onRemoveMovementBatchItem = onRemoveMovementBatchItem,
        onSearchInventoryBySku = onSearchInventoryBySku,
        onRecordMovement = onRecordMovement,
        onOpenLowStockInventory = onOpenLowStockInventory,
        onSelectOverviewComponent = onSelectOverviewComponent,
        onOpenMovements = onOpenMovements,
        onSelectMovement = onSelectMovement,
        onOpenMovementDetail = onOpenMovementDetail,
        onOpenSettingsHome = onOpenSettingsHome,
        onOpenSyncSettings = onOpenSyncSettings,
        selectedSettingsSection = selectedSettingsSection,
        onBackFromSettingsSection = onBackFromSettingsSection,
        settingsContent = settingsContent,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComponentVaultAppShellContent(
    uiState: InventoryUiState,
    destination: InventoryDestination,
    layoutMode: InventoryLayoutMode,
    onDestinationChange: (InventoryDestination) -> Unit,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onScanInventory: () -> Unit = {},
    onSortChange: (InventorySortOption) -> Unit,
    onSelectComponent: (String) -> Unit,
    onOpenComponentDetail: (String) -> Unit,
    onAddComponent: () -> Unit,
    onImportComponent: () -> Unit,
    onGenerateLabel: (String) -> Unit,
    onOpenBluetoothPrint: () -> Unit = {},
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onBatchTransfer: BatchTransferAction? = null,
    onQuickMovement: QuickMovementAction? = null,
    onScanMovementLabel: () -> Unit,
    onRetryMovementScan: () -> Unit,
    onDismissMovementScanResult: () -> Unit,
    onDiscardMovementBatch: () -> Unit,
    onOpenMovementBatchReview: () -> Unit,
    onUpdateMovementBatchItemMovementType: (String, String) -> Unit,
    onUpdateMovementBatchItemQuantity: (String, String) -> Unit,
    onUpdateMovementBatchItemReason: (String, String) -> Unit,
    onUpdateMovementBatchItemNote: (String, String) -> Unit,
    onRemoveMovementBatchItem: (String) -> Unit,
    onSearchInventoryBySku: (String) -> Unit,
    onRecordMovement: (String?) -> Unit,
    onOpenLowStockInventory: () -> Unit,
    onSelectOverviewComponent: (String) -> Unit,
    onOpenMovements: (String?) -> Unit,
    onSelectMovement: (String) -> Unit,
    onOpenMovementDetail: (String) -> Unit,
    onOpenSettingsHome: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    selectedSettingsSection: SettingsSection? = null,
    onBackFromSettingsSection: () -> Unit = {},
    settingsContent: @Composable (PaddingValues) -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val strings = vaultStrings()
    val title = if (
        destination == InventoryDestination.Settings &&
        selectedSettingsSection != null &&
        !layoutMode.showsListDetail
    ) {
        selectedSettingsSection.title(strings)
    } else {
        strings.shell.destinationLabel(destination)
    }
    val effectiveSnackbarHostState = snackbarHostState ?: remember { SnackbarHostState() }
    val destinationStateHolder = rememberSaveableStateHolder()

    val topBar: @Composable () -> Unit = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (destination == InventoryDestination.Settings && selectedSettingsSection != null && !layoutMode.showsListDetail) {
                    androidx.compose.material3.TextButton(onClick = onBackFromSettingsSection) {
                        Text(strings.common.actionBack)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            actions = {
                if (uiState.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
        )
    }

    val floatingActionButton: @Composable () -> Unit = {
        when (destination) {
            InventoryDestination.Inventory -> {
                ExtendedFloatingActionButton(
                    onClick = onAddComponent,
                    text = { Text(strings.common.actionImport) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                )
            }

            else -> Unit
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            InventoryDestination.entries.forEach { item ->
                val label = strings.shell.destinationLabel(item)
                item(
                    selected = destination == item,
                    onClick = { onDestinationChange(item) },
                    icon = { Icon(item.icon, contentDescription = label) },
                    label = { Text(label) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Scaffold(
            topBar = topBar,
            floatingActionButton = floatingActionButton,
            snackbarHost = { SnackbarHost(effectiveSnackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            destinationStateHolder.SaveableStateProvider(destination.name) {
                ShellContent(
                    contentPadding = padding,
                    uiState = uiState,
                    destination = destination,
                    layoutMode = layoutMode,
                    onQueryChange = onQueryChange,
                    onStockFilterChange = onStockFilterChange,
                    onCategoryChange = onCategoryChange,
                    onLocationChange = onLocationChange,
                    onScanInventory = onScanInventory,
                    onSortChange = onSortChange,
                    onSelectComponent = onSelectComponent,
                    onOpenComponentDetail = onOpenComponentDetail,
                    onImportComponent = onImportComponent,
                    onGenerateLabel = onGenerateLabel,
                    onOpenBluetoothPrint = onOpenBluetoothPrint,
                    onEditComponent = onEditComponent,
                    onRequestDeleteComponent = onRequestDeleteComponent,
                    onBatchTransfer = onBatchTransfer,
                    onQuickMovement = onQuickMovement,
                    onScanMovementLabel = onScanMovementLabel,
                    onRetryMovementScan = onRetryMovementScan,
                    onDismissMovementScanResult = onDismissMovementScanResult,
                    onDiscardMovementBatch = onDiscardMovementBatch,
                    onOpenMovementBatchReview = onOpenMovementBatchReview,
                    onUpdateMovementBatchItemMovementType = onUpdateMovementBatchItemMovementType,
                    onUpdateMovementBatchItemQuantity = onUpdateMovementBatchItemQuantity,
                    onUpdateMovementBatchItemReason = onUpdateMovementBatchItemReason,
                    onUpdateMovementBatchItemNote = onUpdateMovementBatchItemNote,
                    onRemoveMovementBatchItem = onRemoveMovementBatchItem,
                    onSearchInventoryBySku = onSearchInventoryBySku,
                    onRecordMovement = onRecordMovement,
                    onOpenLowStockInventory = onOpenLowStockInventory,
                    onSelectOverviewComponent = onSelectOverviewComponent,
                    onOpenMovements = onOpenMovements,
                    onSelectMovement = onSelectMovement,
                    onOpenMovementDetail = onOpenMovementDetail,
                    onOpenSettings = onOpenSettingsHome,
                    onOpenSyncSettings = onOpenSyncSettings,
                    settingsContent = settingsContent,
                )
            }
        }
    }
}

@Composable
private fun ShellContent(
    contentPadding: PaddingValues,
    uiState: InventoryUiState,
    destination: InventoryDestination,
    layoutMode: InventoryLayoutMode,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onScanInventory: () -> Unit,
    onSortChange: (InventorySortOption) -> Unit,
    onSelectComponent: (String) -> Unit,
    onOpenComponentDetail: (String) -> Unit,
    onImportComponent: () -> Unit,
    onGenerateLabel: (String) -> Unit,
    onOpenBluetoothPrint: () -> Unit,
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onBatchTransfer: BatchTransferAction?,
    onQuickMovement: QuickMovementAction?,
    onScanMovementLabel: () -> Unit,
    onRetryMovementScan: () -> Unit,
    onDismissMovementScanResult: () -> Unit,
    onDiscardMovementBatch: () -> Unit,
    onOpenMovementBatchReview: () -> Unit,
    onUpdateMovementBatchItemMovementType: (String, String) -> Unit,
    onUpdateMovementBatchItemQuantity: (String, String) -> Unit,
    onUpdateMovementBatchItemReason: (String, String) -> Unit,
    onUpdateMovementBatchItemNote: (String, String) -> Unit,
    onRemoveMovementBatchItem: (String) -> Unit,
    onSearchInventoryBySku: (String) -> Unit,
    onRecordMovement: (String?) -> Unit,
    onOpenLowStockInventory: () -> Unit,
    onSelectOverviewComponent: (String) -> Unit,
    onOpenMovements: (String?) -> Unit,
    onSelectMovement: (String) -> Unit,
    onOpenMovementDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    settingsContent: @Composable (PaddingValues) -> Unit,
) {
    when (destination) {
        InventoryDestination.Inventory -> InventoryContent(
            contentPadding = contentPadding,
            uiState = uiState.inventory,
            statusMessage = uiState.statusMessage,
            layoutMode = layoutMode,
            onQueryChange = onQueryChange,
            onStockFilterChange = onStockFilterChange,
            onCategoryChange = onCategoryChange,
            onLocationChange = onLocationChange,
            onScanInventory = onScanInventory,
            onSortChange = onSortChange,
            onSelectComponent = onSelectComponent,
            onOpenComponentDetail = onOpenComponentDetail,
            onImportComponent = onImportComponent,
            onGenerateLabel = onGenerateLabel,
            onOpenBluetoothPrint = onOpenBluetoothPrint,
            onEditComponent = onEditComponent,
            onRequestDeleteComponent = onRequestDeleteComponent,
            onBatchTransfer = onBatchTransfer,
            onQuickMovement = onQuickMovement,
            onRecordMovement = { componentId -> onRecordMovement(componentId) },
        )

        InventoryDestination.Movements -> MovementsContent(
            contentPadding = contentPadding,
            uiState = uiState.movements,
            statusMessage = uiState.statusMessage,
            layoutMode = layoutMode,
            onSelectMovement = onSelectMovement,
            onOpenMovementDetail = onOpenMovementDetail,
            onScanMovementLabel = onScanMovementLabel,
            onRetryMovementScan = onRetryMovementScan,
            onDismissMovementScanResult = onDismissMovementScanResult,
            onDiscardMovementBatch = onDiscardMovementBatch,
            onOpenMovementBatchReview = onOpenMovementBatchReview,
            onUpdateMovementBatchItemMovementType = onUpdateMovementBatchItemMovementType,
            onUpdateMovementBatchItemQuantity = onUpdateMovementBatchItemQuantity,
            onUpdateMovementBatchItemReason = onUpdateMovementBatchItemReason,
            onUpdateMovementBatchItemNote = onUpdateMovementBatchItemNote,
            onRemoveMovementBatchItem = onRemoveMovementBatchItem,
            onSearchInventoryBySku = onSearchInventoryBySku,
            onImportComponent = onImportComponent,
            onRecordMovement = { onRecordMovement(uiState.inventory.list.selectedComponentId) },
        )

        InventoryDestination.Overview -> OverviewContent(
            contentPadding = contentPadding,
            uiState = uiState.overview,
            storageLocations = uiState.inventory.storageLocations,
            syncConfiguration = uiState.syncConfiguration,
            statusMessage = uiState.statusMessage,
            onOpenLowStock = onOpenLowStockInventory,
            onSelectLowStockComponent = onSelectOverviewComponent,
            onOpenMovements = { onOpenMovements(null) },
            onSelectMovement = { movementId -> onOpenMovements(movementId) },
            onOpenSettings = onOpenSettings,
            onOpenSyncSettings = onOpenSyncSettings,
        )

        InventoryDestination.Settings -> settingsContent(contentPadding)
    }
}
