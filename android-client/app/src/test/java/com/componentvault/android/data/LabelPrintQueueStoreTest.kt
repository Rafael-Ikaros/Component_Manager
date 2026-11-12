package com.componentvault.android.data

import com.componentvault.android.model.ComponentLabelSeed
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class LabelPrintQueueStoreTest {
    private val seed = ComponentLabelSeed(
        sku = "C123", name = "Resistor", category = "Passive", packageName = "0603",
        location = "Drawer 2", quantity = 120, minStock = 10,
        model = "RC0603", brand = "Acme", sourceLabel = "supplier",
        rawPayload = "{raw:payload}", notes = listOf("batch 1", "fragile"),
    )

    @Test
    fun hmProfileRoundTripsAndLegacyQueueRemainsM1() {
        val hm = LabelPrintQueue(paper = M1TestPaperProfile(72f, 300f),
            printerProfileId = LabelPrinterProfile.HmA300eCpcl.id, mediaType = LabelMediaType.Continuous.id)
        assertEquals(hm, LabelPrintQueueCodec.decode(LabelPrintQueueCodec.encode(hm)))
        val legacy = JSONObject(LabelPrintQueueCodec.encode(LabelPrintQueue())).put("version", 3)
        legacy.remove("printerProfileId")
        legacy.remove("mediaType")
        assertEquals("m1", LabelPrintQueueCodec.decode(legacy.toString()).printerProfileId)
        assertFailsWith<IllegalArgumentException> { LabelPrintQueue(paper = M1TestPaperProfile(72f, 40f)) }
        assertFailsWith<IllegalArgumentException> { LabelPrintQueue(printerProfileId = "unknown") }
        assertFailsWith<IllegalArgumentException> { LabelPrintQueue(mediaType = "black_mark") }
    }

    @Test
    fun jsonRoundTripPreservesFullSnapshotAndPaper() {
        val queue = LabelPrintQueue.create(
            listOf(seed to 2), templateId = ComponentLabelTemplate.Qr10x40.id,
            textTemplateId = ComponentTextLabelTemplate.NameModel.id,
            paper = M1TestPaperProfile(25f, 40f, 270, -1.5f, 2.25f),
        )
        val updated = queue.update(queue.items.first().id, LabelPrintItemState.Sent, "Printed")
        assertEquals(updated, LabelPrintQueueCodec.decode(LabelPrintQueueCodec.encode(updated)))
        assertEquals(seed, LabelPrintQueueCodec.decode(LabelPrintQueueCodec.encode(updated)).items.first().seed)
    }

    @Test
    fun loadPersistsRecoveryAndPreservesCompletedStates() {
        val context = RuntimeEnvironment.getApplication()
        val store = LabelPrintQueueStore(context)
        store.clear()
        try {
            val queue = LabelPrintQueue.create(listOf(seed to 4))
            val items = queue.items.mapIndexed { index, item ->
                item.copy(state = listOf(
                    LabelPrintItemState.Sending, LabelPrintItemState.Sent,
                    LabelPrintItemState.Skipped, LabelPrintItemState.Uncertain,
                )[index])
            }
            store.save(queue.copy(items = items))
            val loaded = store.load()
            assertEquals(
                listOf(LabelPrintItemState.Uncertain, LabelPrintItemState.Sent,
                    LabelPrintItemState.Skipped, LabelPrintItemState.Uncertain),
                loaded.items.map { it.state },
            )
            assertEquals(loaded, LabelPrintQueueCodec.decode(
                context.filesDir.resolve("label-print-queue.json").readText(),
            ))
            store.clear()
            assertEquals(LabelPrintQueue(), store.load())
        } finally {
            store.clear()
        }
    }

    @Test
    fun corruptQueueIsDiagnosedRatherThanDiscarded() {
        val context = RuntimeEnvironment.getApplication()
        val store = LabelPrintQueueStore(context)
        store.clear()
        try {
            context.filesDir.resolve("label-print-queue.json").writeText("{broken")
            val error = assertFailsWith<IllegalStateException> { store.load() }
            assertTrue(error.message.orEmpty().contains("clear it explicitly"))
        } finally {
            store.clear()
        }
    }

    @Test
    fun editedDesignIsSnapshottedForEveryCopyAndLegacyQueueStillLoads() {
        val paper = M1TestPaperProfile(40f, 60f)
        val design = LabelDesign.default(seed, ComponentLabelTemplate.Qr30x40,
            ComponentTextLabelTemplate.default, paper).withText("name", "Custom")
        val queue = LabelPrintQueue.create(listOf(seed to 2), paper = paper,
            designs = mapOf(seed.sku to design))
        val restored = LabelPrintQueueCodec.decode(LabelPrintQueueCodec.encode(queue))
        assertEquals(listOf(design, design), restored.items.map { it.design })

        val legacy = JSONObject(LabelPrintQueueCodec.encode(queue)).put("version", 1)
        val items = legacy.getJSONArray("items")
        for (index in 0 until items.length()) items.getJSONObject(index).remove("design")
        assertEquals(listOf(null, null), LabelPrintQueueCodec.decode(legacy.toString()).items.map { it.design })
    }

    @Test
    fun textFontsAndSizesRoundTripAndOlderDesignsUseDefaults() {
        val paper = M1TestPaperProfile(40f, 60f)
        val original = LabelDesign.default(seed, ComponentLabelTemplate.Qr30x40,
            ComponentTextLabelTemplate.default, paper)
        val design = original.withFont("name", LabelFont.Bold).withFont("sku", LabelFont.Monospace)
            .withFontSize("name", 12.5f).withFontSize("details", 9f)
        assertEquals(design, design.withFont("qr", LabelFont.Serif))
        assertEquals(design, design.withFontSize("qr", 14f))
        assertEquals(original, original.withFontSize("name", 12.5f).withFontSize("name", null))
        assertFailsWith<IllegalArgumentException> { original.withFontSize("name", Float.POSITIVE_INFINITY) }
        val queue = LabelPrintQueue.create(listOf(seed to 1), paper = paper, designs = mapOf(seed.sku to design))
        val encoded = JSONObject(LabelPrintQueueCodec.encode(queue))
        assertEquals(queue, LabelPrintQueueCodec.decode(encoded.toString()))

        val elements = encoded.getJSONArray("items").getJSONObject(0).getJSONObject("design").getJSONArray("elements")
        val byId = (0 until elements.length()).map(elements::getJSONObject).associateBy { it.getString("id") }
        assertEquals("bold", byId.getValue("name").getString("font"))
        assertEquals(12.5, byId.getValue("name").getDouble("fontSizePt"))
        assertTrue(!byId.getValue("sku").has("fontSizePt"))
        byId.getValue("name").remove("font")
        byId.getValue("name").remove("fontSizePt")
        byId.getValue("sku").put("font", "future-font")
        byId.getValue("details").put("fontSizePt", -1)
        byId.getValue("qr").put("font", "serif").put("fontSizePt", 14)
        assertEquals(original, LabelPrintQueueCodec.decode(encoded.toString()).items.single().design)
    }

    @Test
    fun freeLabelRoundTripsWithoutInventingAnInventorySeed() {
        val paper = M1TestPaperProfile(40f, 60f)
        val design = LabelDesign.free(FreeLabelTemplate.Qr, paper).withQrPayload("qr", "https://example.test/part")
        val queue = LabelPrintQueue.createFree(FreeLabel("Shelf note", FreeLabelTemplate.Qr),
            copies = 2, paper = paper, design = design)
        val restored = LabelPrintQueueCodec.decode(LabelPrintQueueCodec.encode(queue))
        assertEquals(queue, restored)
        assertTrue(restored.items.all { it.seed == null && it.freeLabel?.title == "Shelf note" })

        val legacyV2 = JSONObject(LabelPrintQueueCodec.encode(LabelPrintQueue.create(listOf(seed to 1))))
            .put("version", 2)
        legacyV2.getJSONArray("items").getJSONObject(0).remove("freeLabel")
        assertEquals(seed, LabelPrintQueueCodec.decode(legacyV2.toString()).items.single().seed)
    }
}
