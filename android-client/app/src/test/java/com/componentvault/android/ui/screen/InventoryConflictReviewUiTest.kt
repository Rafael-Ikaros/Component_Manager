package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.componentvault.android.R
import com.componentvault.android.model.InventoryConflictComponent
import com.componentvault.android.model.InventoryConflictLocation
import com.componentvault.android.model.InventoryConflictMovement
import com.componentvault.android.model.InventoryConflictMovementEdit
import com.componentvault.android.model.InventoryConflictReview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class InventoryConflictReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outboundRequiresAvailableStockAndExplicitConfirmation() {
        val component = InventoryConflictComponent(
            id = "cmp-one", sku = "C123", name = "Test part", serverQuantity = 2,
            locations = listOf(
                InventoryConflictLocation("empty", "Empty bin", 0),
                InventoryConflictLocation("spare", "Spare bin", 2),
            ),
            movements = listOf(InventoryConflictMovement("mov-one", "outbound", 3, "empty", null, "Test issue")),
            blockedReason = null, serverVersion = "server", localVersion = "local",
        )
        val submitted = mutableListOf<List<InventoryConflictMovementEdit>>()
        val resources = compose.activity.resources
        val confirm = resources.getString(R.string.sync_conflict_confirm_action)
        val dialogText = resources.getString(R.string.sync_conflict_confirm_description, "C123", 1)
        val quantity = resources.getString(R.string.sync_conflict_quantity)
        val action = hasText(confirm)
        val list = hasScrollAction()
        compose.setContent {
            MaterialTheme {
                InventoryConflictReviewContent(
                    state = InventoryConflictReviewUiState(review = InventoryConflictReview(listOf(component))),
                    onDismiss = {},
                    onRefresh = {},
                    onReplay = { _, edits -> submitted.add(edits) },
                )
            }
        }
        compose.onNodeWithText(resources.getString(R.string.sync_conflict_view_component)).performClick()
        compose.onNode(list).performScrollToNode(action)
        compose.onNode(action).assertIsNotEnabled()
        compose.onNode(list).performScrollToNode(hasText(quantity))
        compose.onNodeWithText(quantity).performTextReplacement("2")
        compose.onNodeWithText(resources.getString(R.string.movement_location_with_stock, "Empty bin", 0)).performClick()
        compose.onNode(hasText(resources.getString(R.string.movement_location_with_stock, "Spare bin", 2)) and hasClickAction())
            .performClick()
        compose.onNode(list).performScrollToNode(action)
        compose.onNode(action).performClick()
        compose.onNodeWithText(dialogText).assertExists()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        compose.onNodeWithText(resources.getString(R.string.sync_conflict_confirm_commit)).performClick()
        compose.runOnIdle { assertEquals(listOf(InventoryConflictMovementEdit("mov-one", 2, "spare")), submitted.single()) }
    }
}
