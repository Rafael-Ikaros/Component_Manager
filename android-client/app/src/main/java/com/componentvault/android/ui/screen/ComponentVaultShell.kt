package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import com.componentvault.android.R
import com.componentvault.android.ui.theme.VaultPageTitle
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
    onPrintSelectedComponents: ((List<String>) -> Unit)? = null,
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
    onUpdateMovementBatchItemPurchaseAmount: (String, String) -> Unit = { _, _ -> },
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
    settingsTwoPane: Boolean = layoutMode.showsListDetail,
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
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
        onPrintSelectedComponents = onPrintSelectedComponents,
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
        onUpdateMovementBatchItemPurchaseAmount = onUpdateMovementBatchItemPurchaseAmount,
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
        settingsTwoPane = settingsTwoPane,
        onDetailAction = onDetailAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onPrintSelectedComponents: ((List<String>) -> Unit)? = null,
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
    onUpdateMovementBatchItemPurchaseAmount: (String, String) -> Unit = { _, _ -> },
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
    settingsTwoPane: Boolean = layoutMode.showsListDetail,
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
) {
    val strings = vaultStrings()
    val settingsDetail = selectedSettingsSection.takeIf {
        destination == InventoryDestination.Settings && !settingsTwoPane
    }
    val title = settingsDetail?.title(strings) ?: strings.shell.destinationLabel(destination)
    val colors = MaterialTheme.colorScheme
    val isFirstPhasePage = destination == InventoryDestination.Inventory || destination == InventoryDestination.Settings
    val caption = when {
        destination == InventoryDestination.Inventory && uiState.inventory.isLoaded -> {
            val count = uiState.inventory.totalComponentCount
            pluralStringResource(R.plurals.shell_local_inventory_count, count, count)
        }
        destination == InventoryDestination.Settings && settingsDetail == null ->
            stringResource(R.string.shell_settings_local_first)
        else -> null
    }
    val effectiveSnackbarHostState = snackbarHostState ?: remember { SnackbarHostState() }
    val destinationStateHolder = rememberSaveableStateHolder()

    val topBar: @Composable () -> Unit = {
        TopAppBar(
            title = {
                if (isFirstPhasePage) {
                    Column {
                        Text(title, style = VaultPageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (caption != null && LocalDensity.current.fontScale < 1.5f) {
                            Text(caption, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else Text(title)
            },
            navigationIcon = {
                if (settingsDetail != null) {
                    IconButton(onClick = onBackFromSettingsSection) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.common.actionBack,
                            tint = colors.primary)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
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

    val adaptiveNavigationType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    // IME padding belongs to the page. Release the bottom bar's layout space while the
    // keyboard covers it, otherwise that reserved space becomes a gap above the IME.
    val density = LocalDensity.current
    val imeOccludesBottom = WindowInsets.isImeVisible &&
        WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val usesBottomNavigation = adaptiveNavigationType == NavigationSuiteType.NavigationBar
    val navigationType = if (usesBottomNavigation && (imeOccludesBottom || settingsDetail != null)) {
        NavigationSuiteType.None
    } else {
        adaptiveNavigationType
    }
    val dockInventoryAction = usesBottomNavigation && layoutMode.widthClass == InventoryWidthClass.Compact
    val navigationItemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = colors.primaryContainer,
            selectedIconColor = colors.onPrimaryContainer,
            selectedTextColor = colors.primary,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = colors.primaryContainer,
            selectedIconColor = colors.onPrimaryContainer,
            selectedTextColor = colors.primary,
        ),
    )
    NavigationSuiteScaffold(
        layoutType = navigationType,
        navigationSuiteColors = NavigationSuiteDefaults.colors(navigationBarContainerColor = colors.background),
        navigationSuiteItems = {
            InventoryDestination.entries.forEach { item ->
                val label = strings.shell.destinationLabel(item)
                item(
                    selected = destination == item,
                    onClick = { onDestinationChange(item) },
                    icon = { Icon(item.icon, contentDescription = label) },
                    label = { Text(label) },
                    colors = navigationItemColors,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Scaffold(
            topBar = topBar,
            bottomBar = {
                if (dockInventoryAction && destination == InventoryDestination.Inventory && !imeOccludesBottom) {
                    Surface(color = colors.background) {
                        Button(
                            onClick = onAddComponent,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.shell_add_import), Modifier.padding(start = 6.dp))
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!dockInventoryAction && destination == InventoryDestination.Inventory) {
                    ExtendedFloatingActionButton(
                        onClick = onAddComponent,
                        text = { Text(stringResource(R.string.shell_add_import)) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    )
                }
            },
            snackbarHost = { SnackbarHost(effectiveSnackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().then(pageEntranceMotion(destination))) {
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
                        onPrintSelectedComponents = onPrintSelectedComponents,
                        onEditComponent = onEditComponent,
                        onRequestDeleteComponent = onRequestDeleteComponent,
                        onBatchTransfer = onBatchTransfer,
                        onQuickMovement = onQuickMovement,
                        onDetailAction = onDetailAction,
                        onScanMovementLabel = onScanMovementLabel,
                        onRetryMovementScan = onRetryMovementScan,
                        onDismissMovementScanResult = onDismissMovementScanResult,
                        onDiscardMovementBatch = onDiscardMovementBatch,
                        onOpenMovementBatchReview = onOpenMovementBatchReview,
                        onUpdateMovementBatchItemMovementType = onUpdateMovementBatchItemMovementType,
                        onUpdateMovementBatchItemQuantity = onUpdateMovementBatchItemQuantity,
                        onUpdateMovementBatchItemReason = onUpdateMovementBatchItemReason,
                        onUpdateMovementBatchItemNote = onUpdateMovementBatchItemNote,
                        onUpdateMovementBatchItemPurchaseAmount = onUpdateMovementBatchItemPurchaseAmount,
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
    onPrintSelectedComponents: ((List<String>) -> Unit)?,
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
    onUpdateMovementBatchItemPurchaseAmount: (String, String) -> Unit = { _, _ -> },
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
    onDetailAction: ((String, InventoryDetailAction) -> Unit)?,
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
            onPrintSelectedComponents = onPrintSelectedComponents,
            onEditComponent = onEditComponent,
            onRequestDeleteComponent = onRequestDeleteComponent,
            onBatchTransfer = onBatchTransfer,
            onQuickMovement = onQuickMovement,
            onDetailAction = onDetailAction,
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
            onUpdateMovementBatchItemPurchaseAmount = onUpdateMovementBatchItemPurchaseAmount,
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
