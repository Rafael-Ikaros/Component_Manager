package com.componentvault.android.ui.screen

import androidx.compose.runtime.saveable.Saver
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportFieldOrigin
import com.componentvault.android.model.ComponentImportFieldOrigins
import com.componentvault.android.model.ComponentImportSourceType
import com.componentvault.android.model.MovementBatchQueueItemUiState
import com.componentvault.android.model.MovementBatchSessionUiState
import com.componentvault.android.model.MovementBatchStage
import org.json.JSONArray
import org.json.JSONObject

internal val ComponentDraftSaver = Saver<ComponentDraft?, String>(
    save = { draft -> draft?.let { encodeComponentDraft(it).toString() } ?: "" },
    restore = { text -> text.takeIf(String::isNotEmpty)?.let { decodeComponentDraft(JSONObject(it)) } },
)

private fun encodeComponentDraft(draft: ComponentDraft) = JSONObject().apply {
    put("valuationUnitCost", draft.valuationUnitCost ?: JSONObject.NULL)
    put("valuationAmount", draft.valuationAmount ?: JSONObject.NULL)
    put("purchaseAmount", draft.purchaseAmount ?: JSONObject.NULL)
    put("id", draft.id)
    put("expectedUpdatedAt", draft.expectedUpdatedAt)
    put("sku", draft.sku)
    put("name", draft.name)
    put("category", draft.category)
    put("packageName", draft.packageName)
    put("location", draft.location)
    put("description", draft.description)
    put("quantity", draft.quantity)
    put("minStock", draft.minStock)
}

private fun decodeComponentDraft(json: JSONObject) = ComponentDraft(
    purchaseAmount = if (json.isNull("purchaseAmount")) null else json.getString("purchaseAmount"),
    valuationAmount = if (json.isNull("valuationAmount")) null else json.getString("valuationAmount"),
    valuationUnitCost = if (json.isNull("valuationUnitCost")) null else json.getString("valuationUnitCost"),
    id = json.optString("id").takeIf(String::isNotEmpty),
    expectedUpdatedAt = if (json.isNull("expectedUpdatedAt")) null else json.getString("expectedUpdatedAt"),
    sku = json.getString("sku"),
    name = json.getString("name"),
    category = json.getString("category"),
    packageName = json.getString("packageName"),
    location = json.getString("location"),
    description = json.getString("description"),
    quantity = json.getInt("quantity"),
    minStock = json.getInt("minStock"),
)

internal val ComponentImportCandidateSaver = Saver<ComponentImportCandidate?, String>(
    save = { candidate -> candidate?.let { encodeImportCandidate(it).toString() } ?: "" },
    restore = { text -> text.takeIf(String::isNotEmpty)?.let { decodeImportCandidate(JSONObject(it)) } },
)

private fun encodeImportCandidate(candidate: ComponentImportCandidate) = JSONObject().apply {
    put("sourceType", candidate.sourceType.name)
    put("rawPayload", candidate.rawPayload)
    put("sourceLabel", candidate.sourceLabel)
    put("sku", candidate.sku)
    put("name", candidate.name)
    put("packageName", candidate.packageName)
    put("category", candidate.category)
    put("model", candidate.model)
    put("brand", candidate.brand)
    put("vendor", candidate.vendor)
    put("modelFamily", candidate.modelFamily)
    put("recognitionConfidence", candidate.recognitionConfidence)
    put("matchedBy", candidate.matchedBy)
    put("normalizedPackageKey", candidate.normalizedPackageKey)
    put("suggestedQuantity", candidate.suggestedQuantity)
    put("notes", JSONArray(candidate.notes))
    put("fieldOrigins", JSONObject().apply {
        put("sku", candidate.fieldOrigins.sku.name)
        put("name", candidate.fieldOrigins.name.name)
        put("category", candidate.fieldOrigins.category.name)
        put("packageName", candidate.fieldOrigins.packageName.name)
        put("model", candidate.fieldOrigins.model.name)
        put("brand", candidate.fieldOrigins.brand.name)
    })
}

private fun decodeImportCandidate(json: JSONObject): ComponentImportCandidate {
    val origins = json.getJSONObject("fieldOrigins")
    return ComponentImportCandidate(
        sourceType = ComponentImportSourceType.valueOf(json.getString("sourceType")),
        rawPayload = json.getString("rawPayload"),
        sourceLabel = json.getString("sourceLabel"),
        sku = json.getString("sku"),
        name = json.getString("name"),
        packageName = json.getString("packageName"),
        category = json.getString("category"),
        model = json.optString("model").takeIf(String::isNotEmpty),
        brand = json.optString("brand").takeIf(String::isNotEmpty),
        vendor = json.optString("vendor").takeIf(String::isNotEmpty),
        modelFamily = json.optString("modelFamily").takeIf(String::isNotEmpty),
        recognitionConfidence = json.optString("recognitionConfidence").takeIf(String::isNotEmpty),
        matchedBy = json.optString("matchedBy").takeIf(String::isNotEmpty),
        normalizedPackageKey = json.optString("normalizedPackageKey").takeIf(String::isNotEmpty),
        suggestedQuantity = json.optInt("suggestedQuantity").takeIf { json.has("suggestedQuantity") && !json.isNull("suggestedQuantity") },
        notes = json.getJSONArray("notes").let { array -> List(array.length()) { array.getString(it) } },
        fieldOrigins = ComponentImportFieldOrigins(
            sku = ComponentImportFieldOrigin.valueOf(origins.getString("sku")),
            name = ComponentImportFieldOrigin.valueOf(origins.getString("name")),
            category = ComponentImportFieldOrigin.valueOf(origins.getString("category")),
            packageName = ComponentImportFieldOrigin.valueOf(origins.getString("packageName")),
            model = ComponentImportFieldOrigin.valueOf(origins.getString("model")),
            brand = ComponentImportFieldOrigin.valueOf(origins.getString("brand")),
        ),
    )
}

/** Saves review inputs only. Stock and names are rebuilt from the current database on restore. */
internal object MovementBatchDraftCodec {
    fun encode(session: MovementBatchSessionUiState): String = JSONObject().apply {
        put("defaultMovementType", session.defaultMovementType)
        put("totalScans", session.totalScans)
        put("lastQueuedComponentName", session.lastQueuedComponentName)
        put("lastQueuedComponentSku", session.lastQueuedComponentSku)
        put("items", JSONArray().apply {
            session.queuedItems.forEach { item -> put(JSONObject().apply {
                put("componentId", item.componentId)
                put("location", item.location)
                put("scanCount", item.scanCount)
                put("movementType", item.movementType)
                put("quantityText", item.quantityText)
                put("purchaseAmount", item.purchaseAmount)
                put("reason", item.reason)
                put("note", item.note)
                put("isDirty", item.isDirty)
            }) }
        })
    }.toString()

    fun decode(text: String): MovementBatchSessionUiState {
        val json = JSONObject(text)
        val items = json.getJSONArray("items")
        return MovementBatchSessionUiState(
            defaultMovementType = json.getString("defaultMovementType"),
            stage = MovementBatchStage.Review,
            queuedItems = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                MovementBatchQueueItemUiState(
                    componentId = item.getString("componentId"),
                    componentName = "", componentSku = "", category = "", packageName = "",
                    location = item.getString("location"), currentStock = 0, minStock = 0,
                    scanCount = item.getInt("scanCount"),
                    movementType = item.getString("movementType"),
                    quantityText = item.getString("quantityText"),
                    purchaseAmount = item.optString("purchaseAmount"),
                    reason = item.getString("reason"), note = item.getString("note"),
                    isDirty = item.getBoolean("isDirty"),
                )
            },
            totalScans = json.getInt("totalScans"),
            lastQueuedComponentName = json.getString("lastQueuedComponentName"),
            lastQueuedComponentSku = json.getString("lastQueuedComponentSku"),
        )
    }
}
