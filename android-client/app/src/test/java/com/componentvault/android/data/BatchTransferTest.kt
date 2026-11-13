package com.componentvault.android.data

import android.app.Application
import com.componentvault.android.R
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.StorageLocationSaveIntent
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BatchTransferTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before fun setUp() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        repository = InventoryRepository(context)
        runBlocking {
            assertTrue(repository.saveStorageLocation("B", "B", StorageLocationSaveIntent.Create).isSuccess)
        }
    }

    @Test fun transfersAllLinesAndWritesMovementsInOneCommit() = runBlocking {
        val first = addComponent("R1", 5)
        val second = addComponent("C1", 3)
        val result = repository.transferComponentsBatch("A", "B", listOf(
            line(first, 2), line(second, 3),
        ))
        assertTrue(result.isSuccess, result.message)
        assertEquals(3, allocation(first, "A"))
        assertEquals(2, allocation(first, "B"))
        assertEquals(0, allocation(second, "A"))
        assertEquals(3, allocation(second, "B"))
        assertEquals(2, scalar("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'transfer'"))
        assertEquals(5, repository.loadComponents().first { it.id == first }.quantity)
    }

    @Test fun insufficientLaterLineRollsBackEveryEarlierLine() = runBlocking {
        val first = addComponent("R1", 5)
        val second = addComponent("C1", 3)
        val result = repository.transferComponentsBatch("A", "B", listOf(
            line(first, 2), line(second, 4),
        ))
        assertFalse(result.isSuccess)
        assertEquals(5, allocation(first, "A"))
        assertEquals(0, allocation(first, "B"))
        assertEquals(3, allocation(second, "A"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'transfer'"))
    }

    @Test fun rejectsSameLocationAndDuplicateComponentBeforeWriting() = runBlocking {
        val component = addComponent("R1", 5)
        assertFalse(repository.transferComponentsBatch("A", "A", listOf(line(component, 1))).isSuccess)
        assertFalse(repository.transferComponentsBatch("A", "B", listOf(
            line(component, 1), line(component, 1),
        )).isSuccess)
        assertEquals(5, allocation(component, "A"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'transfer'"))
    }

    @Test fun changedInventoryAfterReviewRejectsWholeBatch() = runBlocking {
        val first = addComponent("R1", 5)
        val second = addComponent("C1", 3)
        val reviewedLines = listOf(line(first, 2), line(second, 2))
        assertTrue(repository.recordMovement(MovementEntryDraft(
            componentId = second, movementType = "inbound", quantity = 1,
            reason = "Another local edit", note = "", locationId = "A",
        )).isSuccess)

        val result = repository.transferComponentsBatch("A", "B", reviewedLines)
        assertFalse(result.isSuccess)
        assertEquals(5, allocation(first, "A"))
        assertEquals(0, allocation(first, "B"))
        assertEquals(4, allocation(second, "A"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'transfer'"))
    }

    @Test fun outboundUsesSelectedLocationBalanceAfterTransferringAllStock() = runBlocking {
        val component = addComponent("R1", 103)
        val transfer = repository.transferComponentsBatch("A", "B", listOf(line(component, 103)))
        assertTrue(transfer.isSuccess, transfer.message)
        assertEquals("A", repository.loadComponents().first { it.id == component }.location)
        val draft = MovementEntryDraft(
            componentId = component, movementType = "outbound", quantity = 2,
            reason = "Issue stock", note = "", locationId = "A",
        )

        val rejected = repository.recordMovement(draft)
        assertFalse(rejected.isSuccess)
        assertEquals(context.getString(R.string.movement_source_stock_insufficient, 0, 2L), rejected.message)
        assertEquals(0, allocation(component, "A"))
        assertEquals(103, allocation(component, "B"))
        assertEquals(103, repository.loadComponents().first { it.id == component }.quantity)
        assertEquals(0, scalar("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'outbound'"))

        val accepted = repository.recordMovement(draft.copy(locationId = "B"))
        assertTrue(accepted.isSuccess, accepted.message)
        assertEquals(0, allocation(component, "A"))
        assertEquals(101, allocation(component, "B"))
        assertEquals(101, repository.loadComponents().first { it.id == component }.quantity)
        assertEquals(1, scalar("SELECT COUNT(*) FROM stock_movements WHERE movement_type = 'outbound' AND location_id = 'B'"))
    }

    @Test fun staleSourceBalanceIsRejected() = runBlocking {
        val component = addComponent("R1", 3)
        val transfer = repository.transferComponentsBatch("A", "B", listOf(line(component, 1)))
        assertTrue(transfer.isSuccess, transfer.message)
        val outbound = MovementEntryDraft(
            componentId = component, movementType = "outbound", quantity = 1,
            reason = "Issue stock", note = "", locationId = "A",
        )
        val accepted = repository.recordMovement(outbound)
        assertTrue(accepted.isSuccess, accepted.message)

        val staleDrafts = listOf(
            outbound.copy(quantity = 2),
            outbound.copy(movementType = "transfer", quantity = 2, destinationLocationId = "B"),
            outbound.copy(movementType = "adjustment", quantity = -2),
        )
        for (draft in staleDrafts) {
            val rejected = repository.recordMovement(draft)
            assertFalse(rejected.isSuccess)
            assertEquals(context.getString(R.string.movement_source_stock_insufficient, 1, 2L), rejected.message)
        }
        assertEquals(1, allocation(component, "A"))
        assertEquals(1, allocation(component, "B"))
        assertEquals(2, repository.loadComponents().first { it.id == component }.quantity)
        assertEquals(2, scalar("SELECT COUNT(*) FROM stock_movements"))
    }

    private suspend fun addComponent(sku: String, quantity: Int): String = repository.saveComponent(ComponentDraft(
        sku = sku, name = sku, category = "Passive", packageName = "0603",
        location = "A", description = "", quantity = quantity, minStock = 0,
    )).let { result ->
        assertTrue(result.isSuccess, result.message)
        requireNotNull(result.entityId)
    }

    private suspend fun line(componentId: String, quantity: Int): BatchTransferLine = BatchTransferLine(
        componentId, quantity,
        repository.loadComponents().first { it.id == componentId }.updatedAt,
    )

    private fun allocation(componentId: String, locationId: String): Int = query(
        "SELECT COALESCE((SELECT quantity FROM component_allocations WHERE component_id = ? AND location_id = ?), 0)",
        arrayOf(componentId, locationId),
    )

    private fun scalar(sql: String): Int = query(sql, emptyArray())

    private fun query(sql: String, arguments: Array<String>): Int {
        val helper = InventoryDatabaseHelper(context)
        try {
            return helper.readableDatabase.rawQuery(sql, arguments).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        } finally { helper.close() }
    }
}
