package com.componentvault.android.data

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BomReleaseCommitter(private val databaseHelper: InventoryDatabaseHelper) {
    suspend fun commitBomRelease(
        preview: com.componentvault.android.data.bom.BomReleasePreview,
        releaseId: String,
        batchId: String,
    ): com.componentvault.android.data.bom.BomReleaseResult = withContext(Dispatchers.IO) {
        if (releaseId.isBlank() || batchId.isBlank() || !preview.canCommit) {
            return@withContext com.componentvault.android.data.bom.BomReleaseResult(
                com.componentvault.android.data.bom.BomReleaseOutcome.REJECTED,
                "BOM 预览不完整，请先解决未匹配项和缺料问题。",
            )
        }
        databaseHelper.writableDatabase.use { db ->
            db.beginTransaction()
            try {
                val alreadyApplied = db.rawQuery(
                    "SELECT 1 FROM bom_releases WHERE release_id = ?",
                    arrayOf(releaseId),
                ).use { it.moveToFirst() }
                if (alreadyApplied) {
                    return@withContext com.componentvault.android.data.bom.BomReleaseResult(
                        com.componentvault.android.data.bom.BomReleaseOutcome.ALREADY_APPLIED,
                        "本批次已出库，没有重复扣减库存。",
                    )
                }
                check(preview.lines.mapNotNull { it.componentId }.distinct().size == preview.lines.size) {
                    "BOM 预览包含重复元件，请重新生成预览。"
                }

                val currentRows = preview.lines.map { line ->
                    val componentId = requireNotNull(line.componentId)
                    db.rawQuery(
                        "SELECT sku, quantity, updated_at FROM components " +
                            "WHERE id = ? AND deleted = 0",
                        arrayOf(componentId),
                    ).use { cursor ->
                        check(cursor.moveToFirst()) { "选中的元器件已不存在，请刷新预览。" }
                        val currentUpdatedAt = cursor.getString(2)
                        check(
                            line.expectedUpdatedAt != null &&
                                SyncProtocol.timestampsEqual(currentUpdatedAt, line.expectedUpdatedAt),
                        ) {
                            "预览后库存已变化，请重新生成 BOM 预览。"
                        }
                        val available = cursor.getInt(1)
                        check(available >= line.requirement.requiredQuantity) {
                            "${cursor.getString(0)} 库存不足，整批尚未出库。"
                        }
                        Triple(componentId, available, cursor.getString(0))
                    }
                }

                val now = utcNow()
                preview.lines.zip(currentRows).forEach { (line, current) ->
                    val required = line.requirement.requiredQuantity
                    val currentPlan = allocationPlan(db, current.first, required)
                    check(currentPlan == line.allocationPlan.map { it.locationId to it.quantity }) {
                        "预览后库位分配已变化，请重新生成 BOM 预览。"
                    }
                    val consumed = consumeAllocations(db, current.first, required)
                    val average = db.rawQuery("SELECT inventory_unit_cost FROM components WHERE id=?", arrayOf(current.first)).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
                    val values = ContentValues().apply {
                        put("quantity", current.second - required)
                        if (current.second == required) putNull("inventory_unit_cost")
                        put("updated_at", now)
                    }
                    db.update("components", values, "id = ?", arrayOf(current.first))
                    enqueueEntity(db, "component", current.first, now)
                    consumed.forEach { (locationId, consumedQuantity) ->
                        val movementId = "mov-${randomId()}"
                        db.insertOrThrow(
                            "stock_movements", null, ContentValues().apply {
                                put("id", movementId)
                                put("component_id", current.first)
                                put("movement_type", "outbound")
                                put("quantity", consumedQuantity)
                                put("cost_amount", InventoryCost.amount(average, consumedQuantity))
                                put("reason", "BOM: ${preview.parsed.projectName}")
                                put("note", "Batch $batchId; release $releaseId")
                                put("happened_at", now)
                                put("updated_at", now)
                                put("deleted", 0)
                                put("location_id", locationId)
                            },
                        )
                        enqueueEntity(db, "stock_movement", movementId, now)
                    }
                    verifyAllocationTotal(db, current.first, current.second - required)
                }
                db.insertOrThrow(
                    "bom_releases",
                    null,
                    ContentValues().apply {
                        put("release_id", releaseId)
                        put("batch_id", batchId)
                        put("project_name", preview.parsed.projectName)
                        put("production_runs", preview.parsed.productionSets)
                        put("source_fingerprint", preview.parsed.fileSha256)
                        put("source_sheet", preview.parsed.selectedSheet.name)
                        put("created_at", now)
                    },
                )
                db.setTransactionSuccessful()
                com.componentvault.android.data.bom.BomReleaseResult(
                    com.componentvault.android.data.bom.BomReleaseOutcome.APPLIED,
                    "BOM 批量出库已完成。",
                )
            } catch (error: Exception) {
                com.componentvault.android.data.bom.BomReleaseResult(
                    com.componentvault.android.data.bom.BomReleaseOutcome.REJECTED,
                    error.message ?: "BOM 出库失败，整批已回滚。",
                )
            } finally {
                db.endTransaction()
            }
        }
    }

}
