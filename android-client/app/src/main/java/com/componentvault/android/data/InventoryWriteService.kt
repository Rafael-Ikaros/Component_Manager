package com.componentvault.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import com.componentvault.android.R
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationSaveIntent
import com.componentvault.android.model.parseImportDescription
import com.componentvault.android.data.bom.BomInboundItem
import com.componentvault.android.data.bom.BomProcurementStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal class InventoryWriteService(
    private val appContext: Context,
    private val databaseHelper: InventoryDatabaseHelper,
    private val catalogService: InventoryImportCatalog,
) {
    suspend fun saveComponent(draft: ComponentDraft): OperationResult = withContext(Dispatchers.IO) {
        try {
            validateComponentDraft(draft)
            var componentId: String? = null

            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    componentId = upsertComponent(db, draft)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            OperationResult(
                isSuccess = true,
                message = if (draft.id == null) {
                    text(R.string.sync_component_created_local)
                } else {
                    text(R.string.sync_component_saved_local)
                },
                entityId = componentId,
            )
        } catch (exception: Exception) {
            OperationResult(
                isSuccess = false,
                message = exception.message ?: text(R.string.sync_component_save_failed),
            )
        }
    }

    suspend fun saveImportedComponent(
        draft: ComponentDraft,
        sourceCandidate: ComponentImportCandidate?,
        appPreferences: AppPreferences,
    ): OperationResult = withContext(Dispatchers.IO) {
        try {
            validateComponentDraft(draft)
            val parsedDescription = parseImportDescription(draft.description)
            var componentId: String? = null

            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    componentId = upsertComponent(db, draft)
                    if (
                        appPreferences.enableLocalImportLearning &&
                        sourceCandidate != null &&
                        sourceCandidate.sourceType != com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel
                    ) {
                        catalogService.upsertImportLearningMapping(
                            db = db,
                            sourceCandidate = sourceCandidate,
                            draft = draft,
                            parsedDescription = parsedDescription,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            if (appPreferences.enableLocalImportLearning && sourceCandidate != null &&
                draft.category.trim() != sourceCandidate.category.trim()
            ) {
                CategoryLearningStore(appContext).learn(sourceCandidate, draft.category)
            }

            val reminder = if (draft.id == null && draft.quantity > 0)
                remindInbound(listOf(BomInboundItem(draft.sku, parsedDescription.model, draft.packageName)))
            else ""

            OperationResult(
                isSuccess = true,
                message = if (draft.id == null) {
                    text(R.string.sync_component_created_local)
                } else {
                    text(R.string.sync_component_saved_local)
                } + reminder,
                entityId = componentId,
            )
        } catch (exception: Exception) {
            OperationResult(
                isSuccess = false,
                message = exception.message ?: text(R.string.sync_component_save_failed),
            )
        }
    }

    suspend fun softDeleteComponent(componentId: String): OperationResult = withContext(Dispatchers.IO) {
        if (componentId.isBlank()) {
            return@withContext OperationResult(false, text(R.string.sync_select_component_first))
        }

        databaseHelper.writableDatabase.use { db ->
            db.beginTransaction()
            try {
                val updatedAt = utcNow()
                val values = ContentValues().apply {
                    put("deleted", 1)
                    put("updated_at", updatedAt)
                }
                val affectedRows = db.update(
                    "components",
                    values,
                    "id = ?",
                    arrayOf(componentId),
                )
                if (affectedRows == 0) {
                    return@withContext OperationResult(false, text(R.string.sync_component_not_found))
                }

                enqueueEntity(db, "component", componentId, updatedAt)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        OperationResult(
            isSuccess = true,
            message = text(R.string.sync_component_deleted_local),
        )
    }

    suspend fun recordMovement(draft: MovementEntryDraft): OperationResult = withContext(Dispatchers.IO) {
        try {
            validateMovementDraft(draft)

            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    applyMovementDrafts(db, listOf(draft))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            OperationResult(
                isSuccess = true,
                message = text(R.string.sync_movement_recorded_local),
            )
        } catch (exception: Exception) {
            OperationResult(
                isSuccess = false,
                message = exception.message ?: text(R.string.sync_movement_record_failed),
            )
        }
    }

    internal suspend fun appendImportedStock(
        target: ExistingImportTarget,
        quantity: Int,
        locationId: String,
        purchaseAmount: String? = null,
    ): OperationResult = withContext(Dispatchers.IO) {
        runCatching {
            require(quantity > 0) { "入库数量必须为正整数。" }
            val location = locationId.trim()
            require(location.isNotBlank()) { "请选择入库库位。" }
            var newTotal = 0
            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    val current = db.rawQuery(
                        "SELECT quantity,updated_at FROM components WHERE id=? AND deleted=0",
                        arrayOf(target.componentId),
                    ).use { cursor ->
                        check(cursor.moveToFirst()) { "元器件已不存在，请重新核对。" }
                        cursor.getInt(0) to cursor.getString(1)
                    }
                    check(current.second == target.updatedAt) { "库存已发生变化，请重新核对后再确认。" }
                    check(db.rawQuery("SELECT 1 FROM storage_locations WHERE id=? AND deleted=0", arrayOf(location)).use { it.moveToFirst() }) {
                        "库位不存在或已删除。"
                    }
                    newTotal = Math.addExact(current.first, quantity)
                    val newAllocation = Math.addExact(allocationQuantity(db, target.componentId, location), quantity)
                    val now = utcNow()
                    db.update("components", ContentValues().apply {
                        put("inventory_unit_cost", InventoryCost.inbound(current.first, getComponentById(db, target.componentId)?.inventoryUnitCost, quantity, purchaseAmount)); put("quantity", newTotal); put("location", location); put("updated_at", now)
                    }, "id=?", arrayOf(target.componentId))
                    setAllocationQuantity(db, target.componentId, location, newAllocation)
                    verifyAllocationTotal(db, target.componentId, newTotal)
                    val movementId = "mov-" + randomId()
                    db.insertOrThrow("stock_movements", null, ContentValues().apply {
                        put("id", movementId); put("component_id", target.componentId); put("movement_type", "inbound")
                        put("purchase_amount", InventoryCost.text(InventoryCost.decimal(purchaseAmount))); put("cost_amount", InventoryCost.text(InventoryCost.decimal(purchaseAmount)))
                        put("quantity", quantity); put("reason", "JLC single import inbound"); put("note", "")
                        put("happened_at", now); put("updated_at", now); put("deleted", 0); put("location_id", location)
                    })
                    enqueueEntity(db, "component", target.componentId, now)
                    enqueueEntity(db, "stock_movement", movementId, now)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            OperationResult(true, "已追加 $quantity 个，当前库存 $newTotal。" +
                remindInbound(listOf(inboundForComponent(target.componentId, target.sku))), target.componentId)
        }.getOrElse { OperationResult(false, it.message ?: "追加库存失败。") }
    }

    internal suspend fun loadBatchJlcReceipts(sessionId: String): Set<String> = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db -> readBatchJlcReceipts(db, sessionId) }
    }

    internal suspend fun commitBatchJlc(
        sessionId: String,
        rows: List<BatchJlcRow>,
    ): OperationResult = withContext(Dispatchers.IO) {
        runCatching {
            require(rows.isNotEmpty()) { "没有可提交的包装。" }
            var committedCount = 0
            var skippedCount = 0
            var committedItems = emptyList<BomInboundItem>()
            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    val already = readBatchJlcReceipts(db, sessionId)
                    skippedCount = rows.count {
                        it.selected && it.status == BatchJlcStatus.Ready && it.id in already
                    }
                    val planned = BatchJlcCommitPlanner.plan(rows, already)
                    val now = utcNow()
                    planned.forEach { item ->
                        val row = item.row
                        val sku = item.sku
                        val quantity = item.quantity
                        val component = db.rawQuery(
                            "SELECT id,quantity FROM components WHERE UPPER(sku)=? AND deleted=0 LIMIT 2",
                            arrayOf(sku),
                        ).use { cursor ->
                            if (!cursor.moveToFirst()) null else {
                                (cursor.getString(0) to cursor.getInt(1)).also {
                                    check(!cursor.moveToNext()) {
                                        "存在多个大小写不同但料号相同的活跃元器件，请先合并冲突记录。"
                                    }
                                }
                            }
                        }
                        val componentId = component?.first ?: "cmp-" + randomId()
                        val oldTotal = component?.second ?: 0
                        val location = row.location.trim()
                        val activeLocation = db.rawQuery(
                            "SELECT 1 FROM storage_locations WHERE id=? AND deleted=0",
                            arrayOf(location),
                        ).use { it.moveToFirst() }
                        check(activeLocation) { "库位 ${row.location} 不存在或已删除。" }
                        if (component == null) {
                            require(row.name.isNotBlank() && row.category.isNotBlank() && row.packageName.isNotBlank()) {
                                "新料号 $sku 缺少名称、分类或封装。"
                            }
                            db.insertOrThrow("components", null, ContentValues().apply {
                                put("id", componentId)
                                put("sku", sku)
                                put("name", row.name.trim())
                                put("category", row.category.trim())
                                put("package_name", row.packageName.trim())
                                put("location", location)
                                put("description", row.description.trim())
                                put("quantity", 0)
                                put("min_stock", 0)
                                put("updated_at", now)
                                putNull("base_updated_at")
                            })
                            setAllocationQuantity(db, componentId, location, 0)
                        }
                        val average = getComponentById(db, componentId)?.inventoryUnitCost
                        val nextAverage = InventoryCost.inbound(oldTotal, average, quantity, row.purchaseAmount)
                        val oldAllocation = allocationQuantity(db, componentId, location)
                        val newTotal = Math.addExact(oldTotal, quantity)
                        val newAllocation = Math.addExact(oldAllocation, quantity)
                        db.update("components", ContentValues().apply {
                            put("quantity", newTotal)
                            put("inventory_unit_cost", nextAverage)
                            put("location", location)
                            put("updated_at", now)
                        }, "id=?", arrayOf(componentId))
                        setAllocationQuantity(db, componentId, location, newAllocation)
                        verifyAllocationTotal(db, componentId, newTotal)

                        val movementId = "mov-" + randomId()
                        db.insertOrThrow("stock_movements", null, ContentValues().apply {
                            put("id", movementId)
                            put("component_id", componentId)
                            put("movement_type", "inbound")
                            put("quantity", quantity)
                            put("purchase_amount", InventoryCost.text(InventoryCost.decimal(row.purchaseAmount)))
                            put("cost_amount", InventoryCost.text(InventoryCost.decimal(row.purchaseAmount)))
                            put("reason", if (row.id.startsWith("order-")) {
                                "LCSC order statement inbound"
                            } else {
                                "JLC batch inbound"
                            })
                            put("note", "")
                            put("happened_at", now)
                            put("updated_at", now)
                            put("deleted", 0)
                            put("location_id", location)
                        })
                        enqueueEntity(db, "component", componentId, now)
                        enqueueEntity(db, "stock_movement", movementId, now)
                        db.insertOrThrow("batch_jlc_receipts", null, ContentValues().apply {
                            put("row_id", row.id)
                            put("session_id", sessionId)
                            put("component_id", componentId)
                            put("movement_id", movementId)
                            put("committed_at", now)
                        })
                    }
                    committedCount = planned.size
                    committedItems = planned.map { item ->
                        BomInboundItem(item.sku, parseImportDescription(item.row.description).model,
                            item.row.packageName)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            OperationResult(true, "已批量入库 $committedCount 条明细；跳过已提交 $skippedCount 条。" +
                remindInbound(committedItems))
        }.getOrElse { OperationResult(false, it.message ?: "批量入库失败。") }
    }

    private fun readBatchJlcReceipts(db: SQLiteDatabase, sessionId: String): Set<String> =
        db.rawQuery(
            "SELECT row_id FROM batch_jlc_receipts WHERE session_id = ? OR row_id LIKE 'order-%'",
            arrayOf(sessionId),
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    suspend fun saveStorageLocation(
        id: String,
        name: String,
        intent: StorageLocationSaveIntent,
    ): OperationResult = withContext(Dispatchers.IO) {
        runCatching {
            val code = id.trim()
            val displayName = name.trim()
            require(code.isNotEmpty() && code.length <= 120 && displayName.isNotEmpty() && displayName.length <= 200)
            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    val existingDeleted = db.rawQuery(
                        "SELECT deleted FROM storage_locations WHERE id = ?",
                        arrayOf(code),
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }
                    val now = utcNow()
                    when (intent) {
                        StorageLocationSaveIntent.Create -> {
                            check(existingDeleted == null) { "库位编号已存在。" }
                            val inserted = db.insertOrThrow("storage_locations", null, ContentValues().apply {
                                put("id", code)
                                put("name", displayName)
                                put("updated_at", now)
                                put("deleted", 0)
                            })
                            check(inserted != -1L) { "库位新增失败。" }
                        }
                        StorageLocationSaveIntent.Edit -> {
                            check(existingDeleted == false) { "库位不存在或已删除。" }
                            val updated = db.update("storage_locations", ContentValues().apply {
                                put("name", displayName)
                                put("updated_at", now)
                            }, "id = ? AND deleted = 0", arrayOf(code))
                            check(updated == 1) { "库位不存在或已删除。" }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            OperationResult(
                true,
                if (intent == StorageLocationSaveIntent.Create) "库位已新增。" else "库位已更新。",
                code,
            )
        }.getOrElse { OperationResult(false, it.message ?: "库位保存失败。") }
    }

    suspend fun deleteStorageLocation(id: String): OperationResult = withContext(Dispatchers.IO) {
        runCatching {
            databaseHelper.writableDatabase.use { db ->
                val occupied = db.rawQuery(
                    """SELECT 1 FROM component_allocations a JOIN components c ON c.id=a.component_id
                       WHERE a.location_id = ? AND a.quantity > 0 AND c.deleted = 0 LIMIT 1""",
                    arrayOf(id),
                ).use { it.moveToFirst() }
                check(!occupied) { "该库位仍有库存，不能删除。" }
                val count = db.update("storage_locations", ContentValues().apply {
                    put("deleted", 1); put("updated_at", utcNow())
                }, "id = ? AND deleted = 0", arrayOf(id))
                check(count == 1) { "库位不存在。" }
            }
            OperationResult(true, "空库位已删除。", id)
        }.getOrElse { OperationResult(false, it.message ?: "库位删除失败。") }
    }

    suspend fun recordMovementsBatch(drafts: List<MovementEntryDraft>): OperationResult = withContext(Dispatchers.IO) {
        try {
            if (drafts.isEmpty()) {
                throw IllegalStateException(text(R.string.sync_movement_batch_empty))
            }
            drafts.forEach(::validateMovementDraft)

            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    applyMovementDrafts(db, drafts)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            OperationResult(
                isSuccess = true,
                message = text(R.string.sync_movement_batch_recorded_local, drafts.size),
            )
        } catch (exception: Exception) {
            OperationResult(
                isSuccess = false,
                message = exception.message ?: text(R.string.sync_movement_record_failed),
            )
        }
    }

    internal suspend fun transferComponentsBatch(
        sourceLocationId: String,
        destinationLocationId: String,
        lines: List<BatchTransferLine>,
    ): OperationResult = withContext(Dispatchers.IO) {
        try {
            val source = sourceLocationId.trim()
            val destination = destinationLocationId.trim()
            require(source.isNotEmpty() && destination.isNotEmpty() && source != destination) {
                text(R.string.batch_transfer_invalid_locations)
            }
            require(lines.isNotEmpty() && lines.map { it.componentId }.distinct().size == lines.size) {
                text(R.string.batch_transfer_invalid_selection)
            }
            require(lines.all { it.componentId.isNotBlank() && it.quantity > 0 && it.expectedUpdatedAt.isNotBlank() }) {
                text(R.string.batch_transfer_invalid_quantity)
            }

            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    for (locationId in listOf(source, destination)) {
                        val active = db.rawQuery(
                            "SELECT 1 FROM storage_locations WHERE id = ? AND deleted = 0",
                            arrayOf(locationId),
                        ).use { it.moveToFirst() }
                        check(active) { text(R.string.batch_transfer_location_gone) }
                    }
                    lines.forEach { line ->
                        val component = getComponentById(db, line.componentId)
                            ?: throw IllegalStateException(text(R.string.batch_transfer_component_gone))
                        check(!component.deleted) {
                            text(R.string.batch_transfer_component_gone)
                        }
                        check(component.updatedAt == line.expectedUpdatedAt) {
                            text(R.string.batch_transfer_stale)
                        }
                        val sourceQuantity = allocationQuantity(db, line.componentId, source)
                        check(sourceQuantity >= line.quantity) { text(R.string.batch_transfer_insufficient) }
                        StockAllocationMath.transfer(
                            sourceQuantity,
                            allocationQuantity(db, line.componentId, destination),
                            line.quantity,
                        )
                    }
                    applyMovementDrafts(db, lines.map { line ->
                        MovementEntryDraft(
                            componentId = line.componentId,
                            movementType = "transfer",
                            quantity = line.quantity,
                            reason = text(R.string.batch_transfer_reason),
                            note = "",
                            locationId = source,
                            destinationLocationId = destination,
                        )
                    })
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            OperationResult(true, text(R.string.sync_movement_batch_recorded_local, lines.size))
        } catch (exception: Exception) {
            OperationResult(false, exception.message ?: text(R.string.sync_movement_record_failed))
        }
    }

    private fun upsertComponent(
        db: SQLiteDatabase,
        draft: ComponentDraft,
    ): String {
        val existingComponent = draft.id?.let { getComponentById(db, it) }
        if (draft.id != null) {
            check(existingComponent != null && !existingComponent.deleted &&
                existingComponent.updatedAt == draft.expectedUpdatedAt
            ) { text(R.string.sync_component_edit_stale) }
        }
        ensureUniqueActiveSku(db, draft.sku.trim(), draft.id)
        val updatedAt = utcNow()
        val componentId = draft.id ?: "cmp-${randomId()}"
        val oldTotal = existingComponent?.quantity ?: 0
        require(draft.purchaseAmount == null || draft.quantity > 0) { "填写采购金额时数量必须大于零。" }
        val valuationTotal = InventoryCost.decimal(draft.valuationAmount)
        val valuation = InventoryCost.text(if (valuationTotal != null) {
            require(draft.quantity > 0) { "零库存不能补录成本。" }
            valuationTotal.divide(java.math.BigDecimal(draft.quantity),16,java.math.RoundingMode.HALF_EVEN)
        } else InventoryCost.decimal(draft.valuationUnitCost, 16))
        require(valuation == null || draft.quantity > 0) { "零库存不能补录成本。" }
        val baseUpdatedAt = draft.id?.let { id ->
            db.rawQuery("SELECT base_updated_at FROM components WHERE id = ?", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
        ensureStorageLocation(db, draft.location.trim(), updatedAt)

        val values = ContentValues().apply {
            put("id", componentId)
            put("sku", draft.sku.trim())
            put("name", draft.name.trim())
            put("category", draft.category.trim())
            put("package_name", draft.packageName.trim())
            put("location", draft.location.trim())
            put("description", draft.description.trim())
            put("quantity", draft.quantity)
            put("inventory_unit_cost", (valuation ?: if (existingComponent == null && draft.quantity > 0) InventoryCost.inbound(0, null, draft.quantity, draft.purchaseAmount) else existingComponent?.inventoryUnitCost).takeIf { draft.quantity > 0 })
            put("min_stock", draft.minStock)
            put("updated_at", updatedAt)
            put("deleted", 0)
            put("base_updated_at", baseUpdatedAt)
        }
        db.insertWithOnConflict(
            "components",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val locationId = draft.location.trim()
        if (existingComponent != null && existingComponent.location.trim() != locationId) {
            db.update(
                "component_allocations",
                ContentValues().apply { put("quantity", 0) },
                "component_id = ? AND location_id <> ?",
                arrayOf(componentId, locationId),
            )
            setAllocationQuantity(db, componentId, locationId, draft.quantity)
        } else {
            val existingAtLocation = allocationQuantity(db, componentId, locationId)
            val adjustedAtLocation = Math.addExact(existingAtLocation, draft.quantity - oldTotal)
            check(adjustedAtLocation >= 0) { "The edited total would make the selected location negative." }
            setAllocationQuantity(db, componentId, locationId, adjustedAtLocation)
        }
        if (existingComponent == null && draft.quantity > 0 && draft.purchaseAmount != null) {
            val movementId = "mov-${randomId()}"
            val paid = InventoryCost.text(InventoryCost.decimal(draft.purchaseAmount))
            db.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("id", movementId); put("component_id", componentId); put("movement_type", "inbound")
                put("quantity", draft.quantity); put("reason", "Initial purchase"); put("note", "")
                put("purchase_amount", paid); put("cost_amount", paid); put("happened_at", updatedAt)
                put("updated_at", updatedAt); put("deleted", 0); put("location_id", locationId)
            })
            enqueueEntity(db, "stock_movement", movementId, updatedAt)
        }
        if (valuation != null) {
            val auditId = "mov-${randomId()}"
            db.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("id", auditId); put("component_id", componentId); put("movement_type", "adjustment")
                put("quantity", 0); put("reason", "Current inventory valuation"); put("note", "期初当前库存估值 CNY unit cost: $valuation; previous unit cost: ${existingComponent?.inventoryUnitCost ?: "unknown"}")
                put("cost_amount", InventoryCost.text((valuationTotal ?: java.math.BigDecimal(valuation).multiply(java.math.BigDecimal(draft.quantity))).subtract(java.math.BigDecimal(existingComponent?.inventoryUnitCost ?: "0").multiply(java.math.BigDecimal(draft.quantity))))); put("happened_at", updatedAt)
                put("updated_at", updatedAt); put("deleted", 0); put("location_id", locationId)
            })
            enqueueEntity(db, "stock_movement", auditId, updatedAt)
        }
        verifyAllocationTotal(db, componentId, draft.quantity)
        enqueueEntity(db, "component", componentId, updatedAt)
        return componentId
    }

    private fun validateComponentDraft(draft: ComponentDraft) {
        if (
            draft.sku.isBlank() ||
            draft.name.isBlank() ||
            draft.category.isBlank() ||
            draft.packageName.isBlank() ||
            draft.location.isBlank()
        ) {
            throw IllegalStateException(text(R.string.sync_component_required_fields))
        }
        if (draft.quantity < 0 || draft.minStock < 0) {
            throw IllegalStateException(text(R.string.sync_component_non_negative))
        }
    }

    private fun validateMovementDraft(draft: MovementEntryDraft) {
        if (draft.componentId.isBlank() || draft.reason.isBlank() || draft.movementType.isBlank()) {
            throw IllegalStateException(text(R.string.sync_choose_component_type_reason))
        }
        if (draft.movementType == "transfer") {
            if (draft.quantity <= 0 || draft.locationId.isNullOrBlank() ||
                draft.destinationLocationId.isNullOrBlank() || draft.locationId == draft.destinationLocationId
            ) throw IllegalStateException("Choose two different locations and a positive transfer quantity.")
        } else if (draft.movementType == "adjustment") {
            if (draft.quantity == 0) {
                throw IllegalStateException(text(R.string.sync_adjustment_non_zero))
            }
        } else if (draft.quantity <= 0) {
            throw IllegalStateException(text(R.string.sync_movement_quantity_positive))
        }
    }

    private fun applyMovementDrafts(
        db: SQLiteDatabase,
        drafts: List<MovementEntryDraft>,
    ) {
        drafts.forEach { draft ->
            val component = getComponentById(db, draft.componentId)
                ?: throw IllegalStateException(text(R.string.sync_choose_active_component_first))

            val sourceLocation = draft.locationId?.trim()?.takeIf(String::isNotEmpty) ?: component.location
            val destinationLocation = draft.destinationLocationId?.trim()?.takeIf(String::isNotEmpty)
            val delta = calculateQuantityDelta(draft.movementType, draft.quantity)
            val newQuantity = component.quantity + delta

            val updatedAt = utcNow()
            ensureStorageLocation(db, sourceLocation, updatedAt)
            destinationLocation?.let { ensureStorageLocation(db, it, updatedAt) }
            val sourceQuantity = allocationQuantity(db, draft.componentId, sourceLocation)
            val requestedFromSource = if (draft.movementType == "transfer") draft.quantity.toLong() else -delta.toLong()
            check(sourceQuantity.toLong() >= requestedFromSource) {
                text(R.string.movement_source_stock_insufficient, sourceQuantity, requestedFromSource)
            }
            check(newQuantity >= 0) { text(R.string.sync_negative_stock_error) }
            if (draft.movementType == "transfer") {
                val destinationQuantity = allocationQuantity(db, draft.componentId, requireNotNull(destinationLocation))
                val transferred = StockAllocationMath.transfer(sourceQuantity, destinationQuantity, draft.quantity)
                setAllocationQuantity(db, draft.componentId, sourceLocation, transferred.first)
                setAllocationQuantity(db, draft.componentId, destinationLocation, transferred.second)
            } else {
                val newAllocation = StockAllocationMath.applyDelta(sourceQuantity, delta)
                setAllocationQuantity(db, draft.componentId, sourceLocation, newAllocation)
            }
            val nextCost = when {
                newQuantity == 0 -> null
                draft.movementType == "inbound" -> InventoryCost.inbound(component.quantity, component.inventoryUnitCost, draft.quantity, draft.purchaseAmount)
                else -> component.inventoryUnitCost
            }
            val movementId = "mov-${randomId()}"
            val movementValues = ContentValues().apply {
                put("id", movementId)
                put("component_id", draft.componentId)
                put("movement_type", draft.movementType.lowercase(Locale.US))
                put("purchase_amount", InventoryCost.text(InventoryCost.decimal(draft.purchaseAmount)))
                put("cost_amount", if (draft.movementType == "inbound") InventoryCost.text(InventoryCost.decimal(draft.purchaseAmount)) else if (draft.movementType == "transfer") null else InventoryCost.amount(component.inventoryUnitCost, draft.quantity))
                put("quantity", normalizeMovementQuantity(draft.movementType, draft.quantity))
                put("reason", draft.reason.trim())
                put("note", draft.note.trim())
                put("happened_at", updatedAt)
                put("updated_at", updatedAt)
                put("deleted", 0)
                put("location_id", sourceLocation)
                put("destination_location_id", destinationLocation)
            }
            db.insertWithOnConflict(
                "stock_movements",
                null,
                movementValues,
                SQLiteDatabase.CONFLICT_REPLACE,
            )

            val componentValues = ContentValues().apply {
                put("quantity", newQuantity)
                put("inventory_unit_cost", nextCost)
                put("updated_at", updatedAt)
            }
            db.update(
                "components",
                componentValues,
                "id = ?",
                arrayOf(draft.componentId),
            )
            verifyAllocationTotal(db, draft.componentId, newQuantity)

            enqueueEntity(db, "component", draft.componentId, updatedAt)
            enqueueEntity(db, "stock_movement", movementId, updatedAt)
        }
    }

    private fun ensureUniqueActiveSku(
        db: SQLiteDatabase,
        sku: String,
        componentId: String?,
    ) {
        db.rawQuery(
            """
            SELECT COUNT(*) AS duplicate_count
            FROM components
            WHERE deleted = 0 AND sku = ? AND id != COALESCE(?, '')
            """.trimIndent(),
            arrayOf(sku, componentId ?: ""),
        ).use { cursor ->
            cursor.moveToFirst()
            if (cursor.getInt(cursor.getColumnIndexOrThrow("duplicate_count")) > 0) {
                throw IllegalStateException(text(R.string.sync_duplicate_active_sku))
            }
        }
    }

    private fun getComponentById(
        db: SQLiteDatabase,
        componentId: String,
    ): ComponentRecord? {
        db.rawQuery(
            """
            SELECT
                id,
                sku,
                name,
                category,
                package_name,
                location,
                COALESCE(description, '') AS description,
                inventory_unit_cost,
                quantity,
                min_stock,
                updated_at,
                deleted
            FROM components
            WHERE id = ?
            """.trimIndent(),
            arrayOf(componentId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                ComponentRecord(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    sku = cursor.getString(cursor.getColumnIndexOrThrow("sku")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                    packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                    location = cursor.getString(cursor.getColumnIndexOrThrow("location")),
                    description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                    inventoryUnitCost = cursor.getString(cursor.getColumnIndexOrThrow("inventory_unit_cost")),
                    quantity = cursor.getInt(cursor.getColumnIndexOrThrow("quantity")),
                    minStock = cursor.getInt(cursor.getColumnIndexOrThrow("min_stock")),
                    updatedAt = cursor.getString(cursor.getColumnIndexOrThrow("updated_at")),
                    deleted = cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1,
                )
            } else {
                null
            }
        }
    }

    private fun calculateQuantityDelta(
        movementType: String,
        quantity: Int,
    ): Int = when (movementType.lowercase(Locale.US)) {
        "inbound" -> quantity
        "outbound" -> -quantity
        "adjustment" -> quantity
        "transfer" -> 0
        else -> throw IllegalStateException(text(R.string.sync_unsupported_movement_type))
    }

    private fun normalizeMovementQuantity(
        movementType: String,
        quantity: Int,
    ): Int = when (movementType.lowercase(Locale.US)) {
        "adjustment" -> quantity
        else -> kotlin.math.abs(quantity)
    }

    private fun inboundForComponent(componentId: String, sku: String): BomInboundItem =
        runCatching {
            databaseHelper.readableDatabase.use { db ->
                db.rawQuery("SELECT package_name,description FROM components WHERE id=?", arrayOf(componentId))
                    .use { cursor ->
                        if (!cursor.moveToFirst()) BomInboundItem(sku, null, null)
                        else BomInboundItem(sku, parseImportDescription(cursor.getString(1)).model,
                            cursor.getString(0))
                    }
            }
        }.getOrElse { BomInboundItem(sku, null, null) }

    private fun remindInbound(items: List<BomInboundItem>): String {
        if (items.isEmpty()) return ""
        return runCatching {
            val arrived = BomProcurementStore(File(appContext.filesDir, "bom-procurement.json"))
                .matchInboundAll(items)
            if (arrived.isEmpty()) "" else "\n" + text(R.string.bom_procurement_arrival_notice, arrived.size)
        }.getOrElse { "\n" + text(R.string.bom_procurement_reminder_update_failed) }
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String = appContext.getString(resId, *args)
}
