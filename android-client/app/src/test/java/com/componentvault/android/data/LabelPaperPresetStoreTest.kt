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
}
