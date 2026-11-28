package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.ui.theme.LocalVaultWarning

/** The caller retains selection and swipe action handling. */
@Composable
internal fun InventoryTabletListRowContent(
    item: InventoryListItemUiState,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    selectionMode: Boolean,
    storageLocations: List<StorageLocationRecord>,
    actions: List<InventoryRowAction> = emptyList(),
    showOverflow: Boolean = true,
) {
    InventoryFlatListRowContent(item, selected, modifier, onClick, selectionMode,
        storageLocations, actions, compact = true, showOverflow = showOverflow)
}

@Composable
internal fun InventoryDetailIdentity(component: ComponentRecord) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    SectionPane(title = null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp || largeText) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProductThumbnail(sku = component.sku, imageUrl = component.productImageUrl,
                        description = component.description, imageSize = 80.dp)
                    InventoryDetailIdentityText(component)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    ProductThumbnail(sku = component.sku, imageUrl = component.productImageUrl,
                        description = component.description, imageSize = 80.dp)
                    Column(Modifier.weight(1f)) { InventoryDetailIdentityText(component) }
                }
            }
        }
    }
}

@Composable
private fun InventoryDetailIdentityText(component: ComponentRecord) {
    val identity = remember(component.description) { com.componentvault.android.model.parseImportDescription(component.description) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(component.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        identity.model?.takeIf { it != component.name }?.let { model ->
            Text(model, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Text(component.sku, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(listOfNotNull(identity.brand, localizedCategoryLabel(component.category), component.packageName)
            .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InventoryTabletDetailSummary(
    component: ComponentRecord,
    onEditComponent: (String) -> Unit,
    onGenerateLabel: (ComponentRecord) -> Unit,
    onRequestDeleteComponent: (String) -> Unit,
    onRecordMovement: (String) -> Unit,
    onDetailAction: ((String, InventoryDetailAction) -> Unit)? = null,
) {
    val strings = vaultStrings()
    val warning = LocalVaultWarning.current
    var expanded by remember(component.id) { mutableStateOf(false) }
    SectionPane(title = strings.inventory.detailStockTitle) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.inventory_usage_remaining), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(component.quantity.toString(), style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("inventory_detail_quantity"))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.inventory.componentMinStock(component.minStock),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                InventoryTag(
                    text = if (component.isLowStock) strings.common.statusLowStock else strings.common.statusHealthy,
                    containerColor = if (component.isLowStock) warning.container else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (component.isLowStock) warning.foreground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onDetailAction != null) {
                FilledTonalButton(onClick = { onDetailAction(component.id, InventoryDetailAction.Inbound) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inventory_detail_inbound")) {
                    Text(stringResource(R.string.inventory_quick_inbound))
                }
                OutlinedButton(onClick = { onDetailAction(component.id, InventoryDetailAction.Outbound) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inventory_detail_outbound")) {
                    Text(stringResource(R.string.inventory_quick_outbound))
                }
                OutlinedButton(onClick = { onDetailAction(component.id, InventoryDetailAction.Transfer) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("inventory_detail_transfer")) {
                    Text(stringResource(R.string.inventory_quick_transfer))
                }
            } else {
                FilledTonalButton(onClick = { onRecordMovement(component.id) }, Modifier.heightIn(min = 48.dp)) {
                    Text(strings.common.actionRecordMovement)
                }
            }
            OutlinedButton(onClick = { onGenerateLabel(component) }, Modifier.heightIn(min = 48.dp)) {
                Text(strings.common.actionGenerateLabel)
            }
            Box {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)
                    .testTag("inventory_detail_more")) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inventory_ui_more_actions))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(strings.common.actionEdit) },
                        onClick = { expanded = false; onEditComponent(component.id) })
                    if (onDetailAction != null) DropdownMenuItem(text = { Text(strings.common.actionRecordMovement) },
                        onClick = { expanded = false; onRecordMovement(component.id) })
                    DropdownMenuItem(text = { Text(strings.common.actionDelete, color = MaterialTheme.colorScheme.error) },
                        onClick = { expanded = false; onRequestDeleteComponent(component.id) })
                }
            }
        }
    }
}

/** Full field values wrap; only the list uses ellipsis. */
@Composable
internal fun InventoryDetailFieldGrid(fields: List<Pair<String, String>>) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp && !largeText) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            fields.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { (label, value) ->
                        ValueBlock(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    if (row.size < columns) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
