package com.componentvault.android.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferencePriceNotesTest {
    @Test fun replacementIsIdempotentPreservesNotesAndMissingPricePreservesOldPrice() {
        val price = requireNotNull(ReferencePriceSnapshot.create("1.2500", "CNY", null, "https://www.szlcsc.com/", "2026-11-01T00:00:00Z"))
        val old = listOf("我的备注", "参数：频率：12MHz", "参考单价：9", "参考币种：USD", "参考起订量：5")
        val merged = ReferencePriceNotes.merge(old, price)
        assertEquals(merged, ReferencePriceNotes.merge(merged, price))
        assertEquals(merged, ReferencePriceNotes.merge(merged, null))
        assertTrue(merged.contains("我的备注"))
        assertTrue(merged.contains("参数：频率：12MHz"))
        assertEquals(price, ReferencePriceNotes.parse(merged.joinToString("\n")))
        assertNull(ReferencePriceNotes.parse("参考单价：0\n参考币种：CNY"))
    }

    @Test fun englishDisplayExplainsUnknownQuantityAndFormatsQueryTime() {
        val price = requireNotNull(ReferencePriceSnapshot.create("0.125", "USD", null, "https://www.lcsc.com/", "2026-11-01T00:00:00Z"))
        val notes = ReferencePriceNotes.merge(emptyList(), price).joinToString("\n")
        val display = ReferencePriceNotes.display(notes, AppLanguage.English) { "2026-11-01 08:00" }
        assertEquals("USD 0.125 / piece (reference) · Applicable minimum quantity not provided\nQuery time: 2026-11-01 08:00\nSource: https://www.lcsc.com/", display)
        assertTrue(requireNotNull(ReferencePriceNotes.display(notes, AppLanguage.ZhCn)).contains("适用起订量未提供"))
        assertTrue(notes.contains("价格查询时间：2026-11-01T00:00:00Z"))
    }
}
