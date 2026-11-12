package com.componentvault.android.data

import com.componentvault.android.data.bom.TabularRow
import com.componentvault.android.data.bom.XlsxReadLimits
import com.componentvault.android.data.bom.XlsxWorkbookReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest

/** Only reads the input columns. Catalog lookup and inventory writes stay in the batch workflow. */
internal object BatchCodeImportParser {
    private const val MaxBytes = 10 * 1024 * 1024
    private const val MaxTextChars = 64 * 1024
    private val codeHeaders = setOf("c编码", "c代码", "c料号", "料号", "商品编号", "sku", "code")
    private val quantityHeaders = setOf("数量", "quantity", "qty")

    fun text(value: String, defaultLocation: String, locationIds: Set<String>): List<BatchJlcRow> {
        require(value.length <= MaxTextChars) { "文本超过 65536 个字符，请分批导入。" }
        val rows = value.lineSequence().mapIndexedNotNull { index, line ->
            val trimmed = line.trim().removePrefix("\uFEFF")
            if (trimmed.isBlank()) null else TabularRow(index + 1, trimmed.split(Regex("[\\s,，;；]+")))
        }.toList()
        return parse(rows, defaultLocation, locationIds, textOnly = true)
    }

    fun xlsx(input: InputStream, defaultLocation: String, locationIds: Set<String>): List<BatchJlcRow> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MaxBytes) { "XLSX 文件超过 10 MiB。" }
            output.write(buffer, 0, count)
        }
        val workbook = XlsxWorkbookReader.read(output.toByteArray(), loadWorksheetRows = true,
            limits = XlsxReadLimits.Bom.copy(maxFileBytes = MaxBytes, maxRowsPerSheet = BatchJlcDraftCodec.MaxRows))
        val firstVisible = workbook.sheets.firstOrNull { !it.hidden }
            ?: error("XLSX 中没有可见工作表。")
        return parse(workbook.rowsBySheetName[firstVisible.name].orEmpty(), defaultLocation, locationIds, textOnly = false)
    }

    private fun parse(rows: List<TabularRow>, defaultLocation: String, locationIds: Set<String>,
                      textOnly: Boolean): List<BatchJlcRow> {
        val grouped = linkedMapOf<Pair<String, String>, Int>()
        var dataRows = 0
        var firstContent = true
        rows.forEach { row ->
            fun cell(column: Int) = row.values.getOrNull(column).orEmpty().trim()
            if (row.values.take(3).all(String::isBlank)) return@forEach
            val header = firstContent && cell(0).lowercase() in codeHeaders && cell(1).lowercase() in quantityHeaders
            firstContent = false
            if (header) return@forEach
            val prefix = "第 ${row.rowNumber} 行："
            require(!textOnly || row.values.size == 2) { "${prefix}请填写 C 编码和数量两列。" }
            val sku = requireNotNull(LcscPublicCatalog.normalizeSku(cell(0))) { "${prefix}C 编码无效。" }
            val quantity = runCatching { BigDecimal(cell(1)).intValueExact() }.getOrNull()
            require(quantity != null && quantity > 0) { "${prefix}数量必须为 1–${Int.MAX_VALUE} 的整数。" }
            val location = (if (textOnly) "" else cell(2)).ifBlank { defaultLocation.trim() }
            require(location.isNotBlank() && location in locationIds) { "${prefix}库位编码为空或不存在：$location" }
            require(++dataRows <= BatchJlcDraftCodec.MaxRows) { "每次最多导入 500 行，请分批导入。" }
            val key = sku to location
            grouped[key] = try { Math.addExact(grouped[key] ?: 0, quantity) }
                catch (_: ArithmeticException) { error("${prefix}相同 C 编码与库位的合计数量溢出。") }
        }
        require(grouped.isNotEmpty()) { "没有可导入的 C 编码与数量。" }
        // Identical imports in the same draft retain IDs, so reopening a file does not double inbound.
        val canonical = grouped.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).joinToString("") {
            "${it.key.first.length}:${it.key.first}${it.key.second.length}:${it.key.second}:${it.value};"
        }
        val batchId = hash(canonical)
        return grouped.map { (key, quantity) ->
            BatchJlcRow(id = "codes-${hash("$batchId:${key.first}:${key.second}")}",
                raw = "${key.first}\t$quantity\t${key.second}", sku = key.first,
                quantityText = quantity.toString(), location = key.second)
        }
    }

    fun append(draft: BatchJlcDraft, rows: List<BatchJlcRow>, receipts: Set<String>): Pair<BatchJlcDraft, Int> {
        val known = draft.rows.mapTo(receipts.toMutableSet()) { it.id }
        val added = rows.filter { known.add(it.id) }
        require(draft.rows.size + added.size <= BatchJlcDraftCodec.MaxRows) { "批量草稿最多 500 条，请先处理现有记录。" }
        return draft.copy(rows = draft.rows + added) to (rows.size - added.size)
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
