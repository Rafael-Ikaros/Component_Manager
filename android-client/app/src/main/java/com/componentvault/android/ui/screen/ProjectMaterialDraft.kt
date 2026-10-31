package com.componentvault.android.ui.screen

import com.componentvault.android.data.bom.*
import com.componentvault.android.model.ComponentRecord
import java.util.UUID

/** The editor keeps imported source rows intact; manual rows use the same portable preset schema. */
internal fun emptyProjectMaterials(projectName: String = "Project materials", sheetName: String = "Manual selection"): BomPreset {
    val sheet = BomSheet(sheetName, 0)
    return BomPreset(UUID.randomUUID().toString(), projectName,
        BomParseResult(projectName, 1, listOf(sheet), sheet, emptyList(), ""))
}

internal fun BomPreset.addMaterial(component: ComponentRecord): BomPreset {
    require(parsed.requirements.size < LocalImportLimits.MAX_DATA_ROWS) { "用料清单超过 5000 行限制。" }
    val key = "manual:${UUID.randomUUID()}"
    val row = BomRequirement(BomRequirementIdentity(key), component.sku, component.name, null,
        component.packageName, 1, parsed.productionSets, emptyList())
    return copy(parsed = parsed.copy(requirements = parsed.requirements + row), selections = selections + (key to component.id))
}

internal fun BomPreset.changeMaterialQuantity(key: String, quantity: Int): BomPreset {
    require(quantity > 0) { "每套数量必须是正整数。" }
    val required = Math.multiplyExact(quantity, parsed.productionSets)
    return copy(parsed = parsed.copy(requirements = parsed.requirements.map {
        if (it.identity.canonicalKey == key) it.copy(quantityPerSet = quantity, requiredQuantity = required) else it
    }))
}
