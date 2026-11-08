package com.componentvault.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.componentvault.android.model.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class InventoryWorkbookService(
    private val databaseHelper: InventoryDatabaseHelper,
    private val inventoryImageStore: InventoryImageStore,
) {
    suspend fun exportInventoryWorkbook(): ByteArray = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.beginTransaction()
            try {
                val snapshot = readWorkbookSnapshot(db)
                val images = snapshot.components.mapNotNull { component ->
                    inventoryImageStore.resolve(component.description)?.let { component.id to it }
                }.toMap()
                val bytes = InventoryWorkbookWriter.write(snapshot.copy(images = images))
                InventoryWorkbookCodec.parse(bytes)
                bytes
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun previewInventoryWorkbook(bytes: ByteArray): InventoryRepository.WorkbookPreview =
        withContext(Dispatchers.IO) {
            val workbook = InventoryWorkbookCodec.parse(bytes)
            databaseHelper.readableDatabase.use { db ->
                db.beginTransaction()
                try {
                    val ids = querySet(db, "SELECT id FROM components")
                    val skus = querySet(db, "SELECT UPPER(sku) FROM components")
                    val locations = querySet(db, "SELECT id FROM storage_locations")
                    val movementIds = querySet(db, "SELECT id FROM stock_movements")
                    val conflictingComponents = workbook.movements
                        .filter { it.id in movementIds }
                        .map { it.componentId }
                        .toSet()
                    val fresh = freshComponents(workbook, ids, skus, conflictingComponents)
                    val warnings = buildList {
                        addAll(workbook.warnings)
                        if (conflictingComponents.isNotEmpty()) {
                            add("部分流水 ID 已存在；对应组件将按新记录合并规则跳过。")
                        }
                    }
                    InventoryRepository.WorkbookPreview(
                        workbook = workbook,
                        newComponentCount = fresh.size,
                        skippedComponentCount = workbook.components.size - fresh.size,
                        newLocationCount = workbook.locations.count { it.id !in locations },
                        warnings = warnings,
                    )
                } finally {
                    db.endTransaction()
                }
            }
        }

    suspend fun restoreInventoryWorkbook(
        preview: InventoryRepository.WorkbookPreview,
    ): OperationResult = withContext(Dispatchers.IO) {
        val createdImages = mutableListOf<String>()
        runCatching {
            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    val workbook = preview.workbook
                    check(!db.rawQuery(
                        "SELECT 1 FROM inventory_backup_imports WHERE fingerprint = ?",
                        arrayOf(workbook.fingerprint),
                    ).use { it.moveToFirst() }) { "该备份已经导入。" }

                    val ids = querySet(db, "SELECT id FROM components")
                    val skus = querySet(db, "SELECT UPPER(sku) FROM components")
                    val movementIds = querySet(db, "SELECT id FROM stock_movements")
                    val conflictingComponents = workbook.movements
                        .filter { it.id in movementIds }
                        .map { it.componentId }
                        .toSet()
                    val selected = freshComponents(workbook, ids, skus, conflictingComponents)
                        .map { attachImage(it, workbook, createdImages) }
                    val selectedIds = selected.map { it.id }.toSet()
                    val now = utcNow()
                    val deletedLocations = querySet(db, "SELECT id FROM storage_locations WHERE deleted = 1")
                    check(workbook.allocations.none {
                        it.componentId in selectedIds && it.quantity > 0 && it.locationId in deletedLocations
                    }) { "导入的正库存不能分配到本地已删除库位。" }

                    workbook.locations.forEach { location ->
                        db.insertWithOnConflict(
                            "storage_locations", null,
                            ContentValues().apply {
                                put("id", location.id)
                                put("name", location.name)
                                put("updated_at", location.updatedAt)
                                put("deleted", if (location.deleted) 1 else 0)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                    selected.forEach { component ->
                        db.insertOrThrow("components", null, ContentValues().apply {
                            put("id", component.id)
                            put("sku", component.sku)
                            put("name", component.name)
                            put("category", component.category)
                            put("package_name", component.packageName)
                            put("location", component.location)
                            put("description", component.description)
                            put("quantity", component.quantity)
                            put("min_stock", component.minStock)
                            put("updated_at", component.updatedAt)
                            put("deleted", if (component.deleted) 1 else 0)
                            put("base_updated_at", component.baseUpdatedAt)
                            put("inventory_unit_cost", component.inventoryUnitCost)
                        })
                    }
                    workbook.allocations.filter { it.componentId in selectedIds }.forEach { allocation ->
                        setAllocationQuantity(db, allocation.componentId, allocation.locationId, allocation.quantity)
                    }
                    if (workbook.source == WorkbookSource.ComponentVault) {
                        restoreOriginalMovements(db, workbook, selectedIds)
                    } else {
                        createInitialMovements(db, workbook, selected, now)
                    }
                    selected.forEach { component ->
                        verifyAllocationTotal(db, component.id, component.quantity)
                        enqueueEntity(db, "component", component.id, component.updatedAt)
                    }
                    db.insertOrThrow("inventory_backup_imports", null, ContentValues().apply {
                        put("fingerprint", workbook.fingerprint)
                        put("source", workbook.source.name)
                        put("imported_at", now)
                    })
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            OperationResult(
                true,
                "已新增导入 ${preview.newComponentCount} 个元器件；跳过 ${preview.skippedComponentCount} 个现有记录。",
            )
        }.getOrElse { error ->
            createdImages.forEach(inventoryImageStore::delete)
            OperationResult(false, error.message ?: "库存恢复失败。")
        }
    }

    private fun querySet(db: SQLiteDatabase, sql: String): Set<String> =
        db.rawQuery(sql, null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun freshComponents(
        workbook: InventoryWorkbook,
        ids: Set<String>,
        skus: Set<String>,
        conflictingMovementComponents: Set<String>,
    ): List<WorkbookComponent> = workbook.components.filter {
        it.id !in ids && it.sku.uppercase() !in skus && it.id !in conflictingMovementComponents
    }

    private fun attachImage(
        component: WorkbookComponent,
        workbook: InventoryWorkbook,
        createdImages: MutableList<String>,
    ): WorkbookComponent {
        val image = workbook.images[component.id] ?: return component
        val marker = WorkbookImageCodec.marker(image)
        if (!inventoryImageStore.exists(marker)) createdImages += marker
        inventoryImageStore.persist(image)
        val description = component.description.lineSequence()
            .filterNot { it.startsWith("本地图片：") }
            .joinToString("\n")
            .trim()
            .let { base -> listOf(base, marker).filter { it.isNotBlank() }.joinToString("\n") }
        return component.copy(description = description)
    }

    private fun restoreOriginalMovements(
        db: SQLiteDatabase,
        workbook: InventoryWorkbook,
        selectedIds: Set<String>,
    ) {
        workbook.movements.filter { it.componentId in selectedIds }.forEach { movement ->
            db.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("id", movement.id)
                put("component_id", movement.componentId)
                put("movement_type", movement.type)
                put("quantity", movement.quantity)
                put("reason", movement.reason)
                put("note", movement.note)
                put("happened_at", movement.happenedAt)
                put("updated_at", movement.updatedAt)
                put("deleted", if (movement.deleted) 1 else 0)
                put("location_id", movement.locationId)
                put("destination_location_id", movement.destinationLocationId)
                put("purchase_amount", movement.purchaseAmount)
                put("cost_amount", movement.costAmount)
            })
            enqueueEntity(db, "stock_movement", movement.id, movement.updatedAt)
        }
    }

    private fun createInitialMovements(
        db: SQLiteDatabase,
        workbook: InventoryWorkbook,
        selected: List<WorkbookComponent>,
        now: String,
    ) {
        selected.filter { it.quantity > 0 }.forEach { component ->
            workbook.allocations.filter { it.componentId == component.id && it.quantity > 0 }
                .forEach { allocation ->
                    val id = "mov-" + randomId()
                    db.insertOrThrow("stock_movements", null, ContentValues().apply {
                        put("id", id)
                        put("component_id", component.id)
                        put("movement_type", "inbound")
                        put("quantity", allocation.quantity)
                        put("reason", "LCSC schema1 initial inventory")
                        put("note", "Imported source fingerprint " + workbook.fingerprint)
                        put("happened_at", now)
                        put("updated_at", now)
                        put("deleted", 0)
                        put("location_id", allocation.locationId)
                    })
                    enqueueEntity(db, "stock_movement", id, now)
                }
        }
    }

    private fun readWorkbookSnapshot(db: SQLiteDatabase): InventoryWorkbook {
        val components = db.rawQuery(
            "SELECT id,sku,name,category,package_name,location,COALESCE(description,''),quantity,min_stock,updated_at,deleted,base_updated_at,inventory_unit_cost FROM components ORDER BY id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(WorkbookComponent(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getInt(7),
                    cursor.getInt(8), cursor.getString(9), cursor.getInt(10) == 1, cursor.getString(11), cursor.getString(12),
                ))
            }
        }
        val locations = db.rawQuery(
            "SELECT id,name,updated_at,deleted FROM storage_locations ORDER BY id", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(WorkbookLocation(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1,
                ))
            }
        }
        val allocations = db.rawQuery(
            "SELECT component_id,location_id,quantity FROM component_allocations ORDER BY component_id,location_id", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(WorkbookAllocation(
                    cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                ))
            }
        }
        val movements = db.rawQuery(
            "SELECT id,component_id,movement_type,quantity,reason,COALESCE(note,''),happened_at,updated_at,deleted,location_id,destination_location_id,purchase_amount,cost_amount FROM stock_movements ORDER BY id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(WorkbookMovement(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7),
                    cursor.getInt(8) == 1, cursor.getString(9), cursor.getString(10), cursor.getString(11), cursor.getString(12),
                ))
            }
        }
        return InventoryWorkbook(
            WorkbookSource.ComponentVault, components, locations, allocations, movements, "", emptyList(),
        )
    }
}
