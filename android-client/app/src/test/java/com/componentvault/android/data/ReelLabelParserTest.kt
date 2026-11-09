package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelLabelParserTest {
    @Test
    fun joinsRohmSegmentsAndKeepsZeroOhm() {
        val label = requireNotNull(ReelLabelParser.parse(listOf(
            "MCRO3 EZP FX 000     0Ω", "F 5,000 pcs 1526 04398L", "ROHIM SEMICONDUCTOR",
        )))

        assertEquals("MCR03EZPFX000", label.automaticModel)
        assertEquals("ROHM", label.brand)
        assertEquals("0Ω", label.parameters["Resistance"])
        assertEquals(5000, label.nominalQuantity)
        assertNull(label.candidate(label.automaticModel, "Reel").suggestedQuantity)
    }

    @Test
    fun prefersYageoMpnOverShorthandAndLot() {
        val label = requireNotNull(ReelLabelParser.parse(listOf(
            "RC 0603 F R-07 33R", "07010050", "38B1252417", "CPN: RC0603FR-0733RL",
            "QTY: 5000 PCS", "MPN:RCO603FR-0733RL", "General 0603 19% Reel (7inch) 33R", "YAGEO",
        )))

        assertEquals(listOf("RC0603FR-0733RL"), label.models)
        assertEquals("0603", label.packageName)
        assertEquals("33R", label.parameters["Resistance"])
        assertNull(label.parameters["Tolerance"])
    }

    @Test
    fun offersBothSamsungCodesWithoutAssumingTheyAreInterchangeable() {
        val label = requireNotNull(ReelLabelParser.parse(listOf(
            "1608 A 10uF", "CL 10A106KPNC", "CL 10A106KP8NNNC", "AE4KOSM/0828 4,000pcs 0.80T", "SAMSUNG",
        )))

        assertEquals(listOf("CL10A106KP8NNNC", "CL10A106KPNC"), label.models)
        assertEquals("10uF", label.parameters["Capacitance"])
        assertEquals("0603", label.packageName)
        assertEquals(4000, label.nominalQuantity)
        assertNull(label.automaticModel)
        assertNull(label.parameters["Rated Voltage"])
    }

    @Test
    fun requiresSelectionForAnUnmarkedCodeAndDropsConflictingQuantities() {
        val label = requireNotNull(ReelLabelParser.parse(listOf("STM32F103C8T6", "QTY 50", "QTY 100")))

        assertEquals(listOf("STM32F103C8T6"), label.models)
        assertNull(label.automaticModel)
        assertNull(label.nominalQuantity)
    }

    @Test
    fun offersSizeCorrectionFromSamsungPhotoWithoutGuessingQuantity() {
        val label = requireNotNull(ReelLabelParser.parse(listOf(
            "1608 A 10uF", "CL 18A 1B6KPNC", "CL 18A 106KP8NNNC", "4.008pcs", "SAMSUNG",
        )))

        assertEquals(listOf("CL10A106KP8NNNC", "CL18A106KP8NNNC"), label.models)
        assertNull(label.automaticModel)
        assertEquals("0603", label.packageName)
        assertEquals(4008, label.nominalQuantity)
        assertNull(label.candidate(label.models.first(), "Reel").suggestedQuantity)
    }

    @Test
    fun exactMatchPreservesSuffixesAndMeaningfulPunctuation() {
        fun product(sku: String, model: String) = LcscDomesticProduct(
            metadata = ComponentOfficialMetadata(sku = sku, model = model),
            parameters = emptyMap(),
            datasheetUrl = null,
        )
        val exact = product("C123456", "rc0603 fr-0733rl")
        val similar = listOf(product("C123457", "RC0603FR-0733R"), product("C123458", "RC0603FR0733RL"))

        assertEquals(listOf(exact), ReelLabelParser.exactProducts("RC0603FR-0733RL", similar + exact))
    }
}
