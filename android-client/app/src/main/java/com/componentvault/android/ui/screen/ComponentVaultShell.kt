package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
    val caption = when {
        settingsDetail != null -> settingsDetail.caption()
        destination == InventoryDestination.Inventory && uiState.inventory.isLoaded -> {
            val count = uiState.inventory.totalComponentCount
            pluralStringResource(R.plurals.shell_local_inventory_count, count, count)
        }
        destination == InventoryDestination.Settings ->
            stringResource(R.string.shell_settings_local_first)
        destination == InventoryDestination.Overview ->
            stringResource(if (uiState.syncConfiguration.serverBaseUrl.isBlank() ||
                uiState.syncConfiguration.apiToken.isBlank()) R.string.v2_overview_offline
                else R.string.appearance_sync_configured)
        destination == InventoryDestination.Movements -> stringResource(R.string.v2_records_caption)
        else -> null
    }
    val effectiveSnackbarHostState = snackbarHostState ?: remember { SnackbarHostState() }
    val destinationStateHolder = rememberSaveableStateHolder()

    val topBar: @Composable () -> Unit = {
        VaultScreenHeader(
            title = title,
            caption = caption,
            onBack = onBackFromSettingsSection.takeIf { settingsDetail != null },
            actions = {
                if (uiState.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
    val showPhoneNavigation = usesBottomNavigation && !imeOccludesBottom && settingsDetail == null
    val navigationType = if (usesBottomNavigation) NavigationSuiteType.None else adaptiveNavigationType
    val dockInventoryAction = usesBottomNavigation && layoutMode.widthClass == InventoryWidthClass.Compact
    val navigationItemColors = NavigationSuiteDefaults.itemColors(
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = colors.primaryContainer,
            selectedIconColor = colors.onPrimaryContainer,
            selectedTextColor = colors.primary,
        ),
    )
    NavigationSuiteScaffold(
        layoutType = navigationType,
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
                if (showPhoneNavigation) {
                    Column(Modifier.fillMaxWidth().background(colors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars)) {
                        if (dockInventoryAction && destination == InventoryDestination.Inventory) {
                            Button(
                                onClick = onAddComponent,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 8.dp)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(15.dp),
                            ) {
                                Icon(painterResource(R.drawable.v2_plus), contentDescription = null, modifier = Modifier.size(17.dp))
                                Text(stringResource(R.string.shell_add_import), Modifier.padding(start = 6.dp))
                            }
                        }
                        VaultBottomNavigation(destination, onDestinationChange)
                    }
                }
            },
            floatingActionButton = {
                if (!dockInventoryAction && destination == InventoryDestination.Inventory) {
                    ExtendedFloatingActionButton(
                        onClick = onAddComponent,
                        text = { Text(stringResource(R.string.shell_add_import)) },
                        icon = { Icon(painterResource(R.drawable.v2_plus), contentDescription = null) },
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
                        onOpenInventory = { onDestinationChange(InventoryDestination.Inventory) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultScreenHeader(
    title: String,
    caption: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalConfiguration.current.screenWidthDp <= 340
    val edge = if (narrow) 15.dp else 21.dp
    Row(
        Modifier.fillMaxWidth().background(colors.background)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(start = if (onBack == null) edge else edge - 11.dp, end = edge,
                top = if (narrow) 15.dp else 16.dp, bottom = if (narrow) 17.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.v2_arrow_left), contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(16.dp), tint = colors.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = if (narrow) VaultPageTitle.copy(fontSize = 24.sp, lineHeight = 30.sp)
                else VaultPageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
private fun VaultBottomNavigation(
    destination: InventoryDestination,
    onDestinationChange: (InventoryDestination) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val strings = vaultStrings()
    HorizontalDivider(color = colors.outlineVariant)
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InventoryDestination.entries.forEach { item ->
            val selected = destination == item
            val label = strings.shell.destinationLabel(item)
            val tint = if (selected) colors.primary else colors.onSurfaceVariant
            Column(
                Modifier.weight(1f).heightIn(min = 55.dp)
                    .selectable(selected, role = Role.Tab, onClick = { onDestinationChange(item) })
                    .semantics { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier.widthIn(max = 62.dp).fillMaxWidth().heightIn(min = 31.dp)
                        .background(if (selected) colors.primaryContainer else colors.background,
                            RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (item) {
                        InventoryDestination.Overview -> R.drawable.v2_home
                        InventoryDestination.Inventory -> R.drawable.v2_boxes
                        InventoryDestination.Movements -> R.drawable.v2_arrow_left_right
                        InventoryDestination.Settings -> R.drawable.v2_sliders_horizontal
                    }
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
                }
                Text(label, color = tint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onOpenInventory: () -> Unit,
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
            lowStockCount = uiState.overview.lowStockCount,
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
            onOpenInventory = onOpenInventory,
            storageLocations = uiState.inventory.storageLocations,
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
            onOpenInventory = onOpenInventory,
        )

        InventoryDestination.Settings -> settingsContent(contentPadding)
    }
}
