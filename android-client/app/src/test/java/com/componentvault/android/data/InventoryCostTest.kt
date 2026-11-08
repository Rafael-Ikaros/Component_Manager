package com.componentvault.android.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class InventoryCostTest {
    @Test fun weightedAverageUsesActualAmounts() {
        assertEquals("1.5", InventoryCost.inbound(10, "1", 10, "20"))
        assertEquals("0.3333333333333333", InventoryCost.inbound(0, null, 3, "1"))
        assertEquals("0.5", InventoryCost.inbound(10, "1", 10, "0"))
    }
    @Test fun unknownIsNotFree() {
        assertNull(InventoryCost.inbound(10, "1", 10, null))
        assertNull(InventoryCost.inbound(10, null, 10, "20"))
        assertEquals("2", InventoryCost.inbound(0, null, 10, "20"))
        assertEquals("0", InventoryCost.inbound(0, null, 10, "0"))
    }
    @Test fun precisionAndAmounts() {
        assertEquals("0.00000003", InventoryCost.amount("0.00000001", 3))
        assertFailsWith<IllegalArgumentException> { InventoryCost.decimal("-1") }
        assertFailsWith<IllegalArgumentException> { InventoryCost.decimal("0.123456789") }
    }
}
