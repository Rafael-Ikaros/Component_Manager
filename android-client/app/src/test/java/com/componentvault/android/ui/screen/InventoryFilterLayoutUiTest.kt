package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w400dp-h800dp")
class InventoryFilterLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun narrowPaneWrapsEveryFilterWithinItsWidth() {
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
        for (label in listOf(R.string.filter_all, R.string.filter_low_stock,
            R.string.filter_category_label, R.string.filter_location_label, R.string.filter_sort_label)) {
            val node = compose.onNodeWithText(context.getString(label),
                substring = label != R.string.filter_all && label != R.string.filter_low_stock)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= pane.left && bounds.right <= pane.right,
                "Filter must remain inside narrow pane: ${context.getString(label)}")
        }
        compose.onNodeWithText(context.getString(R.string.filter_location_label), substring = true).performClick()
        compose.onNodeWithText(context.getString(R.string.filter_location_all)).assertIsDisplayed()
    }
}
