package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.componentvault.android.R
import com.componentvault.android.data.*
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.SyncConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InventoryNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun systemBackFromMainSettingsReturnsToInventory() {
        val model = InventoryViewModel(context)
        compose.setContent { MaterialTheme { ComponentVaultApp(model) } }
        compose.onNodeWithContentDescription(context.getString(R.string.destination_settings), useUnmergedTree = true).performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(context.getString(R.string.search_components_placeholder)).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w393dp-h850dp")
    fun compactSettingsSectionHidesBottomTabsAndArrowReturnsToSettings() {
        val model = InventoryViewModel(context)
        compose.setContent { MaterialTheme { ComponentVaultApp(model) } }
        val settingsTab = compose.onNodeWithContentDescription(
            context.getString(R.string.destination_settings), useUnmergedTree = true,
        )
        settingsTab.performClick()
        compose.onNodeWithTag("settings_group_Appearance").assertIsDisplayed().performClick()
        settingsTab.assertDoesNotExist()
        compose.onNodeWithTag("appearance_open_theme").performClick()
        compose.onNodeWithTag("appearance_mode_System").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        compose.onNodeWithTag("appearance_open_theme").assertIsDisplayed()
        settingsTab.assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings_group_Appearance").assertIsDisplayed()
        settingsTab.assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1200dp-h960dp")
    fun detailFollowsAnExplicitWideSelectionButDoesNotReopenAfterBack() {
        val ids = runBlocking {
            val repository = InventoryRepository(context)
            repository.saveStorageLocation("NAV", "Shelf", com.componentvault.android.model.StorageLocationSaveIntent.Create)
            (1..2).map { index ->
                repository.saveComponent(com.componentvault.android.model.ComponentDraft(
                    sku = "C$index", name = "Route part $index", category = "IC", packageName = "SOT-23",
                    location = "NAV", description = "", quantity = 3, minStock = 0,
                )).entityId!!
            }
        }
        val compact = InventoryLayoutMode(InventoryWidthClass.Compact, false,
            InventoryFormPresentation.FullScreenRoute, InventorySecondaryPanePresentation.FullScreenRoute)
        val expanded = InventoryLayoutMode(InventoryWidthClass.Expanded, true,
            InventoryFormPresentation.Dialog, InventorySecondaryPanePresentation.SplitPane)
        val mode = mutableStateOf(compact)
        val model = InventoryViewModel(context)
        compose.setContent { MaterialTheme { ComponentVaultApp(model, mode.value) } }
        compose.onNodeWithContentDescription(context.getString(R.string.destination_inventory), useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            model.uiState.inventory.isLoaded || model.startupFailure != null
        }
        kotlin.test.assertNull(model.startupFailure)
        compose.onNodeWithText("Route part 1").performClick()
        val detail = hasAnyAncestor(hasTestTag("inventory_detail_list"))
        compose.onAllNodes(hasText("Route part 1") and detail).onFirst().assertIsDisplayed()
        compose.runOnIdle { mode.value = expanded }
        compose.onNode(hasText("Route part 2") and hasClickAction() and hasAnyAncestor(hasTestTag("inventory_list"))).performClick()
        compose.runOnIdle { mode.value = compact }
        compose.onAllNodes(hasText("Route part 2") and detail).onFirst().assertIsDisplayed()
        compose.onNode(hasText("Route part 1") and detail).assertDoesNotExist()
        compose.runOnIdle {
            kotlin.test.assertEquals(ids[1], model.uiState.inventory.list.selectedComponentId)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("inventory_list").assertExists()
        compose.onNodeWithTag("inventory_detail_list").assertDoesNotExist()
    }

    @Test
    fun importMenuOffersBatchWithoutMaintenanceEntries() {
        compose.setContent {
            MaterialTheme {
                AddComponentEntrySheet({}, {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.import_menu_batch)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_locations_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.backup_title)).assertDoesNotExist()
    }

    @Test
    fun systemBackFromBackupReturnsToParentWithoutFinishingActivity() {
        val visible = mutableStateOf(true)
        val model = InventoryViewModel(context)
        compose.setContent {
            MaterialTheme {
                if (visible.value) InventoryBackupScreen(model) { visible.value = false }
                else Text("Settings parent")
            }
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Settings parent").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun systemBackFromLocationsReturnsToParentWithoutFinishingActivity() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) StorageLocationsScreen(emptyList(), { visible.value = false }, { _, _, _, _ -> }, { _, _ -> })
                else Text("Settings parent")
            }
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Settings parent").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun hundredPackageDraftCanScrollToLastEntryAndEditIt() {
        runBlocking {
            InventoryRepository(context).saveStorageLocation(
                "A", "A", com.componentvault.android.model.StorageLocationSaveIntent.Create,
            )
        }
        val rows = (1..100).map { index ->
            BatchJlcRow(
                id = UUID.nameUUIDFromBytes("package-$index".toByteArray()).toString(),
                raw = "package-$index", status = BatchJlcStatus.Ready,
                sku = "C$index", name = "Part $index", category = "IC", packageName = "SOT-23",
                quantityText = "1", location = "A",
            )
        }
        BatchJlcDraftStore(context).save(BatchJlcDraft(rows = rows))
        compose.setContent {
            MaterialTheme {
                BatchJlcInboundScreen(AppPreferences(), SyncConfiguration("", "", "", false, "", ""), "A", {}, {})
            }
        }
        val reviewTitle = context.getString(R.string.batch_tab_review, rows.size)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(reviewTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(reviewTitle).performClick()
        val editTag = "batch_edit_${rows.last().id}"
        compose.onNodeWithTag("batch_inbound_list").performScrollToNode(hasTestTag(editTag))
        compose.onNodeWithTag(editTag).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.field_package)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
    }
}
