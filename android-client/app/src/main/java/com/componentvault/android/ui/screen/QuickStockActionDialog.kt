package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
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
    var locationId by remember(item.id, inbound) {
        mutableStateOf(options.firstOrNull { it.id == item.location }?.id ?: options.firstOrNull()?.id.orEmpty())
    }
    var purchaseIsUnit by remember(item.id, inbound) { mutableStateOf(false) }
    var purchaseText by remember(item.id, inbound) { mutableStateOf("") }
    var quantityText by remember(item.id, inbound) { mutableStateOf("1") }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val available = allocations.filter { it.componentId == item.id && it.locationId == locationId }
        .sumOf { it.quantity }
    val quantity = quantityText.toIntOrNull()
    val valid = options.any { it.id == locationId } && quantity != null && quantity > 0 &&
        (inbound || quantity <= available)
    val title = stringResource(if (inbound) R.string.inventory_quick_inbound else R.string.inventory_quick_outbound)
    val reason = stringResource(if (inbound) R.string.inventory_quick_inbound_reason
        else R.string.inventory_quick_outbound_reason)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("$title · ${item.sku}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = !busy && options.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("quick_location")) {
                        Text(options.firstOrNull { it.id == locationId }?.name
                            ?: stringResource(R.string.inventory_quick_choose_location))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { location ->
                            DropdownMenuItem(text = { Text(location.name) }, onClick = {
                                locationId = location.id; error = ""; expanded = false
                            })
                        }
                    }
                }
                if (!inbound) Text(stringResource(R.string.inventory_quick_available, available),
                    style = MaterialTheme.typography.bodySmall)
                QuantityInputField(
                    value = quantityText,
                    onValueChange = { value -> if (value.all(Char::isDigit)) { quantityText = value; error = "" } },
                    label = stringResource(R.string.inventory_quick_quantity),
                    minimum = 1,
                    maximum = if (inbound) Int.MAX_VALUE else available.coerceAtLeast(0),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    fieldTestTag = "quick_quantity",
                )
                if (inbound) PurchaseCostFields(purchaseText, purchaseIsUnit, quantity, { purchaseText = it }, { purchaseIsUnit = it })
                if (options.isEmpty()) Text(stringResource(R.string.inventory_quick_no_location),
                    color = MaterialTheme.colorScheme.error)
                if (!inbound && quantity != null && quantity > available) Text(
                    stringResource(R.string.inventory_quick_exceeds_stock), color = MaterialTheme.colorScheme.error)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            Button(onClick = {
                if (busy || !valid) return@Button
                busy = true
                onSubmit(MovementEntryDraft(item.id, if (inbound) "inbound" else "outbound",
                    requireNotNull(quantity), reason, "", locationId, purchaseAmount = if (inbound) purchaseTotal(purchaseText,purchaseIsUnit,requireNotNull(quantity)) else null)) { result ->
                    busy = false
                    if (result.isSuccess) onDismiss() else error = result.message
                }
            }, enabled = valid && !busy, modifier = Modifier.testTag("quick_submit")) {
                Text(stringResource(R.string.inventory_quick_submit))
            }
        },
    )
}
