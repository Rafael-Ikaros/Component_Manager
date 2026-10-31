package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.InventoryScreenUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h800dp")
class InventoryFilterLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun narrowPaneKeepsQuickFiltersInOneRowAndOpensAdvancedPanel() {
        val context: Application = RuntimeEnvironment.getApplication()
        compose.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    Box(Modifier.requiredSize(280.dp, 600.dp).testTag("filter_pane")) {
                        InventoryFilterHeader(
                            uiState = InventoryScreenUiState(), statusMessage = "",
                            onQueryChange = {}, onStockFilterChange = {}, onCategoryChange = {},
                            onLocationChange = {}, onSortChange = {},
                        )
                    }
                }
            }
        }
        val pane = compose.onNodeWithTag("filter_pane").getUnclippedBoundsInRoot()
        val quickFilters = listOf(R.string.filter_all, R.string.filter_low_stock, R.string.inventory_filter_button)
            .map { compose.onNodeWithText(context.getString(it)).assertIsDisplayed().getUnclippedBoundsInRoot() }
        quickFilters.forEach { bounds ->
            assertTrue(bounds.left >= pane.left && bounds.right <= pane.right)
            assertTrue(kotlin.math.abs((bounds.top + bounds.bottom - quickFilters.first().top - quickFilters.first().bottom).value) <= 2f, "Quick filters must occupy one row")
        }
        for (label in listOf(R.string.filter_category_label, R.string.filter_location_label, R.string.filter_sort_label)) {
            val button = compose.onNode(hasText(context.getString(label), substring = true)
                and hasAnyAncestor(hasTestTag("inventory_filter_bar")))
            button.performScrollTo().assertIsDisplayed()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue(kotlin.math.abs((bounds.top + bounds.bottom - quickFilters.first().top - quickFilters.first().bottom).value) <= 2f,
                "Advanced selectors must remain on the same horizontal row")
        }
        compose.onNodeWithTag("inventory_filter_panel_button").performScrollTo().performClick()
        for (label in listOf(R.string.filter_category_label, R.string.filter_location_label, R.string.filter_sort_label)) {
            compose.onNode(hasText(context.getString(label), substring = true)
                and hasAnyAncestor(hasTestTag("inventory_filter_panel"))).assertIsDisplayed()
        }

    }
    @Test
    fun panelEditsAreDraftsUntilAppliedAndResetClearsAdvancedFiltersOnly() {
        val context: Application = RuntimeEnvironment.getApplication()
        val state = mutableStateOf(InventoryScreenUiState(availableCategories = listOf("Resistor")))
        compose.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    InventoryFilterHeader(
                        uiState = state.value, statusMessage = "",
                        onQueryChange = {}, onStockFilterChange = {},
                        onCategoryChange = { state.value = state.value.copy(filters = state.value.filters.copy(category = it)) },
                        onLocationChange = { state.value = state.value.copy(filters = state.value.filters.copy(location = it)) },
                        onSortChange = { state.value = state.value.copy(filters = state.value.filters.copy(sort = it)) },
                    )
                }
            }
        }
        fun openAndSelectCategory() {
            compose.onNodeWithTag("inventory_filter_panel_button").performClick()
            compose.onNode(hasText(context.getString(R.string.filter_category_label), substring = true)
                and hasAnyAncestor(hasTestTag("inventory_filter_panel"))).performClick()
            compose.onNodeWithText("Resistor").performClick()
            compose.runOnIdle { assertEquals(null, state.value.filters.category) }
        }
        openAndSelectCategory()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(null, state.value.filters.category) }
        openAndSelectCategory()
        compose.onNodeWithText(context.getString(R.string.inventory_filter_apply)).performClick()
        compose.runOnIdle { assertEquals("Resistor", state.value.filters.category) }
        compose.onNodeWithTag("inventory_filter_panel_button").assertIsSelected().performClick()
        compose.onNodeWithText(context.getString(R.string.inventory_filter_reset)).performClick()
        compose.runOnIdle { assertEquals("Resistor", state.value.filters.category) }
        compose.onNodeWithText(context.getString(R.string.inventory_filter_apply)).performClick()
        compose.runOnIdle { assertEquals(null, state.value.filters.category) }
        compose.onNodeWithTag("inventory_filter_panel_button").assertIsNotSelected()
        compose.onNode(hasText(context.getString(R.string.filter_category_label), substring = true)
            and hasAnyAncestor(hasTestTag("inventory_filter_bar"))).performScrollTo().performClick()
        compose.onNodeWithText("Resistor").performClick()
        compose.runOnIdle { assertEquals("Resistor", state.value.filters.category) }
        compose.onNodeWithTag("inventory_filter_panel_button").performScrollTo().assertIsSelected().performClick()
        compose.onNode(hasText("Resistor", substring = true)
            and hasAnyAncestor(hasTestTag("inventory_filter_panel"))).assertIsDisplayed()

    }

}
