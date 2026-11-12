package com.componentvault.android.ui.screen

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.componentvault.android.R
import com.componentvault.android.data.LabelPrintItemState
import com.componentvault.android.data.LabelMediaType
import com.componentvault.android.data.LabelPaperPresetStore
import com.componentvault.android.data.LabelPrinterProfile
import com.componentvault.android.data.LabelDesign
import com.componentvault.android.data.LabelElement
import com.componentvault.android.data.LabelElementType
import com.componentvault.android.data.M1TestPaperProfile
import com.componentvault.android.data.LabelPrintQueue
import com.componentvault.android.data.LabelPrintOffset
import com.componentvault.android.data.LabelPrintQueueStore
import com.componentvault.android.data.LabelPrinterPreferences
import com.componentvault.android.data.SavedLabelPaper
import com.componentvault.android.data.SavedLabelPrinter
import com.componentvault.android.model.ComponentLabelSeed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Robolectric's BluetoothDevice shadow references BluetoothAddress, which is absent on API 28.
@Implements(BluetoothDevice::class)
class PairedLabelPrinterShadow {
    var advertisedName = ""

    @Implementation
    protected fun getName() = advertisedName

    @Implementation
    protected fun getType() = BluetoothDevice.DEVICE_TYPE_CLASSIC

    @Implementation
    protected fun getBondState() = BluetoothDevice.BOND_BONDED
}

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

    private fun openEditor() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("label_print_edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("label_print_edit").performScrollTo().performClick()
    }

    private fun waitForPrintableConfirmation() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("label_print_submit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForPrintablePreview() {
        compose.onNodeWithTag("label_print_open").performClick()
        waitForPrintableConfirmation()
        openEditor()
    }

    private fun pairTestPrinters(vararg printers: Pair<String, String>) {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(printers.map { (address, name) ->
            adapter.getRemoteDevice(address).also {
                Shadow.extract<PairedLabelPrinterShadow>(it).advertisedName = name
            }
        }.toSet())
    }

    @Test
    @Config(shadows = [PairedLabelPrinterShadow::class])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun knownPrintersSelectTheirProtocolAndOtherDevicesRequireAChoice() {
        LabelPrintQueueStore(context).clear()
        val m1 = "00:11:22:33:44:01" to "M1-6870"
        val hm = "00:11:22:33:44:02" to "HM-A300-8EDA"
        val other = "00:11:22:33:44:03" to "HM-A300ER-8EDA"
        val p1 = "00:11:22:33:44:05" to "P1"
        pairTestPrinters(m1, hm, other, p1)
        val preferences = LabelPrinterPreferences(context.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE))
        val seed = ComponentLabelSeed("TEST-CPCL", "Resistor", "Passive", "0603", "A", 1, 0)
        setLabelContent { MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) } }
        waitForPrintableConfirmation()
        compose.onNodeWithTag("label_print_device").performScrollTo().performClick()
        val m1Chip = compose.onNodeWithTag("label_print_protocol_m1")
        val cpclChip = compose.onNodeWithTag("label_print_protocol_cpcl-spp")
        fun chooseDevice(name: String) = compose.onNodeWithText(name).performScrollTo().performClick()
        fun closePicker() = compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        chooseDevice(m1.second)
        m1Chip.assertIsSelected()
        chooseDevice(hm.second)
        cpclChip.assertIsSelected()
        chooseDevice(other.second)
        m1Chip.assertIsNotSelected().assertIsEnabled()
        cpclChip.assertIsNotSelected().assertIsEnabled()
        closePicker()
        compose.onNodeWithTag("label_print_submit").assertIsNotEnabled()
        compose.onNodeWithTag("label_print_device").performScrollTo().performClick()
        m1Chip.performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        m1Chip.assertIsSelected()
        compose.runOnIdle {
            assertEquals("m1", preferences.load().single { it.address == other.first }.profileId)
        }
        chooseDevice(hm.second)
        cpclChip.assertIsSelected()
        chooseDevice(other.second)
        m1Chip.assertIsSelected()
        chooseDevice(p1.second)
        val p1Chip = compose.onNodeWithTag("label_print_protocol_detonger-p1")
        p1Chip.performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        p1Chip.assertIsSelected()
        chooseDevice(hm.second)
        cpclChip.assertIsSelected()
        chooseDevice(p1.second)
        p1Chip.assertIsSelected()
        closePicker()
        compose.runOnIdle {
            assertEquals(
                mapOf(m1.first to "m1", hm.first to "cpcl-spp", other.first to "m1", p1.first to "detonger-p1"),
                preferences.load().associate { it.address to it.profileId },
            )
        }
    }

    @Test
    @Config(shadows = [PairedLabelPrinterShadow::class])
    fun restoredCpclQueueCanUseNewDeviceAfterManualCompatibleChoice() {
        val seed = ComponentLabelSeed("TEST-QUEUE", "Resistor", "Passive", "0603", "A", 1, 0)
        val store = LabelPrintQueueStore(context)
        val queue = LabelPrintQueue.create(listOf(seed to 1), printerProfileId = LabelPrinterProfile.HmA300eCpcl.id)
        store.save(queue)
        try {
            pairTestPrinters("00:11:22:33:44:04" to "Label printer")
            setLabelContent { MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("print_queue_start").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.label_flow_printer_details)).performClick()
            compose.onNodeWithText("Label printer").performScrollTo().performClick()
            compose.onNodeWithTag("label_print_protocol_m1").assertIsNotEnabled()
            val cpclChip = compose.onNodeWithTag("label_print_protocol_cpcl-spp")
            cpclChip.assertIsNotSelected().assertIsEnabled()
            cpclChip.performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
            compose.onNodeWithTag("print_queue_start").assertIsEnabled()
            compose.runOnIdle { assertEquals(queue, store.load()) }
        } finally {
            store.clear()
        }
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
        waitForPrintableConfirmation()
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
        openEditor()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("print_label_preview").fetchSemanticsNodes().isNotEmpty() }
        for (height in listOf("61", "60", "62", "60")) {
            compose.onNodeWithTag("label_editor_paper").performClick()
            compose.onNodeWithTag("label_paper_custom_tab").performClick()
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
        openEditor()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("label_element_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("label_element_name").performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_content)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_editor_text))
            .performScrollTo().performTextReplacement("Custom display")
        compose.onNodeWithTag("label_content_done").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_advanced)).performClick()
        val x = compose.onNodeWithText(context.getString(R.string.label_editor_x)).performScrollTo()
        val originalX = x.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        x.performTextReplacement("999")
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        compose.onNodeWithTag("label_print_open").performClick()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_create)).assertIsNotEnabled()
        openEditor()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        x.performScrollTo().performTextReplacement(originalX)
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
        val back = compose.onNodeWithContentDescription(context.getString(R.string.action_back))
        back.performClick()
        waitForPrintableConfirmation()
        back.performClick()
        compose.onNodeWithTag("label_element_qr").assertIsDisplayed()
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
        openEditor()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("label_element_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("label_element_name").performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        compose.onNodeWithText(context.getString(R.string.label_ui_advanced)).performClick()
        val x = compose.onNodeWithText(context.getString(R.string.label_editor_x)).performScrollTo()
        val originalX = x.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val y = compose.onNodeWithText(context.getString(R.string.label_editor_y))
        x.performScrollTo().performTextReplacement("")
        y.performScrollTo().performTextReplacement("")
        y.performScrollTo().performTextReplacement("1")
        compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        compose.onNodeWithTag("label_print_open").performClick()
        compose.onNodeWithText(context.getString(R.string.bluetooth_label_print_create)).assertIsNotEnabled()
        openEditor()
        compose.onNodeWithText(context.getString(R.string.label_ui_position)).performClick()
        x.performScrollTo()
        assertEquals("", x.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        x.performTextReplacement(originalX)
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
            waitForPrintableConfirmation()
            compose.onNodeWithText(context.getString(R.string.label_ui_paper_summary,
                "40.0", "60.0", "0"), substring = true).assertExists()
            // The old queue's 48 mm / -100 mm offsets must not clip a new label.
            compose.onNodeWithTag("label_print_submit").assertIsEnabled()
        } finally {
            LabelPrintQueueStore(context).clear()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun componentStartsInConfirmationWithDirectCopiesAndSeparateCalibration() {
        val store = LabelPrintQueueStore(context)
        val seed = ComponentLabelSeed("ROT-COMPONENT", "Resistor", "Passive", "0603", "A", 1, 0)
        val previous = LabelPrintQueue.create(
            listOf(seed.copy(sku = "OLD-LABEL") to 1), paper = M1TestPaperProfile(40f, 60f, 90, 0.5f, -0.25f),
        )
        store.save(previous.update(previous.items.single().id, LabelPrintItemState.Sent))
        try {
            setLabelContent {
                MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) }
            }
            waitForPrintableConfirmation()
            compose.onNodeWithTag("label_print_open").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.label_ui_paper_summary,
                "40.0", "60.0", "0"), substring = true).assertExists()

            compose.onNodeWithTag("label_print_copies").performScrollTo().performClick()
            compose.onNodeWithTag("label_copies_decrease").assertIsNotEnabled()
            compose.onNodeWithTag("label_copies_increase").performClick()
            compose.onNodeWithTag("label_copies_value").assertTextContains("2")
            compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()

            openEditor()
            compose.onNodeWithTag("label_element_name").assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
            compose.onNodeWithTag("label_print_calibration").performScrollTo().performClick()
            compose.onNodeWithTag("label_calibration_x").performScrollTo()
                .assertTextContains(LabelPrintOffset.Default.xMm.toString()).performTextReplacement("0")
            compose.onNodeWithTag("label_calibration_apply").performScrollTo().performClick()

            waitForPrintableConfirmation()
            compose.onNodeWithTag("label_print_submit").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("print_queue_list").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                val queue = store.load()
                assertEquals(M1TestPaperProfile(40f, 60f), queue.paper)
                assertEquals(listOf(seed.sku, seed.sku), queue.items.map { it.seed?.sku })
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
        openEditor()
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
        val presets = LabelPaperPresetStore(context)
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
        compose.onNodeWithTag("label_paper_custom_tab").performClick()
        val width = compose.onNodeWithTag("label_paper_width")
        val apply = compose.onNodeWithTag("label_paper_apply")
        width.performScrollTo().performTextReplacement("60")
        apply.assertIsEnabled()
        width.performScrollTo().performTextReplacement("73")
        apply.assertIsEnabled()
        width.performScrollTo().performTextReplacement("301")
        apply.assertIsNotEnabled()
        width.performScrollTo().performTextReplacement("35")
        compose.onNodeWithTag("label_paper_height").performScrollTo().performTextReplacement("25")
        apply.performClick()
        compose.runOnIdle {
            assertEquals(M1TestPaperProfile(35f, 25f), selectedPaper)
            assertTrue(presets.load().isEmpty())
        }
        compose.onNodeWithTag("label_paper_name").performScrollTo().performTextReplacement("Shelf labels")
        compose.onNodeWithTag("label_paper_save_apply").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SavedLabelPaper("Shelf labels", 35f, 25f, LabelMediaType.Gap)), presets.load())
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun paperPresetUsesCurrentComponentAndRestoresRotationAndBothOffsets() {
        val presets = LabelPaperPresetStore(context)
        val paper = M1TestPaperProfile(40f, 60f, 90, 0.5f, -0.25f)
        presets.save("P1 paper", paper)
        val seed = ComponentLabelSeed("CURRENT", "Current resistor", "Passive", "0603", "A", 1, 0)
        val queue = LabelPrintQueueStore(context)
        setLabelContent { MaterialTheme { BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) } }
        waitForPrintableConfirmation()
        compose.onNodeWithTag("label_print_paper").performScrollTo().performClick()
        compose.onNodeWithText("P1 paper").performScrollTo().performClick()
        compose.onNodeWithTag("label_paper_apply").performClick()
        waitForPrintableConfirmation()
        compose.onNodeWithTag("label_print_submit").performClick()
        compose.waitUntil(10_000) { queue.load().items.isNotEmpty() }
        assertEquals(paper, queue.load().paper)
        assertEquals(seed, queue.load().items.single().seed)
        assertEquals("Current resistor", queue.load().items.single().design!!.elements.first { it.id == "name" }.text)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inventorySelectionCreatesOneLabelForEachCurrentComponent() {
        val first = com.componentvault.android.model.ComponentRecord("one", "C100", "Resistor", "Passive",
            "0603", "A", "", 100, 1, "", false)
        val second = first.copy(id = "two", sku = "C200", name = "Capacitor", quantity = 200)
        val deleted = first.copy(id = "deleted", sku = "C300", deleted = true)
        val store = LabelPrintQueueStore(context)
        setLabelContent {
            MaterialTheme { BluetoothLabelPrintScreen(listOf(first, second, deleted), null,
                initialPaper = M1TestPaperProfile(40f, 60f),
                initialComponentIds = listOf("one", "two", "deleted", "missing"), onDismiss = {}) }
        }
        waitForPrintableConfirmation()
        compose.onNodeWithTag("label_print_submit").performClick()
        compose.waitUntil(10_000) { store.load().items.size == 2 }
        val items = store.load().items
        assertEquals(listOf("C100", "C200"), items.map { it.seed!!.sku })
        assertEquals(listOf("Resistor", "Capacitor"), items.map {
            it.design!!.elements.first { element -> element.id == "name" }.text
        })
        assertTrue(items.all { it.copyNumber == 1 && it.copies == 1 })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun applyingPaperPersistsAlignmentWithoutPrintingAndRestoresOnReopen() {
        val shown = mutableStateOf(true)
        val seed = ComponentLabelSeed("DRAFT", "Resistor", "Passive", "0603", "A", 1, 0)
        val store = LabelPaperPresetStore(context)
        setLabelContent {
            MaterialTheme { if (shown.value) BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {}) }
        }
        waitForPrintableConfirmation()
        compose.onNodeWithTag("label_print_paper").performScrollTo().performClick()
        compose.onNodeWithText("270°").performScrollTo().performClick()
        compose.onNodeWithTag("label_paper_offset_x").performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("label_paper_offset_y").performScrollTo().performTextReplacement("-1")
        compose.onNodeWithTag("label_paper_apply").performClick()
        val expected = M1TestPaperProfile(40f, 60f, 270, 1f, -1f)
        compose.waitUntil(10_000) { store.last() == expected }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = true }
        waitForPrintableConfirmation()
        compose.onNodeWithText(context.getString(R.string.label_ui_paper_summary,
            "40.0", "60.0", "270"), substring = true).assertExists()
        assertEquals(expected, store.last())
        assertTrue(LabelPrintQueueStore(context).load().items.isEmpty())
    }

    @Test
    @Config(shadows = [PairedLabelPrinterShadow::class])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun reopeningAndSwitchingPrintersNeverCopiesGlobalPaperOffsetsIntoDeviceCalibration() {
        val m1 = "00:11:22:33:44:71" to "M1-6870"
        val p1 = "00:11:22:33:44:72" to "P1-test"
        pairTestPrinters(m1, p1)
        val preferences = LabelPrinterPreferences(context.getSharedPreferences("m1_label_print", Context.MODE_PRIVATE))
        preferences.save(listOf(
            SavedLabelPrinter(m1.first, m1.second, LabelPrinterProfile.M1.id, LabelMediaType.Gap.id),
            SavedLabelPrinter(p1.first, p1.second, LabelPrinterProfile.DetongerP1.id, LabelMediaType.Gap.id),
        ), m1.first, LabelPrinterProfile.M1.id, LabelMediaType.Gap.id)
        val m1Offset = LabelPrintOffset(0.5f, -0.5f)
        val p1Offset = LabelPrintOffset(2f, 1f)
        preferences.saveOffset(m1.first, m1Offset)
        preferences.saveOffset(p1.first, p1Offset)
        val papers = LabelPaperPresetStore(context)
        papers.saveLast(M1TestPaperProfile(40f, 60f, 90, 99f, 99f))
        papers.save("Explicit offset", M1TestPaperProfile(40f, 60f, 90, 1f, -1f))
        val visible = mutableStateOf(true)
        val seed = ComponentLabelSeed("CALIBRATION", "Resistor", "Passive", "0603", "A", 1, 0)
        setLabelContent { MaterialTheme {
            if (visible.value) BluetoothLabelPrintScreen(emptyList(), seed, onDismiss = {})
        } }
        fun expectOffset(offset: LabelPrintOffset) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.label_confirmation_offset_summary,
                    offset.xMm, offset.yMm)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.label_ui_paper_summary,
                "40.0", "60.0", "90"), substring = true).assertExists()
            assertEquals(m1Offset, preferences.loadOffset(m1.first))
            assertEquals(p1Offset, preferences.loadOffset(p1.first))
        }
        fun reopen() {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
        }
        fun selectDevice(name: String) {
            compose.onNodeWithTag("label_print_device").performScrollTo().performClick()
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.label_ui_close)).performScrollTo().performClick()
        }
        expectOffset(m1Offset)
        selectDevice(p1.second)
        expectOffset(p1Offset)
        reopen()
        expectOffset(p1Offset)
        selectDevice(m1.second)
        expectOffset(m1Offset)
        reopen()
        expectOffset(m1Offset)
        compose.onNodeWithTag("label_print_paper").performScrollTo().performClick()
        compose.onNodeWithText("Explicit offset").performScrollTo().performClick()
        compose.onNodeWithTag("label_paper_apply").performClick()
        compose.waitUntil(10_000) { preferences.loadOffset(m1.first) == LabelPrintOffset(1f, -1f) }
        assertEquals(p1Offset, preferences.loadOffset(p1.first))
    }
}
