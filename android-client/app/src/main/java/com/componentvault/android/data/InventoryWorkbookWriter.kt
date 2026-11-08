package com.componentvault.android.data

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object InventoryWorkbookWriter {
    private val instantMillis=DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    fun write(data:InventoryWorkbook, exportedAt:String=instantMillis.format(Instant.now())):ByteArray {
        val sheets=listOf(
            "meta" to listOf(listOf<Any?>("format","component-vault"),listOf("schemaVersion","2"),listOf("exportedAt",exportedAt)),
            "components" to (listOf(InventoryWorkbookCodec.componentHeaders.map{it as Any?})+data.components.map{listOf(it.id,it.sku,it.name,it.category,it.packageName,it.location,it.description,it.quantity,it.minStock,it.updatedAt,it.deleted,it.baseUpdatedAt,"",it.inventoryUnitCost)}),
            "storage_locations" to (listOf(InventoryWorkbookCodec.locationHeaders.map{it as Any?})+data.locations.map{listOf(it.id,it.name,it.updatedAt,it.deleted)}),
            "allocations" to (listOf(InventoryWorkbookCodec.allocationHeaders.map{it as Any?})+data.allocations.map{listOf(it.componentId,it.locationId,it.quantity)}),
            "stock_movements" to (listOf(InventoryWorkbookCodec.movementHeaders.map{it as Any?})+data.movements.map{listOf(it.id,it.componentId,it.type,it.quantity,it.reason,it.note,it.happenedAt,it.updatedAt,it.deleted,it.locationId,it.destinationLocationId,it.purchaseAmount,it.costAmount)})
        )
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use{zip->
            fun add(name:String,value:String){zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()}
            val images=data.components.mapIndexedNotNull{i,c->data.images[c.id]?.let{Triple(i+1,c,it)}}
            add("[Content_Types].xml",types(sheets.size,images));add("_rels/.rels",rootRels())
            add("xl/workbook.xml",workbook(sheets.map{it.first}));add("xl/_rels/workbook.xml.rels",rels(sheets.size))
            sheets.forEachIndexed{i,s->add("xl/worksheets/sheet"+(i+1)+".xml",sheet(s.second,i==1&&images.isNotEmpty()))}
            if(images.isNotEmpty()){
                add("xl/worksheets/_rels/sheet2.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>")
                add("xl/drawings/drawing1.xml",drawing(images));add("xl/drawings/_rels/drawing1.xml.rels",drawingRels(images))
                images.forEachIndexed{i,p->zip.putNextEntry(ZipEntry("xl/media/image${i+1}.${WorkbookImageCodec.validateImage(p.third)}"));zip.write(p.third);zip.closeEntry()}
            }
        }
        return output.toByteArray()
    }
    internal fun writeSheets(sheets:List<Pair<String,List<List<Any?>>>>):ByteArray {
        require(sheets.isNotEmpty()) { "工作簿至少需要一个工作表。" }
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use{zip->
            fun add(name:String,value:String){zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()}
            add("[Content_Types].xml",types(sheets.size,emptyList()));add("_rels/.rels",rootRels())
            add("xl/workbook.xml",workbook(sheets.map{it.first}));add("xl/_rels/workbook.xml.rels",rels(sheets.size))
            sheets.forEachIndexed{i,s->add("xl/worksheets/sheet"+(i+1)+".xml",sheet(s.second))}
        }
        return output.toByteArray()
    }
    private fun sheet(rows:List<List<Any?>>,drawing:Boolean=false)=buildString{append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>");rows.forEachIndexed{ri,row->append("<row r=\""+(ri+1)+"\">");row.forEachIndexed{ci,v->if(v!=null){val ref=column(ci)+(ri+1);when(v){is Number->append("<c r=\""+ref+"\"><v>"+v+"</v></c>");is Boolean->append("<c r=\""+ref+"\" t=\"b\"><v>"+(if(v)"1" else "0")+"</v></c>");else->append("<c r=\""+ref+"\" t=\"inlineStr\"><is><t>"+escape(v.toString())+"</t></is></c>")}}};append("</row>")};append("</sheetData>");if(drawing)append("<drawing r:id=\"rId1\"/>");append("</worksheet>")}
    private fun column(index:Int):String{var n=index+1;var s="";while(n>0){n--;s=('A'.code+n%26).toChar()+s;n/=26};return s}
    private fun escape(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun workbook(names:List<String>)="<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"+names.mapIndexed{i,n->"<sheet name=\""+escape(n)+"\" sheetId=\""+(i+1)+"\" r:id=\"rId"+(i+1)+"\"/>"}.joinToString("")+"</sheets></workbook>"
    private fun rels(n:Int)="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+(1..n).joinToString(""){"<Relationship Id=\"rId"+it+"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet"+it+".xml\"/>"}+"</Relationships>"
    private fun rootRels()="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
    private fun types(n:Int,images:List<Triple<Int,WorkbookComponent,ByteArray>>)="<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"+(if(images.any{WorkbookImageCodec.validateImage(it.third)=="png"})"<Default Extension=\"png\" ContentType=\"image/png\"/>" else "")+(if(images.any{WorkbookImageCodec.validateImage(it.third)=="jpg"})"<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" else "")+"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"+(1..n).joinToString(""){"<Override PartName=\"/xl/worksheets/sheet"+it+".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}+(if(images.isNotEmpty())"<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" else "")+"</Types>"
    private fun drawing(images:List<Triple<Int,WorkbookComponent,ByteArray>>)="<?xml version=\"1.0\"?><xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"+images.mapIndexed{i,p->"<xdr:oneCellAnchor><xdr:from><xdr:col>12</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${p.first}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx=\"914400\" cy=\"914400\"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${i+1}\" name=\"image${i+1}\"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId${i+1}\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>"}.joinToString("")+"</xdr:wsDr>"
    private fun drawingRels(images:List<Triple<Int,WorkbookComponent,ByteArray>>)="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+images.mapIndexed{i,p->"<Relationship Id=\"rId${i+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image${i+1}.${WorkbookImageCodec.validateImage(p.third)}\"/>"}.joinToString("")+"</Relationships>"
}
