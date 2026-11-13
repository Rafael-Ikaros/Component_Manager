package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.InventoryConflictComponent
import com.componentvault.android.model.InventoryConflictMovementEdit
import com.componentvault.android.model.StorageLocationRecord

private data class MovementFields(val quantity: String, val locationId: String)

private data class MovementReviewDraft(
    val componentId: String,
    val serverVersion: String,
    val localVersion: String,
    val fields: List<MovementFields>,
)

private val MovementReviewDraftSaver = listSaver<MovementReviewDraft?, String>(
    save = { draft ->
        if (draft == null) emptyList() else {
            listOf(draft.componentId, draft.serverVersion, draft.localVersion) +
                draft.fields.flatMap { listOf(it.quantity, it.locationId) }
        }
    },
    restore = { values ->
        MovementReviewDraft(values[0], values[1], values[2], values.drop(3).chunked(2).map {
            MovementFields(it[0], it[1])
        })
    },
)

@Composable
internal fun InventoryConflictReviewScreen(
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadInventoryConflictReview() }
    InventoryConflictReviewContent(
        state = viewModel.conflictReviewUiState,
        onDismiss = onDismiss,
        onRefresh = viewModel::loadInventoryConflictReview,
        onReplay = viewModel::replayInventoryConflict,
    )
}

@Composable
internal fun InventoryConflictReviewContent(
    state: InventoryConflictReviewUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onReplay: (InventoryConflictComponent, List<InventoryConflictMovementEdit>) -> Unit,
) {
    val components = state.review?.components.orEmpty()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val component = components.firstOrNull { it.id == selectedId }
    val busy = state.loading || state.submitting
    var draft by rememberSaveable(stateSaver = MovementReviewDraftSaver) { mutableStateOf<MovementReviewDraft?>(null) }
    val originalFields = component?.movements.orEmpty().map { MovementFields(it.quantity.toString(), it.locationId.orEmpty()) }
    val fields = draft?.takeIf {
        component != null && it.componentId == component.id && it.serverVersion == component.serverVersion &&
            it.localVersion == component.localVersion && it.fields.size == originalFields.size
    }?.fields ?: originalFields
    var confirm by remember(component?.id, component?.serverVersion, component?.localVersion) { mutableStateOf(false) }
    val balances = component?.locations.orEmpty().associate { it.id to it.quantity }
    val edits = component?.movements.orEmpty().zip(fields).mapNotNull { (movement, entry) ->
        val quantity = entry.quantity.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        if (entry.locationId !in balances) return@mapNotNull null
        InventoryConflictMovementEdit(movement.id, quantity, entry.locationId)
    }
    val projected = balances.mapValues { (_, quantity) -> quantity.toLong() }.toMutableMap()
    val fits = run {
        // Match by ID since a row with invalid input is absent from the edits list.
        val editsById = edits.associateBy(InventoryConflictMovementEdit::id)
        var projectedTotal = component?.serverQuantity?.toLong() ?: 0L
        component?.movements.orEmpty().all { movement ->
            val edit = editsById[movement.id] ?: return@all false
            val delta = if (movement.type == "inbound") edit.quantity.toLong() else -edit.quantity.toLong()
            val next = projected.getValue(edit.locationId) + delta
            projected[edit.locationId] = next
            projectedTotal += delta
            next in 0L..Int.MAX_VALUE.toLong() && projectedTotal in 0L..Int.MAX_VALUE.toLong()
        }
    }
    val canSubmit = !busy && component?.blockedReason == null && edits.isNotEmpty() && fits

    SecondaryPageScaffold(
        title = stringResource(R.string.sync_conflict_review_title),
        navigationEnabled = !state.submitting,
        onBack = { if (component == null) onDismiss() else selectedId = null },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_conflict_review_description))
                    state.message?.let { message ->
                        Text(message, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !busy) {
                        Text(stringResource(R.string.sync_conflict_refresh))
                    }
                    if (busy) CircularProgressIndicator()
                }
            }
            if (component == null) {
                if (state.review != null && components.isEmpty()) {
                    item { Text(stringResource(R.string.sync_conflict_no_components)) }
                }
                itemsIndexed(components, key = { _, part -> part.id }) { _, part ->
                    SectionPane(title = part.name, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                        Text(part.sku)
                        Text(stringResource(R.string.sync_conflict_server_total, part.serverQuantity))
                        part.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick = { selectedId = part.id }, enabled = !busy) {
                            Text(stringResource(R.string.sync_conflict_view_component))
                        }
                    }
                }
            } else {
                item(key = "component-${component.id}") {
                    SectionPane(title = component.name, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                        Text(component.sku)
                        Text(stringResource(R.string.sync_conflict_server_total, component.serverQuantity))
                        for (location in component.locations.filter { it.quantity > 0 }) {
                            Text(stringResource(R.string.movement_location_with_stock, location.name, location.quantity))
                        }
                        component.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (component.blockedReason == null && component.movements.isNotEmpty()) {
                    fun editMovement(index: Int, entry: MovementFields) {
                        draft = MovementReviewDraft(
                            component.id, component.serverVersion, component.localVersion,
                            fields.toMutableList().also { it[index] = entry },
                        )
                    }
                    itemsIndexed(component.movements, key = { _, movement -> "movement-${movement.id}" }) { index, movement ->
                        val entry = fields[index]
                        val title = stringResource(
                            if (movement.type == "inbound") R.string.sync_conflict_inbound else R.string.sync_conflict_outbound,
                        )
                        SectionPane(
                            title = stringResource(R.string.sync_conflict_movement_step, index + 1, title),
                            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                        ) {
                            Text(movement.reason)
                            OutlinedTextField(
                                value = entry.quantity,
                                onValueChange = { quantity ->
                                    if (quantity.all(Char::isDigit)) {
                                        editMovement(index, entry.copy(quantity = quantity))
                                    }
                                },
                                label = { Text(stringResource(R.string.sync_conflict_quantity)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !busy,
                                readOnly = movement.purchaseAmount != null,
                            )
                            movement.purchaseAmount?.let { amount ->
                                Text(stringResource(R.string.sync_conflict_purchase_fixed, amount))
                            }
                            if (busy) {
                                Text(component.locations.firstOrNull { it.id == entry.locationId }?.name.orEmpty())
                            } else {
                                MovementLocationSelector(
                                    title = stringResource(R.string.sync_conflict_location),
                                    value = entry.locationId,
                                    locations = component.locations.map { StorageLocationRecord(it.id, it.name, component.serverVersion) },
                                    quantities = balances,
                                    onSelect = { location ->
                                        editMovement(index, entry.copy(locationId = location))
                                    },
                                )
                            }
                        }
                    }
                    item(key = "confirm") {
                        Column(
                            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!fits) {
                                Text(stringResource(R.string.sync_conflict_check_movements), color = MaterialTheme.colorScheme.error)
                            }
                            state.message?.takeIf { state.failed }?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                            Button(onClick = { confirm = true }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.sync_conflict_confirm_action))
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm && component != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.sync_conflict_confirm_action)) },
            text = { Text(stringResource(R.string.sync_conflict_confirm_description, component.sku, edits.size)) },
            confirmButton = {
                TextButton(
                    enabled = canSubmit,
                    onClick = { confirm = false; onReplay(component, edits) },
                ) { Text(stringResource(R.string.sync_conflict_confirm_commit)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
