package com.componentvault.android.data.bom

/** Limits shared by the local BOM and Component Hub importers. */
object LocalImportLimits {
    const val MAX_FILE_BYTES: Int = 10 * 1024 * 1024
    const val MAX_DATA_ROWS: Int = 5_000
    const val MAX_ZIP_ENTRIES: Int = 256
    const val MAX_ZIP_ENTRY_BYTES: Int = 12 * 1024 * 1024
    const val MAX_ZIP_TOTAL_BYTES: Int = 32 * 1024 * 1024
}

enum class LocalImportErrorCode {
    FILE_TOO_LARGE,
    INVALID_TEXT_ENCODING,
    INVALID_CSV,
    INVALID_XLSX,
    ZIP_LIMIT_EXCEEDED,
    SHEET_NOT_FOUND,
    MISSING_REQUIRED_COLUMN,
    TOO_MANY_ROWS,
    INVALID_PROJECT_NAME,
    INVALID_POSITIVE_INTEGER,
    QUANTITY_OVERFLOW,
    INVALID_COMPONENT_HUB_JSON,
}

class LocalImportException(
    val code: LocalImportErrorCode,
    message: String,
    val sheetName: String? = null,
    val rowNumber: Int? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

data class BomSheet(
    val name: String,
    val index: Int,
    val hidden: Boolean = false,
)

data class BomColumnMapping(
    val sku: Int? = null,
    val model: Int? = null,
    val packageName: Int? = null,
    val quantity: Int? = null,
    val name: Int? = null,
    val reference: Int? = null,
) {
    fun validate(columnCount: Int) {
        require(quantity != null) { "请选择需求数量列。" }
        require(sku != null || model != null || name != null) { "请至少选择 SKU、型号或名称列，以便保留待人工匹配的行。" }
        val selected = listOfNotNull(sku, model, packageName, quantity, name, reference)
        selected.forEach {
            require(it in 0 until columnCount) { "列映射超出表头范围。" }
        }
        require(selected.size == selected.toSet().size) { "同一列不能映射到多个字段；请重新选择列映射。" }
    }
}

data class BomTableInspection(
    val sheet: BomSheet,
    val headers: List<String>,
    val headerRowNumber: Int,
    val automaticMapping: BomColumnMapping,
    val sampleRows: List<List<String>> = emptyList(),
    val dataRowCount: Int = 0,
)

data class BomSourceRow(
    val sheetName: String,
    val rowNumber: Int,
    val quantityPerSet: Int,
    val fields: Map<String, String>,
)

data class BomRequirementIdentity(
    val canonicalKey: String,
)

data class BomRequirement(
    val identity: BomRequirementIdentity,
    val sku: String?,
    val name: String?,
    val model: String?,
    val packageName: String?,
    val quantityPerSet: Int,
    val requiredQuantity: Int,
    val sourceRows: List<BomSourceRow>,
)

data class BomParseResult(
    val projectName: String,
    val productionSets: Int,
    val availableSheets: List<BomSheet>,
    val selectedSheet: BomSheet,
    val requirements: List<BomRequirement>,
    val fileSha256: String,
    val sourceName: String = "",
    val warnings: List<String> = emptyList(),
)

data class InventoryMatchCandidate(
    val inventoryId: String,
    val sku: String,
    val model: String? = null,
    val packageName: String? = null,
    val displayName: String? = null,
    val active: Boolean = true,
    val category: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val inventoryUnitCost: String? = null,
)

enum class BomMatchKind {
    EXACT_SKU,
    EXACT_MODEL,
    EXACT_MODEL_AND_PACKAGE,
    NONE,
}

/**
 * Pure preview state. A null [selectedInventoryId] with multiple [candidates]
 * means the UI must ask the user to choose one.
 */
data class BomMatchPreview(
    val requirement: BomRequirement,
    val kind: BomMatchKind,
    val candidates: List<InventoryMatchCandidate>,
    val selectedInventoryId: String? = candidates.singleOrNull()?.inventoryId,
) {
    val isAmbiguous: Boolean
        get() = candidates.size > 1 && selectedInventoryId == null

    fun select(inventoryId: String?): BomMatchPreview {
        require(inventoryId == null || candidates.any { it.inventoryId == inventoryId }) {
            "Selected inventory component is not one of the match candidates."
        }
        return copy(selectedInventoryId = inventoryId)
    }
}

object BomInventoryMatcher {
    fun match(
        requirements: List<BomRequirement>,
        inventory: List<InventoryMatchCandidate>,
    ): List<BomMatchPreview> = requirements.map { requirement ->
        val activeInventory = inventory.filter(InventoryMatchCandidate::active)
        val skuMatches = requirement.sku
            ?.takeIf(String::isNotBlank)
            ?.let { sku -> activeInventory.filter { it.sku.normalizedIdentityPart() == sku.normalizedIdentityPart() } }
            .orEmpty()
        if (!requirement.sku.isNullOrBlank()) {
            if (skuMatches.isEmpty()) {
                BomMatchPreview(requirement, BomMatchKind.NONE, emptyList())
            } else {
                BomMatchPreview(requirement, BomMatchKind.EXACT_SKU, skuMatches)
            }
        } else {
            val model = requirement.model?.normalizedIdentityPart().orEmpty()
            val packageName = requirement.packageName?.normalizedIdentityPart().orEmpty()
            val modelMatches = if (model.isBlank()) {
                emptyList()
            } else {
                activeInventory.filter {
                    it.model?.normalizedIdentityPart() == model
                }
            }
            val modelPackageMatches = if (packageName.isBlank()) modelMatches else {
                modelMatches.filter { it.packageName?.normalizedIdentityPart() == packageName }
            }
            if (modelPackageMatches.isEmpty()) {
                BomMatchPreview(requirement, BomMatchKind.NONE, emptyList())
            } else {
                BomMatchPreview(
                    requirement,
                    if (packageName.isBlank()) BomMatchKind.EXACT_MODEL
                    else BomMatchKind.EXACT_MODEL_AND_PACKAGE,
                    modelPackageMatches,
                )
            }
        }
    }

    fun search(
        inventory: List<InventoryMatchCandidate>,
        query: String,
        limit: Int = 50,
    ): List<InventoryMatchCandidate> {
        val normalizedQuery = query.normalizedIdentityPart()
        if (normalizedQuery.isBlank() || limit <= 0) return emptyList()
        return inventory.asSequence()
            .filter(InventoryMatchCandidate::active)
            .mapNotNull { candidate ->
                val fields = listOfNotNull(
                    candidate.sku,
                    candidate.model,
                    candidate.packageName,
                    candidate.displayName,
                ).map(String::normalizedIdentityPart)
                val rank = when {
                    fields.any { it == normalizedQuery } -> 0
                    fields.any { it.startsWith(normalizedQuery) } -> 1
                    fields.any { normalizedQuery in it } -> 2
                    else -> return@mapNotNull null
                }
                rank to candidate
            }
            .sortedWith(compareBy<Pair<Int, InventoryMatchCandidate>> { it.first }
                .thenBy { it.second.sku.normalizedIdentityPart() }
                .thenBy { it.second.inventoryId })
            .map { it.second }
            .take(limit)
            .toList()
    }
}

internal fun String.normalizedIdentityPart(): String =
    trim().replace(Regex("\\s+"), " ").uppercase()
