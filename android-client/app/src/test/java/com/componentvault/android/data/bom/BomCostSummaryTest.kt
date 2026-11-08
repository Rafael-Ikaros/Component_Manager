package com.componentvault.android.data.bom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BomCostSummaryTest {
    private fun line(cost: String?, matched: Boolean = true) = BomReleaseLine(
        BomRequirement(BomRequirementIdentity("C1"),"C1","Part",null,null,3,6,emptyList()),
        if (matched) "id" else null, if (matched) "C1" else null,1,"time",
        listOf(InventoryMatchCandidate("id","C1",inventoryUnitCost=cost)))
    @Test fun shortagePricesFullRequirementAndDoesNotChangeInput() {
        val source = line("0.25")
        val result = BomCostSummary.calculate(listOf(source),2)
        assertTrue(result.complete)
        assertEquals("1.5",result.knownSubtotal.stripTrailingZeros().toPlainString())
        assertEquals("0.75",result.perSetKnownSubtotal.stripTrailingZeros().toPlainString())
        assertEquals(1,source.availableQuantity)
    }
    @Test fun unknownAndUnmatchedRemainSeparateFromSubtotal() {
        val result=BomCostSummary.calculate(listOf(line("0"),line(null),line("2",false)),2)
        assertFalse(result.complete)
        assertEquals(2,result.missingLines.size)
        assertEquals(0,result.knownSubtotal.signum())
    }
}
