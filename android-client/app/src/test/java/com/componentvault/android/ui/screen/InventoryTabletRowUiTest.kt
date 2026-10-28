package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.StorageLocationRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h720dp")
class InventoryTabletRowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactRowKeepsResolvedLocationAndExplicitSwipeActions() {
        var opens = 0
        var inbound = 0
        val item = InventoryListItemUiState(
            id = "tablet-part", name = "Long ceramic capacitor name for the compact tablet row",
            sku = "C-TABLET-0603-100NF-50V", category = "Capacitor", packageName = "0603",
            location = "drawer-id", quantity = 100, minStock = 5, isLowStock = false,
            updatedAt = "2026-10-28T00:00:00Z", specificationSummary = "100 nF · 50 V",
        )
        compose.setContent {
            ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                MaterialTheme {
                    Box(Modifier.width(420.dp)) {
                        InventoryListRow(
                            item = item, selected = true, compact = true,
                            onClick = { opens++ }, onInbound = { inbound++ },
                            onOutbound = {}, onTransfer = {}, onDelete = {},
                            storageLocations = listOf(StorageLocationRecord("drawer-id", "电容抽屉 A01", item.updatedAt)),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("电容抽屉 A01").assertIsDisplayed()
        compose.onNodeWithText("100").assertIsDisplayed()
        val row = compose.onNodeWithTag("inventory_tablet_row", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(row.bottom - row.top >= 48.dp)
        compose.onNodeWithTag("inventory_reveal_card").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, inbound); assertEquals(0, opens) }
        val action = compose.onNodeWithTag("inventory_quick_inbound").assertIsDisplayed()
        val bounds = action.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        action.performClick()
        compose.runOnIdle { assertEquals(1, inbound); assertEquals(0, opens) }
    }
}
