package com.componentvault.android.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SyncMovementNullableLocationTest {
    @Test fun pullPreservesNullableReferencesAndRepairsOnlyDanglingNullStrings() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("component-vault.db")
        val helper = InventoryDatabaseHelper(context)
        try {
            val db = helper.writableDatabase
            val service = InventorySyncService(context, helper,
                context.getSharedPreferences("nullable_location_test", Context.MODE_PRIVATE), Any())
            db.execSQL("INSERT INTO storage_locations(id, name, updated_at, deleted) VALUES ('shelf', 'Shelf', '2026-10-28T00:00:00Z', 0)")
            applyPull(service, db, listOf(movement("json-null", JSONObject.NULL, JSONObject.NULL),
                movement("legacy-null", "null", "null"), movement("real", "shelf", "shelf")))
            assertLocations(db, "json-null", null)
            assertLocations(db, "legacy-null", null)
            assertLocations(db, "real", "shelf")

            // Simulate already downloaded corruption with no new remote movement.
            db.execSQL("UPDATE stock_movements SET location_id = 'null', destination_location_id = 'null' WHERE id = 'json-null'")
            val outgoingMethod = InventorySyncService::class.java.getDeclaredMethod("getMovementJson",
                SQLiteDatabase::class.java, String::class.java)
            outgoingMethod.isAccessible = true
            val outgoing = outgoingMethod.invoke(service, db, "json-null") as JSONObject
            assertTrue(outgoing.isNull("location_id"))
            assertTrue(outgoing.isNull("destination_location_id"))
            applyPull(service, db, emptyList())
            assertLocations(db, "json-null", null)
            db.rawQuery("SELECT COUNT(*), SUM(quantity), MIN(updated_at) FROM stock_movements", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
                assertEquals(21, it.getInt(1))
                assertEquals("2026-10-28T00:00:00Z", it.getString(2))
            }

            // The literal ID is not reserved: an actual matching location is preserved.
            db.execSQL("INSERT INTO storage_locations(id, name, updated_at, deleted) VALUES ('null', 'null', '2026-10-28T00:00:00Z', 0)")
            applyPull(service, db, listOf(movement("named-null", "null", "null")))
            assertLocations(db, "named-null", "null")
        } finally {
            helper.close()
            context.deleteDatabase("component-vault.db")
        }
    }

    private fun movement(id: String, source: Any, destination: Any) = JSONObject()
        .put("id", id).put("component_id", "component").put("movement_type", "in")
        .put("quantity", 7).put("reason", "test").put("note", "preserve")
        .put("happened_at", "2026-10-28T00:00:00Z").put("updated_at", "2026-10-28T00:00:00Z")
        .put("deleted", false).put("location_id", source).put("destination_location_id", destination)

    private fun applyPull(service: InventorySyncService, db: SQLiteDatabase, movements: List<JSONObject>) {
        val method = InventorySyncService::class.java.getDeclaredMethod("applyPullResponse",
            SQLiteDatabase::class.java, JSONObject::class.java, List::class.java)
        method.isAccessible = true
        db.beginTransaction()
        try {
            method.invoke(service, db, JSONObject().put("inventory_protocol", 1)
                .put("stock_movements", JSONArray(movements)), emptyList<Any>())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun assertLocations(db: SQLiteDatabase, id: String, expected: String?) {
        db.rawQuery("SELECT location_id, destination_location_id FROM stock_movements WHERE id = ?", arrayOf(id)).use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getString(0))
            assertEquals(expected, it.getString(1))
        }
    }
}
