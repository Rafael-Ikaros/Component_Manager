package com.componentvault.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.componentvault.android.R
import com.componentvault.android.ui.theme.LocalVaultWarning
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.componentvault.android.model.OverviewUiState
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.model.StorageLocationRecord

@Composable
internal fun OverviewScreen(
    contentPadding: PaddingValues,
    uiState: OverviewUiState,
    syncConfiguration: SyncConfiguration,
    statusMessage: String,
    onOpenLowStock: () -> Unit,
    onSelectLowStockComponent: (String) -> Unit,
    onOpenMovements: () -> Unit,
    onSelectMovement: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    onOpenInventory: () -> Unit = onOpenLowStock,
) {
    OverviewContent(
        contentPadding = contentPadding,
        uiState = uiState,
        syncConfiguration = syncConfiguration,
        statusMessage = statusMessage,
        onOpenLowStock = onOpenLowStock,
        onSelectLowStockComponent = onSelectLowStockComponent,
        onOpenMovements = onOpenMovements,
        onSelectMovement = onSelectMovement,
        onOpenSettings = onOpenSettings,
        onOpenSyncSettings = onOpenSyncSettings,
        storageLocations = storageLocations,
        onOpenInventory = onOpenInventory,
    )
}

@Composable
internal fun OverviewContent(
    contentPadding: PaddingValues,
    uiState: OverviewUiState,
    syncConfiguration: SyncConfiguration,
    statusMessage: String,
    onOpenLowStock: () -> Unit,
    onSelectLowStockComponent: (String) -> Unit,
    onOpenMovements: () -> Unit,
    onSelectMovement: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    onOpenInventory: () -> Unit = onOpenLowStock,
) {
    val strings = vaultStrings()
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 19.dp, bottom = 23.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OverviewMetric(uiState.componentCount.toString(), stringResource(R.string.v2_overview_parts), Modifier.weight(1f))
                    OverviewMetric(uiState.totalUnits.toString(), stringResource(R.string.v2_overview_quantity), Modifier.weight(1f))
                    OverviewMetric(uiState.lowStockCount.toString(), strings.overview.metricLowStock,
                        Modifier.weight(1f).clickable(onClick = onOpenLowStock))
                }
                HorizontalDivider(color = colors.outlineVariant)
            }
            item { OverviewSectionHeading(stringResource(R.string.v2_overview_attention)) }
            if (uiState.lowStockItems.isEmpty()) {
                item { Text(strings.common.emptyAllComponentsHealthy, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(uiState.lowStockItems.size) { index ->
                    val part = uiState.lowStockItems[index]
                    val warning = LocalVaultWarning.current
                    Row(Modifier.fillMaxWidth().heightIn(min = 65.dp)
                        .clickable { onSelectLowStockComponent(part.id) }.padding(vertical = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(part.name, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.v2_overview_low_stock_caption, part.sku, part.quantity, part.minStock),
                                fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                        }
                        Surface(modifier = Modifier.weight(1f), color = warning.container,
                            contentColor = warning.foreground, shape = RoundedCornerShape(6.dp)) {
                            Text(if (part.quantity == 0) stringResource(R.string.inventory_zero_stock) else strings.common.statusLowStock,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Icon(painterResource(R.drawable.v2_chevron_right), contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
            item { OverviewSectionHeading(stringResource(R.string.v2_overview_recent)) }
            if (uiState.recentMovements.isEmpty()) {
                item { Text(strings.common.emptyNoMovements, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium) }
            } else {
                val recent = uiState.recentMovements.take(3)
                items(recent.size) { index ->
                    val movement = recent[index]
                    VaultMovementLogRow(movement, storageLocations = storageLocations,
                        showDivider = index < recent.lastIndex, onClick = { onSelectMovement(movement.id) })
                }
            }
            item {
                TextButton(onClick = onOpenMovements) {
                    Text(stringResource(R.string.v2_overview_view_records, uiState.movementCount))
                }
            }
        }
        Surface(color = colors.background) {
            Button(
                onClick = onOpenInventory,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(stringResource(R.string.v2_overview_open_inventory))
            }
        }
    }
}

@Composable
private fun OverviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverviewSectionHeading(text: String) {
    Text(text, Modifier.padding(top = 22.dp, bottom = 12.dp),
        fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
}
