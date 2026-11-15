package com.componentvault.android.data

import jxl.NumberCell
import jxl.Sheet
import jxl.Workbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

internal data class LcscOrderImport(
    val rows: List<BatchJlcRow>,
    val mergedLines: Int,
    val skippedFees: Int,
)

/** Reads LCSC mall statements and order detail XLS exports into the batch inbound draft. */
internal object LcscOrderWorkbookParser {
    private val statementHeaders = setOf(
        "订单编号", "商品编号", "商品名称", "商品型号", "封装格式", "订购数量", "品牌", "商品类型",
    )
    private val detailHeaders = setOf("商品编号", "商品名称", "厂家型号", "品牌", "订购数量(修改后)")
    private val orderLabel = Regex("(?:订单编号|订单号)\\s*[:：]\\s*(.*)")
    private val preambleOrderPattern = Regex("SO[0-9]+")
    private val skuPattern = Regex("C[0-9]{1,10}", RegexOption.IGNORE_CASE)
    private val summaryLabels = setOf("商品合计金额", "订单合计金额", "退款合计金额", "合计", "总计")
    private val quantityPattern = Regex("""(\d{1,3}(?:,\d{3})+(?:\.0+)?|\d+(?:\.0+)?)\s*(个|只|片|颗|件|套|支|卷|包|pcs|pc|pieces)?""", RegexOption.IGNORE_CASE)

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
        )) { "请选择立创商城导出的订单详情或物料明细对账单（.xls）。" }
        val workbook = Workbook.getWorkbook(ByteArrayInputStream(bytes))
        try {
            var recognized = false
            for (sheet in workbook.sheets) {
                val header = findHeader(sheet) ?: continue
                recognized = true
                val result = readSheet(sheet, header)
                if (result.rows.isNotEmpty()) return result
            }
            require(recognized) { "未识别立创商城订单详情或物料明细对账单的表头。" }
            error("对账单中没有可入库的商品明细。")
        } finally {
            workbook.close()
        }
    }

    private data class Header(val row: Int, val columns: Map<String, Int>, val preambleOrder: String)

    private fun normalizedHeader(value: String): String =
        value.filterNot(Char::isWhitespace).replace('（', '(').replace('）', ')')

    private fun findHeader(sheet: Sheet): Header? {
        val preambleOrders = linkedSetOf<String>()
        for (row in 0 until minOf(sheet.rows, 40)) {
            val cells = sheet.getRow(row).map { it.contents.trim() }
            val columns = cells.indices.associateBy { normalizedHeader(cells[it]) }
            if (isOrderHeader(columns.keys)) {
                val orderInTable = "订单编号" in columns || "订单号" in columns
                require(orderInTable || preambleOrders.size <= 1) { "表头上方有多个订单号，请分订单导入。" }
                return Header(row, columns, preambleOrders.singleOrNull().orEmpty())
            }
            for ((index, cell) in cells.withIndex()) {
                val match = orderLabel.matchEntire(cell) ?: continue
                val candidate = match.groupValues[1].trim().ifBlank { cells.getOrNull(index + 1).orEmpty() }
                if (preambleOrderPattern.matches(candidate)) preambleOrders.add(candidate)
            }
        }
        return null
    }

    private fun isOrderHeader(names: Set<String>) =
        names.containsAll(statementHeaders) || names.containsAll(detailHeaders)

    private fun readSheet(sheet: Sheet, header: Header): LcscOrderImport {
        val (headerRow, columns, preambleOrder) = header
        fun column(vararg names: String) = names.firstNotNullOfOrNull { columns[it] }
        val orderColumn = column("订单编号", "订单号")
        val skuColumn = requireNotNull(column("商品编号"))
        val nameColumn = requireNotNull(column("商品名称"))
        val modelColumn = column("商品型号", "厂家型号")
        val brandColumn = column("品牌")
        val categoryColumn = column("商品类型", "商品分类", "分类")
        val packageColumn = column("封装格式", "封装")
        val quantityColumn = requireNotNull(column("订购数量(修改后)", "订购数量"))
        val subtotalColumn = column("小计金额(人民币含税)")
        val unitPriceColumn = column("单价(人民币含税)")
        val statusColumn = column("订单状态")
        val withheldColumn = column("是否不发此货")
        val grouped = linkedMapOf<String, OrderLine>()
        var mergedLines = 0
        var skippedFees = 0
        for (row in headerRow + 1 until sheet.rows) {
            fun value(column: Int?) = column?.let { sheet.getCell(it, row).contents.trim() }.orEmpty()
            val rowCells = sheet.getRow(row).map { it.contents.trim() }
            if (rowCells.all(String::isBlank) || isOrderHeader(rowCells.mapTo(mutableSetOf(), ::normalizedHeader))) continue
            val sku = value(skuColumn).uppercase(Locale.ROOT)
            val rawOrder = value(orderColumn)
            val order = if (orderColumn == null) preambleOrder else rawOrder
            val productName = value(nameColumn)
            val model = value(modelColumn)
            val brand = value(brandColumn)
            val category = value(categoryColumn)
            val packageName = value(packageColumn)
            val noProductFields = listOf(sku, model, brand, category, packageName, value(quantityColumn)).all(String::isBlank)
            if (noProductFields) {
                if (listOf("配送费", "运费", "运输费", "快递费").any(productName::contains)) {
                    skippedFees++
                    continue
                }
                val first = rowCells.firstOrNull(String::isNotBlank).orEmpty()
                if (productName.isBlank() &&
                    (rawOrder in summaryLabels || first in summaryLabels || first.startsWith("说明：") ||
                        first.startsWith("说明:") || first.startsWith("商品合计金额") ||
                        first.startsWith("订单合计金额") || first.startsWith("退款合计金额"))
                ) continue
            }
            require(order.isNotBlank() && skuPattern.matches(sku)) {
                "第 ${row + 1} 行缺少订单号或有效的 C 料号，请核对对账单。"
            }
            val status = value(statusColumn)
            require(!status.contains("取消") && !status.contains("退款")) {
                "第 ${row + 1} 行是取消或退款订单，需要先核对后再导入。"
            }
            require(value(withheldColumn).lowercase(Locale.ROOT) in setOf("", "否", "0", "-", "false")) {
                "第 ${row + 1} 行标记为不发货，请核对实际收货后再导入。"
            }
            val quantity = quantity(sheet, quantityColumn, row)
            fun money(column: Int?): BigDecimal? = try {
                column?.let {
                    val cell = sheet.getCell(it, row)
                    val text = if (cell is NumberCell) InventoryCost.text(BigDecimal.valueOf(cell.value)) else value(it)
                    InventoryCost.decimal(text)
                }
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${row + 1} 行采购金额无效：${error.message}", error)
            }
            val amount = money(subtotalColumn) ?: money(unitPriceColumn)?.multiply(BigDecimal(quantity))
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
                grouped[key] = prior.copy(
                    quantity = Math.addExact(prior.quantity, quantity),
                    amount = if (prior.amount == null || amount == null) null else InventoryCost.text(BigDecimal(prior.amount).add(amount)),
                )
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
        val number = if (cell is NumberCell) cell.value else quantityPattern.matchEntire(cell.contents.trim())
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
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
