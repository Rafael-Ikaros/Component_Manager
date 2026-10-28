package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.InventoryListUiState
import com.componentvault.android.model.InventoryScreenUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h720dp")
class InventoryShortViewportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shortPaneCanScrollPastFiltersToFullRowAndBackToSearchWithoutWritingStock() {
        val first = InventoryListItemUiState(
            id = "short-first", name = "First capacitor", sku = "C-100", category = "Capacitor",
            packageName = "0603", location = "A01", quantity = 100, minStock = 5,
            isLowStock = false, updatedAt = "2026-10-28T00:00:00Z",
        )
        val state = mutableStateOf(InventoryScreenUiState(
            totalComponentCount = 3,
            availableCategories = listOf("Capacitor", "Resistor"),
            availableLocations = listOf("A01", "B01"),
            list = InventoryListUiState(items = listOf(
                first, first.copy(id = "short-second", name = "Second capacitor"),
                first.copy(id = "short-third", name = "Third capacitor"),
            )),
        ))
        var openedId: String? = null
        var writes = 0
        compose.setContent {
            ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(280.dp)) {
                        InventoryContent(
                            contentPadding = PaddingValues(), uiState = state.value, statusMessage = "",
                            layoutMode = InventoryLayoutMode(
                                widthClass = InventoryWidthClass.Medium, supportsListDetail = false,
                                formPresentation = InventoryFormPresentation.FullScreenRoute,
                                secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
                            ),
                            onQueryChange = { query -> state.value = state.value.copy(filters = state.value.filters.copy(query = query)) },
                            onStockFilterChange = {}, onCategoryChange = {}, onLocationChange = {},
                            onSortChange = {}, onSelectComponent = {},
                            onOpenComponentDetail = { openedId = it }, onImportComponent = {},
                            onGenerateLabel = {}, onEditComponent = {},
                            onRequestDeleteComponent = { writes++ }, onRecordMovement = { writes++ },
                            onBatchTransfer = { _, _, _, _ -> writes++ },
                            onQuickMovement = { _, _ -> writes++ },
                        )
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("inventory_list")
        list.performScrollToIndex(1)
        val firstRow = compose.onNode(hasText(first.name) and hasClickAction()).assertIsDisplayed()
        val viewport = list.getUnclippedBoundsInRoot()
        val bounds = firstRow.getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
            "The complete first row must fit inside the short scrolling pane")
        firstRow.performClick()
        compose.runOnIdle { assertEquals(first.id, openedId); assertEquals(0, writes) }

        list.performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput("capacitor")
        compose.runOnIdle {
            assertEquals("capacitor", state.value.filters.query)
            assertEquals(100, state.value.list.items.first().quantity)
            assertEquals(0, writes)
        }
    }
}
