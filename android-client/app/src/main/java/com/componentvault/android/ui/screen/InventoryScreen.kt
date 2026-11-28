package com.componentvault.android.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.componentvault.android.ui.theme.VaultPageTitle
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
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
import com.componentvault.android.model.StorageLocationRecord
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
internal enum class InventoryDetailAction { Inbound, Outbound, Transfer }

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
    onPrintSelectedComponents: ((List<String>) -> Unit)? = null,
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onBatchTransfer: BatchTransferAction? = null,
    onQuickMovement: QuickMovementAction? = null,
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
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
        onPrintSelectedComponents = onPrintSelectedComponents,
        onEditComponent = onEditComponent,
        onRequestDeleteComponent = onRequestDeleteComponent,
        onRecordMovement = onRecordMovement,
        onBatchTransfer = onBatchTransfer,
        onQuickMovement = onQuickMovement,
        onDetailAction = onDetailAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class, ExperimentalLayoutApi::class)
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
    onPrintSelectedComponents: ((List<String>) -> Unit)? = null,
    onEditComponent: (String) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onBatchTransfer: BatchTransferAction? = null,
    onQuickMovement: QuickMovementAction? = null,
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val imeOccludesBottom = WindowInsets.isImeVisible &&
        WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
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
                .padding(rememberContentPadding(contentPadding, horizontal = 20.dp, vertical = 20.dp,
                    bottom = if (imeOccludesBottom) 0.dp else 20.dp)),
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
                            onPrintSelectedComponents = onPrintSelectedComponents,
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
                                onEditComponent = onEditComponent,
                                onGenerateLabel = { component -> onGenerateLabel(component.id) },
                                onRequestDeleteComponent = onRequestDeleteComponent,
                                onRecordMovement = onRecordMovement,
                                onDetailAction = onDetailAction,
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
            contentPadding = rememberContentPadding(contentPadding, horizontal = 16.dp, vertical = 12.dp,
                bottom = if (imeOccludesBottom) 0.dp else 12.dp),
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
            onPrintSelectedComponents = onPrintSelectedComponents,
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
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
) {
    val strings = vaultStrings()
    var actionsExpanded by rememberSaveable(component?.id) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.inventory.selectedComponentTitle,
                        style = VaultPageTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.common.actionBack)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionsExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inventory_ui_more_actions))
                        }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            DropdownMenuItem(text = { Text(strings.common.actionImport) },
                                onClick = { actionsExpanded = false; onAddComponent() })
                            component?.let { current ->
                                DropdownMenuItem(text = { Text(strings.common.actionDelete, color = MaterialTheme.colorScheme.error) },
                                    onClick = { actionsExpanded = false; onRequestDeleteComponent(current.id) })
                            }
                        }
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
            onEditComponent = onEditComponent,
            onGenerateLabel = onGenerateLabel,
            onRequestDeleteComponent = onRequestDeleteComponent,
            onRecordMovement = onRecordMovement,
            onDetailAction = onDetailAction,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    onPrintSelectedComponents: ((List<String>) -> Unit)?,
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
    LaunchedEffect(uiState.isLoaded, uiState.list.items.map { it.id }) {
        if (uiState.isLoaded) {
            selectedIds = selectedIds.intersect(uiState.list.items.map { it.id }.toSet()).toList()
        }
    }
    val selectedItems = uiState.list.items.filter { it.id in selectedIds }
    fun exitSelection() { selecting = false; selectedIds = emptyList(); transferVisible = false }

    LazyColumn(
        modifier = modifier.padding(contentPadding).testTag("inventory_list"),
        // The loading header must not clamp a restored position against an empty data set.
        state = if (uiState.isLoaded) listState else rememberLazyListState(),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InventoryFilterHeader(
                    uiState = uiState,
                    statusMessage = statusMessage,
                    onQueryChange = onQueryChange,
                    onStockFilterChange = onStockFilterChange,
                    onCategoryChange = onCategoryChange,
                    onLocationChange = onLocationChange,
                    onSortChange = onSortChange,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.inventory_ui_result_count, uiState.list.items.size),
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if ((onBatchTransfer != null || onPrintSelectedComponents != null) && !selecting) {
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
                    Text(stringResource(R.string.batch_transfer_selected_count, selectedItems.size),
                        style = MaterialTheme.typography.titleSmall)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = ::exitSelection, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        if (onBatchTransfer != null) FilledTonalButton(
                            enabled = selectedItems.isNotEmpty(),
                            onClick = { transferVisible = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.batch_transfer_select)) }
                        if (onPrintSelectedComponents != null) {
                            TextButton(onClick = { selectedIds = uiState.list.items.map { it.id } },
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.label_batch_select_results))
                            }
                            FilledTonalButton(
                                enabled = selectedItems.isNotEmpty(),
                                onClick = { onPrintSelectedComponents(selectedItems.map { it.id }) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("inventory_print_selected"),
                            ) { Text(stringResource(R.string.label_batch_print_selected)) }
                        }
                    }
                }
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val focusManager = LocalFocusManager.current
    val searchLabel = strings.inventory.searchPlaceholder
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth().testTag("inventory_search")
                .semantics { contentDescription = searchLabel },
            value = uiState.filters.query,
            onValueChange = onQueryChange,
            singleLine = true,
            interactionSource = searchInteraction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { field ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (searchFocused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            if (uiState.filters.query.isEmpty()) Text(searchLabel,
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                        if (uiState.filters.query.isNotBlank()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.inventory_ui_clear_search))
                            }
                        }
                    }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("inventory_filter_bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                border = null,
                shape = RoundedCornerShape(12.dp),
                colors = inventoryFilterChipColors(),
                selected = uiState.filters.stockFilter == InventoryStockFilter.All,
                onClick = { onStockFilterChange(InventoryStockFilter.All) },
                label = { Text(strings.inventory.filterAll) },
            )
            FilterChip(
                border = null,
                shape = RoundedCornerShape(12.dp),
                colors = inventoryFilterChipColors(),
                selected = uiState.filters.stockFilter == InventoryStockFilter.LowStock,
                onClick = { onStockFilterChange(InventoryStockFilter.LowStock) },
                label = { Text(strings.inventory.filterLowStock) },
            )
            InventoryDropdownFilters(
                uiState = uiState, category = uiState.filters.category,
                location = uiState.filters.location, sort = uiState.filters.sort,
                onCategoryChange = onCategoryChange, onLocationChange = onLocationChange,
                onSortChange = onSortChange,
            )
        }
    }
}

@Composable
private fun InventoryDropdownFilters(
    uiState: InventoryScreenUiState,
    category: String?,
    location: String?,
    sort: InventorySortOption,
    onCategoryChange: (String?) -> Unit,
    onLocationChange: (String?) -> Unit,
    onSortChange: (InventorySortOption) -> Unit,
) {
    val strings = vaultStrings()
    var categoryMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var locationMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    FilterMenuButton(
        label = strings.inventory.filterCategoryLabel,
        value = category
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
        value = location?.let {
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
        value = inventorySortLabel(sort),
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
        FilterChip(
            modifier = Modifier.widthIn(max = maxMenuWidth).semantics { contentDescription = "$label: $value" },
            selected = expanded,
            onClick = { onExpandedChange(true) },
            border = null,
            shape = RoundedCornerShape(12.dp),
            colors = inventoryFilterChipColors(),
            label = { Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
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
private fun inventoryFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
private fun FilterMenuItemText(value: String) {
    Text(text = value, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private val detailPhotoReferenceLine = Regex("本地图片：[0-9a-f]{64}\\.(?:png|jpg)")

@Composable
internal fun InventoryDetailPane(
    detail: InventoryDetailUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    storageLocations: List<StorageLocationRecord> = emptyList(),
    onEditComponent: (String) -> Unit,
    onGenerateLabel: (ComponentRecord) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
) {
    val strings = vaultStrings()
    val priceLanguage = if (LocalConfiguration.current.locales[0].language.equals("zh", ignoreCase = true))
        com.componentvault.android.model.AppLanguage.ZhCn else com.componentvault.android.model.AppLanguage.English

    LazyColumn(
        modifier = modifier.testTag("inventory_detail_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
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

        item(key = "identity_${component.id}") { InventoryDetailIdentity(component) }
        item(key = "stock_${component.id}") {
            InventoryTabletDetailSummary(
                component = component,
                onEditComponent = onEditComponent,
                onGenerateLabel = onGenerateLabel,
                onRequestDeleteComponent = onRequestDeleteComponent,
                onRecordMovement = onRecordMovement,
                onDetailAction = onDetailAction,
            )
        }
        item {
            InventoryDetailSection(title = strings.inventory.detailBasicTitle) {
                val fields = mutableListOf(
                    strings.common.fieldSku to component.sku,
                    strings.common.fieldPackage to component.packageName,
                    strings.common.fieldCategory to localizedCategoryLabel(component.category),
                    strings.common.labelUpdated to formatShortLocalTimestamp(component.updatedAt),
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
                InventoryDetailFieldGrid(fields = fields)
            }
        }
        item {
            InventoryDetailSection(title = strings.inventory.detailLocationTitle) {
                if (detail.allocations.isEmpty()) {
                    ValueBlock(
                        label = strings.common.fieldLocation,
                        value = displayLocationName(component.location, storageLocations, strings.common.labelNotConfigured),
                    )
                } else {
                    detail.allocations.forEach { allocation ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            Text(allocation.locationName.ifBlank { allocation.locationId },
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(allocation.quantity.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item(key = "cost_${component.id}") {
            InventoryDetailSection(title = stringResource(R.string.inventory_actual_cost_title),
                modifier = Modifier.testTag("inventory_detail_actual_cost")) {
                InventoryDetailFieldGrid(listOf(
                    stringResource(R.string.inventory_unit_cost_cny) to
                        (component.inventoryUnitCost ?: stringResource(R.string.inventory_cost_unknown)),
                    stringResource(R.string.inventory_value_cny) to
                        (com.componentvault.android.data.InventoryCost.amount(component.inventoryUnitCost, component.quantity)
                            ?.let { java.math.BigDecimal(it).setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString() }
                            ?: stringResource(R.string.inventory_cost_unknown)),
                ))
            }
        }
        com.componentvault.android.model.ReferencePriceNotes.display(
            component.description, priceLanguage, { formatShortLocalTimestamp(it) },
        )?.let { price ->
            item(key = "reference_${component.id}") {
                InventoryDetailSection(title = stringResource(R.string.catalog_reference_price)) {
                    Text(price, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            InventoryDetailSection(title = strings.inventory.detailNotesTitle) {
                val notes = remember(component.description) {
                    component.description.lineSequence()
                        .filterNot(detailPhotoReferenceLine::matches)
                        .joinToString("\n")
                }
                Text(
                    text = notes.ifBlank { strings.common.labelNoDescription },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "usage_${component.id}") {
            InventoryDetailSection(title = stringResource(R.string.inventory_usage_title)) {
                InventoryDetailFieldGrid(listOf(
                    stringResource(R.string.inventory_usage_remaining) to component.quantity.toString(),
                    stringResource(R.string.inventory_usage_issued) to detail.issuedQuantity.toString(),
                ))
            }
        }
        item {
            InventoryDetailSection(title = strings.inventory.detailRecentMovementsTitle) {
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
