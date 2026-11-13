package com.componentvault.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class InventoryWorkbookCodecTest {
    private fun sample(components:List<WorkbookComponent> = listOf(WorkbookComponent("cmp-1","C1","电阻","电阻","0603","loc-1","",4,1,"2026-09-15T00:00:00Z",false,null)))=InventoryWorkbook(
        WorkbookSource.ComponentVault,components,listOf(WorkbookLocation("loc-1","A-01","2026-09-15T00:00:00Z",false)),
        listOf(WorkbookAllocation("cmp-1","loc-1",4)),
        listOf(WorkbookMovement("mov-1","cmp-1","inbound",4,"initial","","2026-09-15T00:00:00Z","2026-09-15T00:00:00Z",false,"loc-1",null)),"",emptyList())

    @Test fun ownWorkbookRoundTripsExactTablesAndTransferFields(){
        val transfer=WorkbookMovement("mov-2","cmp-1","transfer",2,"move","","2026-09-15T01:00:00Z","2026-09-15T01:00:00Z",false,"loc-1","loc-2")
        val model=sample().copy(locations=sample().locations+WorkbookLocation("loc-2","B-02","2026-09-15T00:00:00Z",false),movements=sample().movements+transfer)
        val parsed=InventoryWorkbookCodec.parse(InventoryWorkbookWriter.write(model,"2026-09-15T02:00:00.000Z"))
        assertEquals(WorkbookSource.ComponentVault,parsed.source);assertEquals(transfer,parsed.movements.last());assertTrue(parsed.fingerprint.length==64)
    }

    @Test fun costFieldsRoundTripIncludingNegativeValuation() {
        val original = sample()
        val model = original.copy(components = original.components.map { it.copy(inventoryUnitCost = "0.1234567890123456") }, movements = original.movements.map { it.copy(type = "adjustment", quantity = 0, costAmount = "-2.25") })
        val parsed = InventoryWorkbookCodec.parse(InventoryWorkbookWriter.write(model))
        assertEquals(model.components, parsed.components)
        assertEquals(model.movements, parsed.movements)
    }

    @Test fun nullableTextDistinguishesNullEmptyAndLiteralNull() {
        val original = sample()
        val values = listOf(null, "", "null")
        val model = original.copy(
            components = values.mapIndexed { index, value ->
                original.components.single().copy(id = "cmp-$index", sku = "C$index", description = value)
            },
            allocations = values.indices.map { index ->
                original.allocations.single().copy(componentId = "cmp-$index")
            },
            movements = values.mapIndexed { index, value ->
                original.movements.single().copy(id = "mov-$index", componentId = "cmp-$index", note = value)
            },
        )
        val bytes = InventoryWorkbookWriter.write(model)
        val parsed = InventoryWorkbookCodec.parse(bytes)
        assertEquals(values, parsed.components.map { it.description })
        assertEquals(values, parsed.movements.map { it.note })

        val oldSchema2 = rewriteEntries(bytes) { path, xml ->
            if (path.endsWith("sheet1.xml")) xml.replace("nullableText", "unused") else xml
        }
        val oldParsed = InventoryWorkbookCodec.parse(oldSchema2)
        assertEquals(listOf("", "", "null"), oldParsed.components.map { it.description })
        assertEquals(listOf("", "", "null"), oldParsed.movements.map { it.note })
    }

    @Test fun duplicateActiveSkuIsRejected(){
        val duplicate=sample().components.first().copy(id="cmp-2")
        val model=sample(sample().components+duplicate).copy(allocations=sample().allocations+WorkbookAllocation("cmp-2","loc-1",4))
        assertFailsWith<IllegalArgumentException>{InventoryWorkbookCodec.parse(InventoryWorkbookWriter.write(model))}
    }

    @Test fun ownNumericQuantitiesAcceptIntegralDecimalsButRejectBoolean(){
        val decimal=rewriteEntries(InventoryWorkbookWriter.write(sample())){path,xml->if(path.endsWith("sheet2.xml")||path.endsWith("sheet4.xml"))xml.replace("<v>4</v>","<v>4.0</v>") else xml}
        assertEquals(4,InventoryWorkbookCodec.parse(decimal).components.single().quantity)
        val bool=rewriteEntries(decimal){path,xml->if(path.endsWith("sheet4.xml"))xml.replace("<c r=\"C2\"><v>4.0</v></c>","<c r=\"C2\" t=\"b\"><v>1</v></c>") else xml}
        assertFailsWith<IllegalStateException>{InventoryWorkbookCodec.parse(bool)}
    }

    @Test fun lcscPoiNumericCellsAcceptIntegralDecimalsAndScientificEpoch(){
        val parsed=InventoryWorkbookCodec.parse(lcscPoiFixture("5.0"))
        assertEquals("cmp-legacy-1",parsed.components.single().id)
        assertEquals(5,parsed.components.single().quantity)
        assertEquals("A-01",parsed.locations.single().id)
        assertEquals("2023-11-14T22:13:20Z",parsed.components.single().updatedAt)
    }

    @Test fun lcscPoiNumericCellsRejectFractions(){
        assertFailsWith<IllegalStateException>{InventoryWorkbookCodec.parse(lcscPoiFixture("1.5"))}
    }

    private fun lcscPoiFixture(quantity:String):ByteArray{
        val sheets=listOf(
            "meta" to listOf(listOf("schemaVersion","1")),
            "storage_locations" to listOf(
                listOf("id","code","displayName","colorHex","sortMode","remark","createdAt"),
                listOf("#1.0","A-01","主库位","","","","#1700000000000.0"),
            ),
            "components" to listOf(
                listOf("id","partNumber","name","brand","packageName","category","specJson","description","sourceUrl","updatedAt","imagePreview"),
                listOf("#1.0","C70565","晶振","YXC","3225","晶振","{}","desc","https://item.szlcsc.com/1.html","#1.7E12",""),
            ),
            "inventory_items" to listOf(
                listOf("id","componentId","locationId","quantity","lastInboundAt","updatedAt"),
                listOf("#9.0","#1.0","#1.0","#$quantity","","#1700000000000.0"),
            ),
        )
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{zip->
            fun add(path:String,text:String){zip.putNextEntry(ZipEntry(path));zip.write(text.toByteArray());zip.closeEntry()}
            add("[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"+(1..4).joinToString(""){"<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}+"</Types>")
            add("_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            add("xl/workbook.xml","<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"+sheets.mapIndexed{i,s->"<sheet name=\"${s.first}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>"}.joinToString("")+"</sheets></workbook>")
            add("xl/_rels/workbook.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+(1..4).joinToString(""){"<Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$it.xml\"/>"}+"</Relationships>")
            sheets.forEachIndexed{i,s->add("xl/worksheets/sheet${i+1}.xml",poiSheet(s.second))}
        };return out.toByteArray()
    }
    private fun poiSheet(rows:List<List<String>>)="<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"+rows.mapIndexed{ri,row->"<row r=\"${ri+1}\">"+row.mapIndexed{ci,value->val ref=('A'.code+ci).toChar().toString()+(ri+1);if(value.startsWith("#"))"<c r=\"$ref\"><v>${value.drop(1)}</v></c>" else "<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>"}.joinToString("")+"</row>"}.joinToString("")+"</sheetData></worksheet>"
    private fun rewriteEntries(bytes:ByteArray,change:(String,String)->String):ByteArray{val out=ByteArrayOutputStream();ZipOutputStream(out).use{target->ZipInputStream(bytes.inputStream()).use{source->while(true){val entry=source.nextEntry?:break;val data=source.readBytes();target.putNextEntry(ZipEntry(entry.name));target.write(if(entry.name.endsWith(".xml"))change(entry.name,data.toString(Charsets.UTF_8)).toByteArray() else data);target.closeEntry()}}};return out.toByteArray()}
}
