package com.componentvault.android.ui.screen

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import com.componentvault.android.R
import com.componentvault.android.data.InventoryDatabaseHelper
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportFieldOrigin
import com.componentvault.android.model.ComponentImportFieldOrigins
import com.componentvault.android.model.ComponentImportSourceType
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.StorageLocationSaveIntent
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InventoryDraftRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    private fun awaitModel(model: InventoryViewModel, condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            condition() || model.startupFailure != null
        }
        assertNull(model.startupFailure)
    }

    @Test
    fun importedEditorSeedAndLearningOriginSurviveSavedStateRoundTrip() {
        val draft = ComponentDraft(null, "C123", "Part", "IC", "SOT", "A", "notes", 3, 1)
        val candidate = ComponentImportCandidate(
            sourceType = ComponentImportSourceType.JlcQr,
            rawPayload = "raw", sourceLabel = "JLC", sku = "C123", name = "Part",
            notes = listOf("source note"),
            fieldOrigins = ComponentImportFieldOrigins(name = ComponentImportFieldOrigin.User),
        )
        val draftSaved = with(ComponentDraftSaver) { with(saverScope) { save(draft) } }!!
        val editDraft = draft.copy(id = "cmp-1", expectedUpdatedAt = "2026-11-13T00:00:00Z")
        val editSaved = with(ComponentDraftSaver) { with(saverScope) { save(editDraft) } }!!
        val candidateSaved = with(ComponentImportCandidateSaver) { with(saverScope) { save(candidate) } }!!
        assertEquals(draft, ComponentDraftSaver.restore(draftSaved))
        assertEquals(editDraft, ComponentDraftSaver.restore(editSaved))
        val oldEditSaved = org.json.JSONObject(editSaved).apply { remove("expectedUpdatedAt") }.toString()
        assertEquals(editDraft.copy(expectedUpdatedAt = null), ComponentDraftSaver.restore(oldEditSaved))
        assertEquals(candidate, ComponentImportCandidateSaver.restore(candidateSaved))
        assertNull(ComponentDraftSaver.restore(with(ComponentDraftSaver) { with(saverScope) { save(null) } }!!))
        assertNull(ComponentImportCandidateSaver.restore(with(ComponentImportCandidateSaver) { with(saverScope) { save(null) } }!!))
    }

    @Test
    fun restoredEditKeepsOpeningVersionAndRemainsAnEditWhenComponentDisappears() {
        val tester = StateRestorationTester(compose)
        val original = ComponentRecord(
            id = "edit-part", sku = "C123", name = "Original", category = "IC",
            packageName = "SOT-23", location = "A", description = "", quantity = 10,
            minStock = 0, updatedAt = "2026-11-13T00:00:00Z", deleted = false,
        )
        val current = mutableStateOf<ComponentRecord?>(original)
        val submitted = mutableListOf<ComponentDraft>()
        val compactMode = InventoryLayoutMode(
            widthClass = InventoryWidthClass.Compact,
            supportsListDetail = false,
            formPresentation = InventoryFormPresentation.FullScreenRoute,
            secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
        )
        tester.setContent {
            val openingVersion = rememberSaveable { original.updatedAt }
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    ComponentEditorRoute(
                        visible = true, scannerVisible = false, existing = current.value,
                        initialDraft = null, availableCategories = emptyList(), storageLocations = emptyList(),
                        layoutMode = compactMode,
                        onDismiss = {}, onSave = { draft, _ -> submitted.add(draft) },
                        onSaveAndGenerateLabel = { draft, _ -> submitted.add(draft) },
                        editingId = original.id, expectedUpdatedAt = openingVersion,
                    )
                }
            }
        }
        compose.onNodeWithText("Original").performTextReplacement("Renamed")
        compose.runOnIdle {
            current.value = original.copy(quantity = 8, updatedAt = "2026-11-13T00:01:00Z")
        }
        tester.emulateSavedInstanceStateRestore()
        compose.runOnIdle { current.value = null }
        compose.onNodeWithText("Renamed").assertTextContains("Renamed")
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.runOnIdle {
            val draft = submitted.single()
            assertEquals(original.id, draft.id)
            assertEquals(original.updatedAt, draft.expectedUpdatedAt)
            assertEquals(original.quantity, draft.quantity)
        }
    }

    @Test
    fun editedFormFieldSurvivesLayoutChangeAndStateRestoration() {
        val tester = StateRestorationTester(compose)
        val compactMode = InventoryLayoutMode(
            widthClass = InventoryWidthClass.Compact,
            supportsListDetail = false,
            formPresentation = InventoryFormPresentation.FullScreenRoute,
            secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
        )
        val dialogMode = InventoryLayoutMode(
            widthClass = InventoryWidthClass.Expanded,
            supportsListDetail = true,
            formPresentation = InventoryFormPresentation.Dialog,
            secondaryPanePresentation = InventorySecondaryPanePresentation.SplitPane,
        )
        val mode = mutableStateOf(compactMode)
        tester.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    ComponentEditorRoute(
                        visible = true,
                        scannerVisible = false,
                        existing = null,
                        initialDraft = ComponentDraft(null, "C123", "Seed", "IC", "SOT", "A", "", 1, 0),
                        availableCategories = emptyList(),
                        storageLocations = emptyList(),
                        layoutMode = mode.value,
                        onDismiss = {},
                        onSave = { _, _ -> },
                        onSaveAndGenerateLabel = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithText("Seed").performTextReplacement("Edited part")
        compose.runOnIdle { mode.value = dialogMode }
        compose.onNodeWithText("Edited part").assertTextContains("Edited part")
        compose.runOnIdle { mode.value = compactMode }
        compose.onNodeWithText("Edited part").assertTextContains("Edited part")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Edited part").assertTextContains("Edited part")
    }

    @Test
    fun createdImportLocationRefreshesParentInventory() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        val model = InventoryViewModel(context)
        compose.setContent { MaterialTheme { androidx.compose.material3.Text("host") } }
        awaitModel(model) { !model.uiState.isBusy && model.uiState.syncConfiguration.deviceId.isNotEmpty() }
        compose.runOnIdle {
            model.saveStorageLocation("RECOVER-A", "New shelf", StorageLocationSaveIntent.Create)
        }
        awaitModel(model) { model.uiState.inventory.storageLocations.any { it.id == "RECOVER-A" } }
        assertEquals("New shelf", model.uiState.inventory.storageLocations.single { it.id == "RECOVER-A" }.name)
    }

    @Test
    fun scanReviewRestoresWithoutWritingStockAndDiscardClearsSnapshot() {
        val helper = InventoryDatabaseHelper(context)
        try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        val repository = InventoryRepository(context)
        runBlocking {
            assertTrue(repository.saveStorageLocation("A", "Shelf A", StorageLocationSaveIntent.Create).isSuccess)
            assertTrue(repository.saveComponent(ComponentDraft(
                sku = "C70565", name = "Existing part", category = "IC", packageName = "SOT-23",
                location = "A", description = "", quantity = 10, minStock = 1,
            )).isSuccess)
        }
        val handle = SavedStateHandle()
        val model = InventoryViewModel(context, handle)
        compose.setContent { MaterialTheme { androidx.compose.material3.Text("host") } }
        awaitModel(model) { model.uiState.availableComponents.any { it.sku == "C70565" } }
        compose.runOnIdle {
            model.openMovementScanner()
            model.resolveMovementComponentFromLabel("cvl2|C70565|Existing%20part|IC|SOT-23|||10")
        }
        awaitModel(model) { model.uiState.movements.batchSession.queuedItems.size == 1 }
        compose.runOnIdle { model.updateMovementBatchItemQuantity(model.uiState.availableComponents.single().id, "2") }
        val snapshot = handle.get<String>("movement_batch_review_draft")!!
        val restoredHandle = SavedStateHandle(mapOf("movement_batch_review_draft" to snapshot))
        val restored = InventoryViewModel(context, restoredHandle)
        awaitModel(restored) { restored.uiState.availableComponents.isNotEmpty() }
        assertEquals("2", restored.uiState.movements.batchSession.queuedItems.single().quantityText)
        assertEquals(10, runBlocking { repository.loadComponents().single().quantity })
        compose.runOnIdle { restored.discardMovementBatchSession() }
        assertNull(restoredHandle.get<String>("movement_batch_review_draft"))

        val committedHandle = SavedStateHandle(mapOf("movement_batch_review_draft" to snapshot))
        val committed = InventoryViewModel(context, committedHandle)
        awaitModel(committed) { committed.uiState.availableComponents.isNotEmpty() }
        compose.runOnIdle { committed.commitMovementBatch() }
        awaitModel(committed) { committed.uiState.availableComponents.single().quantity == 12 }
        assertNull(committedHandle.get<String>("movement_batch_review_draft"))
        assertEquals(12, runBlocking { repository.loadComponents().single().quantity })
    }
}
