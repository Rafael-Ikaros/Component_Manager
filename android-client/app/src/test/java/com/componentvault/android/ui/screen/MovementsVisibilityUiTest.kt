package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.componentvault.android.R
import com.componentvault.android.model.MovementsUiState
import com.componentvault.android.model.StockMovementRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MovementsVisibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val mode = InventoryLayoutMode(
        widthClass = InventoryWidthClass.Compact,
        supportsListDetail = false,
        formPresentation = InventoryFormPresentation.FullScreenRoute,
        secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
    )
    private val active = StockMovementRecord(
        id = "active-move", componentId = "active", componentSku = "C1",
        componentName = "Active part", movementType = "inbound", quantity = 1,
        reason = "Test", note = "", happenedAt = "2026-09-27T00:00:00Z",
        updatedAt = "2026-09-27T00:00:00Z", deleted = false,
    )
    private val deleted = active.copy(
        id = "deleted-move", componentId = "deleted", componentSku = "C2", componentName = "Deleted part",
    )

    @Test fun hidesDeletedComponentHistoryUntilExplicitlyIncludedAndMarksIt() {
        assertEquals(listOf(active), visibleMovementsForActiveComponents(
            listOf(active, deleted), setOf("active"), false))
        assertEquals(listOf(active, deleted), visibleMovementsForActiveComponents(
            listOf(active, deleted), setOf("active"), true))
        compose.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    MovementsContent(
                        contentPadding = PaddingValues(), uiState = MovementsUiState(
                            items = listOf(active, deleted), activeComponentIds = setOf("active"),
                            componentCount = 1,
                        ),
                        statusMessage = "", layoutMode = mode,
                        onSelectMovement = {}, onOpenMovementDetail = {},
                        onScanMovementLabel = {}, onRetryMovementScan = {},
                        onDismissMovementScanResult = {}, onDiscardMovementBatch = {},
                        onOpenMovementBatchReview = {},
                        onUpdateMovementBatchItemMovementType = { _, _ -> },
                        onUpdateMovementBatchItemQuantity = { _, _ -> },
                        onUpdateMovementBatchItemReason = { _, _ -> },
                        onUpdateMovementBatchItemNote = { _, _ -> },
                        onRemoveMovementBatchItem = {},
                        onSearchInventoryBySku = {}, onImportComponent = {}, onRecordMovement = {},
                    )
                }
            }
        }
        val list = compose.onNodeWithTag("movements_master_list")
        list.performScrollToNode(hasTestTag("movements_include_deleted_components"))
        val toggle = compose.onNodeWithTag("movements_include_deleted_components")
        toggle.assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.movements_include_deleted_components, 1))
            .assertIsDisplayed()
        toggle.performClick()
        toggle.assertIsSelected()
        list.performScrollToNode(hasText("Deleted part"))
        compose.onNodeWithText("Deleted part").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.movements_deleted_component)).assertIsDisplayed()
    }
}
