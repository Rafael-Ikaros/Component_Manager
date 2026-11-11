package com.componentvault.android.ui.screen

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.componentvault.android.R
import com.componentvault.android.data.LabelPrintItemState
import com.componentvault.android.data.LabelMediaType
import com.componentvault.android.data.LabelPrinterProfile
import com.componentvault.android.data.LabelDesign
import com.componentvault.android.data.LabelElement
import com.componentvault.android.data.LabelElementType
import com.componentvault.android.data.M1TestPaperProfile
import com.componentvault.android.data.LabelPrintQueue
import com.componentvault.android.data.LabelPrintQueueStore
import com.componentvault.android.data.LabelPrinterPreferences
import com.componentvault.android.data.SavedLabelPrinter
import com.componentvault.android.model.ComponentLabelSeed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BluetoothLabelPrintUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun setLabelContent(content: @Composable () -> Unit) {
        compose.setContent {
            ProvideComponentVaultStrings(runtimeComponentVaultStrings()) { content() }
        }
    }

    private fun waitForPrintablePreview() {
        compose.onNodeWithTag("label_print_open").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(
                hasText(context.getString(R.string.bluetooth_label_print_create)) and isEnabled(),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun previouslySavedCpclPrinterShowsOneGenericProtocolAndCanBeForgotten() {
        LabelPrintQueueStore(context).clear()
        val preferences = LabelPrinterPreferences(context.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE))
        val address = "00:11:22:33:44:55"
        preferences.save(
            listOf(SavedLabelPrinter(address, "Saved label printer",
                LabelPrinterProfile.HmA300eCpcl.id, LabelMediaType.Gap.id)),
            address, LabelPrinterProfile.HmA300eCpcl.id, LabelMediaType.Gap.id,
        )
        val seed = ComponentLabelSeed("C393939", "TYPE-C16PIN", "Connector", "SMD", "A", 10, 1)
        setLabelContent { MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("label_print_open") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("label_print_open").performClick()
        val createLabel = context.getString(R.string.bluetooth_label_print_create)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(createLabel) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("label_print_device").performScrollTo().performClick()
        compose.onNodeWithText("Saved label printer").assertExists()
        assertTrue(compose.onAllNodesWithText(
            context.getString(R.string.label_flow_printer_unavailable), substring = true,
        ).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(context.getString(R.string.label_cpcl_generic)).assertIsSelected()
        compose.onNodeWithText("HM-A300E CPCL").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.label_flow_forget_printer)).performScrollTo().performClick()
        compose.onNodeWithText("Saved label printer", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        compose.onNodeWithText(createLabel).assertIsEnabled()
        compose.runOnIdle { assertTrue(preferences.load().isEmpty()) }
    }

    @Test
    fun draggingAnElementContinuesAcrossMultipleMovesAndRecompositions() {
        val paper = M1TestPaperProfile(40f, 60f)
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        var design by mutableStateOf(LabelDesign(listOf(
            LabelElement("name", LabelElementType.Text, "Part", 1f, 1f, 12f, 6f),
        )))
        setLabelContent {
            MaterialTheme {
                LabelEditorCanvas(bitmap, design, paper, "name", true,
                    onSelect = {}, onMove = { id, x, y -> design = design.moved(id, x, y, paper) })
            }
        }
        compose.onNodeWithTag("label_element_name").performTouchInput {
            down(center)
            moveBy(Offset(32f, 0f))
            moveBy(Offset(32f, 0f))
            up()
        }
        compose.runOnIdle { assertTrue(design.elements.single().xMm > 3f) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun replacingPaperPreviewKeepsTheDisplayedBitmapDrawable() {
        LabelPrintQueueStore(context).clear()
        val seed = ComponentLabelSeed("C393939", "TYPE-C16PIN", "Connector", "SMD", "A", 10, 1)
        setLabelContent {
            MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("print_label_preview").fetchSemanticsNodes().isNotEmpty() }
        for (height in listOf("61", "60", "62", "60")) {
            compose.onNodeWithTag("label_editor_paper").performClick()
            compose.onNodeWithTag("label_paper_height").performScrollTo().performTextReplacement(height)
            compose.onNodeWithTag("label_paper_apply").performClick()
            waitForPrintablePreview()
            compose.onNodeWithTag("print_label_preview").assertIsDisplayed()
            // Robolectric has no device PixelCopy callback. Draw through the same
            // native Canvas path used by the existing UI screenshot regressions.
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val snapshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(snapshot))
                    val pixels = IntArray(snapshot.width * snapshot.height)
                    snapshot.getPixels(pixels, 0, snapshot.width, 0, 0, snapshot.width, snapshot.height)
                    assertTrue(pixels.any { it ushr 24 != 0 }, "Preview page did not draw")
                } finally {
                    snapshot.recycle()
                }
            }
        }
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun editedLabelCanBeRepairedAfterAnOutOfBoundsPosition() {
        LabelPrintQueueStore(context).clear()
        val seed = ComponentLabelSeed("EDIT-1", "Editable part", "Connector", "SMD", "A", 10, 1)
        setLabelContent { MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("label_element_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("label_element_name").performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_content)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_editor_text))
            .performScrollTo().performTextReplacement("Custom display")
        compose.onNodeWithTag("label_content_done").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_advanced)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_editor_x))
            .performScrollTo().performTextReplacement("999")
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        compose.onNodeWithTag("label_print_open").performClick()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_create)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_editor_x))
            .performScrollTo().performTextReplacement("1")
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        waitForPrintablePreview()
        LabelPrintQueueStore(context).clear()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun emptyFreeQrKeepsEditableCanvasAndExplainsMissingContent() {
        LabelPrintQueueStore(context).clear()
        setLabelContent {
            MaterialTheme { BluetoothLabelPrintScreen(emptyList(), null,
                initialToolTemplate = LabelToolTemplate.Qr, onDismiss = {}) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("label_element_qr").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("print_label_preview").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.label_ui_enter_qr)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.label_ui_empty_qr)).assertIsDisplayed()
        compose.onNodeWithTag("label_element_qr").performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_content)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_tool_qr_payload))
            .performScrollTo().performTextReplacement("https://example.test/qr")
        compose.onNodeWithTag("label_content_done").assertIsDisplayed().performClick()
        waitForPrintablePreview()
        LabelPrintQueueStore(context).clear()
    }

    @Test
    fun emptyFreeTextShowsLocalizedElementCaption() {
        LabelPrintQueueStore(context).clear()
        setLabelContent {
            MaterialTheme { BluetoothLabelPrintScreen(emptyList(), null,
                initialToolTemplate = LabelToolTemplate.Text, onDismiss = {}) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("label_element_text").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.label_ui_empty_text)).assertIsDisplayed()
        LabelPrintQueueStore(context).clear()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun positionSheetCanSelectAFieldWithoutVisiblePrintContent() {
        LabelPrintQueueStore(context).clear()
        setLabelContent {
            MaterialTheme { BluetoothLabelPrintScreen(emptyList(), null,
                initialToolTemplate = LabelToolTemplate.Qr, onDismiss = {}) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("label_element_qr").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithTag("label_select_qr").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.label_ui_advanced)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_editor_x)).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.label_editor_x)).assertIsDisplayed()
        LabelPrintQueueStore(context).clear()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allIncompleteGeometryFieldsMustBeRepairedBeforeQueueCreation() {
        LabelPrintQueueStore(context).clear()
        val seed = ComponentLabelSeed("EDIT-2", "Connector", "Connector", "SMD", "A", 10, 1)
        setLabelContent { MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("label_element_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("label_element_name").performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_advanced)).performClick()
        val x = compose.onNodeWithText(context.getString(R.string.label_editor_x))
        val y = compose.onNodeWithText(context.getString(R.string.label_editor_y))
        x.performScrollTo().performTextReplacement("")
        y.performScrollTo().performTextReplacement("")
        y.performScrollTo().performTextReplacement("1")
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        compose.onNodeWithTag("label_print_open").performClick()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_create)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_editor_x)).performScrollTo().performTextReplacement("1")
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        waitForPrintablePreview()
        LabelPrintQueueStore(context).clear()
    }

    @Test
    fun restoredHundredLabelQueueCanReviewLastItemAndReturnToParent() {
        val selection = (1..100).map { index ->
            ComponentLabelSeed(
                sku = "UI-$index",
                name = "元件 $index",
                category = "IC",
                packageName = "SOT-23",
                location = "A",
                quantity = index,
                minStock = 1,
            ) to 1
        }
        val original = LabelPrintQueue.create(selection, printerProfileId = LabelPrinterProfile.GenericCpcl.id)
        val last = original.items.last()
        LabelPrintQueueStore(context).save(original.update(last.id, LabelPrintItemState.Uncertain, "print_check_label"))
        val visible = mutableStateOf(true)
        setLabelContent {
            MaterialTheme {
                if (visible.value) BluetoothLabelPrintScreen(emptyList(), null, onDismiss = { visible.value = false })
                else Text("库存父页面")
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("print_queue_list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("print_queue_list").performScrollToNode(hasTestTag("print_queue_item_${last.id}"))
        compose.onNodeWithTag("print_queue_item_${last.id}").assertIsDisplayed()
        compose.onNodeWithTag("print_queue_start").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_review)).performClick()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_retry)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_mark_printed)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.label_flow_print_confirmed), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("库存父页面").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        LabelPrintQueueStore(context).clear()
    }
    @Test
    fun labelToolOpensSelectedTemplateDirectly() {
        var selected: LabelToolTemplate? = null
        setLabelContent {
            MaterialTheme {
                LabelToolTemplatePicker(onSelect = { selected = it }, onBack = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.label_tool_text)).performClick()
        compose.runOnIdle { assertEquals(LabelToolTemplate.Text, selected) }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun newPaperReplacesRotationAndOffsetsFromAnEmptySavedQueue() {
        val oldPaper = M1TestPaperProfile(40f, 60f, 90, 48f, -100f)
        LabelPrintQueueStore(context).save(LabelPrintQueue(paper = oldPaper))
        val seed = ComponentLabelSeed("ROT-NEW", "Resistor", "Passive", "0603", "A", 1, 0)
        try {
            setLabelContent {
                MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed,
                    initialPaper = M1TestPaperProfile(40f, 60f), onDismiss = {}) }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("label_element_name").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("label_print_open") and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.label_ui_paper_summary,
                    "40.0", "60.0", "0")).fetchSemanticsNodes().isNotEmpty()
            }
            // The saved 48 mm / -100 mm offsets would clip the raster and block queue creation.
            waitForPrintablePreview()
        } finally {
            LabelPrintQueueStore(context).clear()
        }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun newComponentLabelResetsRotationButKeepsSavedPaperAndCalibration() {
        val savedPaper = M1TestPaperProfile(40f, 60f, 90, 0.5f, -0.25f)
        val store = LabelPrintQueueStore(context)
        store.save(LabelPrintQueue(paper = savedPaper))
        val seed = ComponentLabelSeed("ROT-COMPONENT", "Resistor", "Passive", "0603", "A", 1, 0)
        try {
            setLabelContent {
                MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("label_element_name").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.label_ui_paper_summary,
                "40.0", "60.0", "0")).assertExists()
            waitForPrintablePreview()
            compose.onNodeWithTag("label_print_open").performClick()
            compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_create)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("print_queue_list").fetchSemanticsNodes().isNotEmpty()
            }
            val expectedPaper = savedPaper.copy(rotationDegrees = 0)
            compose.waitUntil(10_000) { store.load().paper == expectedPaper }
            compose.runOnIdle {
                assertEquals(expectedPaper, store.load().paper)
            }
        } finally {
            store.clear()
        }
    }
    @Test
    fun emptySavedQueueWithoutNewLabelIntentKeepsRotation() {
        val store = LabelPrintQueueStore(context)
        store.save(LabelPrintQueue(paper = M1TestPaperProfile(40f, 60f, 90)))
        try {
            setLabelContent {
                MaterialTheme { BluetoothLabelPrintScreen(emptyList(), null, onDismiss = {}) }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("label_print_open").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.label_ui_paper_summary,
                    "40.0", "60.0", "90")).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            store.clear()
        }
    }
    @Test
    fun restoredQueueKeepsItsSavedRotationWhenNewPaperWasChosen() {
        val seed = ComponentLabelSeed("ROT-90", "Saved label", "IC", "SMD", "A", 1, 0)
        LabelPrintQueueStore(context).save(LabelPrintQueue.create(listOf(seed to 1),
            paper = M1TestPaperProfile(40f, 60f, 90)))
        try {
            setLabelContent {
                MaterialTheme { BluetoothLabelPrintScreen(emptyList(), null,
                    initialPaper = M1TestPaperProfile(30f, 20f), onDismiss = {}) }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("print_queue_list").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.label_ui_paper_summary,
                "40.0", "60.0", "90")).assertExists()
        } finally {
            LabelPrintQueueStore(context).clear()
        }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun componentTextTemplateUsesTheExistingEditorWithoutQr() {
        LabelPrintQueueStore(context).clear()
        val seed = ComponentLabelSeed("TEXT-1", "Resistor", "Passive", "0603", "A", 2, 0)
        setLabelContent {
            MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed,
                initialToolTemplate = LabelToolTemplate.ComponentText,
                initialPaper = M1TestPaperProfile(40f, 30f), onDismiss = {}) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("label_element_text").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("print_label_preview").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("label_element_qr").fetchSemanticsNodes().isEmpty())
        LabelPrintQueueStore(context).clear()
    }
    @Test
    fun customPaperRequiresSupportedCanvasRangeBeforeApplying() {
        var selectedPaper: M1TestPaperProfile? = null
        setLabelContent {
            MaterialTheme {
                LabelPaperPickerSheet(
                    paper = M1TestPaperProfile(40f, 60f),
                    media = LabelMediaType.Gap,
                    profile = LabelPrinterProfile.GenericCpcl,
                    onApply = { value, _ -> selectedPaper = value },
                    onDismiss = {},
                )
            }
        }
        val width = compose.onNodeWithTag("label_paper_width")
        val apply = compose.onNodeWithTag("label_paper_apply")
        width.performScrollTo().performTextReplacement("60")
        apply.assertIsEnabled()
        width.performScrollTo().performTextReplacement("73")
        apply.assertIsNotEnabled()
        width.performScrollTo().performTextReplacement("35")
        compose.onNodeWithTag("label_paper_height").performScrollTo().performTextReplacement("25")
        apply.performClick()
        compose.runOnIdle { assertEquals(M1TestPaperProfile(35f, 25f), selectedPaper) }
    }
}
