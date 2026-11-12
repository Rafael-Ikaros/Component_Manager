package com.componentvault.android.data

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LabelPaperPresetStoreTest {
    private val preferences get() = RuntimeEnvironment.getApplication().getSharedPreferences("paper-test", Context.MODE_PRIVATE)

    @Test fun namedPresetsAndLastRotationSurviveReopeningWithoutAPrintQueue() {
        val store = LabelPaperPresetStore(preferences)
        val paper = M1TestPaperProfile(100f, 150f, 270, 1.5f, -2f)
        store.save("大标签", paper)
        store.save("小标签", M1TestPaperProfile(30f, 20f, 90))
        store.saveLast(paper)
        val reopened = LabelPaperPresetStore(preferences)
        assertEquals(paper, reopened.last())
        assertEquals(listOf("大标签", "小标签"), reopened.load().map { it.name })
        reopened.save("大标签", paper.copy(rotationDegrees = 180))
        assertEquals(2, reopened.load().size)
        assertEquals(180, reopened.load().first().paper.rotationDegrees)
        reopened.delete("小标签")
        assertEquals(listOf("大标签"), LabelPaperPresetStore(preferences).load().map { it.name })
    }

    @Test fun limitsAllowUpdatingAndRejectOverflowAndBlankNames() {
        val store = LabelPaperPresetStore(preferences)
        val paper = M1TestPaperProfile(40f, 60f)
        repeat(LabelPaperPresetStore.MaxPresets) { store.save("纸张$it", paper) }
        assertFailsWith<IllegalArgumentException> { store.save("额外纸张", paper) }
        assertFailsWith<IllegalArgumentException> { store.save("  ", paper) }
        store.save("纸张0", paper.copy(rotationDegrees = 90))
        assertEquals(90, store.load().first().paper.rotationDegrees)
    }

    @Test fun corruptSettingsFallBackWithoutLosingUsablePresets() {
        preferences.edit().putString("last_paper", "broken").putString("paper_presets", "[{},false]").commit()
        val store = LabelPaperPresetStore(preferences)
        assertNull(store.last())
        assertEquals(emptyList(), store.load())
    }

    @Test fun expandedCanvasDoesNotBypassPhysicalPrinterLimits() {
        val paper = M1TestPaperProfile(100f, 150f, 90)
        LabelPrinterProfile.entries.forEach { profile ->
            assertFailsWith<IllegalArgumentException> { LabelPrintQueue(paper = paper, printerProfileId = profile.id) }
        }
        LabelPrintQueue(paper = M1TestPaperProfile(48f, 300f), printerProfileId = LabelPrinterProfile.DetongerP1.id)
        assertFailsWith<IllegalArgumentException> { LabelPrinterProfile.DetongerP1.validate(M1TestPaperProfile(48.1f, 60f)) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(Float.NaN, 60f) }
        assertFailsWith<IllegalArgumentException> { M1TestPaperProfile(40f, Float.POSITIVE_INFINITY) }
    }

    @Test fun earlierP1PresetsMigrateOnceWithRotationAndOffsets() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = context.getSharedPreferences("label_paper_settings", Context.MODE_PRIVATE)
        legacy.edit().putString("paper_presets", """[{"name":"P1", "width":40,"height":30,"rotation":90,"offsetX":2,"offsetY":-1}]""")
            .putString("last_paper", """{"width":40,"height":30,"rotation":90,"offsetX":2,"offsetY":-1}""").commit()
        val upstream = context.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE)
        LabelPaperPresetStore(upstream).save(listOf(
            SavedLabelPaper("", 30f, 20f, LabelMediaType.Gap),
            SavedLabelPaper("", 40f, 30f, LabelMediaType.Gap),
        ))
        val store = LabelPaperPresetStore(context)
        val expected = M1TestPaperProfile(40f, 30f, 90, 2f, -1f)
        assertEquals(expected, store.last())
        assertEquals(expected, store.load().single { it.name == "P1" }.paper)
        assertEquals(3, store.load().size)
        store.delete("P1")
        assertEquals(listOf(30f, 40f), LabelPaperPresetStore(context).load().map { it.widthMm })
    }

    @Test fun upstreamSizeOnlyPresetsStillLoadAndSameSizeCanHaveDifferentAlignment() {
        preferences.edit().putString("saved_label_papers",
            """[{"name":"old", "width_mm":40,"height_mm":30,"media":"gap"}]""").commit()
        val store = LabelPaperPresetStore(preferences)
        assertEquals(M1TestPaperProfile(40f, 30f), store.load().single().paper)
        store.save("rotated", M1TestPaperProfile(40f, 30f, 90, 2f, -1f))
        assertEquals(2, store.load().size)
        assertEquals(90, store.load().last().rotationDegrees)
        assertEquals(false, LabelPrinterProfile.DetongerP1.usesCpcl)
        assertEquals(true, LabelPrinterProfile.DetongerP1.supportsContinuous)
        assertEquals(LabelPrinterProfile.DetongerP1, LabelPrinterProfile.DetongerP1.configurable)
    }
}
