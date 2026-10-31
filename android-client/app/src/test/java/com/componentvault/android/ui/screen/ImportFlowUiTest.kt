package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.componentvault.android.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImportFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun chooserPresentsFourDistinctWorkflowsAndSecondaryManualEntry() {
        var selected = ""
        compose.setContent {
            MaterialTheme {
                AddComponentEntrySheet(
                    onDismiss = {},
                    onImportComponent = { selected = "single" },
                    onAddComponent = { selected = "manual" },
                    onImportBom = { selected = "bom" },
                    onImportMigration = { selected = "migration" },
                    onBatchJlc = { selected = "batch" },
                )
            }
        }

        val expand = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        if (compose.onAllNodes(expand).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(expand).performSemanticsAction(SemanticsActions.Expand) { it() }
        }
        val workflows = listOf(
            R.string.import_menu_single,
            R.string.import_menu_batch,
            R.string.import_menu_bom,
            R.string.import_menu_migration,
        )
        workflows.forEach { labelRes ->
            val label = context.getString(labelRes)
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.import_menu_migration)).performClick()
        assertEquals("migration", selected)
        val manualLabel = context.getString(R.string.import_menu_manual)
        compose.onNodeWithText(manualLabel).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chooserBackReturnsToInventoryHost() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                androidx.compose.material3.Text("Inventory host")
                if (visible.value) {
                    AddComponentEntrySheet({ visible.value = false }, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Inventory host").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Inventory host").assertIsDisplayed()
    }

    @Test
    fun projectMaterialsOffersThreeSourcesBeforeShowingConfiguration() {
        compose.setContent {
            MaterialTheme {
                BomImportScreen(InventoryViewModel(context), {}, BomImportMode.Bom)
            }
        }

        compose.onNodeWithText(context.getString(R.string.materials_import)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.materials_saved)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.materials_manual)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bom_project_name)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.bom_match_inventory)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.materials_manual)).performClick()
        compose.onNodeWithText(context.getString(R.string.bom_project_name)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bom_match_inventory)).assertIsDisplayed()
    }

    @Test
    fun migrationWorkflowDoesNotExposeBomConfiguration() {
        compose.setContent {
            MaterialTheme {
                BomImportScreen(InventoryViewModel(context), {}, BomImportMode.Migration)
            }
        }

        compose.onNodeWithText(context.getString(R.string.bom_task_migration)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bom_choose_file)).assertIsDisplayed()
        compose.onNodeWithText("生产套数").assertDoesNotExist()
    }
}
