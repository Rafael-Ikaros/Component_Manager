package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelCatalogReviewTest {
    @Test
    fun equivalentUnitsAndLocalizedNamesAgree() {
        val review = reviewReelCatalog(
            label("Brand: SAMSUNG", "MPN: CL10A106KP8NNNC", "0603 10µF 10V ±10%"),
            ComponentOfficialMetadata(
                brand = "SAMSUNG(三星)",
                packageName = "0603(1608 Metric)",
                parameters = mapOf("容量(nF)" to "10000", "额定电压" to "10Vdc", "Capacitance Tolerance" to "10%"),
            ),
        )
        assertEquals(5, review.matchedFields)
        assertEquals(0, review.unverifiedFields)
        assertTrue(review.hasComparableElectricalParameter)
        assertTrue(review.conflicts.isEmpty())
    }

    @Test
    fun exactModelWithDisagreeingLabelReportsEachField() {
        val model = "RC0603FR-0733RL"
        val review = reviewReelCatalog(
            label("MPN: $model", "Brand: ROHM", "0805 330R ±5%"),
            ComponentOfficialMetadata(
                model = model, brand = "YAGEO(国巨)", packageName = "0603",
                parameters = mapOf("Resistance" to "33Ω", "Tolerance" to "±1%"),
            ),
        )
        assertEquals(
            listOf(ReelCatalogField.Brand, ReelCatalogField.Package, ReelCatalogField.Resistance, ReelCatalogField.Tolerance),
            review.conflicts.map { it.field },
        )
        val resistance = review.conflicts.first { it.field == ReelCatalogField.Resistance }
        assertEquals("330R", resistance.label)
        assertEquals("33Ω", resistance.catalog)
    }

    @Test
    fun resistorShorthandAndZeroAreComparedNumerically() {
        val reference = ComponentOfficialMetadata(parameters = mapOf("Resistance" to "33Ω"))
        assertEquals(1, reviewReelCatalog(label("MPN: R12345", "33R"), reference).matchedFields)
        val zero = reference.copy(parameters = mapOf("Resistance" to "0mΩ"))
        assertEquals(1, reviewReelCatalog(label("MPN: R12345", "0Ω"), zero).matchedFields)
    }

    @Test
    fun genericCatalogValueRequiresAnUnambiguousUnit() {
        val reel = label("MPN: RC0603FR-0733RL", "33R")
        val value = mapOf("Value" to "33Ω", "Tolerance" to "±1%")
        assertEquals(1, reviewReelCatalog(reel, ComponentOfficialMetadata(parameters = value)).matchedFields)
        assertEquals(mapOf("Resistance" to "33Ω", "Tolerance" to "±1%"), reelCatalogParameters(value))
        assertEquals(mapOf("Capacitance" to "10000nF"), reelCatalogParameters(mapOf("Value(nF)" to "10000")))
        val withoutUnit = ComponentOfficialMetadata(packageName = "0603", parameters = mapOf("Value" to "33"))
        val unverified = reviewReelCatalog(reel, withoutUnit)
        assertEquals(1, unverified.unverifiedFields)
        assertFalse(unverified.hasComparableElectricalParameter)
    }

    @Test
    fun missingAndUnparseableFieldsAreUnverified() {
        val review = reviewReelCatalog(
            label("MPN: CL10A106KP8NNNC", "Brand: SAMSUNG", "0603 10µF 10V"),
            ComponentOfficialMetadata(brand = "三星", packageName = "SMD", parameters = mapOf("Rated Voltage" to "see datasheet")),
        )
        assertEquals(1, review.matchedFields)
        assertEquals(3, review.unverifiedFields)
        assertFalse(review.hasComparableElectricalParameter)
        assertTrue(review.conflicts.isEmpty())
    }

    @Test
    fun conflictingAliasesInCatalogDoNotVerifyTheField() {
        val review = reviewReelCatalog(
            label("MPN: R12345", "33R"),
            ComponentOfficialMetadata(parameters = mapOf("阻值" to "33Ω", "Resistance" to "330Ω")),
        )
        assertEquals(0, review.matchedFields)
        assertEquals(1, review.unverifiedFields)
        assertTrue(review.conflicts.isEmpty())
    }

    @Test
    fun explicitAcAndDcAreDifferentRatings() {
        val review = reviewReelCatalog(
            label("MPN: CL10A106KP8NNNC", "10VAC"),
            ComponentOfficialMetadata(parameters = mapOf("Voltage - Rated" to "10VDC")),
        )
        assertEquals(listOf(ReelCatalogField.Voltage), review.conflicts.map { it.field })
    }

    private fun label(vararg lines: String) = requireNotNull(ReelLabelParser.parse(lines.toList()))
}
