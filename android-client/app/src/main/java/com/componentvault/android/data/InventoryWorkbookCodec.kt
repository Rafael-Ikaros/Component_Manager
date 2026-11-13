package com.componentvault.android.data

import com.componentvault.android.data.bom.TabularRow
import com.componentvault.android.data.bom.XlsxWorkbookReader
import com.componentvault.android.data.bom.XlsxReadLimits
import java.security.MessageDigest
import java.math.BigDecimal

internal enum class WorkbookSource { ComponentVault, LcscAndroidErp }
internal data class WorkbookComponent(val id:String,val sku:String,val name:String,val category:String,val packageName:String,val location:String,val description:String?,val quantity:Int,val minStock:Int,val updatedAt:String,val deleted:Boolean,val baseUpdatedAt:String?,val inventoryUnitCost:String? = null)
internal data class WorkbookLocation(val id:String,val name:String,val updatedAt:String,val deleted:Boolean)
internal data class WorkbookAllocation(val componentId:String,val locationId:String,val quantity:Int)
internal data class WorkbookMovement(val id:String,val componentId:String,val type:String,val quantity:Int,val reason:String,val note:String?,val happenedAt:String,val updatedAt:String,val deleted:Boolean,val locationId:String?,val destinationLocationId:String?,val purchaseAmount:String? = null,val costAmount:String? = null)
internal data class InventoryWorkbook(val source:WorkbookSource,val components:List<WorkbookComponent>,val locations:List<WorkbookLocation>,val allocations:List<WorkbookAllocation>,val movements:List<WorkbookMovement>,val fingerprint:String,val warnings:List<String>,val images:Map<String,ByteArray> = emptyMap())

internal object InventoryWorkbookCodec {
    const val MAX_FILE_BYTES = 50 * 1024 * 1024
    const val NULLABLE_TEXT_META = "nullableText"
    private val textCellTypes = setOf("s", "inlineStr", "str")
    val componentHeaders=listOf("id","sku","name","category","package_name","location","description","quantity","min_stock","updated_at","deleted","base_updated_at","image_preview","inventory_unit_cost")
    val locationHeaders=listOf("id","name","updated_at","deleted")
    val allocationHeaders=listOf("component_id","location_id","quantity")
    val movementHeaders=listOf("id","component_id","movement_type","quantity","reason","note","happened_at","updated_at","deleted","location_id","destination_location_id","purchase_amount","cost_amount")

    fun parse(bytes:ByteArray):InventoryWorkbook {
        val sheets=XlsxWorkbookReader.read(bytes,true,loadAllWorksheets=true,limits=XlsxReadLimits.InventoryBackup).rowsBySheetName
        val meta=sheets["meta"] ?: error("缺少 meta 工作表。")
        val pairs=meta.associate { it.values.getOrElse(0){""}.trim() to it.values.getOrElse(1){""}.trim() }
        return when {
            pairs["format"]=="component-vault" && pairs["schemaVersion"] in setOf("1","2") -> own(sheets,bytes,pairs["schemaVersion"]=="2", pairs[NULLABLE_TEXT_META] == "1")
            pairs["schemaVersion"]=="1" && sheets.containsKey("inventory_items") -> lcsc(sheets,bytes)
            else -> error("不支持的库存工作簿格式或版本。")
        }
    }

    private fun own(s:Map<String,List<TabularRow>>,b:ByteArray,v2:Boolean,nullableText:Boolean):InventoryWorkbook {
        if(v2) {
            require(s["components"]?.firstOrNull()?.values == componentHeaders && s["stock_movements"]?.firstOrNull()?.values == movementHeaders) { "schema2 必须包含成本列。" }
            requireTextColumns(s,"components",listOf(13),allowBlank=true)
            requireTextColumns(s,"stock_movements",listOf(11,12),allowBlank=true)
        }
        requireTextColumns(s,"components",listOf(0,1));requireTextColumns(s,"storage_locations",listOf(0,1));requireTextColumns(s,"allocations",listOf(0,1));requireTextColumns(s,"stock_movements",listOf(0,1,9,10),allowBlank=true)
        val descriptions = optionalText(s, "components", "description", componentHeaders, nullableText)
        val c=table(s,"components",componentHeaders).mapIndexed{index,r->WorkbookComponent(r.req("id"),r.req("sku"),r.req("name"),r.req("category"),r.req("package_name"),r.req("location"),descriptions[index],r.nonNeg("quantity"),r.nonNeg("min_stock"),r.req("updated_at"),r.bool("deleted"),r["base_updated_at"].blankNull(),r["inventory_unit_cost"].blankNull()?.also { InventoryCost.decimal(it,16) })}
        val l=table(s,"storage_locations",locationHeaders).map{r->WorkbookLocation(r.req("id"),r.req("name"),r.req("updated_at"),r.bool("deleted"))}
        val a=table(s,"allocations",allocationHeaders).map{r->WorkbookAllocation(r.req("component_id"),r.req("location_id"),r.nonNeg("quantity"))}
        val notes = optionalText(s, "stock_movements", "note", movementHeaders, nullableText)
        val m=table(s,"stock_movements",movementHeaders).mapIndexed{index,r->WorkbookMovement(r.req("id"),r.req("component_id"),r.req("movement_type"),r.integer("quantity"),r.req("reason"),notes[index],r.req("happened_at"),r.req("updated_at"),r.bool("deleted"),r["location_id"].blankNull(),r["destination_location_id"].blankNull(),r["purchase_amount"].blankNull()?.also { InventoryCost.decimal(it) },InventoryCost.signed(r["cost_amount"].blankNull()))}
        validate(c,l,a,m); return InventoryWorkbook(WorkbookSource.ComponentVault,c,l,a,m,sha(b),emptyList(),WorkbookImageCodec.extract(b,c,12))
    }

    private fun lcsc(s:Map<String,List<TabularRow>>,b:ByteArray):InventoryWorkbook {
        val lh=listOf("id","code","displayName","colorHex","sortMode","remark","createdAt")
        val ch=listOf("id","partNumber","name","brand","packageName","category","specJson","description","sourceUrl","updatedAt","imagePreview")
        val ih=listOf("id","componentId","locationId","quantity","lastInboundAt","updatedAt")
        val lr=table(s,"storage_locations",lh); val cr=table(s,"components",ch); val ir=table(s,"inventory_items",ih)
        val sourceLocations=lr.associate { row -> row.numericId("id") to row.req("code") }
        unique(sourceLocations.values.toList(), "外部库位 code")
        val l=lr.map{row->WorkbookLocation(row.req("code"),row["displayName"].blankNull()?:row.req("code"),epoch(row.req("createdAt")),false)}
        require(l.isNotEmpty()){ "外部备份必须至少包含一个库位。" }
        val rawAllocations=ir.map{row->WorkbookAllocation("cmp-legacy-"+row.numericId("componentId"),sourceLocations[row.numericId("locationId")]?:error("inventory_items 引用了未知库位。"),row.nonNeg("quantity"))}
        val knownIds=rawAllocations.map{it.componentId}.toSet()
        val a=rawAllocations+cr.map{"cmp-legacy-"+it.numericId("id")}.filterNot{it in knownIds}.map{WorkbookAllocation(it,l.first().id,0)}
        val grouped=a.groupBy{it.componentId}
        val c=cr.map{r->val id="cmp-legacy-"+r.numericId("id");WorkbookComponent(id,r.req("partNumber").uppercase(),r["name"].blankNull()?:r.req("partNumber"),r["category"].blankNull()?:"未分类",r["packageName"].blankNull()?:"未知",grouped[id]?.firstOrNull()?.locationId?:l.firstOrNull()?.id.orEmpty(),listOfNotNull(r["description"].blankNull(),r["brand"].blankNull()?.let{"品牌："+it},r["specJson"].blankNull()?.let{"参数："+it},r["sourceUrl"].blankNull()?.let{"来源："+it}).joinToString("\n"),grouped[id].orEmpty().sumOf{it.quantity},0,epoch(r.req("updatedAt")),false,null)}
        validate(c,l,a,emptyList()); return InventoryWorkbook(WorkbookSource.LcscAndroidErp,c,l,a,emptyList(),sha(b),listOf("外部备份没有历史流水；恢复时只生成初始库存记录。"),WorkbookImageCodec.extract(b,c,10,11))
    }

    private fun validate(c:List<WorkbookComponent>,l:List<WorkbookLocation>,a:List<WorkbookAllocation>,m:List<WorkbookMovement>) {
        unique(c.map{it.id},"组件 ID"); unique(c.filterNot{it.deleted}.map{it.sku.uppercase()},"有效 SKU"); unique(l.map{it.id},"库位 ID"); unique(a.map{it.componentId+"\u0000"+it.locationId},"分配键"); unique(m.map{it.id},"流水 ID")
        val ci=c.map{it.id}.toSet();val li=l.map{it.id}.toSet()
        require(a.all{it.componentId in ci && it.locationId in li}){"分配包含孤立外键。"};require(m.all{it.componentId in ci}){"流水包含孤立组件。"}
        c.forEach{x->require(x.quantity!=0 || x.inventoryUnitCost==null){"零库存成本必须为空。"};val rows=a.filter{it.componentId==x.id};require(rows.isNotEmpty()){x.sku+" 缺少库位分配。"};val sum=rows.fold(0L){v,q->Math.addExact(v,q.quantity.toLong())};require(sum==x.quantity.toLong()){x.sku+" 的分配合计与库存不一致。"};java.time.Instant.parse(x.updatedAt);x.baseUpdatedAt?.let{java.time.Instant.parse(it)}}
        m.forEach{require(it.type=="inbound" || it.purchaseAmount==null){"仅入库流水允许采购金额。"};java.time.Instant.parse(it.happenedAt);java.time.Instant.parse(it.updatedAt);require(it.locationId==null||it.locationId in li){"流水包含未知来源库位。"};require(it.destinationLocationId==null||it.destinationLocationId in li){"流水包含未知目标库位。"}}
        m.filter{it.type=="transfer"&&!it.deleted}.forEach{require(it.quantity>0&&it.locationId!=null&&it.destinationLocationId!=null&&it.locationId!=it.destinationLocationId){"调拨流水无效。"}}
    }
    private fun unique(v:List<String>,n:String){require(v.size==v.toSet().size){n+" 存在重复值。"}}
    private fun table(s:Map<String,List<TabularRow>>,n:String,h:List<String>):List<Map<String,String>>{val rows=s[n]?:error("缺少 "+n+" 工作表。");require(rows.firstOrNull()?.values==h || rows.firstOrNull()?.values==h.filterNot { it in setOf("inventory_unit_cost","purchase_amount","cost_amount") }){n+" 表头不匹配。"};return rows.drop(1).map{row->h.mapIndexed{i,k->k to row.values.getOrElse(i){""}}.toMap()}}
    // New backups omit a NULL cell and write an explicit text cell for an empty string.
    // Older exports collapsed both cases, so preserve their previous interpretation.
    private fun optionalText(
        sheets: Map<String, List<TabularRow>>,
        sheet: String,
        field: String,
        headers: List<String>,
        nullableText: Boolean,
    ): List<String?> {
        val column = headers.indexOf(field)
        return sheets[sheet].orEmpty().drop(1).map { row ->
            val value = row.values.getOrElse(column) { "" }
            if (!nullableText || value.isNotEmpty() || row.cellTypes.getOrElse(column) { "" } in textCellTypes) {
                value
            } else {
                null
            }
        }
    }
    private fun requireTextColumns(s:Map<String,List<TabularRow>>,name:String,columns:List<Int>,allowBlank:Boolean=false){s[name]?.drop(1)?.forEach{row->columns.forEach{column->val value=row.values.getOrElse(column){""};if(value.isNotBlank()||!allowBlank)require(row.cellTypes.getOrElse(column){""} in textCellTypes){"$name 第 ${row.rowNumber} 行的 ID/SKU/库位必须是 Excel 文本单元格。"}}}}
    private fun Map<String,String>.req(k:String)=get(k)?.trim()?.takeIf{it.isNotEmpty()}?:error(k+" 不能为空。")
    private fun Map<String,String>.integer(k:String)=integral(k).let{runCatching{it.intValueExact()}.getOrElse{error(k+" 必须是严格整数且不能溢出。")}}
    private fun Map<String,String>.nonNeg(k:String)=integer(k).also{require(it>=0){k+" 不能为负数。"}}
    private fun Map<String,String>.bool(k:String)=when(req(k).uppercase()){"TRUE"->true;"FALSE"->false;else->error(k+" 必须是 Boolean。")}
    private fun String?.blankNull()=this?.trim()?.takeIf{it.isNotEmpty()}
    private fun Map<String,String>.numericId(k:String)=integral(k).let{runCatching{it.toBigIntegerExact().toString()}.getOrElse{error(k+" 必须是整数 ID。")}}
    private fun Map<String,String>.integral(k:String)=runCatching{BigDecimal(req(k))}.getOrElse{error(k+" 必须是有限数值。")}.also{runCatching{it.toBigIntegerExact()}.getOrElse{error(k+" 必须是整数。")}}
    private fun epoch(v:String)=runCatching{BigDecimal(v).longValueExact()}.mapCatching{java.time.Instant.ofEpochMilli(it).toString()}.getOrElse{error("时间必须是未溢出的 epoch millis 整数。")}
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
}
