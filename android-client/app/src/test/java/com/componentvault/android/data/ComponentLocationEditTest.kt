package com.componentvault.android.data

import android.app.Application
import com.componentvault.android.R
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.MovementEntryDraft
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
class ComponentLocationEditTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before
    fun setUp() {
        val helper = InventoryDatabaseHelper(context)
        try {
            context.deleteDatabase(helper.databaseName)
        } finally {
            helper.close()
        }
        repository = InventoryRepository(context)
    }

    @Test
    fun editingLocationMovesAllAllocationsAndKeepsTotal() = runBlocking {
        val draft = ComponentDraft(
            sku = "LOCATION-EDIT", name = "Part", category = "IC", packageName = "SOT-23",
            location = "A", description = "", quantity = 10, minStock = 0,
        )
        val created = repository.saveComponent(draft)
        assertTrue(created.isSuccess, created.message)
        val componentId = requireNotNull(created.entityId)
        assertTrue(repository.recordMovement(MovementEntryDraft(
            componentId = componentId, movementType = "transfer", quantity = 4,
            reason = "Split", note = "", locationId = "A", destinationLocationId = "B",
        )).isSuccess)
        assertEquals(mapOf("A" to 6, "B" to 4), allocations(componentId).filterValues { it > 0 })

        val edited = repository.saveComponent(draft.copy(
            id = componentId, location = "C", expectedUpdatedAt = repository.loadComponents().single().updatedAt,
        ))

        assertTrue(edited.isSuccess, edited.message)
        assertEquals(mapOf("A" to 0, "B" to 0, "C" to 10), allocations(componentId))
        assertEquals(10, repository.loadComponents().single { it.id == componentId }.quantity)
    }

    @Test
    fun editingTotalWithoutChangingLocationPreservesOtherAllocation() = runBlocking {
        val draft = ComponentDraft(
            sku = "QUANTITY-EDIT", name = "Part", category = "IC", packageName = "SOT-23",
            location = "A", description = "", quantity = 10, minStock = 0,
        )
        val created = repository.saveComponent(draft)
        assertTrue(created.isSuccess, created.message)
        val componentId = requireNotNull(created.entityId)
        assertTrue(repository.recordMovement(MovementEntryDraft(
            componentId = componentId, movementType = "transfer", quantity = 4,
            reason = "Split", note = "", locationId = "A", destinationLocationId = "B",
        )).isSuccess)

        val edited = repository.saveComponent(draft.copy(
            id = componentId, location = "A", quantity = 12,
            expectedUpdatedAt = repository.loadComponents().single().updatedAt,
        ))

        assertTrue(edited.isSuccess, edited.message)
        assertEquals(mapOf("A" to 8, "B" to 4), allocations(componentId))
    }

    @Test
    fun staleEditKeepsCurrentQuantityAllocationsAndHistory() = runBlocking {
        val draft = ComponentDraft(
            sku = "STALE", name = "Part", category = "IC", packageName = "SOT-23",
            location = "A", description = "", quantity = 10, minStock = 0,
        )
        val created = repository.saveComponent(draft)
        assertTrue(created.isSuccess, created.message)
        val original = repository.loadComponents().single()
        val edit = draft.copy(id = original.id, expectedUpdatedAt = original.updatedAt, name = "New name")
        assertTrue(repository.recordMovement(MovementEntryDraft(
            componentId = original.id, movementType = "outbound", quantity = 2,
            reason = "Used two", note = "", locationId = "A",
        )).isSuccess)
        val current = repository.loadComponents().single()
        val movements = repository.loadMovements()

        val rejected = repository.saveComponent(edit)
        assertFalse(rejected.isSuccess)
        assertEquals(context.getString(R.string.sync_component_edit_stale), rejected.message)
        assertEquals(current, repository.loadComponents().single())
        assertEquals(8, current.quantity)
        assertEquals(mapOf("A" to 8), allocations(original.id))
        assertEquals(movements, repository.loadMovements())
    }

    @Test
    fun editsCannotReviveDeletedOrMissingComponentsOrSaveWithoutVersion() = runBlocking {
        val draft = ComponentDraft(
            sku = "DELETED", name = "Part", category = "IC", packageName = "SOT-23",
            location = "A", description = "", quantity = 10, minStock = 0,
        )
        val created = repository.saveComponent(draft)
        assertTrue(created.isSuccess, created.message)
        val original = repository.loadComponents().single()
        assertFalse(repository.saveComponent(draft.copy(id = original.id)).isSuccess)
        assertTrue(repository.softDeleteComponent(original.id).isSuccess)
        assertFalse(repository.saveComponent(
            draft.copy(id = original.id, expectedUpdatedAt = original.updatedAt),
        ).isSuccess)
        assertFalse(repository.saveComponent(
            draft.copy(id = "missing", expectedUpdatedAt = original.updatedAt),
        ).isSuccess)
        assertTrue(repository.loadComponents().isEmpty())
    }

    private fun allocations(componentId: String): Map<String, Int> {
        val helper = InventoryDatabaseHelper(context)
        try {
            return helper.readableDatabase.rawQuery(
                "SELECT location_id, quantity FROM component_allocations WHERE component_id = ?",
                arrayOf(componentId),
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
                }
            }
        } finally {
            helper.close()
        }
    }
}
