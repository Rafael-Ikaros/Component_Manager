package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.InventoryDetailUiState
import com.componentvault.android.model.InventoryListItemUiState
import com.componentvault.android.model.InventoryListUiState
import com.componentvault.android.model.InventoryScreenUiState
import com.componentvault.android.model.ReferencePriceNotes
import com.componentvault.android.model.ReferencePriceSnapshot
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.ui.theme.ComponentVaultTheme
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
class InventoryPhaseOneUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val item = InventoryListItemUiState(
        id = "phase-one-part", name = "Ceramic capacitor", sku = "C-100", category = "Capacitor",
        packageName = "0603", location = "A", quantity = 10, minStock = 10, isLowStock = true,
        updatedAt = "2026-11-28T00:00:00Z",
    )
    private val component = ComponentRecord(
        id = item.id, sku = item.sku, name = item.name, category = item.category,
        packageName = item.packageName, location = item.location, description = "",
        quantity = item.quantity, minStock = item.minStock, updatedAt = item.updatedAt, deleted = false,
    )

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                MaterialTheme { block() }
            }
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun overflowInvokesTheSameExplicitActionWithoutOpeningDetail() {
        var opens = 0
        var inbound = 0
        var outbound = 0
        var transfers = 0
        var deletes = 0
        content {
            ComponentVaultTheme(darkTheme = false) {
                InventoryListRow(item, selected = false, onClick = { opens++ },
                    onInbound = { inbound++ }, onOutbound = { outbound++ },
                    onTransfer = { transfers++ }, onDelete = { deletes++ })
            }
        }
        // Issued quantity and timestamps now belong to detail, not every list row.
        compose.onNodeWithText(context.getString(R.string.inventory_usage_issued_value, item.issuedQuantity)).assertDoesNotExist()
        compose.onNodeWithText(formatShortLocalTimestamp(item.updatedAt)).assertDoesNotExist()
        val rowBounds = compose.onNodeWithTag("inventory_reveal_card").getUnclippedBoundsInRoot()
        assertTrue(rowBounds.bottom - rowBounds.top <= 112.dp, "Default phone rows must remain compact")
        val moreBounds = compose.onNodeWithTag("inventory_row_more_${item.id}").getUnclippedBoundsInRoot()
        val quantityBounds = compose.onNodeWithTag("inventory_row_quantity_${item.id}", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(moreBounds.right - moreBounds.left >= 48.dp && moreBounds.bottom - moreBounds.top >= 48.dp)
        assertTrue(moreBounds.left >= quantityBounds.right, "Overflow must be beside, not below, the quantity")
        assertTrue(moreBounds.top < quantityBounds.bottom && moreBounds.bottom > quantityBounds.top)
        val actions = listOf(R.string.inventory_quick_inbound, R.string.inventory_quick_outbound,
            R.string.inventory_quick_transfer, R.string.action_delete)
        actions.forEach { label ->
            compose.onNodeWithTag("inventory_row_more_${item.id}").performClick()
            compose.runOnIdle { assertEquals(0, opens) }
            compose.onNodeWithText(context.getString(label)).performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(1, 1, 1, 1), listOf(inbound, outbound, transfers, deletes))
            assertEquals(0, opens)
        }
    }

    @Test
    fun rowPrefersInStockLocationsAndKeepsTheAdditionalLocationSuffix() {
        val current = mutableStateOf(item.copy(location = "empty",
            inStockLocationIds = listOf("stock-b", "stock-c")))
        content {
            InventoryListRow(current.value, selected = false, onClick = {}, storageLocations = listOf(
                StorageLocationRecord("empty", "Empty drawer", item.updatedAt),
                StorageLocationRecord("stock-b", "In-stock drawer B", item.updatedAt),
                StorageLocationRecord("stock-c", "In-stock drawer C", item.updatedAt),
            ))
        }
        compose.onNodeWithTag("inventory_row_location_${item.id}", useUnmergedTree = true)
            .assertContentDescriptionEquals("In-stock drawer B +1")
        compose.onNodeWithText("Empty drawer").assertDoesNotExist()
        compose.onNodeWithText(" +1").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(inStockLocationIds = emptyList()) }
        compose.onNodeWithTag("inventory_row_location_${item.id}", useUnmergedTree = true)
            .assertContentDescriptionEquals("Empty drawer")
        compose.onNodeWithText(" +1").assertDoesNotExist()
    }

    @Test
    fun selectionHasSelectedSemanticsAndNoOverflow() {
        content {
            InventoryListRow(item, selected = true, selectionMode = true, onClick = {},
                onInbound = {}, onOutbound = {}, onTransfer = {}, onDelete = {})
        }
        compose.onNode(hasText(item.name) and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag("inventory_row_more_${item.id}").assertDoesNotExist()
    }

    @Test
    fun loadingSnapshotDoesNotDiscardSelectionButLoadedFiltersStillPruneIt() {
        val current = mutableStateOf(InventoryScreenUiState(list = InventoryListUiState(items = listOf(item))))
        content {
            InventoryContent(contentPadding = PaddingValues(), uiState = current.value, statusMessage = "",
                layoutMode = InventoryLayoutMode(InventoryWidthClass.Compact, supportsListDetail = false,
                    formPresentation = InventoryFormPresentation.FullScreenRoute,
                    secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute),
                onQueryChange = {}, onStockFilterChange = {}, onCategoryChange = {}, onLocationChange = {},
                onSortChange = {}, onSelectComponent = {}, onOpenComponentDetail = {}, onImportComponent = {},
                onGenerateLabel = {}, onEditComponent = {}, onRequestDeleteComponent = {}, onRecordMovement = {},
                onPrintSelectedComponents = {})
        }
        compose.onNodeWithText(context.getString(R.string.inventory_ui_select)).performClick()
        compose.onNodeWithTag("inventory_list").performScrollToNode(hasText(item.name))
        compose.onNodeWithText(item.name).performClick()
        compose.runOnIdle { current.value = current.value.copy(isLoaded = false, list = InventoryListUiState()) }
        compose.waitForIdle()
        compose.runOnIdle {
            current.value = current.value.copy(isLoaded = true, list = InventoryListUiState(items = listOf(item)))
        }
        compose.onNodeWithTag("inventory_list").performScrollToNode(
            hasText(context.getString(R.string.batch_transfer_selected_count, 1)))
        compose.onNodeWithText(context.getString(R.string.batch_transfer_selected_count, 1)).assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(list = InventoryListUiState()) }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.batch_transfer_selected_count, 0)).assertIsDisplayed()
    }

    @Test
    fun loadingHeaderDoesNotClampTheInventoryScrollPosition() {
        val items = List(40) { index -> item.copy(id = "part-$index", name = "Scroll row $index") }
        val loaded = InventoryScreenUiState(list = InventoryListUiState(items = items))
        val current = mutableStateOf(loaded)
        content {
            InventoryContent(contentPadding = PaddingValues(), uiState = current.value, statusMessage = "",
                layoutMode = InventoryLayoutMode(InventoryWidthClass.Compact, false,
                    InventoryFormPresentation.FullScreenRoute, InventorySecondaryPanePresentation.FullScreenRoute),
                onQueryChange = {}, onStockFilterChange = {}, onCategoryChange = {}, onLocationChange = {},
                onSortChange = {}, onSelectComponent = {}, onOpenComponentDetail = {}, onImportComponent = {},
                onGenerateLabel = {}, onEditComponent = {}, onRequestDeleteComponent = {}, onRecordMovement = {})
        }
        compose.onNodeWithTag("inventory_list").performScrollToIndex(29)
        compose.onNodeWithText("Scroll row 28").assertIsDisplayed()
        compose.runOnIdle { current.value = InventoryScreenUiState(isLoaded = false) }
        compose.onNodeWithText("Scroll row 28").assertDoesNotExist()
        compose.runOnIdle { current.value = loaded }
        compose.onNodeWithText("Scroll row 28").assertIsDisplayed()
    }

    @Test
    fun shortLargeTextDetailKeepsAllTypedActionsScrollableAndBoundToTheSameId() {
        val requests = mutableListOf<Pair<String, InventoryDetailAction>>()
        var genericMovements = 0
        content {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                Box(Modifier.width(320.dp).height(280.dp)) {
                    InventoryDetailPane(InventoryDetailUiState(component = component),
                        onEditComponent = {}, onGenerateLabel = {}, onRequestDeleteComponent = {},
                        onRecordMovement = { genericMovements++ },
                        onDetailAction = { id, action -> requests += id to action })
                }
            }
        }
        val actions = listOf("inbound" to InventoryDetailAction.Inbound,
            "outbound" to InventoryDetailAction.Outbound, "transfer" to InventoryDetailAction.Transfer)
        actions.forEach { (tag, _) ->
            compose.onNodeWithTag("inventory_detail_list")
                .performScrollToNode(hasTestTag("inventory_detail_$tag"))
            val button = compose.onNodeWithTag("inventory_detail_$tag").assertIsDisplayed()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            button.performClick()
        }
        compose.runOnIdle {
            assertEquals(actions.map { component.id to it.second }, requests)
            assertEquals(0, genericMovements)
        }
    }

    @Test
    fun defaultNarrowDetailKeepsStockActionsInOneRowAndGroupsEditWithLabel() {
        var edited: String? = null
        var labelled: ComponentRecord? = null
        content {
            Box(Modifier.width(320.dp)) {
                InventoryTabletDetailSummary(component,
                    onEditComponent = { edited = it }, onGenerateLabel = { labelled = it },
                    onRequestDeleteComponent = {}, onRecordMovement = {}, onDetailAction = { _, _ -> })
            }
        }
        val bounds = listOf("inbound", "outbound", "transfer").map {
            compose.onNodeWithTag("inventory_detail_$it").assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        assertTrue(bounds.all { it.top == bounds.first().top }, "Default stock actions must use one row")
        compose.onNodeWithTag("inventory_detail_edit").performClick()
        compose.onNodeWithTag("inventory_detail_label").performClick()
        compose.runOnIdle { assertEquals(component.id, edited); assertEquals(component, labelled) }
    }

    @Test
    fun phoneFieldGridUsesTwoColumnsAndFallsBackToOneAtLargeFontScale() {
        val scale = mutableStateOf(1f)
        val longValue = "Full specification value ".repeat(6)
        content {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale.value)) {
                Box(Modifier.width(320.dp)) {
                    InventoryDetailFieldGrid(listOf("SKU" to "C-100", "Package" to "0603", "Notes" to longValue))
                }
            }
        }
        val sku = compose.onNodeWithText("SKU").getUnclippedBoundsInRoot()
        val pkg = compose.onNodeWithText("Package").getUnclippedBoundsInRoot()
        assertEquals(sku.top, pkg.top)
        assertTrue(pkg.left > sku.right)
        compose.onNodeWithText(longValue).assertIsDisplayed()
        compose.runOnIdle { scale.value = 2f }
        val largeSku = compose.onNodeWithText("SKU").getUnclippedBoundsInRoot()
        val largePackage = compose.onNodeWithText("Package").getUnclippedBoundsInRoot()
        assertEquals(largeSku.left, largePackage.left)
        assertTrue(largePackage.top > largeSku.bottom)
        compose.onNodeWithText(longValue).assertExists()
    }

    @Test
    fun detailNotesHideOnlyStandalonePhotoReferences() {
        val pngReference = "本地图片：${"a".repeat(64)}.png"
        val jpgReference = "本地图片：${"b".repeat(64)}.jpg"
        val notes = "Inspect before soldering.\n封装：0603\n参考本地图片：${"c".repeat(64)}.png"
        val description = "$pngReference\n$notes\n$jpgReference"
        val current = mutableStateOf(component.copy(description = description))
        var labelDescription: String? = null
        content {
            InventoryDetailPane(
                detail = InventoryDetailUiState(component = current.value),
                onEditComponent = {},
                onGenerateLabel = { labelDescription = it.description },
                onRequestDeleteComponent = {},
                onRecordMovement = {},
            )
        }
        val detailList = compose.onNodeWithTag("inventory_detail_list")
        detailList.performScrollToNode(hasText(notes))
        compose.onNodeWithText(notes).assertIsDisplayed()
        compose.onNodeWithText(pngReference, substring = true).assertDoesNotExist()
        compose.onNodeWithText(jpgReference, substring = true).assertDoesNotExist()

        detailList.performScrollToNode(hasTestTag("inventory_detail_label"))
        compose.onNodeWithTag("inventory_detail_label").performClick()
        val noDescription = context.getString(R.string.label_no_description)
        compose.runOnIdle {
            assertEquals(description, labelDescription)
            current.value = component.copy(description = "\n$pngReference\n")
        }
        detailList.performScrollToNode(hasText(noDescription))
        compose.onNodeWithText(noDescription).assertIsDisplayed()
    }

    @Test
    fun actualCostDistinguishesUnknownFromZeroAndKeepsReferencePriceSeparate() {
        val price = ReferencePriceSnapshot("1.25", "CNY", 100,
            "https://example.com/component", "2026-11-28T00:00:00Z")
        val current = mutableStateOf(component.copy(
            description = ReferencePriceNotes.merge(emptyList(), price).joinToString("\n")))
        content {
            InventoryDetailPane(InventoryDetailUiState(component = current.value),
                onEditComponent = {}, onGenerateLabel = {}, onRequestDeleteComponent = {}, onRecordMovement = {})
        }
        val list = compose.onNodeWithTag("inventory_detail_list")
        list.performScrollToNode(hasText(context.getString(R.string.inventory_actual_cost_title)))
        compose.onAllNodesWithText(context.getString(R.string.inventory_cost_unknown)).assertCountEquals(2)
        compose.runOnIdle { current.value = current.value.copy(inventoryUnitCost = "0") }
        compose.onNodeWithText(context.getString(R.string.inventory_cost_unknown)).assertDoesNotExist()
        compose.onNode(hasText("0") and hasAnyAncestor(hasTestTag("inventory_detail_actual_cost"))).assertExists()
        compose.onNode(hasText("0.00") and hasAnyAncestor(hasTestTag("inventory_detail_actual_cost"))).assertExists()
        list.performScrollToNode(hasText(context.getString(R.string.catalog_reference_price)))
        compose.onNodeWithText("CNY 1.25", substring = true).assertIsDisplayed()
    }
}
