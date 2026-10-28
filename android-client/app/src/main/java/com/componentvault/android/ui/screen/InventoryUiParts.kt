package com.componentvault.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.componentvault.android.R
import com.componentvault.android.data.PublicProductImageStore
import com.componentvault.android.data.InventoryImageStore
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.InventorySortOption
import com.componentvault.android.model.StockMovementRecord
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.ui.theme.VaultWarning
import com.componentvault.android.ui.theme.VaultWarningContainer
import androidx.compose.animation.core.animate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun displayLocationName(
    locationId: String,
    storageLocations: List<StorageLocationRecord>,
    emptyLabel: String,
): String = when {
    locationId.isBlank() -> emptyLabel
    else -> storageLocations.firstOrNull { it.id == locationId }?.name?.takeIf { it.isNotBlank() } ?: locationId
}

@Composable
internal fun StatusBanner(
    message: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (message.isBlank()) {
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

@Composable
internal fun SectionPane(
    title: String?,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!title.isNullOrBlank() || !supporting.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                }
            }
            content()
        }
    }
}

@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun EmptyPane(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ValueBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InventoryListRow(
    item: InventoryListItemUiState,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    onInbound: (() -> Unit)? = null,
    onOutbound: (() -> Unit)? = null,
    onTransfer: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    compact: Boolean = false,
) {
    if (!selectionMode && listOf(onInbound, onOutbound, onTransfer, onDelete).any { it != null }) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val buttonWidth = ((maxWidth - 96.dp) / 4).coerceIn(48.dp, 56.dp)
            val revealWidth = buttonWidth * 4
            val revealPx = with(LocalDensity.current) { revealWidth.toPx() }
            var offsetPx by remember(item.id) { mutableFloatStateOf(0f) }
            val scope = rememberCoroutineScope()
            var settleJob by remember { mutableStateOf<Job?>(null) }
            val dragState = rememberDraggableState { delta ->
                offsetPx = (offsetPx + delta).coerceIn(-revealPx, 0f)
            }
            fun close() {
                settleJob?.cancel()
                offsetPx = 0f
            }
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)) {
                if (offsetPx < 0f) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    Row(
                    modifier = Modifier.width(revealWidth).fillMaxHeight()
                        .testTag("inventory_quick_actions"),
                ) {
                    val colors = MaterialTheme.colorScheme
                    listOf(
                        Triple(R.string.inventory_quick_inbound, Icons.Outlined.Add, onInbound) to
                            Pair(colors.primaryContainer, colors.onPrimaryContainer),
                        Triple(R.string.inventory_quick_outbound, Icons.Outlined.Remove, onOutbound) to
                            Pair(colors.secondaryContainer, colors.onSecondaryContainer),
                        Triple(R.string.inventory_quick_transfer, Icons.Outlined.SwapHoriz, onTransfer) to
                            Pair(colors.tertiaryContainer, colors.onTertiaryContainer),
                        Triple(R.string.action_delete, Icons.Outlined.Delete, onDelete) to
                            Pair(colors.errorContainer, colors.onErrorContainer),
                    ).forEachIndexed { index, (action, palette) ->
                        val (label, icon, callback) = action
                        Box(
                            modifier = Modifier.width(buttonWidth).fillMaxHeight()
                                .background(palette.first)
                                .clickable(enabled = callback != null) {
                                    close()
                                    callback?.invoke()
                                }
                                .testTag(listOf("inventory_quick_inbound", "inventory_quick_outbound",
                                    "inventory_quick_transfer", "inventory_quick_delete")[index]),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(icon, contentDescription = null, tint = palette.second)
                                Text(stringResource(label), color = palette.second,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
                }
                InventoryListRowContent(item, selected,
                    Modifier.offset { IntOffset(offsetPx.roundToInt(), 0) }
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal,
                            onDragStarted = { settleJob?.cancel() },
                            onDragStopped = {
                                val target = if (offsetPx <= -revealPx / 2) -revealPx else 0f
                                settleJob = scope.launch {
                                    animate(offsetPx, target) { value, _ -> offsetPx = value }
                                }
                            },
                        ).testTag("inventory_reveal_card"),
                    onClick = {
                        if (offsetPx < 0f) {
                            settleJob?.cancel()
                            settleJob = scope.launch { animate(offsetPx, 0f) { value, _ -> offsetPx = value } }
                        } else onClick()
                    }, selectionMode, storageLocations, compact)
            }
        }
    } else {
        InventoryListRowContent(item, selected, modifier, onClick, selectionMode, storageLocations, compact)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InventoryListRowContent(
    item: InventoryListItemUiState,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    selectionMode: Boolean,
    storageLocations: List<StorageLocationRecord>,
    compact: Boolean,
) {
    if (compact) {
        InventoryTabletListRowContent(item, selected, modifier, onClick, selectionMode, storageLocations)
        return
    }
    val strings = vaultStrings()
    val localizedCategory = localizedCategoryLabel(item.category)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
                if (selectionMode) Checkbox(checked = selected, onCheckedChange = null)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.sku,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.specificationSummary?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "$localizedCategory / ${item.packageName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InventoryTag(text = displayLocationName(item.location, storageLocations, strings.common.labelNotConfigured))
                    InventoryTag(
                        text = if (item.isLowStock) {
                            strings.common.statusLowStock
                        } else {
                            strings.common.statusHealthy
                        },
                        containerColor = if (item.isLowStock) {
                            VaultWarningContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (item.isLowStock) {
                            VaultWarning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    text = formatShortLocalTimestamp(item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                }
                Column(
                    modifier = Modifier.widthIn(max = 144.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = item.quantity.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = strings.inventory.componentMinStock(item.minStock),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ProductThumbnail(
                            sku = item.sku,
                            imageUrl = item.productImageUrl,
                            description = item.description,
                            imageSize = 52.dp,
                        )
                    }
                StockUsageDonut(
                    summary = com.componentvault.android.model.StockUsageSummary(
                        remaining = item.quantity,
                        issued = item.issuedQuantity,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun ProductThumbnail(
    sku: String,
    imageUrl: String?,
    description: String = "",
    modifier: Modifier = Modifier,
    imageSize: Dp = 60.dp,
) {
    val context = LocalContext.current
    val imageStore = remember(context) { PublicProductImageStore.get(context) }
    val localStore = remember(context) { InventoryImageStore(context) }
    var bitmap by remember(sku, imageUrl, description) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(sku, imageUrl, description) { mutableStateOf(true) }
    val photoRevision by InventoryImageStore.revision.collectAsState()
    LaunchedEffect(sku, imageUrl, description, photoRevision) {
        loading = true
        bitmap = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                localStore.resolve(description)?.let { bytes ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            } ?: if (localStore.key(description) == null) imageStore.load(sku, imageUrl) else null
        }.getOrNull()
        loading = false
    }

    Surface(
        modifier = modifier.size(imageSize),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = androidx.compose.ui.res.stringResource(
                    com.componentvault.android.R.string.product_image_description,
                    sku,
                ),
                modifier = Modifier.padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (loading) {
                        androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.product_image_loading)
                    } else if (localStore.key(description) != null) {
                        "照片待下载 / 请同步"
                    } else {
                        androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.product_image_unavailable)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
    }
}

@Composable
internal fun MovementHistoryRow(
    movement: StockMovementRecord,
    selected: Boolean,
    componentDeleted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val strings = vaultStrings()
    val rowModifier = if (onClick == null) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    }

    Surface(
        modifier = rowModifier,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = movement.componentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = movement.componentSku,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    MovementQuantityPill(movement)
                    Text(
                        text = formatShortLocalTimestamp(movement.happenedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MovementTypePill(movement.movementType)
                if (componentDeleted) InventoryTag(
                    text = stringResource(R.string.movements_deleted_component),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = movement.reason.ifBlank { strings.common.labelNoReasonRecorded },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun InventoryTag(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MovementTypePill(movementType: String) {
    val label = movementTypeLabel(movementType)
    val colors = when (movementType.lowercase()) {
        "inbound" -> androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        "outbound" -> androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = VaultWarningContainer,
            labelColor = VaultWarning,
        )
        else -> androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = colors,
    )
}

@Composable
internal fun MovementQuantityPill(movement: StockMovementRecord) {
    val text = formatMovementQuantity(movement.quantityChange)
    val colors = when (movement.movementType.lowercase()) {
        "inbound" -> androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        "outbound" -> androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = colors,
    )
}

@Composable
internal fun RailDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color = color, shape = RoundedCornerShape(99.dp)),
    )
}

@Composable
internal fun movementTypeLabel(movementType: String): String =
    if (movementType.equals("transfer", true)) {
        androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.movement_type_transfer)
    } else {
        vaultStrings().movements.movementTypeLabel(movementType)
    }

@Composable
internal fun inventorySortLabel(sort: InventorySortOption): String =
    vaultStrings().inventory.sortLabel(sort)

@Composable
internal fun formatMovementQuantity(quantity: Long): String =
    vaultStrings().movements.movementQuantity(quantity)
