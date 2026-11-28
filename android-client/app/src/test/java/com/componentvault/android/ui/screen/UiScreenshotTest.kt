package com.componentvault.android.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.componentvault.android.R
import com.componentvault.android.data.AppearanceAccent
import com.componentvault.android.data.AppearanceMode
import com.componentvault.android.data.AppearancePreferences
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.StorageLocationSaveIntent
import com.componentvault.android.ui.theme.ComponentVaultTheme
import com.componentvault.android.ui.theme.LocalAppearance
import com.componentvault.android.ui.theme.vaultColorScheme
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [28],
    application = Application::class,
    qualifiers = "zh-rCN",
)
class UiScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun captureImportWorkflowChooserLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                AddComponentEntrySheet({}, {}, {}, {})
            }
        }

        saveScreenshot("import-workflow-chooser-light.png") {
            captureView(::latestDialogDecorView)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureBomTaskChooserLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                BomImportScreen(InventoryViewModel(context), {})
            }
        }

        saveScreenshot("bom-task-chooser-light.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureBomFileStageLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                BomImportScreen(InventoryViewModel(context), {}, BomImportMode.Bom)
            }
        }

        saveScreenshot("bom-file-stage-light.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureMigrationFileStageLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                BomImportScreen(InventoryViewModel(context), {}, BomImportMode.Migration)
            }
        }

        saveScreenshot("migration-file-stage-light.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureStorageLocationListLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                StorageLocationsScreen(sampleLocations, {}, { _, _, _, _ -> }, { _, _ -> })
            }
        }

        saveScreenshot("storage-locations-list-light.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureStorageLocationCreatePageLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                StorageLocationsScreen(emptyList(), {}, { _, _, _, _ -> }, { _, _ -> })
            }
        }
        compose.onNodeWithTag("locations_add").performClick()
        compose.onNodeWithTag("locations_code").assertIsDisplayed()

        saveScreenshot("storage-locations-create-page-light.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureStorageLocationEditPageLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                StorageLocationsScreen(sampleLocations, {}, { _, _, _, _ -> }, { _, _ -> })
            }
        }
        compose.onNodeWithTag("locations_edit_BIN-A01").performClick()
        compose.onNodeWithTag("locations_name").assertIsDisplayed()

        saveScreenshot("storage-locations-edit-page-light.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-normal-port-xhdpi")
    fun captureStorageLocationListDark() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = true) {
                StorageLocationsScreen(sampleLocations, {}, { _, _, _, _ -> }, { _, _ -> })
            }
        }

        saveScreenshot("storage-locations-list-dark.png") {
            captureView { compose.activity.window.decorView }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h850dp-normal-port-xhdpi")
    fun capturePhaseOneSettingsLight() {
        showPhaseOneFixture(dark = false, accent = AppearanceAccent.Teal)
        openTab(R.string.destination_settings)
        compose.onNodeWithText(context.getString(R.string.appearance_title)).assertIsDisplayed()
        saveScreenshot("phase-one-settings-light.png") { captureView { compose.activity.window.decorView } }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h760dp-normal-port-xhdpi")
    fun capturePhaseOneInventoryNarrow() {
        showPhaseOneFixture(dark = false, accent = AppearanceAccent.Blue)
        openTab(R.string.destination_inventory)
        compose.onNodeWithText(context.getString(R.string.shell_add_import)).assertIsDisplayed()
        saveScreenshot("phase-one-inventory-narrow.png") { captureView { compose.activity.window.decorView } }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h850dp-normal-port-xhdpi")
    fun capturePhaseOneBlueNavigationDark() {
        showPhaseOneFixture(dark = true, accent = AppearanceAccent.Blue)
        openTab(R.string.destination_settings)
        compose.onNodeWithTag("settings_group_Appearance").assertIsDisplayed()
        saveScreenshot("phase-one-settings-mist-blue-dark.png") {
            val bitmap = captureView { compose.activity.window.decorView }
            val bottomHeight = (96 * context.resources.displayMetrics.density).toInt()
            val pixels = IntArray(bitmap.width * bottomHeight)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, bitmap.height - bottomHeight, bitmap.width, bottomHeight)
            val indicator = vaultColorScheme(darkTheme = true, accent = AppearanceAccent.Blue).primaryContainer.toArgb()
            assertTrue(indicator in pixels, "Bottom navigation must render the active accent container")
            bitmap
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h850dp-normal-port-xhdpi")
    fun capturePhaseOneDetailDark() {
        showPhaseOneFixture(dark = true, accent = AppearanceAccent.Teal)
        openTab(R.string.destination_inventory)
        compose.onNodeWithText("AMS1117-3.3").assertIsDisplayed().performClick()
        compose.onNodeWithTag("inventory_detail_list").assertIsDisplayed()
        saveScreenshot("phase-one-detail-dark.png") { captureView { compose.activity.window.decorView } }
    }

    private fun showPhaseOneFixture(dark: Boolean, accent: AppearanceAccent) {
        val repository = InventoryRepository(context)
        runBlocking {
            sampleLocations.take(3).forEach { location ->
                val result = repository.saveStorageLocation(location.id, location.name, StorageLocationSaveIntent.Create)
                assertTrue(result.isSuccess, result.message)
            }
            listOf("NE555DR", "4.7 kΩ ±1%", "USB Type-C 16P", "SS14", "100 nF · 50 V", "10 kΩ ±1%", "AMS1117-3.3")
                .forEachIndexed { index, name ->
                    val part = ComponentDraft(
                        sku = "CV-FIXTURE-${index + 1}", name = name, category = "IC",
                        packageName = if (index == 6) "SOT-223" else "0603",
                        location = sampleLocations[index % 3].id, description = "布局验证用合成元件",
                        quantity = listOf(5, 120, 4, 0, 86, 240, 3)[index], minStock = 10,
                    )
                    val result = repository.saveComponent(part)
                    assertTrue(result.isSuccess, result.message)
                }
        }
        val model = InventoryViewModel(context)
        val appearance = AppearancePreferences(if (dark) AppearanceMode.Dark else AppearanceMode.Light, accent)
        compose.setContent {
            CompositionLocalProvider(LocalAppearance provides appearance) {
                ComponentVaultTheme(darkTheme = dark, accent = accent) { ComponentVaultApp(model) }
            }
        }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            model.uiState.inventory.isLoaded || model.startupFailure != null
        }
        kotlin.test.assertNull(model.startupFailure)
    }

    private fun openTab(label: Int) {
        compose.onNodeWithContentDescription(context.getString(label), useUnmergedTree = true).performClick()
    }

    private fun captureView(viewProvider: () -> View): Bitmap {
        var captured: Bitmap? = null
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                val view = viewProvider()
                check(view.width > 0 && view.height > 0) {
                    "View has not been laid out: ${view.width}x${view.height}"
                }
                captured = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    view.draw(Canvas(bitmap))
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        return requireNotNull(captured)
    }

    private fun latestDialogDecorView(): View {
        val managerClass = Class.forName("android.view.WindowManagerGlobal")
        val manager = managerClass.getDeclaredMethod("getInstance").invoke(null)
        val viewsField = managerClass.getDeclaredField("mViews").apply { isAccessible = true }
        val activityDecor = compose.activity.window.decorView
        return (viewsField.get(manager) as? List<*>)
            .orEmpty()
            .filterIsInstance<View>()
            .lastOrNull { it !== activityDecor }
            ?: error("No dialog window was attached to WindowManagerGlobal.")
    }

    private fun saveScreenshot(fileName: String, capture: () -> Bitmap) {
        val output = File(screenshotDirectory(), fileName)
        output.parentFile?.mkdirs()
        val bitmap = capture()
        assertContainsRenderedPixels(bitmap)
        FileOutputStream(output).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(output.isFile && output.length() > 0, "Screenshot was not written: ${output.absolutePath}")
    }

    private fun assertContainsRenderedPixels(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val firstOpaque = pixels.firstOrNull { it ushr 24 != 0 }
        assertTrue(firstOpaque != null, "Screenshot contains no opaque pixels.")
        assertTrue(
            pixels.any { it ushr 24 != 0 && it != firstOpaque },
            "Screenshot contains only one opaque color; UI content was not rendered.",
        )
    }

    private fun screenshotDirectory(): File {
        val moduleDirectory = listOf(File("."), File("app"), File("android-client/app"))
            .firstOrNull { File(it, "build.gradle.kts").isFile }
            ?: error("Cannot locate the Android app module from ${File(".").absolutePath}")
        return File(moduleDirectory, "build/reports/ui-screenshots")
    }

    private companion object {
        val sampleLocations = listOf(
            StorageLocationRecord("BIN-A01", "常用元件抽屉", "2026-09-16T08:00:00Z"),
            StorageLocationRecord("BIN-A02", "贴片电阻与电容", "2026-09-16T08:00:00Z"),
            StorageLocationRecord("SHELF-B", "开发板与工具", "2026-09-16T08:00:00Z"),
            StorageLocationRecord("CABINET-C", "连接器与线材", "2026-09-16T08:00:00Z"),
            StorageLocationRecord("ARCHIVE-D", "低频使用库存", "2026-09-16T08:00:00Z"),
        )
    }
}
