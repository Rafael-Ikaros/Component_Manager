package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationRecord

internal typealias QuickMovementAction = (MovementEntryDraft, (OperationResult) -> Unit) -> Unit

@Composable
internal fun QuickStockActionDialog(
    item: InventoryListItemUiState,
    inbound: Boolean,
    allocations: List<ComponentAllocationRecord>,
    locations: List<StorageLocationRecord>,
    onDismiss: () -> Unit,
    onSubmit: QuickMovementAction,
) {
    val activeLocations = locations.filterNot(StorageLocationRecord::deleted)
    val options = if (inbound) activeLocations else activeLocations.filter { location ->
        allocations.any { it.componentId == item.id && it.locationId == location.id && it.quantity > 0 }
    }
    var locationId by rememberSaveable(item.id, inbound) {
        mutableStateOf(options.firstOrNull { it.id == item.location }?.id ?: options.firstOrNull()?.id.orEmpty())
    }
    var purchaseIsUnit by rememberSaveable(item.id, inbound) { mutableStateOf(false) }
    var purchaseText by rememberSaveable(item.id, inbound) { mutableStateOf("") }
    var quantityText by rememberSaveable(item.id, inbound) { mutableStateOf("1") }
    val movementType = if (inbound) "inbound" else "outbound"
    val defaultReason = stringResource(if (inbound) R.string.inventory_quick_inbound_reason
        else R.string.inventory_quick_outbound_reason)
    var reason by rememberSaveable(item.id, inbound) { mutableStateOf(defaultReason) }
    var note by rememberSaveable(item.id, inbound) { mutableStateOf("") }
    var reviewing by rememberSaveable(item.id, inbound) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    val layoutMode = rememberInventoryLayoutMode()
    val operation = movementTypeLabel(movementType)
    val available = allocations.filter { it.componentId == item.id && it.locationId == locationId }
        .sumOf { it.quantity }
    val quantity = quantityText.toIntOrNull()
    val valid = options.any { it.id == locationId } && quantity != null && quantity > 0 &&
        (inbound || quantity <= available)
    val visibleError = when {
        error.isNotBlank() -> error
        options.isEmpty() -> stringResource(R.string.inventory_quick_no_location)
        !inbound && quantity != null && quantity > available ->
            stringResource(R.string.inventory_quick_exceeds_stock)
        else -> null
    }
    val strings = vaultStrings()
    val leave = {
        if (!busy) {
            if (reviewing) reviewing = false else onDismiss()
        }
    }
    BackHandler(enabled = !layoutMode.prefersDialogForms, onBack = leave)

    AdaptiveFormSurface(
        modal = true,
        title = if (reviewing) stringResource(if (inbound) R.string.v2_confirm_inbound else R.string.v2_confirm_outbound)
            else movementTypeLabel(movementType),
        caption = stringResource(if (reviewing) R.string.prototype_movement_review_caption else R.string.v2_movement_choose_location),
        layoutMode = layoutMode,
        onDismiss = leave,
        footerSaveLabel = stringResource(if (reviewing) R.string.prototype_movement_confirm_action
            else R.string.prototype_movement_review_action, operation),
        footerTestTag = if (reviewing) "quick_confirm" else "quick_submit",
        footerDismissLabel = stringResource(if (reviewing) R.string.prototype_movement_modify else R.string.action_cancel),
        onCancelReview = onDismiss.takeIf { reviewing },
        dismissEnabled = !busy,
        footerError = visibleError,
        saveEnabled = valid && !busy,
        onSave = submit@{
            if (!valid || busy) return@submit
            val count = requireNotNull(quantity)
            val paid = runCatching {
                if (!inbound) null else {
                    com.componentvault.android.data.InventoryCost.decimal(purchaseText)
                    purchaseTotal(purchaseText, purchaseIsUnit, count)
                }
            }.getOrElse { error = it.message.orEmpty(); return@submit }
            error = ""
            if (!reviewing) {
                reviewing = true
                return@submit
            }
            busy = true
            onSubmit(MovementEntryDraft(item.id, movementType, count, reason.trim().ifBlank { defaultReason },
                note.trim(), locationId, purchaseAmount = paid)) { result ->
                busy = false
                if (result.isSuccess) onDismiss() else {
                    reviewing = false
                    error = result.message
                }
            }
        },
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reviewing) {
                    MovementReviewContent(
                        component = "${item.name} · ${item.sku}",
                        movementType = movementType,
                        location = options.firstOrNull { it.id == locationId }?.name.orEmpty(),
                        destination = null,
                        quantity = quantityText,
                        reason = reason.trim().ifBlank { defaultReason },
                        note = note.trim(),
                        locationQuantity = available,
                        totalQuantity = item.quantity,
                        purchaseAmount = if (inbound && quantity != null && quantity > 0)
                            purchaseTotal(purchaseText, purchaseIsUnit, quantity) else null,
                    )
                } else {
                    Text("${item.name} · ${item.sku}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.testTag("quick_location")) {
                        MovementLocationSelector(
                            title = stringResource(if (inbound) R.string.prototype_movement_inbound_location
                                else R.string.prototype_movement_outbound_location),
                            value = locationId,
                            locations = options,
                            onSelect = { locationId = it; error = "" },
                        )
                    }
                    Text(stringResource(R.string.prototype_movement_stock_before, available),
                        style = MaterialTheme.typography.bodySmall)
                    QuantityInputField(
                        value = quantityText,
                        onValueChange = { value -> if (value.all(Char::isDigit)) { quantityText = value; error = "" } },
                        label = stringResource(R.string.inventory_quick_quantity),
                        minimum = 1,
                        maximum = if (inbound) Int.MAX_VALUE else available.coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                        fieldTestTag = "quick_quantity",
                    )
                    SettingsTextField(reason, { reason = it }, label = { Text(strings.common.fieldReason) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    SettingsTextField(note, { note = it }, label = { Text(strings.common.fieldNote) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    if (inbound) PurchaseCostFields(purchaseText, purchaseIsUnit, quantity,
                        { purchaseText = it; error = "" }, { purchaseIsUnit = it; error = "" })
                }
            }
        }
    }
}
