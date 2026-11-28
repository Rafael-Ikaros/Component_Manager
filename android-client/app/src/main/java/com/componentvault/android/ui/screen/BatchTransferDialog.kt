package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.BatchTransferLine
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationRecord

internal typealias BatchTransferAction =
    (String, String, List<BatchTransferLine>, (OperationResult) -> Unit) -> Unit

@Composable
internal fun BatchTransferDialog(
    items: List<InventoryListItemUiState>,
    allocations: List<ComponentAllocationRecord>,
    locations: List<StorageLocationRecord>,
    onDismiss: () -> Unit,
    onSubmit: BatchTransferAction,
    singleItemMode: Boolean = false,
) {
    val sourceOptions = locations.filter { location ->
        items.isNotEmpty() && items.all { item ->
            allocations.any {
                it.componentId == item.id && it.locationId == location.id && it.quantity > 0
            }
        }
    }
    var sourceId by remember(items, allocations) { mutableStateOf(sourceOptions.firstOrNull()?.id.orEmpty()) }
    var destinationId by remember(items, locations) {
        mutableStateOf(locations.firstOrNull { it.id != sourceId }?.id.orEmpty())
    }
    var quantities by remember(sourceId, items, allocations) {
        mutableStateOf(items.associate { item ->
            item.id to allocations.firstOrNull {
                it.componentId == item.id && it.locationId == sourceId
            }?.quantity?.takeIf { it > 0 }?.toString().orEmpty()
        })
    }
    var reviewing by remember { mutableStateOf(false) }
    var reviewedLines by remember { mutableStateOf<List<BatchTransferLine>?>(null) }
    var reviewedSourceId by remember { mutableStateOf("") }
    var reviewedDestinationId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val availableById = items.associate { item ->
        item.id to (allocations.firstOrNull {
            it.componentId == item.id && it.locationId == sourceId
        }?.quantity ?: 0)
    }
    val valid = sourceId.isNotBlank() && destinationId.isNotBlank() && sourceId != destinationId &&
        items.isNotEmpty() && items.all { item ->
            val quantity = quantities[item.id]?.toIntOrNull()
            quantity != null && quantity > 0 && quantity <= availableById.getValue(item.id)
        }

    val layoutMode = rememberInventoryLayoutMode()
    val leave = {
        if (!busy) {
            if (reviewing) {
                reviewing = false
                reviewedLines = null
                error = ""
            } else onDismiss()
        }
    }
    BackHandler(enabled = !layoutMode.prefersDialogForms, onBack = leave)
    AdaptiveFormSurface(
        modal = true,
        caption = stringResource(if (reviewing) R.string.prototype_movement_review_hint else R.string.v2_transfer_hint),
        title = stringResource(when {
            reviewing -> R.string.v2_confirm_transfer
            singleItemMode -> R.string.inventory_quick_transfer
            else -> R.string.batch_transfer_title
        }),
        layoutMode = layoutMode,
        onDismiss = leave,
        footerDismissLabel = stringResource(if (reviewing) R.string.prototype_movement_modify else R.string.action_cancel),
        footerSaveLabel = stringResource(if (reviewing) R.string.batch_transfer_submit else R.string.batch_transfer_review),
        footerTestTag = "transfer_confirm".takeIf { reviewing },
        dismissEnabled = !busy,
        onCancelReview = onDismiss.takeIf { reviewing },
        saveEnabled = valid && !busy,
        onSave = {
            if (valid && !busy) {
                if (!reviewing) {
                    reviewedLines = items.map { item ->
                        BatchTransferLine(item.id, requireNotNull(quantities[item.id]?.toIntOrNull()), item.updatedAt)
                    }
                    reviewedSourceId = sourceId
                    reviewedDestinationId = destinationId
                    reviewing = true
                    error = ""
                } else {
                    busy = true
                    onSubmit(reviewedSourceId, reviewedDestinationId, requireNotNull(reviewedLines)) { result ->
                        busy = false
                        if (result.isSuccess) onDismiss() else error = result.message
                    }
                }
            }
        },
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (singleItemMode) {
                    Text(items.singleOrNull()?.sku.orEmpty(), modifier = Modifier.testTag("inventory_quick_transfer_sku"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(stringResource(R.string.batch_transfer_count, items.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!reviewing) {
                    if (locations.size < 2) Text(stringResource(R.string.batch_transfer_need_two_locations))
                    if (sourceOptions.isEmpty()) Text(stringResource(R.string.batch_transfer_no_common_source))
                    LocationPicker(
                        label = stringResource(R.string.batch_transfer_source),
                        selectedId = sourceId,
                        options = sourceOptions,
                        enabled = !busy,
                        onSelect = {
                            sourceId = it
                            if (destinationId == it) {
                                destinationId = locations.firstOrNull { location -> location.id != it }?.id.orEmpty()
                            }
                        },
                    )
                    LocationPicker(
                        label = stringResource(R.string.batch_transfer_destination),
                        selectedId = destinationId,
                        options = locations.filter { it.id != sourceId },
                        enabled = !busy,
                        onSelect = { destinationId = it },
                    )
                    Text(stringResource(R.string.inventory_ui_transfer_items), style = MaterialTheme.typography.titleSmall)
                    items.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${item.name} · ${item.sku}", style = MaterialTheme.typography.bodyMedium)
                            QuantityInputField(
                                value = quantities[item.id].orEmpty(),
                                onValueChange = { value ->
                                    if (value.all(Char::isDigit)) quantities = quantities + (item.id to value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.batch_transfer_quantity),
                                minimum = 1,
                                maximum = availableById.getValue(item.id),
                                enabled = !busy,
                            )
                            Text(stringResource(R.string.batch_transfer_available, availableById.getValue(item.id)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else {
                    MovementReviewNote()
                    val from = locations.firstOrNull { it.id == reviewedSourceId }?.name ?: reviewedSourceId
                    val to = locations.firstOrNull { it.id == reviewedDestinationId }?.name ?: reviewedDestinationId
                    items.forEach { item ->
                        val count = reviewedLines.orEmpty().firstOrNull { it.componentId == item.id }?.quantity
                        if (count != null) {
                            val sourceQuantity = allocations.firstOrNull {
                                it.componentId == item.id && it.locationId == reviewedSourceId
                            }?.quantity ?: 0
                            val destinationQuantity = allocations.firstOrNull {
                                it.componentId == item.id && it.locationId == reviewedDestinationId
                            }?.quantity ?: 0
                            Column {
                                Text(item.name, Modifier.padding(top = 12.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.titleSmall)
                                MovementReviewRow(stringResource(R.string.batch_transfer_quantity), count.toString())
                                MovementReviewRow(stringResource(R.string.batch_transfer_source),
                                    "$from · ${stockChangeText(sourceQuantity, -count.toLong())}")
                                MovementReviewRow(stringResource(R.string.batch_transfer_destination),
                                    "$to · ${stockChangeText(destinationQuantity, count.toLong())}")
                                MovementReviewRow(stringResource(R.string.prototype_movement_total),
                                    stockChangeText(item.quantity, 0L))
                            }
                        }
                    }
                    Text(stringResource(if (singleItemMode) R.string.inventory_quick_transfer_review
                        else R.string.batch_transfer_review_prompt),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LocationPicker(
    label: String,
    selectedId: String,
    options: List<StorageLocationRecord>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    options.firstOrNull { it.id == selectedId }?.name ?: "—",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { location ->
                    DropdownMenuItem(
                        text = { Text(location.name) },
                        onClick = { onSelect(location.id); expanded = false },
                    )
                }
            }
        }
    }
}
