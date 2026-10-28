package com.componentvault.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.ui.theme.VaultWarning
import com.componentvault.android.ui.theme.VaultWarningContainer

/** Dense tablet presentation; the caller retains selection and swipe action handling. */
@Composable
internal fun InventoryTabletListRowContent(
    item: InventoryListItemUiState,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    selectionMode: Boolean,
    storageLocations: List<StorageLocationRecord>,
) {
    val strings = vaultStrings()
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag("inventory_tablet_row"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.sku, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.packageName.takeIf { it.isNotBlank() }, item.specificationSummary)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(displayLocationName(item.location, storageLocations, strings.common.labelNotConfigured),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.widthIn(max = 120.dp), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.quantity.toString(), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                Text(strings.inventory.componentMinStock(item.minStock),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                InventoryTag(
                    text = if (item.isLowStock) strings.common.statusLowStock else strings.common.statusHealthy,
                    containerColor = if (item.isLowStock) VaultWarningContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (item.isLowStock) VaultWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
) {
    val strings = vaultStrings()
    SectionPane(title = component.name, supporting = strings.inventory.componentSubtitle(
        component.sku, localizedCategoryLabel(component.category), component.packageName,
    )) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ProductThumbnail(sku = component.sku, imageUrl = component.productImageUrl,
                description = component.description, imageSize = 80.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.inventory.metricQuantity, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(component.quantity.toString(), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text(strings.inventory.componentMinStock(component.minStock),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StatusBanner(
            message = if (component.isLowStock) strings.inventory.selectedComponentLowStockStatus
                else strings.inventory.selectedComponentHealthyStatus,
            containerColor = if (component.isLowStock) VaultWarningContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (component.isLowStock) VaultWarning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onEditComponent(component.id) }, Modifier.heightIn(min = 48.dp)) {
                Text(strings.common.actionEdit)
            }
            OutlinedButton(onClick = { onRecordMovement(component.id) }, Modifier.heightIn(min = 48.dp)) {
                Text(strings.common.actionRecordMovement)
            }
            OutlinedButton(onClick = { onGenerateLabel(component) }, Modifier.heightIn(min = 48.dp)) {
                Text(strings.common.actionGenerateLabel)
            }
            TextButton(onClick = { onRequestDeleteComponent(component.id) }, Modifier.heightIn(min = 48.dp)) {
                Text(strings.common.actionDelete)
            }
        }
    }
}

/** Full field values wrap; only the list uses ellipsis. */
@Composable
internal fun InventoryDetailFieldGrid(fields: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 2 else 1
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
