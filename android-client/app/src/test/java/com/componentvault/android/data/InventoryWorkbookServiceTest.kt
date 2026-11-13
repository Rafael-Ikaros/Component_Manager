package com.componentvault.android.data

import android.app.Application
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
class InventoryWorkbookServiceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before fun setUp() = resetRepository()

    private fun resetRepository() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        repository = InventoryRepository(context)
    }

    private fun workbook() = InventoryWorkbook(
        source = WorkbookSource.ComponentVault,
        components = listOf(WorkbookComponent("backup-part", "TEST-BACKUP", "Backup part", "IC", "SOT-23", "backup-bin", "", 4, 1, "2026-10-18T00:00:00.000Z", false, null)),
        locations = listOf(WorkbookLocation("backup-bin", "Backup shelf", "2026-10-18T00:00:00.000Z", false)),
        allocations = listOf(WorkbookAllocation("backup-part", "backup-bin", 4)),
        movements = listOf(WorkbookMovement("backup-movement", "backup-part", "inbound", 4, "Initial", "", "2026-10-18T00:00:00.000Z", "2026-10-18T00:00:00.000Z", false, "backup-bin", null)),
        fingerprint = "",
        warnings = emptyList(),
    )

    @Test fun restorePreservesTablesAndRejectsReplay() = runBlocking {
        val source = workbook()
        val preview = repository.previewInventoryWorkbook(InventoryWorkbookWriter.write(source))
        assertEquals(1, preview.newComponentCount)
        val restored = repository.restoreInventoryWorkbook(preview)
        assertTrue(restored.isSuccess, restored.message)
        val exported = InventoryWorkbookCodec.parse(repository.exportInventoryWorkbook())
        assertEquals(source.components, exported.components)
        assertEquals(source.allocations, exported.allocations)
        assertEquals(source.movements, exported.movements)
        assertTrue(exported.locations.containsAll(source.locations))
        assertFalse(repository.restoreInventoryWorkbook(preview).isSuccess)
        assertEquals(4, repository.loadComponents().single().quantity)
        assertEquals(1, repository.loadMovements().size)
    }

    @Test fun exportAndRestorePreserveSqlNullAndOriginalVersions() = runBlocking {
        val backup = workbook()
        val original = backup.copy(
            components = backup.components.map { it.copy(baseUpdatedAt = it.updatedAt, inventoryUnitCost = "1.25") },
            movements = backup.movements.map { it.copy(purchaseAmount = "5", costAmount = "5") },
        )
        val initial = repository.restoreInventoryWorkbook(
            repository.previewInventoryWorkbook(InventoryWorkbookWriter.write(original)),
        )
        assertTrue(initial.isSuccess, initial.message)

        val helper = InventoryDatabaseHelper(context)
        try {
            helper.writableDatabase.execSQL("UPDATE components SET description = NULL")
            helper.writableDatabase.execSQL("UPDATE stock_movements SET note = NULL")
        } finally {
            helper.close()
        }
        val exported = repository.exportInventoryWorkbook()
        val expectedComponents = original.components.map { it.copy(description = null) }
        val expectedMovements = original.movements.map { it.copy(note = null) }
        val parsed = InventoryWorkbookCodec.parse(exported)
        assertEquals(expectedComponents, parsed.components)
        assertEquals(expectedMovements, parsed.movements)

        resetRepository()
        val restored = repository.restoreInventoryWorkbook(repository.previewInventoryWorkbook(exported))
        assertTrue(restored.isSuccess, restored.message)
        val roundTrip = InventoryWorkbookCodec.parse(repository.exportInventoryWorkbook())
        assertEquals(expectedComponents, roundTrip.components)
        assertEquals(expectedMovements, roundTrip.movements)
        assertEquals(original.allocations, roundTrip.allocations)
    }

    @Test fun deletedDestinationAfterPreviewRejectsRestoreWithoutPartialStock() = runBlocking {
        assertTrue(repository.saveStorageLocation("backup-bin", "Backup shelf", StorageLocationSaveIntent.Create).isSuccess)
        val preview = repository.previewInventoryWorkbook(InventoryWorkbookWriter.write(workbook()))
        assertTrue(repository.deleteStorageLocation("backup-bin").isSuccess)
        assertFalse(repository.restoreInventoryWorkbook(preview).isSuccess)
        assertTrue(repository.loadComponents().isEmpty())
        assertTrue(repository.loadMovements().isEmpty())
        assertTrue(repository.loadAllocations().isEmpty())
    }
}
