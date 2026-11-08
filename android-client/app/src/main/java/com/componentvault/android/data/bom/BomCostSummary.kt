package com.componentvault.android.data.bom

import com.componentvault.android.data.InventoryCost
import java.math.BigDecimal
import java.math.RoundingMode

/** Read-only material estimate; shortage never truncates required quantity. */
data class BomCostSummary(val knownSubtotal: BigDecimal, val perSetKnownSubtotal: BigDecimal, val missingLines: List<BomReleaseLine>) {
    val complete: Boolean get() = missingLines.isEmpty()
    companion object {
        fun calculate(lines: List<BomReleaseLine>, productionSets: Int): BomCostSummary {
            require(productionSets > 0)
            var subtotal = BigDecimal.ZERO
            val missing = mutableListOf<BomReleaseLine>()
            lines.forEach { line ->
                val average = line.candidates.firstOrNull { it.inventoryId == line.componentId }?.inventoryUnitCost
                val amount = InventoryCost.amount(average, line.requirement.requiredQuantity)
                if (amount == null) missing.add(line) else subtotal = subtotal.add(BigDecimal(amount))
            }
            return BomCostSummary(subtotal, subtotal.divide(BigDecimal(productionSets),16,RoundingMode.HALF_EVEN),missing)
        }
    }
}
