package com.componentvault.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("inventory_detail_identity")) {
        if (maxWidth < 280.dp || largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductThumbnail(sku = component.sku, imageUrl = component.productImageUrl,
                    description = component.description, imageSize = 88.dp, compactPlaceholder = true)
                InventoryDetailIdentityText(component)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                ProductThumbnail(sku = component.sku, imageUrl = component.productImageUrl,
                    description = component.description, imageSize = 88.dp, compactPlaceholder = true)
                Column(Modifier.weight(1f)) { InventoryDetailIdentityText(component) }
            }
        }
    }
}

@Composable
private fun InventoryDetailIdentityText(component: ComponentRecord) {
    val identity = remember(component.description) { com.componentvault.android.model.parseImportDescription(component.description) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(component.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        identity.model?.takeIf { it != component.name }?.let { model ->
            Text(model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Text(component.sku, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(listOfNotNull(identity.brand, localizedCategoryLabel(component.category), component.packageName)
            .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
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
    val largeText = LocalDensity.current.fontScale >= 1.5f
    var expanded by remember(component.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(component.quantity.toString(), style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Medium, modifier = Modifier.testTag("inventory_detail_quantity"))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.inventory_usage_remaining), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.inventory.componentMinStock(component.minStock),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (component.isLowStock) Surface(color = warning.container, shape = MaterialTheme.shapes.extraSmall) {
                            Text(if (component.quantity == 0) stringResource(R.string.inventory_zero_stock) else strings.common.statusLowStock,
                                color = warning.foreground, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)
                    .testTag("inventory_detail_more")) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.inventory_ui_more_actions))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (onDetailAction != null) DropdownMenuItem(text = { Text(strings.common.actionRecordMovement) },
                        onClick = { expanded = false; onRecordMovement(component.id) })
                    DropdownMenuItem(text = { Text(strings.common.actionDelete, color = MaterialTheme.colorScheme.error) },
                        onClick = { expanded = false; onRequestDeleteComponent(component.id) })
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("inventory_detail_stock_actions")) {
            val availableWidth = maxWidth
            if (onDetailAction == null) {
                Button(onClick = { onRecordMovement(component.id) }, Modifier.heightIn(min = 48.dp)) {
                    Text(strings.common.actionRecordMovement)
                }
            } else if (!largeText && availableWidth >= 260.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InventoryDetailAction.entries.forEach { action ->
                        InventoryDetailStockButton(action, Modifier.weight(1f)) { onDetailAction(component.id, action) }
                    }
                }
            } else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InventoryDetailAction.entries.forEach { action ->
                        InventoryDetailStockButton(action, Modifier.widthIn(max = availableWidth)) { onDetailAction(component.id, action) }
                    }
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                InventoryDetailActionRow(strings.common.actionEdit, stringResource(R.string.inventory_edit_supporting),
                    Icons.Outlined.Edit, Modifier.testTag("inventory_detail_edit")) { onEditComponent(component.id) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InventoryDetailActionRow(strings.common.actionGenerateLabel, stringResource(R.string.inventory_label_supporting),
                    Icons.AutoMirrored.Outlined.Label, Modifier.testTag("inventory_detail_label")) { onGenerateLabel(component) }
            }
        }
    }
}

@Composable
private fun InventoryDetailStockButton(action: InventoryDetailAction, modifier: Modifier, onClick: () -> Unit) {
    val (label, icon, tag) = when (action) {
        InventoryDetailAction.Inbound -> Triple(R.string.inventory_quick_inbound, Icons.Outlined.Add, "inbound")
        InventoryDetailAction.Outbound -> Triple(R.string.inventory_quick_outbound, Icons.Outlined.Remove, "outbound")
        InventoryDetailAction.Transfer -> Triple(R.string.inventory_transfer_short, Icons.Outlined.SwapHoriz, "transfer")
    }
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), Modifier.padding(start = 4.dp))
    }
    val buttonModifier = modifier.heightIn(min = 48.dp).testTag("inventory_detail_$tag")
    val padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    val shape = RoundedCornerShape(15.dp)
    if (action == InventoryDetailAction.Inbound) {
        Button(onClick = onClick, modifier = buttonModifier, shape = shape, contentPadding = padding, content = content)
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = padding,
            content = content,
        )
    }
}

@Composable
private fun InventoryDetailActionRow(
    title: String,
    supporting: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** Detail sections sit on the page, not a stack of nested cards. */
@Composable
internal fun InventoryDetailSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        content()
    }
}

/** Short fields share a row even on phones; large fonts and long text get full width. */
@Composable
internal fun InventoryDetailFieldGrid(fields: List<Pair<String, String>>) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("inventory_detail_field_grid")) {
        val twoColumns = maxWidth >= 260.dp && !largeText
        val rows = remember(fields, twoColumns) {
            buildList<List<Pair<String, String>>> {
                val pending = mutableListOf<Pair<String, String>>()
                fields.forEach { field ->
                    if (!twoColumns || field.second.length > 64 || '\n' in field.second) {
                        if (pending.isNotEmpty()) { add(pending.toList()); pending.clear() }
                        add(listOf(field))
                    } else {
                        pending += field
                        if (pending.size == 2) { add(pending.toList()); pending.clear() }
                    }
                }
                if (pending.isNotEmpty()) add(pending.toList())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { (label, value) -> ValueBlock(label, value, Modifier.weight(1f)) }
                    if (twoColumns && row.size == 1 && row[0].second.length <= 64 && '\n' !in row[0].second) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
