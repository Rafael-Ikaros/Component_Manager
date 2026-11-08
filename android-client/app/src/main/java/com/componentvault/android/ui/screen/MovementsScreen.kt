package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.MovementBatchQueueItemUiState
import com.componentvault.android.model.MovementQuickAction
import com.componentvault.android.model.MovementScanMatchStatus
import com.componentvault.android.model.MovementScanUiState
import com.componentvault.android.model.MovementsUiState
import com.componentvault.android.model.StockMovementRecord
import kotlinx.coroutines.launch

internal fun visibleMovementsForActiveComponents(
    items: List<StockMovementRecord>,
    activeComponentIds: Set<String>?,
    includeDeletedComponents: Boolean,
): List<StockMovementRecord> =
    if (includeDeletedComponents || activeComponentIds == null) items
    else items.filter { it.componentId in activeComponentIds }
@Composable
internal fun MovementsScreen(
    contentPadding: PaddingValues,
    uiState: MovementsUiState,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    onSelectMovement: (String) -> Unit,
    onOpenMovementDetail: (String) -> Unit,
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
    onImportComponent: () -> Unit,
    onRecordMovement: () -> Unit,
) {
    MovementsContent(
        contentPadding = contentPadding,
        uiState = uiState,
        statusMessage = statusMessage,
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
        onRecordMovement = onRecordMovement,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun MovementsContent(
    contentPadding: PaddingValues,
    uiState: MovementsUiState,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    onSelectMovement: (String) -> Unit,
    onOpenMovementDetail: (String) -> Unit,
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
    onImportComponent: () -> Unit,
    onRecordMovement: () -> Unit,
) {
    var includeDeletedComponents by rememberSaveable { mutableStateOf(false) }
    val visibleItems = visibleMovementsForActiveComponents(uiState.items, uiState.activeComponentIds, includeDeletedComponents)
    val hiddenDeletedCount = uiState.items.size -
        visibleMovementsForActiveComponents(uiState.items, uiState.activeComponentIds, false).size
    val visibleState = uiState.copy(
        items = visibleItems,
        selectedMovementId = uiState.selectedMovementId?.takeIf { selectedId ->
            visibleItems.any { it.id == selectedId }
        },
    )
    val effectiveSelectedMovement = visibleItems.firstOrNull { it.id == uiState.selectedMovementId }
        ?: visibleItems.firstOrNull()

    if (layoutMode.showsListDetail) {
        val navigator = rememberListDetailPaneScaffoldNavigator<String>()
        val scope = rememberCoroutineScope()

        LaunchedEffect(effectiveSelectedMovement?.id) {
            val movementId = effectiveSelectedMovement?.id ?: return@LaunchedEffect
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, movementId)
        }

        NavigableListDetailPaneScaffold(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(contentPadding)
                .padding(rememberContentPadding(contentPadding, horizontal = 20.dp, vertical = 20.dp)),
            navigator = navigator,
            listPane = {
                AnimatedPane {
                    MovementMasterPane(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(),
                        uiState = visibleState,
                        hiddenDeletedCount = hiddenDeletedCount,
                        includeDeletedComponents = includeDeletedComponents,
                        onIncludeDeletedChange = { includeDeletedComponents = it },
                        statusMessage = statusMessage,
                        onSelectMovement = { movementId ->
                            onSelectMovement(movementId)
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, movementId)
                            }
                        },
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
                        onRecordMovement = onRecordMovement,
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    MovementDetailPane(
                        movement = effectiveSelectedMovement,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    } else {
        MovementMasterPane(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(contentPadding),
            contentPadding = rememberContentPadding(contentPadding, horizontal = 16.dp, vertical = 16.dp),
            uiState = visibleState,
            hiddenDeletedCount = hiddenDeletedCount,
            includeDeletedComponents = includeDeletedComponents,
            onIncludeDeletedChange = { includeDeletedComponents = it },
            statusMessage = statusMessage,
            onSelectMovement = { movementId ->
                onSelectMovement(movementId)
                onOpenMovementDetail(movementId)
            },
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
            onRecordMovement = onRecordMovement,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MovementDetailRoute(
    movement: StockMovementRecord?,
    onDismiss: () -> Unit,
) {
    val strings = vaultStrings()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = movement?.componentName ?: strings.movements.detailTitle,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text(strings.common.actionBack)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        MovementDetailPane(
            movement = movement,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding),
            contentPadding = rememberContentPadding(padding, horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MovementBatchReviewRoute(
    uiState: MovementsUiState,
    onDismiss: () -> Unit,
    onScanMovementLabel: () -> Unit,
    onCommitMovementBatch: () -> Unit,
    onDiscardMovementBatch: () -> Unit,
    onUpdateMovementBatchItemMovementType: (String, String) -> Unit,
    onUpdateMovementBatchItemQuantity: (String, String) -> Unit,
    onUpdateMovementBatchItemReason: (String, String) -> Unit,
    onUpdateMovementBatchItemNote: (String, String) -> Unit,
    onUpdateMovementBatchItemPurchaseAmount: (String, String) -> Unit = { _, _ -> },
    onRemoveMovementBatchItem: (String) -> Unit,
    onUpdateMovementBatchItemLocation: (String, String) -> Unit = { _, _ -> },
) {
    val strings = vaultStrings()
    BackHandler(onBack = onDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.movements.batchReviewTitle) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text(strings.common.actionBack) }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = rememberContentPadding(padding, horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MovementBatchReviewSummaryPane(
                    uiState = uiState,
                    onScanMovementLabel = onScanMovementLabel,
                    onCommitMovementBatch = onCommitMovementBatch,
                    onDiscardMovementBatch = onDiscardMovementBatch,
                )
            }
            items(uiState.batchSession.queuedItems, key = { it.componentId }) { item ->
                MovementBatchQueueItemCard(
                    item = item,
                    onMovementTypeChange = { onUpdateMovementBatchItemMovementType(item.componentId, it) },
                    onQuantityChange = { onUpdateMovementBatchItemQuantity(item.componentId, it) },
                    onReasonChange = { onUpdateMovementBatchItemReason(item.componentId, it) },
                    onNoteChange = { onUpdateMovementBatchItemNote(item.componentId, it) },
                    onPurchaseChange = { onUpdateMovementBatchItemPurchaseAmount(item.componentId, it) },
                    onLocationChange = { onUpdateMovementBatchItemLocation(item.componentId, it) },
                    onRemove = { onRemoveMovementBatchItem(item.componentId) },
                )
            }
        }
    }
}

@Composable
private fun MovementMasterPane(
    modifier: Modifier,
    contentPadding: PaddingValues,
    uiState: MovementsUiState,
    hiddenDeletedCount: Int,
    includeDeletedComponents: Boolean,
    onIncludeDeletedChange: (Boolean) -> Unit,
    statusMessage: String,
    onSelectMovement: (String) -> Unit,
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
    onImportComponent: () -> Unit,
    onRecordMovement: () -> Unit,
) {
    val strings = vaultStrings()

    LazyColumn(
        modifier = modifier.testTag("movements_master_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MovementQuickEntryPane(
                statusMessage = statusMessage,
                scanState = uiState.scan,
                hasBatchItems = uiState.batchSession.queuedItems.isNotEmpty(),
                onScanMovementLabel = onScanMovementLabel,
                onRetryMovementScan = onRetryMovementScan,
                onDismissMovementScanResult = onDismissMovementScanResult,
                onSearchInventoryBySku = onSearchInventoryBySku,
                onImportComponent = onImportComponent,
                onRecordMovement = onRecordMovement,
            )
        }

        if (uiState.activeComponentIds != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.movements_include_deleted_components, hiddenDeletedCount),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includeDeletedComponents, onCheckedChange = onIncludeDeletedChange,
                        modifier = Modifier.testTag("movements_include_deleted_components"))
                }
            }
        }
        if (uiState.batchSession.queuedItems.isNotEmpty()) {
            item {
                OutlinedButton(onClick = onOpenMovementBatchReview, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.movements.batchReviewTitle)
                }
            }
        }

        item {
            Text(
                text = strings.movements.resultsSummary(
                    uiState.items.size,
                    uiState.componentCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.items.isEmpty()) {
            item {
                EmptyPane(strings.common.emptyNoMovements)
            }
        } else {
            items(uiState.items, key = { it.id }) { movement ->
                MovementHistoryRow(
                    movement = movement,
                    selected = movement.id == uiState.selectedMovementId,
                    componentDeleted = uiState.activeComponentIds?.let { movement.componentId !in it } == true,
                    onClick = { onSelectMovement(movement.id) },
                )
            }
        }
    }
}

@Composable
private fun MovementQuickEntryPane(
    statusMessage: String,
    scanState: MovementScanUiState,
    hasBatchItems: Boolean,
    onScanMovementLabel: () -> Unit,
    onRetryMovementScan: () -> Unit,
    onDismissMovementScanResult: () -> Unit,
    onSearchInventoryBySku: (String) -> Unit,
    onImportComponent: () -> Unit,
    onRecordMovement: () -> Unit,
) {
    val strings = vaultStrings()
    val scanActionLabel = if (hasBatchItems) {
        strings.movements.batchContinueAction
    } else {
        strings.movements.quickScanAction
    }

    SectionPane(
        title = strings.movements.recordTitle,
        supporting = strings.movements.recordSubtitle,
    ) {
        FilledTonalButton(
            onClick = onScanMovementLabel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(scanActionLabel)
        }
        OutlinedButton(
            onClick = onRecordMovement,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.movements.quickManualAction)
        }

        when {
            scanState.isResolving -> {
                SectionPane(
                    title = strings.movements.scanResolvingTitle,
                    supporting = strings.movements.scanResolvingSubtitle,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(
                            text = strings.movements.scanResolvingSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            scanState.resolution.matchStatus == MovementScanMatchStatus.InvalidLabel -> {
                SectionPane(
                    title = strings.movements.invalidLabelTitle,
                    supporting = strings.movements.invalidLabelDescription,
                ) {
                    OutlinedButton(
                        onClick = onRetryMovementScan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.importer.actionRetryScan)
                    }
                    OutlinedButton(
                        onClick = onImportComponent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionImport)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismissMovementScanResult()
                            onRecordMovement()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionRecordMovement)
                    }
                }
            }

            scanState.resolution.matchStatus == MovementScanMatchStatus.NotFound -> {
                SectionPane(
                    title = strings.movements.notFoundTitle,
                    supporting = strings.movements.notFoundDescription,
                ) {
                    scanState.resolution.parsedSku.takeIf { it.isNotBlank() }?.let { sku ->
                        ValueBlock(label = strings.common.fieldSku, value = sku)
                    }
                    scanState.resolution.parsedName.takeIf { it.isNotBlank() }?.let { name ->
                        ValueBlock(label = strings.common.fieldName, value = name)
                    }
                    scanState.resolution.parsedPackageName.takeIf { it.isNotBlank() }?.let { packageName ->
                        ValueBlock(label = strings.common.fieldPackage, value = packageName)
                    }
                    scanState.resolution.parsedLocation.takeIf { it.isNotBlank() }?.let { location ->
                        ValueBlock(label = strings.common.fieldLocation, value = location)
                    }
                    OutlinedButton(
                        onClick = onRetryMovementScan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.importer.actionRetryScan)
                    }
                    scanState.resolution.parsedSku.takeIf { it.isNotBlank() }?.let { sku ->
                        OutlinedButton(
                            onClick = { onSearchInventoryBySku(sku) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.common.actionViewInventory)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onDismissMovementScanResult()
                            onRecordMovement()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionRecordMovement)
                    }
                }
            }

            scanState.resolution.matchStatus == MovementScanMatchStatus.Ambiguous -> {
                SectionPane(
                    title = strings.movements.ambiguousTitle,
                    supporting = strings.movements.ambiguousDescription,
                ) {
                    scanState.resolution.parsedSku.takeIf { it.isNotBlank() }?.let { sku ->
                        ValueBlock(label = strings.common.fieldSku, value = sku)
                    }
                    OutlinedButton(
                        onClick = onRetryMovementScan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.importer.actionRetryScan)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismissMovementScanResult()
                            onRecordMovement()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionRecordMovement)
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun MovementBatchReviewSummaryPane(
    uiState: MovementsUiState,
    onScanMovementLabel: () -> Unit,
    onCommitMovementBatch: () -> Unit,
    onDiscardMovementBatch: () -> Unit,
) {
    val strings = vaultStrings()
    val batchSession = uiState.batchSession

    SectionPane(
        title = strings.movements.batchReviewTitle,
        supporting = strings.movements.batchSummary(
            batchSession.queuedItems.size,
            batchSession.totalScans,
        ),
    ) {
        batchSession.lastQueuedComponentName.takeIf { it.isNotBlank() }?.let { componentName ->
            Text(
                text = strings.movements.batchLastQueued(
                    componentName,
                    batchSession.lastQueuedComponentSku,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onCommitMovementBatch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.movements.batchSaveAction)
        }
        OutlinedButton(
            onClick = onScanMovementLabel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.movements.batchContinueAction)
        }
        OutlinedButton(
            onClick = onDiscardMovementBatch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.movements.batchDiscardAction)
        }
    }
}

@Composable
private fun MovementBatchQueueItemCard(
    item: MovementBatchQueueItemUiState,
    onMovementTypeChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onPurchaseChange: (String) -> Unit = {},
    onLocationChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = vaultStrings()
    val projectedQuantity = projectedQuantity(item)

    SectionPane(
        title = item.componentName,
        supporting = item.componentSku,
    ) {
        Text(
            text = strings.movements.batchQueuedScanCount(item.scanCount),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ValueBlock(label = strings.common.fieldCategory, value = localizedCategoryLabel(item.category))
        ValueBlock(label = strings.common.fieldPackage, value = item.packageName)
        var locationMenuExpanded by rememberSaveable { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { locationMenuExpanded = true }) {
                Text("${strings.common.fieldLocation}: ${item.locationNames[item.location] ?: item.location} (${item.locationQuantities[item.location] ?: 0})")
            }
            androidx.compose.material3.DropdownMenu(
                expanded = locationMenuExpanded,
                onDismissRequest = { locationMenuExpanded = false },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                item.locationQuantities.forEach { (location, quantity) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("${item.locationNames[location] ?: location} ($quantity)") },
                        onClick = { onLocationChange(location); locationMenuExpanded = false },
                    )
                }
            }
        }
        ValueBlock(label = strings.movements.currentStockLabel, value = item.currentStock.toString())
        ValueBlock(label = strings.movements.minimumStockLabel, value = item.minStock.toString())
        projectedQuantity?.let { quantity ->
            ValueBlock(label = strings.movements.batchProjectedStockLabel, value = quantity.toString())
        }
        MovementTypeSelector(
            movementType = item.movementType,
            onMovementTypeChange = onMovementTypeChange,
        )
        QuantityInputField(
            value = item.quantityText,
            onValueChange = onQuantityChange,
            modifier = Modifier.fillMaxWidth(),
            label = strings.common.fieldQuantity,
            minimum = if (item.movementType == MovementQuickAction.Adjustment.movementType) Int.MIN_VALUE else 1,
            maximum = if (item.movementType == MovementQuickAction.Outbound.movementType)
                (item.locationQuantities[item.location] ?: 0).coerceAtLeast(0) else Int.MAX_VALUE,
            skipZero = item.movementType == MovementQuickAction.Adjustment.movementType,
        )
        if (item.movementType == "inbound") OutlinedTextField(item.purchaseAmount, onPurchaseChange, label = { Text("实际采购总额（CNY，留空未知）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = item.reason,
            onValueChange = onReasonChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.common.fieldReason) },
            singleLine = true,
        )
        OutlinedTextField(
            value = item.note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.common.fieldNote) },
            minLines = 2,
        )
        Text(
            text = strings.forms.movementEditorInstruction,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.movements.batchRemoveAction)
        }
    }
}

private fun projectedQuantity(
    item: MovementBatchQueueItemUiState,
): Int? {
    val quantity = item.quantityText.toIntOrNull() ?: return null
    return when (item.movementType) {
        MovementQuickAction.Inbound.movementType -> item.currentStock + quantity
        MovementQuickAction.Outbound.movementType -> item.currentStock - quantity
        MovementQuickAction.Adjustment.movementType -> item.currentStock + quantity
        else -> null
    }
}

@Composable
private fun MovementDetailPane(
    movement: StockMovementRecord?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
) {
    val strings = vaultStrings()

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (movement == null) {
            item {
                SectionPane(
                    title = strings.movements.detailTitle,
                    supporting = strings.movements.detailEmptySupporting,
                ) {
                    EmptyPane(strings.movements.detailEmpty)
                }
            }
            return@LazyColumn
        }

        item {
            SectionPane(
                title = movement.componentName,
                supporting = movement.componentSku,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MovementTypePill(movement.movementType)
                    MovementQuantityPill(movement)
                }
                ValueBlock(label = "实际采购金额（CNY）", value = movement.purchaseAmount ?: "未知")
                ValueBlock(label = "库存结转/估值金额（CNY）", value = movement.costAmount ?: "未知")
                ValueBlock(
                    label = strings.movements.detailHappenedAt,
                    value = formatShortLocalTimestamp(movement.happenedAt),
                )
                ValueBlock(
                    label = strings.common.fieldReason,
                    value = movement.reason.ifBlank { strings.common.labelNoReasonRecorded },
                )
                ValueBlock(
                    label = strings.common.fieldNote,
                    value = movement.note.ifBlank { strings.common.labelNoNote },
                )
                Text(
                    text = strings.movements.updatedAt(formatShortLocalTimestamp(movement.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
