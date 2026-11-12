package com.componentvault.android.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchCodeImportParserTest {
    private val locations = setOf("LOC-A", "LOC-B")

    @Test fun textNormalizesCodesAndMergesOnlySameLocation() {
        val rows = BatchCodeImportParser.text("C编码 数量\nc123,10\n\nC123\t5\nC456，2", "LOC-A", locations)
        assertEquals(listOf("C123", "C456"), rows.map { it.sku })
        assertEquals(listOf("15", "2"), rows.map { it.quantityText })
        assertTrue(rows.all { it.location == "LOC-A" && it.status == BatchJlcStatus.Captured && it.name.isBlank() })
    }

    @Test fun malformedTextReportsOriginalLineAndDoesNotReturnPartialRows() {
        for (line in listOf("C123 0", "C123 -1", "C123 1.2", "C123 2147483648", "R123 5", "C123", "C123 2 LOC-A")) {
            val error = assertFailsWith<IllegalArgumentException> {
                BatchCodeImportParser.text("C123 2\n\n$line", "LOC-A", locations)
            }
            assertTrue(error.message!!.contains("第 3 行"), error.message)
        }
        assertFailsWith<IllegalArgumentException> { BatchCodeImportParser.text("C123 1", "", locations) }
        assertFailsWith<IllegalArgumentException> { BatchCodeImportParser.text("C123 1", "Unknown", locations) }
    }

    @Test fun xlsxReadsFixedColumnsAndUsesFallbackForSparseLocationCells() {
        val input = workbook("""
            <row r="1">${cell("A1", "C代码")}${cell("B1", "数量")}${cell("C1", "库位编码")}</row>
            <row r="3"><c r="A3" t="s"><v>0</v></c><c r="B3"><v>10.0</v></c>${cell("C3", "LOC-B")}</row>
            <row r="4">${cell("A4", "C123")}<c r="B4"><v>2E1</v></c></row>
            <row r="5">${cell("A5", "C123")}<c r="B5"><v>5</v></c>${cell("C5", "LOC-B")}</row>
        """)
        val rows = BatchCodeImportParser.xlsx(input.inputStream(), "LOC-A", locations)
        assertEquals(listOf("LOC-B", "LOC-A"), rows.map { it.location })
        assertEquals(listOf("15", "20"), rows.map { it.quantityText })
    }

    @Test fun xlsxCanUsePerRowLocationsWithoutADefaultAndRejectsBadRows() {
        val rows = BatchCodeImportParser.xlsx(workbook("<row r=\"2\">${cell("A2", "C123")}${cell("B2", "2")}${cell("C2", "LOC-B")}</row>").inputStream(), "", locations)
        assertEquals("LOC-B", rows.single().location)
        for (cells in listOf(
            cell("A8", "C123") + cell("B8", "2") + cell("C8", "UNKNOWN"),
            cell("A8", "C123") + cell("C8", "LOC-A"),
            cell("A8", "C123") + cell("B8", "1.5") + cell("C8", "LOC-A"),
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                BatchCodeImportParser.xlsx(workbook("<row r=\"8\">$cells</row>").inputStream(), "LOC-A", locations)
            }
            assertTrue(error.message!!.contains("第 8 行"))
        }
    }

    @Test fun formulaWorkbookUsesExistingReadersRejection() {
        val error = assertFailsWith<com.componentvault.android.data.bom.LocalImportException> {
            BatchCodeImportParser.xlsx(workbook("<row r=\"1\">${cell("A1", "C123")}<c r=\"B1\"><f>1+1</f><v>2</v></c></row>").inputStream(), "LOC-A", locations)
        }
        assertTrue(error.message!!.contains("公式"))
    }

    @Test fun reimportingIdenticalContentDoesNotDuplicateDraftOrCommittedRows() {
        val rows = BatchCodeImportParser.text("C123 2\nC456 3", "LOC-A", locations)
        val reordered = BatchCodeImportParser.text("C456 3\nc123 2", "LOC-A", locations)
        assertEquals(rows.map { it.id }.toSet(), reordered.map { it.id }.toSet())
        val (draft, skipped) = BatchCodeImportParser.append(BatchJlcDraft(rows = rows), reordered, emptySet())
        assertEquals(2, skipped)
        assertEquals(2, draft.rows.size)
        val (recovered, receiptSkipped) = BatchCodeImportParser.append(BatchJlcDraft(), rows, rows.map { it.id }.toSet())
        assertEquals(2, receiptSkipped)
        assertTrue(recovered.rows.isEmpty())
    }

    @Test fun limitsRejectOverflowAndOversizedDrafts() {
        assertFailsWith<IllegalStateException> { BatchCodeImportParser.text("C123 2147483647\nC123 1", "LOC-A", locations) }
        assertFailsWith<IllegalArgumentException> { BatchCodeImportParser.text((1..501).joinToString("\n") { "C$it 1" }, "LOC-A", locations) }
        val full = BatchCodeImportParser.text((1..500).joinToString("\n") { "C$it 1" }, "LOC-A", locations)
        assertEquals(500, full.size)
        assertFailsWith<IllegalArgumentException> { BatchCodeImportParser.append(BatchJlcDraft(rows = full),
            BatchCodeImportParser.text("C9999 1", "LOC-A", locations), emptySet()) }
    }

    private fun cell(ref: String, value: String) = "<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>"
    private fun workbook(rows: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray()); zip.closeEntry()
            }
            entry("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Import" sheetId="1" r:id="r1"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            entry("xl/sharedStrings.xml", "<sst><si><t>C123</t></si></sst>")
            entry("xl/worksheets/sheet1.xml", "<worksheet><sheetData>$rows</sheetData></worksheet>")
        }
    }.toByteArray()
}
