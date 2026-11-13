package com.componentvault.android.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.componentvault.android.model.ComponentDraft
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
class BatchJlcDatabaseTest {
    private lateinit var repository: InventoryRepository
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = runBlocking {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        repository = InventoryRepository(context)
        assertTrue(repository.saveStorageLocation("A", "A", StorageLocationSaveIntent.Create).isSuccess)
        assertTrue(repository.saveStorageLocation("B", "B", StorageLocationSaveIntent.Create).isSuccess)
        assertTrue(repository.saveComponent(ComponentDraft(
            sku = "C70565", name = "Existing part", category = "IC",
            packageName = "SOT-23", location = "A", description = "Keep existing data",
            quantity = 10, minStock = 1,
        )).isSuccess)
    }

    @Test fun initialPurchaseKeepsActualAmountAndZeroStockClearsAverage() = runBlocking {
        assertTrue(repository.saveComponent(ComponentDraft(sku="NEW", name="Paid", category="IC", packageName="SMD", location="A", description="", quantity=3,minStock=0,purchaseAmount="1")).isSuccess)
        val paid=repository.loadComponents().first { it.sku=="NEW" }
        assertEquals("0.3333333333333333",paid.inventoryUnitCost)
        assertEquals("1",repository.loadMovements().first { it.componentId==paid.id }.purchaseAmount)
        val oneThird = java.math.BigDecimal("1").divide(java.math.BigDecimal(3),16,java.math.RoundingMode.HALF_EVEN).toPlainString()
        assertTrue(repository.saveComponent(ComponentDraft(id=paid.id,sku=paid.sku,name=paid.name,category=paid.category,packageName=paid.packageName,location=paid.location,description=paid.description,quantity=3,minStock=0,valuationUnitCost=oneThird,valuationAmount="1",expectedUpdatedAt=paid.updatedAt)).isSuccess)
        assertEquals(oneThird,repository.loadComponents().first { it.id==paid.id }.inventoryUnitCost)
        assertEquals("0.0000000000000001",repository.loadMovements().first { it.componentId==paid.id && it.movementType=="adjustment" }.costAmount)
        assertTrue(!repository.saveComponent(ComponentDraft(sku="EMPTYPAID",name="Invalid",category="IC",packageName="SMD",location="A",description="",quantity=0,minStock=0,purchaseAmount="1")).isSuccess)
        assertTrue(repository.recordMovement(com.componentvault.android.model.MovementEntryDraft(paid.id,"outbound",3,"test","","A")).isSuccess)
        assertEquals(null,repository.loadComponents().first { it.id==paid.id }.inventoryUnitCost)
    }

    @Test fun versionFiveCostMigrationPreservesExistingData() {
        val helper = InventoryDatabaseHelper(context)
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE components(id TEXT PRIMARY KEY, quantity INTEGER)")
            db.execSQL("CREATE TABLE stock_movements(id TEXT PRIMARY KEY, quantity INTEGER)")
            db.execSQL("INSERT INTO components VALUES('kept',7)")
            db.execSQL("INSERT INTO stock_movements VALUES('history',3)")
            helper.onUpgrade(db,5,6)
            db.rawQuery("SELECT quantity,inventory_unit_cost FROM components WHERE id='kept'",null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(7,cursor.getInt(0)); assertTrue(cursor.isNull(1)) }
            db.rawQuery("SELECT quantity,purchase_amount,cost_amount FROM stock_movements WHERE id='history'",null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(3,cursor.getInt(0)); assertTrue(cursor.isNull(1)); assertTrue(cursor.isNull(2)) }
        }
        helper.close()
    }

    @Test fun versionSixMigrationAddsReplayHistoryAndPreservesInventory() {
        val helper = InventoryDatabaseHelper(context)
        try {
            SQLiteDatabase.create(null).use { db ->
                helper.onCreate(db)
                db.execSQL("DROP TABLE inventory_replay_history")
                db.execSQL("INSERT INTO components(id,sku,name,category,package_name,location,quantity,min_stock,updated_at) " +
                    "VALUES('kept','C100','Part','IC','0603','A',7,0,'2026-10-22T01:00:00Z')")
                helper.onUpgrade(db, 6, 7)
                db.rawQuery("SELECT quantity FROM components WHERE id='kept'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(7, it.getInt(0))
                }
                db.rawQuery("SELECT COUNT(*) FROM inventory_replay_history", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally {
            helper.close()
        }
    }

    @Test fun costFlowsAndUnknownInbound() = runBlocking {
        val original = repository.loadComponents().single()
        assertTrue(repository.saveComponent(ComponentDraft(id = original.id, sku = original.sku, name = original.name, category = original.category, packageName = original.packageName, location = original.location, description = original.description, quantity = 10, minStock = 1, valuationUnitCost = "1", expectedUpdatedAt = original.updatedAt)).isSuccess)
        val paid = listOf(row("paid", "C70565", "10", "A").copy(purchaseAmount = "20"))
        assertTrue(repository.commitBatchJlc("cost", paid).isSuccess)
        assertEquals("1.5", repository.loadComponents().single().inventoryUnitCost)
        assertTrue(repository.commitBatchJlc("cost", paid).isSuccess)
        assertEquals(20, repository.loadComponents().single().quantity)
        assertTrue(repository.recordMovement(com.componentvault.android.model.MovementEntryDraft(original.id,"outbound",5,"test","", "A")).isSuccess)
        assertEquals("1.5", repository.loadComponents().single().inventoryUnitCost)
        assertTrue(repository.commitBatchJlc("unknown", listOf(row("unknown", "C70565", "1", "A"))).isSuccess)
        assertEquals(null, repository.loadComponents().single().inventoryUnitCost)
    }

    @Test
    fun mixedInboundUpdatesAllocationsAndReplayDoesNotDuplicate() = runBlocking {
        val rows = listOf(row("one", "C70565", "5", "B"),
            row("two", "C30926", "3", "A"), row("three", "C70565", "2", "A"))
        assertTrue(repository.commitBatchJlc("session", rows).isSuccess)
        assertEquals(17, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(12, number("SELECT quantity FROM component_allocations WHERE component_id=(SELECT id FROM components WHERE sku='C70565') AND location_id='A'"))
        assertEquals(5, number("SELECT quantity FROM component_allocations WHERE component_id=(SELECT id FROM components WHERE sku='C70565') AND location_id='B'"))
        assertEquals(3, number("SELECT quantity FROM components WHERE sku='C30926'"))
        assertEquals(1, number("SELECT COUNT(*) FROM components WHERE sku='C70565' AND name='Existing part' AND description='Keep existing data'"))
        assertEquals(3, number("SELECT COUNT(*) FROM stock_movements WHERE movement_type='inbound'"))
        assertEquals(3, number("SELECT COUNT(*) FROM sync_queue WHERE entity_type='stock_movement'"))
        assertEquals(rows.map { it.id }.toSet(), repository.loadBatchJlcReceipts("session"))

        val reopened = InventoryRepository(context)
        assertTrue(reopened.commitBatchJlc("session", rows).isSuccess)
        assertEquals(17, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(3, number("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(3, number("SELECT COUNT(*) FROM batch_jlc_receipts"))
    }

    @Test
    fun laterOverflowRollsBackNewPartMovementsQueueAndReceipts() = runBlocking {
        val queueBefore = number("SELECT COUNT(*) FROM sync_queue")
        val result = repository.commitBatchJlc("rollback", listOf(
            row("new-first", "C30926", "3", "A"),
            row("overflow-last", "C70565", Int.MAX_VALUE.toString(), "B"),
        ))
        assertFalse(result.isSuccess)
        assertEquals(0, number("SELECT COUNT(*) FROM components WHERE sku='C30926'"))
        assertEquals(10, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(10, number("SELECT SUM(quantity) FROM component_allocations"))
        assertEquals(0, number("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0, number("SELECT COUNT(*) FROM batch_jlc_receipts"))
        assertEquals(queueBefore, number("SELECT COUNT(*) FROM sync_queue"))
        assertFalse(repository.commitBatchJlc("zero", listOf(row("zero", "C70565", "0", "A"))).isSuccess)
        assertFalse(repository.commitBatchJlc("missing-bin", listOf(row("missing", "C70565", "1", "MISSING"))).isSuccess)
        assertEquals(10, number("SELECT quantity FROM components WHERE sku='C70565'"))
    }

    @Test
    fun singleImportAppendsAndRejectsStaleConfirmationWithoutReplacingMetadata() = runBlocking {
        val target = requireNotNull(repository.findExistingImportTarget("C70565"))
        assertEquals(10, target.quantity)
        assertTrue(repository.appendImportedStock(target, 5, "B").isSuccess)
        assertEquals(15, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(5, number("SELECT quantity FROM component_allocations WHERE location_id='B'"))
        assertEquals(1, number("SELECT COUNT(*) FROM components WHERE name='Existing part' AND description='Keep existing data'"))
        assertFalse(repository.appendImportedStock(target, 5, "B").isSuccess)
        val refreshed = requireNotNull(repository.findExistingImportTarget("C70565"))
        assertFalse(repository.appendImportedStock(refreshed, 0, "B").isSuccess)
        assertFalse(repository.appendImportedStock(refreshed, Int.MAX_VALUE, "B").isSuccess)
        assertFalse(repository.appendImportedStock(refreshed, 1, "MISSING").isSuccess)
        assertTrue(repository.appendImportedStock(refreshed, 15, "A").isSuccess)
        assertEquals(30, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(30, number("SELECT SUM(quantity) FROM component_allocations"))
        assertEquals(2, number("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun versionFourUpgradeCreatesReceiptsAndPreservesStock() = runBlocking {
        val backupDirectory = java.io.File(context.noBackupFilesDir, "database-preupgrade")
        backupDirectory.listFiles()?.forEach { it.delete() }
        context.deleteDatabase("component-vault.db")
        val source = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("component-vault.db"), null)
        // Actual v4 columns: neither cost fields nor v5 batch receipts exist.
        source.execSQL("CREATE TABLE components (id TEXT PRIMARY KEY,sku TEXT NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,package_name TEXT NOT NULL,location TEXT NOT NULL,description TEXT,quantity INTEGER NOT NULL CHECK(quantity>=0),min_stock INTEGER NOT NULL CHECK(min_stock>=0),updated_at TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,base_updated_at TEXT)")
        source.execSQL("CREATE UNIQUE INDEX idx_components_sku_active ON components(sku) WHERE deleted=0")
        source.execSQL("CREATE TABLE stock_movements(id TEXT PRIMARY KEY,component_id TEXT NOT NULL,movement_type TEXT NOT NULL,quantity INTEGER NOT NULL,reason TEXT NOT NULL,note TEXT,happened_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,location_id TEXT,destination_location_id TEXT)")
        source.execSQL("CREATE TABLE storage_locations(id TEXT PRIMARY KEY,name TEXT NOT NULL,updated_at TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)")
        source.execSQL("CREATE TABLE component_allocations(component_id TEXT NOT NULL,location_id TEXT NOT NULL,quantity INTEGER NOT NULL,PRIMARY KEY(component_id,location_id))")
        source.execSQL("CREATE TABLE sync_queue(entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,entity_updated_at TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(entity_type,entity_id))")
        source.execSQL("INSERT INTO components VALUES('legacy','C70565','Existing part','IC','SOT-23','A','Keep existing data',10,1,'2026-01-01T00:00:00Z',0,NULL)")
        source.execSQL("INSERT INTO storage_locations VALUES('A','A','2026-01-01T00:00:00Z',0)")
        source.execSQL("INSERT INTO component_allocations VALUES('legacy','A',10)")
        source.execSQL("INSERT INTO stock_movements VALUES('old-movement','legacy','inbound',10,'initial',NULL,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',0,'A',NULL)")
        source.rawQuery("PRAGMA journal_mode=WAL", null).use { assertTrue(it.moveToFirst()) }
        source.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { assertTrue(it.moveToFirst()) }
        source.version = 4
        source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { assertTrue(it.moveToFirst()) }
        source.execSQL("UPDATE components SET description='WAL pre-upgrade marker' WHERE sku='C70565'")
        val sourceWal = java.io.File(context.getDatabasePath("component-vault.db").path + "-wal")
        assertTrue(sourceWal.isFile && sourceWal.length() > 32)

        try {
            val upgraded = InventoryRepository(context)
            assertTrue(upgraded.loadBatchJlcReceipts("new-session").isEmpty())
        } finally {
            source.close()
        }
        assertEquals(10, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(10, number("SELECT SUM(quantity) FROM component_allocations"))
        assertEquals(7, number("PRAGMA user_version"))
        assertEquals(1, number("SELECT COUNT(*) FROM components WHERE sku='C70565' AND inventory_unit_cost IS NULL"))
        assertEquals(1, number("SELECT COUNT(*) FROM stock_movements WHERE id='old-movement' AND quantity=10 AND purchase_amount IS NULL AND cost_amount IS NULL"))

        val backup = requireNotNull(backupDirectory.listFiles()?.singleOrNull { it.name.endsWith(".db") })
        SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT description FROM components WHERE sku='C70565'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("WAL pre-upgrade marker", cursor.getString(0))
            }
            assertEquals(4, db.version)
        }
    }

    @Test
    fun orderReceiptsPreventInboundAcrossDifferentImportSessions() = runBlocking {
        val order = row("order", "C70565", "5", "B").copy(id = "order-synthetic-order-sku")
        assertTrue(repository.commitBatchJlc("first-import", listOf(order)).isSuccess)
        assertTrue(order.id in repository.loadBatchJlcReceipts("another-import"))
        assertTrue(repository.commitBatchJlc("another-import", listOf(order)).isSuccess)
        assertEquals(15, number("SELECT quantity FROM components WHERE sku='C70565'"))
        assertEquals(1, number("SELECT COUNT(*) FROM stock_movements"))
    }
    private fun row(id: String, sku: String, quantity: String, location: String) = BatchJlcRow(
        id = java.util.UUID.nameUUIDFromBytes(id.toByteArray()).toString(),
        raw = "{pc:$sku,qty:$quantity,pdi:sample-$id}", status = BatchJlcStatus.Ready,
        sku = sku, name = "New part", category = "IC", packageName = "SOT-23",
        quantityText = quantity, location = location,
    )

    private fun number(sql: String): Int {
        val helper = InventoryDatabaseHelper(context)
        try {
            return helper.readableDatabase.rawQuery(sql, null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        } finally { helper.close() }
    }
}
