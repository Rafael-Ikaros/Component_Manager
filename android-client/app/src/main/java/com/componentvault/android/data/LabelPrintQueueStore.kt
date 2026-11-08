package com.componentvault.android.data

import android.content.Context
import android.util.AtomicFile
import com.componentvault.android.model.ComponentLabelSeed
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Synchronous storage. Callers schedule load/save/clear off the UI thread. */
internal class LabelPrintQueueStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "label-print-queue.json"))

    fun load(): LabelPrintQueue {
        val queue = try {
            val json = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            LabelPrintQueueCodec.decode(json)
        } catch (_: FileNotFoundException) {
            return LabelPrintQueue()
        } catch (error: Exception) {
            throw IllegalStateException("Cannot read saved label print queue; clear it explicitly to start over", error)
        }
        val recovered = queue.recovered()
        if (recovered != queue) save(recovered)
        return recovered
    }

    fun save(queue: LabelPrintQueue) {
        val bytes = LabelPrintQueueCodec.encode(queue).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() = file.delete()
}

internal object LabelPrintQueueCodec {
    private const val Version = 4

    fun encode(queue: LabelPrintQueue): String = JSONObject()
        .put("version", Version)
        .put("printerProfileId", queue.printerProfileId)
        .put("mediaType", queue.mediaType)
        .put("templateId", queue.templateId)
        .put("textTemplateId", queue.textTemplateId)
        .put("paper", JSONObject()
            .put("widthMm", queue.paper.widthMm)
            .put("heightMm", queue.paper.heightMm)
            .put("rotationDegrees", queue.paper.rotationDegrees)
            .put("offsetXmm", queue.paper.offsetXmm)
            .put("offsetYmm", queue.paper.offsetYmm))
        .put("items", JSONArray().apply {
            queue.items.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("seed", item.seed?.let(::encodeSeed) ?: JSONObject.NULL)
                    .put("freeLabel", item.freeLabel?.let { free -> JSONObject()
                        .put("title", free.title).put("template", free.template.name)
                    } ?: JSONObject.NULL)
                    .put("copyNumber", item.copyNumber)
                    .put("copies", item.copies)
                    .put("state", item.state.name)
                    .put("detail", item.detail)
                    .put("design", item.design?.let(::encodeDesign) ?: JSONObject.NULL))
            }
        }).toString()

    fun decode(json: String): LabelPrintQueue {
        val root = JSONObject(json)
        val version = root.getInt("version")
        require(version in 1..Version) { "Unsupported label print queue version" }
        val paperJson = root.getJSONObject("paper")
        val paper = M1TestPaperProfile(
            paperJson.getDouble("widthMm").toFloat(),
            paperJson.getDouble("heightMm").toFloat(),
            paperJson.getInt("rotationDegrees"),
            paperJson.getDouble("offsetXmm").toFloat(),
            paperJson.getDouble("offsetYmm").toFloat(),
        )
        val array = root.getJSONArray("items")
        require(array.length() <= LabelPrintQueue.MaxItems) { "Label print queue exceeds item limit" }
        val items = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LabelPrintItem(
                id = item.getString("id"),
                seed = if (version < 3 || !item.isNull("seed"))
                    decodeSeed(item.getJSONObject("seed")) else null,
                copyNumber = item.getInt("copyNumber"),
                copies = item.getInt("copies"),
                state = LabelPrintItemState.valueOf(item.getString("state")),
                detail = item.getString("detail"),
                design = if (item.isNull("design") || !item.has("design")) null
                    else decodeDesign(item.getJSONObject("design")),
                freeLabel = if (version < 3 || item.isNull("freeLabel")) null
                    else item.getJSONObject("freeLabel").let { free ->
                        FreeLabel(free.getString("title"),
                            FreeLabelTemplate.valueOf(free.getString("template")))
                    },
            ).also {
                require(it.id.isNotBlank()) { "Label print item has no ID" }
                require(it.copies in 1..LabelPrintQueue.MaxCopies && it.copyNumber in 1..it.copies) {
                    "Label print item has invalid copy number"
                }
            }
        }
        require(items.map(LabelPrintItem::id).toSet().size == items.size) {
            "Label print queue contains duplicate item IDs"
        }
        return LabelPrintQueue(
            items = items,
            templateId = root.getString("templateId"),
            textTemplateId = root.getString("textTemplateId"),
            paper = paper,
            printerProfileId = if (version < 4) LabelPrinterProfile.M1.id else root.getString("printerProfileId"),
            mediaType = if (version < 4) LabelMediaType.Gap.id else root.getString("mediaType"),
        )
    }

    fun encodeSeed(seed: ComponentLabelSeed): JSONObject = JSONObject()
        .put("sku", seed.sku)
        .put("name", seed.name)
        .put("category", seed.category)
        .put("packageName", seed.packageName)
        .put("location", seed.location)
        .put("quantity", seed.quantity)
        .put("minStock", seed.minStock)
        .put("model", seed.model ?: JSONObject.NULL)
        .put("brand", seed.brand ?: JSONObject.NULL)
        .put("sourceLabel", seed.sourceLabel ?: JSONObject.NULL)
        .put("rawPayload", seed.rawPayload ?: JSONObject.NULL)
        .put("notes", JSONArray(seed.notes))

    fun decodeSeed(json: JSONObject): ComponentLabelSeed {
        val notes = json.getJSONArray("notes")
        return ComponentLabelSeed(
            sku = json.getString("sku"),
            name = json.getString("name"),
            category = json.getString("category"),
            packageName = json.getString("packageName"),
            location = json.getString("location"),
            quantity = json.getInt("quantity"),
            minStock = json.getInt("minStock"),
            model = json.nullableString("model"),
            brand = json.nullableString("brand"),
            sourceLabel = json.nullableString("sourceLabel"),
            rawPayload = json.nullableString("rawPayload"),
            notes = (0 until notes.length()).map(notes::getString),
        )
    }

    private fun encodeDesign(design: LabelDesign): JSONObject = JSONObject().put("elements", JSONArray().apply {
        design.elements.forEach { element ->
            put(JSONObject().put("id", element.id).put("type", element.type.name)
                .put("text", element.text).put("xMm", element.xMm).put("yMm", element.yMm)
                .put("widthMm", element.widthMm).put("heightMm", element.heightMm))
        }
    })

    private fun decodeDesign(json: JSONObject): LabelDesign {
        val array = json.getJSONArray("elements")
        require(array.length() <= 16) { "标签元素过多。" }
        return LabelDesign((0 until array.length()).map { index ->
            val element = array.getJSONObject(index)
            LabelElement(
                id = element.getString("id"),
                type = LabelElementType.valueOf(element.getString("type")),
                text = element.getString("text"),
                xMm = element.getDouble("xMm").toFloat(),
                yMm = element.getDouble("yMm").toFloat(),
                widthMm = element.getDouble("widthMm").toFloat(),
                heightMm = element.getDouble("heightMm").toFloat(),
            )
        })
    }

    private fun JSONObject.nullableString(key: String): String? {
        require(has(key)) { "Label seed is missing $key" }
        return if (isNull(key)) null else getString(key)
    }
}
