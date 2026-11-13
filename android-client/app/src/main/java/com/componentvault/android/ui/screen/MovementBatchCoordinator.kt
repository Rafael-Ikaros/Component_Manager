package com.componentvault.android.ui.screen

import android.app.Application
import com.componentvault.android.R
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.MovementBatchQueueItemUiState
import com.componentvault.android.model.MovementBatchSessionUiState
import com.componentvault.android.model.MovementBatchStage
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.MovementQuickAction
import com.componentvault.android.model.StorageLocationRecord

/** Stateless batch rules over the latest loaded inventory snapshot. */
internal class MovementBatchCoordinator(
    private val application: Application,
    private val components: List<ComponentRecord>,
    private val locations: List<StorageLocationRecord>,
    private val allocations: List<ComponentAllocationRecord>,
) {
    fun update(
        session: MovementBatchSessionUiState,
        componentId: String,
        transform: (MovementBatchQueueItemUiState) -> MovementBatchQueueItemUiState,
    ): MovementBatchSessionUiState = session.copy(
        stage = MovementBatchStage.Review,
        queuedItems = session.queuedItems.map { item ->
            if (item.componentId == componentId) transform(item) else item
        },
    )

    fun withMovementType(item: MovementBatchQueueItemUiState, movementType: String): MovementBatchQueueItemUiState {
        val location = if (movementType == MovementQuickAction.Outbound.movementType &&
            (item.locationQuantities[item.location] ?: 0) <= 0
        ) {
            item.locationQuantities.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key ?: item.location
        } else {
            item.location
        }
        return item.copy(movementType = movementType, location = location, errorMessage = null)
    }

    fun enqueue(
        session: MovementBatchSessionUiState,
        component: ComponentRecord,
    ): MovementBatchSessionUiState {
        val existingItem = session.queuedItems.firstOrNull { it.componentId == component.id }
        val queuedItems = if (existingItem == null) {
            session.queuedItems + withMovementType(toMovementBatchQueueItem(component), session.defaultMovementType).copy(
                reason = application.getString(
                    if (session.defaultMovementType == MovementQuickAction.Outbound.movementType)
                        R.string.scan_stock_outbound else R.string.scan_stock_inbound,
                ),
            )
        } else {
            session.queuedItems.map { item ->
                if (item.componentId != component.id) {
                    item
                } else {
                    val nextScanCount = item.scanCount + 1
                    item.copy(
                        componentName = component.name,
                        componentSku = component.sku,
                        category = component.category,
                        packageName = component.packageName,
                        locationQuantities = movementLocationQuantities(component.id),
                        locationNames = locations.filterNot { it.deleted }.associate { it.id to it.name },
                        currentStock = component.quantity,
                        minStock = component.minStock,
                        scanCount = nextScanCount,
                        quantityText = if (item.isDirty) {
                            item.quantityText
                        } else {
                            nextScanCount.toString()
                        },
                        errorMessage = null,
                    )
                }
            }
        }

        return session.copy(
            stage = MovementBatchStage.Scanning,
            queuedItems = queuedItems,
            totalScans = session.totalScans + 1,
            lastQueuedComponentName = component.name,
            lastQueuedComponentSku = component.sku,
        )
    }

    private fun movementLocationQuantities(componentId: String): Map<String, Int> =
        locations.filterNot { it.deleted }.associate { location ->
            location.id to allocations.filter { it.componentId == componentId && it.locationId == location.id }.sumOf { it.quantity }
        }

    private fun toMovementBatchQueueItem(
        component: ComponentRecord,
    ): MovementBatchQueueItemUiState {
        return MovementBatchQueueItemUiState(
            componentId = component.id,
            componentName = component.name,
            componentSku = component.sku,
            category = component.category,
            packageName = component.packageName,
            location = component.location,
            locationQuantities = movementLocationQuantities(component.id),
            locationNames = locations.filterNot { it.deleted }.associate { it.id to it.name },
            currentStock = component.quantity,
            minStock = component.minStock,
        )
    }

    fun reconcile(
        session: MovementBatchSessionUiState,
    ): MovementBatchSessionUiState {
        if (!session.isActive) {
            return session
        }

        val missingComponentMessage = application
            .getString(R.string.sync_choose_active_component_first)

        return session.copy(
            queuedItems = session.queuedItems.map { item ->
                val component = components.firstOrNull { it.id == item.componentId }
                if (component == null) {
                    item.copy(errorMessage = missingComponentMessage)
                } else {
                    item.copy(
                        componentName = component.name,
                        componentSku = component.sku,
                        category = component.category,
                        packageName = component.packageName,
                        locationQuantities = movementLocationQuantities(component.id),
                        locationNames = locations.filterNot { it.deleted }.associate { it.id to it.name },
                        currentStock = component.quantity,
                        minStock = component.minStock,
                    )
                }
            },
        )
    }

    fun validate(
        session: MovementBatchSessionUiState,
    ): ValidationResult {
        if (session.queuedItems.isEmpty()) {
            val message = application.getString(R.string.sync_movement_batch_empty)
            return ValidationResult(
                isValid = false,
                drafts = emptyList(),
                session = session.copy(stage = MovementBatchStage.Review),
                message = message,
            )
        }

        var firstError: String? = null
        val drafts = mutableListOf<MovementEntryDraft>()
        val queuedItems = session.queuedItems.map { item ->
            val quantity = item.quantityText.toIntOrNull()
            val errorMessage = when {
                components.none { it.id == item.componentId } ->
                    application.getString(R.string.sync_choose_active_component_first)
                item.location !in item.locationQuantities ->
                    application.getString(R.string.scan_stock_select_location)
                item.movementType == MovementQuickAction.Outbound.movementType && quantity != null &&
                    quantity > (item.locationQuantities[item.location] ?: 0) ->
                    application.getString(R.string.scan_stock_location_insufficient)
                item.reason.isBlank() || item.movementType.isBlank() ->
                    application.getString(R.string.sync_choose_component_type_reason)
                quantity == null ->
                    application.getString(R.string.sync_invalid_movement_quantity)
                item.movementType == MovementQuickAction.Adjustment.movementType && quantity == 0 ->
                    application.getString(R.string.sync_adjustment_non_zero)
                item.movementType != MovementQuickAction.Adjustment.movementType && quantity <= 0 ->
                    application.getString(R.string.sync_movement_quantity_positive)
                item.movementType == MovementQuickAction.Outbound.movementType &&
                    item.currentStock - quantity < 0 ->
                    application.getString(R.string.sync_negative_stock_error)
                item.movementType == MovementQuickAction.Adjustment.movementType &&
                    item.currentStock + quantity < 0 ->
                    application.getString(R.string.sync_negative_stock_error)
                else -> null
            }

            if (errorMessage == null && quantity != null) {
                drafts += MovementEntryDraft(
                    purchaseAmount = if (item.movementType == "inbound") item.purchaseAmount.takeIf(String::isNotBlank) else null,
                    componentId = item.componentId,
                    movementType = item.movementType,
                    quantity = quantity,
                    reason = item.reason,
                    note = item.note,
                    locationId = item.location,
                )
                item.copy(errorMessage = null)
            } else {
                if (firstError == null) {
                    firstError = errorMessage
                }
                item.copy(errorMessage = errorMessage)
            }
        }

        return ValidationResult(
            isValid = firstError == null,
            drafts = drafts,
            session = session.copy(
                stage = MovementBatchStage.Review,
                queuedItems = queuedItems,
            ),
            message = firstError ?: "",
        )
    }

    data class ValidationResult(
        val isValid: Boolean,
        val drafts: List<MovementEntryDraft>,
        val session: MovementBatchSessionUiState,
        val message: String,
    )

}
