package com.componentvault.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import com.componentvault.android.R
import com.componentvault.android.model.InventoryConflictComponent
import com.componentvault.android.model.InventoryConflictLocation
import com.componentvault.android.model.InventoryConflictMovement
import com.componentvault.android.model.InventoryConflictMovementEdit
import com.componentvault.android.model.InventoryConflictReview
import com.componentvault.android.model.MovementEntryDraft
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal class InventoryConflictReplay(
    private val context: Context,
    private val sync: InventorySyncService,
) {
    fun review(db: SQLiteDatabase, snapshot: JSONObject): InventoryConflictReview {
        val remoteComponents = snapshot.objects("components").associateBy { it.getString("id") }
        val remoteMovements = snapshot.objects("stock_movements").associateBy { it.getString("id") }
        val locations = snapshot.objects("storage_locations").filterNot { it.getBoolean("deleted") }
        val queued = db.rawQuery(
            "SELECT entity_id FROM sync_queue WHERE entity_type = 'component' ORDER BY created_at, entity_id",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return InventoryConflictReview(queued.mapNotNull { id ->
            val local = sync.getComponentJson(db, id) ?: return@mapNotNull null
            val remote = remoteComponents[id] ?: return@mapNotNull null
            val serverVersion = remote.getString("updated_at")
            val base = local.nullableString("base_updated_at")
            val localVersion = local.getString("updated_at")
            val sameBase = base != null && SyncProtocol.timestampsEqual(base, serverVersion)
            if (sameBase && !SyncProtocol.timestampsEqual(localVersion, serverVersion)) return@mapNotNull null
            val movements = pendingMovements(db, id)
            val unsent = movements.filterNot { it.getString("id") in remoteMovements }
            val acknowledged = movements.filter { it.getString("id") in remoteMovements }
            val acknowledgedMatch = acknowledged.all {
                sameMovement(it, remoteMovements.getValue(it.getString("id")))
            }
            // A committed push whose reply was lost remains an ordinary idempotent retry.
            if (unsent.isEmpty() && acknowledgedMatch && sameComponent(local, remote)) return@mapNotNull null
            val blocked = when {
                remote.getBoolean("deleted") || local.getBoolean("deleted") -> R.string.sync_replay_deleted_component
                remote.optJSONArray("allocations") == null -> R.string.sync_replay_legacy_component
                unsent.isEmpty() || !acknowledgedMatch || unsent.any {
                    it.getBoolean("deleted") || it.getString("movement_type") !in setOf("inbound", "outbound") ||
                        it.getInt("quantity") <= 0
                } -> R.string.sync_replay_unsupported_movement
                locations.isEmpty() -> R.string.sync_replay_location_unavailable
                else -> null
            }
            val allocations = remote.objects("allocations").associate { it.getString("location_id") to it.getInt("quantity") }
            InventoryConflictComponent(
                id = id,
                sku = remote.getString("sku"),
                name = remote.getString("name"),
                serverQuantity = remote.getInt("quantity"),
                locations = locations.map {
                    val locationId = it.getString("id")
                    InventoryConflictLocation(locationId, it.getString("name"), allocations[locationId] ?: 0)
                },
                movements = unsent.map {
                    InventoryConflictMovement(it.getString("id"), it.getString("movement_type"), it.getInt("quantity"),
                        it.nullableString("location_id"), it.nullableString("purchase_amount"), it.getString("reason"))
                },
                blockedReason = blocked?.let(::text),
                serverVersion = serverVersion,
                localVersion = localVersion,
            )
        })
    }

    fun replay(
        db: SQLiteDatabase,
        snapshot: JSONObject,
        preview: InventoryConflictComponent,
        edits: List<InventoryConflictMovementEdit>,
        writer: InventoryWriteService,
    ): String {
        val current = review(db, snapshot).components.firstOrNull { it.id == preview.id }
        check(current != null && current.serverVersion == preview.serverVersion &&
            current.localVersion == preview.localVersion && current.movements == preview.movements &&
            current.locations == preview.locations
        ) { text(R.string.sync_replay_stale) }
        check(current.blockedReason == null) { requireNotNull(current.blockedReason) }
        val queued = pendingMovements(db, current.id)
        val unsentIds = current.movements.mapTo(mutableSetOf()) { it.id }
        val pending = queued.filter { it.getString("id") in unsentIds }
        val editsById = edits.associateBy { it.id }
        check(edits.size == pending.size && editsById.keys == pending.mapTo(mutableSetOf()) { it.getString("id") }) {
            text(R.string.sync_replay_stale)
        }
        val activeLocations = current.locations.mapTo(mutableSetOf()) { it.id }
        val drafts = pending.map { movement ->
            val edit = editsById.getValue(movement.getString("id"))
            check(edit.locationId in activeLocations) { text(R.string.sync_replay_location_unavailable) }
            val purchase = movement.nullableString("purchase_amount")
            check(purchase == null || edit.quantity == movement.getInt("quantity")) {
                text(R.string.sync_replay_purchase_quantity)
            }
            MovementEntryDraft(
                componentId = current.id,
                movementType = movement.getString("movement_type"),
                quantity = edit.quantity,
                reason = movement.getString("reason"),
                note = movement.nullableString("note").orEmpty(),
                locationId = edit.locationId,
                purchaseAmount = purchase,
            ).also(writer::validateMovementDraft)
        }

        val remote = snapshot.objects("components").first { it.getString("id") == current.id }
        val local = checkNotNull(sync.getComponentJson(db, current.id))
        // Preserve prior metadata, quantities, cost and movement history for audit.
        db.insertOrThrow("inventory_replay_history", null, ContentValues().apply {
            put("component_id", current.id)
            put("created_at", utcNow())
            put("local_snapshot", JSONObject().put("component", local).put("movements", JSONArray(queued)).toString())
        })
        val neededLocations = remote.objects("allocations").mapTo(mutableSetOf()) { it.getString("location_id") }
        drafts.mapNotNullTo(neededLocations) { it.locationId }
        snapshot.objects("storage_locations").filter { it.getString("id") in neededLocations }.forEach {
            sync.upsertRemoteStorageLocation(db, it)
        }
        // Catch local unsynced deletion of a server location before the normal writer could recreate it.
        for (id in drafts.mapNotNull { it.locationId }.distinct()) {
            check(db.rawQuery("SELECT 1 FROM storage_locations WHERE id = ? AND deleted = 0", arrayOf(id))
                .use { it.moveToFirst() }) { text(R.string.sync_replay_location_unavailable) }
        }
        sync.upsertRemoteComponent(db, remote)
        writer.applyMovementDrafts(db, drafts, previousMovements = pending)
        for (movement in queued) {
            val id = movement.getString("id")
            if (id !in unsentIds) {
                db.delete("sync_queue", "entity_type = 'stock_movement' AND entity_id = ?", arrayOf(id))
            }
        }
        return checkNotNull(sync.getComponentJson(db, current.id)).getString("updated_at")
    }

    private fun pendingMovements(db: SQLiteDatabase, id: String): List<JSONObject> =
        db.rawQuery(
            """
            SELECT m.id FROM stock_movements m JOIN sync_queue q
                ON q.entity_type = 'stock_movement' AND q.entity_id = m.id
            WHERE m.component_id = ? ORDER BY q.created_at, m.id
            """.trimIndent(),
            arrayOf(id),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) sync.getMovementJson(db, cursor.getString(0))?.let(::add)
            }.sortedBy { Instant.parse(it.getString("happened_at")) }
        }

    private fun sameComponent(local: JSONObject, remote: JSONObject): Boolean {
        if (!SyncProtocol.timestampsEqual(local.getString("updated_at"), remote.getString("updated_at"))) return false
        val fields = listOf("sku", "name", "category", "package_name", "location", "description", "quantity",
            "min_stock", "inventory_unit_cost", "deleted")
        return fields.all { local.nullableString(it) == remote.nullableString(it) } &&
            local.objects("allocations").associate { it.getString("location_id") to it.getInt("quantity") } ==
            remote.objects("allocations").associate { it.getString("location_id") to it.getInt("quantity") }
    }

    private fun sameMovement(local: JSONObject, remote: JSONObject): Boolean {
        val fields = listOf("component_id", "movement_type", "quantity", "reason", "note", "deleted",
            "location_id", "destination_location_id", "purchase_amount", "cost_amount")
        return fields.all { local.nullableString(it) == remote.nullableString(it) } &&
            listOf("updated_at", "happened_at").all {
                SyncProtocol.timestampsEqual(local.getString(it), remote.getString(it))
            }
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { items ->
        List(items.length(), items::getJSONObject)
    }.orEmpty()

    private fun text(@StringRes id: Int): String = context.getString(id)
}
