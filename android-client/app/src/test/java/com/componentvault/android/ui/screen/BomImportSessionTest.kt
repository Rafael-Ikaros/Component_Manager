package com.componentvault.android.ui.screen

import android.app.Application
import com.componentvault.android.data.InventoryDatabaseHelper
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomRequirement
import com.componentvault.android.data.bom.BomRequirementIdentity
import com.componentvault.android.data.bom.BomReleasePreview
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.data.bom.BomPreset
import com.componentvault.android.data.bom.BomPresetCodec
import com.componentvault.android.data.bom.BomSourceRow
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.ComponentDraft
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BomImportSessionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before fun setUp() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        repository = InventoryRepository(context)
    }

    @Test fun keptBomPreviewRefreshesOnceWithoutDroppingUserChoices() {
        val session = BomImportSession()
        val parsed = BomParseResult(
            projectName = "Board", productionSets = 5,
            availableSheets = listOf(BomSheet("BOM", 0)), selectedSheet = BomSheet("BOM", 0),
            requirements = emptyList(), fileSha256 = "test-file",
        )
        session.entryMode = BomImportMode.Bom
        session.selectedMode.value = BomImportMode.Bom
        session.preview.value = BomReleasePreview(parsed, emptyList())
        session.productionSets.value = "5"
        session.selections.value = mapOf("sku:R-1" to "component-1")
        session.excludedKeys.value = setOf("sku:C-1")
        session.retainOnExit = true

        assertTrue(session.consumeRetainedPreviewRefresh(BomImportMode.Bom))
        assertFalse(session.consumeRetainedPreviewRefresh(BomImportMode.Bom))
        assertEquals("5", session.productionSets.value)
        assertEquals(mapOf("sku:R-1" to "component-1"), session.selections.value)
        assertEquals(setOf("sku:C-1"), session.excludedKeys.value)
    }

    @Test fun clearedMatchStaysUnmatchedWhileAnotherAutomaticMatchRemains() = runBlocking {
        for (sku in listOf("R-1", "C-1")) {
            val saved = repository.saveComponent(ComponentDraft(
                sku = sku, name = sku, category = "Passive", packageName = "0603",
                location = "A", description = "", quantity = 8, minStock = 0,
            ))
            assertTrue(saved.isSuccess, saved.message)
        }
        val requirements = listOf("R-1", "C-1", "M-1").map { sku ->
            BomRequirement(
                identity = BomRequirementIdentity("sku:$sku"), sku = sku, name = sku,
                model = null, packageName = "0603", quantityPerSet = 1, requiredQuantity = 1,
                sourceRows = emptyList(),
            )
        }
        val parsed = BomParseResult(
            projectName = "Board", productionSets = 1,
            availableSheets = listOf(BomSheet("BOM", 0)), selectedSheet = BomSheet("BOM", 0),
            requirements = requirements, fileSha256 = "test-file",
        )
        val matched = repository.previewBomRelease(parsed)
        assertNotNull(matched.matchingLines.first { it.requirement.sku == "R-1" }.componentId)
        assertNotNull(matched.matchingLines.first { it.requirement.sku == "C-1" }.componentId)

        val cleared = repository.previewBomRelease(
            parsed,
            selections = clearBomSelections(emptyMap(), listOf(requirements.first().identity.canonicalKey)),
        )
        assertNull(cleared.matchingLines.first { it.requirement.sku == "R-1" }.componentId)
        assertTrue(cleared.matchingLines.first { it.requirement.sku == "C-1" }.componentId ==
            matched.matchingLines.first { it.requirement.sku == "C-1" }.componentId)
        val withoutExcluded = repository.previewBomRelease(
            parsed,
            selections = clearBomSelections(emptyMap(), listOf(requirements.first().identity.canonicalKey)),
            excludedKeys = setOf(requirements.last().identity.canonicalKey),
        )
        assertEquals(listOf("R-1"), bomProcurementEligible(withoutExcluded).map { it.sku })
    }

    @Test fun manualMaterialsSharePortablePresetAndKeepImportedSourcesAndSkippedChoices() {
        val source = BomSourceRow("BOM", 7, 2, mapOf("Designator" to "R1,R2", "Value" to "10k"))
        val imported = BomRequirement(BomRequirementIdentity("source:7"), "R-1", "Resistor", null, "0603", 2, 6, listOf(source))
        val sheet = BomSheet("BOM", 0)
        val preset = BomPreset("existing", "Board", BomParseResult("Board", 3, listOf(sheet), sheet,
            listOf(imported), "source-hash", "original.csv"), mapOf("source:7" to ""), setOf("source:7"))
        val component = ComponentRecord("component", "C-1", "Capacitor", "Passive", "0603", "A", "", 20, 0, "now", false)
        val draft = preset.addMaterial(component)
        val manualKey = draft.parsed.requirements.last().identity.canonicalKey
        val changed = draft.changeMaterialQuantity(manualKey, 4)
        val restored = BomPresetCodec.decode(BomPresetCodec.encode(listOf(changed))).single()
        assertEquals(imported, restored.parsed.requirements.first())
        assertEquals("original.csv", restored.parsed.sourceName)
        assertEquals("source-hash", restored.parsed.fileSha256)
        assertEquals(setOf("source:7"), restored.excludedKeys)
        assertEquals(mapOf("source:7" to "", manualKey to "component"), restored.selections)
        assertEquals(4, restored.parsed.requirements.last().quantityPerSet)
        assertEquals(12, restored.parsed.requirements.last().requiredQuantity)
        assertEquals(2, imported.sourceRows.single().quantityPerSet)
    }

    @Test fun manualMaterialPreviewDoesNotDeductStock() = runBlocking {
        val saved = repository.saveComponent(ComponentDraft(sku = "R-1", name = "Resistor", category = "Passive", packageName = "0603", location = "A", description = "", quantity = 8, minStock = 0))
        assertTrue(saved.isSuccess, saved.message)
        val original = repository.previewBomRelease(BomParseResult("Board", 1, listOf(BomSheet("BOM", 0)), BomSheet("BOM", 0),
            listOf(BomRequirement(BomRequirementIdentity("row"), "R-1", null, null, null, 2, 2, emptyList())), ""))
        val componentId = requireNotNull(original.matchingLines.single().componentId)
        val component = ComponentRecord(componentId, "R-1", "Resistor", "Passive", "0603", "A", "", 8, 0, "now", false)
        val draft = emptyProjectMaterials().addMaterial(component)
        val preview = repository.previewBomRelease(draft.parsed, draft.selections)
        assertEquals(8, preview.lines.single().availableQuantity)
        val again = repository.previewBomRelease(original.parsed)
        assertEquals(8, again.lines.single().availableQuantity)
    }
}
