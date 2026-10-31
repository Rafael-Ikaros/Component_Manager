package com.componentvault.android.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.componentvault.android.ui.theme.ComponentVaultTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.componentvault.android.model.StorageLocationRecord
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
    fun captureStorageLocationCreateDialogLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                LocationEditorDialog(LocationEditor(), false, {}, { _, _, _ -> })
            }
        }

        saveScreenshot("storage-locations-create-dialog-light.png") {
            captureView(::latestDialogDecorView)
        }
    }

    @Test
    fun captureStorageLocationEditDialogLight() {
        compose.setContent {
            ComponentVaultTheme(darkTheme = false) {
                LocationEditorDialog(
                    LocationEditor("BIN-A01", "常用元件抽屉", isEditing = true),
                    false, {}, { _, _, _ -> },
                )
            }
        }

        saveScreenshot("storage-locations-edit-dialog-light.png") {
            captureView(::latestDialogDecorView)
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
