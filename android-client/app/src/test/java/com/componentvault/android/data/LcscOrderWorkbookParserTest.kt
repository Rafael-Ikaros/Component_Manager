package com.componentvault.android.data

import jxl.Workbook
import jxl.write.Label
import jxl.write.Number as XlsNumber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeNotNull

class LcscOrderWorkbookParserTest {
    @Test
    fun parses_local_statement_when_supplied() {
        val path = System.getenv("LCSC_ORDER_SAMPLE")
        assumeNotNull(path)
        val parsed = File(requireNotNull(path)).inputStream().use(LcscOrderWorkbookParser::parse)
        assertEquals(191, parsed.rows.size)
        assertEquals(2, parsed.skippedFees)
        assertEquals(182, parsed.rows.map { it.sku }.distinct().size)
        assertTrue(parsed.rows.all { it.quantityText.toInt() > 0 })
        assertTrue(parsed.rows.all { it.purchaseAmount != null })
    }

    @Test
    fun imports_ordered_quantity_and_merges_same_order_sku() {
        val parsed = LcscOrderWorkbookParser.parse(ByteArrayInputStream(statement()))

        assertEquals(1, parsed.rows.size)
        assertEquals(1, parsed.mergedLines)
        assertEquals(1, parsed.skippedFees)
        val row = parsed.rows.single()
        assertEquals("C12345", row.sku)
        assertEquals("8", row.quantityText)
        assertEquals("Maker M-01", row.name)
        assertEquals("SMD", row.packageName)
        assertEquals("Resistor", row.category)
        assertTrue(row.description.contains("型号：M-01"))
        assertTrue(row.description.contains("品牌：Maker"))
        assertTrue(row.description.contains("商品名称：Example"))
        assertTrue(row.id.startsWith("order-"))
        assertEquals(row.id, LcscOrderWorkbookParser.parse(ByteArrayInputStream(statement(paddingRows = 2))).rows.single().id)
    }

    @Test fun usesActualSubtotalBeforeUnitAndAggregatesAmounts() {
        val row = LcscOrderWorkbookParser.parse(ByteArrayInputStream(statement(priced=true))).rows.single()
        assertEquals("0.925", row.purchaseAmount)
    }

    @Test
    fun rejects_product_row_without_sku() {
        assertFailsWith<IllegalArgumentException> {
            LcscOrderWorkbookParser.parse(ByteArrayInputStream(statement(invalidProduct = true)))
        }
    }

    @Test
    fun skips_merged_statement_footer_at_row_199() {
        val parsed = LcscOrderWorkbookParser.parse(ByteArrayInputStream(statement(footer = true)))
        assertEquals(1, parsed.rows.size)
        assertEquals("8", parsed.rows.single().quantityText)
        assertEquals(1, parsed.skippedFees)
    }

    @Test
    fun note_prefix_does_not_hide_product_data_with_missing_sku() {
        assertFailsWith<IllegalArgumentException> {
            LcscOrderWorkbookParser.parse(ByteArrayInputStream(statement(footer = true, invalidProduct = true)))
        }
    }

    private fun statement(paddingRows: Int = 0, invalidProduct: Boolean = false, footer: Boolean = false, priced: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        val book = Workbook.createWorkbook(output)
        val sheet = book.createSheet("Order details", 0)
        val headers = listOf("订单编号", "商品编号", "商品名称", "商品型号", "封装格式", "订购数量", "品牌", "商品类型") + if (priced) listOf("单价（人民币含税）", "小计金额（人民币含税）") else emptyList()
        headers.forEachIndexed { column, value -> sheet.addCell(Label(column, paddingRows, value)) }
        fun item(row: Int, quantity: Double) {
            listOf("SO-1", "C12345", "Example", "M-01", "SMD", "", "Maker", "Resistor")
                .forEachIndexed { column, value -> if (value.isNotEmpty()) sheet.addCell(Label(column, row + paddingRows, value)) }
            sheet.addCell(XlsNumber(5, row + paddingRows, quantity))
            if (priced) { sheet.addCell(XlsNumber(8,row + paddingRows,0.125)); if(row==1) sheet.addCell(XlsNumber(9,row + paddingRows,0.3)) }
        }
        item(1, 3.0)
        sheet.addCell(Label(2, 2 + paddingRows, "价外费用（配送费）"))
        item(3, 5.0)
        sheet.addCell(Label(0, 4 + paddingRows, "商品合计金额"))
        if (footer) {
            sheet.mergeCells(0, 198, 1, 198)
            sheet.addCell(Label(0, 198, "说明：\n1、未付款取消的订单不会导出"))
            if (invalidProduct) sheet.addCell(Label(2, 198, "Missing SKU"))
        } else if (invalidProduct) {
            sheet.addCell(Label(0, 5 + paddingRows, "SO-2"))
            sheet.addCell(Label(2, 5 + paddingRows, "Missing SKU"))
        }
        book.write()
        book.close()
        return output.toByteArray()
    }
}
