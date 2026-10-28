package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.InventoryDetailUiState
import com.componentvault.android.model.InventoryScreenUiState
import com.componentvault.android.model.InventorySortOption
import com.componentvault.android.model.InventoryStockFilter
import com.componentvault.android.model.officialParameters
import com.componentvault.android.model.officialParameterKind
import com.componentvault.android.model.StockMovementRecord
import com.componentvault.android.model.StockUsageSummary
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.ui.theme.VaultWarning
import com.componentvault.android.ui.theme.VaultWarningContainer
import kotlinx.coroutines.launch
import java.util.Locale

internal fun localizedOfficialParameterValue(kind: String?, value: String, locale: Locale): String {
    if (kind != "type" || !locale.language.equals("zh", ignoreCase = true)) return value
    return when (value.trim().lowercase(Locale.ROOT)) {
        "ceramic", "ceramic capacitor" -> "陶瓷电容"
        "multilayer ceramic capacitor", "multilayer ceramic capacitors", "mlcc" -> "多层陶瓷电容"
        "thick film" -> "厚膜"
        "thin film" -> "薄膜"
        "smd", "surface mount" -> "贴片"
        else -> value
    }
}
@Composable
internal fun InventoryScreen(
    contentPadding: PaddingValues,
    uiState: InventoryScreenUiState,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onScanInventory: () -> Unit = {},
    onSortChange: (InventorySortOption) -> Unit,
    onSelectComponent: (String) -> Unit,
    onOpenComponentDetail: (String) -> Unit,
    onImportComponent: () -> Unit,
    onGenerateLabel: (String) -> Unit,
    onOpenBluetoothPrint: () -> Unit = {},
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onBatchTransfer: BatchTransferAction? = null,
    onQuickMovement: QuickMovementAction? = null,
) {
    InventoryContent(
        contentPadding = contentPadding,
        uiState = uiState,
        statusMessage = statusMessage,
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
        onRecordMovement = onRecordMovement,
        onBatchTransfer = onBatchTransfer,
        onQuickMovement = onQuickMovement,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun InventoryContent(
    contentPadding: PaddingValues,
    uiState: InventoryScreenUiState,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onScanInventory: () -> Unit = {},
    onSortChange: (InventorySortOption) -> Unit,
    onSelectComponent: (String) -> Unit,
    onOpenComponentDetail: (String) -> Unit,
    onImportComponent: () -> Unit,
    onGenerateLabel: (String) -> Unit,
    onOpenBluetoothPrint: () -> Unit = {},
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onBatchTransfer: BatchTransferAction? = null,
    onQuickMovement: QuickMovementAction? = null,
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val selectionModeState = rememberSaveable { mutableStateOf(false) }
    val selectedIdsState = rememberSaveable { mutableStateOf(emptyList<String>()) }
    var lastNavigatedComponentId by rememberSaveable { mutableStateOf<String?>(null) }
    if (layoutMode.showsListDetail) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .consumeWindowInsets(contentPadding)
                .imePadding()
                .padding(rememberContentPadding(contentPadding, horizontal = 20.dp, vertical = 20.dp)),
        ) {
            val scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).copy(
                maxHorizontalPartitions = if (maxWidth >= 836.dp) 2 else 1,
                horizontalPartitionSpacerSize = 16.dp,
            )
            val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = scaffoldDirective)
            val scope = rememberCoroutineScope()

            LaunchedEffect(uiState.list.selectedComponentId) {
                val selectedComponentId = uiState.list.selectedComponentId ?: return@LaunchedEffect
                if (selectedComponentId != lastNavigatedComponentId) {
                    lastNavigatedComponentId = selectedComponentId
                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedComponentId)
                }
            }

            NavigableListDetailPaneScaffold(
                modifier = Modifier.fillMaxSize(),
                navigator = navigator,
                listPane = {
                    AnimatedPane(modifier = Modifier.preferredWidth(420.dp)) {
                        InventoryListPane(
                            uiState = uiState,
                            statusMessage = statusMessage,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(),
                            compactRows = true,
                            listState = listState,
                            selectionModeState = selectionModeState,
                            selectedIdsState = selectedIdsState,
                            onQueryChange = onQueryChange,
                            onStockFilterChange = onStockFilterChange,
                            onCategoryChange = onCategoryChange,
                            onLocationChange = onLocationChange,
                            onScanInventory = onScanInventory,
                            onSortChange = onSortChange,
                            onSelectComponent = { componentId ->
                                focusManager.clearFocus()
                                lastNavigatedComponentId = componentId
                                onSelectComponent(componentId)
                                scope.launch {
                                    navigator.navigateTo(
                                        ListDetailPaneScaffoldRole.Detail,
                                        componentId,
                                    )
                                }
                            },
                            onOpenBluetoothPrint = onOpenBluetoothPrint,
                            onRequestDeleteComponent = onRequestDeleteComponent,
                            onBatchTransfer = onBatchTransfer,
                            onQuickMovement = onQuickMovement,
                        )
                    }
                },
                detailPane = {
                    AnimatedPane {
                        Column(Modifier.fillMaxSize()) {
                            if (scaffoldDirective.maxHorizontalPartitions == 1) {
                                TextButton(onClick = { scope.launch { navigator.navigateBack() } }) {
                                    Text(vaultStrings().common.actionBack)
                                }
                            }
                            InventoryDetailPane(
                                detail = uiState.detail,
                                storageLocations = uiState.storageLocations,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                adaptiveLayout = true,
                                onEditComponent = onEditComponent,
                                onGenerateLabel = { component -> onGenerateLabel(component.id) },
                                onRequestDeleteComponent = onRequestDeleteComponent,
                                onRecordMovement = onRecordMovement,
                            )
                        }
                    }
                },
            )
        }
    } else {
        InventoryListPane(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(contentPadding)
                .imePadding(),
            contentPadding = rememberContentPadding(contentPadding, horizontal = 16.dp, vertical = 12.dp),
            uiState = uiState,
            statusMessage = statusMessage,
            compactRows = layoutMode.widthClass != InventoryWidthClass.Compact,
            listState = listState,
            selectionModeState = selectionModeState,
            selectedIdsState = selectedIdsState,
            onQueryChange = onQueryChange,
            onStockFilterChange = onStockFilterChange,
            onCategoryChange = onCategoryChange,
            onLocationChange = onLocationChange,
            onScanInventory = onScanInventory,
            onSortChange = onSortChange,
            onSelectComponent = { componentId ->
                focusManager.clearFocus()
                onSelectComponent(componentId)
                onOpenComponentDetail(componentId)
            },
            onOpenBluetoothPrint = onOpenBluetoothPrint,
            onRequestDeleteComponent = onRequestDeleteComponent,
            onBatchTransfer = onBatchTransfer,
            onQuickMovement = onQuickMovement,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InventoryDetailRoute(
    component: ComponentRecord?,
    recentMovements: List<StockMovementRecord>,
    issuedQuantity: Long,
    allocations: List<com.componentvault.android.model.ComponentAllocationRecord> = emptyList(),
    storageLocations: List<StorageLocationRecord> = emptyList(),
    onDismiss: () -> Unit,
    onEditComponent: (String) -> Unit,
    onGenerateLabel: (ComponentRecord) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onAddComponent: () -> Unit,
) {
    val strings = vaultStrings()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = component?.name ?: strings.inventory.selectedComponentTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text(strings.common.actionBack)
                    }
                },
                actions = {
                    TextButton(onClick = onAddComponent) {
                        Text(strings.common.actionImport)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        InventoryDetailPane(
            detail = InventoryDetailUiState(
                component = component,
                recentMovements = recentMovements,
                issuedQuantity = issuedQuantity,
                allocations = allocations,
            ),
            storageLocations = storageLocations,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding),
            contentPadding = rememberContentPadding(padding, horizontal = 16.dp, vertical = 16.dp),
            adaptiveLayout = rememberInventoryLayoutMode().widthClass != InventoryWidthClass.Compact,
            onEditComponent = onEditComponent,
            onGenerateLabel = onGenerateLabel,
            onRequestDeleteComponent = onRequestDeleteComponent,
            onRecordMovement = onRecordMovement,
        )
    }
}

@Composable
private fun InventoryListPane(
    modifier: Modifier,
    contentPadding: PaddingValues,
    uiState: InventoryScreenUiState,
    statusMessage: String,
    compactRows: Boolean = false,
    listState: LazyListState,
    selectionModeState: MutableState<Boolean>,
    selectedIdsState: MutableState<List<String>>,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onScanInventory: () -> Unit,
    onSortChange: (InventorySortOption) -> Unit,
    onSelectComponent: (String) -> Unit,
    onOpenBluetoothPrint: () -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onBatchTransfer: BatchTransferAction?,
    onQuickMovement: QuickMovementAction?,
) {
    val strings = vaultStrings()
    val diagnostic = inventoryDiagnosticFields(uiState)
    LaunchedEffect(diagnostic) {
        com.componentvault.android.data.AppDiagnostics.record("inventory_render", *diagnostic.toTypedArray())
    }
    var selecting by selectionModeState
    var selectedIds by selectedIdsState
    var transferVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    var quickMovement by androidx.compose.runtime.remember { mutableStateOf<Pair<com.componentvault.android.model.InventoryListItemUiState, Boolean>?>(null) }
    var quickTransferItem by androidx.compose.runtime.remember { mutableStateOf<com.componentvault.android.model.InventoryListItemUiState?>(null) }
    var actionsExpanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(uiState.list.items.map { it.id }) {
        selectedIds = selectedIds.intersect(uiState.list.items.map { it.id }.toSet()).toList()
    }
    val selectedItems = uiState.list.items.filter { it.id in selectedIds }
    fun exitSelection() { selecting = false; selectedIds = emptyList(); transferVisible = false }

    Column(
        modifier = modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InventoryFilterHeader(
            uiState = uiState,
            statusMessage = statusMessage,
            onQueryChange = onQueryChange,
            onStockFilterChange = onStockFilterChange,
            onCategoryChange = onCategoryChange,
            onLocationChange = onLocationChange,
            onSortChange = onSortChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.inventory_ui_result_count, uiState.list.items.size),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onBatchTransfer != null && !selecting) {
                TextButton(onClick = { selecting = true }) {
                    Text(stringResource(R.string.inventory_ui_select))
                }
            }
            if (!selecting) {
                TextButton(onClick = onScanInventory) {
                    Text(stringResource(R.string.location_qr_scan))
                }
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { actionsExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inventory_ui_more_actions))
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.label_tool_title)) },
                            onClick = { actionsExpanded = false; onOpenBluetoothPrint() },
                        )
                    }
                }
            }
        }
        if (selecting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.batch_transfer_selected_count, selectedItems.size),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = ::exitSelection) { Text(stringResource(R.string.action_cancel)) }
                FilledTonalButton(
                    enabled = selectedItems.isNotEmpty(),
                    onClick = { transferVisible = true },
                ) { Text(stringResource(R.string.batch_transfer_select)) }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactRows) 4.dp else 12.dp),
        ) {
            if (uiState.list.items.isEmpty()) {
                item {
                    EmptyPane(if (uiState.totalComponentCount == 0) {
                        stringResource(R.string.batch_transfer_inventory_empty)
                    } else {
                        strings.common.emptyNoComponentsMatchFilter
                    })
                }
            } else {
                items(uiState.list.items, key = { it.id }) { item ->
                    InventoryListRow(
                        item = item,
                        compact = compactRows,
                        storageLocations = uiState.storageLocations,
                        selected = if (selecting) item.id in selectedIds else item.id == uiState.list.selectedComponentId,
                        selectionMode = selecting,
                        onClick = {
                            if (selecting) selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            else onSelectComponent(item.id)
                        },
                        onInbound = if (onQuickMovement == null || selecting) null else ({ quickMovement = item to true }),
                        onOutbound = if (onQuickMovement == null || selecting) null else ({ quickMovement = item to false }),
                        onTransfer = if (onBatchTransfer == null || selecting) null else ({ quickTransferItem = item }),
                        onDelete = if (selecting) null else ({ onRequestDeleteComponent(item.id) }),
                    )
                }
            }
        }
    }
    if (transferVisible && onBatchTransfer != null) {
        BatchTransferDialog(
            items = selectedItems,
            allocations = uiState.allocations,
            locations = uiState.storageLocations,
            onDismiss = ::exitSelection,
            onSubmit = onBatchTransfer,
        )
    }
    quickMovement?.let { (item, inbound) ->
        if (onQuickMovement != null) QuickStockActionDialog(item, inbound,
            uiState.allocations, uiState.storageLocations,
            onDismiss = { quickMovement = null }, onSubmit = onQuickMovement)
    }
    quickTransferItem?.let { item ->
        if (onBatchTransfer != null) BatchTransferDialog(
            items = listOf(item), allocations = uiState.allocations, locations = uiState.storageLocations,
            onDismiss = { quickTransferItem = null }, onSubmit = onBatchTransfer, singleItemMode = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun InventoryFilterHeader(
    uiState: InventoryScreenUiState,
    statusMessage: String,
    onQueryChange: (String) -> Unit,
    onStockFilterChange: (InventoryStockFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onSortChange: (InventorySortOption) -> Unit,
) {
    val strings = vaultStrings()
    var categoryMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var locationMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.filters.query,
            onValueChange = onQueryChange,
            placeholder = { Text(strings.inventory.searchPlaceholder) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.filters.query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.inventory_ui_clear_search))
                    }
                }
            },
            singleLine = true,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = uiState.filters.stockFilter == InventoryStockFilter.All,
                onClick = { onStockFilterChange(InventoryStockFilter.All) },
                label = { Text(strings.inventory.filterAll) },
            )
            FilterChip(
                selected = uiState.filters.stockFilter == InventoryStockFilter.LowStock,
                onClick = { onStockFilterChange(InventoryStockFilter.LowStock) },
                label = { Text(strings.inventory.filterLowStock) },
            )
            FilterMenuButton(
                label = strings.inventory.filterCategoryLabel,
                value = uiState.filters.category
                    ?.let { localizedCategoryLabel(it) }
                    ?: strings.inventory.filterCategoryAll,
                expanded = categoryMenuExpanded,
                onExpandedChange = { categoryMenuExpanded = it },
            ) {
                DropdownMenuItem(
                    text = { FilterMenuItemText(strings.inventory.filterCategoryAll) },
                    onClick = {
                        onCategoryChange(null)
                        categoryMenuExpanded = false
                    },
                )
                uiState.availableCategories.forEach { category ->
                    DropdownMenuItem(
                        text = { FilterMenuItemText(localizedCategoryLabel(category)) },
                        onClick = {
                            onCategoryChange(category)
                            categoryMenuExpanded = false
                        },
                    )
                }
            }
            FilterMenuButton(
                label = strings.inventory.filterLocationLabel,
                value = uiState.filters.location?.let {
                    displayLocationName(it, uiState.storageLocations, strings.common.labelNotConfigured)
                } ?: strings.inventory.filterLocationAll,
                expanded = locationMenuExpanded,
                onExpandedChange = { locationMenuExpanded = it },
            ) {
                DropdownMenuItem(
                    text = { FilterMenuItemText(strings.inventory.filterLocationAll) },
                    onClick = {
                        onLocationChange(null)
                        locationMenuExpanded = false
                    },
                )
                uiState.availableLocations.forEach { location ->
                    DropdownMenuItem(
                        text = { FilterMenuItemText(displayLocationName(location, uiState.storageLocations, strings.common.labelNotConfigured)) },
                        onClick = {
                            onLocationChange(location)
                            locationMenuExpanded = false
                        },
                    )
                }
            }
            FilterMenuButton(
                label = strings.inventory.filterSortLabel,
                value = inventorySortLabel(uiState.filters.sort),
                expanded = sortMenuExpanded,
                onExpandedChange = { sortMenuExpanded = it },
            ) {
                InventorySortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { FilterMenuItemText(inventorySortLabel(option)) },
                        onClick = {
                            onSortChange(option)
                            sortMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterMenuButton(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val maxMenuWidth = minOf(280.dp, (configuration.screenWidthDp - 32).coerceAtLeast(112).dp)
    val maxMenuHeight = minOf(320.dp, configuration.screenHeightDp.dp * 0.45f)

    Box {
        OutlinedButton(
            modifier = Modifier.widthIn(max = maxMenuWidth),
            onClick = { onExpandedChange(true) },
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "$label: $value",
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(max = maxMenuWidth).heightIn(max = maxMenuHeight),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            menuContent()
        }
    }
}

@Composable
private fun FilterMenuItemText(value: String) {
    Text(text = value, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun InventoryDetailPane(
    detail: InventoryDetailUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    storageLocations: List<StorageLocationRecord> = emptyList(),
    adaptiveLayout: Boolean = false,
    onEditComponent: (String) -> Unit,
    onGenerateLabel: (ComponentRecord) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
) {
    val strings = vaultStrings()

    LazyColumn(
        modifier = modifier.testTag("inventory_detail_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val component = detail.component
        if (component == null) {
            item {
                SectionPane(
                    title = strings.inventory.selectedComponentTitle,
                    supporting = strings.inventory.selectedComponentEmptySubtitle,
                ) {
                    EmptyPane(strings.common.emptyNoComponentSelected)
                }
            }
            return@LazyColumn
        }

        item {
            if (adaptiveLayout) {
                InventoryTabletDetailSummary(
                    component = component,
                    onEditComponent = onEditComponent,
                    onGenerateLabel = onGenerateLabel,
                    onRequestDeleteComponent = onRequestDeleteComponent,
                    onRecordMovement = onRecordMovement,
                )
            } else {
                SectionPane(
                    title = component.name,
                    supporting = strings.inventory.componentSubtitle(
                        component.sku,
                        localizedCategoryLabel(component.category),
                        component.packageName,
                    ),
                ) {
                    ProductThumbnail(
                        sku = component.sku,
                        imageUrl = component.productImageUrl,
                        description = component.description,
                        imageSize = 112.dp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    StatusBanner(
                        message = if (component.isLowStock) {
                            strings.inventory.selectedComponentLowStockStatus
                        } else {
                            strings.inventory.selectedComponentHealthyStatus
                        },
                        containerColor = if (component.isLowStock) {
                            VaultWarningContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (component.isLowStock) {
                            VaultWarning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricTile(
                            label = strings.inventory.metricQuantity,
                            value = component.quantity.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = strings.inventory.metricMinStock,
                            value = component.minStock.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = { onEditComponent(component.id) }) {
                            Text(strings.common.actionEdit)
                        }
                        OutlinedButton(
                            onClick = { onRecordMovement(component.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.common.actionRecordMovement)
                        }
                    }
                    OutlinedButton(
                        onClick = { onGenerateLabel(component) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionGenerateLabel)
                    }
                    TextButton(onClick = { onRequestDeleteComponent(component.id) }) {
                        Text(strings.common.actionDelete)
                    }
                }
            }
        }
        item {
            SectionPane(title = strings.inventory.detailBasicTitle) {
                val fields = mutableListOf(
                    strings.common.fieldSku to component.sku,
                    strings.common.fieldName to component.name,
                    strings.common.fieldCategory to localizedCategoryLabel(component.category),
                    strings.common.fieldPackage to component.packageName,
                )
                component.officialParameters().forEach { (key, value) ->
                    val label = when (officialParameterKind(key)) {
                        "resistance" -> stringResource(R.string.inventory_param_resistance)
                        "capacitance" -> stringResource(R.string.inventory_param_capacitance)
                        "inductance" -> stringResource(R.string.inventory_param_inductance)
                        "voltage" -> stringResource(R.string.inventory_param_voltage)
                        "tolerance" -> stringResource(R.string.inventory_param_tolerance)
                        "power" -> stringResource(R.string.inventory_param_power)
                        "operating_temperature" -> stringResource(R.string.inventory_param_operating_temperature)
                        "type" -> stringResource(R.string.inventory_param_type)
                        "temperature_coefficient" -> stringResource(R.string.inventory_param_temperature_coefficient)
                        else -> key
                    }
                    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
                    fields += label to localizedOfficialParameterValue(officialParameterKind(key), value, locale)
                }
                if (adaptiveLayout) {
                    InventoryDetailFieldGrid(fields = fields)
                } else {
                    fields.forEach { (label, value) -> ValueBlock(label = label, value = value) }
                }
            }
        }
        item {
            SectionPane(title = strings.inventory.detailStockTitle) {
                val usage = StockUsageSummary(component.quantity, detail.issuedQuantity)
                ValueBlock(
                    label = androidx.compose.ui.res.stringResource(
                        com.componentvault.android.R.string.inventory_usage_remaining,
                    ),
                    value = component.quantity.toString(),
                )
                ValueBlock(
                    label = androidx.compose.ui.res.stringResource(
                        com.componentvault.android.R.string.inventory_usage_issued,
                    ),
                    value = detail.issuedQuantity.toString(),
                )
                ValueBlock(
                    label = androidx.compose.ui.res.stringResource(
                        com.componentvault.android.R.string.inventory_usage_total,
                    ),
                    value = usage.total.toString(),
                )
                ValueBlock(
                    label = strings.inventory.metricMinStock,
                    value = component.minStock.toString(),
                )
                ValueBlock(
                    label = strings.common.labelUpdated,
                    value = formatShortLocalTimestamp(component.updatedAt),
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        com.componentvault.android.R.string.inventory_usage_explanation,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionPane(title = strings.inventory.detailLocationTitle) {
                if (detail.allocations.isEmpty()) {
                    ValueBlock(
                        label = strings.common.fieldLocation,
                        value = displayLocationName(component.location, storageLocations, strings.common.labelNotConfigured),
                    )
                } else {
                    detail.allocations.forEach { allocation ->
                        ValueBlock(
                            label = allocation.locationName.ifBlank { allocation.locationId },
                            value = allocation.quantity.toString(),
                        )
                    }
                }
            }
        }
        item {
            SectionPane(title = strings.inventory.detailNotesTitle) {
                Text(
                    text = component.description.ifBlank { strings.common.labelNoDescription },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionPane(title = strings.inventory.detailRecentMovementsTitle) {
                if (detail.recentMovements.isEmpty()) {
                    EmptyPane(strings.inventory.detailRecentMovementsEmpty)
                } else {
                    detail.recentMovements.forEachIndexed { index, movement ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MovementTypePill(movement.movementType)
                                MovementQuantityPill(movement)
                            }
                            Text(
                                text = movement.reason.ifBlank { strings.common.labelNoReasonRecorded },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = movement.note.ifBlank { strings.common.labelNoNote },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatShortLocalTimestamp(movement.happenedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
