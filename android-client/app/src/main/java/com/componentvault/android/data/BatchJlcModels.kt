package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.trustedProductImageUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class BatchJlcStatus { Captured, Ready, Pending, Committed }

internal data class BatchJlcRow(
    val id: String = UUID.randomUUID().toString(),
    val raw: String,
    val status: BatchJlcStatus = BatchJlcStatus.Captured,
    val sku: String = "",
    val name: String = "",
    val category: String = "",
    val packageName: String = "",
    val quantityText: String = "",
    val location: String = "",
    val error: String = "",
    val selected: Boolean = true,
    val description: String = "",
    val purchaseAmount: String? = null,
)

internal data class BatchJlcDraft(
    val sessionId: String = UUID.randomUUID().toString(),
    val rows: List<BatchJlcRow> = emptyList(),
)

/** Builds sync-safe notes exclusively from public catalog metadata. */
internal fun ComponentOfficialMetadata.toBatchDescription(): String = buildList {
    model?.trim()?.takeIf(String::isNotBlank)?.let { add("型号：$it") }
    (brand ?: vendor)?.trim()?.takeIf(String::isNotBlank)?.let { add("品牌：$it") }
    source?.trim()?.takeIf(String::isNotBlank)?.let { add("识别来源：$it") }
    if (source in setOf("lceda_public_library", "easyeda_public_library")) {
        add("资料来源：嘉立创EDA/EasyEDA官方库")
        add("元件库链接：https://lceda.cn/ · https://easyeda.com")
    }
    vendor?.trim()?.takeIf(String::isNotBlank)?.let { add("识别厂商：$it") }
    modelFamily?.trim()?.takeIf(String::isNotBlank)?.let { add("识别型号族：$it") }
    matchedBy?.trim()?.takeIf(String::isNotBlank)?.let { add("官方查询：匹配方式 ${it.uppercase()}") }
    categoryPath?.trim()?.takeIf(String::isNotBlank)?.let { add("官方分类路径：$it") }
    officialUrl?.trim()?.takeIf(String::isNotBlank)?.let { add("官方链接：$it") }
    description?.trim()?.takeIf(String::isNotBlank)?.let { add("官方描述：$it") }
    trustedProductImageUrl(imageUrl)?.let { add("商品图片：$it") }
    datasheetUrl?.trim()?.takeIf(String::isNotBlank)?.let { add("数据手册：$it") }
    parameters.forEach { (key, value) ->
        val safeKey = key.trim()
        val safeValue = value.trim()
        if (safeKey.isNotBlank() && safeValue.isNotBlank()) add("参数：$safeKey：$safeValue")
    }
    ruleVersion?.trim()?.takeIf(String::isNotBlank)?.let { add("识别规则版本：$it") }
    addAll(com.componentvault.android.model.ReferencePriceNotes.merge(emptyList(), referencePrice))
}.distinct().joinToString("\n")

internal object BatchJlcDraftCodec {
    const val MaxRows = 500
    const val MaxRawChars = 16 * 1024

    fun add(draft: BatchJlcDraft, values: List<String>): Pair<BatchJlcDraft, Int> {
        var duplicates = 0
        val known = draft.rows.map { it.raw.trim() }.toMutableSet()
        val added = mutableListOf<BatchJlcRow>()
        values.forEach { value ->
            val raw = value.trim()
            require(raw.toByteArray(Charsets.UTF_8).size <= MaxRawChars) { "二维码内容超过 16 KiB。" }
            if (raw.isBlank() || !known.add(raw)) {
                duplicates++
            } else {
                require(draft.rows.size + added.size < MaxRows) { "批量草稿最多 500 条。" }
                added += BatchJlcRow(raw = raw)
            }
        }
        return draft.copy(rows = draft.rows + added) to duplicates
    }

    fun encode(draft: BatchJlcDraft): String = JSONObject()
        .put("session_id", draft.sessionId)
        .put("rows", JSONArray().apply {
            draft.rows.forEach { row ->
                put(JSONObject()
                    .put("id", row.id)
                    .put("raw", row.raw)
                    .put("status", row.status.name)
                    .put("sku", row.sku)
                    .put("name", row.name)
                    .put("category", row.category)
                    .put("package_name", row.packageName)
                    .put("description", row.description)
                    .put("quantity", row.quantityText)
                    .put("purchase_amount", row.purchaseAmount ?: JSONObject.NULL)
                    .put("location", row.location)
                    .put("error", row.error)
                    .put("selected", row.selected))
            }
        }).toString()

    fun decode(text: String): BatchJlcDraft {
        val root = JSONObject(text)
        val encodedRows = root.getJSONArray("rows")
        require(encodedRows.length() <= MaxRows)
        val rows = (0 until encodedRows.length()).map { index ->
            val row = encodedRows.getJSONObject(index)
            val raw = row.getString("raw")
            require(raw.toByteArray(Charsets.UTF_8).size <= MaxRawChars)
            BatchJlcRow(
                id = row.getString("id"), raw = raw,
                status = BatchJlcStatus.valueOf(row.getString("status")),
                sku = row.optString("sku"), name = row.optString("name"),
                category = row.optString("category"), packageName = row.optString("package_name"),
                purchaseAmount = if (row.isNull("purchase_amount")) null else row.getString("purchase_amount"),
                description = row.optString("description"), quantityText = row.optString("quantity"),
                location = row.optString("location"), error = row.optString("error"),
                selected = row.optBoolean("selected", true),
            )
        }
        return BatchJlcDraft(root.getString("session_id"), rows)
    }
}

internal data class BatchJlcPlannedRow(
    val row: BatchJlcRow, val sku: String, val quantity: Int, val location: String,
)

internal data class ExistingImportTarget(
    val componentId: String,
    val sku: String,
    val quantity: Int,
    val updatedAt: String,
    val location: String,
)

internal object BatchJlcCommitPlanner {
    fun plan(rows: List<BatchJlcRow>, receipts: Set<String>): List<BatchJlcPlannedRow> {
        val planned = rows.filter {
            it.selected && it.status == BatchJlcStatus.Ready && it.id !in receipts
        }.map { row ->
            val quantity = row.quantityText.trim()
            require(quantity.matches(Regex("[1-9][0-9]*")))
            BatchJlcPlannedRow(
                row = row,
                sku = requireNotNull(LcscPublicCatalog.normalizeSku(row.sku)),
                quantity = quantity.toIntOrNull() ?: error("数量溢出"),
                location = row.location.trim().also { require(it.isNotBlank()) },
            )
        }
        planned.groupBy { it.sku to it.location }.forEach { (_, items) ->
            items.fold(0) { sum, item -> Math.addExact(sum, item.quantity) }
        }
        return planned
    }
}
