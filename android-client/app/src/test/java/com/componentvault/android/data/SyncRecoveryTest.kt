package com.componentvault.android.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomPreset
import com.componentvault.android.data.bom.BomPresetStore
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.model.SyncConfiguration
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
class SyncRecoveryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("component_vault_sync", Context.MODE_PRIVATE)
    private val inventoryTime = "2026-10-22T02:00:00Z"

    @Test fun inventorySuccessSurvivesBomFailureAndConfigurationChangeHidesOldSuccess(): Unit = runBlocking {
        withServer(cancelBom = false) { service, settings, _, _ ->
            val result = service.runSync { settings }
            assertFalse(result.isSuccess)
            assertEquals("Partial", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, ""))
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
            assertEquals("", preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, ""))
            val repository = InventoryRepository(context)
            assertEquals(inventoryTime, repository.loadSyncConfiguration().lastSyncedAt)
            repository.saveSyncConfiguration("http://different-server", "different-key", false)
            val changed = repository.loadSyncConfiguration()
            assertEquals("", changed.syncState)
            assertTrue(changed.lastSyncedAt != inventoryTime)
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
        }
    }

    @Test fun cancelDuringBomKeepsInventorySuccessAndRecordsCancelledPhase(): Unit = runBlocking {
        withServer(cancelBom = true) { service, settings, bomStarted, releaseBom ->
            val run = async(Dispatchers.IO) { service.runSync { settings } }
            bomStarted.get(10, TimeUnit.SECONDS)
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
            run.cancel()
            releaseBom.complete(Unit)
            service.cancelActiveRequests()
            run.join()
            assertTrue(run.isCancelled)
            assertEquals("Cancelled", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, ""))
            assertEquals("BOM", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, ""))
            assertEquals(inventoryTime, preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, ""))
            assertEquals("", preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, ""))
        }
    }

    @Test fun bomEditDuringExchangeKeepsWholeRunPartialUntilNextSync(): Unit = runBlocking {
        val file = File(context.filesDir, "bom-presets.json")
        val store = BomPresetStore(file)
        val sheet = BomSheet("BOM", 0)
        val preset = BomPreset("preset", "Board", BomParseResult("Board", 1, listOf(sheet), sheet, emptyList(), "source"))
        var confirmedSnapshot = org.json.JSONArray()
        withServer(cancelBom = false, bomReply = {
            // The request already contains the old snapshot. Keep the newer local version pending.
            store.save(preset.copy(name = "Edited while BOM request was in flight"))
            JSONObject().put("bom_preset_protocol", 1).put("presets", confirmedSnapshot).toString()
        }) { service, settings, _, _ ->
            store.save(preset)
            confirmedSnapshot = store.syncSnapshot()
            val result = service.runSync { settings }
            assertFalse(result.isSuccess)
            assertEquals("Partial", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, ""))
            assertEquals(0 to 1, service.pendingSummary())
            assertEquals("Edited while BOM request was in flight", store.list().single().name)
            assertTrue(preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, "").orEmpty().isNotBlank())
            assertTrue(InventoryPhotoSync(context).isCurrentSyncComplete())
        }
    }

    @Test fun readingStatusAndOpeningAnotherRepositoryNeverInterruptsAnActiveRun() {
        preferences.edit().clear().commit()
        val repository = InventoryRepository(context)
        preferences.edit().putString(InventoryPreferenceKeys.SYNC_RUN_STATE, "Running")
            .putString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "Inventory").commit()
        assertEquals("Running", repository.loadSyncConfiguration().syncState)
        assertEquals("Running", InventoryRepository(context).loadSyncConfiguration().syncState)
        assertEquals("Inventory", repository.loadSyncConfiguration().syncPhase)
    }

    @Test fun bomPendingUsesConfirmedVersionsAndPreservesNewLocalEditAndTombstone() {
        preferences.edit().clear().commit()
        val file = File(context.filesDir, "bom-presets.json")
        file.delete()
        val store = BomPresetStore(file)
        val sheet = BomSheet("BOM", 0)
        val preset = BomPreset("preset", "Board", BomParseResult("Board", 1, listOf(sheet), sheet, emptyList(), "source"))
        store.save(preset)
        val snapshot = store.syncSnapshot().getJSONObject(0)
        preferences.edit().putString(InventoryPreferenceKeys.BOM_ACK_VERSIONS,
            JSONObject().put(snapshot.getString("id"), snapshot.getString("updated_at")).toString()).commit()
        val helper = InventoryDatabaseHelper(context)
        try {
            val service = InventorySyncService(context, helper, preferences, Any())
            assertEquals(0, service.pendingSummary().second)
            store.save(preset.copy(name = "Edited during request"))
            assertEquals(1, service.pendingSummary().second)
            store.delete(preset.id)
            assertEquals(1, service.pendingSummary().second)
        } finally { helper.close(); file.delete() }
    }

    @Test fun pullSkuCollisionRollsBackAndRetainsLocalStockHistoryQueueAndCursor(): Unit = runBlocking {
        withServer(cancelBom = false, requestCount = 4, pullReply = {
            withDatabase { db ->
                ensureStorageLocation(db, "A", inventoryTime)
                db.execSQL(
                    "INSERT INTO components(id, sku, name, category, package_name, location, quantity, min_stock, updated_at) " +
                        "VALUES ('local', 'C100', 'Local', 'category', '0603', 'A', 10, 0, ?)",
                    arrayOf(inventoryTime),
                )
                setAllocationQuantity(db, "local", "A", 10)
                db.execSQL(
                    "INSERT INTO stock_movements(id, component_id, movement_type, quantity, reason, happened_at, updated_at, location_id) " +
                        "VALUES ('inbound', 'local', 'inbound', 10, 'Test', ?, ?, 'A')",
                    arrayOf(inventoryTime, inventoryTime),
                )
                enqueueEntity(db, "component", "local", inventoryTime)
                enqueueEntity(db, "stock_movement", "inbound", inventoryTime)
            }
            pullSnapshot(remoteComponent("unrelated", "C200", 0), remoteComponent("remote", "C100", 2))
        }) { service, settings, _, _ ->
            preferences.edit().putLong(InventoryPreferenceKeys.SYNC_CURSOR, 7).commit()
            val result = service.runSync { settings }
            assertFalse(result.isSuccess)
            assertTrue(result.message.contains("C100"))
            assertEquals(7L, preferences.getLong(InventoryPreferenceKeys.SYNC_CURSOR, -1))
            withDatabase { db ->
                assertEquals(10, scalar(db, "SELECT quantity FROM components WHERE id = 'local' AND deleted = 0"))
                assertEquals(10, allocationQuantity(db, "local", "A"))
                assertEquals(1, scalar(db, "SELECT COUNT(*) FROM stock_movements WHERE component_id = 'local'"))
                assertEquals(2, scalar(db, "SELECT COUNT(*) FROM sync_queue"))
                assertEquals(0, scalar(db, "SELECT COUNT(*) FROM components WHERE id IN ('remote', 'unrelated')"))
            }
        }
    }

    @Test fun pullAppliesRemoteSkuReassignmentAndUpdatesExistingComponentById(): Unit = runBlocking {
        withServer(cancelBom = false, pullReply = {
            pullSnapshot(remoteComponent("replacement", "C100", 2), remoteComponent("old", "C200", 5))
        }) { service, settings, _, _ ->
            withDatabase { db ->
                ensureStorageLocation(db, "A", inventoryTime)
                db.execSQL(
                    "INSERT INTO components(id, sku, name, category, package_name, location, quantity, min_stock, updated_at) " +
                        "VALUES ('old', 'C100', 'Old', 'category', '0603', 'A', 3, 0, ?)",
                    arrayOf(inventoryTime),
                )
                setAllocationQuantity(db, "old", "A", 3)
            }
            service.runSync { settings } // Inventory succeeds before the simulated BOM failure.
            assertEquals(8L, preferences.getLong(InventoryPreferenceKeys.SYNC_CURSOR, -1))
            withDatabase { db ->
                assertEquals(5, scalar(db, "SELECT quantity FROM components WHERE id = 'old' AND sku = 'C200' AND deleted = 0"))
                assertEquals(2, scalar(db, "SELECT quantity FROM components WHERE id = 'replacement' AND sku = 'C100' AND deleted = 0"))
                assertEquals(5, allocationQuantity(db, "old", "A"))
                assertEquals(2, allocationQuantity(db, "replacement", "A"))
            }
        }
    }

    @Test fun missingQueuedEntityIsReportedWithoutAcknowledgingIt(): Unit = runBlocking {
        withServer(cancelBom = false, requestCount = 2) { service, settings, _, _ ->
            withDatabase { enqueueEntity(it, "component", "missing-item", inventoryTime) }
            val result = service.runSync { settings }
            assertFalse(result.isSuccess)
            assertTrue(result.message.contains("missing-item"))
            withDatabase { assertEquals(1, scalar(it, "SELECT COUNT(*) FROM sync_queue")) }
        }
    }

    private fun withDatabase(block: (SQLiteDatabase) -> Unit) {
        val helper = InventoryDatabaseHelper(context)
        try { block(helper.writableDatabase) } finally { helper.close() }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    private fun pullSnapshot(vararg components: JSONObject): String = JSONObject()
        .put("inventory_protocol", 1).put("cost_protocol", 1).put("sync_cursor", 8)
        .put("server_time", inventoryTime).put("components", JSONArray(components.toList())).toString()

    private fun remoteComponent(id: String, sku: String, quantity: Int): JSONObject = JSONObject()
        .put("id", id).put("sku", sku).put("name", id).put("category", "category")
        .put("package_name", "0603").put("location", "A").put("quantity", quantity)
        .put("min_stock", 0).put("updated_at", inventoryTime).put("deleted", false)
        .put("allocations", JSONArray().put(JSONObject().put("location_id", "A").put("quantity", quantity)))

    private suspend fun withServer(cancelBom: Boolean, bomReply: (() -> String)? = null,
        pullReply: (() -> String)? = null, requestCount: Int = if (bomReply != null) 6 else 5,
        block: suspend (InventorySyncService, SyncConfiguration, CompletableFuture<Unit>, CompletableFuture<Unit>) -> Unit) {
        preferences.edit().clear().commit()
        File(context.filesDir, "bom-presets.json").delete()
        context.deleteDatabase("component-vault.db")
        val helper = InventoryDatabaseHelper(context)
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val settings = SyncConfiguration("test", "http://127.0.0.1:${server.localPort}", "key", false, "", "")
            preferences.edit().putString(InventoryPreferenceKeys.SERVER_BASE_URL, settings.serverBaseUrl)
                .putString(InventoryPreferenceKeys.API_TOKEN, settings.apiToken).commit()
            val bomStarted = CompletableFuture<Unit>()
            val releaseBom = CompletableFuture<Unit>()
            val responder = CompletableFuture.runAsync {
                repeat(requestCount) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(length) { reader.read() }
                        val body = when (index) {
                            0 -> JSONObject().put("inventory_protocol", 1).put("cost_protocol", 1).put("bom_preset_protocol", 1)
                                .put("photo_protocol", if (bomReply != null) 1 else 0).toString()
                            1 -> "{\"server_id\":\"server\",\"account_id\":\"account\",\"role\":\"admin\"}"
                            2 -> "{}"
                            3 -> pullReply?.invoke() ?: pullSnapshot()
                            4 -> {
                                bomStarted.complete(Unit)
                                if (cancelBom) releaseBom.get(10, TimeUnit.SECONDS)
                                bomReply?.invoke() ?: "{\"detail\":\"BOM unavailable\"}"
                            }
                            else -> "{\"photo_protocol\":1,\"photos\":[]}"
                        }
                        val bytes = body.toByteArray()
                        val code = if (index == 4 && bomReply == null) "503 Unavailable" else "200 OK"
                        runCatching {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                }
            }
            try {
                block(InventorySyncService(context, helper, preferences, Any()), settings, bomStarted, releaseBom)
                responder.get(10, TimeUnit.SECONDS)
            } finally { releaseBom.complete(Unit); helper.close() }
        }
    }
}
