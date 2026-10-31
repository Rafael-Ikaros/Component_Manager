package com.componentvault.android.ui.screen

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.componentvault.android.model.InventoryUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp")
class InventoryShellImeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fullShellReleasesBottomNavigationSpaceAboveImeAndRestoresItOnDismiss() {
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                MaterialTheme {
                    Box(Modifier.requiredSize(400.dp, 800.dp).testTag("shell_viewport")) {
                        ComponentVaultAppShellContent(
                            uiState = InventoryUiState(),
                            destination = InventoryDestination.Inventory,
                            layoutMode = InventoryLayoutMode(
                                widthClass = InventoryWidthClass.Compact,
                                supportsListDetail = false,
                                formPresentation = InventoryFormPresentation.FullScreenRoute,
                                secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
                            ),
                            onDestinationChange = { _ -> },
                            onQueryChange = { _ -> },
                            onStockFilterChange = { _ -> },
                            onCategoryChange = { _ -> },
                            onLocationChange = { _ -> },
                            onSortChange = { _ -> },
                            onSelectComponent = { _ -> },
                            onOpenComponentDetail = { _ -> },
                            onAddComponent = { },
                            onImportComponent = { },
                            onGenerateLabel = { _ -> },
                            onEditComponent = { _ -> },
                            onRequestDeleteComponent = { _ -> },
                            onScanMovementLabel = { },
                            onRetryMovementScan = { },
                            onDismissMovementScanResult = { },
                            onDiscardMovementBatch = { },
                            onOpenMovementBatchReview = { },
                            onUpdateMovementBatchItemMovementType = { _, _ -> },
                            onUpdateMovementBatchItemQuantity = { _, _ -> },
                            onUpdateMovementBatchItemReason = { _, _ -> },
                            onUpdateMovementBatchItemNote = { _, _ -> },
                            onRemoveMovementBatchItem = { _ -> },
                            onSearchInventoryBySku = { _ -> },
                            onRecordMovement = { _ -> },
                            onOpenLowStockInventory = { },
                            onSelectOverviewComponent = { _ -> },
                            onOpenMovements = { _ -> },
                            onSelectMovement = { _ -> },
                            onOpenMovementDetail = { _ -> },
                            onOpenSettingsHome = { },
                            onOpenSyncSettings = { },
                        )
                    }
                }
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        fun dispatchIme(visible: Boolean) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(composeView, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (20 * density).roundToInt()))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) (300 * density).roundToInt() else 0))
                    .setVisible(WindowInsetsCompat.Type.ime(), visible)
                    .build())
            }
            compose.waitForIdle()
        }
        dispatchIme(false)
        val closedBottom = compose.onNodeWithTag("inventory_list").getUnclippedBoundsInRoot().bottom
        dispatchIme(true)
        val viewport = compose.onNodeWithTag("shell_viewport").getUnclippedBoundsInRoot()
        val listBottom = compose.onNodeWithTag("inventory_list").getUnclippedBoundsInRoot().bottom
        // The scrolling viewport meets the IME edge without an outer page margin.
        val expectedBottom = viewport.bottom - 300.dp
        assertTrue(abs((listBottom - expectedBottom).value) <= 1f,
            "Inventory must end at the IME edge; expected $expectedBottom, got $listBottom")
        dispatchIme(false)
        val restoredBottom = compose.onNodeWithTag("inventory_list").getUnclippedBoundsInRoot().bottom
        assertTrue(abs((restoredBottom - closedBottom).value) <= 1f,
            "Dismissing IME must restore the navigation bar layout")
    }
}
