package com.componentvault.android.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BatchPurchaseInputTest {
    @Test fun showingUnitPriceKeepsTheExactOrderTotal() {
        val input = BatchPurchaseInput("10", enteredAsUnitPrice = false)
        assertEquals("0.1", input.display(asUnitPrice = true, quantity = 100))
        assertEquals("3.33333333", input.display(asUnitPrice = true, quantity = 3))
        assertEquals("10", input.display(asUnitPrice = false, quantity = 3))
        assertEquals("10", input.total(quantity = 3))
    }

    @Test fun rejectsInvalidAmountsAndUnfinishedQuantityWithoutCreatingZeroCost() {
        val input = BatchPurchaseInput("2", enteredAsUnitPrice = true)
        assertFailsWith<IllegalArgumentException> { input.total(quantity = null) }
        assertEquals("6", input.total(quantity = 3))
        for (text in listOf("-1", "abc", "0.123456789")) {
            assertFailsWith<IllegalArgumentException> { input.copy(text = text).total(quantity = 3) }
        }
        assertNull(input.copy(text = "").total(quantity = 3))
        assertEquals("0", input.copy(text = "0").total(quantity = 3))
    }
}
