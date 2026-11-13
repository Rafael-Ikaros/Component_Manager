package com.componentvault.android.ui.screen

import android.app.Application
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.MovementBatchSessionUiState
import com.componentvault.android.model.MovementQuickAction
import com.componentvault.android.model.StorageLocationRecord
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MovementBatchCoordinatorTest {
    @Test
    fun outboundDefaultsToStockedLocationWithoutRedirectingExistingDraftAfterRefresh() {
        val component = ComponentRecord(
            id = "part", sku = "C1", name = "Part", category = "IC",
            packageName = "SOT-23", location = "A", description = "", quantity = 103,
            minStock = 1, updatedAt = "2026-10-18", deleted = false,
        )
        val locations = listOf(
            StorageLocationRecord("A", "Shelf A", "2026-10-18"),
            StorageLocationRecord("B", "Shelf B", "2026-10-18"),
        )
        fun coordinator(stockedLocation: String) = MovementBatchCoordinator(
            RuntimeEnvironment.getApplication(), listOf(component), locations,
            listOf(ComponentAllocationRecord(component.id, stockedLocation, "Shelf $stockedLocation", 103)),
        )
        val outbound = MovementQuickAction.Outbound.movementType
        val initial = coordinator("B")
        val queued = initial.enqueue(MovementBatchSessionUiState(defaultMovementType = outbound), component)
        assertEquals("B", queued.queuedItems.single().location)
        val inbound = initial.enqueue(MovementBatchSessionUiState(), component).queuedItems.single()
        assertEquals("A", inbound.location)
        val edited = inbound.copy(quantityText = "2", reason = "My reason", isDirty = true)
        val switched = initial.withMovementType(edited, outbound)
        assertEquals("B", switched.location)
        assertEquals("2", switched.quantityText)
        assertEquals("My reason", switched.reason)
        assertEquals("B", initial.withMovementType(switched, outbound).location)

        val refreshed = coordinator("A")
        val reconciled = refreshed.reconcile(queued.copy(queuedItems = listOf(switched)))
        val stale = reconciled.queuedItems.single()
        assertEquals("B", stale.location)
        assertEquals("2", stale.quantityText)
        assertEquals("My reason", stale.reason)
        assertFalse(refreshed.validate(reconciled).isValid)
    }

    @Test
    fun dirtyQuantitySurvivesRescanAndReconciliationThenValidatesAgainstCurrentLocationStock() {
        val component = ComponentRecord(
            id = "part", sku = "C1", name = "Part", category = "IC",
            packageName = "SOT-23", location = "A", description = "", quantity = 8,
            minStock = 1, updatedAt = "2026-10-18", deleted = false,
        )
        val location = StorageLocationRecord("A", "Shelf A", "2026-10-18")
        fun coordinator(stockAtLocation: Int) = MovementBatchCoordinator(
            RuntimeEnvironment.getApplication(), listOf(component), listOf(location),
            listOf(ComponentAllocationRecord(component.id, location.id, location.name, stockAtLocation)),
        )

        val first = coordinator(8).enqueue(
            MovementBatchSessionUiState(defaultMovementType = MovementQuickAction.Outbound.movementType),
            component,
        )
        val edited = coordinator(8).update(first, component.id) {
            it.copy(quantityText = "5", isDirty = true)
        }
        val rescanned = coordinator(8).enqueue(edited, component)
        assertEquals(2, rescanned.totalScans)
        assertEquals(2, rescanned.queuedItems.single().scanCount)
        assertEquals("5", rescanned.queuedItems.single().quantityText)

        val restoredAgainstChangedStock = coordinator(4).reconcile(rescanned)
        val rejected = coordinator(4).validate(restoredAgainstChangedStock)
        assertFalse(rejected.isValid)
        assertTrue(rejected.session.queuedItems.single().errorMessage?.isNotBlank() == true)
        assertEquals("5", rejected.session.queuedItems.single().quantityText)
    }
}
