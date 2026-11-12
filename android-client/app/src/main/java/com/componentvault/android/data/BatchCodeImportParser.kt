package com.componentvault.android.data

import com.componentvault.android.data.bom.TabularRow
import com.componentvault.android.data.bom.XlsxReadLimits
import com.componentvault.android.data.bom.XlsxWorkbookReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

internal enum class CodeImportResolution { Replace, Append }

internal data class CodeImportOverlap(val incoming: BatchJlcRow, val existing: List<BatchJlcRow>, val committed: Boolean) {
    val quantityChanges: Boolean
        get() {
            var total = 0L
            for (row in existing) {
                val quantity = row.quantityText.trim().toIntOrNull()?.takeIf { it > 0 } ?: return true
                total += quantity
            }
            return total != incoming.quantityText.toLongOrNull()
        }

    val ambiguousReplacement: Boolean get() = existing.size > 1 && quantityChanges
}

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
        // Editing another row must not change this row's identity.
        return grouped.map { (key, quantity) ->
            BatchJlcRow(id = "codes-${hash("${key.first.length}:${key.first}${key.second.length}:${key.second}:$quantity")}",
                raw = "${key.first}\t$quantity\t${key.second}", sku = key.first,
                quantityText = quantity.toString(), location = key.second)
        }
    }

    private fun key(row: BatchJlcRow) = row.sku.trim().uppercase() to row.location.trim()

    fun overlaps(draft: BatchJlcDraft, rows: List<BatchJlcRow>, receipts: Set<String>): List<CodeImportOverlap> {
        val existing = draft.rows.groupBy(::key)
        return rows.mapNotNull { incoming ->
            existing[key(incoming)]?.let { matches ->
                CodeImportOverlap(incoming, matches, matches.any { it.status == BatchJlcStatus.Committed || it.id in receipts })
            }
        }
    }

    fun append(draft: BatchJlcDraft, rows: List<BatchJlcRow>, receipts: Set<String>,
               resolution: CodeImportResolution? = null): Pair<BatchJlcDraft, Int> {
        val overlap = overlaps(draft, rows, receipts)
        require(overlap.isEmpty() || resolution != null) { "导入内容与草稿重叠，请选择替换或追加。" }
        val replacing = resolution == CodeImportResolution.Replace
        if (replacing) {
            require(overlap.none { it.committed }) { "重叠记录已入库，不能通过重新导入替换库存。" }
            require(overlap.none { it.ambiguousReplacement }) {
                "相同编码和库位有多条草稿，无法直接替换不同的合计数量。请编辑原行或选择追加。"
            }
        }
        val byKey = overlap.associateBy { key(it.incoming) }
        val correctedQuantities = if (replacing) {
            overlap.filter(CodeImportOverlap::quantityChanges)
                .associate { it.existing.single().id to it.incoming.quantityText }
        } else emptyMap()
        val retained = draft.rows.map { row ->
            correctedQuantities[row.id]?.let { row.copy(quantityText = it) } ?: row
        }
        val known = retained.mapTo(receipts.toMutableSet()) { it.id }
        val added = rows.mapNotNull { row ->
            val matches = byKey[key(row)]
            if (replacing && matches != null) return@mapNotNull null
            if (matches == null && row.id in receipts) return@mapNotNull null
            val id = if (matches != null || row.id in known) "codes-${UUID.randomUUID()}" else row.id
            require(known.add(id)) { "导入记录重复，请重新选择文件。" }
            row.copy(id = id)
        }
        require(retained.size + added.size <= BatchJlcDraftCodec.MaxRows) { "批量草稿最多 500 条，请先处理现有记录。" }
        val replaced = if (replacing) overlap.size else 0
        return draft.copy(rows = retained + added) to (rows.size - added.size - replaced)
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
