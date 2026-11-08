package com.componentvault.android.data

import jxl.NumberCell
import jxl.Sheet
import jxl.Workbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

internal data class LcscOrderImport(
    val rows: List<BatchJlcRow>,
    val mergedLines: Int,
    val skippedFees: Int,
)

/** Reads the LCSC mall's legacy XLS order statement into the batch inbound review draft. */
internal object LcscOrderWorkbookParser {
    private val requiredHeaders = setOf(
        "订单编号", "商品编号", "商品名称", "商品型号", "封装格式", "订购数量", "品牌", "商品类型",
    )
    private val skuPattern = Regex("C[0-9]{1,10}", RegexOption.IGNORE_CASE)
    private val summaryLabels = setOf("商品合计金额", "订单合计金额", "退款合计金额", "合计", "总计")

    fun parse(input: InputStream): LcscOrderImport {
        val bytes = ByteArrayOutputStream().also { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 10 * 1024 * 1024) { "对账单超过 10 MiB。" }
                output.write(buffer, 0, count)
            }
        }.toByteArray()
        require(bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()),
        )) { "请选择立创商城旧版 XLS 对账单。" }
        val workbook = Workbook.getWorkbook(ByteArrayInputStream(bytes))
        try {
            for (sheet in workbook.sheets) {
                val header = findHeader(sheet) ?: continue
                val result = readSheet(sheet, header)
                if (result.rows.isNotEmpty()) return result
            }
            error("对账单中没有可入库的商品明细。")
        } finally {
            workbook.close()
        }
    }

    private fun findHeader(sheet: Sheet): Pair<Int, Map<String, Int>>? {
        for (row in 0 until minOf(sheet.rows, 12)) {
            val columns = (0 until sheet.columns).associateBy { sheet.getCell(it, row).contents.trim() }
            if (requiredHeaders.all { it in columns }) return row to columns
        }
        return null
    }

    private fun readSheet(sheet: Sheet, header: Pair<Int, Map<String, Int>>): LcscOrderImport {
        val (headerRow, columns) = header
        val grouped = linkedMapOf<String, OrderLine>()
        var mergedLines = 0
        var skippedFees = 0
        for (row in headerRow + 1 until sheet.rows) {
            fun value(name: String) = sheet.getCell(columns.getValue(name), row).contents.trim()
            val sku = value("商品编号").uppercase(Locale.ROOT)
            val order = value("订单编号")
            val productName = value("商品名称")
            // The statement ends with a merged note in the order-number column.
            // Only skip a note without product data; malformed product rows must still fail.
            if ((order.startsWith("说明：") || order.startsWith("说明:")) &&
                requiredHeaders.filter { it != "订单编号" }.all { value(it).isBlank() }
            ) continue
            if (sku.isBlank() && (order.isBlank() || order in summaryLabels)) {
                if (productName.contains("配送费")) skippedFees++
                continue
            }
            require(order.isNotBlank() && skuPattern.matches(sku)) {
                "第 ${row + 1} 行缺少订单号或有效的 C 料号，请核对对账单。"
            }
            val statusColumn = columns["订单状态"]
            val status = statusColumn?.let { sheet.getCell(it, row).contents.trim() }.orEmpty()
            require(!status.contains("取消") && !status.contains("退款")) {
                "第 ${row + 1} 行是取消或退款订单，需要先核对后再导入。"
            }
            val quantity = quantity(sheet, columns.getValue("订购数量"), row)
            fun money(header: String) = columns[header]?.let { column -> val cell = sheet.getCell(column, row); if (cell is NumberCell) java.math.BigDecimal.valueOf(cell.value).stripTrailingZeros().toPlainString() else cell.contents.trim().takeIf(String::isNotBlank) }?.let { InventoryCost.decimal(it) }
            val amount = money("小计金额（人民币含税）") ?: money("单价（人民币含税）")?.multiply(java.math.BigDecimal(quantity))
            val model = value("商品型号")
            val brand = value("品牌")
            val category = value("商品类型")
            val packageName = value("封装格式")
            val key = stableId(order, sku)
            val prior = grouped[key]
            if (prior == null) {
                grouped[key] = OrderLine(
                    sku = sku, productName = productName, category = category, packageName = packageName,
                    model = model, brand = brand, quantity = quantity, amount = InventoryCost.text(amount),
                )
            } else {
                require(
                    prior.sku == sku && prior.productName == productName && prior.category == category &&
                        prior.packageName == packageName && prior.model == model && prior.brand == brand,
                ) { "第 ${row + 1} 行与同订单同料号的其他明细不一致，请先核对。" }
                grouped[key] = prior.copy(quantity = Math.addExact(prior.quantity, quantity), amount = if (prior.amount == null || amount == null) null else InventoryCost.text(java.math.BigDecimal(prior.amount).add(amount)))
                mergedLines++
            }
            require(grouped.size <= BatchJlcDraftCodec.MaxRows) { "对账单超过 500 个不同订单商品，请分批导入。" }
        }
        return LcscOrderImport(
            grouped.map { (id, line) ->
                BatchJlcRow(
                    id = id, raw = "lcsc-order:$id", status = BatchJlcStatus.Ready,
                    sku = line.sku, name = displayName(line), category = line.category,
                    packageName = line.packageName, quantityText = line.quantity.toString(),
                    purchaseAmount = line.amount,
                    description = buildList {
                        line.model.takeIf(String::isNotBlank)?.let { add("型号：$it") }
                        line.brand.takeIf(String::isNotBlank)?.let { add("品牌：$it") }
                        line.productName.takeIf(String::isNotBlank)?.let { add("商品名称：$it") }
                    }.joinToString("\n"),
                )
            },
            mergedLines = mergedLines,
            skippedFees = skippedFees,
        )
    }

    private fun quantity(sheet: Sheet, column: Int, row: Int): Int {
        val cell = sheet.getCell(column, row)
        val number = if (cell is NumberCell) cell.value else cell.contents.trim().replace(",", "").toDoubleOrNull()
        require(number != null && number.isFinite() && number > 0 && number <= Int.MAX_VALUE && number % 1.0 == 0.0) {
            "第 ${row + 1} 行的订购数量不是正整数。"
        }
        return number.toInt()
    }

    private fun stableId(order: String, sku: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$order\u001f$sku".toByteArray(Charsets.UTF_8))
        return "order-" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun displayName(line: OrderLine): String = when {
        line.model.isBlank() -> line.productName
        line.brand.isBlank() || line.model.contains(line.brand, ignoreCase = true) -> line.model
        else -> "${line.brand} ${line.model}"
    }

    private data class OrderLine(
        val sku: String,
        val productName: String,
        val category: String,
        val packageName: String,
        val model: String,
        val brand: String,
        val quantity: Int,
        val amount: String? = null,
    )
}
